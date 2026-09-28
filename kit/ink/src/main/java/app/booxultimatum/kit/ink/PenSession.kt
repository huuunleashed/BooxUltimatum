package app.booxultimatum.kit.ink

import app.booxultimatum.kit.log.Logbook

/** The display preview's stroke: a firmware style, a width in panel pixels and an opaque colour (Marker may be half). */
data class PreviewStroke(val style: Int, val widthPx: Float, val argb: Int)

/**
 * The display's pen session for an app that draws in its own window, as Boox Notes does: SurfaceFlinger paints each
 * stroke from the pen at once, the firmware holds the app's own frames while the pen touches, and after the lift the
 * app lets its frame through ([swap]), which replaces the preview with the app's real stroke.
 *
 * What it relies on, each verified on a Note Air6 C with FW 4.3 (see knowledge/experiments.md):
 * - The session must exist before the pen touches; a paused one resumes instantly. So it opens when the canvas is
 *   shown and pauses, rather than ends, while the pen is over the app's controls or a panel is open.
 * - The stroke is sent after START, as style, width, colour: choosing a style loads that style's default width.
 * - The region is read in the panel's own landscape frame; a square as large as the long side covers every rotation.
 * - Letting frames through mid-stroke ends the preview for that stroke, so [swap] only happens after a lift.
 *
 * Every call is made on one thread (the main thread in the app). Nothing here waits or schedules; the caller decides
 * when its frame is ready and calls [swap].
 */
class PenSession(private val display: PenDisplay = SurfaceInkDisplay, private val lease: PenLease = PenLease.None) {
    enum class State { Closed, Unavailable, Paused, Drawing }

    private val log = Logbook.logger("ink.session")

    var state: State = State.Closed
        private set

    /** Whether a stroke has touched since the last [swap]: the firmware is holding this app's frames. */
    var holding: Boolean = false
        private set

    private var stroke: PreviewStroke? = null
    private var excluded: IntArray? = null

    /** The rectangle the preview currently leaves alone, in screen coordinates, if any. */
    val exclusion: IntArray? get() = excluded?.copyOf()
    private var side = 0
    private var strokesSinceOpen = 0

    val available: Boolean get() = state == State.Paused || state == State.Drawing

    /**
     * Opens the session, ready for the pen. [panelLongSide] is the panel's long side in pixels. With [drawing] it
     * starts drawing at once rather than paused: a session paused straight after it opened can stay in the firmware's
     * "to pause" state until the pen next moves, and the first stroke then lost its start (owner's report, NA6C).
     * Returns false, leaving [state] Unavailable, when the firmware has no route: the app then draws without a preview.
     */
    fun open(panelLongSide: Int, preview: PreviewStroke, drawing: Boolean = false): Boolean {
        if (available) { setStroke(preview); if (drawing) resume(); return true }
        if (!display.connect()) {
            state = State.Unavailable
            log.w("no display route")
            return false
        }
        side = panelLongSide
        stroke = preview
        display.setRegion(intArrayOf(0, 0, side, side))
        // A rectangle left by an earlier session would silently hide part of the preview.
        display.setExclude(null)
        excluded = null
        display.setPenState(SurfaceInk.START)
        send(preview)
        display.setPenState(if (drawing) SurfaceInk.DRAW else SurfaceInk.PAUSE)
        state = if (drawing) State.Drawing else State.Paused
        lease.taken()
        holding = false
        strokesSinceOpen = 0
        log.i("opened", "side" to side, "style" to preview.style, "width" to preview.widthPx, "drawing" to drawing)
        return true
    }

    /** The pen is over the canvas: preview its strokes. */
    fun resume() {
        if (state != State.Paused) return
        // Another app (Boox's own, typically) may have ended the session while this one was paused.
        if (display.penState() == SurfaceInk.STOP) {
            log.i("session was ended elsewhere; reopening")
            display.setRegion(intArrayOf(0, 0, side, side))
            display.setPenState(SurfaceInk.START)
            stroke?.let { send(it) }
        }
        display.setPenState(SurfaceInk.DRAW)
        state = State.Drawing
    }

    /**
     * Keeps the preview off one area, in screen coordinates, while the session goes on drawing; null clears it. Over the
     * app's controls this replaces [pause]: a paused session sometimes lost the start of the stroke that followed on the
     * canvas (owner's report, NA6C FW 4.3), and an excluded area needs no resume. The display keeps one rectangle.
     */
    fun exclude(screenRect: IntArray?) {
        if (!available) return
        if (screenRect == null && excluded == null) return
        if (screenRect != null && excluded?.contentEquals(screenRect) == true) return
        display.setExclude(screenRect)
        excluded = screenRect?.copyOf()
    }

    /** The pen is over the app's controls, a panel is open, or fingers are moving the canvas: no preview. */
    fun pause() {
        if (state != State.Drawing) return
        if (holding) swap()
        display.setPenState(SurfaceInk.PAUSE)
        state = State.Paused
    }

    /** A new brush, width or colour. Sent at once while paused; while a stroke is held, after its swap. */
    fun setStroke(preview: PreviewStroke) {
        if (preview == stroke) return
        stroke = preview
        if (!available) return
        if (holding) swap()
        send(preview)
    }

    fun penDown() {
        if (state != State.Drawing) return
        holding = true
    }

    /** The stroke ended; the caller renders it and calls [swap] once its frame is drawn. */
    fun penUp() {
        if (holding) strokesSinceOpen++
    }

    /**
     * Lets the app's frames through again, which replaces the preview with what the app drew. A pulse (hold, then
     * let through) also swaps when the firmware wasn't holding anything, so a missed touch can't leave preview ink.
     */
    fun swap() {
        if (!available) return
        display.enablePost(false)
        display.enablePost(true)
        holding = false
    }

    /** Ends the session and hands the panel back to normal drawing. */
    fun close() {
        if (state == State.Closed || state == State.Unavailable) { state = State.Closed; return }
        if (excluded != null) { display.setExclude(null); excluded = null }
        display.release()
        lease.given()
        log.i("closed", "strokes" to strokesSinceOpen)
        state = State.Closed
        holding = false
    }

    /** Clears any stuck preview and held frames, then opens a fresh session in the same state as before. */
    fun recover() {
        val was = state
        val s = stroke ?: return
        display.release()
        lease.given()
        state = State.Closed
        holding = false
        log.i("recovered", "was" to was)
        if (was == State.Paused || was == State.Drawing) {
            open(side, s)
            if (was == State.Drawing) resume()
        }
    }

    private fun send(s: PreviewStroke) = display.setStroke(s.widthPx, s.argb, s.style)
}
