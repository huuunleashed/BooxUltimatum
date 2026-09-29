package app.booxultimatum.core.ink

import app.booxultimatum.core.ink.HoldPolicy.End
import app.booxultimatum.kit.ink.canvas.InkScheduler
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A clock and scheduler under the test's control: tasks run in time order as [advance] passes them. */
private class FakeTime : InkScheduler {
    var now = 0L
        private set
    private class Task(val at: Long, val seq: Int, val block: () -> Unit) { var cancelled = false }
    private val tasks = mutableListOf<Task>()
    private var seq = 0

    override fun post(delayMs: Long, block: () -> Unit): () -> Unit {
        val t = Task(now + delayMs, seq++, block)
        tasks += t
        return { t.cancelled = true }
    }

    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = tasks.filter { !it.cancelled && it.at <= end }.minWithOrNull(compareBy<Task>({ it.at }, { it.seq })) ?: break
            tasks.remove(next)
            now = next.at
            next.block()
        }
        now = end
        tasks.removeAll { it.cancelled }
    }
}

class HoldPolicyTest {
    private val time = FakeTime()
    private val calls = mutableListOf<String>()
    private val ended = mutableListOf<HoldPolicy.Hold>()
    private var inFront = true
    private val p = HoldPolicy(
        object : HoldPolicy.Frames {
            override fun hold() { calls += "hold" }
            override fun letThrough() { calls += "through" }
        },
        time, { time.now },
    ).apply {
        latencyMs = 800
        mayHold = { inFront }
        onEnded = { ended += it }
    }

    private fun approach() { p.near = true; p.hover() }

    private fun stroke(ms: Long = 150) {
        p.touching = true; p.near = true; p.cancelPending(); p.down()
        time.advance(ms)
        p.touching = false; p.up()
    }

    @Test fun quickStrokesShareOneHoldUntilThePenPauses() {
        approach()
        repeat(6) { stroke(); time.advance(300) }
        assertEquals(listOf("hold"), calls, "nothing let through between quick strokes")
        assertTrue(p.holding)
        assertEquals(6, p.strokes)
        time.advance(500)
        assertEquals(listOf("hold", "through"), calls, "let through once the pen has rested 800 ms since the last lift")
        assertEquals(listOf(End.Pause), ended.map { it.end })
        assertEquals(6, ended.single().strokes)
    }

    @Test fun aNewTouchAlwaysCancelsAPendingSwap() {
        approach()
        stroke()
        time.advance(799)
        stroke(ms = 2_000)
        time.advance(799)
        assertEquals(listOf("hold"), calls)
        time.advance(1)
        assertEquals(listOf("hold", "through"), calls)
        assertEquals(2, ended.single().strokes)
    }

    @Test fun noSwapWhileThePenTouches() {
        approach()
        p.touching = true; p.cancelPending(); p.down()
        time.advance(20_000)
        assertEquals(listOf("hold"), calls, "a long stroke keeps its preview")
    }

    @Test fun thePauseIsTheOwnersChoice() {
        p.latencyMs = 2_000
        approach()
        stroke()
        time.advance(1_999)
        assertEquals(listOf("hold"), calls)
        time.advance(1)
        assertEquals(listOf("hold", "through"), calls)
    }

    @Test fun breaksLetTheFramesThroughAtOnceWithTheirReason() {
        for (reason in listOf(End.Away, End.Eraser, End.LeftApp, End.ScreenOff, End.Locked, End.PenLost)) {
            calls.clear(); ended.clear()
            approach()
            stroke(); time.advance(100); stroke()
            p.end(reason)
            assertEquals(listOf("hold", "through"), calls, "$reason")
            assertEquals(HoldPolicy.Hold(2, 400, reason), ended.single())
            time.advance(10_000)
            assertEquals(listOf("hold", "through"), calls, "nothing pending after $reason")
        }
    }

    @Test fun afterAPauseWithThePenHoveringTheHoldResumesThenIdles() {
        approach()
        stroke()
        time.advance(800)
        assertEquals(listOf("hold", "through"), calls)
        time.advance(HoldPolicy.SETTLE_MS - 1)
        assertEquals(listOf("hold", "through"), calls, "the panel is still showing the app's strokes")
        time.advance(1)
        assertEquals(listOf("hold", "through", "hold"), calls, "held again for the next stroke's start")
        time.advance(HoldPolicy.HOVER_IDLE_MS)
        assertEquals(listOf("hold", "through", "hold", "through"), calls, "a pen resting in range doesn't freeze the app")
        assertEquals(listOf(End.Pause to 1, End.HoverIdle to 0), ended.map { it.end to it.strokes })
    }

