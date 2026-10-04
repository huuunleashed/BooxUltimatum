package app.booxultimatum.kit.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import app.booxultimatum.kit.core.AppWork
import app.booxultimatum.kit.core.InstalledApp
import app.booxultimatum.kit.core.Suite
import app.booxultimatum.kit.core.Suite.signingSha256
import app.booxultimatum.kit.core.SuiteApp
import app.booxultimatum.kit.log.Logbook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Where one suite app stands with updates. The screens turn these into words. */
sealed interface UpdatePhase {
    /** Nothing checked yet in this process. */
    data object Idle : UpdatePhase
    /** This build isn't signed with the release key, so it can't install published APKs. */
    data object Development : UpdatePhase
    data object Checking : UpdatePhase
    data object UpToDate : UpdatePhase
    /** Not installed, and nothing published that this channel offers. */
    data object NotPublished : UpdatePhase
    data class Available(val release: Release) : UpdatePhase
    /** Published, but without a checksum to verify it by, so it isn't offered for install. */
    data class NoChecksum(val release: Release) : UpdatePhase
    data class Downloading(val percent: Int) : UpdatePhase
    data object Verifying : UpdatePhase
    data object NeedsUnknownSources : UpdatePhase
    /** Handed to Android's installer. */
    data object Installing : UpdatePhase
    /** Android is asking the owner to confirm. */
    data object Confirming : UpdatePhase
    data object Installed : UpdatePhase
    data class Failed(val step: Step, val message: String) : UpdatePhase

    enum class Step { Check, Download, Verify, Install }
}

data class AppUpdate(val app: SuiteApp, val installed: InstalledApp?, val phase: UpdatePhase = UpdatePhase.Idle, val offered: Release? = null) {
    /**
     * [UpdatePhase.Confirming] counts as busy: Android's confirmation screen is up, and offering Install again there
     * would start a second download and a second installer session for one release.
     */
    val busy: Boolean get() = phase is UpdatePhase.Checking || phase is UpdatePhase.Downloading || phase is UpdatePhase.Verifying ||
        phase is UpdatePhase.Installing || phase is UpdatePhase.Confirming
    val canInstall: Boolean get() = offered != null && offered.let { it.checksum != null || it.checksumUrl != null } && !busy
}

data class UpdatesState(
    val channel: UpdateChannel = UpdateChannel(),
    val releaseBuild: Boolean = false,
    val apps: Map<SuiteApp, AppUpdate> = emptyMap(),
    val lastCheck: Long = 0L,
) {
    operator fun get(app: SuiteApp): AppUpdate? = apps[app]
    val checking: Boolean get() = apps.values.any { it.phase is UpdatePhase.Checking }
}

/**
 * Updates and first installs for the suite apps this app manages: the hub manages them all, and an app on its own
 * manages itself. One GitHub request reads every app's releases. An APK is installed only if its SHA-256 matches the
 * release, its package is the expected one, it's newer than what's installed, and it's signed with this app's own key,
 * which for every public build is the suite's release key.
 */
object Updates {
    private const val PREFS = "updates"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_PREVIEWS = "include_prereleases"
    private const val KEY_TESTS = "include_tests"
    private const val DAY_MS = 86_400_000L
    internal const val ACTION_INSTALL = "app.booxultimatum.action.UPDATE_INSTALL"
    internal const val EXTRA_APP = "suite_app"

    private val log = Logbook.logger("update")
    private val _state = MutableStateFlow(UpdatesState())
    val state: StateFlow<UpdatesState> = _state
    @Volatile private var managed: Set<SuiteApp> = emptySet()

    /** Reads what's installed and the saved channel. Call before showing update state; cheap and repeatable. */
    fun init(context: Context, apps: Set<SuiteApp>) {
        val app = context.applicationContext
        managed = apps
        val release = Suite.isReleaseBuild(app)
        val p = prefs(app)
        val anyPreview = apps.mapNotNull { Suite.installed(app, it) }.any { (Version.parse(it.versionName) ?: Version(0, 0, 0)) < Version(1, 0, 0) }
        val channel = UpdateChannel(previews = p.getBoolean(KEY_PREVIEWS, anyPreview), tests = p.getBoolean(KEY_TESTS, false))
        _state.update { old ->
            old.copy(
                channel = channel,
                releaseBuild = release,
                lastCheck = p.getLong(KEY_LAST_CHECK, 0L),
                apps = apps.associateWith { a ->
                    val installed = Suite.installed(app, a)
                    val previous = old.apps[a]
                    when {
                        !release -> AppUpdate(a, installed, UpdatePhase.Development)
                        previous != null -> previous.copy(installed = installed)
                        else -> AppUpdate(a, installed)
                    }
                },
            )
        }
    }

