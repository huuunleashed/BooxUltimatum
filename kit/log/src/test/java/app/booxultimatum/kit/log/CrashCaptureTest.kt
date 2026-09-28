package app.booxultimatum.kit.log

import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CrashCaptureTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun crashFileHoldsTheTraceAndTheRecentEvents() {
        val dir = tmp.newFolder("logs")
        val recent = (1..250).map { event(message = "r$it", fields = mapOf("i" to "$it")) }.takeLast(CrashFile.RECENT)
        val error = IllegalStateException("outer", RuntimeException("inner cause"))
        val file = CrashFile.write(dir, T0, "main", "0badc0de", "app.booxultimatum.nib", "1.2.3", 99, error, recent, 20)
        assertEquals("crash-20260928-031500.json", file.name)
        val o = JSONObject(file.readText())
        assertEquals(T0, o.getLong("t"))
        assertEquals("main", o.getString("thread"))
        assertEquals("0badc0de", o.getString("session"))
        assertEquals("app.booxultimatum.nib", o.getString("app"))
        assertEquals("1.2.3", o.getString("version"))
        assertEquals(99, o.getInt("pid"))
        val trace = o.getString("error")
        assertTrue(trace.startsWith("java.lang.IllegalStateException: outer"), trace)
        assertTrue("Caused by: java.lang.RuntimeException: inner cause" in trace, trace)
        assertTrue("\tat " in trace)
        val events = o.getJSONArray("recent")
        assertEquals(200, events.length())
        val first = assertNotNull(EventFormat.parse(events.getJSONObject(0).toString()))
        assertEquals("r51", first.message)
        assertEquals(mapOf("i" to "51"), first.fields)
        assertEquals("r250", EventFormat.parse(events.getJSONObject(199).toString())?.message)
    }

    @Test
    fun crashFileWithoutOptionalFields() {
        val o = JSONObject(CrashFile.content(T0, "t", "s", null, null, 1, Error("x"), emptyList()))
        assertTrue(!o.has("app") && !o.has("version"))
        assertEquals(0, o.getJSONArray("recent").length())
    }

    @Test
    fun handlerRecordsThenDelegatesToThePreviousHandler() {
        val calls = mutableListOf<String>()
        val previous = Thread.UncaughtExceptionHandler { t, e -> calls += "previous ${t.name} ${e.message}" }
        val handler = CrashHandler(previous, { _, _ -> calls += "fallback" }) { t, e -> calls += "record ${t.name} ${e.message}" }
        val thread = Thread({}, "worker")
        handler.uncaughtException(thread, RuntimeException("boom"))
        assertEquals(listOf("record worker boom", "previous worker boom"), calls)
    }

    @Test
    fun handlerNeverThrows() {
        val calls = mutableListOf<String>()
        val previous = Thread.UncaughtExceptionHandler { _, _ ->
            calls += "previous"
            throw IllegalStateException("previous failed")
        }
        val handler = CrashHandler(previous, { _, _ -> calls += "fallback" }) { _, _ ->
            calls += "record"
            throw OutOfMemoryError("record failed")
        }
        handler.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        handler.uncaughtException(Thread.currentThread(), RuntimeException("again"))
        assertEquals(listOf("record", "previous", "record", "previous"), calls)
    }

    @Test
    fun handlerFallsBackWithoutAPreviousHandler() {
        val calls = mutableListOf<String>()
        val handler = CrashHandler(null, { _, e -> calls += "fallback ${e.message}" }) { _, _ -> calls += "record" }
        handler.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        assertEquals(listOf("record", "fallback boom"), calls)
    }

    @Test
    fun aCrashWhileRecordingSkipsTheSecondRecord() {
        val calls = mutableListOf<String>()
        lateinit var handler: CrashHandler
        handler = CrashHandler({ _, e -> calls += "previous ${e.message}" }, { _, _ -> }) { t, _ ->
            calls += "record"
            handler.uncaughtException(t, RuntimeException("nested"))
        }
        handler.uncaughtException(Thread.currentThread(), RuntimeException("first"))
        assertEquals(listOf("record", "previous nested", "previous first"), calls)
    }
}
