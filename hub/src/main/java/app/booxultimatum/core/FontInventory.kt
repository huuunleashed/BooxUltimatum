package app.booxultimatum.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

/** Where one of the app's font files sits. Each place has its own off folder, under the app's fonts folder's `off/`. */
enum class FontPlace {
    /** The app's own folder (Android/data/…/files/fonts), which the home screen, this app and the sleep screen read. */
    App,

    /** The Boox fonts folder, /sdcard/fonts, which NeoReader and Boox's font settings list. Written through Shizuku. */
    Reader,

    /** Documents/BooxUltimatum, where the tablet font goes without Shizuku: MediaStore rows the app owns. */
    Documents,
}

enum class FontUse { Tablet, Reader, Home, App, Sleep }

enum class FontState { On, Off, Partial }

enum class InstalledSort { Name, Size, Recent }

enum class InstalledFilter { All, InUse, Unused, Off }

data class FontStyleKey(val weight: Int, val italic: Boolean) : Comparable<FontStyleKey> {
    override fun compareTo(other: FontStyleKey) = compareValuesBy(this, other, { it.weight }, { it.italic })
}

/** One font file the scan found. [off] files sit in the off folder of their [place]. */
data class FontFileEntry(
    val place: FontPlace,
    val off: Boolean,
    val name: String,
    val path: String,
    val size: Long,
    val modified: Long,
    val sha256: String? = null,
    /** The MediaStore row of a Documents copy. */
    val id: Long? = null,
) {
    val base: String? get() = FontNames.baseOf(name)
    val style: FontStyleKey? get() = FontNames.styleOf(name)
}

/**
 * What the app remembers about a family beyond its files, which are always read from disk.
 * [reader] lists the files it copied into the Boox fonts folder: the only ones there it may move or delete.
 * [released] is set while the family is off: each use it had, keyed by [FontUse] name, to the file name it used.
 */
data class FontRecord(
    val base: String,
    val family: String,
    val installedAt: Long,
    val reader: Set<String> = emptySet(),
    val released: Map<String, String> = emptyMap(),
) {
    companion object {
        /** The tablet font path left in place when the family was turned off, to tell whether it's still ours to change. */
        const val TABLET_AFTER = "tabletAfter"

        /** Stored under [FontUse.Home] when the home screen followed this app's font. */
        const val HOME_VIA_APP = "@app"
    }
}

data class FontScan(
    val files: List<FontFileEntry>,
    /** False when the Boox fonts folder couldn't be read (no Shizuku): its files are then unknown, not gone. */
    val readerChecked: Boolean,
    /** False when MediaStore couldn't be asked about Documents/BooxUltimatum. */
    val documentsChecked: Boolean = true,
)

/** The font settings that can point at an installed file. Paths as stored. */
data class FontSettings(
    val tabletPath: String? = null,
    val appPath: String? = null,
    /** The home screen's own file, when its font source is a custom file. */
    val homePath: String? = null,
    /** The home screen follows this app's font. */
    val homeFollowsApp: Boolean = false,
    /** The sleep screen's file, when its font is a file. */
    val sleepPath: String? = null,
)

/** A family this app installed, with every file of it on the tablet. */
data class InstalledFont(val record: FontRecord, val files: List<FontFileEntry>, val readerChecked: Boolean) {
    val base: String get() = record.base
    val family: String get() = record.family
    val size: Long get() = files.sumOf { it.size }
    val onFiles: List<FontFileEntry> get() = files.filter { !it.off }
    val offFiles: List<FontFileEntry> get() = files.filter { it.off }

    /** Names in the Boox fonts folder: those seen there, or those the record lists when the folder couldn't be read. */
    val readerNames: Set<String>
        get() = if (readerChecked) {
            files.filter { it.place == FontPlace.Reader && !it.off }.map { it.name }.toSet()
        } else {
            record.reader - files.filter { it.place == FontPlace.Reader && it.off }.map { it.name }.toSet()
        }

    val state: FontState
        get() {
            val on = onFiles.size + if (readerChecked) 0 else readerNames.size
            return when {
                offFiles.isEmpty() -> FontState.On
                on == 0 -> FontState.Off
                else -> FontState.Partial
            }
        }

    val styles: List<FontStyleKey> get() = files.mapNotNull { it.style }.distinct().sorted()

