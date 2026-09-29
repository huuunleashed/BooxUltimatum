package app.booxultimatum.nib.engine.brush

/**
 * One end of the pen as the display's per-button configuration takes it: a style code, a width in view pixels, an
 * ARGB colour and the style's parameters as a plain array. The tip carries the pen's own preview; the eraser end
 * carries style [PenButtons.ERASER_STYLE].
 */
class PenButtonConfig(val style: Int, val widthPx: Float, val argb: Int, params: FloatArray) {
    private val values = params.copyOf()

    /** The parameters, a fresh copy each time, ready to hand to the display. */
    val params: FloatArray get() = values.copyOf()

    override fun equals(other: Any?): Boolean =
        other is PenButtonConfig && style == other.style && widthPx == other.widthPx && argb == other.argb && values.contentEquals(other.values)

    override fun hashCode(): Int = ((style * 31 + widthPx.hashCode()) * 31 + argb) * 31 + values.contentHashCode()

    override fun toString(): String =
        "PenButtonConfig(style=$style, widthPx=$widthPx, argb=#${Integer.toHexString(argb)}, params=${values.contentToString()})"
}

/**
 * The display's per-button pen configuration, as BOOX's own note app fills it: the tip previews in the pen's style,
 * width, colour and parameters ([BrushSpec.hardwarePreview]); the eraser end uses style [ERASER_STYLE] with the
 * parameters `[eraserWidthPx, 0.5, 0.1]` (the meaning of the last two is unknown).
 */
object PenButtons {
    /** The eraser end's style code, outside the eight preview styles of [HardwareStyle]. */
    const val ERASER_STYLE = 8

    /** The eraser end's second and third parameters, as BOOX's app sends them. */
    const val ERASER_PARAM_1 = 0.5f
    const val ERASER_PARAM_2 = 0.1f

    private const val WHITE = -0x1

    /** The tip for a stroke of [brush] in [color]; the arguments are [BrushSpec.hardwarePreview]'s. */
    fun tip(
        brush: BrushSpec,
        color: Int,
        viewScale: Float,
        verifiedOnly: Boolean = false,
        marker: MarkerPreview = MarkerPreview.SolidColour,
        viewRotation: Float = 0f,
    ): PenButtonConfig {
        val p = brush.hardwarePreview(color, viewScale, verifiedOnly, marker, viewRotation)
        return PenButtonConfig(p.style.code, p.widthPx, p.argb, p.paramsArray())
    }

    /** The eraser end for an eraser [widthPx] view pixels wide, drawn in [argb] (paper white unless told otherwise). */
    fun eraserEnd(widthPx: Float, argb: Int = WHITE): PenButtonConfig {
        val w = if (widthPx.isFinite() && widthPx > 0f) widthPx else 1f
        return PenButtonConfig(ERASER_STYLE, w, argb, floatArrayOf(w, ERASER_PARAM_1, ERASER_PARAM_2))
    }

    /** The eraser end for [eraser] at [viewScale]: its full width on screen, in its own preview colour. */
    fun eraserEnd(eraser: BrushSpec, viewScale: Float): PenButtonConfig =
        eraserEnd(eraser.width * viewScale, eraser.preview.argb(WHITE))
}
