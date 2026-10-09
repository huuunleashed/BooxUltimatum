package app.booxultimatum.core.battery

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

/** The epochs of the three lines in `battery/deep-sample.jsonl`: two ticks, then the boot after them. */
private const val TICK_1 = 1791396601662L
private const val TICK_2 = 1791421801428L
private const val BOOT = 1791451971354L

class DeepLogTest {
    private class Alarm(val pkg: String, val wakeups: Int, val tags: Map<String, Int> = emptyMap())

    /** A snapshot line as the log writes it: [locks] are acquisitions since the last one, [cpu] is cpuMs by uid name or package. */
    private fun snap(
        epoch: Long,
        reason: String = "tick",
        alarms: List<Alarm> = emptyList(),
        locks: Map<String, Int> = emptyMap(),
        cpu: Map<String, Long> = emptyMap(),
    ): String = JSONObject()
        .put("epoch", epoch)
        .put("reason", reason)
        .put("since", epoch - 3_600_000L)
        .put(
            "alarmWakeups",
            JSONArray(alarms.map { a -> JSONObject().put("pkg", a.pkg).put("wakeups", a.wakeups).put("alarms", a.wakeups).put("tags", JSONObject(a.tags)) }),
        )
        .put("wakelockAcquisitions", JSONObject(locks))
        .put("uidDelta", JSONObject(cpu.mapValues { (_, ms) -> JSONObject().put("uid", 0).put("cpuMs", ms).put("wifiRxKB", 0) }))
        .toString()

    private fun sample(): List<String> =
        DeepLogTest::class.java.getResourceAsStream("/battery/deep-sample.jsonl")!!.bufferedReader().use { it.readLines() }

    @Test
    fun theRealSnapshotsGiveTheWakeSourcesBetweenThem() {
        assertEquals(
            listOf(
                WakeSource("android", 1, 204, 0),
                WakeSource("app.booxultimatum", 0, 59, 0),
                WakeSource("com.onyx.mail", 0, 16, 0),
                WakeSource("com.digibites.accubattery", 0, 15, 0),
                WakeSource("com.onyx.appmarket", 0, 11, 0),
                WakeSource("com.android.networkstack", 3, 0, 0),
            ),
            parseWakeSources(sample().asSequence(), TICK_1, TICK_2),
        )
    }

    @Test
    fun aBootWindowHasLocksButNoAlarmWakeups() {
        val wakes = parseWakeSources(sample().asSequence(), TICK_2, BOOT)
        assertEquals(
            setOf(
                WakeSource("android", 0, 99, 0),
                WakeSource("com.android.systemui", 0, 2, 0),
                WakeSource("com.android.providers.media.module", 0, 2, 0),
            ),
            wakes.toSet(),
        )
        assertEquals(3, wakes.size)
    }

    @Test
    fun sumsTheSnapshotsInsideTheWindow() {
        val lines = arrayOf(
            snap(1000, alarms = listOf(Alarm("android", 100)), locks = mapOf("System onyx_a" to 5), cpu = mapOf("uid1000" to 10L, "com.foo" to 40L)),
            snap(
                2000,
                alarms = listOf(Alarm("android", 130), Alarm("com.zero", 0)),
                locks = mapOf("System onyx_a" to 7, "com.foo bar" to 2),
                cpu = mapOf("uid1000" to 20L, "com.foo" to 60L),
            ),
            snap(3000, alarms = listOf(Alarm("android", 135)), locks = mapOf("System onyx_b" to 1, "com.foo baz" to 4), cpu = mapOf("uid1000" to 30L, "com.foo" to 5L)),
            snap(4000, alarms = listOf(Alarm("android", 999)), locks = mapOf("System onyx_c" to 100), cpu = mapOf("com.foo" to 1000L)),
        )
        assertEquals(
            listOf(WakeSource("android", 35, 8, 50), WakeSource("com.foo", 0, 6, 65)),
            parseWakeSources(lines.asSequence(), 1500, 3500),
        )
    }

    @Test
    fun theLastSnapshotBeforeTheWindowIsTheBaseline() {
        val lines = arrayOf(
            snap(1000, alarms = listOf(Alarm("android", 100))),
            snap(1200, alarms = listOf(Alarm("android", 110))),
            snap(2000, alarms = listOf(Alarm("android", 130))),
        )
        assertEquals(listOf(WakeSource("android", 20, 0, 0)), parseWakeSources(lines.asSequence(), 1500, 2500))
    }

