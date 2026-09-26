package app.booxultimatum.core.exec

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import app.booxultimatum.BuildConfig
import app.booxultimatum.IShellService
import app.booxultimatum.core.Shell
import kotlinx.coroutines.Dispatchers
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
 */
object Privileged {
    private const val IDLE_MS = 45_000L
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
        idle.removeCallbacks(release)
        busy.incrementAndGet()
        val s = connect()
        if (s == null) { busy.decrementAndGet(); return@withContext ShellResult.Unavailable }
        try { runCatching {
            val b = s.exec(command)
            ShellResult(b.getInt(ShellService.KEY_CODE, -1), b.getString(ShellService.KEY_OUT).orEmpty(), b.getString(ShellService.KEY_ERR).orEmpty())
        }.getOrElse {
            service = null
            ShellResult(-1, "", it.toString())
        } } finally {
            busy.decrementAndGet()
            idle.removeCallbacks(release)
            idle.postDelayed(release, IDLE_MS)
        }
    }

    private suspend fun connect(): IShellService? = mutex.withLock {
        service?.takeIf { runCatching { it.asBinder().pingBinder() }.getOrDefault(false) }?.let { return it }
        if (!ready()) return null
        withTimeoutOrNull(10_000) {
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
