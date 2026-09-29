package app.booxultimatum.kit.ink.canvas

import app.booxultimatum.kit.ink.session.InkSession
import app.booxultimatum.kit.ink.session.InkStroke
import app.booxultimatum.kit.log.Logbook

/** Runs [block] after [delayMs] on the caller's thread; returns a function that cancels it. */
fun interface InkScheduler {
    fun post(delayMs: Long, block: () -> Unit): () -> Unit
}

/**
 * The writing choreography of a drawing surface, shared by every suite app that draws with the display's pen preview:
 * when to preview, when the app's own pixels replace the preview, and what happens around the app's controls, finger
 * gestures and breaks. It follows BOOX's own note app (traced on the tablet and read from its code, see
 * `docs/09-ink.md`):
 *
 * - **One hold per writing session.** From the first touch the display holds the app's frames, and strokes follow each
 *   other with nothing reaching the panel in between. The app renders every stroke behind the hold.
 * - **The hold ends at breaks** ([Reveal.AtBreaks], the native behaviour): a touch on the app's controls, a panel or
 *   menu, a finger gesture, the eraser, a tool change the app reports ([releaseNow]), losing focus. Optionally also when
 *   the pen pauses ([Reveal.AfterPause]), which first tries to show the exact ink by pushing it into the display's
 *   layer ([Host.pushInk]) so the hold survives; or after every stroke ([Reveal.EveryStroke], the old behaviour).
 * - **Controls stay clear:** the app reports them with [setControls]; the display leaves all of them alone.
 * - **Controls stay current during a hold:** the app calls [controlsChanged] and the controller asks [Host.pushControls].
 * - **Finger gestures** pause the preview, and [Host.fastMode] switches the display to its fast mode until
 *   [fastModeTailMs] after the gesture.
 * - A release waits for the app's frame with every stroke so far ([frameShown]), or [FRAME_WAIT_MS] at most.
 * - After a pause, drawing resumes no sooner than [rearmMs] after it (NeoReader waits for the panel's update to finish,
 *   at least 500 ms on colour panels), unless the pen touches the canvas first. A pen touch ends fast mode at once.
 *
 * Pure Kotlin and main-thread only; timing comes from [scheduler] and [clockMs], so it's tested on the JVM.
 */
