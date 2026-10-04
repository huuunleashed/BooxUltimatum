package app.booxultimatum.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
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

/**
 * Drain between two consecutive samples. Screen and plug transitions are rows of their own, so the screen state at the
 * start holds for the whole segment. On Boox the SoC also suspends with the screen on (sys.onyx.idledelay), so the
 * sleep fraction is reported, not used to tell standby from use.
 */
data class LogSegment(val from: LogSample, val to: LogSample) {
    val hours get() = (to.elapsed - from.elapsed) / 3_600_000.0
    val asleepFraction get() = (1.0 - (to.uptime - from.uptime).toDouble() / (to.elapsed - from.elapsed)).coerceIn(0.0, 1.0)
    val drainPct get() = (from.level - to.level).toDouble()
    val drainMah get() = if (from.chargeMah != null && to.chargeMah != null) from.chargeMah - to.chargeMah else null

    /** The charge counter wobbles by a few mAh; a standby segment reading -2 mAh is 0, not a charge. */
    val drainMahClamped get() = drainMah?.let { if (it in -3.0..0.0) 0.0 else it }
    val screenOff get() = !from.screenOn
    val valid get() = to.elapsed > from.elapsed && to.uptime >= from.uptime && !from.plugged && !to.plugged && hours > 0.02 && (drainMahClamped ?: drainPct) >= 0
}

/** asleep* now means screen off (standby) and awake* screen on; [screenOnSleepPct] is how much of screen-on time the SoC slept. */
data class LogSummary(
    val samples: Int,
    val spanHours: Double,
    val asleepPctPerHour: Double?,
    val awakePctPerHour: Double?,
    val asleepHours: Double,
    val awakeHours: Double,
    val recent: List<LogSample>,
    val screenOnSleepPct: Double? = null,
)

/**
 * The battery log. Samples are taken by an inexact, non-wakeup alarm: it only fires when the tablet is already
 * awake, so recording adds no wakeups at all. Screen, plug, level and Doze transitions add rows from broadcasts the
 * system sends anyway (see [watch]), so standby and use are split exactly. A deeper diagnostic snapshot (Doze state
 * and history, alarm wakeups by app and tag, wakelock activity, CPU and Wi-Fi by uid, foreground time, own process
 * exits) is added every few hours. Files live in Android/data/app.booxultimatum/files/logs for export and analysis.
 */
object BatteryLog {
    private const val INTERVAL = AlarmManager.INTERVAL_HALF_HOUR
    private const val DEEP_EVERY = 3L * 3600 * 1000
    private const val OPEN_GAP = 10L * 60 * 1000
    private const val KEEP_DAYS = 60
    private const val HEADER = "epoch,reason,level,charge_mAh,voltage_mV,temp_C,plugged,screen_on,elapsed_ms,uptime_ms," +
        "boot_count,current_mA,frontlight,frontlight_ct,wifi,idle,saver"
    private const val FRONTLIGHT = "/sys/class/backlight/onyx_bl_br/brightness"
    private const val FRONTLIGHT_CT = "/sys/class/backlight/onyx_bl_ct/brightness"
    const val ACTION_SAMPLE = "app.booxultimatum.action.LOG_SAMPLE"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watching = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastLevel = -1

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
     * or reinstalling it often must not keep pushing the next sample away. A real boot and a force-stop cancel the
     * alarm (its PendingIntent is gone), so the check below re-arms it exactly when needed; an update keeps it.
     */
    fun schedule(c: Context) {
        if (!enabled(c)) return
        val existing = PendingIntent.getBroadcast(
            c, 0, Intent(c, BatteryLogReceiver::class.java).setAction(ACTION_SAMPLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )
        if (existing != null) return
        c.getSystemService(AlarmManager::class.java).setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + INTERVAL, INTERVAL, pending(c),
        )
    }

