package app.booxultimatum.kit.log

import org.junit.Test
import kotlin.test.assertEquals

class EventQueueTest {
    private fun e(message: String, level: Level) = event(message = message, level = level)
    private fun EventQueue.messages() = snapshot().map { it.message }

    @Test
    fun dropsTheIncomingLowEventWhenFullOfItsLevel() {
        val q = EventQueue(3)
        listOf("a", "b", "c").forEach { q.offer(e(it, Level.Info)) }
        q.offer(e("d", Level.Info))
        q.offer(e("v", Level.Verbose))
        assertEquals(listOf("a", "b", "c"), q.messages())
        assertEquals(2, q.dropped)
    }

    @Test
    fun warnEvictsTheOldestLowestEvent() {
        val q = EventQueue(3)
        q.offer(e("i1", Level.Info))
        q.offer(e("d1", Level.Debug))
        q.offer(e("d2", Level.Debug))
        q.offer(e("w", Level.Warn))
        assertEquals(listOf("i1", "d2", "w"), q.messages())
        assertEquals(1, q.dropped)
    }

    @Test
    fun aHigherLowEventEvictsALowerOne() {
        val q = EventQueue(3)
        q.offer(e("i1", Level.Info))
        q.offer(e("v1", Level.Verbose))
        q.offer(e("i2", Level.Info))
        q.offer(e("d", Level.Debug))
        assertEquals(listOf("i1", "i2", "d"), q.messages())
        q.offer(e("v2", Level.Verbose))
        assertEquals(listOf("i1", "i2", "d"), q.messages())
        assertEquals(2, q.dropped)
    }

    @Test
    fun warnAndErrorSurviveWhileLowEventsAreQueued() {
        val q = EventQueue(3)
        repeat(3) { q.offer(e("i$it", Level.Info)) }
        q.offer(e("e1", Level.Error))
        q.offer(e("w1", Level.Warn))
        q.offer(e("e2", Level.Error))
        assertEquals(listOf("e1", "w1", "e2"), q.messages())
        q.offer(e("i9", Level.Info))
        assertEquals(listOf("e1", "w1", "e2"), q.messages())
        assertEquals(4, q.dropped)
    }

    @Test
    fun withOnlyWarnAndErrorLeftTheOldestGoes() {
        val q = EventQueue(2)
        q.offer(e("w1", Level.Warn))
        q.offer(e("e1", Level.Error))
        q.offer(e("e2", Level.Error))
        assertEquals(listOf("e1", "e2"), q.messages())
        assertEquals(1, q.dropped)
    }

    @Test
    fun drainingEmptiesAndTakeDroppedResets() {
        val q = EventQueue(2)
        repeat(4) { q.offer(e("i$it", Level.Info)) }
        val out = ArrayList<LogEvent>()
        q.drainTo(out)
        assertEquals(listOf("i0", "i1"), out.map { it.message })
        assertEquals(0, q.size)
        assertEquals(2, q.takeDropped())
        assertEquals(0, q.takeDropped())
        q.offer(e("v", Level.Verbose))
        q.offer(e("w", Level.Warn))
        q.offer(e("i", Level.Info))
        assertEquals(listOf("w", "i"), q.messages(), "counts were reset by the drain")
    }
}
