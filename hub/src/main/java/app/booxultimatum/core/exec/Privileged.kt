package app.booxultimatum.core.exec

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import app.booxultimatum.BuildConfig
import app.booxultimatum.IShellService
import app.booxultimatum.core.Shell
import app.booxultimatum.kit.log.Logbook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.resume

data class ShellResult(val code: Int, val out: String, val err: String) {
    val ok get() = code == 0

    /** The most useful single line to show a person when something failed: the exception itself if there is one. */
    val message: String
        get() {
            val lines = (err.ifBlank { out }).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            val exception = lines.firstOrNull { Regex("""^[\w.]+(Exception|Error): """).containsMatchIn(it) }
            return (exception?.substringAfter(": ") ?: lines.firstOrNull() ?: "exit $code").take(240)
        }
}

/** One line of [Privileged.probe]: a step between this app and the shell, whether it worked, and what was seen. */
data class ProbeStep(val label: String, val ok: Boolean, val detail: String)

/** How long a helper service that has failed [failures] times in a row is left alone: 10 minutes, then an hour, then six hours. */
internal fun helperRetryDelayMs(failures: Int): Long = when {
    failures <= 1 -> 10 * 60_000L
    failures == 2 -> 60 * 60_000L
    else -> 6 * 60 * 60_000L
}

/**
 * Says from `ps -A` whether the helper's process exists: the same process name as ours with `:shell` after it. It tells a
 * helper that was never started, or died while starting, from one that is running and never answers.
 */
internal fun describeHelperProcess(ps: String, appId: String): String {
    val ours = ps.lineSequence().map { it.trim().split(Regex("\\s+")) }
        .filter { it.size >= 3 && (it.last() == appId || it.last().startsWith("$appId:")) }
        .map { it.last() to "pid ${it[1]}, user ${it.first()}" }
        .toList()
    val helper = ours.firstOrNull { it.first == "$appId:shell" }
    val others = ours.filter { it.first != "$appId:shell" }.joinToString("; ") { "${it.first} (${it.second})" }
    return when {
        helper != null -> "$appId:shell is running (${helper.second}) and never answered. Other processes of this app: ${others.ifEmpty { "none" }}"
        ours.isEmpty() -> "no process of this app is listed, so the list may be incomplete"
        else -> "no $appId:shell process: the helper was never started, or it ended while starting. This app's processes: $others"
    }
}

/**
 * The log lines worth reading when the helper doesn't start: those that mention Shizuku, a user service or the helper's
 * process, and a crash of one of this app's processes with the lines under its header. At most [max], the newest.
 */
internal fun helperLogLines(logcat: String, appId: String, max: Int = 30): List<String> {
    val lines = logcat.lines()
    val picked = sortedSetOf<Int>()
    lines.forEachIndexed { i, line ->
        val low = line.lowercase()
        if (line.contains("AndroidRuntime") && line.contains(appId)) {
            // The header's "Process:" line names the process; the exception and its frames follow it under the same tag.
            if (i > 0 && lines[i - 1].contains("AndroidRuntime")) picked += i - 1
            var j = i
            while (j <= lines.lastIndex && j < i + 25 && lines[j].contains("AndroidRuntime")) picked += j++
        } else if (low.contains("shizuku") || low.contains("userservice") || line.contains("$appId:shell")) picked += i
    }
    return picked.map { lines[it].trim().take(220) }.filter { it.isNotEmpty() }.takeLast(max)
}

/**
 * Tier T2: shell-uid commands through Shizuku.
 *
 * The usual route is a user service: a small process of ours started by Shizuku that runs `sh -c`. It lives in its own
 * `:shell` process (about 65 MB PSS), and our process lives for as long as the home screen does, so the service is
 * unbound and its process removed after [IDLE_MS] without calls instead of staying resident.
 *
 * Some tablets never let that service start (reported on a Go 10.3 with two Shizuku builds, while other Shizuku apps
 * worked). Shizuku's own remote process runs the same `sh -c` as the same user without any helper of ours, so when the
 * service can't be reached, calls go that way instead of failing.
 *
 * Once the service has failed, no call waits for it again: calls go straight to the direct route and the service is
 * tried in the background, after [helperRetryDelayMs] (longer each time it fails) or when Shizuku restarts. The state is
 * in memory, so a restart of the app starts afresh.
 *
 * Nothing here can hang a page: a call gives up after [CALL_TIMEOUT_MS]. Every failure is logged under `exec`.
 */
