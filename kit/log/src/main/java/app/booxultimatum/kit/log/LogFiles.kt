package app.booxultimatum.kit.log

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Names, listing, pruning and safe lookup of the files in the logs directory. */
internal object LogFiles {
    const val MAX_EXIT_FILES = 10
    const val MAX_EXIT_BYTES = 512 * 1024
    const val DAY_MS = 86_400_000L

    private val STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US).withZone(ZoneOffset.UTC)

    /** The UTC time used in file names. */
    fun stamp(wallMs: Long): String = STAMP.format(Instant.ofEpochMilli(wallMs))

    fun isEvent(name: String): Boolean = name.startsWith("log-") && name.endsWith(".jsonl")
    fun isCrash(name: String): Boolean = name.startsWith("crash-") && name.endsWith(".json")
    fun isExit(name: String): Boolean = name.startsWith("exit-") && (name.endsWith(".txt") || name.endsWith(".pb"))
    fun isLogFile(name: String): Boolean = isEvent(name) || isCrash(name) || isExit(name)

    /** A plain file name: no separators, no "..", no control characters. */
    fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 128 && !name.contains("..") &&
            name.none { it == '/' || it == '\\' || it < ' ' }

    /** Log, crash and exit files in [dir], newest first. */
    fun list(dir: File): List<File> = sortNewestFirst(listMatching(dir, ::isLogFile))

    fun newEventFile(dir: File, wallMs: Long, pid: Int): File = unique(dir, "log-${stamp(wallMs)}-$pid", ".jsonl")
    fun newCrashFile(dir: File, wallMs: Long): File = unique(dir, "crash-${stamp(wallMs)}", ".json")
    fun newExitFile(dir: File, wallMs: Long, reason: String, extension: String): File =
        unique(dir, "exit-${stamp(wallMs)}-$reason", extension)

    /** Keeps the newest [keep] files matching [match], [protect] always among them. Returns the deleted files. */
    fun pruneCount(dir: File, match: (String) -> Boolean, keep: Int, protect: File? = null): List<File> {
        val files = sortNewestFirst(listMatching(dir, match))
        val others = files.filter { it != protect }
        val room = (if (protect != null && files.size != others.size) keep - 1 else keep).coerceAtLeast(0)
        return others.drop(room).filter { it.delete() }
    }

    /** Deletes files matching [match] last modified more than [maxAgeMs] before [nowMs]. */
    fun pruneAge(dir: File, match: (String) -> Boolean, maxAgeMs: Long, nowMs: Long, protect: File? = null): List<File> {
        val cutoff = nowMs - maxAgeMs
        return listMatching(dir, match).filter { it != protect && it.lastModified() < cutoff && it.delete() }
    }

    /** The file [name] in [dir] if it's one [list] returns, else null. Rejects anything that could leave [dir]. */
    fun resolve(dir: File, name: String): File? {
        if (!isSafeName(name) || !isLogFile(name)) return null
        val file = list(dir).firstOrNull { it.name == name } ?: return null
        return try {
            file.takeIf { it.canonicalFile.parentFile == dir.canonicalFile }
        } catch (_: IOException) {
            null
        }
    }

    /** The file name in a provider path "files/<name>", or null for any other shape. */
    fun nameFromSegments(segments: List<String>): String? =
        if (segments.size == 2 && segments[0] == "files" && isSafeName(segments[1])) segments[1] else null

    /** Copies at most [max] bytes; returns the count copied. */
    fun copyCapped(input: InputStream, output: OutputStream, max: Int): Int {
        val buf = ByteArray(8192)
        var total = 0
        while (total < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            output.write(buf, 0, n)
            total += n
        }
        return total
    }

    private fun listMatching(dir: File, match: (String) -> Boolean): List<File> =
        dir.listFiles()?.filter { it.isFile && match(it.name) }.orEmpty()

    private fun sortNewestFirst(files: List<File>): List<File> =
        files.map { it to it.lastModified() }
            .sortedWith(compareByDescending<Pair<File, Long>> { it.second }.thenByDescending { it.first.name })
            .map { it.first }

    // A second file in the same second gets "_2", which sorts after the first by name.
    private fun unique(dir: File, base: String, extension: String): File {
        var file = File(dir, base + extension)
        var n = 2
        while (file.exists()) file = File(dir, "${base}_${n++}$extension")
        return file
    }
}
