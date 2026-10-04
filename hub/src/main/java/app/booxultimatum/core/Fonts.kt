package app.booxultimatum.core

import android.content.Context
import android.util.JsonReader
import androidx.core.content.edit
import app.booxultimatum.R
import app.booxultimatum.core.exec.Privileged
import app.booxultimatum.core.exec.ShellResult
import app.booxultimatum.core.sleep.SleepFont
import app.booxultimatum.core.sleep.SleepScheduler
import app.booxultimatum.core.sleep.SleepStore
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.launcher.FontSource
import app.booxultimatum.launcher.LauncherStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class FontCategory(val metadataName: String) {
    Serif("Serif"), Sans("Sans Serif"), Display("Display"), Handwriting("Handwriting"), Mono("Monospace");

    companion object {
        fun of(name: String) = entries.firstOrNull { it.metadataName == name } ?: Display
    }
}

/** One Google Fonts family. [variants] use the metadata keys: "400", "400i", "700", ... */
data class FontFamilyInfo(val name: String, val category: FontCategory, val popularity: Int, val vietnamese: Boolean, val variants: List<String>) {
    val styles get() = variants.size
    fun has(weight: Int, italic: Boolean) = variants.contains(if (italic) "${weight}i" else "$weight")
    val regularKey: String get() = variants.firstOrNull { it == "400" } ?: variants.firstOrNull { !it.endsWith("i") } ?: variants.first()
}

data class FontStyleFile(val weight: Int, val italic: Boolean, val file: File)

/**
 * The whole Google Fonts library without an API key:
 *  - the catalogue from fonts.google.com/metadata/fonts, parsed as a stream and kept as a compact file;
 *  - font files from the CSS2 API, which hands TrueType URLs to a plain user agent.
 * Files are fetched only for what is on screen, cached, and trimmed when the cache grows.
 *
 * And the library of what the app installed: every family kept in its folder, copied into the Boox fonts folder or
 * published to Documents for the tablet font, rebuilt from disk on every read ([FontInventory]), with its uses,
 * and turned off, on or deleted with every use handed back first.
 */
object Fonts {
    /** What the firmware's own font broadcast reads (verified on the tablet): the spelling Boox uses, not a resolved path. */
    @Suppress("SdCardPath")
    const val TARGET_DIR = "/sdcard/fonts"
    private const val METADATA = "https://fonts.google.com/metadata/fonts"
    private const val CATALOG_TTL = 7L * 24 * 3600 * 1000
    private const val CACHE_LIMIT = 60L * 1024 * 1024
    private val SAFE_FILE = Regex("^[A-Za-z0-9_\\-.]+\\.ttf$")
    private val downloads = Semaphore(3)

    /** Families that read well on e-ink; shown as the "E-ink picks" filter. */
    val einkPicks = setOf(
        "Literata", "EB Garamond", "Merriweather", "Source Serif 4", "Crimson Pro", "Libre Baskerville", "Noto Serif", "Newsreader",
        "Spectral", "Alegreya", "PT Serif", "Gelasio", "Lora", "Charis SIL", "Bitter", "Inter", "Atkinson Hyperlegible Next",
        "Noto Sans", "Source Sans 3", "Lexend", "Be Vietnam Pro", "Open Sans", "IBM Plex Sans", "Work Sans", "Figtree", "Public Sans",
    )

    private fun catalogFile(c: Context) = File(c.filesDir, "gf_catalog.tsv")
    fun cacheDir(c: Context) = File(c.cacheDir, "gfonts").apply { mkdirs() }
    /** Kept outside the cache so the launcher and app can keep using an applied font after cache trims. */
    fun keptDir(c: Context) = File(c.getExternalFilesDir(null), "fonts").apply { mkdirs() }

    // ---------- Catalogue ----------

    suspend fun catalog(context: Context, forceRefresh: Boolean = false): Result<List<FontFamilyInfo>> = withContext(Dispatchers.IO) {
        val f = catalogFile(context)
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < CATALOG_TTL
        if (fresh && !forceRefresh) return@withContext runCatching { readCatalog(f) }
        val downloaded = runCatching { downloadCatalog(f) }
        if (downloaded.isSuccess) runCatching { readCatalog(f) }
        else if (f.exists()) runCatching { readCatalog(f) } // offline: keep the last catalogue
        else Result.failure(downloaded.exceptionOrNull()!!)
    }

