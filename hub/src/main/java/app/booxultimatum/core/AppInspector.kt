package app.booxultimatum.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import app.booxultimatum.core.exec.Privileged

enum class AllowState { User, System, None, Unknown }

data class AppDetail(
    val id: String,
    val label: String,
    val versionName: String?,
    val targetSdk: Int,
    val firstInstall: Long,
    val lastUpdate: Long,
    val installer: String?,
    val system: Boolean,
    val enabled: Boolean,
    val launchable: Boolean,
    val kb: KbEntry?,
    val allow: AllowState,
    val background: BgMode,
    val bucket: Int?,
)

object AppInspector {
    suspend fun inspect(context: Context, pkg: String): AppDetail? {
        val pm = context.packageManager
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()))
            else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
        }.getOrNull() ?: return null
        val app = info.applicationInfo ?: return null
        val allowlist = SystemState.dozeAllowlist()
        val allow = when {
            allowlist == null -> AllowState.Unknown
            pkg in allowlist.second -> AllowState.System
            pkg in allowlist.first -> AllowState.User
            else -> AllowState.None
        }
        val shell = Privileged.ready()
        return AppDetail(
            id = pkg,
            label = app.loadLabel(pm).toString(),
            versionName = info.versionName,
            targetSdk = app.targetSdkVersion,
            firstInstall = info.firstInstallTime,
            lastUpdate = info.lastUpdateTime,
            installer = runCatching { pm.getInstallSourceInfo(pkg).installingPackageName }.getOrNull(),
            system = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            enabled = app.enabled,
            launchable = pm.getLaunchIntentForPackage(pkg) != null,
            kb = PackageScanner.loadKnowledge(context)[pkg],
            allow = allow,
            background = if (shell) SystemState.backgroundMode(pkg) else BgMode.Unknown,
            bucket = if (shell) SystemState.standbyBucket(pkg) else null,
        )
    }
}