    /** Re-reads installed versions, e.g. after an install or uninstall finished. */
    fun refreshInstalled(context: Context) = init(context, managed)

    fun checkIfDue(context: Context) {
        val app = context.applicationContext
        if (managed.isEmpty() || !_state.value.releaseBuild) return
        if (System.currentTimeMillis() - prefs(app).getLong(KEY_LAST_CHECK, 0L) >= DAY_MS) checkNow(app, automatic = true)
    }

    fun setChannel(context: Context, channel: UpdateChannel) {
        prefs(context).edit { putBoolean(KEY_PREVIEWS, channel.previews).putBoolean(KEY_TESTS, channel.tests) }
        log.i("channel", "previews" to channel.previews, "tests" to channel.tests)
        _state.update { s -> s.copy(channel = channel, apps = s.apps.mapValues { (_, u) -> if (u.phase is UpdatePhase.Development) u else u.copy(phase = UpdatePhase.Idle, offered = null) }) }
    }

    fun checkNow(context: Context, automatic: Boolean = false) {
        val app = context.applicationContext
        val s = _state.value
        if (!s.releaseBuild || s.checking || s.apps.values.any { it.busy }) return
        setAll { it.copy(phase = UpdatePhase.Checking) }
        AppWork.scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { ReleaseFeed.parse(httpText(app, ReleaseFeed.API, "application/vnd.github+json")) } }
            val now = System.currentTimeMillis()
            // Only a answered check counts: stamping the time before the result would let one failed check (no
            // network, GitHub down) keep automatic checks quiet for another day.
            if (result.isSuccess) prefs(app).edit { putLong(KEY_LAST_CHECK, now) }
            result.onSuccess { releases ->
                log.i("checked", "releases" to releases.size, "automatic" to automatic)
                val channel = _state.value.channel
                _state.update { st ->
                    st.copy(lastCheck = now, apps = st.apps.mapValues { (a, u) ->
                        val installed = Suite.installed(app, a)
                        val current = installed?.let { Version.parse(it.versionName) }
                        val offer = ReleaseFeed.newest(releases, a, current, channel)
                        val phase = when {
                            offer == null -> if (installed == null) UpdatePhase.NotPublished else UpdatePhase.UpToDate
                            offer.checksum == null && offer.checksumUrl == null -> UpdatePhase.NoChecksum(offer)
                            else -> UpdatePhase.Available(offer)
                        }
                        u.copy(installed = installed, phase = phase, offered = offer)
                    })
                }
            }.onFailure { e ->
                log.w("check failed", "automatic" to automatic, error = e)
                setAll { it.copy(phase = UpdatePhase.Failed(UpdatePhase.Step.Check, e.cleanMessage())) }
            }
        }
    }

    /** Downloads, verifies and installs the release offered for [target]: an update, or a first install. */
    fun install(context: Context, target: SuiteApp) {
        val app = context.applicationContext
        val u = _state.value[target] ?: return
        val release = u.offered ?: return
        if (u.busy) return
        if (!app.packageManager.canRequestPackageInstalls()) { set(target) { it.copy(phase = UpdatePhase.NeedsUnknownSources) }; return }
        AppWork.scope.launch {
            var step = UpdatePhase.Step.Download
            runCatching {
                set(target) { it.copy(phase = UpdatePhase.Downloading(0)) }
                val expected = release.checksum ?: withContext(Dispatchers.IO) { release.checksumUrl?.let { ReleaseFeed.checksumIn(httpText(app, it)) } }
                    ?: error("The release has no checksum.")
                val apk = withContext(Dispatchers.IO) { download(app, target, release) }
                step = UpdatePhase.Step.Verify
                set(target) { it.copy(phase = UpdatePhase.Verifying) }
                withContext(Dispatchers.IO) { verify(app, target, apk, expected) }
                step = UpdatePhase.Step.Install
                withContext(Dispatchers.IO) { commit(app, target, apk) }
                set(target) { it.copy(phase = UpdatePhase.Installing) }
                log.i("install sent", "app" to target.key, "version" to release.version)
            }.onFailure { e ->
                log.w("install failed", "app" to target.key, "step" to step, "version" to release.version, error = e)
                set(target) { it.copy(phase = UpdatePhase.Failed(step, e.cleanMessage())) }
            }
        }
    }

    fun openReleasePage(context: Context, app: SuiteApp? = null) {
        val url = app?.let { _state.value[it]?.offered?.htmlUrl } ?: Suite.RELEASES_URL
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun openUnknownSources(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    internal fun onInstallResult(context: Context, intent: Intent) {
        val target = SuiteApp.entries.firstOrNull { it.key == intent.getStringExtra(EXTRA_APP) } ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                log.i("installed", "app" to target.key)
                set(target) { it.copy(installed = Suite.installed(context, target), phase = UpdatePhase.Installed, offered = null) }
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                set(target) { it.copy(phase = UpdatePhase.Confirming) }
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                log.w("android refused", "app" to target.key, "status" to status, "message" to msg)
                set(target) { it.copy(phase = UpdatePhase.Failed(UpdatePhase.Step.Install, msg)) }
            }
        }
    }

    private fun download(context: Context, target: SuiteApp, release: Release): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.filter { it.name != release.apkName }?.forEach { it.delete() }
        val out = File(dir, release.apkName)
        val conn = open(context, release.apkUrl, readTimeoutMs = 60_000)
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
                            if (pct >= last + 5) {
                                last = pct - pct % 5
                                set(target) { it.copy(phase = UpdatePhase.Downloading(last)) }
                            }
                        }
                    }
                }
            }
            log.d("downloaded", "app" to target.key, "bytes" to read)
            return out
        } finally {
            conn.disconnect()
        }
    }

    private fun verify(context: Context, target: SuiteApp, file: File, expectedSha: String) {
        check(sha256(file).equals(expectedSha, ignoreCase = true)) { "Checksum mismatch." }
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Android could not read the APK.")
        check(info.packageName == target.packageName) { "The APK is ${info.packageName}, not ${target.packageName}." }
        Suite.installed(context, target)?.let { installed ->
            check(info.longVersionCode > installed.versionCode) { "The APK isn't newer than the installed app." }
            check(installed.certificate == info.signingSha256()) { "The APK isn't signed like the installed app." }
        }
        val own = Suite.ownCertificate(context)
        check(own != null && own == info.signingSha256()) { "The APK isn't signed with the suite's key." }
    }

    private fun commit(context: Context, target: SuiteApp, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(target.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input -> session.openWrite("base.apk", 0, apk.length()).use { output -> input.copyTo(output); session.fsync(output) } }
            val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL).putExtra(EXTRA_APP, target.key)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    private fun open(context: Context, url: String, accept: String? = null, readTimeoutMs: Int = 30_000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = readTimeoutMs
            setRequestProperty("User-Agent", userAgent(context))
            if (accept != null) setRequestProperty("Accept", accept)
        }

    private fun httpText(context: Context, url: String, accept: String? = null): String {
        val conn = open(context, url, accept)
        try {
            check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun userAgent(context: Context): String {
        val self = SuiteApp.of(context.packageName)
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return "${self?.title ?: "BooxUltimatum"}/${version ?: "unknown"}"
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
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun set(app: SuiteApp, change: (AppUpdate) -> AppUpdate) =
        _state.update { s -> s.apps[app]?.let { s.copy(apps = s.apps + (app to change(it))) } ?: s }

    private fun setAll(change: (AppUpdate) -> AppUpdate) =
        _state.update { s -> s.copy(apps = s.apps.mapValues { (_, u) -> if (u.phase is UpdatePhase.Development) u else change(u) }) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun Throwable.cleanMessage() = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Updates.ACTION_INSTALL) Updates.onInstallResult(context.applicationContext, intent)
    }
}
