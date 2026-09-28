package app.booxultimatum.nib.pen

import app.booxultimatum.kit.ink.PenDisplay
import app.booxultimatum.kit.ink.PenSession
import app.booxultimatum.kit.ink.PreviewStroke
import app.booxultimatum.kit.ink.SurfaceInk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Records every display call and plays SurfaceFlinger's part in the pen state. */
private class FakeDisplay(var routable: Boolean = true) : PenDisplay {
    val calls = mutableListOf<String>()
    var state = SurfaceInk.STOP

    override fun connect() = routable.also { calls += "connect" }
    override fun penState() = state
    override fun setPenState(state: Int): Boolean {
        calls += "state $state"
        this.state = state
        return true
    }
    override fun setRegion(rect: IntArray): Boolean = true
    override fun setExclude(screenRect: IntArray?): Boolean { calls += "exclude ${screenRect?.joinToString(",") ?: "none"}"; return true }
    override fun setStroke(widthPx: Float, argb: Int, style: Int) {
        calls += "stroke $style $widthPx"
    }
    override fun enablePost(on: Boolean): Boolean {
        calls += "post $on"
        return true
    }
    override fun release() {
        calls += "release"
        state = SurfaceInk.STOP
    }

    val swaps: Int get() = calls.count { it == "post true" }
}

/** A clock and a queue of delayed work, run by hand. */
private class FakeScheduler : PenScheduler {
    var now = 0L
    private val tasks = mutableListOf<Pair<Long, () -> Unit>>()

    override fun post(delayMs: Long, block: () -> Unit): () -> Unit {
        val t = (now + delayMs) to block
        tasks += t
        return { tasks.remove(t) }
    }

    fun advance(ms: Long) {
        now += ms
        while (true) {
            val due = tasks.filter { it.first <= now }.minByOrNull { it.first } ?: break
            tasks.remove(due)
            due.second()
        }
    }
}

class PenControllerTest {
    private val pen = PreviewStroke(1, 3f, 0xFF000000.toInt())
    private val display = FakeDisplay()
    private val scheduler = FakeScheduler()
    private val controller = PenController(PenSession(display), scheduler) { scheduler.now }

    private fun opened(): PenController {
        assertTrue(controller.open(2480, pen))
        display.calls.clear()
        return controller
    }

    @Test fun hoverOverTheCanvasDrawsAndElsewherePauses() {
        val c = opened()
        c.hover(inside = true, eraser = false)
        assertEquals(PenSession.State.Drawing, c.state)
        c.hover(inside = false, eraser = false)
        assertEquals(PenSession.State.Paused, c.state)
        c.hover(inside = true, eraser = true)
        assertEquals(PenSession.State.Paused, c.state, "the eraser end never previews")
    }

    @Test fun theSwapWaitsForTheFrameAndTheExtraDelay() {
        val c = opened()
        c.extraSwapDelayMs = 50
        var swappedAfter = -1L
        c.onSwapped = { ms, watchdog ->
            swappedAfter = ms
            assertFalse(watchdog)
        }
        c.hover(true, false)
        assertTrue(c.down())
        scheduler.advance(200)
        c.up()
        assertTrue(c.awaitingFrame)
        assertEquals(0, display.swaps, "nothing is let through before the stroke's frame")
        scheduler.advance(16)
        c.frameShown()
        assertEquals(0, display.swaps)
        scheduler.advance(50)
        assertEquals(1, display.swaps)
        assertEquals(66L, swappedAfter)
        assertFalse(c.holding)
    }

    @Test fun theWatchdogSwapsWhenNoFrameComes() {
        val c = opened()
        var watchdogged = false
        c.onSwapped = { _, w -> watchdogged = w }
        c.hover(true, false)
        c.down()
        c.up()
        scheduler.advance(PenController.WATCHDOG_MS - 1)
        assertTrue(c.holding)
        scheduler.advance(1)
        assertFalse(c.holding)
        assertTrue(watchdogged)
    }

    @Test fun aQuickSecondStrokeKeepsTheFramesHeldAndSwapsOnce() {
        val c = opened()
        c.hover(true, false)
        c.down()
        c.up()
        c.down()
        assertTrue(c.holding, "letting frames through now would end this stroke's preview")
        c.frameShown()
        assertEquals(0, display.swaps)
        c.up()
        c.frameShown()
        assertEquals(1, display.swaps)
    }

