package app.booxultimatum.nib.pen

import app.booxultimatum.kit.ink.epd.Epd
import app.booxultimatum.kit.ink.session.InkStroke
import app.booxultimatum.nib.engine.brush.PenButtonConfig
import app.booxultimatum.nib.engine.brush.PenButtons

/**
 * One part of the pen as the display's per-part configuration takes it, comparable so Nib sends a configuration only
 * when it changes. [toButton] is the display's own form.
 */
data class PenPart(
    val button: Int,
    val style: Int,
    val widthPx: Float,
    val argb: Int,
    val params: List<Float>,
    val eraserPreview: Boolean,
    val eraserPainter: Int,
) {
    fun toButton(): Epd.ButtonStroke =
        Epd.ButtonStroke(button, style, widthPx, argb, params.takeIf { it.isNotEmpty() }?.toFloatArray(), eraserPreview, eraserPainter)

    override fun toString(): String = "$button:$style ${widthPx}px #${Integer.toHexString(argb)} ${params.joinToString(",")}"
}

/**
 * What Nib tells the display each part of the pen draws, as BOOX's own note app sets it up: the tip previews the
 * stroke Nib sends for the pen in hand (its style, width after [PreviewMatch], colour and parameters), and the eraser
 * end previews an eraser track ([PenButtons.eraserEnd]) that the display draws itself while Nib erases from the
 * eraser end's samples at the lift. Whether the display honours it is *[verify]* on the tablet.
 */
object PenParts {
    /** The eraser end's painter, as the native app sets it: the eraser track style. */
    const val ERASER_PAINTER = PenButtons.ERASER_STYLE

    /** The tip for [stroke], the stroke the session previews. */
    fun tip(stroke: InkStroke): PenPart =
        PenPart(Epd.PenButton.TIP, stroke.style, stroke.widthPx, stroke.argb, stroke.params?.toList().orEmpty(), eraserPreview = false, eraserPainter = 0)

    /** The engine's configuration for [button], as a part; the eraser end previews as an eraser. */
    fun of(config: PenButtonConfig, button: Int): PenPart {
        val eraser = button == Epd.PenButton.CAP
        return PenPart(button, config.style, config.widthPx, config.argb, config.params.toList(), eraserPreview = eraser, eraserPainter = if (eraser) ERASER_PAINTER else 0)
    }

    /** The tip and the eraser end, in the order the native app sends them. */
    fun both(tip: InkStroke, eraserEnd: PenButtonConfig): List<PenPart> = listOf(tip(tip), of(eraserEnd, Epd.PenButton.CAP))
}
