package app.booxultimatum.core.sleep

import android.content.Context
import android.graphics.Typeface
import app.booxultimatum.R
import app.booxultimatum.core.UiFonts
import java.io.File

/**
 * Typefaces for the faces, at any weight. A variable file (the Boox system font is usually one, and so is Archivo)
 * gets a real `wght` instance; a family saved as separate style files gets its nearest real weight. Nothing is
 * synthesised: a fake bold smears on e-ink.
 */
class SleepTypefaces private constructor(private val file: File?, private val styles: Map<Int, File>, val stamp: String) {
    fun at(weight: Int): Typeface {
        val w = weight.coerceIn(100, 900)
        val key = "$stamp|$w"
        synchronized(cache) { cache[key]?.let { return it } }
        val tf = runCatching {
            when {
                styles.size >= 2 -> Typeface.Builder(styles.minBy { (sw, _) -> Math.abs(sw - w) * 2 - (if (sw > w) 1 else 0) }.value).build()
                file != null -> Typeface.Builder(file).setFontVariationSettings("'wght' $w").build()
                else -> null
            }
        }.getOrNull() ?: Typeface.create(Typeface.SANS_SERIF, w, false)
        synchronized(cache) { cache[key] = tf }
        return tf
    }

    companion object {
        private val cache = HashMap<String, Typeface>()

        private val STYLE_WEIGHTS = mapOf(
            "Thin" to 100, "ExtraLight" to 200, "Light" to 300, "Regular" to 400, "Medium" to 500,
            "SemiBold" to 600, "Bold" to 700, "ExtraBold" to 800, "Black" to 900,
        )

        /** Blocking: the system font may need one copy through Shizuku after it changes (see [UiFonts.systemFontCopy]). */
        fun load(context: Context, spec: SleepFaceSpec): SleepTypefaces = when (spec.font) {
            SleepFont.System -> of(UiFonts.systemFontCopy(context))
            SleepFont.Archivo -> of(archivo(context))
            SleepFont.File -> of(spec.fontFile?.let(::File)?.takeIf { it.canRead() })
        }

        private fun of(file: File?): SleepTypefaces {
            if (file == null) return SleepTypefaces(null, emptyMap(), "default")
            val base = file.name.substringBefore('-')
            val styles = file.parentFile?.listFiles { f -> f.name.startsWith("$base-") && f.name.endsWith(".ttf") && !f.name.contains("Italic") }
                .orEmpty()
                .mapNotNull { f -> STYLE_WEIGHTS[f.name.removePrefix("$base-").removeSuffix(".ttf")]?.let { it to f } }
                .toMap()
            return SleepTypefaces(file, if (styles.size >= 2) styles else emptyMap(), "${file.absolutePath}@${file.lastModified()}")
        }

        /** The bundled Archivo, copied once out of the APK so it can be instanced by weight like any other file. */
        // A font resource is a plain file in the APK, so reading it raw is correct here.
        @android.annotation.SuppressLint("ResourceType")
        private fun archivo(context: Context): File? = runCatching {
            val out = File(File(context.filesDir, "sleep").apply { mkdirs() }, "Archivo.ttf")
            if (out.length() == 0L) {
                context.resources.openRawResource(app.booxultimatum.kit.ui.R.font.archivo).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            out
        }.getOrNull()
    }
}
