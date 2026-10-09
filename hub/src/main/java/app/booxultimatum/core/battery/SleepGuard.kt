package app.booxultimatum.core.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.view.Display
import app.booxultimatum.core.BatteryLog
import app.booxultimatum.core.exec.Privileged
import app.booxultimatum.core.sleep.LiveSleepService
import app.booxultimatum.kit.log.Logbook
import kotlinx.coroutines.runBlocking

enum class GuardBlock { Awake, Docked, RecentAction, RecentInput }

enum class StepOutcome { Healed, NotHealed, Skipped }

/** One rung of the ladder as it ran: its number (1 to 3) and what it did. */
data class StepResult(val step: Int, val outcome: StepOutcome)

/** The guard's rules as pure functions, so they are tested without a device. */
object SleepGuardPolicy {
    const val ACTION_GAP_MS = 20 * 60_000L
    const val INPUT_GAP_MS = 60_000L

    /** `BatteryManager.EXTRA_PLUGGED` in the dock (BATTERY_PLUGGED_DOCK, API 33). AC, USB and wireless charging don't count. */
    const val PLUGGED_DOCK = 8

    fun isDock(plugged: Int): Boolean = plugged == PLUGGED_DOCK

    /** The rule that stops the guard acting now, the first that applies; null when it may act. */
    fun block(interactive: Boolean, docked: Boolean, lastActionAgoMs: Long?, msSinceInput: Long?): GuardBlock? = when {
        interactive -> GuardBlock.Awake
        docked -> GuardBlock.Docked
        lastActionAgoMs != null && lastActionAgoMs in 0L until ACTION_GAP_MS -> GuardBlock.RecentAction
        msSinceInput != null && msSinceInput in 0L until INPUT_GAP_MS -> GuardBlock.RecentInput
        else -> null
    }

    fun shouldAct(interactive: Boolean, docked: Boolean, lastActionAgoMs: Long?, msSinceInput: Long?): Boolean =
        block(interactive, docked, lastActionAgoMs, msSinceInput) == null

    /** The step that healed the display (the first whose outcome is Healed), or null when none did. */
    fun outcome(steps: List<StepResult>): Int? = steps.firstOrNull { it.outcome == StepOutcome.Healed }?.step
}

/**
 * The sleep guard (docs §4.8, Experimental): a display left on while the tablet sleeps is put back to sleep, by the least
 * drastic step first. Every step is logged with its result, so the log shows which step, if any, healed it.
 */
object SleepGuard {
    private const val DREAM_REFRESH = "onyx_dream_refresh"
    private const val KEY_SLEEP = 223
    private const val LAST_STEP = 3
    private const val REFRESH_WAIT_MS = 15_000L
    private const val SETTLE_WAIT_MS = 5_000L
    private const val WAKE_LOCK_MS = 90_000L
    private val log = Logbook.logger("battery")

    /** Called once the display has been on while asleep for 10 minutes. Returns at once; the ladder runs on its own thread. */
    fun onStuck(context: Context, sinceMs: Long) {
        val app = context.applicationContext
        if (!BatteryAlerts.guardEnabled(app)) return
        Thread({ holdAndClimb(app, sinceMs) }, "battery-guard").apply { isDaemon = true }.start()
    }

    private fun holdAndClimb(app: Context, sinceMs: Long) {
        val wake = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BooxUltimatum:guard")
        wake.acquire(WAKE_LOCK_MS)
        try {
            runCatching { climb(app, sinceMs) }.onFailure { log.e("sleep guard failed", error = it) }
        } finally {
            if (wake.isHeld) wake.release()
        }
    }

    private fun climb(app: Context, sinceMs: Long) {
        if (!stillOn(app)) return
        val startedAt = System.currentTimeMillis()
        val lastActionAgo = BatteryAlerts.lastGuard(app)?.let { startedAt - it.atMs }
        val steps = mutableListOf<StepResult>()
        for (step in 1..LAST_STEP) {
            val held = SleepGuardPolicy.block(awake(app), docked(app), if (step == 1) lastActionAgo else null, msSinceInput(app))
            if (held != null) {
                log.i("sleep guard held back", "step" to step, "reason" to held.name)
                BatteryLog.record(app, "guard", note = "step$step held back: ${held.name}")
                break
            }
            if (!attempt(app, step)) {
                steps += record(app, step, StepOutcome.Skipped)
                continue
            }
            Thread.sleep(if (step == 1) REFRESH_WAIT_MS else SETTLE_WAIT_MS)
            val healed = !stillOn(app)
            steps += record(app, step, if (healed) StepOutcome.Healed else StepOutcome.NotHealed)
            if (healed) break
        }
        if (steps.isNotEmpty()) BatteryAlerts.saveGuard(app, GuardRecord(startedAt, sinceMs, SleepGuardPolicy.outcome(steps)))
    }

    /** Runs one step. False when it can't run here: the lock needs the accessibility service, the key needs Shizuku. */
    private fun attempt(app: Context, step: Int): Boolean = when (step) {
        1 -> {
            app.sendBroadcast(Intent(DREAM_REFRESH))
            true
        }
        2 -> LiveSleepService.lockScreen()
        else -> Privileged.ready() && runBlocking { Privileged.sh("input keyevent $KEY_SLEEP") }.code == 0
    }

    private fun record(app: Context, step: Int, outcome: StepOutcome): StepResult {
        val word = when (outcome) {
            StepOutcome.Healed -> "healed"
            StepOutcome.NotHealed -> "not healed"
            StepOutcome.Skipped -> "skipped"
        }
        BatteryLog.record(app, "guard", note = "step$step $word")
        log.w("sleep guard step", "step" to step, "result" to word)
        return StepResult(step, outcome)
    }

    /** Still on, or not readable: an unknown state counts as still on, so no step is ever logged as healing on a guess. */
    private fun stillOn(app: Context): Boolean = DisplayWatch.displayState(app).let { it == null || it == Display.STATE_ON }

    private fun awake(app: Context): Boolean = app.getSystemService(PowerManager::class.java).isInteractive

    private fun docked(app: Context): Boolean {
        val plugged = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return SleepGuardPolicy.isDock(plugged)
    }

    private fun msSinceInput(app: Context): Long? = BatteryAlerts.lastWakeMs(app)?.let { System.currentTimeMillis() - it }
}
