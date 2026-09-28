package app.booxultimatum.kit.log

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * Structured logs for a suite app: JSON Lines files in noBackupFilesDir/logs, a logcat mirror, an in-memory ring,
 * crash files and Android's exit records. Call [init] from Application.onCreate; loggers work before it.
 */
object Logbook {
    private const val PREFS = "kit.log"
    private const val KEY_DETAILED_UNTIL = "detailed_until"
    private const val KEY_EXIT_SEEN = "exit_seen"
    private const val LOG_CATEGORY = "log"
    private const val LOG_TAG = "BU/log"
    private const val FLUSH_TIMEOUT_MS = 2_000L
    private const val CRASH_FLUSH_TIMEOUT_MS = 1_000L
    private const val EXIT_RECORDS = 16

    /** Per-launch random id (8 hex chars) written into every event. */
    val session: String = Random.nextInt().toUInt().toString(16).padStart(8, '0')

    private val started = System.currentTimeMillis()
    internal val core = LogCore(SystemLogClock, session, Process.myPid(), logcat = AndroidLogcat)
    private val loggers = ConcurrentHashMap<String, Logger>()
    private val initLock = Any()
    private val detailedLock = Any()
    private var detailedLoaded = false

    @Volatile
    private var initialized = false

    @Volatile
    private var config = LogConfig()

    @Volatile
    private var header: LogHeader? = null

    /**
     * Idempotent. Starts the writer thread, installs the crash handler (chaining the previous one), records
     * ApplicationExitInfo entries not seen before, prunes old files. Safe to call from Application.onCreate.
     */
    fun init(context: Context, config: LogConfig = LogConfig()) {
        synchronized(initLock) {
            if (initialized) return
            val app = context.applicationContext ?: context
            val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            this.config = config
            core.configure(config, config.logcatLevel ?: if (debuggable) Level.Debug else Level.Info)
            installCrashHandler(app)
            initialized = true
            core.pipeline.start { startWriter(app, config, debuggable) }
        }
    }

    fun logger(category: String): Logger = loggers.getOrPut(category) { Logger(category) }

    /** Raise every category to Verbose until the given wall-clock time (persisted in prefs, survives restarts); 0 clears. */
    fun setDetailedUntil(context: Context, wallMs: Long) {
        val until = wallMs.coerceAtLeast(0L)
        synchronized(detailedLock) {
            core.policy.detailedUntil = until
            detailedLoaded = true
            prefs(context).edit { putLong(KEY_DETAILED_UNTIL, until) }
        }
        core.emit(LOG_CATEGORY, LOG_TAG, Level.Info, "detailed mode", arrayOf("until" to until), null)
    }

    fun detailedUntil(context: Context): Long {
        loadDetailed(context)
        return core.policy.detailedUntil
    }

    /** Blocks until queued events are on disk (bounded wait, e.g. 2 s). */
    fun flush() {
        core.pipeline.flush(FLUSH_TIMEOUT_MS)
    }

    /** Newest-last copy of the in-memory ring buffer. */
    fun recent(max: Int = 500): List<LogEvent> = core.ring.snapshot(max)

    /** Log, crash and exit files of this app, newest first. */
    fun files(context: Context): List<File> = LogFiles.list(logsDir(context))

    /** Writes one zip: this app's files under "<packageName>/", plus any extra entries (name to bytes), plus README.txt. */
    fun exportZip(context: Context, out: OutputStream, extra: Map<String, ByteArray> = emptyMap()) {
        flush()
        LogExport.write(out, context.packageName, files(context), extra, LogExport.readme(config))
    }

    /** Parses one JSONL line written by this module; null for the header line or garbage. */
    fun parse(line: String): LogEvent? = EventFormat.parse(line)

    internal fun logsDir(context: Context): File = File(context.noBackupFilesDir, "logs")

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadDetailed(context: Context) {
        synchronized(detailedLock) {
            if (detailedLoaded) return
            core.policy.detailedUntil = prefs(context).getLong(KEY_DETAILED_UNTIL, 0L)
            detailedLoaded = true
        }
    }

    // Runs on the writer thread, so nothing here delays Application.onCreate.
    private fun startWriter(app: Context, config: LogConfig, debuggable: Boolean): LogFileWriter {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        runCatching { loadDetailed(app) }
        val h = buildHeader(app, debuggable)
        header = h
        val dir = logsDir(app)
        runCatching {
            dir.mkdirs()
            val now = System.currentTimeMillis()
            LogFiles.pruneAge(dir, LogFiles::isEvent, config.maxAgeDays.coerceAtLeast(0) * LogFiles.DAY_MS, now)
            LogFiles.pruneCount(dir, LogFiles::isEvent, config.maxFiles)
            LogFiles.pruneCount(dir, LogFiles::isCrash, config.keepCrashes)
        }
        runCatching { recordExits(app, dir) }
        runCatching { Housekeeping.tidyIfDue(app) }
        return LogFileWriter(dir, SystemLogClock, h, config.maxFileBytes, config.maxFiles)
    }

