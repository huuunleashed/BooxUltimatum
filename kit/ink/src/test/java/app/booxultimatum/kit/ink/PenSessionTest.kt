package app.booxultimatum.kit.ink

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Records every display call, and plays SurfaceFlinger's part in the pen state. */
private class FakeDisplay(var routable: Boolean = true) : PenDisplay {
    val calls = mutableListOf<String>()
    var state = SurfaceInk.STOP

    override fun connect() = routable.also { calls += "connect" }
    override fun penState() = state
    override fun setPenState(state: Int): Boolean { calls += "state $state"; this.state = state; return true }
    override fun setRegion(rect: IntArray): Boolean { calls += "region ${rect.joinToString(",")}"; return true }
    override fun setStroke(widthPx: Float, argb: Int, style: Int) { calls += "stroke $style $widthPx ${Integer.toHexString(argb)}" }
    override fun setExclude(screenRect: IntArray?): Boolean { calls += "exclude ${screenRect?.joinToString(",") ?: "none"}"; return true }
    override fun enablePost(on: Boolean): Boolean { calls += "post $on"; return true }
    override fun release() { calls += "release"; state = SurfaceInk.STOP }
}

class PenSessionTest {
    private val pen = PreviewStroke(style = 1, widthPx = 4f, argb = 0xFF000000.toInt())

    @Test fun opensPausedWithTheStrokeAfterStart() {
        val d = FakeDisplay()
        val s = PenSession(d)
        assertTrue(s.open(2480, pen))
        assertEquals(listOf("connect", "region 0,0,2480,2480", "exclude none", "state 1", "stroke 1 4.0 ff000000", "state 3"), d.calls)
        assertEquals(PenSession.State.Paused, s.state)
    }

    @Test fun opensDrawingWhenAsked() {
        val d = FakeDisplay()
        val s = PenSession(d)
        assertTrue(s.open(2480, pen, drawing = true))
        assertEquals(listOf("connect", "region 0,0,2480,2480", "exclude none", "state 1", "stroke 1 4.0 ff000000", "state 2"), d.calls)
        assertEquals(PenSession.State.Drawing, s.state)
    }

    @Test fun withoutARouteItStaysOutOfTheWay() {
        val d = FakeDisplay(routable = false)
        val s = PenSession(d)
        assertFalse(s.open(2480, pen))
        assertEquals(PenSession.State.Unavailable, s.state)
        s.resume(); s.penDown(); s.penUp(); s.swap(); s.close()
        assertEquals(listOf("connect"), d.calls)
    }

    @Test fun aStrokeIsHeldUntilTheSwap() {
        val d = FakeDisplay()
        val s = PenSession(d).apply { open(2480, pen); resume() }
        d.calls.clear()
        s.penDown()
        assertTrue(s.holding)
        s.penUp()
        assertTrue(s.holding, "frames stay held until the app's frame is ready")
        s.swap()
        assertFalse(s.holding)
        assertEquals(listOf("post false", "post true"), d.calls)
    }

    @Test fun pausedSessionsNeverHold() {
        val d = FakeDisplay()
        val s = PenSession(d).apply { open(2480, pen) }
        s.penDown()
        assertFalse(s.holding)
    }

    @Test fun pausingSwapsAHeldStrokeFirst() {
        val d = FakeDisplay()
        val s = PenSession(d).apply { open(2480, pen); resume(); penDown(); penUp() }
        d.calls.clear()
        s.pause()
        assertEquals(listOf("post false", "post true", "state 3"), d.calls)
        assertEquals(PenSession.State.Paused, s.state)
    }

    @Test fun resumeReopensASessionEndedElsewhere() {
        val d = FakeDisplay()
        val s = PenSession(d).apply { open(2480, pen) }
        d.state = SurfaceInk.STOP
        d.calls.clear()
        s.resume()
        assertEquals(listOf("region 0,0,2480,2480", "state 1", "stroke 1 4.0 ff000000", "state 2"), d.calls)
        assertEquals(PenSession.State.Drawing, s.state)
    }

    @Test fun aNewStrokeWaitsForTheSwapAndSkipsRepeats() {
        val d = FakeDisplay()
        val s = PenSession(d).apply { open(2480, pen); resume(); penDown() }
        d.calls.clear()
        s.setStroke(pen)
        assertTrue(d.calls.isEmpty())
        s.setStroke(pen.copy(style = 0, widthPx = 2f))
        assertEquals(listOf("post false", "post true", "stroke 0 2.0 ff000000"), d.calls)
    }

