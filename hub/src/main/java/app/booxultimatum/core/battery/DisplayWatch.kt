package app.booxultimatum.core.battery

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import app.booxultimatum.core.BatteryLog
import app.booxultimatum.kit.log.Logbook
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Notices a display that stays ON while the tablet isn't interactive, which is how the drain of 2026-10-08 and again
 * of 2026-10-12 looked (the display went ON exactly 90 minutes after the tablet slept and was never dozed again, at
 * 300 to 350 mA with the processor unable to suspend, until the owner woke it). Boox's dream refresh turns the display
 * ON for about 1.3 s, so an episode is reported only when it lasts: `display_on` after 2 minutes, `display_stuck` after
 * 10. The sleep guard runs from the second minute, and again at each of [SleepGuardPolicy.ROUND_MINUTES] and hourly
 * after, for as long as the display stays on: the single attempt it made on 2026-10-12 failed and nothing tried again.
 *
 * A Handler drives it, and the live sleep alarm calls [recheck] every few minutes too, so a round is never lost to a
 * timer that did not run. Everything runs on the main thread.
 */
object DisplayWatch {
    private const val DISPLAY_ON_AFTER_MS = 2 * 60_000L
    private const val STUCK_AFTER_MS = 10 * 60_000L
    private const val POLL_MS = 60_000L
    private val started = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val log = Logbook.logger("battery")

    /** When the open episode began, or null outside one. */
    private var onsetMs: Long? = null

    /** What the open episode has logged and run so far. */
    private var loggedOn = false
    private var loggedStuck = false
    private var rounds = 0

    private var poll: Runnable? = null

    /** Once per process. Checks at once too, in case the process starts during an episode. */
    fun start(app: Context) {
        if (!started.compareAndSet(false, true)) return
        app.getSystemService(DisplayManager::class.java).registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) recheck(app)
            }
        }, handler)
        recheck(app)
    }

    /** Called on each change of the default display, on the screen's on and off broadcasts and by the live sleep alarm. */
    internal fun recheck(app: Context) {
        if (!stuck(app)) {
            end()
            return
        }
        if (onsetMs == null) begin()
        due(app)
    }

    /** The default display's state, or null when it can't be read. */
    internal fun displayState(app: Context): Int? = runCatching {
        app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.state
    }.getOrNull()

    private fun stuck(app: Context): Boolean =
        displayState(app) == Display.STATE_ON && !app.getSystemService(PowerManager::class.java).isInteractive

    private fun begin() {
        onsetMs = System.currentTimeMillis()
        loggedOn = false
        loggedStuck = false
        rounds = 0
    }

    /** Logs and runs whatever the time since the onset has made due, then arms the next look. */
    private fun due(app: Context) {
        val onset = onsetMs ?: return
        val elapsed = System.currentTimeMillis() - onset
        if (!loggedOn && elapsed >= DISPLAY_ON_AFTER_MS) {
            loggedOn = true
            BatteryLog.record(app, "display_on", note = onset.toString())
        }
        if (!loggedStuck && elapsed >= STUCK_AFTER_MS) {
            loggedStuck = true
            BatteryLog.record(app, "display_stuck", note = onset.toString())
            log.w("display stuck on while asleep", "since" to onset)
            runCatching { BatteryEvents.displayStuck(app, onset) }
        }
        // One round for however many are due: a check that came late is not a burst of ladders.
        val dueRounds = SleepGuardPolicy.roundsDue(elapsed)
        if (dueRounds > rounds) {
            rounds = dueRounds
            runCatching { BatteryEvents.guardRound(app, onset, dueRounds - 1) }
        }
        arm(app)
    }

    private fun arm(app: Context) {
        poll?.let { handler.removeCallbacks(it) }
        val next = Runnable { recheck(app) }
        poll = next
        handler.postDelayed(next, POLL_MS)
    }

    private fun end() {
        poll?.let { handler.removeCallbacks(it) }
        poll = null
        onsetMs = null
        loggedOn = false
        loggedStuck = false
        rounds = 0
    }
}
