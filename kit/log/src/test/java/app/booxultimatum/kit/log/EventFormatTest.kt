package app.booxultimatum.kit.log

import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventFormatTest {
    private fun json(s: String) = StringBuilder().also { Json.appendString(it, s) }.toString()

    @Test
    fun escapesQuotesBackslashesAndControlCharacters() {
        assertEquals("\"a\\\"b\\\\c/d\"", json("a\"b\\c/d"))
        assertEquals("\"\\u000a\\u000d\\u0009\\u0000\\u001f\"", json("\n\r\t\u0000\u001f"))
        assertEquals("\"é 漢 😀 \u007f\"", json("é 漢 😀 \u007f"))
    }

    @Test
    fun escapesUnpairedSurrogates() {
        assertEquals("\"\\ud800x\"", json("\ud800x"))
        assertEquals("\"\\udc00\"", json("\udc00"))
        assertEquals("\"x\\ud83d\"", json("x\ud83d"))
    }

    @Test
    fun escapedStringsReadBackUnchanged() {
        val tricky = "q\"b\\s/\n\r\t\u0001\u001f é 漢 😀 \u2028 end"
        assertEquals(tricky, JSONObject("{\"v\":${json(tricky)}}").getString("v"))
    }

    @Test
    fun eventRoundTripsThroughParse() {
        val e = event(
            message = "stroke \"done\"\nnext",
            level = Level.Warn,
            category = "nib.pen",
            fields = linkedMapOf("points" to "120", "quote" to "a\"b", "tab" to "\t"),
            error = "java.lang.IllegalStateException: boom\n\tat a.B.c(B.kt:1)",
        )
        val line = EventFormat.event(e)
        assertFalse(line.contains('\n'))
        assertEquals(e, Logbook.parse(line))
    }

    @Test
    fun eventLineHasTheDocumentedShape() {
        val line = EventFormat.event(event(fields = mapOf("k" to "v")))
        assertEquals(
            "{\"t\":$T0,\"m\":123456789,\"l\":\"I\",\"c\":\"test\",\"msg\":\"hello\",\"f\":{\"k\":\"v\"}," +
                "\"th\":\"main\",\"p\":4242,\"s\":\"0badc0de\"}",
            line,
        )
    }

    @Test
    fun everyLevelRoundTrips() {
        for (level in Level.entries) assertEquals(level, EventFormat.parse(EventFormat.event(event(level = level)))?.level)
    }

    @Test
    fun headerLineHasEveryFieldAndParsesToNull() {
        val line = EventFormat.header(header())
        val o = JSONObject(line)
        assertEquals(1, o.getInt("schema"))
        assertEquals("app.booxultimatum.test", o.getString("app"))
        assertEquals("1.2.3", o.getString("version"))
        assertEquals(42L, o.getLong("versionCode"))
        assertFalse(o.getBoolean("debuggable"))
        assertEquals("ONYX NoteAir6C", o.getString("device"))
        assertEquals("16 (36)", o.getString("android"))
        assertEquals("0badc0de", o.getString("session"))
        assertEquals(4242, o.getInt("pid"))
        assertEquals(T0, o.getLong("started"))
        assertNull(Logbook.parse(line))
    }

    @Test
    fun garbageParsesToNull() {
        for (bad in listOf("", "   ", "not json", "{", "[1,2]", "{\"t\":1}", "{\"t\":1,\"l\":\"X\",\"c\":\"a\",\"msg\":\"b\"}")) {
            assertNull(Logbook.parse(bad), bad)
        }
    }

    @Test
    fun fieldValuesAreStringified() {
        val f = EventFormat.fields(
            arrayOf("n" to null, "i" to 3, "l" to 5L, "b" to true, "d" to 1.5, "s" to "x", "list" to listOf(1, 2)),
        )
        assertEquals(
            mapOf("n" to "null", "i" to "3", "l" to "5", "b" to "true", "d" to "1.5", "s" to "x", "list" to "[1, 2]"),
            f,
        )
        assertEquals(listOf("n", "i", "l", "b", "d", "s", "list"), f.keys.toList())
        assertTrue(EventFormat.fields(emptyArray()).isEmpty())
    }

    @Test
    fun longValuesAndMessagesAreTruncated() {
        val v = EventFormat.fields(arrayOf("s" to "y".repeat(600)))["s"]!!
        assertEquals(500, v.length)
        assertTrue(v.endsWith("\u2026"))
        val m = EventFormat.truncate("m".repeat(3000), EventFormat.MAX_MESSAGE)
        assertEquals(2000, m.length)
        assertEquals("short", EventFormat.truncate("short", 10))
    }

    @Test
    fun truncationNeverSplitsASurrogatePair() {
        val s = "a".repeat(8) + "😀" + "b".repeat(10)
        val t = EventFormat.truncate(s, 10)
        assertEquals("a".repeat(8) + "\u2026", t)
    }

    @Test
    fun failingToStringDoesNotThrow() {
        val bad = object {
            override fun toString(): String = throw IllegalStateException("no")
        }
        assertTrue(EventFormat.value(bad).startsWith("<"))
    }

    @Test
    fun millisHaveTwoDecimals() {
        assertEquals("12.34", EventFormat.millis(12_345_678))
        assertEquals("1.05", EventFormat.millis(1_050_000))
        assertEquals("0.00", EventFormat.millis(5_000))
        assertEquals("0.00", EventFormat.millis(-5))
    }

    @Test
    fun logcatMessageAppendsFields() {
        assertEquals("pen down x=1 y=2", EventFormat.logcatMessage("pen down", linkedMapOf("x" to "1", "y" to "2")))
        assertEquals("plain", EventFormat.logcatMessage("plain", emptyMap()))
    }

    @Test
    fun utf8LengthMatchesEncoding() {
        for (s in listOf("", "ascii", "é", "漢字", "😀", "mix é 漢 😀 \u0001", "\ud800")) {
            val expected = if (s == "\ud800") 3 else s.toByteArray(Charsets.UTF_8).size
            assertEquals(expected, Json.utf8Length(s), s)
        }
    }
}
