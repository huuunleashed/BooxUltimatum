package app.booxultimatum.kit.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import java.security.MessageDigest

/**
 * The apps of the BooxUltimatum suite. Each is its own APK, and every public release of each is signed with the same
 * key, so they trust each other through [Suite.PERMISSION] and the hub can install and update the others.
 *
 * - [assetName] names the release asset: `<assetName>-<version>.apk`.
 * - [tagPrefix] starts the release tag: `<tagPrefix><version>`. The hub keeps plain `v`, so releases stay readable by
 *   hubs from before the suite.
 * - [key] names the app in test-build tags and assets (see `kit:update`).
 */
enum class SuiteApp(val packageName: String, val title: String, val assetName: String, val tagPrefix: String, val key: String) {
    Hub("app.booxultimatum", "BooxUltimatum", "BooxUltimatum", "v", "hub"),
    Nib("app.booxultimatum.nib", "Nib", "Nib", "nib-v", "nib");

    companion object {
        fun of(packageName: String?): SuiteApp? = entries.firstOrNull { it.packageName == packageName }
    }
}

/** What Android reports about an installed suite app. */
data class InstalledApp(val versionName: String, val versionCode: Long, val certificate: String?)

object Suite {
    /** Signature permission guarding what suite apps share with each other; declared by the hub. */
    const val PERMISSION = "app.booxultimatum.permission.SUITE"

    const val OWNER = "huuunleashed"
    const val REPO = "BooxUltimatum"
    const val RELEASES_URL = "https://github.com/$OWNER/$REPO/releases"

    /** SHA-256 of the certificate public releases are signed with. */
    const val RELEASE_CERT = "75dbdea9807374ce0a269432528187798d9f662e4fbf8953129f2993d99a2121"

    /** Whether [packageName] belongs to the suite, including apps added after this build. */
    fun isSuitePackage(packageName: String?): Boolean =
        packageName != null && (packageName == SuiteApp.Hub.packageName || packageName.startsWith("${SuiteApp.Hub.packageName}."))

    fun installed(context: Context, app: SuiteApp): InstalledApp? = runCatching {
        packageInfo(context, app.packageName).let { InstalledApp(it.versionName.orEmpty(), it.longVersionCode, it.signingSha256()) }
    }.getOrNull()

    /** This app's own signing certificate. */
    fun ownCertificate(context: Context): String? = runCatching { packageInfo(context, context.packageName).signingSha256() }.getOrNull()

    /** Whether this build is signed with the release key; development builds are signed differently. */
    fun isReleaseBuild(context: Context): Boolean = ownCertificate(context) == RELEASE_CERT

    /** Opens the app's main screen; false when it isn't installed or has none. */
    fun open(context: Context, app: SuiteApp): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
        return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    /** Asks Android to uninstall the app; Android shows its own confirmation. */
    fun uninstall(context: Context, app: SuiteApp): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    @SuppressLint("PackageManagerGetSignatures")
    private fun packageInfo(context: Context, packageName: String): PackageInfo =
        context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)

    fun PackageInfo.signingSha256(): String? =
        signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()?.let { sha256Hex(it) }

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
