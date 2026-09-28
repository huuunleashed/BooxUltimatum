package app.booxultimatum.kit.log

import android.content.Context
import androidx.core.content.edit
import java.io.File

/** How much room one kind of an app's files takes. [id] is one of [Housekeeping]'s area ids, or one an app registered. */
data class StorageUsage(val id: String, val bytes: Long, val files: Int, val cleanable: Boolean)

/**
 * Keeps each suite app's own leftovers bounded, and reports and clears them when the owner asks, so nothing fills the
 * tablet over time.
 *
 * By itself, at most every [TIDY_EVERY_MS] (from [Logbook.init], on the writer thread), it drops:
 * - cache files older than [CACHE_MAX_AGE_MS], then the oldest until the cache is under [CACHE_MAX_BYTES];
 * - downloaded update APKs older than a day;
 * - shared exports (log zips, shared pictures) older than a week, keeping the newest [EXPORTS_KEEP].
 *
 * The logbook keeps itself to [LogConfig.maxFiles] files of [LogConfig.maxFileBytes] and [LogConfig.maxAgeDays], with
 * the newest crash and exit reports. An app's own documents (Nib's drawings, the hub's battery log) are never cleared
 * here; apps register them with [registerData] so they're counted.
 */
object Housekeeping {
    const val LOGS = "logs"
    const val REPORTS = "reports"
    const val CACHE = "cache"
    const val DOWNLOADS = "downloads"
    const val EXPORTS = "exports"
    const val DATA = "data"

    /** What "Clear temporary files" clears. */
    val TEMPORARY = setOf(CACHE, DOWNLOADS, EXPORTS)

    const val TIDY_EVERY_MS = 12 * 3_600_000L
    const val CACHE_MAX_AGE_MS = 3 * 86_400_000L
    const val CACHE_MAX_BYTES = 64L * 1024 * 1024
    const val DOWNLOADS_MAX_AGE_MS = 86_400_000L
    const val EXPORTS_MAX_AGE_MS = 7 * 86_400_000L
    const val EXPORTS_KEEP = 5

    private const val PREFS = "kit.log"
    private const val KEY_TIDIED = "housekeeping_tidied"
    private val log = Logbook.logger("storage")
    private val data = LinkedHashMap<String, (Context) -> List<File>>()

    /** Counts an app's own documents under [id] (for example "drawings"); they're shown, never cleared. */
    fun registerData(id: String, dirs: (Context) -> List<File>) {
        synchronized(data) { data[id] = dirs }
    }

    fun usage(context: Context): List<StorageUsage> {
        val d = Dirs.of(context)
        val logs = Logbook.logsDir(context)
        val registered = synchronized(data) { data.toMap() }
        val out = ArrayList<StorageUsage>()
        out += measure(LOGS, true, listOf(logs)) { LogFiles.isEvent(it.name) }
        out += measure(REPORTS, true, listOf(logs)) { LogFiles.isCrash(it.name) || LogFiles.isExit(it.name) }
        out += measure(CACHE, true, d.caches) { d.isPlainCache(it) }
        out += measure(DOWNLOADS, true, listOf(d.downloads))
        out += measure(EXPORTS, true, d.exports)
        val counted = HashSet<String>()
        registered.forEach { (id, dirs) ->
            val list = runCatching { dirs(context) }.getOrDefault(emptyList())
            list.forEach { counted += it.absolutePath }
            out += measure(id, false, list)
        }
        // Everything else the app keeps: settings, databases, caches of its own. Shown so the total adds up.
        val known = (listOf(logs, d.downloads) + d.caches + d.exports).map { it.absolutePath } + counted
        out += measure(DATA, false, d.roots) { f -> known.none { f.absolutePath.startsWith(it) } }
        return out
    }

    /** Clears the given areas now and returns the bytes freed. Documents and [DATA] are never cleared. */
    fun clean(context: Context, ids: Set<String>): Long {
        val d = Dirs.of(context)
        val logs = Logbook.logsDir(context)
        var freed = 0L
        if (LOGS in ids) {
            Logbook.flush()
            // The newest event file is the one being written; it stays.
            val events = LogFiles.list(logs).filter { LogFiles.isEvent(it.name) }
            freed += delete(events.drop(1))
        }
        if (REPORTS in ids) freed += delete(LogFiles.list(logs).filter { LogFiles.isCrash(it.name) || LogFiles.isExit(it.name) })
        if (CACHE in ids) freed += d.caches.sumOf { dir -> delete(files(dir).filter { d.isPlainCache(it) }) }
        if (DOWNLOADS in ids) freed += delete(files(d.downloads))
        if (EXPORTS in ids) freed += d.exports.sumOf { delete(files(it)) }
        log.i("cleaned", "areas" to ids.sorted().joinToString(","), "bytes" to freed)
        return freed
    }

