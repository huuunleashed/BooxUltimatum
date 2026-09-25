package app.booxultimatum.core

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import rikka.shizuku.Shizuku
import java.io.File

enum class Tier(val label: String) { T0("App"), T1("adb-granted"), T2("Shizuku"), T3("Root") }

data class PrivilegeStatus(
    val secureSettings: Boolean,
    val dump: Boolean,
    val readLogs: Boolean,
    val usageStats: Boolean,
    val shizukuRunning: Boolean,
    val shizukuGranted: Boolean,
    val suBinary: String?,
) {
    val highestTier: Tier
        get() = when {
            suBinary != null -> Tier.T3
            shizukuRunning && shizukuGranted -> Tier.T2
            secureSettings || dump -> Tier.T1
            else -> Tier.T0
        }

    fun rows(): List<Pair<String, String>> = listOf(
        "Highest tier" to "${highestTier.name} · ${highestTier.label}",
        "WRITE_SECURE_SETTINGS" to secureSettings.yesNo(),
        "DUMP" to dump.yesNo(),
        "READ_LOGS" to readLogs.yesNo(),
        "Usage access" to usageStats.yesNo(),
        "Shizuku running" to shizukuRunning.yesNo(),
        "Shizuku permission" to shizukuGranted.yesNo(),
        "su binary" to (suBinary ?: "not found"),
    )

    companion object {
        private val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/data/adb/ksu/bin/su")

        const val ADB_GRANT_HELP = """adb shell pm grant app.booxultimatum android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant app.booxultimatum android.permission.DUMP
adb shell pm grant app.booxultimatum android.permission.READ_LOGS
adb shell appops set app.booxultimatum GET_USAGE_STATS allow"""

        fun check(context: Context): PrivilegeStatus {
            fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            val appOps = context.getSystemService(AppOpsManager::class.java)
            @Suppress("DEPRECATION") // replacement needs API 36; minSdk is 30
            val usage = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
                AppOpsManager.MODE_ALLOWED
            val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            val shizukuOk = running && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
            return PrivilegeStatus(
                secureSettings = granted(Manifest.permission.WRITE_SECURE_SETTINGS),
                dump = granted(Manifest.permission.DUMP),
                readLogs = granted(Manifest.permission.READ_LOGS),
                usageStats = usage,
                shizukuRunning = running,
                shizukuGranted = shizukuOk,
                suBinary = SU_PATHS.firstOrNull { File(it).exists() },
            )
        }

        fun requestShizuku(requestCode: Int = 1) {
            if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) Shizuku.requestPermission(requestCode)
        }
    }
}

private fun Boolean.yesNo() = if (this) "yes" else "no"
