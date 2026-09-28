package app.booxultimatum.kit.log

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RingBufferTest {
    private fun messages(events: List<LogEvent>) = events.map { it.message }

    @Test
    fun keepsTheNewestInOrder() {
        val ring = RingBuffer(3)
        assertTrue(ring.snapshot().isEmpty())
        ring.add(event("a"))
        ring.add(event("b"))
        assertEquals(listOf("a", "b"), messages(ring.snapshot()))
        ring.add(event("c"))
        ring.add(event("d"))
        ring.add(event("e"))
        assertEquals(3, ring.size)
        assertEquals(listOf("c", "d", "e"), messages(ring.snapshot()))
    }

    @Test
    fun snapshotReturnsTheNewestMax() {
        val ring = RingBuffer(5)
        "abcdefg".forEach { ring.add(event(it.toString())) }
        assertEquals(listOf("f", "g"), messages(ring.snapshot(2)))
        assertEquals(listOf("c", "d", "e", "f", "g"), messages(ring.snapshot(50)))
        assertTrue(ring.snapshot(0).isEmpty())
        assertTrue(ring.snapshot(-1).isEmpty())
    }

    @Test
    fun resizeKeepsTheNewestThatFit() {
        val ring = RingBuffer(5)
        "abcde".forEach { ring.add(event(it.toString())) }
        ring.resize(3)
        assertEquals(3, ring.capacity)
        assertEquals(listOf("c", "d", "e"), messages(ring.snapshot()))
        ring.resize(10)
        ring.add(event("f"))
        assertEquals(listOf("c", "d", "e", "f"), messages(ring.snapshot()))
        ring.resize(0)
        assertEquals(1, ring.capacity)
        assertEquals(listOf("f"), messages(ring.snapshot()))
    }
}