    private fun readCatalog(f: File): List<FontFamilyInfo> = f.readLines().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 5) null else FontFamilyInfo(p[0], FontCategory.of(p[1]), p[2].toIntOrNull() ?: 9999, p[3] == "1", p[4].split(',').filter { it.isNotBlank() })
    }

    private fun downloadCatalog(out: File) {
        val c = open(METADATA)
        try {
            check(c.responseCode == 200) { "Google Fonts answered ${c.responseCode}" }
            val input = c.inputStream.buffered()
            // The response starts with the anti-JSON-hijacking prefix )]}'
            input.mark(8)
            val head = ByteArray(4)
            val n = input.read(head)
            if (!(n == 4 && String(head) == ")]}'")) input.reset()
            val lines = StringBuilder()
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() != "familyMetadataList") { r.skipValue(); continue }
                    r.beginArray()
                    while (r.hasNext()) readFamily(r)?.let { fam ->
                        lines.append(fam.name).append('\t').append(fam.category.metadataName).append('\t').append(fam.popularity).append('\t')
                            .append(if (fam.vietnamese) "1" else "0").append('\t').append(fam.variants.joinToString(",")).append('\n')
                    }
                    r.endArray()
                }
                r.endObject()
            }
            val tmp = File(out.parentFile, out.name + ".part")
            tmp.writeText(lines.toString())
            check(tmp.renameTo(out) || (out.delete() && tmp.renameTo(out))) { "Could not save the catalogue" }
        } finally {
            c.disconnect()
        }
    }

    private fun readFamily(r: JsonReader): FontFamilyInfo? {
        var name: String? = null
        var category = "Display"
        var popularity = 9999
        var vi = false
        val variants = mutableListOf<String>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "family" -> name = r.nextString()
                "category" -> category = r.nextString()
                "popularity" -> popularity = r.nextInt()
                "subsets" -> { r.beginArray(); while (r.hasNext()) if (r.nextString() == "vietnamese") vi = true; r.endArray() }
                "fonts" -> { r.beginObject(); while (r.hasNext()) { variants += r.nextName(); r.skipValue() }; r.endObject() }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return name?.let { FontFamilyInfo(it, FontCategory.of(category), popularity, vi, variants.sortedWith(compareBy({ it.removeSuffix("i").toIntOrNull() ?: 400 }, { it.endsWith("i") }))) }
    }

    // ---------- Files ----------

    private fun styleName(weight: Int, italic: Boolean): String {
        val w = when (weight) { 100 -> "Thin"; 200 -> "ExtraLight"; 300 -> "Light"; 400 -> "Regular"; 500 -> "Medium"; 600 -> "SemiBold"; 700 -> "Bold"; 800 -> "ExtraBold"; 900 -> "Black"; else -> "W$weight" }
        return if (italic) (if (weight == 400) "Italic" else "${w}Italic") else w
    }

    fun fileBase(family: String) = family.replace(Regex("[^A-Za-z0-9]"), "")

    fun fileName(family: String, weight: Int, italic: Boolean) = "${fileBase(family)}-${styleName(weight, italic)}.ttf"

    /** Returns a local TTF for one style, downloading it if needed. */
    suspend fun style(context: Context, fam: FontFamilyInfo, weight: Int, italic: Boolean): Result<File> = withContext(Dispatchers.IO) {
        val name = fileName(fam.name, weight, italic)
        File(keptDir(context), name).takeIf { it.length() > 0 }?.let { return@withContext Result.success(it) }
        val cached = File(cacheDir(context), name)
        if (cached.length() > 0) { cached.setLastModified(System.currentTimeMillis()); return@withContext Result.success(cached) }
        downloads.withPermit {
            runCatching {
                val axis = if (italic) "ital,wght@1,$weight" else "wght@$weight"
                val css = get("https://fonts.googleapis.com/css2?family=" + URLEncoder.encode(fam.name, "UTF-8").replace("+", "%20") + ":" + axis)
                val url = Regex("""url\((https://fonts\.gstatic\.com/[^)]+\.ttf)\)""").find(css)?.groupValues?.get(1) ?: error("No TrueType file for ${fam.name}")
                download(url, cached)
                trimCache(context)
                cached
            }
        }
    }

    suspend fun regular(context: Context, fam: FontFamilyInfo): Result<File> {
        val key = fam.regularKey
        return style(context, fam, key.removeSuffix("i").toIntOrNull() ?: 400, key.endsWith("i"))
    }

    /** The four styles a reading app needs (or as many as the family has), copied out of the cache so they persist. */
    suspend fun keep(context: Context, fam: FontFamilyInfo): Result<List<FontStyleFile>> = withContext(Dispatchers.IO) {
        runCatching {
            check(!folders(context).hasOff(fileBase(fam.name))) { context.getString(R.string.fonts_is_off, fam.name) }
            val wanted = listOf(400 to false, 400 to true, 700 to false, 700 to true).filter { (w, i) -> fam.has(w, i) }
                .ifEmpty { listOf(fam.regularKey.removeSuffix("i").toIntOrNull()!! to fam.regularKey.endsWith("i")) }
            val written = mutableListOf<File>()
            wanted.map { (w, i) -> FontStyleFile(w, i, keepOne(context, fam, w, i, written)) }.also { noteInstalled(context, fam.name, written) }
        }
    }

    /** One style kept in the app's folder, such as the heavier cut chosen for the tablet font. */
    suspend fun keepStyle(context: Context, fam: FontFamilyInfo, weight: Int, italic: Boolean): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            check(!folders(context).hasOff(fileBase(fam.name))) { context.getString(R.string.fonts_is_off, fam.name) }
            val written = mutableListOf<File>()
            keepOne(context, fam, weight, italic, written).also { noteInstalled(context, fam.name, written) }
        }
    }

    private suspend fun keepOne(context: Context, fam: FontFamilyInfo, weight: Int, italic: Boolean, written: MutableList<File>): File {
        val src = style(context, fam, weight, italic).getOrThrow()
        val dst = File(keptDir(context), fileName(fam.name, weight, italic))
        val fresh = dst.length() == 0L
        if (src.absolutePath != dst.absolutePath) src.copyTo(dst, overwrite = true)
        if (fresh) written += dst
        return dst
    }

    // ---------- The library: what the app installed, where it is, and what uses it ----------

    private val log = Logbook.logger("fonts")
    private val lock = Mutex()
    private val recordsLock = Any()
    private const val RECORDS = "inventory"
    private const val END = "__BU_END__"

    /**
     * Turned-off files, one folder per place they came from. It sits inside [keptDir] so the storage report counts it
     * with the fonts; every font picker lists only the top level of [keptDir], so nothing offers what's in here.
     */
    fun offRoot(c: Context) = File(keptDir(c), "off")

    fun folders(c: Context) = FontFolders(keptDir(c), offRoot(c))

    /** Where the shell and the app hand files to each other: the shell can't reach internal storage. Emptied on every read. */
    private fun bridgeDir(c: Context) = File(offRoot(c), ".bridge")

    /** Reads the library from disk, MediaStore and (with Shizuku) the Boox fonts folder, and brings the records in line. */
    suspend fun library(context: Context, nameOf: (String) -> String? = { null }): Result<FontLibrary> = withContext(Dispatchers.IO) {
        runCatching { lock.withLock { read(context, nameOf) } }.onFailure { log.e("scan failed", error = it) }
    }

    private suspend fun read(context: Context, nameOf: (String) -> String? = { null }): FontLibrary {
        // Every change runs under the lock and reads first, so anything half-copied here was left by an interrupted one.
        val swept = folders(context).sweep() + (bridgeDir(context).listFiles()?.count { it.delete() } ?: 0)
        if (swept > 0) log.i("tidy", "files" to swept)
        val docs = SystemFont.ownCopies(context)
        val reader = readerFiles()
        var files = folders(context).scan() + docs.orEmpty() + reader.orEmpty()
        val candidates = FontInventory.hashCandidates(loadRecords(context), files)
        if (candidates.isNotEmpty() && reader != null) files = withHashes(files, candidates)
        val r = synchronized(recordsLock) {
            FontInventory.reconcile(loadRecords(context), FontScan(files, reader != null, docs != null), System.currentTimeMillis(), nameOf)
                .also { if (it.changed) saveRecords(context, it.records) }
        }
        r.adopted.forEach { rec ->
            val f = r.fonts.firstOrNull { it.base == rec.base }
            log.i("adopt", "family" to rec.family, "files" to f?.files?.size, "bytes" to f?.size)
        }
        r.adoptedReader.forEach { (base, names) -> log.i("adopt Boox folder copies", "family" to (r.fonts.firstOrNull { it.base == base }?.family ?: base), "files" to names.size) }
        r.dropped.forEach { log.w("drop record", "family" to it.family, "reason" to "no files left") }
        return FontLibrary(r.fonts, r.foreign, reader != null, settings(context))
    }

    /** Every file in the Boox fonts folder, or null when it can't be read (no Shizuku). */
    private suspend fun readerFiles(): List<FontFileEntry>? {
        if (!Privileged.ready()) return null
        val r = Privileged.sh("if [ -d $TARGET_DIR ]; then for f in $TARGET_DIR/*; do [ -f \"\$f\" ] && stat -c '%s|%Y|%n' \"\$f\"; done; fi; echo $END")
        if (!r.out.contains(END)) return null
        return r.out.lineSequence().mapNotNull { FontNames.parseStat(it) }
            .map { (size, time, path) -> FontFileEntry(FontPlace.Reader, false, path.substringAfterLast('/'), FontNames.normalise(path), size, time) }
            .filter { FontNames.isFont(it.name) }
            .toList()
    }

    /** Hashes the named files in the Boox fonts folder and the app's copies of the same names, so they can be compared. */
    private suspend fun withHashes(files: List<FontFileEntry>, names: Set<String>): List<FontFileEntry> {
        val safe = names.filter { SAFE_FILE.matches(it) }.toSet()
        if (safe.isEmpty()) return files
        val reader = FontNames.parseSha256(Privileged.sh("cd $TARGET_DIR && sha256sum " + safe.joinToString(" ") { "'$it'" }).out)
        return files.map { f ->
            when {
                f.name !in safe -> f
                f.place == FontPlace.Reader && !f.off -> f.copy(sha256 = reader[f.name])
                f.place == FontPlace.App -> f.copy(sha256 = runCatching { FontNames.sha256(File(f.path)) }.getOrNull())
                else -> f
            }
        }
    }

    private fun settings(c: Context): FontSettings {
        val home = LauncherStore(c).load()
        val sleep = SleepStore.load(c)
        return FontSettings(
            tabletPath = SystemFont.current(),
            appPath = prefs(c).getString("app_font", null),
            homePath = home.fontFile.takeIf { home.fontSource == FontSource.Custom },
            homeFollowsApp = home.fontSource == FontSource.App,
            sleepPath = sleep.fontFile.takeIf { sleep.font == SleepFont.File },
        )
    }

    private fun loadRecords(c: Context) = FontRecords.fromJson(prefs(c).getString(RECORDS, null))

    private fun saveRecords(c: Context, records: List<FontRecord>) {
        // Committed at once, like the journal: a turn-off may recreate the activity right after.
        prefs(c).edit(commit = true) { putString(RECORDS, FontRecords.toJson(records)) }
    }

    private fun updateRecords(c: Context, block: (List<FontRecord>) -> List<FontRecord>) = synchronized(recordsLock) { saveRecords(c, block(loadRecords(c))) }

    private fun updateRecord(c: Context, base: String, block: (FontRecord) -> FontRecord) = updateRecords(c) { list -> list.map { if (it.base == base) block(it) else it } }

    private fun noteInstalled(c: Context, family: String, written: List<File>) {
        val base = fileBase(family)
        var created = false
        updateRecords(c) { list ->
            if (list.any { it.base == base }) list else { created = true; list + FontRecord(base, family, System.currentTimeMillis()) }
        }
        if (created) log.i("install", "family" to family, "files" to written.size, "bytes" to written.sumOf { it.length() })
        else if (written.isNotEmpty()) log.i("install styles", "family" to family, "files" to written.size, "bytes" to written.sumOf { it.length() })
    }

    private fun noteReader(c: Context, base: String, names: Set<String>) = updateRecord(c, base) { it.copy(reader = it.reader + names) }

    // ---------- Using a font ----------

    /** Installs what [use] needs from Google Fonts, then uses it: the browser's path. */
    suspend fun installFor(context: Context, fam: FontFamilyInfo, use: FontUse, weight: Int = 400): Result<FontChange> = withContext(Dispatchers.IO) {
        runCatching {
            if (use == FontUse.Reader) check(Privileged.ready()) { context.getString(R.string.fonts_needs_shizuku) }
            if (use == FontUse.Tablet) keepStyle(context, fam, weight, false).getOrThrow() else keep(context, fam).getOrThrow()
            use(context, fileBase(fam.name), use, weight).getOrThrow()
        }
    }

    /** Uses an installed family for [use], from the files on disk. [weight] picks the tablet font's cut. */
    suspend fun use(context: Context, base: String, use: FontUse, weight: Int = 400): Result<FontChange> = withContext(Dispatchers.IO) {
        runCatching {
            lock.withLock {
                val font = read(context).font(base) ?: error(context.getString(R.string.fonts_not_found))
                check(font.state == FontState.On) { context.getString(R.string.fonts_is_off, font.family) }
                applyUse(context, font, use, weight)
            }
        }.onFailure { log.w("use failed", "family" to base, "use" to use.name, error = it) }
    }

    private suspend fun applyUse(context: Context, font: InstalledFont, use: FontUse, weight: Int): FontChange {
        val regular = font.regular
        return when (use) {
            FontUse.Tablet -> {
                val f = font.appFile(weight) ?: regular ?: error(context.getString(R.string.fonts_no_upright))
                val target = SystemFont.apply(context, File(f.path), "${font.family} ${f.style?.weight ?: weight}").getOrThrow()
                if (FontNames.normalise(target).substringBeforeLast('/') == FontNames.READER_DIR) noteReader(context, font.base, setOf(f.name))
                log.i("use", "family" to font.family, "use" to "tablet", "weight" to f.style?.weight)
                FontChange(font.family, files = 1, bytes = f.size, tabletPath = target)
            }
            FontUse.Reader -> {
                check(Privileged.ready()) { context.getString(R.string.fonts_needs_shizuku) }
                val files = font.onFiles.filter { it.place == FontPlace.App }
                check(files.isNotEmpty()) { context.getString(R.string.fonts_no_upright) }
                Privileged.sh("mkdir -p $TARGET_DIR").requireOk()
                files.forEach { f ->
                    require(SAFE_FILE.matches(f.name)) { "Unsafe file name ${f.name}" }
                    Privileged.sh("cp '${f.path}' '$TARGET_DIR/${f.name}' && chmod 664 '$TARGET_DIR/${f.name}'").requireOk()
                }
                noteReader(context, font.base, files.map { it.name }.toSet())
                Journal.log(context, "font", font.family, "", true)
                log.i("use", "family" to font.family, "use" to "reader", "files" to files.size, "bytes" to files.sumOf { it.size })
                FontChange(font.family, files = files.size, bytes = files.sumOf { it.size })
            }
            FontUse.Home -> {
                val f = regular ?: error(context.getString(R.string.fonts_no_upright))
                val store = LauncherStore(context)
                store.save(store.load().copy(fontSource = FontSource.Custom, fontFile = f.path))
                log.i("use", "family" to font.family, "use" to "home")
                FontChange(font.family)
            }
            FontUse.App -> {
                val f = regular ?: error(context.getString(R.string.fonts_no_upright))
                setAppFont(context, font.family, f.path)
                log.i("use", "family" to font.family, "use" to "app")
                FontChange(font.family, restart = true)
            }
            FontUse.Sleep -> {
                val f = regular ?: error(context.getString(R.string.fonts_no_upright))
                val spec = SleepStore.load(context)
                SleepStore.save(context, spec.copy(font = SleepFont.File, fontFile = f.path))
                if (spec.active) SleepScheduler.request(context, "font", 3_000)
                log.i("use", "family" to font.family, "use" to "sleep")
                FontChange(font.family)
            }
        }
    }

    /** Stops one use and hands it back: the tablet to its previous font, the rest to their defaults. */
    suspend fun stopUsing(context: Context, base: String, use: FontUse): Result<FontChange> = withContext(Dispatchers.IO) {
        runCatching {
            lock.withLock {
                val lib = read(context)
                val font = lib.font(base) ?: error(context.getString(R.string.fonts_not_found))
                val uses = lib.usesOf(base)
                val which = mutableSetOf(use)
                // The tablet font may be one of the copies in the Boox folder, and the home screen may follow this app's font.
                if (use == FontUse.Reader && FontUse.Tablet in uses && lib.settings.tabletPath?.let { FontNames.normalise(it).substringBeforeLast('/') } == FontNames.READER_DIR) which += FontUse.Tablet
                if (use == FontUse.App && lib.settings.homeFollowsApp) which += FontUse.Home
                if (use == FontUse.Reader) check(lib.readerChecked) { context.getString(R.string.fonts_needs_shizuku_manage) }
                val released = release(context, lib, font, which)
                if (use == FontUse.Reader) removeReaderCopies(context, font)
                FontChange(font.family, released = usesIn(released) + use, restart = FontUse.App.name in released, tabletPath = released[FontRecord.TABLET_AFTER])
            }
        }.onFailure { log.w("stop using failed", "family" to base, "use" to use.name, error = it) }
    }

    /**
     * Hands back each use in [which] that [font] has, and returns what it used, keyed by [FontUse] name, for turning
     * it on again later. The tablet font goes first, and must switch before anything else changes.
     */
    private suspend fun release(context: Context, lib: FontLibrary, font: InstalledFont, which: Set<FontUse>): Map<String, String> {
        val uses = lib.usesOf(font.base) intersect which
        val s = lib.settings
        val out = linkedMapOf<String, String>()
        if (FontUse.Tablet in uses) {
            val goingAway = lib.fonts.filter { it.base == font.base || it.state == FontState.Off }.map { it.base }.toSet()
            val after = SystemFont.release(context) { p ->
                val n = FontNames.normalise(p)
                val dir = n.substringBeforeLast('/')
                (dir == FontNames.READER_DIR || dir == FontNames.DOCUMENTS_DIR) && FontNames.baseOf(n.substringAfterLast('/')) in goingAway
            }.getOrThrow()
            out[FontUse.Tablet.name] = s.tabletPath.orEmpty().substringAfterLast('/')
            out[FontRecord.TABLET_AFTER] = after.orEmpty()
            UiFonts.dropSystemFontCopy(context, font.base)
        }
        if (FontUse.Home in uses) {
            val store = LauncherStore(context)
            val p = store.load()
            out[FontUse.Home.name] = if (p.fontSource == FontSource.App) FontRecord.HOME_VIA_APP else s.homePath.orEmpty().substringAfterLast('/')
            store.save(p.copy(fontSource = FontSource.System, fontFile = null))
        }
        if (FontUse.App in uses) {
            out[FontUse.App.name] = s.appPath.orEmpty().substringAfterLast('/')
            setAppFont(context, null, null)
        }
        if (FontUse.Sleep in uses) {
            val spec = SleepStore.load(context)
            out[FontUse.Sleep.name] = s.sleepPath.orEmpty().substringAfterLast('/')
            SleepStore.save(context, spec.copy(font = SleepFont.System, fontFile = null))
            if (spec.active) SleepScheduler.request(context, "font", 3_000)
        }
        val handed = usesIn(out)
        if (handed.isNotEmpty()) {
            log.i(
                "hand back",
                *fields("family" to font.family, "uses" to handed.joinToString(",") { it.name }, "tablet" to out[FontRecord.TABLET_AFTER]?.let { SystemFont.nameOf(it.ifEmpty { null }) ?: "default" }),
            )
        }
        return out
    }

    /** Log fields without the empty ones. */
    private fun fields(vararg p: Pair<String, Any?>): Array<Pair<String, Any?>> = p.filter { it.second != null && it.second != "" }.toTypedArray()

    private fun usesIn(released: Map<String, String>) = released.keys.mapNotNull { k -> FontUse.entries.firstOrNull { it.name == k } }.toSet()

    private suspend fun removeReaderCopies(context: Context, font: InstalledFont) {
        val names = font.readerNames.filter { SAFE_FILE.matches(it) }
        if (names.isEmpty()) return
        check(Privileged.ready()) { context.getString(R.string.fonts_needs_shizuku_manage) }
        Privileged.sh("rm -f " + names.joinToString(" ") { "'$TARGET_DIR/$it'" }).requireOk()
        updateRecord(context, font.base) { it.copy(reader = it.reader - names.toSet()) }
        Journal.log(context, "font-remove", font.family, "", true)
        log.i("remove Boox folder copies", "family" to font.family, "files" to names.size)
    }

    // ---------- Off, on and delete ----------

    /** Hands back every use, then moves every file into app-private storage, so nothing offers the font. */
    suspend fun turnOff(context: Context, base: String): Result<FontChange> = one(context, base, "turn off") { lib, font -> turnOffLocked(context, lib, font) }

    /** Puts the files back where they were, and gives back the uses it had unless they were changed since. */
    suspend fun turnOn(context: Context, base: String): Result<FontChange> = one(context, base, "turn on") { _, font -> turnOnLocked(context, font) }

    /** Hands back every use, then removes every copy the app made: its folders, the Boox fonts folder and Documents. */
    suspend fun delete(context: Context, base: String): Result<FontChange> = one(context, base, "delete") { lib, font -> deleteLocked(context, lib, font) }

    suspend fun turnAllOff(context: Context): Result<BulkChange> =
        bulk(context, "turn all off", { lib -> lib.fonts.filter { it.state != FontState.Off } }) { lib, font -> turnOffLocked(context, lib, font) }

    suspend fun turnAllOn(context: Context): Result<BulkChange> =
        bulk(context, "turn all on", { lib -> lib.fonts.filter { it.state != FontState.On } }) { _, font -> turnOnLocked(context, font) }

    /** Deletes the families that are on and used nowhere (see [FontInventory.unused]). */
    suspend fun deleteUnused(context: Context): Result<BulkChange> =
        bulk(context, "delete unused", { lib -> lib.unused }) { lib, font -> deleteLocked(context, lib, font) }

    private suspend fun one(context: Context, base: String, what: String, act: suspend (FontLibrary, InstalledFont) -> FontChange): Result<FontChange> =
        withContext(Dispatchers.IO) {
            runCatching {
                lock.withLock {
                    val lib = read(context)
                    act(lib, lib.font(base) ?: error(context.getString(R.string.fonts_not_found)))
                }
            }.onFailure { log.e("$what failed", "family" to base, error = it) }
        }

    private suspend fun bulk(context: Context, what: String, pick: (FontLibrary) -> List<InstalledFont>, act: suspend (FontLibrary, InstalledFont) -> FontChange): Result<BulkChange> =
        withContext(Dispatchers.IO) {
            runCatching {
                lock.withLock {
                    val targets = pick(read(context)).map { it.base }
                    val done = mutableListOf<FontChange>()
                    val failed = mutableListOf<Pair<String, Throwable>>()
                    for (base in targets) {
                        // Read again each time: handing one family's uses back changes the settings the next one sees.
                        val lib = read(context)
                        val font = pick(lib).firstOrNull { it.base == base } ?: continue
                        runCatching { act(lib, font) }.onSuccess { done += it }.onFailure {
                            failed += font.family to it
                            log.e("$what failed", "family" to font.family, error = it)
                        }
                    }
                    log.i(what, "done" to done.size, "failed" to failed.size, "bytes" to done.sumOf { it.bytes })
                    BulkChange(done, failed)
                }
            }
        }

    private suspend fun turnOffLocked(context: Context, lib: FontLibrary, font: InstalledFont): FontChange {
        if (font.readerNames.isNotEmpty()) check(lib.readerChecked && Privileged.ready()) { context.getString(R.string.fonts_needs_shizuku_manage) }
        val released = release(context, lib, font, FontUse.entries.toSet())
        if (released.isNotEmpty()) updateRecord(context, font.base) { it.copy(released = it.released + released) }
        val folders = folders(context)
        val on = font.onFiles
        folders.turnOff(font.base)
        on.filter { it.place == FontPlace.Reader }.takeIf { it.isNotEmpty() }?.let { stashReader(context, folders, it) }
        on.filter { it.place == FontPlace.Documents && it.id != null }.forEach { d ->
            folders.stash(FontPlace.Documents, d.name) { out -> SystemFont.openOwnCopy(context, d.id!!).use { it.copyTo(out) } }
            SystemFont.deleteOwnCopy(context, d.id!!)
        }
        val bytes = on.sumOf { it.size }
        Journal.log(context, "font-off", context.getString(R.string.fonts_journal_off, font.family), "", true)
        log.i("turn off", *fields("family" to font.family, "files" to on.size, "bytes" to bytes, "released" to usesIn(released).joinToString(",") { it.name }))
        return FontChange(font.family, files = on.size, bytes = bytes, released = usesIn(released), restart = FontUse.App.name in released)
    }

    private suspend fun turnOnLocked(context: Context, font: InstalledFont): FontChange {
        val off = font.offFiles
        val offReader = off.filter { it.place == FontPlace.Reader }
        if (offReader.isNotEmpty()) check(Privileged.ready()) { context.getString(R.string.fonts_needs_shizuku_manage) }
        folders(context).turnOn(font.base)
        if (offReader.isNotEmpty()) unstashReader(context, offReader)
        off.filter { it.place == FontPlace.Documents }.forEach { d ->
            val f = File(d.path)
            SystemFont.publishOwnCopy(context, f)
            f.delete()
        }
        val restored = restoreUses(context, font, font.record.released)
        updateRecord(context, font.base) { it.copy(released = emptyMap()) }
        val bytes = off.sumOf { it.size }
        Journal.log(context, "font-on", context.getString(R.string.fonts_journal_on, font.family), "", true)
        log.i("turn on", *fields("family" to font.family, "files" to off.size, "bytes" to bytes, "restored" to restored.joinToString(",") { it.name }))
        return FontChange(font.family, files = off.size, bytes = bytes, restored = restored, restart = FontUse.App in restored)
    }

    private suspend fun deleteLocked(context: Context, lib: FontLibrary, font: InstalledFont): FontChange {
        if (font.readerNames.isNotEmpty()) check(lib.readerChecked && Privileged.ready()) { context.getString(R.string.fonts_needs_shizuku_manage) }
        val released = release(context, lib, font, FontUse.entries.toSet())
        val reader = font.readerNames.filter { SAFE_FILE.matches(it) }
        if (reader.isNotEmpty()) Privileged.sh("rm -f " + reader.joinToString(" ") { "'$TARGET_DIR/$it'" }).requireOk()
        font.onFiles.filter { it.place == FontPlace.Documents && it.id != null }.forEach { SystemFont.deleteOwnCopy(context, it.id!!) }
        folders(context).delete(font.base)
        cacheDir(context).listFiles { f -> FontNames.baseOf(f.name) == font.base }?.forEach { it.delete() }
        UiFonts.dropSystemFontCopy(context, font.base)
        updateRecords(context) { list -> list.filter { it.base != font.base } }
        Journal.log(context, "font-delete", context.getString(R.string.fonts_journal_delete, font.family), "", true)
        log.i("delete", *fields("family" to font.family, "files" to font.files.size, "bytes" to font.size, "released" to usesIn(released).joinToString(",") { it.name }))
        return FontChange(font.family, files = font.files.size, bytes = font.size, released = usesIn(released), restart = FontUse.App.name in released)
    }

    /** Moves the app's copies in the Boox fonts folder into its off folder, through the shell. */
    private suspend fun stashReader(context: Context, folders: FontFolders, files: List<FontFileEntry>) {
        val bridge = bridgeDir(context).apply { mkdirs() }.absolutePath
        files.forEach { f ->
            require(SAFE_FILE.matches(f.name)) { "Unsafe file name ${f.name}" }
            Privileged.sh("cp '$TARGET_DIR/${f.name}' '$bridge/${f.name}' && chmod 666 '$bridge/${f.name}'").requireOk()
            val copy = File(bridge, f.name)
            check(copy.length() == f.size) { "Incomplete copy of ${f.name}" }
            folders.stash(FontPlace.Reader, f.name) { out -> copy.inputStream().use { it.copyTo(out) } }
        }
        Privileged.sh("rm -f " + files.joinToString(" ") { "'$TARGET_DIR/${it.name}' '$bridge/${it.name}'" }).requireOk()
    }

    /** Puts the off copies back into the Boox fonts folder, through the shell. */
    private suspend fun unstashReader(context: Context, files: List<FontFileEntry>) {
        val bridge = bridgeDir(context).apply { mkdirs() }
        Privileged.sh("mkdir -p $TARGET_DIR").requireOk()
        files.forEach { f ->
            require(SAFE_FILE.matches(f.name)) { "Unsafe file name ${f.name}" }
            val src = File(f.path)
            val copy = File(bridge, f.name)
            src.copyTo(copy, overwrite = true)
            Privileged.sh("cp '${copy.absolutePath}' '$TARGET_DIR/${f.name}' && chmod 664 '$TARGET_DIR/${f.name}'").requireOk()
            copy.delete()
            src.delete()
        }
    }

    /** Gives back the uses a family had when it was turned off, each only if its setting is still the default it was handed back to. */
    private suspend fun restoreUses(context: Context, font: InstalledFont, released: Map<String, String>): Set<FontUse> {
        if (released.isEmpty()) return emptySet()
        val out = mutableSetOf<FontUse>()
        val kept = keptDir(context)
        fun file(use: FontUse) = released[use.name]?.let { File(kept, it) }?.takeIf { it.canRead() && FontNames.baseOf(it.name) == font.base }
        file(FontUse.App)?.let { if (appFontFamily(context) == null) { setAppFont(context, font.family, it.absolutePath); out += FontUse.App } }
        released[FontUse.Home.name]?.let { v ->
            val store = LauncherStore(context)
            val p = store.load()
            if (p.fontSource == FontSource.System) {
                if (v == FontRecord.HOME_VIA_APP) {
                    if (appFontFamily(context) == font.family) { store.save(p.copy(fontSource = FontSource.App)); out += FontUse.Home }
                } else file(FontUse.Home)?.let { store.save(p.copy(fontSource = FontSource.Custom, fontFile = it.absolutePath)); out += FontUse.Home }
            }
        }
        file(FontUse.Sleep)?.let { f ->
            val spec = SleepStore.load(context)
            if (spec.font == SleepFont.System) {
                SleepStore.save(context, spec.copy(font = SleepFont.File, fontFile = f.absolutePath))
                if (spec.active) SleepScheduler.request(context, "font", 3_000)
                out += FontUse.Sleep
            }
        }
        file(FontUse.Tablet)?.let { f ->
            val after = FontNames.normalise(released[FontRecord.TABLET_AFTER].orEmpty())
            if (FontNames.normalise(SystemFont.current().orEmpty()) == after) {
                SystemFont.apply(context, f, font.family).onSuccess { t ->
                    if (FontNames.normalise(t).substringBeforeLast('/') == FontNames.READER_DIR) noteReader(context, font.base, setOf(f.name))
                    out += FontUse.Tablet
                }.onFailure { log.w("tablet font not given back", "family" to font.family, error = it) }
            }
        }
        return out
    }

    // ---------- App font ----------

    private fun prefs(c: Context) = c.getSharedPreferences("fonts", Context.MODE_PRIVATE)
    fun appFont(c: Context): String? = prefs(c).getString("app_font", null)?.takeIf { File(it).canRead() }
    fun appFontFamily(c: Context): String? = prefs(c).getString("app_font_family", null)
    fun setAppFont(c: Context, family: String?, path: String?) = prefs(c).edit().putString("app_font", path).putString("app_font_family", family).apply()

    // ---------- Plumbing ----------

    private fun trimCache(context: Context) {
        val files = cacheDir(context).listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= CACHE_LIMIT) break
            total -= f.length()
            f.delete()
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        // A plain agent: the CSS2 API then answers with TrueType, which Android and NeoReader read directly.
        setRequestProperty("User-Agent", "Mozilla/4.0")
    }

    private fun get(url: String): String {
        val c = open(url)
        return try {
            check(c.responseCode == 200) { "Google Fonts answered ${c.responseCode}" }
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, out: File) {
        val tmp = File(out.parentFile, out.name + ".part")
        val c = open(url)
        try {
            check(c.responseCode == 200) { "Google Fonts answered ${c.responseCode}" }
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.length() > 1024) { "Download was empty" }
            check(tmp.renameTo(out)) { "Could not save ${out.name}" }
        } finally {
            c.disconnect()
            tmp.delete()
        }
    }

    private fun ShellResult.requireOk() = check(ok) { message }
}
