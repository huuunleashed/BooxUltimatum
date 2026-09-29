package app.booxultimatum.nib.engine.brush

import kotlin.math.roundToInt

/**
 * Pen widths in the units BOOX's own note app uses: millimetres on the Note Air6 C's 300 ppi panel. A document pixel
 * is a panel pixel at a zoom of 1, so `mm * 300 / 25.4` document pixels (1 mm is about 11.81 px). The native app
 * keeps pens within [NATIVE_PEN_MM] and markers within [NATIVE_MARKER_MM] in steps of [STEP_MM], and sends the
 * display `width * zoom`. Nib's own [BrushSpec.widthRange]s hold these ranges and go finer, since Nib allows thinner
 * lines.
 */
object PenWidth {
    const val PANEL_PPI = 300f
    const val MM_PER_INCH = 25.4f
    const val PX_PER_MM = PANEL_PPI / MM_PER_INCH

    /** The native app's width step. */
    const val STEP_MM = 0.05f

    /** The native app's pen widths. */
    val NATIVE_PEN_MM: ClosedFloatingPointRange<Float> = 0.1f..2f

    /** The native app's marker widths. */
    val NATIVE_MARKER_MM: ClosedFloatingPointRange<Float> = 0.5f..8f

    fun mmToPx(mm: Float): Float = mm * PX_PER_MM

    fun pxToMm(px: Float): Float = px / PX_PER_MM

    /** [mm] rounded to the native app's [STEP_MM]. */
    fun snapMm(mm: Float): Float = if (!mm.isFinite()) mm else (mm / STEP_MM).roundToInt() * STEP_MM

    /**
     * The native app's width range for the pen [kind] matches, in millimetres; null for the kinds it has no pen for
     * (dash, airbrush and the erasers).
     */
    fun nativeRangeMm(kind: BrushKind): ClosedFloatingPointRange<Float>? = when (kind) {
        BrushKind.Marker, BrushKind.Highlighter -> NATIVE_MARKER_MM
        BrushKind.Dash, BrushKind.Airbrush -> null
        else -> if (kind.isEraser) null else NATIVE_PEN_MM
    }

    /** [nativeRangeMm] in document pixels. */
    fun nativeRangePx(kind: BrushKind): ClosedFloatingPointRange<Float>? =
        nativeRangeMm(kind)?.let { mmToPx(it.start)..mmToPx(it.endInclusive) }
}
