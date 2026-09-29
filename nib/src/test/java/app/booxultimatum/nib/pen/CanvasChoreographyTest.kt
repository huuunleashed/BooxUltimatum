package app.booxultimatum.nib.pen

import app.booxultimatum.kit.ink.canvas.InkCanvasController
import app.booxultimatum.kit.ink.canvas.InkScheduler
import app.booxultimatum.kit.ink.epd.Epd
import app.booxultimatum.kit.ink.session.InkDisplay
import app.booxultimatum.kit.ink.session.InkGuard
import app.booxultimatum.kit.ink.session.InkSession
import app.booxultimatum.kit.ink.session.InkStroke
import app.booxultimatum.nib.brush.PreviewPolicy
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.brush.PenButtons
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Records every display call and plays SurfaceFlinger's part in the pen state and the style parameters. */
private class RecordingDisplay(var routable: Boolean = true) : InkDisplay {
    val calls = mutableListOf<String>()
    var state = Epd.PenState.STOP
    private val params = mutableMapOf(1 to floatArrayOf(0.3f, 0.6f), 5 to floatArrayOf(5f, 9f, 9f, 0f))

    override fun connect() = routable.also { calls += "connect" }
    override fun penState() = state
    override fun setPenState(state: Int): Boolean { calls += "state $state"; this.state = state; return true }
    override fun setRegionMode(mode: Epd.RegionMode): Boolean { calls += "mode ${mode.name}"; return true }
    override fun setLimit(rects: IntArray, screen: Boolean): Boolean { calls += "limit ${rects.joinToString(",")}"; return true }
    override fun setExclude(rects: IntArray): Boolean { calls += "exclude ${rects.joinToString(",").ifEmpty { "none" }}"; return true }
    override fun setStroke(style: Int, widthPx: Float, argb: Int) { calls += "stroke $style $widthPx" }
    override fun strokeParameters(style: Int) = params[style]?.copyOf().also { calls += "get params $style" }
    override fun setStrokeParameters(style: Int, params: FloatArray): Boolean { calls += "params $style ${params.joinToString(",")}"; return true }
    override fun setEraserPreview(on: Boolean, style: Int): Boolean { calls += "eraser $on"; return true }
    override fun setBrushPreview(on: Boolean): Boolean { calls += "brush $on"; return true }
    override fun setPenButtons(rect: IntArray, buttons: List<Epd.ButtonStroke>): Boolean {
        calls += "buttons ${buttons.joinToString(";") { "${it.button}:${it.style}:${it.eraserPreview}:${it.eraserPainter}" }.ifEmpty { "none" }}"; return true
    }
    override fun enablePost(on: Boolean): Boolean { calls += "post $on"; return true }
    override fun release() { calls += "release"; state = Epd.PenState.STOP }

    val posts get() = calls.filter { it.startsWith("post") }
}

private class HandClock : InkScheduler {
    var now = 0L
    private class Task(val at: Long, val block: () -> Unit, var cancelled: Boolean = false)
    private val tasks = mutableListOf<Task>()
    override fun post(delayMs: Long, block: () -> Unit): () -> Unit = Task(now + delayMs, block).also { tasks += it }.let { t -> { t.cancelled = true } }
    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
            tasks.remove(next)
            now = next.at
            next.block()
        }
        now = end
    }
}

/**
 * The writing choreography as Nib's canvas uses it (`InkCanvasController` over an `InkSession`): what the old
 * `PenController` tests covered that still applies, and what the canvas now relies on. The controller's own tests in
 * `kit:ink` cover the rest.
 */
class CanvasChoreographyTest {
    private val guard = InkGuard(object : InkGuard.Undo {
        override fun endLiveSession() = false
        override fun notifyAppDied(pid: Int) = Unit
        override fun clearFastMode() = Unit
        override fun restoreTouch() = Unit
        override fun restoreParams(style: Int, params: FloatArray) = Unit
    })
    private val display = RecordingDisplay()
    private val clock = HandClock()
    private val session = InkSession(display, guard)
    private val pen = PreviewPolicy.preview(BrushSpec.defaults(BrushKind.Fountain), -0x1000000, 1f, tryUnverified = false)

    private fun controller(s: InkSession = session) = InkCanvasController(s, clock) { clock.now }

    @Test fun aPanelOpenWhenTheCanvasOpensStartsItPaused() {
        val c = controller()
        c.block("panel")
        assertTrue(c.open(pen, 2480))
        assertEquals(InkSession.State.Paused, session.state)
        c.hover(InkCanvasController.Target.Canvas)
        assertEquals(InkSession.State.Paused, session.state, "blocked: hover doesn't resume")
    }

