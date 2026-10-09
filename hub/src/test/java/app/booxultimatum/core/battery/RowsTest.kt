package app.booxultimatum.core.battery

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RowsTest {
    private val v2 = "epoch,reason,level,charge_mAh,voltage_mV,temp_C,plugged,screen_on,elapsed_ms,uptime_ms," +
        "boot_count,current_mA,frontlight,frontlight_ct,wifi,idle,saver"
    private val v3 = "$v2,display,top,note"

    private fun parse(vararg lines: String): List<BatteryRow> = BatteryRows.parse(lines.asSequence())

    @Test fun parsesAV1LineThatComesBeforeAnyHeader() {
        val row = parse("1000,tick,50,3000,4100,30.5,1,0,123456,98765").single()
        assertEquals(1000L, row.epoch)
        assertEquals("tick", row.reason)
        assertEquals(50, row.level)
        assertEquals(3000.0, row.chargeMah)
        assertEquals(4100, row.voltageMv)
        assertEquals(30.5, row.tempC)
        assertTrue(row.plugged)
        assertFalse(row.interactive)
        assertEquals(123456L, row.elapsedMs)
        assertEquals(98765L, row.uptimeMs)
        assertNull(row.bootCount)
        assertNull(row.display)
        assertEquals("", row.top)
    }

    @Test fun readsAV2FileByColumnNameAndLeavesBlanksEmpty() {
        val rows = parse(
            v2,
            "1791451860000,screen_on,73,,,,0,1,624120643,133123521,6,,,,connected,,0",
            "1791451800000,level,74,2756,4102,30.5,1,0,624060643,133063521,6,172,37,20,off,none,1",
        )
        assertEquals(listOf(1791451800000L, 1791451860000L), rows.map { it.epoch })
        val first = rows[0]
        assertEquals(2756.0, first.chargeMah)
        assertEquals(4102, first.voltageMv)
        assertTrue(first.plugged)
        assertEquals(6, first.bootCount)
        assertEquals(172.0, first.currentMa)
        assertEquals(37, first.frontlight)
        assertEquals(20, first.warmth)
        assertEquals("off", first.wifi)
        assertEquals("none", first.idle)
        assertTrue(first.saver)
        val second = rows[1]
        assertNull(second.chargeMah)
        assertNull(second.voltageMv)
        assertNull(second.tempC)
        assertNull(second.currentMa)
        assertNull(second.frontlight)
        assertNull(second.warmth)
        assertTrue(second.interactive)
        assertFalse(second.saver)
        assertEquals("connected", second.wifi)
        assertEquals("", second.idle)
    }

    @Test fun readsRowsAfterAV3HeaderWithDisplayTopAndNote() {
        val rows = parse(
            v2,
            "1000,level,80,3000,4100,30,0,0,1000,900,6,-300,,,off,none,0",
            v3,
            "2000,display_on,79,2950,4090,30,0,0,2000,1000,6,-300,,,off,none,0,doze,com.example.reader,mark: Applied Doze, quiet",
            "3000,tick,78,2900,4090,30,0,0,3000,2000,6,-300,,,off,none,0,off,,",
        )
        assertEquals(3, rows.size)
        assertNull(rows[0].display)
        assertEquals("", rows[0].top)
        assertEquals("", rows[0].note)
        assertEquals(DisplayState.Doze, rows[1].display)
        assertEquals("com.example.reader", rows[1].top)
        assertEquals("mark: Applied Doze, quiet", rows[1].note)
        assertEquals(DisplayState.Off, rows[2].display)
        assertEquals("", rows[2].note)
    }

    @Test fun skipsBlankShortAndUnparseableLines() {
        val rows = parse(
            v2,
            "",
            "1000,level,80",
            "x,level,80,3000,4100,30,0,0,1000,900,6,-300,,,off,none,0",
            "2000,level,high,3000,4100,30,0,0,2000,900,6,-300,,,off,none,0",
            "3000,level,79,2950,4090,30,0,0,3000,2000,6,-300,,,off,none,0",
        )
        assertEquals(listOf(3000L), rows.map { it.epoch })
    }

    @Test fun sortsByEpochAndKeepsFileOrderForEqualEpochs() {
        val rows = parse(
            "3000,tick,70,2900,4000,30,0,0,3000,2000",
            "1000,tick,80,3000,4100,30,0,0,1000,900",
            "1000,level,79,2950,4090,30,0,0,1000,900",
        )
        assertEquals(listOf(1000L, 1000L, 3000L), rows.map { it.epoch })
        assertEquals(listOf("tick", "level"), rows.take(2).map { it.reason })
    }
}