    /** Upright weights the app's own folder holds, which the tablet, home screen, app and sleep screen can use. */
    val uprightWeights: List<Int>
        get() = onFiles.filter { it.place == FontPlace.App }.mapNotNull { it.style }.filter { !it.italic }.map { it.weight }.distinct().sorted()

    /** The app's own copy of one upright weight. */
    fun appFile(weight: Int): FontFileEntry? =
        onFiles.firstOrNull { it.place == FontPlace.App && it.style == FontStyleKey(weight, false) }

    /** The upright file closest to regular in the app's folder: what the home screen, this app and the sleep screen use. */
    val regular: FontFileEntry? get() = nearestUpright(onFiles.filter { it.place == FontPlace.App })

    /** A file to set the family's name in: the regular style wherever it is, on or off. */
    val preview: FontFileEntry?
        get() = nearestUpright(files.filter { it.place == FontPlace.App }) ?: nearestUpright(files) ?: files.firstOrNull()

    val placesOn: Set<FontPlace>
        get() = onFiles.map { it.place }.toSet() + if (!readerChecked && readerNames.isNotEmpty()) setOf(FontPlace.Reader) else emptySet()

    private fun nearestUpright(list: List<FontFileEntry>): FontFileEntry? =
        list.filter { it.style?.italic == false }.minByOrNull { f ->
            val w = f.style?.weight ?: 400
            kotlin.math.abs(w - 400) * 2 - if (w > 400) 1 else 0
        }
}

/** The result of matching the records against what's on disk. */
data class FontReconciled(
    val fonts: List<InstalledFont>,
    /** Files in the Boox fonts folder this app didn't put there: listed, never touched. */
    val foreign: List<FontFileEntry>,
    val records: List<FontRecord>,
    /** Families found on disk that had no record. */
    val adopted: List<FontRecord>,
    /** Files in the Boox fonts folder claimed because they match the app's own copies byte for byte. */
    val adoptedReader: Map<String, Set<String>>,
    /** Records whose files are all gone. */
    val dropped: List<FontRecord>,
    val changed: Boolean,
)

/** Everything the Fonts page shows about installed fonts, with each family's uses worked out once. */
data class FontLibrary(
    val fonts: List<InstalledFont>,
    val foreign: List<FontFileEntry>,
    val readerChecked: Boolean,
    val settings: FontSettings,
) {
    val uses: Map<String, Set<FontUse>> = fonts.associate { it.base to FontInventory.uses(it, settings) }
    val size: Long get() = fonts.sumOf { it.size }
    val unused: List<InstalledFont> get() = FontInventory.unused(fonts, settings)

    fun font(base: String) = fonts.firstOrNull { it.base == base }

    fun usesOf(base: String) = uses[base].orEmpty()
}

/** What one change did, for the page to report. */
data class FontChange(
    val family: String,
    val files: Int = 0,
    val bytes: Long = 0,
    val released: Set<FontUse> = emptySet(),
    val restored: Set<FontUse> = emptySet(),
    /** This app's own font changed, so its screen must be recreated to show it. */
    val restart: Boolean = false,
    /** The tablet font's path afterwards, when it changed; empty for the Boox default. */
    val tabletPath: String? = null,
)

data class BulkChange(val done: List<FontChange>, val failed: List<Pair<String, Throwable>>) {
    val restart: Boolean get() = done.any { it.restart }
    val bytes: Long get() = done.sumOf { it.bytes }
}

/** File names as Fonts writes them: `<Base>-<Style>.ttf`, the base being the family name without spaces or punctuation. */
object FontNames {
    private val WEIGHTS = mapOf(
        "Thin" to 100, "ExtraLight" to 200, "Light" to 300, "Regular" to 400, "Medium" to 500,
        "SemiBold" to 600, "Bold" to 700, "ExtraBold" to 800, "Black" to 900,
    )
    private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")

