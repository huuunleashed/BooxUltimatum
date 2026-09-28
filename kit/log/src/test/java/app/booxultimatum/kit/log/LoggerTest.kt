package app.booxultimatum.kit.log

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The public API on the shared [Logbook], before init (no Context on the JVM). */
class LoggerTest {
    @Test
    fun loggersAreCachedPerCategory() {
        assertSame(Logbook.logger("test.cache"), Logbook.logger("test.cache"))
        assertEquals("test.cache", Logbook.logger("test.cache").category)
    }

    @Test
    fun sessionIsEightHexCharacters() {
        assertTrue(Regex("[0-9a-f]{8}").matches(Logbook.session), Logbook.session)
    }

    @Test
    fun eventsReachTheRingBeforeInit() {
        val log = Logbook.logger("test.ring")
        log.i("opened", "pages" to 3, "name" to null)
        log.w("slow", "ms" to 250L, error = IllegalStateException("late"))
        log.e("failed", error = RuntimeException("x"))
        val mine = Logbook.recent().filter { it.category == "test.ring" }
        assertEquals(listOf("opened", "slow", "failed"), mine.map { it.message })
        assertEquals(listOf(Level.Info, Level.Warn, Level.Error), mine.map { it.level })
        assertEquals(mapOf("pages" to "3", "name" to "null"), mine[0].fields)
        assertTrue(mine[1].error!!.startsWith("java.lang.IllegalStateException: late"))
        assertTrue(mine.all { it.session == Logbook.session })
        assertTrue(Logbook.recent(1).size == 1)
    }

    @Test
    fun debugBuildsTheMessageOnlyWhenEnabled() {
        var built = 0
        Logbook.logger("test.quiet").debug {
            built++
            "never"
        }
        assertEquals(0, built)
        Logbook.logger("ink.test").debug {
            built++
            "pen"
        }
        assertEquals(1, built)
        assertTrue(Logbook.recent().any { it.category == "ink.test" && it.message == "pen" && it.level == Level.Debug })
    }

    @Test
    fun verboseAndDebugAreOffByDefault() {
        val log = Logbook.logger("test.levels")
        assertTrue(!log.isEnabled(Level.Verbose) && !log.isEnabled(Level.Debug))
        assertTrue(log.isEnabled(Level.Info) && log.isEnabled(Level.Error))
        assertTrue(Logbook.logger("nib.pen").isEnabled(Level.Debug))
        log.v("hidden")
        log.d("hidden")
        assertTrue(Logbook.recent().none { it.category == "test.levels" })
    }

    @Test
    fun spanReturnsTheValueAndLogsItsDuration() {
        val value = Logbook.logger("ink.span").span("render", "strokes" to 3) {
            Thread.sleep(5)
            42
        }
        assertEquals(42, value)
        val e = Logbook.recent().last { it.category == "ink.span" }
        assertEquals("render", e.message)
        assertEquals(Level.Debug, e.level)
        assertEquals("3", e.fields["strokes"])
        val ms = e.fields.getValue("ms")
        assertTrue(Regex("\\d+\\.\\d\\d").matches(ms), ms)
        assertTrue(ms.toDouble() >= 4.0, ms)
    }

    @Test
    fun spanLogsEvenWhenTheBlockThrows() {
        val log = Logbook.logger("ink.throws")
        val result = runCatching { log.span<Unit>("fail") { error("inside") } }
        assertTrue(result.isFailure)
        assertTrue(Logbook.recent().any { it.category == "ink.throws" && it.message == "fail" })
    }

    @Test
    fun spanSkipsTimingWhenDebugIsOff() {
        assertEquals("v", Logbook.logger("test.nospan").span("work") { "v" })
        assertTrue(Logbook.recent().none { it.category == "test.nospan" })
    }
}
