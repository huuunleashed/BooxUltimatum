package app.booxultimatum.kit.log

import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogFileWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()

    @Test
    fun opensTheFirstFileLazily() {
        val dir = tmp.newFolder("logs")
        val w = LogFileWriter(dir, clock, header(), 1_000_000, 8)
        assertTrue(dir.listFiles().isNullOrEmpty())
        w.write(event())
        w.close()
        val file = assertNotNull(w.current)
        assertTrue(Regex("log-20260928-031500-4242\\.jsonl").matches(file.name), file.name)
        assertEquals(listOf("hello"), readEvents(dir).map { it.message })
    }

    @Test
    fun rotatesAtSizeWithANewHeaderPerFile() {
        val dir = tmp.newFolder("logs")
        val max = 600L
        val w = LogFileWriter(dir, clock, header(), max, 50)
        repeat(20) {
            clock.wall += 1000
            w.write(event(message = "event $it " + "x".repeat(100), wallMs = clock.wall))
        }
        w.close()
        val files = LogFiles.list(dir)
        assertTrue(files.size > 3, "files: ${files.size}")
        for (f in files) {
            assertTrue(f.length() <= max, "${f.name} is ${f.length()} bytes")
            val lines = f.readLines()
            val head = JSONObject(lines.first())
            assertEquals(1, head.getInt("schema"))
            assertEquals("0badc0de", head.getString("session"))
            assertNull(EventFormat.parse(lines.first()))
            assertTrue(lines.size >= 2)
        }
        assertEquals((0 until 20).map { "event $it " + "x".repeat(100) }, readEvents(dir).map { it.message })
    }

    @Test
    fun anOversizedEventStillGetsWritten() {
        val dir = tmp.newFolder("logs")
        val w = LogFileWriter(dir, clock, header(), 100, 8)
        w.write(event(message = "z".repeat(500)))
        clock.wall += 1000
        w.write(event(message = "next"))
        w.close()
        assertEquals(2, LogFiles.list(dir).size)
        assertEquals(listOf("z".repeat(500), "next"), readEvents(dir).map { it.message })
    }

    @Test
    fun rotationKeepsAtMostMaxFiles() {
        val dir = tmp.newFolder("logs")
        val w = LogFileWriter(dir, clock, header(), 300, 3)
        repeat(10) {
            clock.wall += 1000
            w.write(event(message = "e$it " + "x".repeat(150)))
        }
        w.close()
        val files = LogFiles.list(dir)
        assertEquals(3, files.size)
        assertTrue(w.current in files)
        assertEquals(listOf("e7", "e8", "e9"), readEvents(dir).map { it.message.substringBefore(' ') })
    }

    @Test
    fun writesAgainAfterClose() {
        val dir = tmp.newFolder("logs")
        val w = LogFileWriter(dir, clock, header(), 1_000_000, 8)
        w.write(event(message = "one"))
        w.close()
        clock.wall += 1000
        w.write(event(message = "two"))
        w.close()
        assertEquals(2, LogFiles.list(dir).size)
        assertEquals(listOf("one", "two"), readEvents(dir).map { it.message })
    }
}
