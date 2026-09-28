package app.booxultimatum.nib.engine.brush

import kotlin.math.max

/**
 * The display firmware's fast preview styles, painted by SurfaceFlinger while the pen touches.
 *
 * @property code the value passed to the firmware.
 * @property verified true when the style has been seen working on the Note Air6 C.
 */
enum class HardwareStyle(val code: Int, val verified: Boolean) {
    Pencil(0, true),
    Fountain(1, true),
    Marker(2, true),
    NeoBrush(3, false),
    Charcoal(4, false),
    Dash(5, false),
    CharcoalV2(6, false),
    SquarePen(7, false),
    ;

    /** The closest verified style, for use while this one is unverified. */
    val fallback: HardwareStyle
        get() = when (this) {
            Pencil, Fountain, Marker -> this
            Charcoal, CharcoalV2 -> Pencil
            NeoBrush, Dash, SquarePen -> Fountain
        }

    companion object {
        fun fromCode(code: Int): HardwareStyle? = entries.firstOrNull { it.code == code }
    }
}

/**
 * How the [HardwareStyle.Marker] preview carries colour. On the Note Air6 C (FW 4.3) the display drops a translucent
 * marker preview in red, blue, green or teal, but shows every grey from black to #BBBBBB and shows opaque colours
 * (tested with the Marker colours probe, 2026-09-28).
 */
enum class MarkerPreview(val id: String) {
    /**
     * Opaque, in the colour itself: it covers what's under the stroke until the lift, and pale colours may not show.
     * The default, which the owner preferred on the tablet.
     */
    SolidColour("solid_colour"),

    /** Translucent, in a grey as light as the colour: what's under the stroke stays visible. */
    SeeThroughGrey("see_through_grey"),
    ;

    /** The ARGB to send for a marker preview of [argb], as [Preview.argb] resolved it. */
    fun adapt(argb: Int): Int {
        val rgb = argb and 0xFFFFFF
        return when (this) {
            SolidColour -> OPAQUE or rgb
            SeeThroughGrey -> {
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                val grey = if (r == g && g == b) r else ((299 * r + 587 * g + 114 * b + 500) / 1000)
                val v = grey.coerceAtMost(LIGHTEST_GREY)
                (Preview.MARKER_ALPHA shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
    }

    companion object {
        /** The lightest grey seen showing at half alpha; lighter ones are sent at this. */
        const val LIGHTEST_GREY = 0xBB
        private const val OPAQUE = -0x1000000

        fun of(id: String?): MarkerPreview = entries.firstOrNull { it.id == id } ?: SolidColour
    }
}

/** A resolved hardware preview: what to pass to the firmware for one stroke. */
data class HardwarePreview(val style: HardwareStyle, val widthPx: Float, val argb: Int)

/** Which colour the hardware preview uses. */
enum class PreviewColor {
    /** Plain black, the clearest on the monochrome layer. */
    Black,

    /** The stroke's own colour, made opaque. */
    Brush,

    /** Paper white, for erasers. */
    White,
}

/**
 * How a brush is shown by the firmware while the pen touches, before the app's own rendering replaces it.
 *
 * Hardware previews must be fully opaque except the [HardwareStyle.Marker] style, which the firmware blends at
 * [MARKER_ALPHA]; the constructor enforces that.
 *
 * @property widthFactor preview width relative to the brush width.
 * @property minWidthPx the thinnest preview, in view pixels.
 */
data class Preview(
    val style: HardwareStyle,
    val widthFactor: Float = 1f,
    val minWidthPx: Float = DEFAULT_MIN_WIDTH_PX,
    val color: PreviewColor = PreviewColor.Brush,
    val alpha: Int = if (style == HardwareStyle.Marker) MARKER_ALPHA else 255,
) {
    init {
        require(alpha in 0..255) { "alpha must be 0..255, was $alpha" }
        require(alpha == 255 || style == HardwareStyle.Marker) { "only the Marker style can be translucent" }
        require(widthFactor >= 0f && minWidthPx >= 0f) { "widths must not be negative" }
    }

    /** The preview width in view pixels for a stroke [docWidth] document pixels wide at [viewScale]. */
    fun widthPx(docWidth: Float, viewScale: Float): Float = max(minWidthPx, docWidth * widthFactor * viewScale)

    /**
     * The style to request for this width. Pencil's texture breaks up below [PENCIL_MIN_WIDTH_PX], so thin pencil
     * previews use Fountain; with [verifiedOnly], unverified styles give way to their [HardwareStyle.fallback].
     */
    fun styleFor(docWidth: Float, viewScale: Float, verifiedOnly: Boolean = false): HardwareStyle {
        var s = if (verifiedOnly) style.fallback else style
        if (s == HardwareStyle.Pencil && widthPx(docWidth, viewScale) < PENCIL_MIN_WIDTH_PX) s = HardwareStyle.Fountain
        return s
    }

    /** The ARGB colour to hand the firmware for a stroke of [brushColor]. */
    fun argb(brushColor: Int): Int {
        val rgb = when (color) {
            PreviewColor.Black -> 0x000000
            PreviewColor.White -> 0xFFFFFF
            PreviewColor.Brush -> brushColor and 0xFFFFFF
        }
        return (alpha shl 24) or rgb
    }

    companion object {
        /** The default thinnest preview, to be tuned on the tablet. */
        const val DEFAULT_MIN_WIDTH_PX = 1f

        /** Below this width the Pencil style's texture looks broken. */
        const val PENCIL_MIN_WIDTH_PX = 3f

        /** The only translucency the firmware's Marker style supports. */
        const val MARKER_ALPHA = 128
    }
}
