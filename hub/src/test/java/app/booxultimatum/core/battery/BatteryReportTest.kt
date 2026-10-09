package app.booxultimatum.core.battery

import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatteryReportTest {
    private val since = 1_000_000L
    private val header = BatteryCsv.HEADER
    private val topAt = header.split(',').indexOf("top")
    private val noteAt = header.split(',').indexOf("note")
    private val deep = """{"epoch":1000005,"reason":"tick","display":"on","bgAllowed":true,"foregroundSec":{"com.example.app":120},"alarmWakeups":[{"pkg":"com.example.app","wakeups":3}],"wakelockAcquisitions":{"com.example.app partial":4},"uidDelta":{"com.example.app":{"cpuMs":900}},"wakelocks":[{"type":1,"tag":"sync","uid":10001}],"exits":[]}"""

    private fun row(epoch: Long, reason: String, top: String = "", note: String = "") = BatteryCsv.line(
        listOf(epoch, reason, 80, "3000", 3900, 30.5, 0, 1, 1000L, 1000L, 6, -120, 40, 12, "connected", "none", 0, "on", top, note),
    )

    private fun field(line: String, at: Int) = line.split(',')[at]

    @Test fun headerLinesAreKeptAndRowsBeforeTheWindowAreDropped() {
        val lines = listOf(header, row(since - 1, "tick"), row(since, "tick"), row(since + 60_000, "tick")).asSequence()
        assertEquals(listOf(header, row(since, "tick"), row(since + 60_000, "tick")), BatteryReport.filterRows(lines, since, includeApps = true))
    }

    @Test fun theTopColumnIsBlankedUnlessAppNamesAreIncluded() {
        val lines = listOf(header, row(since, "tick", top = "com.example.app")).asSequence()
        assertEquals("com.example.app", field(BatteryReport.filterRows(lines, since, includeApps = true)[1], topAt))
        val without = BatteryReport.filterRows(lines, since, includeApps = false)
        assertEquals("", field(without[1], topAt))
        assertFalse(without[1].contains("com.example"))
    }

    @Test fun aNoteIsKeptForDisplayRowsAndForMarksOnlyWithAppNames() {
        val lines = listOf(
            header,
            row(since, "display_on", note = "999940"),
            row(since + 1, "display_stuck", note = "999400"),
            row(since + 2, "mark", note = "Applied Doze"),
            row(since + 3, "tick", note = "left over"),
        ).asSequence()
        val with = BatteryReport.filterRows(lines, since, includeApps = true)
        assertEquals("999940", field(with[1], noteAt))
        assertEquals("Applied Doze", field(with[3], noteAt))
        assertEquals("", field(with[4], noteAt))
        val without = BatteryReport.filterRows(lines, since, includeApps = false)
        assertEquals("999400", field(without[2], noteAt))
        assertEquals("", field(without[3], noteAt))
    }

    @Test fun badRowsAndRowsBeforeAnyHeaderAreDropped() {
        val lines = listOf(row(since + 1, "tick"), header, "", "x,tick", "${since + 2}", row(since + 3, "tick")).asSequence()
        assertEquals(listOf(header, row(since + 3, "tick")), BatteryReport.filterRows(lines, since, includeApps = true))
    }

    @Test fun anOlderLayoutWithoutTopOrNoteIsKeptAsItIs() {
        val v1 = "epoch,reason,level,charge_mAh,voltage_mV,temp_C,plugged,screen_on,elapsed_ms,uptime_ms"
        val line = "${since + 5},tick,80,3000,3900,30.5,0,1,1000,1000"
        assertEquals(listOf(v1, line), BatteryReport.filterRows(listOf(v1, line).asSequence(), since, includeApps = false))
    }

    @Test fun appKeysAreRemovedFromASnapshotWithoutAppNames() {
        val json = JSONObject(BatteryReport.redactDeep(deep, includeApps = false)!!)
        listOf("alarmWakeups", "wakelockAcquisitions", "uidDelta", "wakelocks", "foregroundSec").forEach { assertFalse(json.has(it), it) }
        assertEquals(1_000_005L, json.getLong("epoch"))
        assertEquals("on", json.getString("display"))
        assertTrue(json.getBoolean("bgAllowed"))
        assertTrue(json.has("exits"))
    }

    @Test fun aSnapshotWithAppNamesIsKeptAsItIs() {
        assertEquals(deep, BatteryReport.redactDeep(deep, includeApps = true))
    }

    @Test fun aLineThatIsNotAJsonObjectIsNull() {
        assertNull(BatteryReport.redactDeep("not json", includeApps = false))
        assertNull(BatteryReport.redactDeep("[1,2]", includeApps = true))
    }

    @Test fun snapshotsOutsideTheWindowAndBadLinesAreDropped() {
        val older = deep.replace("1000005", "999999")
        val out = BatteryReport.filterDeep(listOf(older, "junk", deep).asSequence(), since, includeApps = false)
        assertEquals(1, out.size)
        assertEquals(1_000_005L, JSONObject(out[0]).getLong("epoch"))
    }
}
