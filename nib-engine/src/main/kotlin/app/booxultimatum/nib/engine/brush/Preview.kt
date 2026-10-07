package app.booxultimatum.nib.engine.brush

import app.booxultimatum.nib.engine.input.PressureCurve
import kotlin.math.max
import kotlin.math.min

/**
 * The display firmware's fast preview styles, painted by SurfaceFlinger while the pen touches.
 *
 * @property code the value passed to the firmware.
 * @property seenOnTablet true when the style has been seen working on the owner's Note Air6 C.
 * @property nativePen true when BOOX's own note app previews one of its pens in this style on this firmware (FW 4.3):
 *   pencil and plain pen 0, fountain 1, marker 2, brush 3, charcoal 4 (6 for its V2 texture), calligraphy 7. The dash
 *   style (5) previews only its lasso and eraser track, never a pen.
 */
enum class HardwareStyle(val code: Int, val seenOnTablet: Boolean, val nativePen: Boolean) {
    Pencil(0, true, true),
    Fountain(1, true, true),
    Marker(2, true, true),
    NeoBrush(3, false, true),
    Charcoal(4, false, true),
    Dash(5, false, false),
    CharcoalV2(6, false, true),
    SquarePen(7, false, true),
    ;

    /**
     * True when the style is trusted without the owner asking to try unverified ones: it has been seen on the tablet,
     * or BOOX's own note app uses it for a pen in production on this firmware.
     */
    val verified: Boolean get() = seenOnTablet || nativePen

    /**
     * The closest style seen on the tablet, for use while this one isn't [verified]: the pencil style for the constant
     * widths (the dash's ink is one), since the fountain style draws 3 px wider than it's sent.
     */
    val fallback: HardwareStyle
        get() = when (this) {
            Pencil, Fountain, Marker -> this
            Charcoal, CharcoalV2, Dash -> Pencil
            NeoBrush, SquarePen -> Fountain
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

/**
 * A resolved hardware preview: what to pass to the firmware for one stroke, in the order the firmware wants it (style,
 * then width, then colour, then the style's parameters).
 *
 * @property widthPx the width in view pixels (the brush's width times the zoom).
 * @property argb the colour, opaque unless a [MarkerPreview] asked for see-through grey.
 * @property params what to pass to the display's `setStrokeParameters(style, params)` (see [DisplayParams]); empty
 *   means the style takes none, or its own should be left alone, so nothing is sent.
 */
data class HardwarePreview(val style: HardwareStyle, val widthPx: Float, val argb: Int, val params: List<Float> = emptyList()) {
    /** [params] as the display's call takes them. */
    fun paramsArray(): FloatArray = params.toFloatArray()
}

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
     * The style to request; with [verifiedOnly], styles that aren't [HardwareStyle.verified] give way to their
     * [HardwareStyle.fallback]. The pencil style is kept at every width: until 0.3 thin pencil previews were sent in the
     * fountain style (an earlier report of a broken texture below 3 px), and the fountain style draws thin lines
     * heavier than they are; the Widths · pencil probe then showed clean pencil-style lines from 0.5 to 3 px, which is
     * also how BOOX Notes previews its plain pen down to 0.1 mm (owner's test, NA6C FW 4.3, 2026-09-28).
     */
    @Suppress("UNUSED_PARAMETER")
    fun styleFor(docWidth: Float, viewScale: Float, verifiedOnly: Boolean = false): HardwareStyle =
        if (verifiedOnly && !style.verified) style.fallback else style

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

        /** The only translucency the firmware's Marker style supports. */
        const val MARKER_ALPHA = 128
    }
}

/**
 * The per-style parameters the display takes through `setStrokeParameters(style, float[])`, computed from a brush's
 * own settings as BOOX's own note app computes them for its pens (the display's values read back from the tablet,
 * FW 4.3):
 *
 * - fountain (1): `[pressureSensitivity, smoothLevel]`, both 0..1, from the brush's [PressureCurve.sensitivity] and
 *   its [BrushSpec.smoothing]; the display's defaults are 0.3 and 0.6.
 * - charcoal (4) and charcoal v2 (6): `[tiltEnabled, tiltScale]`, so `[1, tiltScale]` for a brush that uses tilt and
 *   `[0, 1]` for one that doesn't; the display's default is `[1, 3]`.
 * - square pen (7): `[2, min(widthPx, 10), angle, smoothLevel]`, where the angle is the nib's in degrees on screen,
 *   +45 for Latin calligraphy and -45 for Asian. The first value is always 2; BOOX's app never writes it.
 * - dash (5): `[5]`, the display's own value.
 * - pencil (0) and brush (3) take none. The marker's `[1, 16]` is the display's own; BOOX's app never writes it and
 *   its meaning is unknown, so it's left alone.
 *
 * How the display's own geometry uses these is closed; Nib's brushes read the same settings (see [BrushKind]).
 */
object DisplayParams {
    /** The square pen's first parameter: always 2. */
    const val SQUARE_MODE = 2f

