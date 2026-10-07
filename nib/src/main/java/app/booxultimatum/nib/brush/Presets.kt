package app.booxultimatum.nib.brush

import app.booxultimatum.nib.pen.PreviewMatch
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.brush.HardwareStyle
import app.booxultimatum.nib.engine.brush.MarkerPreview
import app.booxultimatum.nib.engine.input.PressureCurve
import app.booxultimatum.kit.ink.session.InkStroke

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

        /**
         * Whether presets, and a tuned curve, change anything for [kind]: only for the fountain pen, whose preview style
         * takes the curve as its sensitivity. Every other style draws pressure by its own fixed law (the brush style's
         * square root, the marker's 0.8 to 1, none for the pencil, plain pen, square pen and charcoals), so a reshaped
         * curve made the ink differ from its preview: a soft preset flattened the brush pen's thick and thin strokes
         * against the preview's (owner's report, 2026-10-07).
         */
        fun applies(kind: BrushKind): Boolean = !kind.isEraser && BrushSpec.defaults(kind).preview.style == HardwareStyle.Fountain
    }
}

/**
 * The owner's own settings for a brush, over its factory ones: null leaves a property as the brush has it. Values are
 * clamped to [TuneRange]s when read, so a damaged preference can't make an unusable brush.
 */
data class BrushTune(
    val opacity: Float? = null,
    val exponent: Float? = null,
    val floor: Float? = null,
    val ceiling: Float? = null,
    val smoothing: Float? = null,
    val spacing: Float? = null,
    val flow: Float? = null,
    val grain: Float? = null,
    val jitter: Float? = null,
    val nibAngle: Float? = null,
    val nibFromOrientation: Boolean? = null,
    val speedInfluence: Float? = null,
    val taper: Float? = null,
    val tiltScale: Float? = null,
) {
    val isEmpty: Boolean get() = this == EMPTY

    fun encode(): String = buildList {
        opacity?.let { add("o=$it") }
        exponent?.let { add("e=$it") }
        floor?.let { add("f=$it") }
        ceiling?.let { add("c=$it") }
        smoothing?.let { add("s=$it") }
        spacing?.let { add("sp=$it") }
        flow?.let { add("fl=$it") }
        grain?.let { add("g=$it") }
        jitter?.let { add("j=$it") }
        nibAngle?.let { add("a=$it") }
        nibFromOrientation?.let { add("ao=$it") }
        speedInfluence?.let { add("v=$it") }
        taper?.let { add("t=$it") }
        tiltScale?.let { add("ts=$it") }
    }.joinToString(";")

    companion object {
        val EMPTY = BrushTune()

        /** Reads [encode]'s text; unknown or damaged entries are skipped, values clamped to their ranges. */
        fun decode(text: String): BrushTune {
            var t = EMPTY
            for (part in text.split(';')) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val key = part.substring(0, eq)
                val raw = part.substring(eq + 1)
                if (key == "ao") {
                    raw.toBooleanStrictOrNull()?.let { t = t.copy(nibFromOrientation = it) }
                    continue
                }
                val v = raw.toFloatOrNull()?.takeIf { it.isFinite() } ?: continue
                t = when (key) {
                    "o" -> t.copy(opacity = TuneRange.OPACITY.clamp(v))
                    "e" -> t.copy(exponent = TuneRange.EXPONENT.clamp(v))
                    "f" -> t.copy(floor = TuneRange.FLOOR.clamp(v))
                    "c" -> t.copy(ceiling = TuneRange.CEILING.clamp(v))
                    "s" -> t.copy(smoothing = TuneRange.SMOOTHING.clamp(v))
                    "sp" -> t.copy(spacing = TuneRange.SPACING.clamp(v))
                    "fl" -> t.copy(flow = TuneRange.FLOW.clamp(v))
                    "g" -> t.copy(grain = TuneRange.GRAIN.clamp(v))
                    "j" -> t.copy(jitter = TuneRange.JITTER.clamp(v))
                    "a" -> t.copy(nibAngle = TuneRange.NIB_ANGLE.clamp(v))
                    "v" -> t.copy(speedInfluence = TuneRange.SPEED.clamp(v))
                    "t" -> t.copy(taper = TuneRange.TAPER.clamp(v))
                    "ts" -> t.copy(tiltScale = TuneRange.TILT.clamp(v))
                    else -> t
                }
            }
            return t
        }
    }
}

