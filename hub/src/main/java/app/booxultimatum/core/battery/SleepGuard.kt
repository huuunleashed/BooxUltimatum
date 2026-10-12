package app.booxultimatum.core.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.view.Display
import app.booxultimatum.R
import app.booxultimatum.core.BatteryLog
import app.booxultimatum.core.exec.Privileged
import app.booxultimatum.core.sleep.LiveSleepService
import app.booxultimatum.kit.log.Logbook
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

enum class GuardBlock { Awake, Docked, RecentAction, RecentInput }

enum class StepOutcome { Healed, NotHealed, Skipped }

/** One rung of the ladder as it ran: its id (see [GuardStep]) and what it did. */
data class StepResult(val step: Int, val outcome: StepOutcome)

/**
 * The rungs of the ladder. The ids are stored in the log and in the last-action record, so they never change: 1 to 3 are
 * the original rungs, 4 and 5 were added in 0.9.2 and sit between them in [SleepGuardPolicy.LADDER].
 */
enum class GuardStep(val id: Int) {
    /** `onyx_dream_refresh`, the broadcast Boox's own dream answers by waking the display and dozing again. */
    Refresh(1),

    /** The accessibility service's lock-screen action. */
    Lock(2),

    /** With Shizuku: wake key then sleep key. */
    Key(3),

    /** Says "display on" (`DISPLAY_CHANGED_STATE`, state 2) to the dream, which only re-enters doze when it hears that. */
    Nudge(4),

    /** Wakes the tablet with a wake lock and locks it again, so it goes to sleep from the top and the dream restarts. */
    Cycle(5);

    companion object {
        /** The string that says what the step with this stored [id] did, for the report and the Alerts plate. */
        fun label(id: Int): Int = when (id) {
            Refresh.id -> R.string.ba_step_refresh
            Lock.id -> R.string.ba_step_lock
            Nudge.id -> R.string.ba_step_nudge
            Cycle.id -> R.string.ba_step_cycle
            else -> R.string.ba_step_key
        }
    }
}

/** The guard's rules as pure functions, so they are tested without a device. */
object SleepGuardPolicy {
    /** Between two guard actions of separate episodes: a display that flaps on and off is not hammered. */
    const val ACTION_GAP_MS = 5 * 60_000L
    const val INPUT_GAP_MS = 60_000L

    /** `BatteryManager.EXTRA_PLUGGED` in the dock (BATTERY_PLUGGED_DOCK, API 33). AC, USB and wireless charging don't count. */
    const val PLUGGED_DOCK = 8

    /** Least drastic first. The steps that need Shizuku or the accessibility service say so when they can't run. */
    val LADDER = listOf(GuardStep.Refresh, GuardStep.Nudge, GuardStep.Lock, GuardStep.Cycle, GuardStep.Key)

    /** Minutes after the display went on at which a round of the ladder runs; after the last, one round every [ROUND_EVERY_MIN]. */
    val ROUND_MINUTES = listOf(2, 5, 10, 20, 40)
    const val ROUND_EVERY_MIN = 60

    fun isDock(plugged: Int): Boolean = plugged == PLUGGED_DOCK

    /** When round [round] (counted from 0) is due, in minutes after the display went on. */
    fun roundDueMin(round: Int): Int =
        if (round < ROUND_MINUTES.size) ROUND_MINUTES[round] else ROUND_MINUTES.last() + (round - ROUND_MINUTES.size + 1) * ROUND_EVERY_MIN

    /** How many rounds are due [elapsedMs] after the display went on, so a late check runs one round and not a burst. */
    fun roundsDue(elapsedMs: Long): Int {
        var n = 0
        while (roundDueMin(n) * 60_000L <= elapsedMs) n++
        return n
    }

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
 * The sleep guard (docs §4.8): a display left on while the tablet sleeps is put back to sleep, by the least drastic step
 * first. [DisplayWatch] calls it in rounds while the display stays on, so a step that didn't work is tried again
 * minutes later (and, given the evidence of 2026-10-12, the whole ladder is tried until the display does go off).
 * Every step is logged with its result, so the log shows which step, if any, healed it.
 */
object SleepGuard {
    private const val DREAM_REFRESH = "onyx_dream_refresh"
    private const val ONYX_DISPLAY_STATE = "onyx.action.DISPLAY_CHANGED_STATE"
    private const val ONYX_PACKAGE = "com.onyx"
    private const val DISPLAY_ON = 2
    private const val KEY_WAKE = 224
    private const val KEY_SLEEP = 223
    private const val WAKE_LOCK_MS = 120_000L
    private const val CYCLE_WAKE_WAIT_MS = 3_000L
    private val log = Logbook.logger("battery")
    private val running = AtomicBoolean(false)

    /** Until when a wake of the tablet is the guard's own: the morning report and the "last input" mustn't take it for the owner's. */
    @Volatile private var selfWakeUntilMs = 0L