    /** The automatic pass; cheap when nothing is due. */
    fun tidyIfDue(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (nowMs - prefs.getLong(KEY_TIDIED, 0L) < TIDY_EVERY_MS) return
        val d = Dirs.of(context)
        var freed = 0L
        d.caches.forEach { freed += tidyCache(it, d::isPlainCache, nowMs) }
        freed += delete(files(d.downloads).filter { nowMs - it.lastModified() > DOWNLOADS_MAX_AGE_MS })
        d.exports.forEach { freed += tidyExports(it, nowMs) }
        prefs.edit { putLong(KEY_TIDIED, nowMs) }
        if (freed > 0) log.i("tidied", "bytes" to freed)
    }

    /** Drops cache files older than the limit, then the oldest until the cache fits. Files [include] rejects are left alone. */
    internal fun tidyCache(dir: File, include: (File) -> Boolean, nowMs: Long, maxAgeMs: Long = CACHE_MAX_AGE_MS, maxBytes: Long = CACHE_MAX_BYTES): Long {
        val all = files(dir).filter(include)
        var freed = delete(all.filter { nowMs - it.lastModified() > maxAgeMs })
        val left = all.filter { it.exists() }.sortedBy { it.lastModified() }
        var total = left.sumOf { it.length() }
        for (f in left) {
            if (total <= maxBytes) break
            val n = f.length()
            if (f.delete()) { freed += n; total -= n }
        }
        return freed
    }

    /** Drops exports older than a week, and all but the newest few. */
    internal fun tidyExports(dir: File, nowMs: Long, maxAgeMs: Long = EXPORTS_MAX_AGE_MS, keep: Int = EXPORTS_KEEP): Long {
        val newestFirst = files(dir).sortedByDescending { it.lastModified() }
        return delete(newestFirst.filterIndexed { i, f -> i >= keep || nowMs - f.lastModified() > maxAgeMs })
    }

    internal fun measure(id: String, cleanable: Boolean, dirs: List<File>, include: (File) -> Boolean = { true }): StorageUsage {
        val list = dirs.distinctBy { it.absolutePath }.flatMap { files(it) }.filter(include)
        return StorageUsage(id, list.sumOf { it.length() }, list.size, cleanable)
    }

    internal fun files(dir: File?): List<File> =
        if (dir == null || !dir.exists()) emptyList() else dir.walkTopDown().filter { it.isFile }.toList()

    private fun delete(files: List<File>): Long = files.sumOf { f -> val n = f.length(); if (f.delete()) n else 0L }

    private fun File.startsWith(other: File) = absolutePath.startsWith(other.absolutePath + File.separator)

    /** The places a suite app writes to. */
    private class Dirs(val roots: List<File>, val caches: List<File>, val downloads: File, val exports: List<File>) {
        /** A cache file that isn't a download or a shared export, which have their own areas. */
        fun isPlainCache(f: File): Boolean = !f.startsWith(downloads) && exports.none { f.startsWith(it) }

        companion object {
            fun of(c: Context): Dirs {
                val ext = c.getExternalFilesDir(null)
                return Dirs(
                    roots = listOfNotNull(c.filesDir, c.noBackupFilesDir, c.cacheDir, ext, c.externalCacheDir, File(c.applicationInfo.dataDir, "shared_prefs"), File(c.applicationInfo.dataDir, "databases")),
                    caches = listOfNotNull(c.cacheDir, c.externalCacheDir),
                    downloads = File(c.cacheDir, "updates"),
                    // Where suite apps put what they hand to other apps: exported pictures, log zips, pen recordings.
                    exports = listOfNotNull(ext?.let { File(it, "exports") }) + listOf("exports", "logs", "recordings", "shared").map { File(c.cacheDir, it) },
                )
            }
        }
    }
}