    @Test fun withoutADisplayPathTheCanvasDrawsAlone() {
        val none = RecordingDisplay(routable = false)
        val c = controller(InkSession(none, guard))
        assertFalse(c.open(pen, 2480))
        c.setControls(listOf(intArrayOf(0, 0, 10, 10)))
        c.hover(InkCanvasController.Target.Canvas)
        assertFalse(c.down(), "software-only: Nib draws the stroke itself")
        c.up(intArrayOf(0, 0, 5, 5)); c.frameShown()
        c.releaseNow("undo"); c.gestureStarted(); c.gestureEnded()
        clock.advance(5_000)
        assertEquals(listOf("connect"), none.calls)
    }

    @Test fun thePensOwnParametersGoWithItsStrokeAndArePutBack() {
        val c = controller()
        c.open(pen, 2480)
        assertTrue("get params 1" in display.calls)
        assertTrue("params 1 ${pen.params!!.joinToString(",")}" in display.calls)
        display.calls.clear()
        c.close()
        assertEquals("params 1 0.3,0.6", display.calls.first(), "the display's own fountain settings come back")
    }

    @Test fun theLassoIsPreviewedDashedAndGoesAtTheLift() {
        val c = controller()
        c.open(pen, 2480)
        c.setStroke(InkStroke.lasso())
        assertTrue("stroke 5 3.0" in display.calls)
        assertTrue("params 5 5.0" in display.calls)
        display.calls.clear()
        assertTrue(c.down())
        c.up(intArrayOf(10, 10, 300, 300))
        c.releaseNow("lasso")
        assertTrue(display.posts.isEmpty(), "not before the frame without the path is drawn")
        c.frameShown()
        assertEquals(listOf("post true"), display.posts)
    }

    @Test fun thePreviewedEraserEndKeepsDrawingAndGoesAtItsLift() {
        val c = controller()
        c.open(pen, 2480)
        c.hover(InkCanvasController.Target.Canvas, eraser = false)
        assertTrue(c.down(), "the display previews the eraser end's track")
        c.up(intArrayOf(0, 0, 50, 50))
        c.releaseNow("eraser end")
        c.frameShown()
        assertEquals(listOf("post true"), display.posts)
        assertEquals(InkSession.State.Live, session.state, "no pause, so the next stroke needs no resume")
    }

    @Test fun withoutTheEraserEndPreviewTheSessionIsBlockedForIt() {
        val c = controller()
        c.open(pen, 2480)
        c.hover(InkCanvasController.Target.Canvas, eraser = true)
        assertEquals(InkSession.State.Paused, session.state)
        assertFalse(c.down(), "the eraser end blocks the preview, so its touch isn't previewed")
        c.up(); c.frameShown()
        c.hover(InkCanvasController.Target.Canvas)
        clock.advance(1_000)
        assertEquals(InkSession.State.Live, session.state, "the tip hovering again resumes")
        c.block("eraser")
        c.hover(InkCanvasController.Target.Canvas)
        assertFalse(c.down(), "blocked: Nib draws the erasing itself")
        c.up()
        c.unblock("eraser")
        c.hover(InkCanvasController.Target.Canvas)
        clock.advance(1_000)
        assertEquals(InkSession.State.Live, session.state, "the tip is back")
    }

    @Test fun thePensPartsAreSentWithTheSession() {
        val parts = PenParts.both(pen, PenButtons.eraserEnd(24f)).map { it.toButton() }
        session.setPenButtons(intArrayOf(0, 60, 1860, 2480), parts)
        controller().open(pen, 2480)
        val stroke = display.calls.indexOf("stroke 1 ${pen.widthPx}")
        val buttons = display.calls.indexOf("buttons 0:1:false:0;1:8:true:8")
        assertTrue(stroke in 0 until buttons, "after START and the stroke")
    }

    @Test fun aTapOnAControlLetsTheFramesThroughWhenItEnds() {
        val c = controller()
        c.open(pen, 2480)
        display.calls.clear()
        c.controlsTouched()
        c.controlsTouchEnded()
        assertEquals(listOf("post false", "post true"), display.posts, "the pressed control shows at once")
    }

    @Test fun everyControlIsExcludedAtOnce() {
        val c = controller()
        c.open(pen, 2480)
        display.calls.clear()
        c.setControls(listOf(intArrayOf(12, 72, 300, 170), intArrayOf(12, 300, 120, 1400), intArrayOf(0, 0, 1860, 60)))
        assertEquals(listOf("exclude 12,72,300,170,12,300,120,1400,0,0,1860,60"), display.calls)
    }
}
