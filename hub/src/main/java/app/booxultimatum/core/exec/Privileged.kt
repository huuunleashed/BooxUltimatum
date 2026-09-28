package app.booxultimatum.core.exec

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
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

    companion object {
        val Unavailable = ShellResult(-1, "", "Shizuku is not running or not allowed")
    }
}

/**
 * Tier T2: shell-uid commands through a Shizuku user service.
 *
 * The service runs in its own `:shell` process (about 65 MB PSS). Our process lives for as long as the home screen does, so
 * the service is unbound and its process removed after [IDLE_MS] without calls instead of staying resident.
 *
 * Nothing here can hang a page: a call gives up after [CALL_TIMEOUT_MS], and when the service can't be reached, calls
 * fail at once for [RETRY_AFTER_MS] instead of each waiting for its own connection attempt. Pages that make one call per
 * app would otherwise wait minutes. Every failure is logged under `exec`.
 */
object Privileged {
    private const val IDLE_MS = 45_000L
    private const val BIND_TIMEOUT_MS = 10_000L
    private const val CALL_TIMEOUT_MS = 25_000L
    private const val RETRY_AFTER_MS = 20_000L
    private val log = Logbook.logger("exec")
    @Volatile private var unreachableUntil = 0L
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

    fun ready(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    suspend fun sh(command: String): ShellResult = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() < unreachableUntil) return@withContext ShellResult.Unavailable
        idle.removeCallbacks(release)
        busy.incrementAndGet()
        val started = android.os.SystemClock.elapsedRealtime()
        val s = connect()
        if (s == null) {
            busy.decrementAndGet()
            if (ready()) {
                unreachableUntil = System.currentTimeMillis() + RETRY_AFTER_MS
                log.w("service unreachable", "waited ms" to android.os.SystemClock.elapsedRealtime() - started, "retry in ms" to RETRY_AFTER_MS)
            }
            return@withContext ShellResult.Unavailable
        }
        try {
            val r = coroutineScope {
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

    /** Lets calls try the service again at once, for example after Shizuku restarted. */
    fun retryNow() { unreachableUntil = 0L }

    /** The command without its arguments, for example "cmd appops get": package names and values are dropped. */
    private fun verb(command: String) = command.trim().split(Regex("\\s+"))
        .takeWhile { !it.contains('.') && !it.startsWith("-") && it.any(Char::isLetter) }.take(4).joinToString(" ")

    /** Error text with the owner's app names removed; exception names and the system's, Boox's and the suite's stay. */
    private fun scrub(text: String) = PACKAGE.replace(text) { m -> if (KEEP.any { m.value.startsWith(it) }) m.value else "[app]" }

    private val KEEP = listOf("java.", "javax.", "kotlin.", "android.", "com.android.", "dalvik.", "com.onyx", "app.booxultimatum", "rikka.")

    private val PACKAGE = Regex("""\b[a-zA-Z][\w]*(?:\.[\w]+){2,}\b""")

    private suspend fun connect(): IShellService? = mutex.withLock {
        service?.takeIf { runCatching { it.asBinder().pingBinder() }.getOrDefault(false) }?.let { return it }
        if (!ready()) return null
        withTimeoutOrNull(BIND_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            val s = binder?.takeIf { it.pingBinder() }?.let { IShellService.Stub.asInterface(it) }
                            service = s
                            if (cont.isActive) cont.resume(s)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            service = null
                        }
                    }
                    connection?.let { old -> runCatching { Shizuku.unbindUserService(args, old, false) } }
                    connection = conn
                    runCatching { Shizuku.bindUserService(args, conn) }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
        }
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
