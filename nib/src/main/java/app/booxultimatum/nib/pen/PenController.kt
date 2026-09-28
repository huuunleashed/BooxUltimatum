package app.booxultimatum.nib.pen

import app.booxultimatum.kit.ink.PenSession
import app.booxultimatum.kit.ink.PreviewStroke
import app.booxultimatum.kit.log.Logbook

/** Delayed work on the main thread; returns a handle that cancels it. Android's is a Handler, tests use a fake clock. */
fun interface PenScheduler {
    fun post(delayMs: Long, block: () -> Unit): () -> Unit
}

/**
 * Nib's policy over the display's pen session ([PenSession] in `kit:ink`), shared by the canvas and the Diagnostics
 * probes. The session is open (paused) while a pen surface is on screen with focus; it draws while the pen hovers over
 * that surface, and pauses when the pen is over anything else, while [block]ed (a panel is open, fingers move the
 * page, the eraser is chosen) and while the pen's eraser end or button is used. After each lift the surface draws its
 * frame with the stroke and calls [frameShown]; the swap follows after [extraSwapDelayMs]. A watchdog swaps anyway if
 * frames are still held [WATCHDOG_MS] after a lift. Main thread only.
 */
class PenController(
    private val session: PenSession,
    private val scheduler: PenScheduler,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val log = Logbook.logger("nib.pen")
    private val blocks = LinkedHashSet<String>()

    /** The stroke the display previews, as last asked for. */
    var preview: PreviewStroke? = null
        private set
    private var pendingPreview: PreviewStroke? = null

    /** Extra wait between the stroke's frame and the swap (Diagnostics › Swap delay). */
    var extraSwapDelayMs: Long = 0L

    /** Called after each swap with the time from lift to swap, and whether the watchdog had to do it. */
    var onSwapped: ((delayMs: Long, watchdog: Boolean) -> Unit)? = null

    var touching: Boolean = false
        private set

    /** A finished stroke waits for its frame: the surface should report [frameShown] after its next draw. */
    var awaitingFrame: Boolean = false
        private set

    private var swapQueued = false
    private var pauseAfterSwap = false
    private var liftedAt = 0L
    private var cancelExit: (() -> Unit)? = null
    private var cancelWatchdog: (() -> Unit)? = null
    private var cancelSwap: (() -> Unit)? = null

    /** Whether the display path exists at all (false on other tablets and the emulator: software-only drawing). */
    val available: Boolean get() = session.available

    /** Whether strokes are being previewed by the display right now. */
    val previewing: Boolean get() = session.state == PenSession.State.Drawing

    val state: PenSession.State get() = session.state

    /** Whether the display is holding this app's frames. */
    val holding: Boolean get() = session.holding

    fun open(panelLongSide: Int, stroke: PreviewStroke): Boolean {
        preview = stroke
        pendingPreview = null
        val ok = session.open(panelLongSide, stroke)
        log.d("session open", "ok" to ok, "state" to session.state.name, "style" to stroke.style, "width" to stroke.widthPx)
        return ok
    }

    fun close() {
        cancelTimers()
        if (session.holding) session.swap()
        session.close()
        touching = false
        awaitingFrame = false
        swapQueued = false
        pauseAfterSwap = false
    }

    /** Clears stuck preview ink and held frames, then carries on as before. */
    fun recover() {
        cancelTimers()
        awaitingFrame = false
        swapQueued = false
        pauseAfterSwap = false
        session.recover()
    }

    /** The pen hovers: over this surface or not, and with its eraser end or button or not. */
    fun hover(inside: Boolean, eraser: Boolean) {
        cancelExit?.invoke()
        cancelExit = null
        if (touching) return
        if (!inside || eraser || blocks.isNotEmpty()) pause() else session.resume()
    }

    /** The pen left the hover range, or is about to touch: pause unless a touch follows soon. */
    fun hoverExit() {
        if (touching) return
        cancelExit?.invoke()
        cancelExit = scheduler.post(EXIT_PAUSE_MS) {
            cancelExit = null
            if (!touching) pause()
        }
    }

    fun block(reason: String) {
        if (blocks.add(reason) && !touching) pause()
    }

    fun unblock(reason: String) {
        blocks.remove(reason)
    }

    val blocked: Boolean get() = blocks.isNotEmpty()

    /**
     * The pen touched this surface. Returns whether the display previews this stroke. A stroke that starts before the
     * last one was swapped keeps the frames held: letting them through now would end this stroke's preview, so one
     * swap after this lift replaces both.
     */
    fun down(): Boolean {
        cancelExit?.invoke()
        cancelExit = null
        cancelWatchdog?.invoke()
        cancelWatchdog = null
        cancelSwap?.invoke()
        cancelSwap = null
        swapQueued = false
        awaitingFrame = false
        touching = true
        session.penDown()
        return previewing
    }

    /** Whether the stroke about to start merges with one still waiting for its swap. */
    val swapPending: Boolean get() = session.holding

    /** The pen lifted (or the stroke was cancelled). The surface renders the stroke and then reports [frameShown]. */
    fun up() {
        if (!touching) return
        touching = false
        session.penUp()
        if (session.holding) {
            liftedAt = clockMs()
            awaitingFrame = true
            cancelWatchdog?.invoke()
            cancelWatchdog = scheduler.post(WATCHDOG_MS) {
                cancelWatchdog = null
                if (session.holding) {
                    log.w("frames still held after the lift; swapping", "ms" to (clockMs() - liftedAt))
                    swapNow(watchdog = true)
                }
            }
        } else {
            applyDeferred()
        }
        if (blocks.isNotEmpty()) pause()
    }

    /** The surface's frame with the finished stroke has been handed on: swap, after the extra delay. */
    fun frameShown() {
        if (!awaitingFrame) return
        awaitingFrame = false
        if (swapQueued) return
        swapQueued = true
        if (extraSwapDelayMs > 0) {
            cancelSwap = scheduler.post(extraSwapDelayMs) {
                cancelSwap = null
                swapNow(watchdog = false)
            }
        } else {
            swapNow(watchdog = false)
        }
    }

    /** A new preview stroke; applied at once unless a stroke is under way or waiting for its swap. */
    fun setPreview(stroke: PreviewStroke) {
        if (touching || session.holding) {
            pendingPreview = stroke
            return
        }
        pendingPreview = null
        if (stroke == preview) return
        preview = stroke
        session.setStroke(stroke)
    }

    /** The pen touched something other than this surface (a toolbar key): let frames through and stop previewing. */
    fun touchedOutside() {
        if (touching) return
        if (session.holding) swapNow(watchdog = false)
        session.pause()
    }

    private fun pause() {
        if (session.holding) {
            pauseAfterSwap = true
            return
        }
        session.pause()
    }

    private fun swapNow(watchdog: Boolean) {
        cancelWatchdog?.invoke()
        cancelWatchdog = null
        cancelSwap?.invoke()
        cancelSwap = null
        swapQueued = false
        awaitingFrame = false
        val wasHolding = session.holding
        session.swap()
        if (wasHolding) onSwapped?.invoke(clockMs() - liftedAt, watchdog)
        applyDeferred()
        if (pauseAfterSwap) {
            pauseAfterSwap = false
            session.pause()
        }
    }

    private fun applyDeferred() {
        pendingPreview?.let {
            pendingPreview = null
            if (it != preview) {
                preview = it
                session.setStroke(it)
            }
        }
    }

    private fun cancelTimers() {
        cancelExit?.invoke()
        cancelExit = null
        cancelWatchdog?.invoke()
        cancelWatchdog = null
        cancelSwap?.invoke()
        cancelSwap = null
    }

    companion object {
        /** A hover exit followed by a touch within this long was the pen touching down, not leaving. */
        const val EXIT_PAUSE_MS = 120L

        /** How long frames may stay held after a lift before the watchdog swaps. */
        const val WATCHDOG_MS = 1500L
    }
}
