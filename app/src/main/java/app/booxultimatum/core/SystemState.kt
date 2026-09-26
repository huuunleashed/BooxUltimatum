package app.booxultimatum.core

import android.content.Context
import app.booxultimatum.core.exec.Diagnostics
import app.booxultimatum.core.exec.Privileged
import app.booxultimatum.core.exec.ShellResult

data class DozeState(
    val deepEnabled: Boolean, val lightEnabled: Boolean, val deep: String, val light: String, val forced: Boolean,
    val screenOn: Boolean? = null, val charging: Boolean? = null, val history: List<String> = emptyList(),
)

data class WakeupSource(val pkg: String, val uid: String, val wakeups: Int, val alarms: Int, val tags: Map<String, Int> = emptyMap())

data class HeldWakelock(val type: String, val tag: String, val owner: String)

/** One `dumpsys power` read: wakelocks held now, the Onyx power-manager line, and acquisitions by owner and tag since a time. */
data class PowerDump(val held: List<HeldWakelock>, val onyxPm: String?, val acquisitions: Map<String, Int>)

/** Since-charge totals for one uid from `dumpsys batterystats -c`. */
data class UidStat(val cpuMs: Long, val wifiRxBytes: Long)

enum class BgMode { Allow, Ignore, Default, Unknown }

/** Parsers over `dumpsys` / `cmd` output. Formats checked against NA6C FW 4.3 (capture factory-fw43). */
object SystemState {
    private val PKG = Regex("^[A-Za-z0-9_.]+$")

    fun checkPkg(pkg: String) = require(PKG.matches(pkg)) { "Bad package name: $pkg" }

    suspend fun doze(preferApp: Boolean = false): DozeState? {
        val r = Diagnostics.dumpsys("deviceidle", preferApp)
        if (!r.ok || r.out.isBlank()) return null
        fun flag(name: String) = Regex("""$name=(\w+)""").find(r.out)?.groupValues?.get(1)
        val lines = r.out.lines()
        val start = lines.indexOfFirst { it.trim() == "Idling history:" }
        return DozeState(
            deepEnabled = flag("mDeepEnabled") == "true",
            lightEnabled = flag("mLightEnabled") == "true",
            deep = flag("mState") ?: "?",
            light = flag("mLightState") ?: "?",
            forced = flag("mForceIdle") == "true",
            screenOn = flag("mScreenOn")?.let { it == "true" },
            charging = flag("mCharging")?.let { it == "true" },
            history = if (start < 0) emptyList() else lines.drop(start + 1).takeWhile { it.startsWith("    ") }.map { it.trim() },
        )
    }

    /** Packages on the user-editable Doze allowlist (`user,pkg,uid` lines). System entries cannot be removed. */
    suspend fun dozeAllowlist(): Pair<Set<String>, Set<String>>? {
        val r = Diagnostics.dumpsys("deviceidle whitelist")
        if (!r.ok) return null
        val user = mutableSetOf<String>()
        val system = mutableSetOf<String>()
        r.out.lineSequence().map { it.trim() }.forEach { line ->
            val parts = line.split(',')
            if (parts.size >= 2) when (parts[0]) {
                "user" -> user += parts[1]
                "system", "system-excidle" -> system += parts[1]
            }
        }
        return user to system
    }

    suspend fun setDozeAllowed(pkg: String, allowed: Boolean): ShellResult {
        checkPkg(pkg)
        return Privileged.sh("dumpsys deviceidle whitelist ${if (allowed) "+" else "-"}$pkg")
    }

    suspend fun backgroundMode(pkg: String): BgMode {
        checkPkg(pkg)
        val r = Privileged.sh("cmd appops get $pkg RUN_ANY_IN_BACKGROUND")
        if (!r.ok) return BgMode.Unknown
        val out = r.out.lowercase()
        return when {
            "ignore" in out -> BgMode.Ignore
            "allow" in out -> BgMode.Allow
            "no operations" in out || "default" in out -> BgMode.Default
            else -> BgMode.Unknown
        }
    }

    suspend fun setBackgroundMode(pkg: String, mode: BgMode): ShellResult {
        checkPkg(pkg)
        val m = when (mode) {
            BgMode.Ignore -> "ignore"
            BgMode.Allow -> "allow"
            else -> "default"
        }
        return Privileged.sh("cmd appops set $pkg RUN_ANY_IN_BACKGROUND $m")
    }

    /** Standby bucket number (10 active … 45 restricted), or null when unknown. */
    suspend fun standbyBucket(pkg: String): Int? {
        checkPkg(pkg)
        val r = Privileged.sh("am get-standby-bucket $pkg")
        return if (r.ok) r.out.trim().toIntOrNull() else null
    }

    suspend fun setEnabled(pkg: String, enabled: Boolean): ShellResult {
        checkPkg(pkg)
        return Privileged.sh(if (enabled) "pm enable --user 0 $pkg" else "pm disable-user --user 0 $pkg")
    }

    suspend fun forceStop(pkg: String): ShellResult {
        checkPkg(pkg)
        return Privileged.sh("am force-stop $pkg")
    }

    fun isEnabled(context: Context, pkg: String): Boolean? = runCatching {
        context.packageManager.getApplicationInfo(pkg, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS).enabled
    }.getOrNull()