    /** The square pen's second parameter is the width, up to this. */
    const val SQUARE_MAX_WIDTH_PX = 10f

    /** The dash style's parameter. */
    const val DASH_LENGTH = 5f

    /** The parameters for a stroke of [brush] previewed in [style] at [widthPx], on a page turned by [viewRotation]. */
    fun of(style: HardwareStyle, brush: BrushSpec, widthPx: Float, viewRotation: Float = 0f): List<Float> = when (style) {
        HardwareStyle.Pencil, HardwareStyle.Marker, HardwareStyle.NeoBrush -> emptyList()
        HardwareStyle.Fountain -> listOf(brush.curve.sensitivity, smoothLevel(brush))
        HardwareStyle.Charcoal, HardwareStyle.CharcoalV2 -> if (brush.usesTilt) listOf(1f, brush.tiltScale.coerceAtMost(BrushSpec.MAX_TILT_SCALE)) else listOf(0f, 1f)
        HardwareStyle.Dash -> listOf(DASH_LENGTH)
        HardwareStyle.SquarePen -> listOf(
            SQUARE_MODE,
            if (widthPx.isNaN()) SQUARE_MAX_WIDTH_PX else min(max(widthPx, 0f), SQUARE_MAX_WIDTH_PX),
            nibDegrees(brush.nibAngle - viewRotation),
            smoothLevel(brush),
        )
    }

    /**
     * The values the display holds for [style] when nothing has changed them, as read back from the tablet (FW 4.3),
     * for putting it back as it was; null where they haven't been read (square pen) and empty for styles without any.
     */
    fun displayDefaults(style: HardwareStyle): List<Float>? = when (style) {
        HardwareStyle.Pencil, HardwareStyle.NeoBrush -> emptyList()
        HardwareStyle.Fountain -> listOf(PressureCurve.NATIVE_FOUNTAIN_SENSITIVITY, BrushSpec.NATIVE_FOUNTAIN_SMOOTHING)
        HardwareStyle.Marker -> listOf(1f, 16f)
        HardwareStyle.Charcoal, HardwareStyle.CharcoalV2 -> listOf(1f, BrushSpec.NATIVE_TILT_SCALE)
        HardwareStyle.Dash -> listOf(DASH_LENGTH)
        HardwareStyle.SquarePen -> null
    }

    /** The display's smoothLevel for [brush]: its smoothing, 0..1. */
    fun smoothLevel(brush: BrushSpec): Float = if (brush.smoothing.isNaN()) 0f else brush.smoothing.coerceIn(0f, 1f)

    /**
     * [angle] (radians, counter-clockwise on screen from the x axis, as [BrushSpec.nibAngle]) as the display's nib
     * angle: degrees in (-90, 90], since a flat nib turned half a turn is the same nib.
     */
    fun nibDegrees(angle: Float): Float {
        if (!angle.isFinite()) return 0f
        var d = Math.round(Math.toDegrees(angle.toDouble()) * 1000.0) / 1000.0 % 180.0
        if (d <= -90.0) d += 180.0
        if (d > 90.0) d -= 180.0
        return d.toFloat()
    }
}
