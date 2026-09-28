package app.booxultimatum.core

import android.content.Context
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.booxultimatum.core.exec.Privileged
import java.io.File

/**
 * Turns font files into complete Compose families, so every weight the UI uses (400 body, 500 labels, 600
 * headings) renders from a real or variable instance of the chosen face instead of a synthetic bold of one file.
 * Also reads the tablet's current system font, which Boox keeps outside Android's font config.
 */
object UiFonts {
    /** Weights the instrument type scale uses, after the text-weight offset. */
    private val WEIGHTS = listOf(300, 400, 500, 600, 700, 800)

    private val STYLE_WEIGHTS = mapOf(
        "Thin" to 100, "ExtraLight" to 200, "Light" to 300, "Regular" to 400, "Medium" to 500,
        "SemiBold" to 600, "Bold" to 700, "ExtraBold" to 800, "Black" to 900,
    )

    /**
     * The family for [path]. Upright sibling files named `<Base>-<Style>.ttf` (how Fonts saves Google families)
     * supply their real weights. A single or variable file is registered once per weight with a `wght` variation,
     * which picks the matching instance of a variable font and is ignored by a static one.
     */
    @OptIn(ExperimentalTextApi::class)
    fun family(path: String): FontFamily? = runCatching {
        val file = File(path)
        val base = file.name.substringBefore('-')
        val siblings = file.parentFile?.listFiles { f -> f.name.startsWith("$base-") && f.name.endsWith(".ttf") && !f.name.contains("Italic") }
            .orEmpty()
            .mapNotNull { f -> STYLE_WEIGHTS[f.name.removePrefix("$base-").removeSuffix(".ttf")]?.let { it to f } }
            .toMap()
        val fonts = if (siblings.size >= 2) {
            siblings.map { (w, f) -> Font(f, FontWeight(w)) }
        } else {
            WEIGHTS.map { w -> Font(file, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))) }
        }
        FontFamily(fonts)
    }.getOrNull()

    // ---------- Text weight ----------

    private fun prefs(c: Context) = c.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** Added to every weight in the type scale: 0 as designed, 100 heavier (the default on e-ink), 200 heaviest. */
    fun weightBoost(c: Context): Int = prefs(c).getInt("weight_boost", 100)

    fun setWeightBoost(c: Context, v: Int) = prefs(c).edit().putInt("weight_boost", v.coerceIn(0, 200)).apply()

    // ---------- The Boox system font ----------

    private const val PROP = "persist.sys.font.en.regular.config"

    /** Path of the font Boox applies system-wide, or null when the firmware default is in use. */
    fun systemFontPath(): String? = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("getprop", PROP))
        p.inputStream.bufferedReader().readText().trim().ifEmpty { null }
    }.getOrNull()

    /**
     * A readable copy of the system font, so the app can draw it at proper weights. The original usually sits in
     * shared storage the app can't read, so it is copied through Shizuku once per change. Blocking.
     */
    fun systemFontCopy(context: Context): File? {
        val src = systemFontPath() ?: return null
        val dir = File(context.filesDir, "sysfont").apply { mkdirs() }
        val name = src.substringAfterLast('/').replace(Regex("[^A-Za-z0-9_.\\-]"), "_")
        val dst = File(dir, name)
        val stamp = File(dir, "source.txt")
        if (dst.length() > 0 && stamp.takeIf { it.exists() }?.readText() == src) return dst
        dir.listFiles()?.forEach { it.delete() }
        val direct = runCatching { File(src).takeIf { it.canRead() }?.copyTo(dst, overwrite = true) }.getOrNull()
        if (direct == null) {
            if (!Privileged.ready()) return null
            // The shell can read shared storage; it writes into our external files dir, which we can read back.
            val bridge = File(context.getExternalFilesDir(null), "sysfont.ttf")
            val r = kotlinx.coroutines.runBlocking { Privileged.sh("cp '${src.replace("'", "")}' '${bridge.absolutePath}' && chmod 666 '${bridge.absolutePath}'") }
            if (!r.ok || !bridge.canRead()) return null
            bridge.copyTo(dst, overwrite = true)
            bridge.delete()
        }
        stamp.writeText(src)
        return dst
    }
}
