package app.booxultimatum.kit.log

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LogCoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val logcat = mutableListOf<String>()
    private var logcatError: Throwable? = null
    private val core = LogCore(clock, "0badc0de", 4242, queueCapacity = 64) { level, tag, message, error ->
        logcat += "${level.code} $tag $message"
        logcatError = error
    }

    private fun emit(category: String, level: Level, message: String, vararg fields: Pair<String, Any?>, error: Throwable? = null) =
        core.emit(category, "BU/$category", level, message, fields, error)

    @Test
    fun eventsBeforeTheFileSinkStartsAreKeptAndWrittenLater() {
        emit("ink", Level.Debug, "pen down", "x" to 1, "y" to null)
        emit("misc", Level.Debug, "quiet")
        emit("misc", Level.Info, "hello", "k" to "v")
        assertEquals(listOf("pen down", "hello"), core.ring.snapshot().map { it.message })
        assertEquals(2, core.pipeline.size)
        assertEquals(listOf("I BU/misc hello k=v"), logcat)

        val dir = tmp.newFolder("logs")
        val writer = LogFileWriter(dir, clock, header(), 1_000_000, 8)
        core.pipeline.attach(writer)
        core.pipeline.drainNow()
        writer.close()
        val written = readEvents(dir)
        assertEquals(listOf("pen down", "hello"), written.map { it.message })
        assertEquals(mapOf("x" to "1", "y" to "null"), written[0].fields)
        assertEquals(T0, written[0].wallMs)
        assertEquals("0badc0de", written[0].session)
        assertEquals(4242, written[0].pid)
        assertEquals(Thread.currentThread().name, written[0].thread)
    }

    @Test
    fun theRingKeepsEventsThatOnlyReachLogcat() {
        core.configure(LogConfig(fileLevel = Level.Warn, debugCategories = emptySet()), Level.Debug)
        emit("misc", Level.Debug, "to logcat")
        emit("misc", Level.Verbose, "nowhere")
        assertEquals(listOf("to logcat"), core.ring.snapshot().map { it.message })
        assertEquals(0, core.pipeline.size)
        assertEquals(listOf("D BU/misc to logcat"), logcat)
    }

    @Test
    fun configureResizesTheRing() {
        core.configure(LogConfig(ringSize = 2), Level.Info)
        repeat(5) { emit("misc", Level.Info, "m$it") }
        assertEquals(listOf("m3", "m4"), core.ring.snapshot().map { it.message })
    }

    @Test
    fun errorsCarryTheirStackTrace() {
        val boom = IllegalArgumentException("bad input")
        emit("update", Level.Warn, "verify failed", "asset" to "x.apk", error = boom)
        val e = core.ring.snapshot().single()
        val trace = assertNotNull(e.error)
        assertTrue(trace.startsWith("java.lang.IllegalArgumentException: bad input"))
        assertSame(boom, logcatError)
        emit("update", Level.Info, "ok")
        assertNull(core.ring.snapshot().last().error)
    }

    @Test
    fun longMessagesAreTruncated() {
        emit("misc", Level.Info, "m".repeat(5000))
        assertEquals(EventFormat.MAX_MESSAGE, core.ring.snapshot().single().message.length)
    }

    @Test
    fun detailedModeWritesVerbose() {
        core.policy.detailedUntil = clock.wall + 1000
        emit("misc", Level.Verbose, "fine grain")
        assertEquals(1, core.pipeline.size)
        assertTrue(logcat.isEmpty())
        clock.wall += 1000
        emit("misc", Level.Verbose, "after expiry")
        assertEquals(1, core.pipeline.size)
    }

    @Test
    fun emitNeverThrows() {
        val throwing = LogCore(clock, "s", 1) { _, _, _, _ -> throw IllegalStateException("logcat down") }
        val bad = object {
            override fun toString(): String = throw IllegalStateException("no")
        }
        throwing.emit("misc", "BU/misc", Level.Error, "still fine", arrayOf("bad" to bad), RuntimeException("x"))
        assertEquals(listOf("still fine"), throwing.ring.snapshot().map { it.message })
    }
}