    /** The family part of a name the app wrote, or null for anything else. */
    fun baseOf(name: String): String? {
        if (!name.endsWith(".ttf", ignoreCase = true)) return null
        val base = name.substringBefore('-', name.substringBeforeLast('.'))
        return base.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() } }
    }

    fun styleOf(name: String): FontStyleKey? {
        if (!name.contains('-')) return null
        val style = name.substringAfter('-').substringBeforeLast('.')
        if (style == "Italic") return FontStyleKey(400, true)
        val italic = style.endsWith("Italic")
        val w = style.removeSuffix("Italic")
        val weight = WEIGHTS[w] ?: w.removePrefix("W").toIntOrNull()?.takeIf { w.startsWith("W") } ?: return null
        return FontStyleKey(weight, italic)
    }

    fun isFont(name: String) = name.substringAfterLast('.', "").lowercase() in FONT_EXTENSIONS

    /** A readable family name for a base with no record: "EBGaramond" → "EB Garamond", "SourceSerif4" → "Source Serif 4". */
    fun display(base: String): String =
        base.replace(Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|(?<=[A-Za-z])(?=[0-9])"), " ")

    private const val SHARED = "/storage/emulated/0/"

    /**
     * One spelling for shared-storage paths, so a font path from any source compares equal to the scan's. The
     * aliases are the point here: Boox's own apps, adb and the firmware all hand out /sdcard or /mnt/sdcard.
     */
    @Suppress("SdCardPath")
    fun normalise(path: String): String = when {
        path.startsWith("/sdcard/") -> SHARED + path.removePrefix("/sdcard/")
        path.startsWith("/storage/self/primary/") -> SHARED + path.removePrefix("/storage/self/primary/")
        path.startsWith("/mnt/sdcard/") -> SHARED + path.removePrefix("/mnt/sdcard/")
        else -> path
    }

    const val READER_DIR = "/storage/emulated/0/fonts"
    const val DOCUMENTS_DIR = "/storage/emulated/0/Documents/BooxUltimatum"

    /** Parses one `stat -c '%s|%Y|%n'` line: size, modification time in ms, path. */
    fun parseStat(line: String): Triple<Long, Long, String>? {
        val parts = line.trim().split('|', limit = 3)
        if (parts.size < 3 || parts[2].isEmpty()) return null
        val size = parts[0].toLongOrNull() ?: return null
        val time = parts[1].toLongOrNull() ?: return null
        return Triple(size, time * 1000, parts[2])
    }

    /** Parses `sha256sum` output into file name to hash. */
    fun parseSha256(out: String): Map<String, String> = out.lineSequence().mapNotNull { line ->
        val hash = line.substringBefore(' ').trim()
        val name = line.substringAfter(' ', "").trim().substringAfterLast('/')
        if (hash.length == 64 && name.isNotEmpty()) name to hash.lowercase() else null
    }.toMap()

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

object FontInventory {
    /** Unrecorded files in the Boox fonts folder that share a name and size with one of the app's own copies: hash them to decide. */
    fun hashCandidates(records: List<FontRecord>, files: List<FontFileEntry>): Set<String> {
        val recorded = records.flatMap { it.reader }.toSet()
        val own = files.filter { it.place == FontPlace.App }.associateBy { it.name }
        return files.filter { it.place == FontPlace.Reader && !it.off && it.name !in recorded && it.base != null }
            .filter { r -> own[r.name]?.size == r.size }
            .map { it.name }
            .toSet()
    }

    /**
     * Rebuilds the inventory from [scan]. Files in the app's folders and its own MediaStore rows are the app's by
     * construction; a file in the Boox fonts folder is the app's only when a record lists it or its bytes match one
     * of the app's copies. Families on disk without a record are adopted, and records with nothing left are dropped,
     * unless their files may sit in a place the scan couldn't read.
     */
    fun reconcile(records: List<FontRecord>, scan: FontScan, now: Long, nameOf: (String) -> String? = { null }): FontReconciled {
        val byBase = records.filter { it.base.isNotEmpty() }.associateBy { it.base }
        val own = scan.files.filter { (it.place != FontPlace.Reader || it.off) && it.base != null }
        val appCopies = own.filter { it.place == FontPlace.App }.groupBy { it.name }
        val claimed = mutableListOf<FontFileEntry>()
        val foreign = mutableListOf<FontFileEntry>()
        val adoptedReader = mutableMapOf<String, MutableSet<String>>()
        for (r in scan.files.filter { it.place == FontPlace.Reader && !it.off }) {
            val base = r.base
            val listed = base != null && byBase[base]?.reader?.contains(r.name) == true
            val matches = base != null && r.sha256 != null && appCopies[r.name].orEmpty().any { it.size == r.size && it.sha256 == r.sha256 }
            when {
                listed -> claimed += r
                matches -> { claimed += r; adoptedReader.getOrPut(base!!) { mutableSetOf() } += r.name }
                else -> foreign += r
            }
        }
        val grouped = (own + claimed).groupBy { it.base!! }
        val adopted = mutableListOf<FontRecord>()
        val dropped = mutableListOf<FontRecord>()
        val kept = mutableListOf<FontRecord>()
        val fonts = mutableListOf<InstalledFont>()
        var changed = false
        for (base in (grouped.keys + byBase.keys).distinct()) {
            val files = grouped[base].orEmpty()
            val old = byBase[base]
            val offReader = files.filter { it.place == FontPlace.Reader && it.off }.map { it.name }.toSet()
            if (files.isEmpty()) {
                val unseenReader = !scan.readerChecked && old!!.reader.isNotEmpty()
                when {
                    unseenReader -> Unit
                    !scan.documentsChecked -> { kept += old!!; continue }
                    else -> { dropped += old!!; changed = true; continue }
                }
            }
            val reader = if (scan.readerChecked) files.filter { it.place == FontPlace.Reader && !it.off }.map { it.name }.toSet() + offReader
            else old?.reader.orEmpty() + offReader
            val better = nameOf(base)
            val record = when {
                old == null -> FontRecord(base, better ?: FontNames.display(base), files.minOf { it.modified }.takeIf { it > 0 } ?: now, reader).also { adopted += it }
                else -> old.copy(
                    reader = reader,
                    family = if (better != null && old.family == FontNames.display(base)) better else old.family,
                    released = if (files.none { it.off }) emptyMap() else old.released,
                )
            }
            if (record != old) changed = true
            kept += record
            fonts += InstalledFont(record, files.sortedWith(compareBy({ it.place }, { it.off }, { it.style }, { it.name })), scan.readerChecked)
        }
        return FontReconciled(
            fonts.sortedBy { it.family.lowercase() }, foreign.sortedBy { it.name.lowercase() }, kept.sortedBy { it.base },
            adopted, adoptedReader, dropped, changed,
        )
    }

    fun uses(font: InstalledFont, s: FontSettings): Set<FontUse> {
        val on = font.onFiles.map { FontNames.normalise(it.path) }.toSet()
        fun owns(path: String?) = path != null && FontNames.normalise(path) in on
        val out = mutableSetOf<FontUse>()
        s.tabletPath?.let { FontNames.normalise(it) }?.let { t ->
            val inReader = t.substringBeforeLast('/') == FontNames.READER_DIR && t.substringAfterLast('/') in font.readerNames
            if (t in on || inReader) out += FontUse.Tablet
        }
        if (font.readerNames.isNotEmpty()) out += FontUse.Reader
        if (owns(s.appPath)) out += FontUse.App
        if (owns(s.homePath) || (s.homeFollowsApp && owns(s.appPath))) out += FontUse.Home
        if (owns(s.sleepPath)) out += FontUse.Sleep
        return out
    }

    /** Families that are on and used nowhere, not even listed in the Boox fonts folder. Off families are left alone. */
    fun unused(fonts: List<InstalledFont>, s: FontSettings): List<InstalledFont> =
        fonts.filter { it.state == FontState.On && uses(it, s).isEmpty() }

    fun arrange(fonts: List<InstalledFont>, uses: Map<String, Set<FontUse>>, sort: InstalledSort, filter: InstalledFilter): List<InstalledFont> =
        fonts.filter {
            when (filter) {
                InstalledFilter.All -> true
                InstalledFilter.InUse -> uses[it.base].orEmpty().isNotEmpty()
                InstalledFilter.Unused -> it.state == FontState.On && uses[it.base].orEmpty().isEmpty()
                InstalledFilter.Off -> it.state != FontState.On
            }
        }.sortedWith(
            when (sort) {
                InstalledSort.Name -> compareBy { it.family.lowercase() }
                InstalledSort.Size -> compareByDescending<InstalledFont> { it.size }.thenBy { it.family.lowercase() }
                InstalledSort.Recent -> compareByDescending<InstalledFont> { it.record.installedAt }.thenBy { it.family.lowercase() }
            },
        )
}

/** The records as JSON in the app's preferences. */
object FontRecords {
    fun toJson(records: List<FontRecord>): String = JSONArray().apply {
        records.forEach { r ->
            put(
                JSONObject().put("base", r.base).put("family", r.family).put("at", r.installedAt)
                    .put("reader", JSONArray(r.reader.sorted()))
                    .put("released", JSONObject().apply { r.released.forEach { (k, v) -> put(k, v) } }),
            )
        }
    }.toString()

    fun fromJson(raw: String?): List<FontRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val base = o.optString("base").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val reader = o.optJSONArray("reader")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            val released = o.optJSONObject("released")?.let { r -> r.keys().asSequence().associateWith { r.getString(it) } }.orEmpty()
            FontRecord(base, o.optString("family").ifEmpty { FontNames.display(base) }, o.optLong("at"), reader, released)
        }
    }
}