object Privileged {
    private const val IDLE_MS = 45_000L
    private const val BIND_TIMEOUT_MS = 10_000L
    /** The probe waits longer than a call does, to tell a helper that is slow from one that never starts. */
    private const val PROBE_BIND_TIMEOUT_MS = 30_000L
    private const val CALL_TIMEOUT_MS = 25_000L
    private const val MAX_CHARS = 250_000
    private val log = Logbook.logger("exec")
    @Volatile private var helperBrokenUntil = 0L
    /** Times in a row the helper failed to start; 0 until it has, and again once it has connected. */
    @Volatile private var helperFailures = 0
    @Volatile private var lastBindError: String? = null
    private val retrying = AtomicBoolean(false)
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Blocking binder calls run here, so a stuck one never takes a thread other work needs. */
    private val calls = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "shizuku-call").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val mutex = Mutex()
    @Volatile private var service: IShellService? = null
    @Volatile private var connection: ServiceConnection? = null
    private val busy = java.util.concurrent.atomic.AtomicInteger(0)
    private val idle = android.os.Handler(android.os.Looper.getMainLooper())
    private val release: Runnable = object : Runnable {
        override fun run() {
            if (busy.get() > 0) { idle.postDelayed(this, IDLE_MS); return }
            val c = connection ?: return
            connection = null
            service = null
            runCatching { Shizuku.unbindUserService(args, c, true) }
        }
    }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    /** `Shizuku.newProcess` is private since API 13.1 and slated for removal, so it is reached by reflection and may be missing. */
    private val remoteProcess by lazy {
        Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            .apply { isAccessible = true }
    }

    fun ready(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Why [ready] is false, in words, or null when it is true. */
    private fun notReadyReason(): String? = when {
        !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> "Shizuku isn’t running"
        !runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false) ->
            "Shizuku is running but hasn’t allowed BooxUltimatum"
        else -> null
    }

    private fun liveService(): IShellService? =
        service?.takeIf { runCatching { it.asBinder().pingBinder() }.getOrDefault(false) }

    suspend fun sh(command: String): ShellResult = withContext(Dispatchers.IO) {
        notReadyReason()?.let { return@withContext ShellResult(-1, "", it) }
        if (liveService() == null && helperFailures > 0) {
            // The helper has failed before, so this call doesn't wait for another try: it goes the direct way at once.
            if (System.currentTimeMillis() >= helperBrokenUntil) retryHelperInBackground()
            val d = direct(command)
            if (d.ok || d.code != -1) return@withContext d
            // The direct route is unavailable too (a Shizuku without it). Inside the pause there is nothing else to try;
            // after it, the helper is the only way left, so the call waits for it below.
            if (System.currentTimeMillis() < helperBrokenUntil) return@withContext d
        }
        idle.removeCallbacks(release)
        busy.incrementAndGet()
        val started = android.os.SystemClock.elapsedRealtime()
        val s = connect()
        if (s == null) {
            busy.decrementAndGet()
            notReadyReason()?.let { return@withContext ShellResult(-1, "", it) }
            log.w("helper service didn’t start, using Shizuku directly", "waited ms" to android.os.SystemClock.elapsedRealtime() - started, "error" to lastBindError)
            return@withContext direct(command)
        }
        try {
            val r = viaHelper(s, command)
            val ms = android.os.SystemClock.elapsedRealtime() - started
            // Only the command's verb is logged: its arguments name the owner's apps.
            if (!r.ok) log.w("command failed", "verb" to verb(command), "code" to r.code, "ms" to ms, "error" to scrub(r.message))
            else log.debug { "ran ${verb(command)} in $ms ms" }
            r
        } finally {
            busy.decrementAndGet()
            idle.removeCallbacks(release)
            idle.postDelayed(release, IDLE_MS)
        }
    }

    private suspend fun viaHelper(s: IShellService, command: String): ShellResult = coroutineScope {
        val call = async(calls) {
            runCatching {
                val b = s.exec(command)
                ShellResult(b.getInt(ShellService.KEY_CODE, -1), b.getString(ShellService.KEY_OUT).orEmpty(), b.getString(ShellService.KEY_ERR).orEmpty())
            }.getOrElse {
                service = null
                ShellResult(-1, "", it.toString())
            }
        }
        withTimeoutOrNull(CALL_TIMEOUT_MS) { call.await() } ?: run {
            call.cancel()
            service = null
            ShellResult(-2, "", "No answer within ${CALL_TIMEOUT_MS / 1000} s")
        }
    }

    /** The same `sh -c` through Shizuku's own remote process: no helper of ours, which is why it can work where the helper won't start. */
    private suspend fun direct(command: String): ShellResult {
        val started = android.os.SystemClock.elapsedRealtime()
        val process = AtomicReference<Process?>(null)
        val r = coroutineScope {
            val call = async(calls) {
                runCatching {
                    val p = remoteProcess.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
                    process.set(p)
                    var out = ""
                    var err = ""
                    val outReader = thread(isDaemon = true) { out = runCatching { readLimited(p.inputStream) }.getOrDefault("") }
                    val errReader = thread(isDaemon = true) { err = runCatching { readLimited(p.errorStream) }.getOrDefault("") }
                    val code = p.waitFor()
                    outReader.join(2_000)
                    errReader.join(2_000)
                    runCatching { p.destroy() }
                    ShellResult(code, out, err)
                }.getOrElse { ShellResult(-1, "", (it.cause ?: it).toString()) }
            }
            withTimeoutOrNull(CALL_TIMEOUT_MS) { call.await() } ?: run {
                runCatching { process.get()?.destroy() }
                call.cancel()
                ShellResult(-2, "", "No answer within ${CALL_TIMEOUT_MS / 1000} s")
            }
        }
        val ms = android.os.SystemClock.elapsedRealtime() - started
        if (!r.ok) log.w("direct command failed", "verb" to verb(command), "code" to r.code, "ms" to ms, "error" to scrub(r.message))
        else log.debug { "ran ${verb(command)} directly in $ms ms" }
        return if (r.ok || r.code != -1) r
        else ShellResult(-1, "", "Shizuku’s helper service didn’t start (${lastBindError ?: "no answer"}) and its direct route failed: ${r.message}")
    }

    private fun readLimited(input: InputStream): String {
        val sb = StringBuilder()
        input.bufferedReader().use { r ->
            val buf = CharArray(8192)
            while (true) {
                val n = r.read(buf)
                if (n < 0) break
                val room = MAX_CHARS - sb.length
                if (room > 0) sb.append(buf, 0, minOf(n, room))
            }
        }
        return sb.toString()
    }

    /** Lets the helper service be tried again at once, from the shortest pause, for example after Shizuku restarted. */
    fun retryNow() {
        helperBrokenUntil = 0L
        helperFailures = minOf(helperFailures, 1)
    }

    /** One try at a time, off the caller's thread: a call that finds the pause over is answered the direct way meanwhile. */
    private fun retryHelperInBackground() {
        if (!retrying.compareAndSet(false, true)) return
        background.launch {
            try {
                if (connect() != null) {
                    // Nobody is using it yet: let it go after the idle time rather than keep a 65 MB process resident.
                    idle.removeCallbacks(release)
                    idle.postDelayed(release, IDLE_MS)
                }
            } finally {
                retrying.set(false)
            }
        }
    }

    /**
     * Walks the steps between this app and the shell and says where it stops: the server, our permission, the helper
     * service and a command through it, and a command through Shizuku's own process. It tries the helper afresh and waits
     * up to [PROBE_BIND_TIMEOUT_MS] for it, so it can take half a minute. When the helper doesn't start, it also reads
     * from the shell why: whether the helper's process exists, and the log lines about Shizuku and the helper. Each step
     * is logged under `exec` too.
     */
    suspend fun probe(): List<ProbeStep> = withContext(Dispatchers.IO) {
        val steps = mutableListOf<ProbeStep>()
        fun add(label: String, ok: Boolean, detail: String) {
            steps += ProbeStep(label, ok, detail)
            log.i("probe", "step" to label, "ok" to ok, "detail" to scrub(detail))
        }
        add(
            "This tablet",
            true,
            "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.firstOrNull()}, " +
                "build ${Build.DISPLAY}, BooxUltimatum ${BuildConfig.VERSION_NAME}",
        )
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            add("Shizuku server", false, "not running: start it again (the Shizuku app or the bundled start script)")
            return@withContext steps
        }
        add(
            "Shizuku server",
            true,
            listOf(
                runCatching { "API ${Shizuku.getVersion()}" }.getOrDefault("API unknown"),
                runCatching { "uid ${Shizuku.getUid()}" }.getOrDefault("uid unknown"),
                runCatching { Shizuku.getSELinuxContext() ?: "no SELinux context" }.getOrDefault("SELinux context unknown"),
            ).joinToString(", "),
        )
        val allowed = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        add("Permission for BooxUltimatum", allowed, if (allowed) "allowed" else "not allowed: allow BooxUltimatum in the Shizuku app")
        if (!allowed) return@withContext steps

        retryNow()
        val started = android.os.SystemClock.elapsedRealtime()
        val helper = connect(PROBE_BIND_TIMEOUT_MS, record = false)
        val bindMs = android.os.SystemClock.elapsedRealtime() - started
        if (helper == null) {
            add("Helper service", false, "didn’t start (${lastBindError ?: "no answer within ${PROBE_BIND_TIMEOUT_MS / 1000} s"})")
        } else {
            val slow = if (bindMs > BIND_TIMEOUT_MS) ", longer than the ${BIND_TIMEOUT_MS / 1000} s a call waits for it" else ""
            add("Helper service", true, "connected in $bindMs ms$slow")
            val r = sh("id")
            add("Command through the helper", r.ok, if (r.ok) r.out.trim().take(120) else r.message)
        }
        val d = direct("id")
        add("Command through Shizuku directly", d.ok, if (d.ok) d.out.trim().take(120) else d.message)
        // The helper didn't start: the direct route is how to look at why, from the same user the helper would run as.
        if (helper == null && d.ok) {
            val ps = direct("ps -A")
            add("Evidence: the helper's process", ps.ok, if (ps.ok) describeHelperProcess(ps.out, BuildConfig.APPLICATION_ID) else ps.message)
            val logcat = direct("logcat -d -t 3000 -v threadtime")
            val lines = if (logcat.ok) helperLogLines(logcat.out, BuildConfig.APPLICATION_ID).map { scrub(it) } else emptyList()
            add(
                "Evidence: the system log",
                logcat.ok,
                when {
                    !logcat.ok -> logcat.message
                    lines.isEmpty() -> "no lines about Shizuku or the helper in the last 3000 log lines"
                    else -> "the newest ${lines.size} lines about Shizuku or the helper (app names left out):\n" + lines.joinToString("\n")
                },
            )
        }
        steps
    }

    /** The command without its arguments, for example "cmd appops get": package names and values are dropped. */
    private fun verb(command: String) = command.trim().split(Regex("\\s+"))
        .takeWhile { !it.contains('.') && !it.startsWith("-") && it.any(Char::isLetter) }.take(4).joinToString(" ")

    /** Error text with the owner's app names removed; exception names and the system's, Boox's and the suite's stay. */
    private fun scrub(text: String) = PACKAGE.replace(text) { m -> if (KEEP.any { m.value.startsWith(it) }) m.value else "[app]" }

    private val KEEP = listOf("java.", "javax.", "kotlin.", "android.", "com.android.", "dalvik.", "com.onyx", "app.booxultimatum", "rikka.", "moe.shizuku")

    private val PACKAGE = Regex("""\b[a-zA-Z][\w]*(?:\.[\w]+){2,}\b""")

    /** [record] is false for the probe, whose failures say nothing about how long to leave the helper alone. */
    private suspend fun connect(waitMs: Long = BIND_TIMEOUT_MS, record: Boolean = true): IShellService? = mutex.withLock {
        liveService()?.let { return it }
        if (!ready()) return null
        if (System.currentTimeMillis() < helperBrokenUntil) return null
        lastBindError = null
        val bound = withTimeoutOrNull(waitMs) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            val s = binder?.takeIf { it.pingBinder() }?.let { IShellService.Stub.asInterface(it) }
                            service = s
                            if (s != null) {
                                helperBrokenUntil = 0L
                                helperFailures = 0
                            }
                            if (cont.isActive) cont.resume(s)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            service = null
                        }
                    }
                    connection?.let { old -> runCatching { Shizuku.unbindUserService(args, old, false) } }
                    connection = conn
                    runCatching { Shizuku.bindUserService(args, conn) }.onFailure {
                        lastBindError = it.cause?.toString() ?: it.toString()
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
        if (bound == null) {
            if (lastBindError == null) lastBindError = "no answer within ${waitMs / 1000} s"
            if (record) {
                helperFailures++
                helperBrokenUntil = System.currentTimeMillis() + helperRetryDelayMs(helperFailures)
            }
        }
        bound
    }
}

/**
 * `dumpsys` through Shizuku when possible, otherwise from the app itself using the adb-granted DUMP permission.
 * Background logging passes `preferApp`: with DUMP granted (T1) the app runs dumpsys itself and never starts the
 * 65 MB `:shell` helper process just to read a snapshot.
 */
object Diagnostics {
    suspend fun dumpsys(args: String, preferApp: Boolean = false): ShellResult {
        if (preferApp) appDumpsys(args).let { if (it.ok && it.out.isNotBlank() && !it.out.take(300).contains("Permission Denial")) return it }
        if (Privileged.ready()) {
            val r = Privileged.sh("dumpsys $args")
            if (r.ok) return r
        }
        return appDumpsys(args)
    }

    private suspend fun appDumpsys(args: String): ShellResult = withContext(Dispatchers.IO) {
        val r = Shell.run("dumpsys", *args.split(' ').filter { it.isNotBlank() }.toTypedArray(), timeoutSec = 20)
        ShellResult(r.exitCode, r.stdout, r.stderr)
    }
}
