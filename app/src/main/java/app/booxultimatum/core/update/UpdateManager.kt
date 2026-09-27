package app.booxultimatum.core.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.content.edit
import app.booxultimatum.BuildConfig
import app.booxultimatum.R
import app.booxultimatum.core.AppWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

data class UpdateState(
    val includePrereleases: Boolean = true,
    val buildKind: BuildKind = BuildKind.Development,
    val line: UpdateLine = UpdateLine(R.string.about_update_reading),
    val release: UpdateRelease? = null,
    val busy: Boolean = false,
    val canDownload: Boolean = false,
    val needsUnknownSources: Boolean = false,
    val disabled: Boolean = false,
)

enum class BuildKind { Release, Development }

data class UpdateLine(@param:StringRes val resId: Int, val arg: String? = null)

data class UpdateRelease(
    val version: String,
    val tag: String,
    val htmlUrl: String,
    val apkUrl: String,
    val apkName: String,
    val checksum: String?,
    val notes: String,
)

object UpdateManager {
    private const val OWNER = "huuunleashed"
    private const val REPO = "BooxUltimatum"
    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=10"
    private const val PREFS = "updates"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_PRERELEASES = "include_prereleases"
    private const val DAY_MS = 86_400_000L
    private const val RELEASE_CERT = "75dbdea9807374ce0a269432528187798d9f662e4fbf8953129f2993d99a2121"
    private const val ACTION_INSTALL = "app.booxultimatum.action.UPDATE_INSTALL"
    private val HEX_64 = Regex("""\b[0-9a-fA-F]{64}\b""")

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state

    fun initialise(context: Context) {
        val app = context.applicationContext
        val include = prefs(app).getBoolean(KEY_PRERELEASES, defaultPrereleases())
        val kind = buildKind(app)
        _state.value = _state.value.copy(
            includePrereleases = include,
            buildKind = kind,
            disabled = kind != BuildKind.Release,
            line = UpdateLine(if (kind == BuildKind.Release) R.string.about_update_ready else R.string.about_update_development),
        )
    }

    fun buildKind(context: Context): BuildKind = if (installedCertificate(context.applicationContext) == RELEASE_CERT) BuildKind.Release else BuildKind.Development

    fun checkIfDue(context: Context) {
        val app = context.applicationContext
        initialise(app)
        if (_state.value.disabled) return
        val now = System.currentTimeMillis()
        if (now - prefs(app).getLong(KEY_LAST_CHECK, 0L) >= DAY_MS) checkNow(app, automatic = true)
    }

    fun setIncludePrereleases(context: Context, include: Boolean) {
        val app = context.applicationContext
        prefs(app).edit { putBoolean(KEY_PRERELEASES, include) }
        _state.update { it.copy(includePrereleases = include, release = null, canDownload = false, line = UpdateLine(R.string.about_update_channel_changed)) }
    }

    fun checkNow(context: Context, automatic: Boolean = false) {
        val app = context.applicationContext
        initialise(app)
        if (_state.value.disabled || _state.value.busy) return
        AppWork.scope.launch {
            _state.update { it.copy(line = UpdateLine(R.string.about_update_checking), busy = true, canDownload = false, needsUnknownSources = false) }
            val result = runCatching { findUpdate(app) }
            prefs(app).edit { putLong(KEY_LAST_CHECK, System.currentTimeMillis()) }
            result.fold(
                onSuccess = { release ->
                    _state.update {
                        if (release == null) it.copy(line = UpdateLine(R.string.about_update_up_to_date), release = null, busy = false, canDownload = false)
                        else if (release.checksum == null) it.copy(line = UpdateLine(R.string.about_update_no_checksum_version, release.version), release = release, busy = false, canDownload = false)
                        else it.copy(line = UpdateLine(R.string.about_update_available, release.version), release = release, busy = false, canDownload = true)
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(line = UpdateLine(if (automatic) R.string.about_update_auto_failed else R.string.about_update_check_failed, e.cleanMessage()), busy = false, canDownload = false)
                    }
                },
            )
        }
    }