    private fun bootCount(c: Context): Int? = runCatching { Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()

    /**
     * Android 15+ also delivers BOOT_COMPLETED when a force-stopped app is next started (verified on FW 4.3: every
     * `boot` row matched a FORCE STOP in `dumpsys activity exit-info` while elapsedRealtime kept growing). The global
     * boot count only changes on a real boot.
     */
    fun isNewBoot(c: Context): Boolean {
        val now = bootCount(c)
        val p = prefs(c)
        val last = p.getInt("boot_count", -1)
        if (now != null) p.edit().putInt("boot_count", now).apply()
        return if (now == null || last == -1) SystemClock.elapsedRealtime() < 10 * 60_000L else now != last
    }

    /**
     * Rows for screen, power, level and Doze transitions, from broadcasts the system sends anyway; registered once per
     * process (the home screen keeps it alive) and never waking the tablet. ACTION_BATTERY_CHANGED arrives often while
     * awake, but only a level step on battery is written, so the rest cost one comparison.
     */
    fun watch(app: Context) {
        if (!watching.compareAndSet(false, true)) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) addAction(PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val reason = when (i.action) {
                    Intent.ACTION_SCREEN_ON -> "screen_on"
                    Intent.ACTION_SCREEN_OFF -> "screen_off"
                    Intent.ACTION_POWER_CONNECTED -> "plug"
                    Intent.ACTION_POWER_DISCONNECTED -> "unplug"
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> "doze_deep"
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> "saver"
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val first = lastLevel < 0
                        if (level < 0 || level == lastLevel) return
                        lastLevel = level
                        // The sticky intent delivered on registration is not a step; charging steps are not needed.
                        if (first || i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) return
                        "level"
                    }
                    else -> if (Build.VERSION.SDK_INT >= 33 && i.action == PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED) "doze_light" else return
                }
                val pending = goAsync()
                record(c, reason) { pending.finish() }
            }
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
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
                        writeDeep(app, reason, last)
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
        // An app may read its own app-ops without any permission; the Shizuku helper starts only when there is work.
        val restricted = runCatching {
            val ops = c.getSystemService(android.app.AppOpsManager::class.java)
            ops.unsafeCheckOpNoThrow("android:run_any_in_background", android.os.Process.myUid(), c.packageName) == android.app.AppOpsManager.MODE_IGNORED
        }.getOrDefault(true)
        if (!restricted) return
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
        // Rows written before the extra columns existed keep their own file, so each file has a single layout.
        if (f.exists() && f.bufferedReader().use { it.readLine() } != HEADER) f.renameTo(File(dir(c), f.name.removeSuffix(".csv") + ".v1.csv"))
        if (!f.exists()) f.writeText(HEADER + "\n")
        val pm = c.getSystemService(PowerManager::class.java)
        val idle = when {
            pm.isDeviceIdleMode -> "deep"
            Build.VERSION.SDK_INT >= 33 && pm.isDeviceLightIdleMode -> "light"
            else -> "none"
        }
        f.appendText(
            listOf(
                b.readAtMillis, reason, b.levelPct, b.chargeCounterMah?.let { "%.0f".format(Locale.US, it) } ?: "",
                b.voltageMv ?: "", b.tempC ?: "", if (b.source != PowerSource.None) 1 else 0, if (b.interactive) 1 else 0,
                SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(),
                bootCount(c) ?: "", b.currentNowMa?.let { "%.0f".format(Locale.US, it) } ?: "",
                // Plain app reads of these sysfs nodes may be refused by SELinux; the deep snapshot retries through Shizuku.
                Shell.readFile(FRONTLIGHT) ?: "", Shell.readFile(FRONTLIGHT_CT) ?: "",
                wifiState(c), idle, if (pm.isPowerSaveMode) 1 else 0,
            ).joinToString(",") + "\n",
        )
    }

    private fun wifiState(c: Context): String = runCatching {
        val wm = c.getSystemService(android.net.wifi.WifiManager::class.java)
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
        when {
            !wm.isWifiEnabled -> "off"
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "connected"
            else -> "on"
        }
    }.getOrDefault("")

    private suspend fun writeDeep(c: Context, reason: String, since: Long) {
        val o = JSONObject().put("epoch", System.currentTimeMillis()).put("reason", reason).put("since", since)
        SystemState.doze(preferApp = true)?.let {
            o.put(
                "doze",
                JSONObject().put("deepEnabled", it.deepEnabled).put("lightEnabled", it.lightEnabled).put("deep", it.deep).put("light", it.light)
                    .put("screenOn", it.screenOn).put("charging", it.charging).put("history", JSONArray(it.history)),
            )
        }
        SystemState.alarmWakeups(preferApp = true)?.let { list ->
            o.put("alarmWakeups", JSONArray().apply {
                list.take(20).forEach { w ->
                    val tags = JSONObject().apply { w.tags.entries.sortedByDescending { it.value }.take(5).forEach { put(it.key, it.value) } }
                    put(JSONObject().put("pkg", w.pkg).put("wakeups", w.wakeups).put("alarms", w.alarms).put("tags", tags))
                }
            })
        }
        SystemState.power(since, preferApp = true)?.let { p ->
            o.put("wakelocks", JSONArray().apply { p.held.forEach { put(JSONObject().put("type", it.type).put("tag", it.tag).put("uid", it.owner)) } })
            o.put("wakelockAcquisitions", JSONObject().apply { p.acquisitions.forEach { (k, v) -> put(k, v) } })
            p.onyxPm?.let { o.put("onyxPm", it) }
        }
        SystemState.uidStats(preferApp = true)?.let { now -> o.put("uidDelta", uidDelta(c, now)) }
        o.put("foregroundSec", foregroundSince(c, maxOf(since, System.currentTimeMillis() - 24L * 3600 * 1000)))
        o.put("exits", exitsSince(c, since))
        val fl = Shell.readFile(FRONTLIGHT)
        o.put("frontlight", fl ?: if (Privileged.ready()) Privileged.sh("cat $FRONTLIGHT $FRONTLIGHT_CT").out.trim().replace('\n', '/') else "")
        o.put("shizuku", Privileged.ready())
        o.put("powerSave", c.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode)
        File(dir(c), "deep-${day(System.currentTimeMillis())}.jsonl").appendText(o.toString() + "\n")
    }

    /** CPU ms and Wi-Fi bytes per uid since the previous snapshot; batterystats resets at full charge, so a drop means a reset. */
    private fun uidDelta(c: Context, now: Map<Int, UidStat>): JSONObject {
        val f = File(c.filesDir, "uid-stats.json")
        val before = runCatching { JSONObject(f.readText()) }.getOrNull()
        val pm = c.packageManager
        val rows = now.map { (uid, s) ->
            val b = before?.optJSONArray(uid.toString())
            val cpu = s.cpuMs - (b?.optLong(0) ?: 0)
            val rx = s.wifiRxBytes - (b?.optLong(1) ?: 0)
            Triple(uid, if (cpu < 0) s.cpuMs else cpu, if (rx < 0) s.wifiRxBytes else rx)
        }
        f.writeText(JSONObject().apply { now.forEach { (uid, s) -> put(uid.toString(), JSONArray().put(s.cpuMs).put(s.wifiRxBytes)) } }.toString())
        return JSONObject().apply {
            rows.sortedByDescending { it.second }.take(12).forEach { (uid, cpu, rx) ->
                val name = if (uid < 10000) "uid$uid" else pm.getPackagesForUid(uid)?.firstOrNull() ?: uid.toString()
                put(name, JSONObject().put("uid", uid).put("cpuMs", cpu).put("wifiRxKB", rx / 1024))
            }
        }
    }

    /** Foreground seconds per app since [since], from UsageStats (PACKAGE_USAGE_STATS is adb-granted, T1). */
    private fun foregroundSince(c: Context, since: Long): JSONObject = JSONObject().apply {
        runCatching {
            val events = c.getSystemService(android.app.usage.UsageStatsManager::class.java).queryEvents(since, System.currentTimeMillis())
            val e = android.app.usage.UsageEvents.Event()
            val started = HashMap<String, Long>()
            val total = HashMap<String, Long>()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                when (e.eventType) {
                    android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> started[e.packageName] = e.timeStamp
                    android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED ->
                        started.remove(e.packageName)?.let { total[e.packageName] = (total[e.packageName] ?: 0L) + (e.timeStamp - it) }
                }
            }
            total.entries.sortedByDescending { it.value }.take(10).forEach { put(it.key, it.value / 1000) }
        }
    }

    /** Our own process deaths since [since]: a force-stop also cancels the log alarm, so gaps in the log are explained. */
    private fun exitsSince(c: Context, since: Long): JSONArray = JSONArray().apply {
        runCatching {
            c.getSystemService(android.app.ActivityManager::class.java).getHistoricalProcessExitReasons(null, 0, 16)
                .filter { it.timestamp > since }
                .forEach { put(JSONObject().put("t", it.timestamp).put("reason", it.reason).put("what", it.description ?: "").put("process", it.processName)) }
        }
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
            .sortedBy { it.epoch }
    }

    /**
     * Standby (screen off) versus in-use (screen on) drain from consecutive unplugged samples over the last week.
     * Rates come from the charge counter when both ends have it (1 mAh steps instead of 1 % steps).
     */
    fun summary(c: Context): LogSummary {
        val s = samples(c)
        val segs = s.zipWithNext { a, b -> LogSegment(a, b) }.filter { it.valid }
        val asleep = segs.filter { it.screenOff }
        val awake = segs.filter { !it.screenOff }
        // The charge counter can read 0 (a fresh boot, a counter reset); a zero capacity would make every rate infinite.
        val capacity = s.lastOrNull { it.chargeMah != null && it.level >= 50 }
            ?.let { it.chargeMah!! * 100.0 / it.level }
            ?.takeIf { it > 0.0 }
            ?: 3700.0
        fun rate(list: List<LogSegment>): Double? {
            val h = list.sumOf { it.hours }
            if (h < 0.5) return null
            val mah = list.mapNotNull { it.drainMahClamped }
            return if (mah.size == list.size) mah.sum() * 100 / capacity / h else list.sumOf { it.drainPct } / h
        }
        val awakeHours = awake.sumOf { it.hours }
        return LogSummary(
            samples = s.size,
            spanHours = if (s.size > 1) (s.last().epoch - s.first().epoch) / 3_600_000.0 else 0.0,
            asleepPctPerHour = rate(asleep),
            awakePctPerHour = rate(awake),
            asleepHours = asleep.sumOf { it.hours },
            awakeHours = awakeHours,
            recent = s.takeLast(400),
            screenOnSleepPct = if (awakeHours >= 0.5) awake.sumOf { it.asleepFraction * it.hours } / awakeHours * 100 else null,
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
                    appendLine("  elapsed and uptime in ms, boot count, instant current in mA, front light and warmth 0-32, Wi-Fi off/on/connected,")
                    appendLine("  Doze mode, Battery Saver). Reasons: tick (30 min alarm, never wakes the tablet), screen_on/screen_off, plug/unplug,")
                    appendLine("  level (1 % step on battery), doze_deep/doze_light/saver, open/manual, boot (real reboot), unstop (first start after a")
                    appendLine("  force-stop, which Android 15+ reports as BOOT_COMPLETED), update. 1 - Δuptime/Δelapsed is the share of time the SoC slept;")
                    appendLine("  on Boox it also sleeps with the screen on. battery-YYYY-MM.v1.csv files hold rows from before the extra columns.")
                    appendLine("deep-YYYY-MM-DD.jsonl: every few hours, Doze state and history, alarm wakeups by app and tag, wakelock acquisitions,")
                    appendLine("  CPU ms and Wi-Fi KB by uid since the previous snapshot, foreground seconds by app, and our own process exits.")
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

/** Receives the log alarm, boot (real or after a force-stop) and our own updates; manifest-registered, not exported. */
class BatteryLogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> if (BatteryLog.isNewBoot(context)) "boot" else "unstop"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "update"
            else -> "tick"
        }
        // Boot and force-stop clear the alarm, an update keeps it; schedule() re-arms only what is missing, so frequent
        // reinstalls no longer restart the 30-minute countdown.
        if (reason != "tick") BatteryLog.schedule(context)
        BatteryLog.record(context, reason) { result.finish() }
    }
}
