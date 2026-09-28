package app.booxultimatum.nib.engine.brush

import app.booxultimatum.nib.engine.input.PressureCurve
import kotlin.math.PI

/**
 * Everything that decides how a stroke looks. Each stroke keeps the spec it was drawn with, so changing a brush later
 * never changes old ink.
 *
 * @property width the line width in document pixels at full pressure.
 * @property curve pressure to width factor.
 * @property smoothing positional smoothing while drawing, 0 (raw) to 1 (heavy).
 * @property opacity group opacity 0..1: the stroke is drawn opaque and composited once, so it never darkens where it
 *   crosses itself.
 * @property spacing for dab brushes, the distance between dabs as a fraction of the dab diameter.
 * @property flow for dab brushes, the alpha of each dab.
 * @property grain for dab brushes, how much each dab's alpha varies, 0..1.
 * @property jitter for dab brushes, how far dabs scatter, as a fraction of the radius.
 * @property pressureFlow for dab brushes, how much pressure drives coverage, 0..1.
 * @property nibAngle for nib brushes, the nib edge's angle in radians, counter-clockwise on screen from the x axis
 *   (PI/4 rises to the right, like a calligrapher's 45 degrees).
 * @property nibFromOrientation for nib brushes, follow the pen's orientation instead of [nibAngle].
 * @property minRatio for nib brushes, the thinnest line relative to the full width.
 * @property speedInfluence how much speed thins the line, 0..1.
 * @property taper start and end taper length in multiples of [width]; 0 for none.
 * @property dashOn for dashed brushes, the dash length in multiples of [width].
 * @property dashOff for dashed brushes, the gap length in multiples of [width].
 */