    fun downloadAndInstall(context: Context) {
        val app = context.applicationContext
        val release = _state.value.release ?: return
        if (_state.value.busy) return
        if (release.checksum == null) {
            _state.update { it.copy(line = UpdateLine(R.string.about_update_no_checksum), canDownload = false) }
            return
        }
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.update { it.copy(line = UpdateLine(R.string.about_update_need_sources), needsUnknownSources = true) }
            return
        }
        AppWork.scope.launch {
            val result = runCatching {
                _state.update { it.copy(line = UpdateLine(R.string.about_update_downloading, "0"), busy = true, canDownload = false, needsUnknownSources = false) }
                val apk = withContext(Dispatchers.IO) { download(app, release) }
                _state.update { it.copy(line = UpdateLine(R.string.about_update_verifying)) }
                withContext(Dispatchers.IO) { verifyDownloadedApk(app, apk, release.checksum) }
                _state.update { it.copy(line = UpdateLine(R.string.about_update_ready_install), busy = false) }
                withContext(Dispatchers.IO) { install(app, apk) }
            }
            result.onFailure { e -> _state.update { it.copy(line = UpdateLine(R.string.about_update_failed, e.cleanMessage()), busy = false, canDownload = true) } }
        }
    }

    fun openReleasePage(context: Context) {
        val url = _state.value.release?.htmlUrl ?: "https://github.com/$OWNER/$REPO/releases"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openUnknownSources(context: Context) {
        val uri = Uri.parse("package:${context.packageName}")
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    internal fun onInstallResult(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_SUCCESS -> _state.update { it.copy(line = UpdateLine(R.string.about_update_installed), busy = false, canDownload = false) }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                _state.update { it.copy(line = UpdateLine(R.string.about_update_confirm), busy = false) }
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().ifBlank { context.getString(R.string.about_update_android_refused) }
                _state.update { it.copy(line = UpdateLine(R.string.about_update_install_failed, msg), busy = false, canDownload = true) }
            }
        }
    }

    private suspend fun findUpdate(context: Context): UpdateRelease? = withContext(Dispatchers.IO) {
        val releases = JSONArray(httpText(API, accept = "application/vnd.github+json"))
        val current = Semver.parse(BuildConfig.VERSION_NAME)
        val includePre = _state.value.includePrereleases
        (0 until releases.length()).asSequence()
            .map { releases.getJSONObject(it) }
            .filter { !it.optBoolean("draft") }
            .filter { includePre || !it.optBoolean("prerelease") }
            .mapNotNull { releaseFrom(it) }
            .filter { Semver.parse(it.version) > current }
            .maxByOrNull { Semver.parse(it.version) }
    }

    private fun releaseFrom(obj: JSONObject): UpdateRelease? {
        val tag = obj.optString("tag_name")
        val version = tag.removePrefix("v")
        val apkName = "BooxUltimatum-$version.apk"
        val assets = obj.optJSONArray("assets") ?: JSONArray()
        val apk = (0 until assets.length()).asSequence().map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == apkName } ?: return null
        val checksum = checksumFor(obj, assets, apkName, apk)
        return UpdateRelease(
            version = version,
            tag = tag,
            htmlUrl = obj.optString("html_url"),
            apkUrl = apk.optString("browser_download_url"),
            apkName = apkName,
            checksum = checksum?.lowercase(Locale.US),
            notes = stripReleaseNotes(obj.optString("body")),
        )
    }

    private fun checksumFor(release: JSONObject, assets: JSONArray, apkName: String, apk: JSONObject): String? {
        apk.optString("digest").takeIf { it.startsWith("sha256:", ignoreCase = true) }?.substringAfter(':')?.takeIf { it.matches(HEX_64) }?.let { return it }
        val sumName = "$apkName.sha256"
        val sum = (0 until assets.length()).asSequence().map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == sumName }?.optString("browser_download_url")
        if (!sum.isNullOrBlank()) httpText(sum).lineSequence().mapNotNull { HEX_64.find(it)?.value }.firstOrNull()?.let { return it }
        return release.optString("body").lineSequence().firstOrNull { it.contains(apkName) }?.let { HEX_64.find(it)?.value }
    }

    private fun download(context: Context, release: UpdateRelease): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, release.apkName)
        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "BooxUltimatum/${BuildConfig.VERSION_NAME}")
        }
        try {
            val total = conn.contentLengthLong.takeIf { it > 0L } ?: -1L
            var read = 0L
            var last = 0
            BufferedInputStream(conn.inputStream).use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0L) {
                            val pct = ((read * 100) / total).toInt().coerceIn(0, 100)
                            if (pct >= last + 5 || pct == 100) {
                                last = pct - pct % 5
                                _state.update { it.copy(line = UpdateLine(R.string.about_update_downloading, pct.toString())) }
                            }
                        }
                    }
                }
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    private fun verifyDownloadedApk(context: Context, file: File, expectedSha: String) {
        val actual = sha256(file)
        check(actual.equals(expectedSha, ignoreCase = true)) { "Checksum mismatch." }
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Android could not read the APK.")
        check(info.packageName == context.packageName) { "APK package name is ${info.packageName}, not ${context.packageName}." }
        check(info.longVersion() > installedVersion(context)) { "APK is not newer than the installed app." }
        val apkCert = info.signingSha256()
        val ownCert = installedCertificate(context)
        check(apkCert != null && ownCert != null && apkCert == ownCert) { "APK signature does not match this installed app." }
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input -> session.openWrite("base.apk", 0, apk.length()).use { output -> input.copyTo(output); session.fsync(output) } }
            session.commit(installIntent(context, id))
        }
        _state.update { it.copy(line = UpdateLine(R.string.about_update_install_sent), busy = true) }
    }

    private fun installIntent(context: Context, id: Int) = PendingIntent.getBroadcast(
        context,
        id,
        Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL),
        PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0,
    ).intentSender

    private fun httpText(url: String, accept: String? = null): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "BooxUltimatum/${BuildConfig.VERSION_NAME}")
            if (accept != null) setRequestProperty("Accept", accept)
        }
        try {
            check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    @SuppressLint("PackageManagerGetSignatures")
    private fun installedCertificate(context: Context): String? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingSha256()
    }.getOrNull()

    private fun installedVersion(context: Context): Long = context.packageManager.getPackageInfo(context.packageName, 0).longVersion()

    private fun android.content.pm.PackageInfo.longVersion(): Long = longVersionCode

    private fun android.content.pm.PackageInfo.signingSha256(): String? {
        val signers = signingInfo?.apkContentsSigners
        return signers?.firstOrNull()?.toByteArray()?.let { MessageDigest.getInstance("SHA-256").digest(it).toHex() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun stripReleaseNotes(raw: String): String = raw
        .replace(Regex("""```[\s\S]*?```"""), "")
        .replace(Regex("""[*_`>#\[\]]"""), "")
        .lineSequence()
        .map { it.trim().trimStart('-', '•').trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .take(800)

    private fun defaultPrereleases(): Boolean = Semver.parse(BuildConfig.VERSION_NAME) < Semver(1, 0, 0)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun Throwable.cleanMessage() = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private data class Semver(val major: Int, val minor: Int, val patch: Int) : Comparable<Semver> {
        override fun compareTo(other: Semver): Int = compareValuesBy(this, other, Semver::major, Semver::minor, Semver::patch)

        companion object {
            fun parse(value: String): Semver {
                val parts = value.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
                return Semver(parts.getOrElse(0) { 0 }, parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
            }
        }
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        UpdateManager.onInstallResult(context.applicationContext, intent)
    }
}
