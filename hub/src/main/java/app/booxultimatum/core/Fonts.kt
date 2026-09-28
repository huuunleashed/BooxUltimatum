package app.booxultimatum.core

import android.content.Context
import android.util.JsonReader
import app.booxultimatum.core.exec.Privileged
import app.booxultimatum.core.exec.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
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
 */
object Fonts {
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
            val wanted = listOf(400 to false, 400 to true, 700 to false, 700 to true).filter { (w, i) -> fam.has(w, i) }
                .ifEmpty { listOf(fam.regularKey.removeSuffix("i").toIntOrNull()!! to fam.regularKey.endsWith("i")) }
            wanted.map { (w, i) ->
                val src = style(context, fam, w, i).getOrThrow()
                val dst = File(keptDir(context), fileName(fam.name, w, i))
                if (src.absolutePath != dst.absolutePath) src.copyTo(dst, overwrite = true)
                FontStyleFile(w, i, dst)
            }
        }
    }

    fun keptRegular(context: Context, family: String): File? =
        File(keptDir(context), fileName(family, 400, false)).takeIf { it.exists() }
            ?: keptDir(context).listFiles { f -> f.name.startsWith(fileBase(family) + "-") && !f.name.contains("Italic") }?.firstOrNull()

    // ---------- Applying ----------

    suspend fun installedNames(): Set<String>? {
        if (!Privileged.ready()) return null
        val r = Privileged.sh("ls -1 $TARGET_DIR 2>/dev/null")
        return r.out.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun isInstalled(family: String, installed: Set<String>?) = installed?.any { it.startsWith(fileBase(family) + "-") } == true

    /** Copies the family into /sdcard/fonts through the shell, where NeoReader and the Boox reading settings find it. */
    suspend fun installForReader(context: Context, fam: FontFamilyInfo): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            check(Privileged.ready()) { context.getString(app.booxultimatum.R.string.fonts_needs_shizuku) }
            val files = keep(context, fam).getOrThrow()
            Privileged.sh("mkdir -p $TARGET_DIR").requireOk()
            files.forEach { f ->
                require(SAFE_FILE.matches(f.file.name)) { "Unsafe file name ${f.file.name}" }
                Privileged.sh("cp '${f.file.absolutePath}' '$TARGET_DIR/${f.file.name}' && chmod 664 '$TARGET_DIR/${f.file.name}'").requireOk()
            }
            Journal.log(context, "font", fam.name, "", true)
            files.size
        }
    }

    suspend fun removeFromReader(context: Context, family: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val base = fileBase(family)
            require(base.isNotEmpty())
            Privileged.sh("rm -f $TARGET_DIR/$base-*.ttf").requireOk()
            Journal.log(context, "font-remove", family, "", true)
        }
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