    @Test fun closeReleasesOnce() {
        val d = FakeDisplay()
        val s = PenSession(d).apply { open(2480, pen); resume() }
        d.calls.clear()
        s.close(); s.close()
        assertEquals(listOf("release"), d.calls)
        assertEquals(PenSession.State.Closed, s.state)
    }

    @Test fun recoverRestoresTheState() {
        val d = FakeDisplay()
        val s = PenSession(d).apply { open(2480, pen); resume(); penDown() }
        s.recover()
        assertEquals(PenSession.State.Drawing, s.state)
        assertFalse(s.holding)
        assertTrue("release" in d.calls)
        assertEquals(SurfaceInk.DRAW, d.state)
    }

    @Test fun anExcludedAreaKeepsTheSessionDrawingAndIsSentOnce() {
        val d = FakeDisplay()
        val s = PenSession(d).apply { open(2480, pen, drawing = true) }
        d.calls.clear()
        s.exclude(intArrayOf(10, 20, 300, 400))
        s.exclude(intArrayOf(10, 20, 300, 400))
        assertEquals(listOf("exclude 10,20,300,400"), d.calls)
        assertEquals(PenSession.State.Drawing, s.state)
        s.exclude(null)
        s.exclude(null)
        assertEquals(listOf("exclude 10,20,300,400", "exclude none"), d.calls)
    }

    @Test fun closingClearsTheExcludedArea() {
        val d = FakeDisplay()
        val s = PenSession(d).apply { open(2480, pen, drawing = true); exclude(intArrayOf(1, 2, 3, 4)) }
        d.calls.clear()
        s.close()
        assertEquals(listOf("exclude none", "release"), d.calls)
    }

    private class RecordingLease : PenLease {
        val calls = mutableListOf<String>()
        override fun taken() { calls += "taken" }
        override fun given() { calls += "given" }
    }

    @Test fun theLeaseIsHeldExactlyWhileTheSessionIsOpen() {
        val lease = RecordingLease()
        val s = PenSession(FakeDisplay(), lease)
        s.open(2480, pen)
        assertEquals(listOf("taken"), lease.calls)
        s.close()
        assertEquals(listOf("taken", "given"), lease.calls)
        s.close()
        assertEquals(listOf("taken", "given"), lease.calls, "closing twice gives it once")
    }

    @Test fun noRouteTakesNoLease() {
        val lease = RecordingLease()
        PenSession(FakeDisplay(routable = false), lease).open(2480, pen)
        assertEquals(emptyList(), lease.calls)
    }

    @Test fun aLeaseFromAnEndedProcessEndsItsDrawingSession() {
        val f = java.io.File.createTempFile("lease", null).apply { writeText("12345 1790600000000") }
        val d = FakeDisplay().apply { state = SurfaceInk.DRAW }
        assertTrue(FileLease(f).recoverStale(d))
        assertTrue("release" in d.calls)
        assertFalse(f.exists(), "the stale lease is dropped")
    }

    @Test fun aLeaseFromAnEndedProcessLeavesAPausedSessionAlone() {
        val f = java.io.File.createTempFile("lease", null).apply { writeText("12345 1790600000000") }
        val d = FakeDisplay().apply { state = SurfaceInk.PAUSED }
        assertFalse(FileLease(f).recoverStale(d), "a paused session draws nothing, and may be another app's by now")
        assertFalse("release" in d.calls)
        assertFalse(f.exists())
    }

    @Test fun noLeaseOrThisProcessesOwnLeaseChangesNothing() {
        val missing = java.io.File(System.getProperty("java.io.tmpdir"), "no-such-lease-${System.nanoTime()}")
        val d = FakeDisplay().apply { state = SurfaceInk.DRAW }
        assertFalse(FileLease(missing).recoverStale(d))
        val own = java.io.File.createTempFile("lease", null).apply { writeText("${android.os.Process.myPid()} 1") }
        assertFalse(FileLease(own).recoverStale(d))
        assertTrue(d.calls.isEmpty())
        assertTrue(own.exists(), "this process's own lease stays")
        own.delete()
    }

    @Test fun theFileLeaseWritesAndRemovesItsNote() {
        val f = java.io.File(System.getProperty("java.io.tmpdir"), "lease-${System.nanoTime()}")
        val lease = FileLease(f)
        lease.taken()
        assertTrue(f.exists())
        assertEquals(android.os.Process.myPid(), f.readText().substringBefore(' ').toInt())
        lease.given()
        assertFalse(f.exists())
    }
}
