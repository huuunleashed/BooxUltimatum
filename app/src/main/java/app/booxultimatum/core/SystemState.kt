package app.booxultimatum.core

import android.content.Context
import app.booxultimatum.core.exec.Diagnostics
import app.booxultimatum.core.exec.Privileged
import app.booxultimatum.core.exec.ShellResult

data class DozeState(val deepEnabled: Boolean, val lightEnabled: Boolean, val deep: String, val light: String, val forced: Boolean)

data class WakeupSource(val pkg: String, val uid: String, val wakeups: Int, val alarms: Int)

data class HeldWakelock(val type: String, val tag: String, val owner: String)

enum class BgMode { Allow, Ignore, Default, Unknown }

/** Parsers over `dumpsys` / `cmd` output. Formats checked against NA6C FW 4.3 (capture factory-fw43). */
object SystemState {
    private val PKG = Regex("^[A-Za-z0-9_.]+$")

    fun checkPkg(pkg: String) = require(PKG.matches(pkg)) { "Bad package name: $pkg" }

    suspend fun doze(): DozeState? {
        val r = Diagnostics.dumpsys("deviceidle")
        if (!r.ok || r.out.isBlank()) return null
        fun flag(name: String) = Regex("""$name=(\w+)""").find(r.out)?.groupValues?.get(1)
        return DozeState(
            deepEnabled = flag("mDeepEnabled") == "true",
            lightEnabled = flag("mLightEnabled") == "true",
            deep = flag("mState") ?: "?",
            light = flag("mLightState") ?: "?",
            forced = flag("mForceIdle") == "true",
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

    /** Per-package alarm wakeups since the last reset, from `dumpsys alarm` "Alarm Stats". */
    suspend fun alarmWakeups(): List<WakeupSource>? {
        val r = Diagnostics.dumpsys("alarm")
        if (!r.ok || r.out.isBlank()) return null
        val header = Regex("""^ {2}(\S+?):(\S+) \+\S+ running, (\d+) wakeups:""")
        val alarmsLine = Regex("""^ {4}\+\S+ (\d+) wakes (\d+) alarms""")
        val result = mutableListOf<WakeupSource>()
        var inStats = false
        var current: WakeupSource? = null
        r.out.lineSequence().forEach { line ->
            if (line.trim() == "Alarm Stats:") { inStats = true; return@forEach }
            if (!inStats) return@forEach
            header.find(line)?.let { m ->
                current?.let { result += it }
                current = WakeupSource(pkg = m.groupValues[2], uid = m.groupValues[1], wakeups = m.groupValues[3].toInt(), alarms = 0)
                return@forEach
            }
            alarmsLine.find(line)?.let { m -> current = current?.let { it.copy(alarms = it.alarms + m.groupValues[2].toInt()) } }
        }
        current?.let { result += it }
        return result.groupBy { it.pkg }
            .map { (pkg, list) -> WakeupSource(pkg, list.first().uid, list.sumOf { it.wakeups }, list.sumOf { it.alarms }) }
            .sortedByDescending { it.wakeups }
    }

    /** Wakelocks held right now, from `dumpsys power`. */
    suspend fun heldWakelocks(): List<HeldWakelock>? {
        val r = Diagnostics.dumpsys("power")
        if (!r.ok || r.out.isBlank()) return null
        val lines = r.out.lines()
        val start = lines.indexOfFirst { it.trim().startsWith("Wake Locks: size=") }
        if (start < 0) return emptyList()
        val entry = Regex("""^\s+(\w+_WAKE_LOCK)\s+'([^']*)'.*?(?:\(uid=(\d+))?""")
        return lines.drop(start + 1).takeWhile { it.isNotBlank() }.mapNotNull { l ->
            entry.find(l)?.let { HeldWakelock(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        }
    }

    suspend fun setDozeEnabled(enabled: Boolean): ShellResult =
        Privileged.sh("dumpsys deviceidle ${if (enabled) "enable" else "disable"} all")

    suspend fun forceIdle(on: Boolean): ShellResult =
        Privileged.sh(if (on) "dumpsys deviceidle force-idle deep" else "dumpsys deviceidle unforce")
}
