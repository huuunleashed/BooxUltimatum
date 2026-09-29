package app.booxultimatum.kit.ink.session

import app.booxultimatum.kit.ink.FakeInkDisplay
import app.booxultimatum.kit.ink.epd.Epd
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InkSessionTest {
    private val pen = InkStroke(style = 1, widthPx = 4f, argb = 0xFF000000.toInt())
    private val guard = InkGuard(NoUndo)

    private object NoUndo : InkGuard.Undo {
        override fun endLiveSession() = false
        override fun clearFastMode() = Unit
        override fun restoreTouch() = Unit
        override fun restoreParams(style: Int, params: FloatArray) = Unit
    }

    private fun session(d: FakeInkDisplay) = InkSession(d, guard)

    @Test fun opensInMultiRegionModeWithTheStrokeAfterStartThenDraws() {
        val d = FakeInkDisplay()
        val s = session(d)
        assertTrue(s.open(pen, 2480))
        assertEquals(listOf("connect", "mode Multi", "limit panel 0,0,2480,2480", "exclude none", "state 1", "stroke 1 4.0 ff000000", "state 2"), d.calls)
        assertEquals(InkSession.State.Live, s.state)
        assertTrue(guard.outstanding, "an open session is on record")
    }

    @Test fun aScreenLimitIsSentInScreenCoordinates() {
        val d = FakeInkDisplay()
        session(d).open(pen, 2480, limit = intArrayOf(0, 100, 1860, 2400), live = false)
        assertTrue("limit screen 0,100,1860,2400" in d.calls)
        assertEquals("state 3", d.calls.last())
    }

    @Test fun withoutARouteItStaysOutOfTheWay() {
        val d = FakeInkDisplay(routable = false)
        val s = session(d)
        assertFalse(s.open(pen, 2480))
        assertEquals(InkSession.State.Unavailable, s.state)
        s.resume(); s.penDown(); s.penUp(); s.release("x"); s.flush("x"); s.close()
        assertEquals(listOf("connect"), d.calls)
    }

    @Test fun theHoldLastsAcrossStrokesUntilReleased() {
        val d = FakeInkDisplay()
        val s = session(d).apply { open(pen, 2480) }
        d.clear()
        repeat(5) { s.penDown(); s.penUp() }
        assertTrue(s.holding)
        assertEquals(5, s.strokesInHold)
        assertEquals(emptyList(), d.posts, "nothing reaches the display between strokes")
        assertTrue(s.release("menu"))
        assertEquals(listOf("post true"), d.posts)
        assertFalse(s.holding)
        assertFalse(s.release("again"), "nothing to release twice")
    }

    @Test fun aPausedSessionDoesNotHold() {
        val d = FakeInkDisplay()
        val s = session(d).apply { open(pen, 2480, live = false) }
        s.penDown()
        assertFalse(s.holding)
    }

    @Test fun pausingReleasesTheHoldFirst() {
        val d = FakeInkDisplay()
        val s = session(d).apply { open(pen, 2480) }
        s.penDown(); s.penUp()
        d.clear()
        s.pause("panel")
        assertEquals(listOf("post true", "state 3"), d.calls)
        assertEquals(InkSession.State.Paused, s.state)
    }

    @Test fun resumeReopensASessionEndedElsewhere() {
        val d = FakeInkDisplay()
        val s = session(d).apply { open(pen, 2480, live = false) }
        s.setExclusions(listOf(intArrayOf(0, 0, 100, 100)))
        d.state = Epd.PenState.STOP
        d.clear()
        s.resume()
        assertEquals(listOf("mode Multi", "limit panel 0,0,2480,2480", "exclude 0,0,100,100", "state 1", "stroke 1 4.0 ff000000", "state 2"), d.calls)
    }

    @Test fun exclusionsAreSentAllTogetherAndOnlyWhenTheyChange() {
        val d = FakeInkDisplay()
        val s = session(d).apply { open(pen, 2480) }
        d.clear()
        val a = intArrayOf(0, 0, 100, 100)
        val b = intArrayOf(1700, 2000, 1860, 2480)
        s.setExclusions(listOf(a, b))
        s.setExclusions(listOf(a.copyOf(), b.copyOf()))
        s.setExclusions(emptyList())
        assertEquals(listOf("exclude 0,0,100,100,1700,2000,1860,2480", "exclude none"), d.calls)
    }

    @Test fun strokeParametersAreSetAndTheOriginalsPutBackAtClose() {
        val d = FakeInkDisplay()
        val s = session(d)
        s.open(InkStroke(1, 4f, -0x1000000, floatArrayOf(0.8f, 0.2f)), 2480)
        assertTrue("get params 1" in d.calls)
        assertTrue("params 1 0.8,0.2" in d.calls)
        assertEquals(listOf(0.3f, 0.6f), guard.changedParams()[1]?.toList(), "the original is on record")
        s.setStroke(InkStroke(1, 5f, -0x1000000, floatArrayOf(0.5f, 0.5f)))
        assertEquals(1, d.calls.count { it == "get params 1" }, "the original is read once")
        d.clear()
        s.close()
        assertEquals(listOf("params 1 0.3,0.6", "release"), d.calls)
        assertFalse(guard.outstanding)
    }

    @Test fun aStrokeWithoutParametersLeavesTheDisplaysAlone() {
        val d = FakeInkDisplay()
        session(d).open(pen, 2480)
        assertFalse(d.calls.any { it.startsWith("params") || it.startsWith("get params") })
    }

    @Test fun penButtonsAreSentAndReArmedWithTheSession() {
        val d = FakeInkDisplay()
        val s = session(d)
        val rect = intArrayOf(0, 0, 1860, 2480)
        val list = listOf(Epd.ButtonStroke(Epd.PenButton.TIP, 1, 4f, -0x1000000), Epd.ButtonStroke(Epd.PenButton.CAP, 8, 30f, -0x1000000, floatArrayOf(30f, 0.5f, 0.1f), true, 8))
        s.setPenButtons(rect, list)
        assertTrue(d.calls.isEmpty(), "nothing sent before the session opens")
        s.open(pen, 2480, live = false)
        assertTrue("buttons 0,0,1860,2480 0:1;1:8" in d.calls)
        d.state = Epd.PenState.STOP
        d.clear()
        s.resume()
        assertTrue("buttons 0,0,1860,2480 0:1;1:8" in d.calls, "a session reopened after another app ended it gets them again")
    }

    @Test fun flushPulsesEvenWithoutAKnownHold() {
        val d = FakeInkDisplay()
        val s = session(d).apply { open(pen, 2480) }
        d.clear()
        s.flush("tap")
        assertEquals(listOf("post false", "post true"), d.calls)
    }

    @Test fun recoverReopensWithTheSameExclusions() {
        val d = FakeInkDisplay()
        val s = session(d).apply { open(pen, 2480) }
        s.setExclusions(listOf(intArrayOf(1, 2, 3, 4)))
        d.clear()
        s.recover()
        assertEquals("release", d.calls.first())
        assertTrue("exclude 1,2,3,4" in d.calls)
        assertEquals(InkSession.State.Live, s.state)
    }
}
