package app.booxultimatum.core.battery

import org.junit.Test
import kotlin.test.assertEquals

class ForegroundTest {
    private fun resumed(pkg: String, ms: Long) = UsageEvent(pkg, 1, ms)
    private fun paused(pkg: String, ms: Long) = UsageEvent(pkg, 2, ms)
    private fun span(pkg: String, start: Long, end: Long) = ForegroundSpan(pkg, start, end)

    @Test
    fun appsTakeTurnsInFront() {
        val events = listOf(
            resumed("A", 100), paused("A", 200), resumed("B", 200), paused("B", 300), resumed("A", 300), paused("A", 500),
        )
        assertEquals(listOf(span("A", 100, 200), span("B", 200, 300), span("A", 300, 500)), spans(events, 0, 1000))
    }

    @Test
    fun aRepeatedResumeKeepsOneSpan() {
        val events = listOf(resumed("A", 100), resumed("A", 150), paused("A", 200))
        assertEquals(listOf(span("A", 100, 200)), spans(events, 0, 1000))
    }

    @Test
    fun aResumeOfAnotherAppEndsThePreviousSpan() {
        val events = listOf(resumed("A", 100), resumed("B", 200), paused("B", 300))
        assertEquals(listOf(span("A", 100, 200), span("B", 200, 300)), spans(events, 0, 1000))
    }

    @Test
    fun aPauseOfAnAppNotInFrontIsIgnored() {
        val events = listOf(resumed("A", 100), paused("B", 150), paused("A", 200))
        assertEquals(listOf(span("A", 100, 200)), spans(events, 0, 1000))
    }

    @Test
    fun aSpanStillOpenAtTheEndRunsToTheEnd() {
        assertEquals(listOf(span("A", 100, 400)), spans(listOf(resumed("A", 100)), 0, 400))
    }

    @Test
    fun spansAreClippedToTheWindowAndEmptyOnesDropped() {
        val events = listOf(resumed("A", 0), resumed("B", 50), paused("B", 150), resumed("A", 160), paused("A", 500))
        assertEquals(listOf(span("A", 200, 400)), spans(events, 200, 400))
    }

    @Test
    fun eventsAfterTheWindowAreClipped() {
        val events = listOf(resumed("A", 100), resumed("B", 900))
        assertEquals(listOf(span("A", 100, 500)), spans(events, 0, 500))
    }

    @Test
    fun aZeroLengthSpanIsDropped() {
        assertEquals(emptyList(), spans(listOf(resumed("A", 100), paused("A", 100)), 0, 1000))
    }

    @Test
    fun eventsAreReadInTimeOrder() {
        val events = listOf(paused("A", 200), resumed("A", 100))
        assertEquals(listOf(span("A", 100, 200)), spans(events, 0, 1000))
    }

    @Test
    fun noEventsNoSpans() {
        assertEquals(emptyList(), spans(emptyList(), 0, 1000))
    }
}