    private fun buildHeader(app: Context, debuggable: Boolean): LogHeader {
        val info = runCatching { packageInfo(app) }.getOrNull()
        return LogHeader(
            app = app.packageName,
            version = info?.versionName ?: "?",
            versionCode = info?.longVersionCode ?: 0L,
            debuggable = debuggable,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            android = "${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})",
            session = session,
            pid = core.pid,
            started = started,
        )
    }

    private fun packageInfo(app: Context): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageInfo(app.packageName, 0)
        }

    private fun installCrashHandler(app: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(
            CrashHandler(
                previous = previous,
                fallback = { _, _ ->
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                },
                record = { thread, error -> recordCrash(app.packageName, logsDir(app), thread, error) },
            ),
        )
    }

    private fun recordCrash(packageName: String, dir: File, thread: Thread, error: Throwable) {
        val file = try {
            CrashFile.write(
                dir = dir,
                wallMs = System.currentTimeMillis(),
                thread = thread.name,
                session = session,
                app = packageName,
                version = header?.version,
                pid = core.pid,
                error = error,
                recent = core.ring.snapshot(CrashFile.RECENT),
                keep = config.keepCrashes,
            )
        } catch (_: Throwable) {
            null
        }
        core.emit(
            LOG_CATEGORY,
            LOG_TAG,
            Level.Error,
            "uncaught exception",
            arrayOf("thread" to thread.name, "file" to file?.name),
            error,
        )
        core.pipeline.flush(CRASH_FLUSH_TIMEOUT_MS)
    }

    private fun recordExits(app: Context, dir: File) {
        val am = app.getSystemService(ActivityManager::class.java) ?: return
        val prefs = prefs(app)
        val seen = prefs.getLong(KEY_EXIT_SEEN, 0L)
        var newest = seen
        val infos = am.getHistoricalProcessExitReasons(app.packageName, 0, EXIT_RECORDS)
        for (info in infos.sortedBy { it.timestamp }) {
            if (info.timestamp <= seen) continue
            newest = maxOf(newest, info.timestamp)
            runCatching { recordExit(info, dir) }
        }
        if (newest > seen) prefs.edit { putLong(KEY_EXIT_SEEN, newest) }
        LogFiles.pruneCount(dir, LogFiles::isExit, LogFiles.MAX_EXIT_FILES)
    }

    private fun recordExit(info: ApplicationExitInfo, dir: File) {
        val reason = ExitReasons.name(info.reason)
        val withTrace = info.reason == ApplicationExitInfo.REASON_ANR ||
            (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val trace = if (withTrace) runCatching { copyTrace(info, dir, reason) }.getOrNull() else null
        core.emit(
            LOG_CATEGORY,
            LOG_TAG,
            if (ExitReasons.isFailure(info.reason)) Level.Warn else Level.Info,
            "process exit",
            arrayOf(
                "reason" to reason,
                "description" to info.description,
                "importance" to info.importance,
                "pss" to info.pss,
                "rss" to info.rss,
                "status" to info.status,
                "timestamp" to info.timestamp,
                "trace" to trace?.name,
            ),
            null,
        )
    }

    // ANR traces are text; native crash traces are tombstones in protobuf, hence .pb.
    private fun copyTrace(info: ApplicationExitInfo, dir: File, reason: String): File? {
        val input = info.traceInputStream ?: return null
        val extension = if (info.reason == ApplicationExitInfo.REASON_ANR) ".txt" else ".pb"
        val file = LogFiles.newExitFile(dir, info.timestamp, reason, extension)
        input.use { src -> file.outputStream().use { LogFiles.copyCapped(src, it, LogFiles.MAX_EXIT_BYTES) } }
        file.setLastModified(info.timestamp)
        return file
    }
}

internal object AndroidLogcat : LogcatSink {
    override fun write(level: Level, tag: String, message: String, error: Throwable?) {
        when (level) {
            Level.Verbose -> Log.v(tag, message, error)
            Level.Debug -> Log.d(tag, message, error)
            Level.Info -> Log.i(tag, message, error)
            Level.Warn -> Log.w(tag, message, error)
            Level.Error -> Log.e(tag, message, error)
        }
    }
}
