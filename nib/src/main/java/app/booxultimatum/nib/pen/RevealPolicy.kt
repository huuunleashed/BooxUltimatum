package app.booxultimatum.nib.pen

import app.booxultimatum.kit.ink.canvas.InkCanvasController.Reveal
import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.brush.HardwareStyle
import app.booxultimatum.nib.engine.brush.MarkerPreview

/** Settings › Display preview › Show the finished ink: when Nib's own ink replaces the display's preview. */
enum class RevealChoice(val id: String) {
    /** At breaks for pens the display previews faithfully, after a pause for the others ([RevealPolicy.effective]). */
    Auto("auto"),

    /** Only at breaks, as BOOX's own note app does: a menu, a gesture, undo, a tool change, leaving the page. */
    AtBreaks("at_breaks"),

    /** After the pen pauses, when Nib pushes its exact ink into the display's layer; at breaks as well. */
    AfterPause("after_pause"),

    /** After every stroke, as Nib 0.2 did. */
    EveryStroke("every_stroke"),
    ;

    companion object {
        fun of(id: String?): RevealChoice = entries.firstOrNull { it.id == id } ?: Auto
    }
}

/**
 * Which reveal the canvas uses for the pen in hand. BOOX's own note app keeps the display's preview on screen until a
 * break, which works because its pens' final ink looks like their preview. Nib's pens whose final ink the display
 * previews faithfully (a native-matched style and a plain, opaque stroke) do the same under [RevealChoice.Auto]; the
 * rest (textured dab brushes, blends such as the highlighter's multiply, see-through or translucent ink, a stand-in
 * style, a layer that changes how ink looks) wait for a pause, and Nib then pushes its exact ink.
 */
object RevealPolicy {
    const val DEFAULT_PAUSE_MS = 800
    const val MIN_PAUSE_MS = 400
    const val MAX_PAUSE_MS = 2000
    const val PAUSE_STEP_MS = 100

    fun clampPause(ms: Int): Int = ms.coerceIn(MIN_PAUSE_MS, MAX_PAUSE_MS)

    /** How the active layer changes the look of new ink. */
    data class LayerLook(val opacity: Float = 1f, val blend: Blend = Blend.Normal, val alphaLock: Boolean = false) {
        val plain: Boolean get() = opacity >= 1f && blend == Blend.Normal && !alphaLock
    }

    /**
     * Whether the display's preview of [brush] in [color] looks like the ink Nib draws for it: a style BOOX's own
     * note app uses for a pen (and not a stand-in), a solid stroke rather than dabs, a normal blend, the brush's own
     * opacity or more, an opaque colour, a marker preview in the colour itself, and a plain layer.
     */
    fun faithful(
        brush: BrushSpec,
        color: Int,
        layer: LayerLook = LayerLook(),
        verifiedOnly: Boolean = true,
        marker: MarkerPreview = MarkerPreview.SolidColour,
        viewScale: Float = 1f,
    ): Boolean {
        val kind = brush.kind
        if (kind.isEraser || kind.rendersAsDabs) return false
        if (brush.blend != Blend.Normal || !layer.plain) return false
        val style = brush.preview.styleFor(brush.width, viewScale, verifiedOnly)
        if (!style.nativePen || (verifiedOnly && !brush.preview.style.verified)) return false
        if (brush.opacity < BrushSpec.defaults(kind).opacity - OPACITY_SLACK) return false
        if ((color ushr 24) != 0xFF) return false
        if (style == HardwareStyle.Marker && marker == MarkerPreview.SeeThroughGrey && !isGrey(color)) return false
        return true
    }

    /** The controller's reveal for [choice] with this pen. */
    fun effective(
        choice: RevealChoice,
        brush: BrushSpec,
        color: Int,
        layer: LayerLook = LayerLook(),
        verifiedOnly: Boolean = true,
        marker: MarkerPreview = MarkerPreview.SolidColour,
        viewScale: Float = 1f,
    ): Reveal = when (choice) {
        RevealChoice.AtBreaks -> Reveal.AtBreaks
        RevealChoice.AfterPause -> Reveal.AfterPause
        RevealChoice.EveryStroke -> Reveal.EveryStroke
        RevealChoice.Auto -> if (faithful(brush, color, layer, verifiedOnly, marker, viewScale)) Reveal.AtBreaks else Reveal.AfterPause
    }

    private fun isGrey(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return r == g && g == b
    }

    private const val OPACITY_SLACK = 0.005f
}
