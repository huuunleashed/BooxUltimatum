package app.booxultimatum.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.booxultimatum.core.exec.Privileged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One light sample from the battery log. */
data class LogSample(
    val epoch: Long, val reason: String, val level: Int, val chargeMah: Double?, val voltageMv: Int?, val tempC: Double?,
    val plugged: Boolean, val screenOn: Boolean, val elapsed: Long, val uptime: Long,
)

/** Drain between two consecutive samples, split by whether the tablet mostly slept in between. */
data class LogSegment(val from: LogSample, val to: LogSample) {
    val hours get() = (to.elapsed - from.elapsed) / 3_600_000.0
    val asleepFraction get() = (1.0 - (to.uptime - from.uptime).toDouble() / (to.elapsed - from.elapsed)).coerceIn(0.0, 1.0)
    val drainPct get() = (from.level - to.level).toDouble()
    val drainMah get() = if (from.chargeMah != null && to.chargeMah != null) from.chargeMah - to.chargeMah else null
    val valid get() = to.elapsed > from.elapsed && !from.plugged && !to.plugged && hours > 0.05 && (drainMah ?: drainPct) >= 0
}

data class LogSummary(
    val samples: Int,
    val spanHours: Double,
    val asleepPctPerHour: Double?,
    val awakePctPerHour: Double?,
    val asleepHours: Double,
    val awakeHours: Double,
    val recent: List<LogSample>,
)

/**
 * The battery log. Samples are taken by an inexact, non-wakeup alarm: it only fires when the tablet is already
 * awake, so recording adds no wakeups at all. The first sample after the tablet wakes shows how much it drained
 * while asleep. A deeper diagnostic snapshot (Doze state, alarm wakeups by app, held wakelocks) is added every
 * few hours. Files live in Android/data/app.booxultimatum/files/logs for export and analysis.
 */
object BatteryLog {
    private const val INTERVAL = AlarmManager.INTERVAL_HALF_HOUR
    private const val DEEP_EVERY = 3L * 3600 * 1000
    private const val OPEN_GAP = 10L * 60 * 1000
    private const val KEEP_DAYS = 60
    const val ACTION_SAMPLE = "app.booxultimatum.action.LOG_SAMPLE"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun dir(c: Context) = File(c.getExternalFilesDir(null), "logs").apply { mkdirs() }
    private fun prefs(c: Context) = c.getSharedPreferences("battery_log", Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("enabled", true)

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("enabled", on).apply()
        if (on) schedule(c) else cancel(c)
    }

