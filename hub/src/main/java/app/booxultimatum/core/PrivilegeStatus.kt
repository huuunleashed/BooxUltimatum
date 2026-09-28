package app.booxultimatum.core

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import rikka.shizuku.Shizuku
import java.io.File

enum class Tier { T0, T1, T2, T3 }

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

    /** adb-granted permissions (tier T1) that are currently held, out of [T1_GRANTS]. */
    val t1Granted get() = listOf(secureSettings, dump, readLogs, usageStats).count { it }

    companion object {
        const val T1_GRANTS = 4
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

        /** Returns false when the Shizuku server is unreachable (not started since reboot, or a stale binder). */
        fun requestShizuku(requestCode: Int = 1): Boolean {
            if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return false
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(requestCode)
            return true
        }
    }
}