    /** True while a screen-on is the guard's own doing. */
    fun causedWake(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs < selfWakeUntilMs

    /**
     * One round: the ladder, once. Returns at once; the ladder runs on a thread of its own, and a round is skipped while
     * the previous one is still running. [round] counts from 0 within an episode.
     */
    fun onStuck(context: Context, sinceMs: Long, round: Int = 0) {
        val app = context.applicationContext
        if (!BatteryAlerts.guardEnabled(app)) return
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                holdAndClimb(app, sinceMs, round)
            } finally {
                running.set(false)
            }
        }, "battery-guard").apply { isDaemon = true }.start()
    }

    private fun holdAndClimb(app: Context, sinceMs: Long, round: Int) {
        val wake = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BooxUltimatum:guard")
        wake.acquire(WAKE_LOCK_MS)
        try {
            runCatching { climb(app, sinceMs, round) }.onFailure { log.e("sleep guard failed", error = it) }
        } finally {
            if (wake.isHeld) wake.release()
        }
    }

    private fun climb(app: Context, sinceMs: Long, round: Int) {
        if (!stillOn(app)) return
        val startedAt = System.currentTimeMillis()
        // The gap only separates episodes: later rounds of one episode are already minutes apart by the schedule.
        val lastActionAgo = if (round == 0) BatteryAlerts.lastGuard(app)?.let { startedAt - it.atMs } else null
        val steps = mutableListOf<StepResult>()
        for ((index, step) in SleepGuardPolicy.LADDER.withIndex()) {
            val held = SleepGuardPolicy.block(awake(app), docked(app), if (index == 0) lastActionAgo else null, msSinceInput(app))
            if (held != null) {
                log.i("sleep guard held back", "step" to step.id, "round" to round, "reason" to held.name)
                BatteryLog.record(app, "guard", note = "r$round step${step.id} held back: ${held.name}")
                break
            }
            if (!attempt(app, step)) {
                steps += record(app, round, step, StepOutcome.Skipped)
                continue
            }
            Thread.sleep(settleMs(step))
            val healed = !stillOn(app)
            steps += record(app, round, step, if (healed) StepOutcome.Healed else StepOutcome.NotHealed)
            if (healed) break
        }
        if (steps.isNotEmpty()) BatteryAlerts.saveGuard(app, GuardRecord(startedAt, sinceMs, SleepGuardPolicy.outcome(steps)))
    }

    private fun settleMs(step: GuardStep): Long = when (step) {
        GuardStep.Refresh -> 15_000L
        GuardStep.Cycle -> 10_000L
        GuardStep.Key -> 8_000L
        else -> 6_000L
    }

    /** Runs one step. False when it can't run here: the lock and the cycle need the accessibility service, the key needs Shizuku. */
    private fun attempt(app: Context, step: GuardStep): Boolean = when (step) {
        GuardStep.Refresh -> {
            app.sendBroadcast(Intent(DREAM_REFRESH))
            true
        }
        GuardStep.Nudge -> {
            // The receiver is Boox's own, registered exported, and the action is not a protected broadcast (checked in the
            // framework manifest, 2026-10-12): the dream starts its doze timer when it hears state 2 and never otherwise.
            app.sendBroadcast(Intent(ONYX_DISPLAY_STATE).setPackage(ONYX_PACKAGE).putExtra("state", DISPLAY_ON))
            true
        }
        GuardStep.Lock -> LiveSleepService.lockScreen()
        GuardStep.Cycle -> cycle(app)
        GuardStep.Key -> Privileged.ready() &&
            runBlocking { Privileged.sh("input keyevent $KEY_WAKE; sleep 1; input keyevent $KEY_SLEEP") }.code == 0
    }

    /**
     * Wakes the tablet with a wake lock that turns the screen on, then locks it through the accessibility service, so the
     * firmware puts it to sleep from awake, the way a press of the power button does, and Boox's dream starts afresh.
     * Whether the display really goes off afterwards is what the step's logged result says *[verify]*.
     */
    @Suppress("DEPRECATION")
    private fun cycle(app: Context): Boolean {
        if (!LiveSleepService.running) return false
        val power = app.getSystemService(PowerManager::class.java)
        val lock = power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "BooxUltimatum:guard-wake")
        selfWakeUntilMs = System.currentTimeMillis() + 60_000L
        try {
            lock.acquire(CYCLE_WAKE_WAIT_MS + 2_000L)
            val deadline = System.currentTimeMillis() + CYCLE_WAKE_WAIT_MS
            while (!power.isInteractive && System.currentTimeMillis() < deadline) Thread.sleep(150)
            if (power.isInteractive) {
                Thread.sleep(600)
                LiveSleepService.lockScreen()
            }
        } finally {
            if (lock.isHeld) lock.release()
        }
        return true
    }

    private fun record(app: Context, round: Int, step: GuardStep, outcome: StepOutcome): StepResult {
        val word = when (outcome) {
            StepOutcome.Healed -> "healed"
            StepOutcome.NotHealed -> "not healed"
            StepOutcome.Skipped -> "skipped"
        }
        BatteryLog.record(app, "guard", note = "r$round step${step.id} $word")
        log.w("sleep guard step", "step" to step.id, "round" to round, "result" to word)
        return StepResult(step.id, outcome)
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
