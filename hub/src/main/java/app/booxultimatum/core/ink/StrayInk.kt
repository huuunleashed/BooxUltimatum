package app.booxultimatum.core.ink

import android.content.Context
import app.booxultimatum.kit.ink.epd.Epd
import app.booxultimatum.kit.log.Logbook
import java.util.concurrent.Executors

/**
 * Ends a pen session that nobody is drawing in. SurfaceFlinger doesn't notice when the process that opened a session
 * dies, so an app killed mid-session (an update, a crash, the system) leaves the preview drawing over every app, the
 * home screen included (seen on NA6C FW 4.3 when Nib was updated mid-session, 2026-09-28). No app draws over home, and
 * Instant ink pauses there, so a session still drawing a moment after home appears was left behind. Recovering it
 * also undoes the other display changes such a session can leave (see [InstantInk.recoverScreen]).
 */
object StrayInk {
    private val log = Logbook.logger("ink")
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "stray-ink").apply { isDaemon = true } }

    /** Checks off the main thread; home calls it a moment after it comes to the front. */
    fun check(context: Context) {
        val app = context.applicationContext
        worker.execute { runCatching { checkNow(app) }.onFailure { log.w("stray session check failed", error = it) } }
    }

    private fun checkNow(context: Context) {
        if (InstantInk.status == InstantInk.Status.Armed) return
        val route = Epd.connect(InstantInk.shizukuRoute) ?: return
        val state = Epd.penState() ?: return
        if (!Epd.PenState.isLive(state)) return
        log.w("stray pen session ended at home", "state" to state, "route" to route.name)
        InstantInk.recoverScreen(context)
    }
}