    @Test
    fun aWindowWithNoEarlierSnapshotCountsFromItsSecondSnapshot() {
        val lines = arrayOf(
            snap(2000, alarms = listOf(Alarm("android", 130))),
            snap(3000, alarms = listOf(Alarm("android", 135))),
        )
        assertEquals(listOf(WakeSource("android", 5, 0, 0)), parseWakeSources(lines.asSequence(), 1500, 3500))
    }

    @Test
    fun aCounterThatFallsIsCountedFromZero() {
        val lines = arrayOf(
            snap(1000, alarms = listOf(Alarm("android", 500))),
            snap(2000, alarms = listOf(Alarm("android", 40))),
        )
        assertEquals(listOf(WakeSource("android", 40, 0, 0)), parseWakeSources(lines.asSequence(), 0, 3000))
    }

    @Test
    fun aBootRestartsTheCountFromZero() {
        val lines = arrayOf(
            snap(1000, alarms = listOf(Alarm("android", 50))),
            snap(2000, reason = "boot", alarms = listOf(Alarm("android", 80))),
        )
        assertEquals(listOf(WakeSource("android", 80, 0, 0)), parseWakeSources(lines.asSequence(), 0, 3000))
    }

    @Test
    fun systemOwnersAndUidsAreGroupedAsAndroid() {
        val lines = arrayOf(
            snap(1000),
            snap(
                2000,
                alarms = listOf(
                    Alarm("com.google.x", 6, mapOf("*walarm*:TIME_TICK" to 6)),
                    Alarm("com.bar", 3, mapOf("*walarm*:TIME_TICK" to 2, "*walarm*:other" to 1)),
                ),
                locks = mapOf("System onyx_a" to 4, "System" to 1, "com.app tag" to 2),
                cpu = mapOf("uid1000" to 10L, "uid1013" to 5L, "com.app" to 20L),
            ),
        )
        assertEquals(
            listOf(WakeSource("android", 6, 5, 15), WakeSource("com.bar", 3, 0, 0), WakeSource("com.app", 0, 2, 20)),
            parseWakeSources(lines.asSequence(), 0, 3000),
        )
    }

    @Test
    fun aWindowWithNoSnapshotsIsEmpty() {
        val lines = arrayOf(snap(1000, locks = mapOf("System onyx_a" to 3)))
        assertEquals(emptyList(), parseWakeSources(lines.asSequence(), 2000, 3000))
        assertEquals(emptyList(), parseWakeSources(emptySequence(), 0, 3000))
    }

    @Test
    fun aMalformedLineIsSkipped() {
        val lines = sequenceOf(
            snap(2000, locks = mapOf("com.a x" to 2)),
            "{\"epoch\":2500,",
            "",
            "[1,2]",
            "{\"reason\":\"tick\"}",
            snap(3000, locks = mapOf("com.a x" to 3)),
        )
        assertEquals(listOf(WakeSource("com.a", 0, 5, 0)), parseWakeSources(lines, 1000, 4000))
    }

    @Test
    fun largestFirstAndTiesByCpu() {
        val lines = arrayOf(
            snap(1000),
            snap(
                2000,
                alarms = listOf(Alarm("com.a", 5), Alarm("com.b", 3), Alarm("com.d", 0)),
                locks = mapOf("com.b x" to 2, "com.c y" to 1),
                cpu = mapOf("com.a" to 100L, "com.b" to 300L),
            ),
        )
        assertEquals(
            listOf(WakeSource("com.b", 3, 2, 300), WakeSource("com.a", 5, 0, 100), WakeSource("com.c", 0, 1, 0)),
            parseWakeSources(lines.asSequence(), 0, 3000),
        )
    }

    @Test
    fun theDayFilesAroundTheWindowAreRead() {
        val names = listOf(
            "deep-2026-10-09.jsonl", "deep-2026-10-06.jsonl", "battery-2026-10.csv", "deep-2026-10-08.jsonl",
            "deep-2026-10-07.jsonl", "deep-oops.jsonl",
        )
        val from = Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()
        val to = Instant.parse("2026-10-08T13:00:00Z").toEpochMilli()
        assertEquals(
            listOf("deep-2026-10-07.jsonl", "deep-2026-10-08.jsonl", "deep-2026-10-09.jsonl"),
            deepFilesFor(names, from, to, ZoneOffset.UTC),
        )
    }
}
