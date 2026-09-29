package app.booxultimatum.kit.ink.session

import app.booxultimatum.kit.ink.epd.Epd
import app.booxultimatum.kit.log.Logbook

/**
 * One app's pen session with the display, run the way BOOX's own note and reading apps run theirs (traced on the
 * tablet, NA6C FW 4.3, 2026-09-28): the display draws every stroke itself, and from the first touch it holds the app's
 * frames for the whole writing session. The app renders each stroke behind the hold; nothing reaches the panel between
 * strokes, so strokes follow each other without a pause. The hold ends only at a break the caller chooses ([release]):
 * a menu, a gesture, a tool change, leaving the page. Releasing after every stroke, as Nib 0.2 did, makes the e-ink
 * controller switch update schemes twice per stroke, which is what made quick strokes feel slow.
 *
 * - [open] sets multi-region mode, so any number of [setExclusions] rectangles keep the app's controls clear of the
 *   preview (verified with two areas), the limit, the stroke, then starts drawing or paused.
 * - A stroke's parameters ([InkStroke.params]) replace the display's for that style; the originals are read first and
 *   put back at [close], and [InkGuard] remembers them in case the process dies first.
 * - Every call is made on one thread (the main thread in the apps).
 */
class InkSession(
    private val display: InkDisplay = EpdInkDisplay(),
    private val guard: InkGuard = InkGuard.process,
) {
    enum class State { Closed, Unavailable, Paused, Live }

    private val log = Logbook.logger("ink.session")

    var state: State = State.Closed
        private set

    /** The display holds this app's frames: a stroke has touched since the last [release]. */
    var holding: Boolean = false
        private set

    val available: Boolean get() = state == State.Paused || state == State.Live

    var stroke: InkStroke? = null
        private set
    private var limit: IntArray? = null
    private var side = 0
    private var exclusions = IntArray(0)
    private var buttonsRect: IntArray? = null
    private var buttons: List<Epd.ButtonStroke> = emptyList()
    private val originals = LinkedHashMap<Int, FloatArray>()

    /** Strokes drawn under the current hold, and in the whole session. */
    var strokesInHold = 0
        private set
    var strokesInSession = 0
        private set
    private var holds = 0

    /**
     * Opens the session. [limit] is where the preview may draw, as screen rectangles; null means everywhere (a square
     * of the panel's [panelLongSide] in its own frame). With [live] it starts drawing at once rather than paused: a
     * session paused straight after it opened could stay in "to pause" until the pen moved, and the first stroke lost
     * its start. Returns false, leaving [state] Unavailable, when the display offers no route.
     */
    fun open(stroke: InkStroke, panelLongSide: Int, limit: IntArray? = null, live: Boolean = true): Boolean {
        if (available) {
            setStroke(stroke)
            if (live) resume()
            return true
        }
        if (!display.connect()) {
            state = State.Unavailable
            log.w("no display route")
            return false
        }
        side = panelLongSide
        this.limit = limit?.copyOf()
        this.stroke = stroke
        exclusions = IntArray(0)
        arm()
        display.setPenState(if (live) Epd.PenState.DRAW else Epd.PenState.PAUSE)
        state = if (live) State.Live else State.Paused
        holding = false
        strokesInHold = 0
        strokesInSession = 0
        holds = 0
        guard.sessionOpened()
        log.i("opened", "side" to side, "limit" to limit?.joinToString(), "style" to stroke.style, "width" to stroke.widthPx, "live" to live)
        return true
    }

    /** Region mode, limit, no exclusions, START, then the stroke: what a (re)started session needs. */
    private fun arm() {
        display.setRegionMode(Epd.RegionMode.Multi)
        val l = limit
        if (l != null) display.setLimit(l, screen = true) else display.setLimit(intArrayOf(0, 0, side, side), screen = false)
        display.setExclude(exclusions)
        display.setPenState(Epd.PenState.START)
        stroke?.let { send(it) }
        buttonsRect?.let { r -> if (buttons.isNotEmpty()) display.setPenButtons(r, buttons) }
    }

    /** The pen is over the drawing surface: preview its strokes. */
    fun resume() {
        if (state != State.Paused) return
        // Another app (a BOOX one, typically) may have ended the session while this one was paused.
        if (display.penState() == Epd.PenState.STOP) {
            log.i("session was ended elsewhere; reopening")
            arm()
        }
        display.setPenState(Epd.PenState.DRAW)
        state = State.Live
    }

    /**
     * A break that needs the app's own drawing on screen (a menu, a gesture, an eraser that draws live): lets the held
     * frames through, then stops previewing until [resume].
     */
    fun pause(reason: String) {
        if (state != State.Live) return
        release(reason)
        display.setPenState(Epd.PenState.PAUSE)
        state = State.Paused
    }

    /** Where the preview may draw, as screen rectangles; null for everywhere. */
    fun setLimit(rects: IntArray?) {
        if (rects?.contentEquals(limit) == true || (rects == null && limit == null)) return
        limit = rects?.copyOf()
        if (!available) return
        if (rects != null) display.setLimit(rects, screen = true) else display.setLimit(intArrayOf(0, 0, side, side), screen = false)
    }

    /** The app's controls and panels, as screen rectangles (left, top, right, bottom each): the preview stays off them. */
    fun setExclusions(rects: List<IntArray>) {
        val flat = IntArray(rects.size * 4).also { a -> rects.forEachIndexed { i, r -> r.copyInto(a, i * 4, 0, 4) } }
        if (flat.contentEquals(exclusions)) return
        exclusions = flat
        if (available) display.setExclude(flat)
    }

    val exclusionCount: Int get() = exclusions.size / 4

    /** A new pen, width or colour; sent at once, while a hold goes on. */
    fun setStroke(s: InkStroke) {
        if (s == stroke) return
        stroke = s
        if (available) send(s)
    }

    /**
     * What each part of the pen draws inside [rect] (screen left, top, right, bottom), as the native note app sets it
     * up: the tip the pen's stroke, the eraser end an eraser track, the side button a marker or the lasso. An empty
     * list clears it. *[verify]* on the tablet before an app relies on it.
     */
    fun setPenButtons(rect: IntArray, list: List<Epd.ButtonStroke>) {
        buttonsRect = rect.copyOf()
        buttons = list.toList()
        if (available) display.setPenButtons(rect, list)
    }

    /** The display previews the eraser end's track in [style] while [on]. */
    fun setEraserPreview(on: Boolean, style: Int = InkStroke.STYLE_ERASER) {
        if (available) display.setEraserPreview(on, style)
    }

    /** The pen touched the drawing surface while previewing: the display holds the app's frames from now on. */
    fun penDown() {
        if (state != State.Live) return
        if (!holding) { holding = true; holds++; strokesInHold = 0 }
    }

    /** The stroke ended; it stays in the display's preview while the hold lasts. */
    fun penUp() {
        if (!holding) return
        strokesInHold++
        strokesInSession++
    }

    /**
     * Lets the held frames through, which replaces the display's preview with what the app has drawn. Call it only once
     * the app's frame with every stroke so far has been drawn. Returns whether anything was held.
     */
    fun release(reason: String): Boolean {
        if (!available || !holding) return false
        display.enablePost(true)
        holding = false
        log.d("released", "reason" to reason, "strokes" to strokesInHold, "hold" to holds)
        return true
    }

    /**
     * Lets frames through whether or not a hold is known: a hold then release pulse, which also swaps after a touch the
     * display caught but the app didn't see (a pen tap on a control, for instance).
     */
    fun flush(reason: String) {
        if (!available) return
        display.enablePost(false)
        display.enablePost(true)
        holding = false
        log.d("flushed", "reason" to reason)
    }

    /** Ends the session and hands the display back: parameters restored, exclusions cleared, frames flowing. */
    fun close() {
        if (state == State.Closed || state == State.Unavailable) { state = State.Closed; return }
        restoreParams()
        display.release()
        guard.sessionClosed()
        log.i("closed", "strokes" to strokesInSession, "holds" to holds)
        state = State.Closed
        holding = false
    }

    /** Clears any stuck preview and held frames, then opens a fresh session in the same state as before. */
    fun recover() {
        val was = state
        val s = stroke ?: return
        display.release()
        state = State.Closed
        holding = false
        log.i("recovered", "was" to was)
        if (was == State.Paused || was == State.Live) {
            val keep = exclusions
            open(s, side, limit, live = was == State.Live)
            exclusions = IntArray(0)
            setExclusions(keep.toList().chunked(4).map { it.toIntArray() })
        }
    }

    private fun send(s: InkStroke) {
        display.setStroke(s.style, s.widthPx, s.argb)
        val p = s.params ?: return
        if (s.style !in originals) {
            display.strokeParameters(s.style)?.let { original ->
                originals[s.style] = original
                guard.paramsChanged(s.style, original)
            }
        }
        display.setStrokeParameters(s.style, p)
    }

    private fun restoreParams() {
        originals.forEach { (style, p) -> display.setStrokeParameters(style, p); guard.paramsRestored(style) }
        originals.clear()
    }
}