    private fun pending(c: Context) = PendingIntent.getBroadcast(
        c, 0, Intent(c, BatteryLogReceiver::class.java).setAction(ACTION_SAMPLE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * ELAPSED_REALTIME (not _WAKEUP) + inexact: the system batches it with other work and never wakes for it.
     * Scheduling again would restart the countdown, so an alarm that is already set is left alone: opening the app
     * often must not keep pushing the next sample away. Boot and force-stop clear it, and then it is set afresh.
     */
    fun schedule(c: Context, force: Boolean = false) {
        if (!enabled(c)) return
        val existing = PendingIntent.getBroadcast(
            c, 0, Intent(c, BatteryLogReceiver::class.java).setAction(ACTION_SAMPLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )
        if (existing != null && !force) return
        c.getSystemService(AlarmManager::class.java).setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + INTERVAL, INTERVAL, pending(c),
        )
    }

    private fun cancel(c: Context) {
        c.getSystemService(AlarmManager::class.java).cancel(pending(c))
        pending(c).cancel()
    }

    /** Records a light sample now, and a deep one if it is due. Runs off the main thread, one record at a time. */
    fun record(c: Context, reason: String, done: (() -> Unit)? = null) {
        if (!enabled(c)) { done?.invoke(); return }
        val app = c.applicationContext
        // Opening the app is a free moment to sample, but not a reason to crowd the log: at most one every 10 minutes.
        if (reason == "open" && System.currentTimeMillis() - prefs(app).getLong("last_sample", 0) < OPEN_GAP) { done?.invoke(); return }
        scope.launch {
            lock.withLock {
                runCatching { writeSample(app, reason); prefs(app).edit().putLong("last_sample", System.currentTimeMillis()).apply() }
                runCatching {
                    val last = prefs(app).getLong("last_deep", 0)
                    if (System.currentTimeMillis() - last > DEEP_EVERY || reason == "boot") {
                        prefs(app).edit().putLong("last_deep", System.currentTimeMillis()).commit()
                        writeDeep(app, reason)
                    }
                }
                runCatching { prune(app) }
            }
            done?.invoke()
        }
    }

    private val lock = kotlinx.coroutines.sync.Mutex()

    /**
     * Boox background-restricts some apps it did not ship. If that ever happens to BooxUltimatum the log would stop
     * sampling, so with Shizuku available the app lifts the restriction on itself.
     */
    fun ensureNotRestricted(c: Context) {
        scope.launch {
            if (!Privileged.ready()) return@launch
            if (SystemState.backgroundMode(c.packageName) == BgMode.Ignore) {
                SystemState.setBackgroundMode(c.packageName, BgMode.Allow)
                Journal.log(c, "self", "BooxUltimatum background restriction lifted", "", true)
            }
        }
    }

    private fun day(t: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))

    private fun writeSample(c: Context, reason: String) {
        val b = BatterySnapshot.read(c)
        val f = File(dir(c), "battery-${day(b.readAtMillis).substring(0, 7)}.csv")
        if (!f.exists()) f.writeText("epoch,reason,level,charge_mAh,voltage_mV,temp_C,plugged,screen_on,elapsed_ms,uptime_ms\n")
        f.appendText(
            listOf(
                b.readAtMillis, reason, b.levelPct, b.chargeCounterMah?.let { "%.0f".format(Locale.US, it) } ?: "",
                b.voltageMv ?: "", b.tempC ?: "", if (b.source != PowerSource.None) 1 else 0, if (b.interactive) 1 else 0,
                SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(),
            ).joinToString(",") + "\n",
        )
    }

    private suspend fun writeDeep(c: Context, reason: String) {
        val o = JSONObject().put("epoch", System.currentTimeMillis()).put("reason", reason)
        SystemState.doze()?.let { o.put("doze", JSONObject().put("deepEnabled", it.deepEnabled).put("lightEnabled", it.lightEnabled).put("deep", it.deep).put("light", it.light)) }
        SystemState.alarmWakeups()?.let { list -> o.put("alarmWakeups", JSONArray().apply { list.take(20).forEach { put(JSONObject().put("pkg", it.pkg).put("wakeups", it.wakeups).put("alarms", it.alarms)) } }) }
        SystemState.heldWakelocks()?.let { list -> o.put("wakelocks", JSONArray().apply { list.forEach { put(JSONObject().put("type", it.type).put("tag", it.tag).put("uid", it.owner)) } }) }
        o.put("shizuku", Privileged.ready())
        o.put("powerSave", c.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode)
        File(dir(c), "deep-${day(System.currentTimeMillis())}.jsonl").appendText(o.toString() + "\n")
    }

    private fun prune(c: Context) {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000
        dir(c).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    fun samples(c: Context, sinceMs: Long = 7L * 24 * 3600 * 1000): List<LogSample> {
        val from = System.currentTimeMillis() - sinceMs
        return dir(c).listFiles { f -> f.name.startsWith("battery-") && f.name.endsWith(".csv") }.orEmpty().sortedBy { it.name }
            .flatMap { f -> f.readLines().drop(1) }
            .mapNotNull { line ->
                val p = line.split(',')
                if (p.size < 10) return@mapNotNull null
                runCatching {
                    LogSample(p[0].toLong(), p[1], p[2].toInt(), p[3].toDoubleOrNull(), p[4].toIntOrNull(), p[5].toDoubleOrNull(), p[6] == "1", p[7] == "1", p[8].toLong(), p[9].toLong())
                }.getOrNull()
            }
            .filter { it.epoch >= from }
    }

    /** Standby versus awake drain from consecutive unplugged samples over the last week. */
    fun summary(c: Context): LogSummary {
        val s = samples(c)
        val segs = s.zipWithNext { a, b -> LogSegment(a, b) }.filter { it.valid }
        val asleep = segs.filter { it.asleepFraction >= 0.8 }
        val awake = segs.filter { it.asleepFraction < 0.5 }
        fun rate(list: List<LogSegment>): Double? {
            val h = list.sumOf { it.hours }
            return if (h < 0.5) null else list.sumOf { it.drainPct } / h
        }
        return LogSummary(
            samples = s.size,
            spanHours = if (s.size > 1) (s.last().epoch - s.first().epoch) / 3_600_000.0 else 0.0,
            asleepPctPerHour = rate(asleep),
            awakePctPerHour = rate(awake),
            asleepHours = asleep.sumOf { it.hours },
            awakeHours = awake.sumOf { it.hours },
            recent = s.takeLast(400),
        )
    }

    fun files(c: Context): List<File> = dir(c).listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }

    /**
     * Every log in one zip, with a README naming the tablet, firmware and file formats, so the bundle explains itself
     * when it is opened on a computer. Blocking; call off the main thread. Earlier exports are replaced.
     */
    fun bundle(c: Context): File? {
        val logs = files(c)
        if (logs.isEmpty()) return null
        val out = File(c.getExternalFilesDir(null), "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val zip = File(out, "booxultimatum-battery-$stamp.zip")
        java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("README.txt"))
            val info = c.packageManager.getPackageInfo(c.packageName, 0)
            z.write(
                buildString {
                    appendLine("BooxUltimatum battery log export, ${SimpleDateFormat("yyyy-MM-dd HH:mm Z", Locale.US).format(Date())}")
                    appendLine("App ${info.versionName} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · firmware ${android.os.Build.DISPLAY} · Android ${android.os.Build.VERSION.RELEASE}")
                    appendLine()
                    appendLine("battery-YYYY-MM.csv: one light sample per row (level, charge counter in mAh, voltage, temperature, plugged, screen on,")
                    appendLine("  elapsed and uptime in ms). Between two rows, 1 - Δuptime/Δelapsed is the share of time spent asleep.")
                    appendLine("deep-YYYY-MM-DD.jsonl: every few hours, Doze state, alarm wakeups by app and held wakelocks.")
                }.toByteArray(),
            )
            z.closeEntry()
            logs.forEach { f ->
                z.putNextEntry(java.util.zip.ZipEntry(f.name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return zip
    }
}

/** Receives the log alarm and boot; manifest-registered, not exported. */
class BatteryLogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "update"
            else -> "tick"
        }
        // Boot and app updates clear alarms, so the schedule is always set again here.
        if (reason != "tick") BatteryLog.schedule(context, force = true)
        BatteryLog.record(context, reason) { result.finish() }
    }
}