/** How far each tunable property may go. */
enum class TuneRange(val min: Float, val max: Float) {
    OPACITY(0.05f, 1f),
    EXPONENT(0.25f, 4f),
    FLOOR(0f, 1f),
    CEILING(0.1f, 1.5f),
    SMOOTHING(0f, 1f),
    SPACING(0.02f, 1f),
    FLOW(0.02f, 1f),
    GRAIN(0f, 1f),
    JITTER(0f, 1f),
    NIB_ANGLE(0f, Math.PI.toFloat()),
    SPEED(0f, 1f),
    TAPER(0f, 6f),

    /** A tilting brush's tilt scale: 1 ignores tilt, 3 is the display's own, 6 the broadest it honours. */
    TILT(BrushSpec.NO_TILT, BrushSpec.MAX_TILT_SCALE),
    ;

    fun clamp(v: Float): Float = if (v.isNaN()) min else v.coerceIn(min, max)
}

/** One favourite pen: a brush, a width, a pressure preset, a colour and the owner's tuning. What a rail slot holds. */
data class BrushPreset(
    val kind: BrushKind,
    val width: Float,
    val pressure: PressurePreset,
    val color: Int,
    val tune: BrushTune = BrushTune.EMPTY,
) {
    /** The full brush this preset draws with. */
    fun spec(): BrushSpec {
        val base = BrushSpec.defaults(kind).withWidth(width)
        var curve = base.curve
        if (PressurePreset.applies(kind)) {
            curve = pressure.curve(kind)
            tune.floor?.let { curve = curve.copy(floor = it) }
            tune.ceiling?.let { curve = curve.copy(ceiling = it) }
            tune.exponent?.let { curve = curve.copy(exponent = it) }
        }
        return base.copy(
            curve = curve,
            opacity = tune.opacity ?: base.opacity,
            smoothing = tune.smoothing ?: base.smoothing,
            spacing = tune.spacing ?: base.spacing,
            flow = tune.flow ?: base.flow,
            grain = tune.grain ?: base.grain,
            jitter = tune.jitter ?: base.jitter,
            nibAngle = tune.nibAngle ?: base.nibAngle,
            nibFromOrientation = tune.nibFromOrientation ?: base.nibFromOrientation,
            // Speed and taper only where the brush offers them (Brush settings hides them otherwise): the brush pen gave
            // them up to match its preview, and a slot tuned before mustn't bring them back unseen.
            speedInfluence = if (base.speedInfluence > 0f) tune.speedInfluence ?: base.speedInfluence else base.speedInfluence,
            taper = if (base.speedInfluence > 0f) tune.taper ?: base.taper else base.taper,
            // Only for the brushes that tilt: the display's charcoal styles broaden their preview by the same scale.
            tiltScale = if (base.usesTilt) tune.tiltScale ?: base.tiltScale else base.tiltScale,
        )
    }

    /** The same pen with a pressure preset chosen: the preset's curve replaces any tuned sensitivity. */
    fun withPressure(p: PressurePreset): BrushPreset = copy(pressure = p, tune = tune.copy(exponent = null))

    /** Whether the curve has been tuned away from the preset. */
    val customCurve: Boolean get() = tune.exponent != null || tune.floor != null || tune.ceiling != null

    fun encode(): String {
        val base = listOf(kind.id, width.toString(), pressure.key, Integer.toHexString(color))
        return (if (tune.isEmpty) base else base + tune.encode()).joinToString(SEP)
    }

    companion object {
        private const val SEP = ":"

        /** Reads [encode]'s text (with or without a tune, as 0.1 wrote it); null for anything damaged or naming a brush this version lacks. */
        fun decode(text: String?): BrushPreset? {
            val parts = text?.split(SEP) ?: return null
            if (parts.size != 4 && parts.size != 5) return null
            val stored = BrushKind.fromId(parts[0])?.takeIf { !it.isEraser } ?: return null
            // The pencil before 0.3, whose plain-line preview never looked like its grey dabs, became the stipple
            // pencil the display's charcoal v2 style previews: the same width (as the display is sent it) and colour.
            val kind = if (stored.retired) BrushKind.Pencil else stored
            val width = parts[1].toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
            val color = parts[3].toLongOrNull(16)?.toInt() ?: return null
            val tune = if (parts.size == 5) BrushTune.decode(parts[4]) else BrushTune.EMPTY
            return BrushPreset(kind, BrushSpec.widthRange(kind).let { width.coerceIn(it) }, PressurePreset.of(parts[2]), color, tune)
        }

        /** The slots on a first start: a fountain pen, a fineliner, a pencil, a highlighter, a brush pen and a marker. */
        val DEFAULTS: List<BrushPreset> = listOf(
            BrushPreset(BrushKind.Fountain, 3f, PressurePreset.Medium, 0xFF000000.toInt()),
            BrushPreset(BrushKind.Fineliner, 0.75f, PressurePreset.Medium, 0xFF000000.toInt()),
            BrushPreset(BrushKind.Pencil, 2.5f, PressurePreset.Medium, 0xFF000000.toInt()),
            BrushPreset(BrushKind.Highlighter, 20f, PressurePreset.Medium, 0xFFF2C300.toInt()),
            BrushPreset(BrushKind.BrushPen, 6f, PressurePreset.Medium, 0xFF1F4FB8.toInt()),
            BrushPreset(BrushKind.Marker, 16f, PressurePreset.Medium, 0xFFD2232A.toInt()),
        )
    }
}
/** The pen panel's groups, in order, and which brushes each holds. Erasers live in the eraser panel. */
enum class BrushGroup(val key: String, val kinds: List<BrushKind>) {
    Pens("pens", listOf(BrushKind.Fineliner, BrushKind.Fountain, BrushKind.Ballpoint, BrushKind.Calligraphy, BrushKind.CalligraphyAsian, BrushKind.SquarePen, BrushKind.Dash)),
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
 * What the display is asked to preview for a brush. The engine names each brush's own firmware style; a style that
 * isn't trusted yet (neither seen on the tablet nor used by BOOX's own note app for a pen, which leaves only the dash)
 * gives way to a stand-in (the engine's [HardwareStyle.fallback]) unless the owner turns on *Try unverified preview
 * styles*. The style's parameters come with the engine's [app.booxultimatum.nib.engine.brush.HardwarePreview.params].
 */
object PreviewPolicy {
    /**
     * With [match], the width is sized to the stroke Nib will draw (see [PreviewMatch]). The style's parameters come
     * from the brush's own settings, with a flat nib's angle as it shows on a page turned by [viewRotation] radians.
     */
    fun preview(
        brush: BrushSpec, color: Int, viewScale: Float, tryUnverified: Boolean, match: PreviewMatch? = null,
        marker: MarkerPreview = MarkerPreview.SolidColour, viewRotation: Float = 0f,
    ): InkStroke {
        val p = brush.hardwarePreview(color, viewScale, verifiedOnly = !tryUnverified, marker = marker, viewRotation = viewRotation)
        val width = match?.width(brush, p.style.code, p.widthPx) ?: p.widthPx
        return InkStroke(p.style.code, width, p.argb, params(brush, p))
    }

    /**
     * The style's parameters: the brush's own when the style is the brush's own, else the display's defaults. A style
     * standing in (a thin pencil or fineliner previewed as a fountain pen, for instance) got parameters derived from a
     * different pen in 0.3.0-test.1, and the fineliner's sensitivity of 0 drew its preview much thicker than its ink.
     * The Lab showed why: the display's fountain draws thinner as the sensitivity rises and thicker as it falls, and
     * matches Nib's own fountain at its defaults (owner's test on the Note Air6 C, FW 4.3, 2026-09-28).
     */
    fun params(brush: BrushSpec, p: app.booxultimatum.nib.engine.brush.HardwarePreview): FloatArray? {
        val own = p.style == brush.preview.style
        val list = if (own) p.params else app.booxultimatum.nib.engine.brush.DisplayParams.displayDefaults(p.style) ?: emptyList()
        return list.takeIf { it.isNotEmpty() }?.toFloatArray()
    }

    /** The style shown instead of the brush's own because that one is unverified, or null when none stands in. */
    fun standIn(brush: BrushSpec, tryUnverified: Boolean): HardwareStyle? {
        val own = brush.preview.style
        if (tryUnverified || own.verified) return null
        return own.fallback
    }
}