class InkCanvasController(
    val session: InkSession,
    private val scheduler: InkScheduler,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    enum class Reveal { AtBreaks, AfterPause, EveryStroke }

    /** Where the pen is when it hovers. */
    enum class Target { Canvas, Controls, Elsewhere }

    /** What the surface offers the controller; everything is optional. */
    interface Host {
        /** Pushes the page's pixels inside [dirty] (screen left, top, right, bottom) into the display's layer; true if shown. */
        fun pushInk(dirty: IntArray): Boolean = false

        /** Pushes the app's controls that changed during a hold; true if shown. */
        fun pushControls(): Boolean = false

        /** Turns the display's fast mode on for a finger gesture, or off after it. */
        fun fastMode(on: Boolean) {}
    }

    private val log = Logbook.logger("ink.canvas")
    private val blocks = LinkedHashSet<String>()

    var host: Host = object : Host {}
    var reveal: Reveal = Reveal.AtBreaks
    var pauseMs: Long = 800L
    var fastGestures: Boolean = true
    var fastModeTailMs: Long = 600L

    /** The least time between a pause and resuming, so the panel finishes the pause's update first. */
    var rearmMs: Long = 0L

    /** Called after each release with its reason and the strokes it showed; for the app's log and tests. */
    var onReleased: ((reason: String, strokes: Int) -> Unit)? = null

    var touching: Boolean = false
        private set
    private var pendingStroke: InkStroke? = null
    private var dirty: IntArray? = null
    private var frameDirty = false
    private var pendingRelease: String? = null
    private var pendingPause: String? = null
    private var fastModeOn = false
    private var openedAt = 0L
    private var cancelFrameWait: (() -> Unit)? = null
    private var cancelPauseReveal: (() -> Unit)? = null
    private var cancelFastOff: (() -> Unit)? = null
    private var cancelRearm: (() -> Unit)? = null
    private var pausedAt = Long.MIN_VALUE / 2

    val available: Boolean get() = session.available
    val previewing: Boolean get() = session.state == InkSession.State.Live
    val holding: Boolean get() = session.holding
    val blocked: Boolean get() = blocks.isNotEmpty()
    val stroke: InkStroke? get() = pendingStroke ?: session.stroke

    /** How long the session has been open, for the per-stroke log. */
    val msSinceOpen: Long get() = if (openedAt == 0L) -1L else clockMs() - openedAt

    /** Opens the session, drawing at once unless something blocks it. Returns whether the display path exists. */
    fun open(stroke: InkStroke, panelLongSide: Int, limit: IntArray? = null): Boolean {
        pendingStroke = null
        val ok = session.open(stroke, panelLongSide, limit, live = blocks.isEmpty())
        openedAt = clockMs()
        return ok
    }

    fun close() {
        cancelTimers()
        fastModeOff()
        session.close()
        touching = false
        frameDirty = false
        pendingRelease = null
        pendingPause = null
        dirty = null
    }

    /** Clears stuck preview ink and held frames, then carries on as before. */
    fun recover() {
        cancelTimers()
        frameDirty = false
        pendingRelease = null
        pendingPause = null
        dirty = null
        session.recover()
    }

    /** A new pen, width or colour: applied at once, or after the stroke under way. */
    fun setStroke(s: InkStroke) {
        if (touching) { pendingStroke = s; return }
        pendingStroke = null
        session.setStroke(s)
    }

    /** The app's controls and panels over the canvas, as screen rectangles; the preview stays off all of them. */
    fun setControls(rects: List<IntArray>) = session.setExclusions(rects)

    /** Where the preview may draw, as screen rectangles; null for everywhere. */
    fun setLimit(rects: IntArray?) = session.setLimit(rects)

    /**
     * The pen hovers over [target]; [eraser] when its eraser end or button is in use, which blocks the preview until it
     * isn't (a block, so a touch with the eraser end can't resume it). An app that lets the display preview the eraser
     * end ([InkSession.setPenButtons]) passes false.
     */
    fun hover(target: Target, eraser: Boolean = false) {
        if (touching) return
        if (eraser) block(ERASER_END) else unblock(ERASER_END)
        when {
            blocks.isNotEmpty() -> Unit
            target == Target.Canvas -> resumeWhenSettled()
            // The controls are excluded from the preview, so the session goes on drawing and the next stroke on the
            // canvas needs no resume (a resume sometimes lost a quick stroke's start on the tablet).
            else -> Unit
        }
    }

    /** The pen touched the canvas. Returns whether the display previews this stroke. */
    fun down(): Boolean {
        cancelPauseReveal?.invoke(); cancelPauseReveal = null
        if (fastModeOn) fastModeOff()
        if (blocks.isEmpty() && session.state == InkSession.State.Paused) { cancelRearm?.invoke(); cancelRearm = null; session.resume() }
        touching = true
        session.penDown()
        return previewing
    }

    /** The pen lifted from the canvas; [bounds] is the stroke's screen rectangle (left, top, right, bottom), if known. */
    fun up(bounds: IntArray? = null) {
        if (!touching) return
        touching = false
        val held = session.holding
        session.penUp()
        pendingStroke?.let { pendingStroke = null; session.setStroke(it) }
        if (held) {
            frameDirty = true
            bounds?.let { b -> dirty = dirty?.let { d -> intArrayOf(minOf(d[0], b[0]), minOf(d[1], b[1]), maxOf(d[2], b[2]), maxOf(d[3], b[3])) } ?: b.copyOf() }
            when (reveal) {
                Reveal.EveryStroke -> requestRelease("stroke")
                Reveal.AfterPause -> cancelPauseReveal = scheduler.post(pauseMs) { cancelPauseReveal = null; revealAfterPause() }
                Reveal.AtBreaks -> Unit
            }
        }
        if (blocks.isNotEmpty()) requestPause(blocks.first())
    }

    /** The app's frame with every finished stroke so far has been drawn. */
    fun frameShown() {
        frameDirty = false
        cancelFrameWait?.invoke(); cancelFrameWait = null
        runPending()
    }

    /** The pen or a finger touched the app's controls: let the frames through so the control can show it responded. */
    fun controlsTouched() {
        if (touching) return
        requestRelease("controls")
    }

    /** That touch ended. The display may have held frames for it, so they're let through again. */
    fun controlsTouchEnded() {
        if (touching || !session.available) return
        if (!frameDirty) session.flush("controls touch ended")
    }

    /** Something on the app's controls changed during a hold (undo became possible, a size changed). */
    fun controlsChanged() {
        if (session.holding && !touching) host.pushControls()
    }

    /** A break the app decides (undo, a tool change, a selection): the app's own pixels go on screen. */
    fun releaseNow(reason: String) = requestRelease(reason)

    fun block(reason: String) {
        if (!blocks.add(reason)) return
        if (!touching) requestPause(reason)
    }

    fun unblock(reason: String) {
        blocks.remove(reason)
    }

    /** A finger gesture on the canvas (pan, zoom, turn) began. */
    fun gestureStarted() {
        cancelFastOff?.invoke(); cancelFastOff = null
        block(GESTURE)
        if (fastGestures && !fastModeOn) { fastModeOn = true; host.fastMode(true) }
    }

    /** The gesture ended: fast mode stays briefly in case another follows, then the screen is cleaned. */
    fun gestureEnded() {
        unblock(GESTURE)
        if (fastModeOn) {
            cancelFastOff?.invoke()
            cancelFastOff = scheduler.post(fastModeTailMs) { cancelFastOff = null; fastModeOff() }
        }
    }

    private fun resumeWhenSettled() {
        if (session.state != InkSession.State.Paused) return
        val wait = pausedAt + rearmMs - clockMs()
        if (wait <= 0) { session.resume(); return }
        if (cancelRearm != null) return
        cancelRearm = scheduler.post(wait) {
            cancelRearm = null
            if (blocks.isEmpty() && !touching) session.resume()
        }
    }

    private fun fastModeOff() {
        cancelFastOff?.invoke(); cancelFastOff = null
        if (fastModeOn) { fastModeOn = false; host.fastMode(false) }
    }

    private fun revealAfterPause() {
        if (touching || !session.holding) return
        val d = dirty
        if (d != null && !frameDirty && host.pushInk(d)) {
            log.d("ink pushed", "rect" to d.joinToString())
            dirty = null
            return
        }
        requestRelease("pause")
    }

    private fun requestRelease(reason: String) {
        if (!session.holding) { dirty = null; return }
        if (frameDirty) {
            pendingRelease = pendingRelease ?: reason
            waitForFrame()
            return
        }
        doRelease(reason)
    }

    private fun requestPause(reason: String) {
        if (frameDirty && session.holding) {
            pendingPause = reason
            waitForFrame()
            return
        }
        doPause(reason)
    }

    private fun waitForFrame() {
        if (cancelFrameWait != null) return
        cancelFrameWait = scheduler.post(FRAME_WAIT_MS) {
            cancelFrameWait = null
            log.w("frame not reported; releasing anyway")
            frameDirty = false
            runPending()
        }
    }

    private fun runPending() {
        pendingPause?.let { pendingPause = null; pendingRelease = null; doPause(it); return }
        pendingRelease?.let { pendingRelease = null; doRelease(it) }
    }

    private fun doRelease(reason: String) {
        val strokes = session.strokesInHold
        if (session.release(reason)) {
            dirty = null
            onReleased?.invoke(reason, strokes)
        }
    }

    private fun doPause(reason: String) {
        val strokes = session.strokesInHold
        val wasHolding = session.holding
        val wasLive = session.state == InkSession.State.Live
        session.pause(reason)
        if (wasLive) pausedAt = clockMs()
        if (wasHolding && !session.holding) { dirty = null; onReleased?.invoke(reason, strokes) }
    }

    private fun cancelTimers() {
        cancelFrameWait?.invoke(); cancelFrameWait = null
        cancelPauseReveal?.invoke(); cancelPauseReveal = null
        cancelFastOff?.invoke(); cancelFastOff = null
        cancelRearm?.invoke(); cancelRearm = null
    }

    companion object {
        /** The longest a release waits for the app to report its frame. */
        const val FRAME_WAIT_MS = 250L
        private const val GESTURE = "gesture"
        private const val ERASER_END = "eraser end"
    }
}
