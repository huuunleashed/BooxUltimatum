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
 * Notices a display that stays ON while the tablet isn't interactive, which is how the drain of 2026-10-08 looked. Boox's
 * dream refresh turns the display ON for about 1.3 s, so an episode is reported only when it lasts: `display_on` after 2
 * minutes, `display_stuck` after 10. A Handler and no alarm or wake lock: a stuck display keeps the CPU awake anyway.
 * Everything runs on the main thread.
 */
object DisplayWatch {
    private const val DISPLAY_ON_AFTER_MS = 2 * 60_000L
    private const val STUCK_AFTER_MS = 10 * 60_000L
    private val started = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val log = Logbook.logger("battery")
    private val timers = mutableListOf<Runnable>()

    /** When the open episode began, or null outside one. */
    private var onsetMs: Long? = null

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

    /** Called on each change of the default display and on the screen's on and off broadcasts. */
    internal fun recheck(app: Context) {
        when {
            !stuck(app) -> end()
            onsetMs == null -> begin(app)
        }
    }

    /** The default display's state, or null when it can't be read. */
    internal fun displayState(app: Context): Int? = runCatching {
        app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.state
    }.getOrNull()

    private fun stuck(app: Context): Boolean =
        displayState(app) == Display.STATE_ON && !app.getSystemService(PowerManager::class.java).isInteractive

    private fun begin(app: Context) {
        val onset = System.currentTimeMillis()
        onsetMs = onset
        for ((delay, reason) in listOf(DISPLAY_ON_AFTER_MS to "display_on", STUCK_AFTER_MS to "display_stuck")) {
            val timer = Runnable { fire(app, onset, reason) }
            timers += timer
            handler.postDelayed(timer, delay)
        }
    }

    private fun fire(app: Context, onset: Long, reason: String) {
        if (!stuck(app)) {
            end()
            return
        }
        BatteryLog.record(app, reason, note = onset.toString())
        if (reason == "display_stuck") {
            runCatching { BatteryEvents.displayStuck(app, onset) }
            log.w("display stuck on while asleep", "since" to onset)
        }
    }

    private fun end() {
        timers.forEach { handler.removeCallbacks(it) }
        timers.clear()
        onsetMs = null
    }
}