    @Test fun aPanelPausesButNotMidStrokeAndNotBeforeTheSwap() {
        val c = opened()
        c.hover(true, false)
        c.down()
        c.block("panel")
        assertEquals(PenSession.State.Drawing, c.state, "a stroke under way is never cut")
        c.up()
        assertEquals(PenSession.State.Drawing, c.state, "the swap comes first")
        c.frameShown()
        assertEquals(PenSession.State.Paused, c.state)
        c.hover(true, false)
        assertEquals(PenSession.State.Paused, c.state, "blocked: hover doesn't resume")
        c.unblock("panel")
        c.hover(true, false)
        assertEquals(PenSession.State.Drawing, c.state)
    }

    @Test fun previewChangesWaitForTheSwap() {
        val c = opened()
        c.hover(true, false)
        c.down()
        c.setPreview(PreviewStroke(0, 5f, 0xFF000000.toInt()))
        assertFalse(display.calls.any { it.startsWith("stroke") })
        c.up()
        c.frameShown()
        assertTrue(display.calls.last().startsWith("stroke 0 5.0"))
    }

    @Test fun opensDrawingSoTheFirstStrokeIsCaught() {
        val c = PenController(PenSession(FakeDisplay()), scheduler) { scheduler.now }
        assertTrue(c.open(2480, pen))
        assertEquals(PenSession.State.Drawing, c.state)
    }

    @Test fun opensPausedWhenBlocked() {
        val c = PenController(PenSession(FakeDisplay()), scheduler) { scheduler.now }
        c.block("panel")
        assertTrue(c.open(2480, pen))
        assertEquals(PenSession.State.Paused, c.state)
    }

    @Test fun leavingHoverRangeKeepsDrawing() {
        val c = opened()
        c.hover(true, false)
        c.hoverExit()
        scheduler.advance(1000)
        assertEquals(PenSession.State.Drawing, c.state, "a quick stroke from out of range must still be previewed")
        c.down()
        c.up()
        c.frameShown()
        c.hoverExit()
        scheduler.advance(1000)
        assertEquals(PenSession.State.Drawing, c.state)
    }

    @Test fun aTouchOnTheControlsLetsFramesThrough() {
        val c = opened()
        c.hover(true, false)
        c.touchedOutside()
        assertEquals(PenSession.State.Paused, c.state)
    }

    @Test fun withoutADisplayPathEverythingIsQuiet() {
        val none = FakeDisplay(routable = false)
        val c = PenController(PenSession(none), scheduler) { scheduler.now }
        assertFalse(c.open(2480, pen))
        c.hover(true, false)
        assertFalse(c.down())
        c.up()
        c.frameShown()
        scheduler.advance(5_000)
        assertEquals(listOf("connect"), none.calls)
        assertFalse(c.available)
    }

    @Test fun overAControlThePreviewLeavesItAloneAndKeepsDrawing() {
        val c = opened()
        c.hover(true, false)
        val card = intArrayOf(100, 200, 500, 900)
        c.hover(inside = false, eraser = false, control = card)
        assertEquals(PenSession.State.Drawing, c.state, "no pause, so the next stroke needs no resume")
        assertTrue(display.calls.contains("exclude 100,200,500,900"))
        c.hover(inside = true, eraser = false)
        assertTrue(c.down(), "the stroke on the canvas is previewed straight away")
    }

    @Test fun withoutAControlRectangleItStillPauses() {
        val c = opened()
        c.hover(inside = false, eraser = false, control = null)
        assertEquals(PenSession.State.Paused, c.state)
    }

    @Test fun aBlockStillPausesEvenOverAControl() {
        val c = opened()
        c.block("panel")
        c.hover(inside = false, eraser = false, control = intArrayOf(0, 0, 10, 10))
        assertEquals(PenSession.State.Paused, c.state)
    }

    @Test fun aTouchOnAControlKeepsDrawingAndLetsItsFramesThroughAfter() {
        val c = opened()
        c.hover(true, false)
        c.touchedOutside(intArrayOf(0, 0, 50, 50))
        assertEquals(PenSession.State.Drawing, c.state)
        val before = display.swaps
        c.touchOutsideEnded()
        assertEquals(before + 1, display.swaps, "the pressed control shows at once")
    }

    @Test fun theExclusionClearsWhenAsked() {
        val c = opened()
        c.hover(false, false, intArrayOf(1, 2, 3, 4))
        assertTrue(c.exclusion != null)
        c.clearExclusion()
        assertEquals(null, c.exclusion)
        assertEquals("exclude none", display.calls.last())
    }
}
