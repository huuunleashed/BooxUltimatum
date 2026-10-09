package app.booxultimatum.nib.engine.brush

import app.booxultimatum.nib.engine.input.PressureCurve
import app.booxultimatum.nib.engine.render.Stipple
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

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
 * @property tiltScale how much a tilted pen broadens the line: 1 (the value for every stroke drawn before tilt was used)
 *   ignores tilt. Above 1 it's the display's own charcoal `tiltScale` (3 on the Note Air6 C), not a largest width
 *   multiple: with [TiltResponse.Native] the line is `1 + tiltScale * g(tilt)` times as broad, up to about 7.9 times
 *   at a scale of 3 with the pen lying flat. The display's charcoal styles take it as it is (see [TiltShading],
 *   [DisplayParams]).
 * @property tiltResponse how a tilted pen changes the line, when [usesTilt]: BOOX's measured response for every brush
 *   drawn from format 1.2; strokes drawn before brushes had one read as [TiltResponse.Eased], the only response
 *   there was.
 * @property speedDamping how much speed thins the line as BOOX's fountain pen does: the width is divided by
 *   `1 + speedDamping * speed`, with speed in document pixels per millisecond; 0 (every stroke drawn before) ignores
 *   it. Unlike [speedInfluence], it keeps thinning as the pen goes faster.
 * @property minWidth the thinnest line in document pixels, whatever the pressure and speed, but never more than
 *   [width]; 0 (every stroke drawn before) for none. BOOX's fountain pen never draws thinner than 2 px. BOOX works in
 *   page pixels, which are screen pixels at 100 %; Nib keeps the floor in document pixels, which are screen pixels at
 *   100 % too, so it's 2 screen px there and scales with the zoom like the rest of the stroke.
 * @property dot for the stipple pencil, how big its dots are, in document pixels: the page's lattice of cells, each on or
 *   off, is this size. A stroke is committed with the dots the display drew while it was previewed, one screen pixel, so
 *   `1 / zoom` at the zoom it's drawn at ([inkAt]); zooming then scales the grain with the page, like any image, and at
 *   the zoom it was drawn at it's the preview's dots exactly. 1 (every stroke drawn before) is one document pixel.
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
    val tiltScale: Float = NO_TILT,
    val tiltResponse: TiltResponse = TiltResponse.Native,
    val speedDamping: Float = 0f,
    val minWidth: Float = 0f,
    val dot: Float = 1f,
) {
    /** The allowed width range for this kind. */
    val widthRange: ClosedFloatingPointRange<Float> get() = widthRange(kind)

    /** A copy with [w] clamped to [widthRange]. */
    fun withWidth(w: Float): BrushSpec = copy(width = if (w.isNaN()) width else w.coerceIn(widthRange))

    /** True when a tilted pen changes this brush's line (see [tiltScale]). */
    val usesTilt: Boolean get() = tiltScale > 1f

    /**
     * Everything the display needs to preview a stroke of this brush in [color] at [viewScale] (see [HardwarePreview]):
     * the style (Pencil becomes Fountain when thin; styles that aren't [HardwareStyle.verified] fall back when
     * [verifiedOnly]), the width in view pixels, the colour (opaque, or as [marker] has it for the marker style) and
     * the style's parameters from this brush's settings, with a nib's angle as it shows on a page turned clockwise by
     * [viewRotation] radians.
     */
    fun hardwarePreview(
        color: Int,
        viewScale: Float,
        verifiedOnly: Boolean = false,
        marker: MarkerPreview = MarkerPreview.SolidColour,
        viewRotation: Float = 0f,
    ): HardwarePreview {
        val style = preview.styleFor(width, viewScale, verifiedOnly)
        val px = preview.widthPx(width, viewScale)
        val argb = preview.argb(color).let { if (style == HardwareStyle.Marker) marker.adapt(it) else it }
        return HardwarePreview(style, px, argb, DisplayParams.of(style, this, px, viewRotation))
    }

    /**
     * The display's parameters for this brush's preview style as [hardwarePreview] resolves it, as a plain array for
     * `setStrokeParameters(style, params)` or the tip of the pen's button configuration ([PenButtons.tip]); empty
     * when the style takes none.
     */
    fun displayParams(viewScale: Float = 1f, verifiedOnly: Boolean = false, viewRotation: Float = 0f): FloatArray =
        hardwarePreview(OPAQUE_BLACK, viewScale, verifiedOnly, viewRotation = viewRotation).paramsArray()

    /** The largest radius any point of a stroke can reach, in document pixels. */
    val maxRadius: Float
        get() {
            val tilt = if (usesTilt) TiltShading.maxWidthFactor(tiltScale, tiltResponse) else 1f
            return max(width * 0.5f * curve.maxFactor * tilt, widthFloor * 0.5f)
        }

    /** The width no point of a stroke is drawn thinner than (see [minWidth]): 0 when there is none. */
    val widthFloor: Float get() = if (minWidth > 0f && width > 0f) min(minWidth, width) else 0f

    /**
     * The brush a stroke drawn at [viewScale] (screen pixels per document pixel) is committed with, so that its ink is
     * what the display previewed: this brush, except for the fountain pen and the pencil.
     *
     * The display's fountain style draws a stroke it's sent `w` screen pixels wide the way BOOX's fountain pen draws one
     * `w + 3` wide. BOOX Notes therefore sends the display the pen's width but draws its own fountain ink 3 screen pixels
     * wider (`FountainShapes.createNeoPenV2`, whose constant is named `FOUNTAIN_PEN_V1_COMPENSATION`), with its 2 px
     * floor and speed measured on screen at the zoom the stroke is drawn at. The fountain pen here does the same: its
     * ink gets [FOUNTAIN_DISPLAY_PAD_PX] screen pixels more, and its floor and speed thinning are taken on screen, while
     * the display is still sent the pen's own width ([hardwarePreview]). Without it the preview stood about
     * `3 · pressure^(2s)` pixels wider than the ink that replaced it, and swelled with pressure while the ink, held at its
     * floor, hardly did (owner's report, Note Air6 C, FW 4.3, 2026-10-06; `docs/09-ink.md` › *Measured pens*).
     *
     * The pencil's ink is the stipple the display's charcoal v2 style draws: a stamp of `1.16 w + 5` screen pixels for
     * the `w` it's sent ([app.booxultimatum.nib.engine.render.Stipple.diameter]), `w` being exactly what [hardwarePreview]
     * sends, made of dots one screen pixel across. Its stroke is committed with that diameter, in document pixels at the
     * zoom it's drawn at, as its width, and with those dots, `1 / zoom` document pixels across, as its [dot], so it is the
     * preview at that zoom and, at any other, the same stroke scaled with the page.
     */
    fun inkAt(viewScale: Float): BrushSpec {
        val z = if (viewScale.isFinite() && viewScale > 0f) viewScale else 1f
        if (kind.rendersAsStipple) return copy(width = Stipple.diameter(preview.widthPx(width, z), z), dot = (1f / z).coerceIn(MIN_DOT, MAX_DOT))
        if (kind != BrushKind.Fountain || preview.style != HardwareStyle.Fountain) return this
        return copy(
            width = width + FOUNTAIN_DISPLAY_PAD_PX / z,
            minWidth = if (minWidth > 0f) minWidth / z else 0f,
            speedDamping = speedDamping * z,
        )
    }

    companion object {
        private const val OPAQUE_BLACK = -0x1000000

        /** The range of [dot]: one screen pixel at the furthest zoom the canvas allows (a quarter) and at the nearest (sixteen times). */
        const val MIN_DOT = 1f / 16f
        const val MAX_DOT = 4f

        /** The [tiltScale] that ignores tilt. */
        const val NO_TILT = 1f

        /** The broadest [tiltScale] honoured. */
        const val MAX_TILT_SCALE = 6f

        /** The display's charcoal `tiltScale` on the Note Air6 C, which Nib's tilting brushes start from. */
        const val NATIVE_TILT_SCALE = 3f

        /** The native fountain pen's default smoothing (the display's `smoothLevel`). */
        const val NATIVE_FOUNTAIN_SMOOTHING = 0.6f

        /**
         * BOOX's fountain pen thins with speed: its width is divided by `1 + 0.077 * speed` in pixels per millisecond,
         * so 0.99 of it when slow, 0.965 at 0.5 px/ms and 0.76 at 4 px/ms (see [speedDamping]).
         */
        const val NATIVE_FOUNTAIN_SPEED_DAMPING = 0.077f

        /** BOOX's fountain pen never draws thinner than 2 px (see [minWidth] for the units). */
        const val NATIVE_FOUNTAIN_MIN_WIDTH = 2f

        /**
         * How much wider, in screen pixels, the display's fountain style draws a stroke than the width it's sent, and so
         * how much wider BOOX Notes draws its fountain ink than the width it sends (see [inkAt]).
         */
        const val FOUNTAIN_DISPLAY_PAD_PX = 3f

        /** BOOX's pencil lays each dab at `0.6 * pressure` alpha, at a constant width. */
        const val NATIVE_PENCIL_FLOW = 0.6f

        /**
         * BOOX's pencil lays a dab about every 0.27 of its width: 0.58, 1.13 and 2.13 px apart at widths of 2, 4 and 8
         * (0.27 is the least-squares fit through the origin).
         */
        const val NATIVE_PENCIL_SPACING = 0.27f

        /**
         * How much pressure drives BOOX's charcoal: its stamps' mean alpha is 16, 28, 44 and 55 of 255 at pressures
         * 0.1, 0.3, 0.7 and 1, and `1 - 0.75 + 0.75 * pressure` of the full-pressure alpha is within 2 of those.
         */
        const val NATIVE_CHARCOAL_PRESSURE_FLOW = 0.75f

        /**
         * The display's brush style draws `2 * width * sqrt(pressure)` of the width it's sent, as BOOX's brush does, so
         * a brush previewed in it sends half its full-pressure width.
         */
        const val NEO_BRUSH_PREVIEW_FACTOR = 0.5f

        /** The display's brush style's pressure response on a brush's full-pressure width: `sqrt(pressure)`. */
        val NEO_BRUSH_CURVE = PressureCurve(0.5f, 0f, 1f)

        /**
         * BOOX's marker's pressure response, measured on a marker W wide: 0.8W up to pressure 0.2, 0.9W at 0.5 and W
         * from 0.8. This straight line through 0.8W and W is within 0.04W of each.
         */
        val MARKER_CURVE = PressureCurve(1f, 0.8f, 1f)

        /** The alpha BOOX's marker lays its opaque stroke down at: 128 of 255. */
        const val MARKER_OPACITY = 128f / 255f

        /** The allowed width range for [kind], in document pixels. */
        fun widthRange(kind: BrushKind): ClosedFloatingPointRange<Float> = when (kind) {
            BrushKind.Fineliner -> 0.5f..40f
            BrushKind.Fountain -> 0.5f..60f
            BrushKind.Ballpoint -> 0.5f..24f
            BrushKind.Pencil -> 0.5f..40f
            BrushKind.GrainPencil -> 0.5f..60f
            BrushKind.Graphite -> 0.5f..24f
            BrushKind.Marker -> 4f..200f
            BrushKind.Highlighter -> 4f..200f
            BrushKind.BrushPen -> 1f..80f
            BrushKind.Calligraphy -> 1f..80f
            BrushKind.CalligraphyAsian -> 1f..80f
            BrushKind.NeoBrush -> 1f..100f
            BrushKind.Charcoal -> 1f..100f
            BrushKind.CharcoalV2 -> 1f..120f
            BrushKind.Dash -> 0.5f..40f
            BrushKind.SquarePen -> 0.5f..60f
            BrushKind.Airbrush -> 4f..400f
            BrushKind.PixelEraser -> 2f..400f
            BrushKind.StrokeEraser -> 2f..200f
            BrushKind.LassoEraser -> 1f..8f
        }

        /** The factory settings for [kind]. */
        fun defaults(kind: BrushKind): BrushSpec = when (kind) {
            // BOOX's plain pen: a constant-width round path, previewed in the pencil style.
            BrushKind.Fineliner -> BrushSpec(
                kind, width = 2f, smoothing = 0.35f,
                preview = Preview(HardwareStyle.Pencil),
            )
            // BOOX's fountain pen as BOOX Notes draws its final ink, measured: max(2 px, width * v * pressure^(2s)), with
            // the display's own sensitivity s 0.3 and smoothing 0.6, and v = 1 / (1 + 0.077 * speed in px/ms).
            BrushKind.Fountain -> BrushSpec(
                kind, width = 3f, curve = PressureCurve.ofSensitivity(PressureCurve.NATIVE_FOUNTAIN_SENSITIVITY),
                smoothing = NATIVE_FOUNTAIN_SMOOTHING,
                speedDamping = NATIVE_FOUNTAIN_SPEED_DAMPING, minWidth = NATIVE_FOUNTAIN_MIN_WIDTH,
                preview = Preview(HardwareStyle.Fountain),
            )
            // BOOX's ballpoint keeps its width whatever the pressure (measured), and BOOX Notes previews it in the pencil
            // style, which draws the width it's sent. The fountain style, which previewed it until 0.3, draws 3 px wider.
            BrushKind.Ballpoint -> BrushSpec(
                kind, width = 1.5f, smoothing = 0.3f, opacity = 0.88f,
                preview = Preview(HardwareStyle.Pencil),
            )
            // Nib's pencil: BOOX's 1-bit stipple, as the display's charcoal v2 style previews it, measured (Stipple). The
            // width is the one the display is sent, as BOOX Notes' own pen widths are; the ink is a stamp of 1.16 times
            // it plus 5 screen pixels ([inkAt]). Pressure sets how much of the line is covered, never its width, and tilt
            // broadens it by the display's own law without making it lighter.
            BrushKind.Pencil -> BrushSpec(
                kind, width = 2.5f, smoothing = 0.3f,
                tiltScale = NATIVE_TILT_SCALE,
                preview = Preview(HardwareStyle.CharcoalV2),
            )
            // The pencil before 0.3: BOOX's library pencil (pressure and speed sensitivity 0), measured: every dab the
            // full width whatever the pressure, speed or tilt, at 0.6 * pressure alpha, on the line without scatter and
            // each turned to a random angle. Kept only so strokes drawn with it look as they did.
            BrushKind.GrainPencil -> BrushSpec(
                kind, width = 2.5f, smoothing = 0.3f,
                spacing = NATIVE_PENCIL_SPACING, flow = NATIVE_PENCIL_FLOW, pressureFlow = 1f,
                preview = Preview(HardwareStyle.Pencil),
            )
            // Nib's own finer, harder pencil: a constant width like BOOX's, so its preview in the display's pencil style
            // (a line at the width sent) matches, lighter and denser, with grainier dabs. Like BOOX's pencil it ignores
            // tilt unless the owner gives it a tilt scale.
            BrushKind.Graphite -> BrushSpec(
                kind, width = 1.2f, smoothing = 0.3f,
                spacing = 0.2f, flow = 0.45f, grain = 0.6f, pressureFlow = 1f,
                preview = Preview(HardwareStyle.Pencil),
            )
            // BOOX's marker: drawn opaque, then laid down at alpha 128, so it never darkens where it crosses itself. Its
            // width follows pressure a little (measured): 0.8 of it at the lightest touch, all of it at full pressure.
            BrushKind.Marker -> BrushSpec(
                kind, width = 16f, curve = MARKER_CURVE, smoothing = 0.4f, opacity = MARKER_OPACITY,
                preview = Preview(HardwareStyle.Marker),
            )
            // Previewed in the display's marker style too, so it narrows at a light touch as the marker does, with a round
            // nib as that style draws: the flat chisel it had until 0.3.0-test.8 made its ink as little as a third of the
            // preview's width across some directions (the owner's report, 2026-10-07). Strokes drawn before keep theirs.
            BrushKind.Highlighter -> BrushSpec(
                kind, width = 20f, curve = MARKER_CURVE, smoothing = 0.5f, blend = Blend.Multiply,
                nibAngle = (PI / 2 - 0.35).toFloat(), minRatio = 1f,
                preview = Preview(HardwareStyle.Marker),
            )
            // BOOX's brush, as the display's brush style previews it: its width follows the square root of pressure, and
            // the display, which draws up to twice the width it's sent, gets half. No taper and no thinning with speed,
            // which the preview can't draw (they made its ink thinner than the preview until 0.3.0-test.8); strokes drawn
            // before keep theirs.
            BrushKind.BrushPen -> BrushSpec(
                kind, width = 6f, curve = NEO_BRUSH_CURVE, smoothing = 0.45f,
                preview = Preview(HardwareStyle.NeoBrush, widthFactor = NEO_BRUSH_PREVIEW_FACTOR),
            )
            // BOOX's Latin calligraphy: a flat nib at +45 degrees, whatever the pressure, as BOOX's square pen draws it and the
            // display's square pen style previews it (its width followed pressure from 0.7 until 0.3.0-test.10).
            BrushKind.Calligraphy -> BrushSpec(
                kind, width = 8f, smoothing = 0.4f,
                nibAngle = (PI / 4).toFloat(), minRatio = 0.12f,
                preview = Preview(HardwareStyle.SquarePen),
            )
            // BOOX's Asian calligraphy: the other diagonal, -45 degrees, kept as the same nib turned half a turn (135).
            BrushKind.CalligraphyAsian -> BrushSpec(
                kind, width = 8f, smoothing = 0.4f,
                nibAngle = (3 * PI / 4).toFloat(), minRatio = 0.12f,
                preview = Preview(HardwareStyle.SquarePen),
            )
            // BOOX's brush, measured: 2 * configured width * sqrt(pressure), whatever the speed, with no taper. Nib's
            // width is the full-pressure width, so the display, which draws up to twice what it's sent, gets half.
            BrushKind.NeoBrush -> BrushSpec(
                kind, width = 8f, curve = NEO_BRUSH_CURVE, smoothing = 0.45f,
                preview = Preview(HardwareStyle.NeoBrush, widthFactor = NEO_BRUSH_PREVIEW_FACTOR),
            )
            // BOOX's charcoal, measured: pressure sets how dark it lays, not how wide, and tilt broadens it by
            // 1 + tiltScale * g(tilt) without making it lighter.
            BrushKind.Charcoal -> BrushSpec(
                kind, width = 8f, smoothing = 0.35f,
                spacing = 0.15f, flow = 0.35f, grain = 0.6f, jitter = 0.2f, pressureFlow = NATIVE_CHARCOAL_PRESSURE_FLOW,
                tiltScale = NATIVE_TILT_SCALE,
                preview = Preview(HardwareStyle.Charcoal),
            )
            BrushKind.CharcoalV2 -> BrushSpec(
                kind, width = 12f, smoothing = 0.35f,
                spacing = 0.12f, flow = 0.3f, grain = 0.75f, jitter = 0.35f, pressureFlow = NATIVE_CHARCOAL_PRESSURE_FLOW,
                tiltScale = NATIVE_TILT_SCALE,
                preview = Preview(HardwareStyle.CharcoalV2),
            )
            BrushKind.Dash -> BrushSpec(
                kind, width = 2f, smoothing = 0.35f, dashOn = 3f, dashOff = 2.2f,
                preview = Preview(HardwareStyle.Dash),
            )
            // A square nib, previewed in the square pen style, which ignores pressure (it followed pressure from 0.6 until
            // 0.3.0-test.10).
            BrushKind.SquarePen -> BrushSpec(
                kind, width = 4f, smoothing = 0.35f,
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
