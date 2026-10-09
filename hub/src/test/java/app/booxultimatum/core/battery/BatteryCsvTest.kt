package app.booxultimatum.core.battery

import android.view.Display
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatteryCsvTest {
    private val v2Columns = listOf(
        "epoch", "reason", "level", "charge_mAh", "voltage_mV", "temp_C", "plugged", "screen_on", "elapsed_ms", "uptime_ms",
        "boot_count", "current_mA", "frontlight", "frontlight_ct", "wifi", "idle", "saver",
    )

    @Test fun headerKeepsTheSeventeenColumnsAndAddsDisplayTopAndNote() {
        assertEquals(v2Columns + listOf("display", "top", "note"), BatteryCsv.HEADER.split(','))
        assertTrue(BatteryCsv.HEADER.endsWith(",display,top,note"))
    }

    @Test fun sanitizeNoteReplacesSeparatorsThenTrimsAndCuts() {
        assertEquals("a b  c", BatteryCsv.sanitizeNote("a,b\r\nc"))
        assertEquals("hi", BatteryCsv.sanitizeNote("  hi \n"))
        assertEquals("", BatteryCsv.sanitizeNote(" \t "))
        assertEquals(80, BatteryCsv.sanitizeNote("x".repeat(200)).length)
    }

    @Test fun lineJoinsWithCommasAndNullIsEmpty() {
        assertEquals("1,tick,,2.5,true", BatteryCsv.line(listOf(1, "tick", null, 2.5, true)))
        assertEquals("", BatteryCsv.line(emptyList()))
    }

    @Test fun aV3RowStillParsesItsTenLeadingColumnsByPosition() {
        val row = BatteryCsv.line(
            listOf(
                1_700_000_000_000L, "tick", 81, "3412", 3900, 31.5, 1, 0, 123_456L, 98_765L,
                6, "-120", 40, 12, "connected", "none", 0, "off", "com.example.app", BatteryCsv.sanitizeNote("a,note"),
            ),
        )
        val p = row.split(',')
        assertEquals(20, p.size)
        // The reads BatteryLog.samples() makes of the first ten columns.
        assertEquals(1_700_000_000_000L, p[0].toLong())
        assertEquals("tick", p[1])
        assertEquals(81, p[2].toInt())
        assertEquals(3412.0, p[3].toDoubleOrNull())
        assertEquals(3900, p[4].toIntOrNull())
        assertEquals(31.5, p[5].toDoubleOrNull())
        assertEquals("1", p[6])
        assertEquals("0", p[7])
        assertEquals(123_456L, p[8].toLong())
        assertEquals(98_765L, p[9].toLong())
        assertEquals(listOf("off", "com.example.app", "a note"), p.drop(17))
    }

    @Test fun theLastHeaderOfAFileWins() {
        assertEquals("epoch,new", BatteryCsv.lastHeader(sequenceOf("epoch,old", "1,tick", "epoch,new", "2,tick")))
        assertNull(BatteryCsv.lastHeader(sequenceOf("1,tick")))
        assertNull(BatteryCsv.lastHeader(emptySequence()))
    }

    @Test fun theFrontIsTheLatestResumedPackageNotPausedSince() {
        assertEquals("", BatteryCsv.frontmost(emptyList()))
        assertEquals("", BatteryCsv.frontmost(listOf("a" to false)))
        assertEquals("b", BatteryCsv.frontmost(listOf("a" to true, "b" to true)))
        assertEquals("a", BatteryCsv.frontmost(listOf("a" to true, "b" to true, "b" to false)))
        assertEquals("a", BatteryCsv.frontmost(listOf("a" to true, "b" to true, "a" to true, "b" to false)))
        assertEquals("b", BatteryCsv.frontmost(listOf("a" to true, "b" to true, "b" to false, "a" to false)))
        assertEquals("a", BatteryCsv.frontmost(listOf("a" to true, "a" to false, "b" to false)))
    }

    @Test fun displayStatesGetTheirColumnNames() {
        assertEquals("on", BatteryCsv.displayColumn(Display.STATE_ON))
        assertEquals("doze", BatteryCsv.displayColumn(Display.STATE_DOZE))
        assertEquals("doze", BatteryCsv.displayColumn(Display.STATE_DOZE_SUSPEND))
        assertEquals("off", BatteryCsv.displayColumn(Display.STATE_OFF))
        assertEquals("other", BatteryCsv.displayColumn(Display.STATE_UNKNOWN))
    }
}
