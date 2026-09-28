package app.booxultimatum.nib.brush

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Nib's colours. The fixed swatches are tuned for Kaleido 3, whose colour filter mutes everything: saturated, fairly
 * dark hues read best, and pale ones vanish. The picker covers the rest by hue and lightness at a fixed saturation.
 */
object Palette {
    /** One swatch: [key] names it for the string table, [argb] is opaque. */
    data class Swatch(val key: String, val argb: Int)

    val SWATCHES: List<Swatch> = listOf(
        Swatch("black", 0xFF000000.toInt()),
        Swatch("dark_grey", 0xFF404040.toInt()),
        Swatch("mid_grey", 0xFF808080.toInt()),
        Swatch("white", 0xFFFFFFFF.toInt()),
        Swatch("red", 0xFFD2232A.toInt()),
        Swatch("orange", 0xFFE8731A.toInt()),
        Swatch("yellow", 0xFFF2C300.toInt()),
        Swatch("green", 0xFF1F8A3B.toInt()),
        Swatch("teal", 0xFF0F8580.toInt()),
        Swatch("blue", 0xFF1F4FB8.toInt()),
        Swatch("purple", 0xFF6D2A9E.toInt()),
        Swatch("brown", 0xFF7A4A21.toInt()),
    )

    /** The picker's saturation: strong, since Kaleido halves what it's given. */
    const val PICKER_SATURATION = 0.85f

    /** An opaque colour from [hue] in degrees (0..360) and [lightness] (0..1), at [saturation]. */
    fun hsl(hue: Float, lightness: Float, saturation: Float = PICKER_SATURATION): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val l = lightness.coerceIn(0f, 1f)
        val s = saturation.coerceIn(0f, 1f)
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return argb(r + m, g + m, b + m)
    }

    /** Hue in degrees and lightness 0..1 of an ARGB colour, for placing the picker's marks. */
    fun hueLightness(argb: Int): Pair<Float, Float> {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        val d = mx - mn
        if (d == 0f) return 0f to l
        val h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return ((h + 360f) % 360f) to l
    }

    /** The swatch with exactly this colour, or null for a picked one. */
    fun swatchOf(argb: Int): Swatch? = SWATCHES.firstOrNull { it.argb == (argb or OPAQUE) }

    /** Whether ink of [argb] needs a dark rim to show on white paper. */
    fun isPale(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b > 200
    }

    private fun argb(r: Float, g: Float, b: Float): Int =
        OPAQUE or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)

    private fun channel(v: Float): Int = (v.coerceIn(0f, 1f) * 255f).roundToInt()

    const val OPAQUE: Int = -0x1000000
}