data class BrushSpec(
    val kind: BrushKind,
    val width: Float,
    val curve: PressureCurve = PressureCurve.CONSTANT,
    val smoothing: Float = 0.3f,
    val opacity: Float = 1f,
    val blend: Blend = Blend.Normal,
    val preview: Preview = Preview(HardwareStyle.Fountain),
    val spacing: Float = 0.2f,
    val flow: Float = 1f,
    val grain: Float = 0f,
    val jitter: Float = 0f,
    val pressureFlow: Float = 0f,
    val nibAngle: Float = (PI / 4).toFloat(),
    val nibFromOrientation: Boolean = false,
    val minRatio: Float = 0.15f,
    val speedInfluence: Float = 0f,
    val taper: Float = 0f,
    val dashOn: Float = 3f,
    val dashOff: Float = 2f,
) {
    /** The allowed width range for this kind. */
    val widthRange: ClosedFloatingPointRange<Float> get() = widthRange(kind)

    /** A copy with [w] clamped to [widthRange]. */
    fun withWidth(w: Float): BrushSpec = copy(width = if (w.isNaN()) width else w.coerceIn(widthRange))

    /**
     * Everything the firmware needs to preview a stroke of this brush in [color] at [viewScale]: the style (Pencil
     * becomes Fountain when thin; unverified styles fall back when [verifiedOnly]), the width in view pixels and the
     * ARGB colour.
     */
    fun hardwarePreview(color: Int, viewScale: Float, verifiedOnly: Boolean = false): HardwarePreview =
        HardwarePreview(preview.styleFor(width, viewScale, verifiedOnly), preview.widthPx(width, viewScale), preview.argb(color))

    /** The largest radius any point of a stroke can reach, in document pixels. */
    val maxRadius: Float get() = width * 0.5f * curve.maxFactor

    companion object {
        /** The allowed width range for [kind], in document pixels. */
        fun widthRange(kind: BrushKind): ClosedFloatingPointRange<Float> = when (kind) {
            BrushKind.Fineliner -> 0.5f..40f
            BrushKind.Fountain -> 0.5f..60f
            BrushKind.Ballpoint -> 0.5f..12f
            BrushKind.Pencil -> 0.5f..60f
            BrushKind.Graphite -> 0.5f..24f
            BrushKind.Marker -> 4f..200f
            BrushKind.Highlighter -> 4f..200f
            BrushKind.BrushPen -> 1f..80f
            BrushKind.Calligraphy -> 1f..80f
            BrushKind.NeoBrush -> 1f..100f
            BrushKind.Charcoal -> 1f..100f
            BrushKind.CharcoalV2 -> 2f..120f
            BrushKind.Dash -> 0.5f..40f
            BrushKind.SquarePen -> 0.5f..60f
            BrushKind.Airbrush -> 4f..400f
            BrushKind.PixelEraser -> 2f..400f
            BrushKind.StrokeEraser -> 2f..200f
            BrushKind.LassoEraser -> 1f..8f
        }

        /** The factory settings for [kind]. */
        fun defaults(kind: BrushKind): BrushSpec = when (kind) {
            BrushKind.Fineliner -> BrushSpec(
                kind, width = 2f, smoothing = 0.35f,
                preview = Preview(HardwareStyle.Fountain),
            )
            BrushKind.Fountain -> BrushSpec(
                kind, width = 3f, curve = PressureCurve(0.75f, 0.25f, 1f), smoothing = 0.4f,
                preview = Preview(HardwareStyle.Fountain),
            )
            BrushKind.Ballpoint -> BrushSpec(
                kind, width = 1.5f, curve = PressureCurve(1f, 0.7f, 1f), smoothing = 0.3f, opacity = 0.88f,
                preview = Preview(HardwareStyle.Fountain),
            )
            BrushKind.Pencil -> BrushSpec(
                kind, width = 2.5f, curve = PressureCurve(1.1f, 0.45f, 1f), smoothing = 0.3f,
                spacing = 0.18f, flow = 0.5f, grain = 0.5f, jitter = 0.1f, pressureFlow = 0.6f,
                preview = Preview(HardwareStyle.Pencil),
            )
            BrushKind.Graphite -> BrushSpec(
                kind, width = 1.2f, curve = PressureCurve(1f, 0.35f, 1f), smoothing = 0.3f,
                spacing = 0.2f, flow = 0.45f, grain = 0.6f, jitter = 0.08f, pressureFlow = 0.8f,
                preview = Preview(HardwareStyle.Fountain, widthFactor = 0.6f),
            )
            BrushKind.Marker -> BrushSpec(
                kind, width = 16f, smoothing = 0.4f, opacity = 0.5f,
                preview = Preview(HardwareStyle.Marker),
            )
            BrushKind.Highlighter -> BrushSpec(
                kind, width = 20f, smoothing = 0.5f, blend = Blend.Multiply,
                nibAngle = (PI / 2 - 0.35).toFloat(), minRatio = 0.3f,
                preview = Preview(HardwareStyle.Marker),
            )
            BrushKind.BrushPen -> BrushSpec(
                kind, width = 6f, curve = PressureCurve(1.4f, 0.08f, 1f), smoothing = 0.45f,
                speedInfluence = 0.45f, taper = 1.5f,
                preview = Preview(HardwareStyle.NeoBrush),
            )
            BrushKind.Calligraphy -> BrushSpec(
                kind, width = 8f, curve = PressureCurve(1f, 0.7f, 1f), smoothing = 0.4f,
                nibAngle = (PI / 4).toFloat(), minRatio = 0.12f,
                preview = Preview(HardwareStyle.SquarePen),
            )
            BrushKind.NeoBrush -> BrushSpec(
                kind, width = 8f, curve = PressureCurve(1.6f, 0.05f, 1f), smoothing = 0.45f,
                speedInfluence = 0.2f, taper = 2.5f,
                preview = Preview(HardwareStyle.NeoBrush),
            )
            BrushKind.Charcoal -> BrushSpec(
                kind, width = 8f, curve = PressureCurve(1f, 0.5f, 1f), smoothing = 0.35f,
                spacing = 0.15f, flow = 0.35f, grain = 0.6f, jitter = 0.2f, pressureFlow = 0.9f,
                preview = Preview(HardwareStyle.Charcoal),
            )
            BrushKind.CharcoalV2 -> BrushSpec(
                kind, width = 12f, curve = PressureCurve(1f, 0.6f, 1f), smoothing = 0.35f,
                spacing = 0.12f, flow = 0.3f, grain = 0.75f, jitter = 0.35f, pressureFlow = 1f,
                preview = Preview(HardwareStyle.CharcoalV2),
            )
            BrushKind.Dash -> BrushSpec(
                kind, width = 2f, smoothing = 0.35f, dashOn = 3f, dashOff = 2.2f,
                preview = Preview(HardwareStyle.Dash),
            )
            BrushKind.SquarePen -> BrushSpec(
                kind, width = 4f, curve = PressureCurve(1f, 0.6f, 1f), smoothing = 0.35f,
                nibAngle = (PI / 4).toFloat(), minRatio = 0.55f,
                preview = Preview(HardwareStyle.SquarePen),
            )
            BrushKind.Airbrush -> BrushSpec(
                kind, width = 40f, curve = PressureCurve(1f, 0.3f, 1f), smoothing = 0.5f,
                spacing = 0.12f, flow = 0.06f, jitter = 0.05f, pressureFlow = 1f,
                preview = Preview(HardwareStyle.Marker, widthFactor = 0.6f),
            )
            BrushKind.PixelEraser -> BrushSpec(
                kind, width = 24f, smoothing = 0.2f, blend = Blend.Erase,
                preview = Preview(HardwareStyle.Fountain, color = PreviewColor.White),
            )
            BrushKind.StrokeEraser -> BrushSpec(
                kind, width = 12f, smoothing = 0f,
                preview = Preview(HardwareStyle.Fountain, widthFactor = 0.1f, minWidthPx = 1.5f, color = PreviewColor.Black),
            )
            BrushKind.LassoEraser -> BrushSpec(
                kind, width = 2f, smoothing = 0f,
                preview = Preview(HardwareStyle.Fountain, widthFactor = 0.5f, color = PreviewColor.Black),
            )
        }
    }
}