    @Test fun theNextStrokeStartsOnTheResumedHold() {
        approach()
        stroke()
        time.advance(800 + HoldPolicy.SETTLE_MS + 100)
        stroke()
        time.advance(800)
        assertEquals(listOf("hold", "through", "hold", "through"), calls)
        assertEquals(listOf(1, 1), ended.map { it.strokes })
    }

    @Test fun noResumedHoldOnceTheChosenAppHasGone() {
        approach()
        stroke()
        inFront = false
        time.advance(800 + HoldPolicy.SETTLE_MS + HoldPolicy.HOVER_IDLE_MS)
        assertEquals(listOf("hold", "through"), calls)
    }

    @Test fun noResumedHoldWhenThePenHasLeft() {
        approach()
        stroke()
        p.near = false
        time.advance(10_000)
        assertEquals(listOf("hold", "through"), calls)
    }

    @Test fun aHoverWithoutATouchIdlesOut() {
        approach()
        time.advance(HoldPolicy.HOVER_IDLE_MS - 1)
        assertTrue(p.holding)
        time.advance(1)
        assertFalse(p.holding)
        assertEquals(listOf("hold", "through"), calls)
        assertEquals(End.HoverIdle, ended.single().end)
    }

    @Test fun theWatchdogNeverLeavesFramesHeldForLong() {
        p.touching = true; p.near = true; p.down()
        time.advance(HoldPolicy.WATCHDOG_MS)
        assertEquals(listOf("hold", "through"), calls)
        assertEquals(End.Watchdog, ended.single().end)
    }

    @Test fun forgettingCancelsEverythingWithoutADisplayCall() {
        approach()
        stroke()
        p.forget(End.Off)
        assertFalse(p.holding)
        assertEquals(listOf("hold"), calls, "the session's release lets the frames through")
        assertEquals(HoldPolicy.Hold(1, 150, End.Off), ended.single())
        time.advance(60_000)
        assertEquals(listOf("hold"), calls)
    }

    @Test fun aLiftOrABreakWithNothingHeldDoesNothing() {
        p.up()
        p.end(End.Away)
        time.advance(60_000)
        assertEquals(emptyList(), calls)
        assertEquals(emptyList(), ended)
    }

    @Test fun anApproachRightAfterAReleaseWaitsForThePanel() {
        approach()
        stroke()
        p.near = false
        p.end(End.Away)
        time.advance(200)
        approach()
        assertEquals(listOf("hold", "through"), calls, "no hold while the app's strokes are still reaching the panel")
        time.advance(HoldPolicy.SETTLE_MS - 200)
        assertEquals(listOf("hold", "through", "hold"), calls, "held once the panel has settled")
    }

    @Test fun aTouchRightAfterAReleaseHoldsAtOnce() {
        approach()
        stroke()
        p.end(End.Eraser)
        time.advance(100)
        approach()
        p.touching = true; p.cancelPending(); p.down()
        assertEquals(listOf("hold", "through", "hold"), calls)
        time.advance(HoldPolicy.SETTLE_MS)
        assertEquals(listOf("hold", "through", "hold"), calls, "the waiting hover hold was dropped, not doubled")
        assertEquals(1, p.strokes)
    }

    @Test fun aWaitingHoldIsDroppedWhenThePenLeaves() {
        approach()
        stroke()
        p.end(End.LeftApp)
        approach()
        p.near = false
        time.advance(10_000)
        assertEquals(listOf("hold", "through"), calls)
    }

    @Test fun theFirstApproachHoldsAtOnce() {
        approach()
        assertEquals(listOf("hold"), calls)
    }

    @Test fun anUnheldStrokeIsReported() {
        var since: Long? = -1L
        p.onUnheldStroke = { since = it }
        p.touching = true; p.near = true; p.down()
        assertEquals(null, since, "no swap yet")
        p.end(End.Away); p.touching = false
        approach()
        stroke()
        time.advance(800)
        p.near = false
        time.advance(5_000)
        p.touching = true; p.near = true; p.cancelPending(); p.down()
        assertEquals(5_000L, since)
    }

    @Test fun savedPausesAreKeptWithinTheOfferedRange() {
        assertEquals(500, HoldPolicy.latencyFor(500))
        assertEquals(1_200, HoldPolicy.latencyFor(1_200))
        assertEquals(400, HoldPolicy.latencyFor(250), "earlier builds offered 250 ms")
        assertEquals(2_000, HoldPolicy.latencyFor(10_000))
        assertTrue(HoldPolicy.DEFAULT_LATENCY_MS in HoldPolicy.LATENCY_CHOICES)
        assertTrue(HoldPolicy.LATENCY_CHOICES.all { it in HoldPolicy.MIN_LATENCY_MS..HoldPolicy.MAX_LATENCY_MS })
    }
}