    /** Per-package alarm wakeups since boot, from `dumpsys alarm` "Alarm Stats", with wakeups per alarm tag. */
    suspend fun alarmWakeups(preferApp: Boolean = false): List<WakeupSource>? {
        val r = Diagnostics.dumpsys("alarm", preferApp)
        if (!r.ok || r.out.isBlank()) return null
        val header = Regex("""^ {2}(\S+?):(\S+) \+\S+ running, (\d+) wakeups:""")
        val alarmsLine = Regex("""^ {4}\+\S+ (\d+) wakes (\d+) alarms""")
        val result = mutableListOf<WakeupSource>()
        var inStats = false
        var current: WakeupSource? = null
        var pendingWakes = -1
        r.out.lineSequence().forEach { line ->
            if (line.trim() == "Alarm Stats:") { inStats = true; return@forEach }
            if (!inStats) return@forEach
            header.find(line)?.let { m ->
                current?.let { result += it }
                current = WakeupSource(pkg = m.groupValues[2], uid = m.groupValues[1], wakeups = m.groupValues[3].toInt(), alarms = 0)
                pendingWakes = -1
                return@forEach
            }
            alarmsLine.find(line)?.let { m ->
                current = current?.let { it.copy(alarms = it.alarms + m.groupValues[2].toInt()) }
                pendingWakes = m.groupValues[1].toInt()
                return@forEach
            }
            // The line after "+Xms N wakes M alarms" names the alarm, e.g. "*walarm*:TIME_TICK".
            if (pendingWakes > 0) current = current?.let { it.copy(tags = it.tags + (line.trim() to pendingWakes)) }
            pendingWakes = -1
        }
        current?.let { result += it }
        return result.groupBy { it.pkg }
            .map { (pkg, list) ->
                val tags = HashMap<String, Int>()
                list.forEach { w -> w.tags.forEach { (t, n) -> tags[t] = (tags[t] ?: 0) + n } }
                WakeupSource(pkg, list.first().uid, list.sumOf { it.wakeups }, list.sumOf { it.alarms }, tags)
            }
            .sortedByDescending { it.wakeups }
    }

    /** Wakelocks held right now, from `dumpsys power`. */
    suspend fun heldWakelocks(): List<HeldWakelock>? = power(System.currentTimeMillis())?.held

    /**
     * `dumpsys power` in one read. Held lines look like `PARTIAL_WAKE_LOCK 'tag' ACQ=-1s (uid=1000 pid=2475)`; the
     * wake lock log lines like `09-26 09:42:05.815 - 1000 (System) - ACQ onyx_RxManager (full)`. The log has no year,
     * so counts across New Year's night are approximate.
     */
    suspend fun power(sinceMillis: Long, preferApp: Boolean = false): PowerDump? {
        val r = Diagnostics.dumpsys("power", preferApp)
        if (!r.ok || r.out.isBlank()) return null
        val lines = r.out.lines()
        val start = lines.indexOfFirst { it.trim().startsWith("Wake Locks: size=") }
        val entry = Regex("""^\s+(\w+_WAKE_LOCK)\s+'([^']*)'.*\(uid=(\d+)""")
        val held = if (start < 0) emptyList() else lines.drop(start + 1).takeWhile { it.isNotBlank() }.mapNotNull { l ->
            entry.find(l)?.let { HeldWakelock(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        }
        val since = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(sinceMillis))
        val acq = Regex("""^\s+(\d\d-\d\d \d\d:\d\d:\d\d)\.\d+ - \d+ \((.*?)\) - ACQ (\S+)""")
        val counts = HashMap<String, Int>()
        lines.forEach { l ->
            acq.find(l)?.let { m -> if (m.groupValues[1] >= since) { val k = m.groupValues[2] + " " + m.groupValues[3]; counts[k] = (counts[k] ?: 0) + 1 } }
        }
        return PowerDump(
            held = held,
            onyxPm = lines.firstOrNull { it.startsWith("Onyx pm") }?.trim(),
            acquisitions = counts.entries.sortedByDescending { it.value }.take(10).associate { it.key to it.value },
        )
    }

    /**
     * CPU time and Wi-Fi bytes per uid since the last full charge, from `dumpsys batterystats -c` lines
     * `9,<uid>,l,cpu,<user ms>,<system ms>,…` and `9,<uid>,l,nt,<mobile rx>,<mobile tx>,<wifi rx>,…`. Never `--checkin`:
     * that one also writes and clears the stored previous-period stats.
     */
    suspend fun uidStats(preferApp: Boolean = false): Map<Int, UidStat>? {
        val r = Diagnostics.dumpsys("batterystats -c", preferApp)
        if (!r.ok || r.out.isBlank()) return null
        val cpu = HashMap<Int, Long>()
        val rx = HashMap<Int, Long>()
        r.out.lineSequence().forEach { line ->
            if (!line.startsWith("9,")) return@forEach
            val p = line.split(',')
            if (p.size < 7 || p[2] != "l") return@forEach
            val uid = p[1].toIntOrNull() ?: return@forEach
            when (p[3]) {
                "cpu" -> cpu[uid] = (p[4].toLongOrNull() ?: 0L) + (p[5].toLongOrNull() ?: 0L)
                "nt" -> rx[uid] = p[6].toLongOrNull() ?: 0L
            }
        }
        return (cpu.keys + rx.keys).associateWith { UidStat(cpu[it] ?: 0L, rx[it] ?: 0L) }
    }

    suspend fun setDozeEnabled(enabled: Boolean): ShellResult =
        Privileged.sh("dumpsys deviceidle ${if (enabled) "enable" else "disable"} all")

    suspend fun forceIdle(on: Boolean): ShellResult =
        Privileged.sh(if (on) "dumpsys deviceidle force-idle deep" else "dumpsys deviceidle unforce")
}
