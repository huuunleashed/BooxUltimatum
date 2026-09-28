package app.booxultimatum.nib.brush

import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.brush.HardwareStyle
import app.booxultimatum.nib.engine.input.PressureCurve
import app.booxultimatum.kit.ink.PreviewStroke

/** How hard the pen has to press: each preset reshapes the brush's own curve; brushes that ignore pressure keep ignoring it. */
enum class PressurePreset(val key: String, private val exponentFactor: Float) {
    Soft("soft", 0.55f),
    Medium("medium", 1f),
    Firm("firm", 1.8f),
    ;

    /** The curve [kind] uses with this preset. */
    fun curve(kind: BrushKind): PressureCurve {
        val base = BrushSpec.defaults(kind).curve
        if (base.isConstant || this == Medium) return base
        return base.copy(exponent = base.exponent * exponentFactor)
    }

    companion object {
        fun of(key: String?): PressurePreset = entries.firstOrNull { it.key == key } ?: Medium

        /** Whether presets change anything for [kind]. */
        fun applies(kind: BrushKind): Boolean = !BrushSpec.defaults(kind).curve.isConstant
    }
}

/** One favourite pen: a brush, a width, a pressure preset and a colour. What a toolbar slot holds. */
data class BrushPreset(val kind: BrushKind, val width: Float, val pressure: PressurePreset, val color: Int) {
    /** The full brush this preset draws with. */
    fun spec(): BrushSpec = BrushSpec.defaults(kind).withWidth(width).copy(curve = pressure.curve(kind))

    fun encode(): String = listOf(kind.id, width.toString(), pressure.key, Integer.toHexString(color)).joinToString(SEP)

    companion object {
        private const val SEP = ":"

        /** Reads [encode]'s text; null for anything damaged or naming a brush this version lacks. */
        fun decode(text: String?): BrushPreset? {
            val parts = text?.split(SEP) ?: return null
            if (parts.size != 4) return null
            val kind = BrushKind.fromId(parts[0])?.takeIf { !it.isEraser } ?: return null
            val width = parts[1].toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
            val color = parts[3].toLongOrNull(16)?.toInt() ?: return null
            return BrushPreset(kind, BrushSpec.widthRange(kind).let { width.coerceIn(it) }, PressurePreset.of(parts[2]), color)
        }

        /** The slots on a first start: a fountain pen, a fineliner, a pencil and a highlighter, as in Boox Notes. */
        val DEFAULTS: List<BrushPreset> = listOf(
            BrushPreset(BrushKind.Fountain, 3f, PressurePreset.Medium, 0xFF000000.toInt()),
            BrushPreset(BrushKind.Fineliner, 0.75f, PressurePreset.Medium, 0xFF000000.toInt()),
            BrushPreset(BrushKind.Pencil, 2.5f, PressurePreset.Medium, 0xFF404040.toInt()),
            BrushPreset(BrushKind.Highlighter, 20f, PressurePreset.Medium, 0xFFF2C300.toInt()),
        )
    }
}

/** The pen panel's groups, in order, and which brushes each holds. Erasers live in the eraser panel. */
enum class BrushGroup(val key: String, val kinds: List<BrushKind>) {
    Pens("pens", listOf(BrushKind.Fineliner, BrushKind.Fountain, BrushKind.Ballpoint, BrushKind.Calligraphy, BrushKind.SquarePen, BrushKind.Dash)),
    Pencils("pencils", listOf(BrushKind.Pencil, BrushKind.Graphite)),
    Markers("markers", listOf(BrushKind.Marker, BrushKind.Highlighter)),
    Brushes("brushes", listOf(BrushKind.BrushPen, BrushKind.NeoBrush, BrushKind.Airbrush)),
    Textured("textured", listOf(BrushKind.Charcoal, BrushKind.CharcoalV2)),
    ;

    companion object {
        fun of(kind: BrushKind): BrushGroup? = entries.firstOrNull { kind in it.kinds }
    }
}

/**
 * What the display is asked to preview for a brush. The engine names each brush's own firmware style; until a style
 * has been seen working on the tablet, a verified stand-in is used instead (the engine's [HardwareStyle.fallback]:
 * Fountain for most, Pencil for the charcoals), unless the owner turns on *Try unverified preview styles*.
 */
object PreviewPolicy {
    fun preview(brush: BrushSpec, color: Int, viewScale: Float, tryUnverified: Boolean): PreviewStroke {
        val p = brush.hardwarePreview(color, viewScale, verifiedOnly = !tryUnverified)
        return PreviewStroke(p.style.code, p.widthPx, p.argb)
    }

    /** The style shown instead of the brush's own because that one is unverified, or null when none stands in. */
    fun standIn(brush: BrushSpec, tryUnverified: Boolean): HardwareStyle? {
        val own = brush.preview.style
        if (tryUnverified || own.verified) return null
        return own.fallback
    }
}
