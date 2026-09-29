package app.booxultimatum.kit.ink.canvas

import app.booxultimatum.kit.ink.FakeInkDisplay
import app.booxultimatum.kit.ink.FakeTime
import app.booxultimatum.kit.ink.session.InkGuard
import app.booxultimatum.kit.ink.session.InkSession
import app.booxultimatum.kit.ink.session.InkStroke
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InkCanvasControllerTest {
    private val pen = InkStroke(1, 4f, -0x1000000)
    private val d = FakeInkDisplay()
    private val time = FakeTime()
    private val guard = InkGuard(object : InkGuard.Undo {
        override fun endLiveSession() = false
        override fun notifyAppDied(pid: Int) = Unit
        override fun clearFastMode() = Unit
        override fun restoreTouch() = Unit
        override fun restoreParams(style: Int, params: FloatArray) = Unit
    })
    private val released = mutableListOf<Pair<String, Int>>()
    private val host = RecordingHost()
    private val c = InkCanvasController(InkSession(d, guard), time, { time.now }).apply {
        host = this@InkCanvasControllerTest.host
        onReleased = { r, n -> released += r to n }
        open(pen, 2480)
    }

    private class RecordingHost : InkCanvasController.Host {
        val calls = mutableListOf<String>()
        var pushWorks = false
        override fun pushInk(dirty: IntArray) = pushWorks.also { calls += "push ink ${dirty.joinToString(",")}" }
        override fun pushControls() = true.also { calls += "push controls" }
        override fun fastMode(on: Boolean) { calls += "fast $on" }
    }

    private fun stroke(bounds: IntArray = intArrayOf(10, 10, 20, 20)) {
        c.down(); c.up(bounds); c.frameShown()
    }

    @Test fun rapidStrokesKeepOneHoldWithNothingLetThrough() {
        d.clear()
        repeat(8) { stroke(); time.advance(120) }
        time.advance(10_000)
        assertEquals(emptyList(), d.posts, "at breaks only: the native behaviour")
        assertTrue(c.holding)
        assertEquals(8, c.session.strokesInHold)
    }

    @Test fun aTouchOnTheControlsReleasesTheWholeBatchOnce() {
        repeat(3) { stroke() }
        d.clear()
        c.controlsTouched()
        assertEquals(listOf("post true"), d.posts)
        assertEquals(listOf("controls" to 3), released)
    }

    @Test fun aReleaseWaitsForTheAppsFrame() {
        c.down(); c.up(intArrayOf(0, 0, 5, 5))
        d.clear()
        c.releaseNow("undo")
        assertEquals(emptyList(), d.posts, "the stroke's frame isn't drawn yet")
        c.frameShown()
        assertEquals(listOf("post true"), d.posts)
    }

    @Test fun aFrameNeverReportedStillReleasesAfterAWhile() {
        c.down(); c.up()
        d.clear()
        c.releaseNow("undo")
        time.advance(InkCanvasController.FRAME_WAIT_MS)
        assertEquals(listOf("post true"), d.posts)
    }

    @Test fun afterPausePushesTheExactInkAndKeepsTheHold() {
        c.reveal = InkCanvasController.Reveal.AfterPause
        host.pushWorks = true
        stroke(intArrayOf(10, 10, 20, 20)); stroke(intArrayOf(5, 30, 15, 40))
        d.clear()
        time.advance(c.pauseMs)
        assertEquals(listOf("push ink 5,10,20,40"), host.calls, "one push of the union of both strokes")
        assertEquals(emptyList(), d.posts)
        assertTrue(c.holding)
    }

    @Test fun afterPauseReleasesWhenThePushIsRefused() {
        c.reveal = InkCanvasController.Reveal.AfterPause
        stroke()
        d.clear()
        time.advance(c.pauseMs)
        assertEquals(listOf("post true"), d.posts)
        assertEquals(listOf("pause" to 1), released)
    }

    @Test fun aNewStrokeCancelsThePauseReveal() {
        c.reveal = InkCanvasController.Reveal.AfterPause
        stroke()
        time.advance(c.pauseMs - 100)
        c.down()
        time.advance(1_000)
        assertTrue(host.calls.isEmpty() && d.posts.isEmpty(), "nothing while the pen is down")
        c.up(); c.frameShown()
        time.advance(c.pauseMs)
        assertEquals(listOf("post true"), d.posts)
        assertEquals(listOf("pause" to 2), released)
    }

    @Test fun everyStrokeIsTheOldBehaviour() {
        c.reveal = InkCanvasController.Reveal.EveryStroke
        d.clear()
        repeat(2) { stroke() }
        assertEquals(listOf("post true", "post true"), d.posts)
    }

    @Test fun controlChangesDuringAHoldArePushed() {
        c.controlsChanged()
        assertTrue(host.calls.isEmpty(), "nothing held, the frames show it anyway")
        stroke()
        c.controlsChanged()
        assertEquals(listOf("push controls"), host.calls)
    }

    @Test fun aGesturePausesTurnsFastModeOnAndOffAfterItsTail() {
        stroke()
        d.clear()
        c.gestureStarted()
        assertEquals(listOf("post true", "state 3"), d.calls)
        assertEquals(listOf("fast true"), host.calls)
        c.gestureEnded()
        time.advance(c.fastModeTailMs - 1)
        assertEquals(listOf("fast true"), host.calls)
        c.gestureStarted()
        time.advance(5_000)
        assertEquals(listOf("fast true"), host.calls, "a second gesture keeps fast mode on")
        c.gestureEnded()
        time.advance(c.fastModeTailMs)
        assertEquals(listOf("fast true", "fast false"), host.calls)
        c.hover(InkCanvasController.Target.Canvas)
        assertEquals(InkSession.State.Live, c.session.state)
    }

    @Test fun blockedDuringAStrokePausesAfterTheLift() {
        c.down()
        c.block("panel")
        assertEquals(InkSession.State.Live, c.session.state)
        c.up(); c.frameShown()
        assertEquals(InkSession.State.Paused, c.session.state)
        c.hover(InkCanvasController.Target.Canvas)
        assertEquals(InkSession.State.Paused, c.session.state, "still blocked")
        c.unblock("panel")
        c.hover(InkCanvasController.Target.Canvas)
        assertEquals(InkSession.State.Live, c.session.state)
    }

    @Test fun resumingAfterAPauseWaitsForThePanel() {
        c.rearmMs = 500
        c.block("panel")
        c.unblock("panel")
        time.advance(100)
        c.hover(InkCanvasController.Target.Canvas)
        assertEquals(InkSession.State.Paused, c.session.state, "too soon after the pause")
        time.advance(400)
        assertEquals(InkSession.State.Live, c.session.state)
    }

    @Test fun aTouchResumesAtOnceEvenBeforeTheWaitIsOver() {
        c.rearmMs = 500
        c.block("panel"); c.unblock("panel")
        c.hover(InkCanvasController.Target.Canvas)
        assertTrue(c.down(), "the stroke is previewed")
        time.advance(1_000)
        assertEquals(InkSession.State.Live, c.session.state)
    }

    @Test fun aPenTouchEndsFastModeAtOnce() {
        c.gestureStarted(); c.gestureEnded()
        c.hover(InkCanvasController.Target.Canvas)
        c.down()
        assertEquals(listOf("fast true", "fast false"), host.calls)
        time.advance(5_000)
        assertEquals(listOf("fast true", "fast false"), host.calls, "not turned off twice")
    }

    @Test fun theEraserEndPausesAndATouchWithItDoesNotResume() {
        c.hover(InkCanvasController.Target.Canvas, eraser = true)
        assertEquals(InkSession.State.Paused, c.session.state)
        assertFalse(c.down(), "the eraser end's touch isn't previewed")
        c.up()
        c.hover(InkCanvasController.Target.Canvas, eraser = false)
        assertEquals(InkSession.State.Live, c.session.state, "the tip hovering again resumes")
    }

    @Test fun hoverOverControlsKeepsDrawing() {
        c.hover(InkCanvasController.Target.Controls)
        assertEquals(InkSession.State.Live, c.session.state)
    }

    @Test fun aStrokeChangeDuringAStrokeWaitsForTheLift() {
        c.down()
        d.clear()
        c.setStroke(InkStroke(2, 16f, -0x1000000))
        assertFalse(d.calls.any { it.startsWith("stroke") })
        c.up()
        assertEquals(listOf("stroke 2 16.0 ff000000"), d.calls.filter { it.startsWith("stroke") })
    }

    @Test fun closeTurnsFastModeOffAndEndsTheSession() {
        c.gestureStarted()
        c.close()
        assertEquals(listOf("fast true", "fast false"), host.calls)
        assertEquals("release", d.calls.last())
    }
}
