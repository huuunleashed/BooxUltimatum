package app.booxultimatum.core.battery

import android.content.Context
import app.booxultimatum.core.BatteryLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Reads the three-hourly `deep-*.jsonl` snapshots. */
object DeepLog {
    /**
     * Wake sources between [fromMs] and [toMs]: wake lock acquisitions and CPU time summed from the snapshots' deltas, and
     * alarm wakeups as the difference between consecutive snapshots of one boot (cumulative since boot; a drop means a reboot).
     * System tags are grouped under pkg "android". Largest first. Empty when the folder holds no snapshots in the window.
     */
    fun wakeSources(context: Context, fromMs: Long, toMs: Long): List<WakeSource> {
        val dir = BatteryLog.dir(context)
        val lines = deepFilesFor(dir.list().orEmpty().toList(), fromMs, toMs, ZoneId.systemDefault())
            .flatMap { name -> runCatching { File(dir, name).readLines() }.getOrDefault(emptyList()) }
        return parseWakeSources(lines.asSequence(), fromMs, toMs)
    }
}

/** The day files that can hold snapshots of the window: its days, with one day either side, oldest first. */
internal fun deepFilesFor(names: List<String>, fromMs: Long, toMs: Long, zone: ZoneId): List<String> {
    val first = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate().minusDays(1)
    val last = Instant.ofEpochMilli(toMs).atZone(zone).toLocalDate().plusDays(1)
    return names.sorted().filter { name ->
        val day = DEEP_FILE.matchEntire(name)?.let { m -> runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull() }
        day != null && !day.isBefore(first) && !day.isAfter(last)
    }
}

/**
 * The wake sources of the snapshots taken in (fromMs, toMs]. A snapshot summarises the time before it, so alarm wakeups
 * are the difference from the snapshot before it, which is the baseline when it was taken before the window. A boot's
 * snapshot restarts the count. Lines that don't parse are skipped.
 */
internal fun parseWakeSources(lines: Sequence<String>, fromMs: Long, toMs: Long): List<WakeSource> {
    val snapshots = lines.mapNotNull(::parseSnapshot).toList().sortedBy { it.epoch }
    val wakeups = HashMap<String, Long>()
    val locks = HashMap<String, Long>()
    val cpu = HashMap<String, Long>()
    val lastAlarm = HashMap<String, Int>()
    var seenBefore = false
    for (s in snapshots) {
        if (s.boot) lastAlarm.clear()
        if (s.epoch > fromMs && s.epoch <= toMs) {
            if (seenBefore || s.boot) {
                for (a in s.alarms) wakeups.bump(a.pkg, wakeupsSince(lastAlarm[a.raw] ?: 0, a.wakeups))
            }
            s.locks.forEach { (pkg, n) -> locks.bump(pkg, n) }
            s.cpuMs.forEach { (pkg, ms) -> cpu.bump(pkg, ms) }
        }
        s.alarms.forEach { lastAlarm[it.raw] = it.wakeups }
        seenBefore = true
    }
    return (wakeups.keys + locks.keys + cpu.keys).map { pkg ->
        WakeSource(pkg, (wakeups[pkg] ?: 0L).toInt(), (locks[pkg] ?: 0L).toInt(), cpu[pkg] ?: 0L)
    }
        .filter { it.wakeups != 0 || it.lockCount != 0 || it.cpuMs != 0L }
        .sortedWith(compareByDescending<WakeSource> { it.wakeups + it.lockCount.toLong() }.thenByDescending { it.cpuMs })
}

private class DeepSnapshot(
    val epoch: Long,
    val boot: Boolean,
    val alarms: List<AlarmCount>,
    val locks: Map<String, Long>,
    val cpuMs: Map<String, Long>,
)

/** An alarm count as logged ([raw], cumulative since boot) and the package it counts toward ([pkg]). */
private class AlarmCount(val raw: String, val pkg: String, val wakeups: Int)

private fun parseSnapshot(line: String): DeepSnapshot? = runCatching {
    val o = JSONObject(line)
    val locks = HashMap<String, Long>()
    o.optJSONObject("wakelockAcquisitions")?.let { acquired ->
        acquired.keys().forEach { key -> locks.bump(canonical(key.substringBefore(' ')), acquired.optLong(key)) }
    }
    val cpu = HashMap<String, Long>()
    o.optJSONObject("uidDelta")?.let { deltas ->
        deltas.keys().forEach { name -> cpu.bump(canonical(name), deltas.optJSONObject(name)?.optLong("cpuMs") ?: 0L) }
    }
    DeepSnapshot(
        epoch = o.getLong("epoch"),
        boot = o.optString("reason") == "boot",
        alarms = o.optJSONArray("alarmWakeups").objects().map { a ->
            val tags = a.optJSONObject("tags")
            val timeTick = tags != null && tags.length() > 0 && tags.keys().asSequence().all { TIME_TICK.matches(it) }
            val raw = a.optString("pkg")
            AlarmCount(raw, if (timeTick) ANDROID else canonical(raw), a.optInt("wakeups"))
        },
        locks = locks,
        cpuMs = cpu,
    )
}.getOrNull()

private fun JSONArray?.objects(): List<JSONObject> =
    this?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }.orEmpty()

private fun MutableMap<String, Long>.bump(pkg: String, n: Long) {
    this[pkg] = (this[pkg] ?: 0L) + n
}

/** A reading below the one before it means the counter restarted at boot, so the reading itself is the count. */
private fun wakeupsSince(before: Int, now: Int): Long = (if (now >= before) now - before else now).toLong()

private const val ANDROID = "android"
private val SYSTEM_UID = Regex("""uid\d+""")
private val DEEP_FILE = Regex("""deep-(\d{4}-\d{2}-\d{2})\.jsonl""")

/** The alarm tag of the one-minute clock tick, which counts as Android itself. */
private val TIME_TICK = Regex(""".*walarm.*:TIME_TICK""")

/** The system, its owner name ("System") and its uids count as one package. */
private fun canonical(name: String): String =
    if (name == "System" || name == ANDROID || SYSTEM_UID.matches(name)) ANDROID else name
