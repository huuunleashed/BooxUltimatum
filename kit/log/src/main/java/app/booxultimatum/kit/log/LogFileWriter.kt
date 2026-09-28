package app.booxultimatum.kit.log

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer

/**
 * Appends event lines to the current event file, opening the first file lazily and rotating to a new file, with a
 * new header, before a line would push it past [maxFileBytes]. Used by one thread only.
 */
internal class LogFileWriter(
    private val dir: File,
    private val clock: LogClock,
    private val header: LogHeader,
    maxFileBytes: Long,
    maxFiles: Int,
) {
    private val maxBytes = maxFileBytes.coerceAtLeast(1)
    private val maxFiles = maxFiles.coerceAtLeast(1)
    private val line = StringBuilder(512)
    private var chars = CharArray(1024)
    private var out: Writer? = null
    private var bytes = 0L
    private var events = 0

    var current: File? = null
        private set

    fun write(event: LogEvent) {
        line.setLength(0)
        EventFormat.appendEvent(line, event)
        line.append('\n')
        val size = Json.utf8Length(line)
        var w = out
        if (w == null || (events > 0 && bytes + size > maxBytes)) w = open()
        put(w, line)
        bytes += size
        events++
    }

    fun flush() {
        out?.flush()
    }

    /** Closes the file; the next write starts a new one. Also the recovery after an I/O error. */
    fun close() {
        val w = out ?: return
        out = null
        try {
            w.close()
        } catch (_: IOException) {
        }
    }

    private fun open(): Writer {
        close()
        dir.mkdirs()
        val file = LogFiles.newEventFile(dir, clock.wallMs(), header.pid)
        val w = BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8), BUFFER_CHARS)
        val head = StringBuilder(256)
        EventFormat.appendHeader(head, header)
        head.append('\n')
        put(w, head)
        out = w
        current = file
        bytes = Json.utf8Length(head).toLong()
        events = 0
        LogFiles.pruneCount(dir, LogFiles::isEvent, maxFiles, protect = file)
        return w
    }

    private fun put(w: Writer, sb: StringBuilder) {
        val n = sb.length
        if (chars.size < n) chars = CharArray(maxOf(n, chars.size * 2))
        sb.getChars(0, n, chars, 0)
        w.write(chars, 0, n)
    }

    private companion object {
        const val BUFFER_CHARS = 16 * 1024
    }
}
