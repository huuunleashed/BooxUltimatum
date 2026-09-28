package app.booxultimatum.kit.log

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogPipelineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private fun writtenMessages(dir: File) = runCatching { readEvents(dir).map { it.message } }.getOrDefault(emptyList())

    @Test
    fun overflowWritesOneDroppedEventOnceThereIsRoom() {
        val dir = tmp.newFolder("logs")
        val pipeline = LogPipeline(clock, "0badc0de", 4242, capacity = 3)
        val writer = LogFileWriter(dir, clock, header(), 1_000_000, 8)
        pipeline.attach(writer)
        repeat(5) { pipeline.enqueue(event(message = "e$it", level = Level.Debug)) }
        assertEquals(3, pipeline.size)
        pipeline.drainNow()
        repeat(2) { pipeline.enqueue(event(message = "late$it")) }
        pipeline.drainNow()
        pipeline.drainNow()
        writer.close()
        val events = readEvents(dir)
        assertEquals(listOf("e0", "e1", "e2", "dropped", "late0", "late1"), events.map { it.message })
        val dropped = events.single { it.message == "dropped" }
        assertEquals("log", dropped.category)
        assertEquals(mapOf("count" to "2"), dropped.fields)
        assertEquals("0badc0de", dropped.session)
        assertEquals(4242, dropped.pid)
    }

    @Test
    fun eventsQueuedBeforeStartAreWrittenOnceTheWriterRuns() {
        val dir = tmp.newFolder("logs")
        val pipeline = LogPipeline(SystemLogClock, "0badc0de", 4242)
        assertFalse(pipeline.flush(100), "no writer yet")
        repeat(3) { pipeline.enqueue(event(message = "early$it")) }
        pipeline.start { LogFileWriter(dir, SystemLogClock, header(), 1_000_000, 8) }
        assertTrue(pipeline.flush(5_000))
        pipeline.enqueue(event(message = "later"))
        assertTrue(pipeline.flush(5_000))
        assertEquals(listOf("early0", "early1", "early2", "later"), writtenMessages(dir))
        pipeline.start { error("a second start is ignored") }
        assertTrue(pipeline.flush(5_000))
    }

    @Test
    fun warnIsWrittenWithoutWaitingForTheInterval() {
        val dir = tmp.newFolder("logs")
        val pipeline = LogPipeline(SystemLogClock, "s", 1, intervalNanos = 60_000_000_000L)
        pipeline.start { LogFileWriter(dir, SystemLogClock, header(), 1_000_000, 8) }
        pipeline.enqueue(event(message = "info"))
        pipeline.enqueue(event(message = "warn", level = Level.Warn))
        assertTrue(waitFor { writtenMessages(dir) == listOf("info", "warn") })
    }

    @Test
    fun infoWaitsForTheIntervalThenGetsWritten() {
        val dir = tmp.newFolder("logs")
        val pipeline = LogPipeline(SystemLogClock, "s", 1, intervalNanos = 300_000_000L)
        pipeline.start { LogFileWriter(dir, SystemLogClock, header(), 1_000_000, 8) }
        pipeline.enqueue(event(message = "info"))
        assertTrue(waitFor { writtenMessages(dir) == listOf("info") })
    }

    @Test
    fun aHalfFullQueueWakesTheWriter() {
        val dir = tmp.newFolder("logs")
        val pipeline = LogPipeline(SystemLogClock, "s", 1, capacity = 8, intervalNanos = 60_000_000_000L)
        pipeline.start { LogFileWriter(dir, SystemLogClock, header(), 1_000_000, 8) }
        repeat(4) { pipeline.enqueue(event(message = "i$it")) }
        assertTrue(waitFor { writtenMessages(dir).size == 4 })
    }

    @Test
    fun aFailedSetupStillDrainsTheQueue() {
        val pipeline = LogPipeline(SystemLogClock, "s", 1)
        pipeline.enqueue(event())
        pipeline.start { throw IllegalStateException("no disk") }
        assertTrue(pipeline.flush(5_000))
        assertEquals(0, pipeline.size)
    }
}
