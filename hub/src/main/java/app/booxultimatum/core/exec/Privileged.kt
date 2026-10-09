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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.InputStream
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

/**
 * Tier T2: shell-uid commands through Shizuku.
 *
 * The usual route is a user service: a small process of ours started by Shizuku that runs `sh -c`. It lives in its own
 * `:shell` process (about 65 MB PSS), and our process lives for as long as the home screen does, so the service is
 * unbound and its process removed after [IDLE_MS] without calls instead of staying resident.
 *
 * Some tablets never let that service start (reported on a Go 10.3 with two Shizuku builds, while other Shizuku apps
 * worked). Shizuku's own remote process runs the same `sh -c` as the same user without any helper of ours, so when the
 * service can't be reached, calls go that way instead of failing, and the service is tried again after [BROKEN_FOR_MS]
 * or when Shizuku restarts.
 *
 * Nothing here can hang a page: a call gives up after [CALL_TIMEOUT_MS]. Every failure is logged under `exec`.
 */
object Privileged {
    private const val IDLE_MS = 45_000L
    private const val BIND_TIMEOUT_MS = 10_000L
    private const val CALL_TIMEOUT_MS = 25_000L
    private const val BROKEN_FOR_MS = 10 * 60_000L
    private const val MAX_CHARS = 250_000
    private val log = Logbook.logger("exec")
    @Volatile private var helperBrokenUntil = 0L
    @Volatile private var lastBindError: String? = null
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
        if (liveService() == null && System.currentTimeMillis() < helperBrokenUntil) return@withContext direct(command)
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

    /** Lets calls try the helper service again at once, for example after Shizuku restarted. */
    fun retryNow() { helperBrokenUntil = 0L }

    /**
     * Walks the steps between this app and the shell and says where it stops: the server, our permission, the helper
     * service and a command through it, and a command through Shizuku's own process. It tries the helper afresh, so it
     * can take ten seconds. Each step is logged under `exec` too.
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
        val helper = connect()
        val bindMs = android.os.SystemClock.elapsedRealtime() - started
        if (helper == null) {
            add("Helper service", false, "didn’t start (${lastBindError ?: "no answer within ${BIND_TIMEOUT_MS / 1000} s"})")
        } else {
            add("Helper service", true, "started in $bindMs ms")
            val r = sh("id")
            add("Command through the helper", r.ok, if (r.ok) r.out.trim().take(120) else r.message)
        }
        val d = direct("id")
        add("Command through Shizuku directly", d.ok, if (d.ok) d.out.trim().take(120) else d.message)
        steps
    }

    /** The command without its arguments, for example "cmd appops get": package names and values are dropped. */
    private fun verb(command: String) = command.trim().split(Regex("\\s+"))
        .takeWhile { !it.contains('.') && !it.startsWith("-") && it.any(Char::isLetter) }.take(4).joinToString(" ")

    /** Error text with the owner's app names removed; exception names and the system's, Boox's and the suite's stay. */
    private fun scrub(text: String) = PACKAGE.replace(text) { m -> if (KEEP.any { m.value.startsWith(it) }) m.value else "[app]" }

    private val KEEP = listOf("java.", "javax.", "kotlin.", "android.", "com.android.", "dalvik.", "com.onyx", "app.booxultimatum", "rikka.", "moe.shizuku")

    private val PACKAGE = Regex("""\b[a-zA-Z][\w]*(?:\.[\w]+){2,}\b""")

    private suspend fun connect(): IShellService? = mutex.withLock {
        liveService()?.let { return it }
        if (!ready()) return null
        if (System.currentTimeMillis() < helperBrokenUntil) return null
        lastBindError = null
        val bound = withTimeoutOrNull(BIND_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            val s = binder?.takeIf { it.pingBinder() }?.let { IShellService.Stub.asInterface(it) }
                            service = s
                            if (s != null) helperBrokenUntil = 0L
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
            if (lastBindError == null) lastBindError = "no answer within ${BIND_TIMEOUT_MS / 1000} s"
            helperBrokenUntil = System.currentTimeMillis() + BROKEN_FOR_MS
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
