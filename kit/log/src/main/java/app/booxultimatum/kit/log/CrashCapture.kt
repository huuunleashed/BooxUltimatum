package app.booxultimatum.kit.log

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** The crash file: one JSON object with the stack trace and the events that led up to it. */
internal object CrashFile {
    const val RECENT = 200

    fun content(
        wallMs: Long,
        thread: String,
        session: String,
        app: String?,
        version: String?,
        pid: Int,
        error: Throwable,
        recent: List<LogEvent>,
    ): String {
        val sb = StringBuilder(8192)
        sb.append("{\"t\":").append(wallMs)
        sb.append(",\"thread\":")
        Json.appendString(sb, thread)
        sb.append(",\"session\":")
        Json.appendString(sb, session)
        if (app != null) {
            sb.append(",\"app\":")
            Json.appendString(sb, app)
        }
        if (version != null) {
            sb.append(",\"version\":")
            Json.appendString(sb, version)
        }
        sb.append(",\"pid\":").append(pid)
        sb.append(",\"error\":")
        Json.appendString(sb, fullTrace(error))
        sb.append(",\"recent\":[")
        for (i in recent.indices) {
            if (i > 0) sb.append(',')
            EventFormat.appendEvent(sb, recent[i])
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Writes the crash file synchronously, then keeps the newest [keep] crash files. */
    fun write(
        dir: File,
        wallMs: Long,
        thread: String,
        session: String,
        app: String?,
        version: String?,
        pid: Int,
        error: Throwable,
        recent: List<LogEvent>,
        keep: Int,
    ): File {
        dir.mkdirs()
        val file = LogFiles.newCrashFile(dir, wallMs)
        file.writeText(content(wallMs, thread, session, app, version, pid, error, recent), Charsets.UTF_8)
        LogFiles.pruneCount(dir, LogFiles::isCrash, keep, protect = file)
        return file
    }

    private fun fullTrace(error: Throwable): String = try {
        error.stackTraceToString()
    } catch (_: Throwable) {
        error.javaClass.name
    }
}

/**
 * The default uncaught-exception handler: records the crash, then hands it to the handler that was installed before.
 * Nothing it does can throw.
 */
internal class CrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val fallback: (Thread, Throwable) -> Unit,
    private val record: (Thread, Throwable) -> Unit,
) : Thread.UncaughtExceptionHandler {
    private val recording = AtomicBoolean(false)

    override fun uncaughtException(thread: Thread, error: Throwable) {
        // A crash while recording (or a second thread crashing meanwhile) goes straight to the previous handler.
        if (recording.compareAndSet(false, true)) {
            try {
                record(thread, error)
            } catch (_: Throwable) {
            } finally {
                recording.set(false)
            }
        }
        try {
            val p = previous
            if (p != null) p.uncaughtException(thread, error) else fallback(thread, error)
        } catch (_: Throwable) {
        }
    }
}
