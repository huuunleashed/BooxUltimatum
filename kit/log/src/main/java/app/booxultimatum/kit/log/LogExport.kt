package app.booxultimatum.kit.log

import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The export archive: an app's files under "<package>/", any extra entries, and a README. */
internal object LogExport {
    const val README = "README.txt"

    /** Writes the zip to [out] and leaves [out] open. Files that vanish meanwhile are skipped. */
    fun write(out: OutputStream, prefix: String, files: List<File>, extra: Map<String, ByteArray>, readme: String) {
        val names = HashSet<String>()
        ZipOutputStream(KeepOpen(out)).use { zip ->
            for (file in files) {
                val name = "$prefix/${file.name}"
                if (!names.add(name)) continue
                val input = try {
                    file.inputStream()
                } catch (_: IOException) {
                    continue
                }
                input.use {
                    zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                    it.copyTo(zip)
                    zip.closeEntry()
                }
            }
            for ((name, bytes) in extra) {
                if (!isSafeEntry(name) || name == README || !names.add(name)) continue
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry(README))
            zip.write(readme.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    fun isSafeEntry(name: String): Boolean =
        name.isNotBlank() && !name.startsWith("/") && !name.contains("..") && !name.contains('\\')

    fun readme(config: LogConfig): String = """
BooxUltimatum diagnostic logs

A BooxUltimatum app made this archive on the tablet when you asked it to export its logs. Each app's files are in a folder named after its package: app.booxultimatum is the hub, app.booxultimatum.nib is Nib. Dates and times in file names are UTC.

log-<date>-<time>-<process>.jsonl: the events of one run of the app, one JSON object per line. The first line describes the run: the app and its version, whether it's a debug build, the tablet model, the Android version, a random session id that changes at every launch, the process id and the start time. Every other line is one event: "t" is the time in milliseconds since 1970, "m" a monotonic clock in nanoseconds for measuring durations, "l" the level (V verbose, D debug, I info, W warning, E error), "c" the part of the app that wrote it, "msg" what happened, "f" named values such as counts, sizes and durations, "th" the thread, "p" the process id, "s" the session id and, when something failed, "err" the stack trace. A new file starts when one passes ${config.maxFileBytes / 1000} KB. The newest ${config.maxFiles} files are kept, and files older than ${config.maxAgeDays} days are deleted.

crash-<date>-<time>.json: written when the app crashed. It holds the time, the thread, the session id, the full stack trace and the last events logged before the crash. The newest ${config.keepCrashes} are kept.

exit-<date>-<time>-<reason>.txt or .pb: what Android recorded when the app stopped responding (anr: a text dump of its threads) or crashed in native code (crash_native: a tombstone in Android's protobuf format). The newest ${LogFiles.MAX_EXIT_FILES} are kept.

Other entries beside these folders were added by the app that made the archive.

What isn't in these files: the apps don't log what you draw or write, text you type, or account and network names. Where the name of a file or of another app matters, they log a short one-way hash or the word "other" instead.

Nothing in this archive leaves the tablet unless you share it yourself.
""".trimStart()

    /** Lets ZipOutputStream close (freeing its deflater) without closing the caller's stream. */
    private class KeepOpen(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
        }

        override fun close() {
            out.flush()
        }
    }
}