/**
 * The app's own font files: [app] holds the ones in use, and [offRoot] the turned-off ones, one folder per
 * [FontPlace] they came from. [offRoot] may sit inside [app]: only files at the top level of [app] count as on.
 * Plain file work, so it runs the same against a temporary folder in tests.
 */
class FontFolders(val app: File, val offRoot: File) {
    fun offDir(place: FontPlace) = File(offRoot, place.name.lowercase())

    /** Deletes the `.part` files an interrupted move or stash left behind. Returns how many. */
    fun sweep(): Int = (listOf(app) + FontPlace.entries.map { offDir(it) }).sumOf { dir ->
        dir.listFiles { f -> f.isFile && f.name.endsWith(".part") }.orEmpty().count { it.delete() }
    }

    fun scan(): List<FontFileEntry> {
        val out = mutableListOf<FontFileEntry>()
        fun add(dir: File, place: FontPlace, off: Boolean) {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".ttf", ignoreCase = true) && f.length() > 0 }.orEmpty().forEach { f ->
                out += FontFileEntry(place, off, f.name, f.absolutePath, f.length(), f.lastModified())
            }
        }
        add(app, FontPlace.App, false)
        FontPlace.entries.forEach { add(offDir(it), it, true) }
        return out
    }

    fun files(dir: File, base: String): List<File> =
        dir.listFiles { f -> f.isFile && FontNames.baseOf(f.name) == base }.orEmpty().sortedBy { it.name }

    fun hasOff(base: String) = FontPlace.entries.any { files(offDir(it), base).isNotEmpty() }

    /** Moves the family's files out of the app folder into its off folder. Returns the moved files at their new place. */
    fun turnOff(base: String): List<File> = files(app, base).map { move(it, offDir(FontPlace.App)) }

    /**
     * Moves the family's off copies back into the app folder. A copy already back in place (a finished earlier
     * attempt) wins, and the off copy is dropped.
     */
    fun turnOn(base: String): List<File> = files(offDir(FontPlace.App), base).map { f ->
        val there = File(app, f.name)
        if (there.length() > 0) { f.delete(); there } else move(f, app)
    }

    /** Writes an off copy of a file from shared storage. */
    fun stash(place: FontPlace, name: String, write: (OutputStream) -> Unit): File {
        val dir = offDir(place).apply { mkdirs() }
        val tmp = File(dir, "$name.part")
        tmp.outputStream().use(write)
        val out = File(dir, name)
        check(tmp.length() > 0) { "Empty copy of $name" }
        check(tmp.renameTo(out) || (out.delete() && tmp.renameTo(out))) { "Couldn’t save $name" }
        return out
    }

    /** Deletes the family's files here, on and off. Returns the bytes freed. */
    fun delete(base: String): Long {
        var freed = 0L
        (listOf(app) + FontPlace.entries.map { offDir(it) }).forEach { dir ->
            files(dir, base).forEach { f -> val n = f.length(); if (f.delete()) freed += n }
        }
        return freed
    }

    companion object {
        /** Renames when it can, otherwise copies, checks the length, and deletes the source. */
        fun move(src: File, dstDir: File): File {
            dstDir.mkdirs()
            val dst = File(dstDir, src.name)
            if (dst.exists()) dst.delete()
            if (src.renameTo(dst)) return dst
            val tmp = File(dstDir, src.name + ".part")
            src.copyTo(tmp, overwrite = true)
            check(tmp.length() == src.length()) { "Incomplete copy of ${src.name}" }
            check(tmp.renameTo(dst)) { "Couldn’t move ${src.name}" }
            check(src.delete()) { "Couldn’t remove ${src.name} after copying it" }
            return dst
        }
    }
}
