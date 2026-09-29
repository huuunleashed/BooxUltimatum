package app.booxultimatum.nib.engine.brush

import app.booxultimatum.nib.engine.input.PressureCurve
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class NativePensTest {
    private fun params(kind: BrushKind, viewScale: Float = 1f, rotation: Float = 0f, edit: (BrushSpec) -> BrushSpec = { it }): List<Float> =
        edit(BrushSpec.defaults(kind)).hardwarePreview(BLACK, viewScale, viewRotation = rotation).params

    private fun assertFloats(expected: List<Float>, actual: List<Float>, message: String? = null) {
        assertEquals(expected.size, actual.size, message)
        for (i in expected.indices) assertEquals(expected[i], actual[i], 1e-4f, message)
    }

    @Test
    fun eachPenPreviewsInTheStyleBooxUsesForIt() {
        val expected = mapOf(
            BrushKind.Fineliner to HardwareStyle.Pencil,
            BrushKind.Pencil to HardwareStyle.Pencil,
            BrushKind.Graphite to HardwareStyle.Pencil,
            BrushKind.Fountain to HardwareStyle.Fountain,
            BrushKind.Ballpoint to HardwareStyle.Fountain,
            BrushKind.Marker to HardwareStyle.Marker,
            BrushKind.Highlighter to HardwareStyle.Marker,
            BrushKind.BrushPen to HardwareStyle.NeoBrush,
            BrushKind.NeoBrush to HardwareStyle.NeoBrush,
            BrushKind.Charcoal to HardwareStyle.Charcoal,
            BrushKind.CharcoalV2 to HardwareStyle.CharcoalV2,
            BrushKind.Calligraphy to HardwareStyle.SquarePen,
            BrushKind.CalligraphyAsian to HardwareStyle.SquarePen,
            BrushKind.SquarePen to HardwareStyle.SquarePen,
            BrushKind.Dash to HardwareStyle.Dash,
        )
        for ((kind, style) in expected) {
            val b = BrushSpec.defaults(kind).withWidth(12f)
            assertEquals(style, b.preview.style, kind.id)
            assertEquals(style, b.hardwarePreview(BLACK, 1f).style, kind.id)
            val trusted = b.hardwarePreview(BLACK, 1f, verifiedOnly = true).style
            assertEquals(if (style == HardwareStyle.Dash) HardwareStyle.Fountain else style, trusted, "$kind needs a stand-in only for the dash")
        }
    }

    @Test
    fun theFinelinerIsExactlyBooxsPlainPen() {
        val b = BrushSpec.defaults(BrushKind.Fineliner)
        assertTrue(b.curve.isConstant, "constant width")
        assertEquals(1f, b.opacity)
        assertEquals(Blend.Normal, b.blend)
        assertEquals(0f, b.taper)
        assertEquals(0f, b.speedInfluence)
        assertTrue(!b.usesTilt && !b.kind.usesNib && !b.kind.rendersAsDabs, "a plain round path")
        val p = b.withWidth(4f).hardwarePreview(BLACK, 1f)
        assertEquals(HardwareStyle.Pencil, p.style)
        assertEquals(emptyList(), p.params, "the pencil style takes no parameters")
        val thin = b.withWidth(1f).hardwarePreview(BLACK, 1f)
        assertEquals(HardwareStyle.Pencil, thin.style, "the pencil style draws clean lines this thin, as BOOX's plain pen relies on")
        assertEquals(emptyList(), thin.params)
    }

    @Test
    fun theFountainPenCarriesTheDisplaysOwnSettings() {
        val b = BrushSpec.defaults(BrushKind.Fountain)
        assertEquals(PressureCurve.NATIVE_FOUNTAIN_SENSITIVITY, b.curve.sensitivity, 1e-5f)
        assertEquals(BrushSpec.NATIVE_FOUNTAIN_SMOOTHING, b.smoothing)
        val p = b.hardwarePreview(BLACK, 2f)
        assertEquals(HardwareStyle.Fountain, p.style)
        assertEquals(6f, p.widthPx, "width times zoom")
        assertFloats(listOf(0.3f, 0.6f), p.params)
        assertFloats(DisplayParams.displayDefaults(HardwareStyle.Fountain)!!, p.params, "the display's own defaults")
        assertFloats(listOf(0.5f, 0.2f), params(BrushKind.Fountain) { it.copy(curve = PressureCurve.ofSensitivity(0.5f), smoothing = 0.2f) })
        assertFloats(listOf(0.5f, 0.6f), params(BrushKind.Fountain) { it.copy(curve = PressureCurve.LINEAR) }, "width = pressure is BOOX's sensitivity 0.5")
        assertFloats(listOf(0f, 1f), params(BrushKind.Fountain) { it.copy(curve = PressureCurve.CONSTANT, smoothing = 3f) }, "clamped to 0..1")
        assertEquals(0f, params(BrushKind.Ballpoint)[0], "the ballpoint's preview keeps its width, as its ink does")
        assertTrue(p.paramsArray().contentEquals(p.params.toFloatArray()))
    }

    @Test
    fun pressureSensitivityRoundTripsThroughTheCurve() {
        for (s in listOf(0f, 0.1f, 0.3f, 0.75f, 1f)) assertEquals(s, PressureCurve.ofSensitivity(s).sensitivity, 1e-6f)
        assertEquals(PressureCurve.CONSTANT, PressureCurve.ofSensitivity(0f))
        assertEquals(PressureCurve(0.6f, 0f, 1f), PressureCurve.ofSensitivity(0.3f), "BOOX's fountain: pressure^(2s)")
        assertEquals(1f, PressureCurve.ofSensitivity(2f).sensitivity)
        assertEquals(0f, PressureCurve.ofSensitivity(Float.NaN).sensitivity)
        assertEquals(0.5f, PressureCurve.LINEAR.sensitivity)
        assertEquals(1f, PressureCurve(3f, 0f, 1f).sensitivity, "clamped")
        assertEquals(0.25f, PressureCurve(0.5f, 0f, 2f).sensitivity, "the ceiling doesn't change the shape")
        assertEquals(0f, PressureCurve(1f, 2f, 1f).sensitivity, "a curve that thickens with less pressure has none")
        val c = PressureCurve.ofSensitivity(0.3f)
        assertEquals(0f, c.factor(0f), 1e-6f)
        assertEquals(1f, c.factor(1f), 1e-6f)
        assertEquals(0.5f.pow(0.6f), c.factor(0.5f), 1e-6f)
        // Nib's fountain pen before 0.3 thinned linearly to 0.7 of its width; the display draws like it at half pressure.
        val before = PressureCurve(1f, 0.7f, 1f)
        assertEquals(before.factor(0.5f), 0.5f.pow(2f * before.sensitivity), 1e-5f)
        assertEquals(0.117f, before.sensitivity, 1e-3f)
        assertEquals(0.5f, PressureCurve.normalize(2048f, PressureCurve.DEVICE_PRESSURE_MAX))
    }

    @Test
    fun eachPenStartsFromBooxsMeasuredSettings() {
        val fountain = BrushSpec.defaults(BrushKind.Fountain)
        assertEquals(BrushSpec.NATIVE_FOUNTAIN_SPEED_DAMPING, fountain.speedDamping)
        assertEquals(BrushSpec.NATIVE_FOUNTAIN_MIN_WIDTH, fountain.minWidth)
        assertEquals(0f, fountain.speedInfluence)
        assertTrue(BrushSpec.defaults(BrushKind.Ballpoint).curve.isConstant)
        val pencil = BrushSpec.defaults(BrushKind.Pencil)
        assertTrue(pencil.curve.isConstant && !pencil.usesTilt, "BOOX's pencil: constant width, tilt off")
        assertEquals(1f, pencil.pressureFlow)
        assertEquals(BrushSpec.NATIVE_PENCIL_FLOW, pencil.flow)
        assertEquals(0f, pencil.grain)
        assertEquals(0f, pencil.jitter)
        assertEquals(BrushSpec.NATIVE_PENCIL_SPACING, pencil.spacing)
        assertEquals(PressureCurve(1f, 0.8f, 1f), BrushSpec.defaults(BrushKind.Marker).curve)
        assertEquals(PressureCurve(1f, 0.8f, 1f), BrushSpec.defaults(BrushKind.Highlighter).curve, "previewed in the marker style too")
        assertEquals(Blend.Multiply, BrushSpec.defaults(BrushKind.Highlighter).blend)
        val neo = BrushSpec.defaults(BrushKind.NeoBrush)
        assertEquals(PressureCurve(0.5f, 0f, 1f), neo.curve)
        assertEquals(0f, neo.taper)
        assertEquals(0f, neo.speedInfluence)
        val brushPen = BrushSpec.defaults(BrushKind.BrushPen)
        assertEquals(PressureCurve(0.5f, 0f, 1f), brushPen.curve, "the brush style's sqrt(pressure), so preview and ink agree")
        assertTrue(brushPen.taper > 0f && brushPen.speedInfluence > 0f, "with its own taper and speed")
        for (kind in listOf(BrushKind.NeoBrush, BrushKind.BrushPen)) assertEquals(BrushSpec.NEO_BRUSH_PREVIEW_FACTOR, BrushSpec.defaults(kind).preview.widthFactor)
        for (kind in listOf(BrushKind.Charcoal, BrushKind.CharcoalV2)) {
            val b = BrushSpec.defaults(kind)
            assertTrue(b.curve.isConstant, "$kind: pressure sets coverage, not size")
            assertEquals(BrushSpec.NATIVE_CHARCOAL_PRESSURE_FLOW, b.pressureFlow)
            assertEquals(BrushSpec.NATIVE_TILT_SCALE, b.tiltScale)
        }
        val graphite = BrushSpec.defaults(BrushKind.Graphite)
        assertTrue(!graphite.usesTilt, "previewed in the pencil style, which doesn't broaden: no tilt unless tuned")
        assertTrue(graphite.curve.isConstant, "a constant width, as its pencil-style preview draws")
        assertEquals(1f, graphite.pressureFlow, "pressure sets how dark, in proportion")
        assertEquals(0f, graphite.jitter, "no scatter wider than the preview")
        for (kind in BrushKind.entries) {
            val b = BrushSpec.defaults(kind)
            assertEquals(TiltResponse.Native, b.tiltResponse, "$kind tilts as BOOX's charcoal once given a tilt scale")
            if (kind != BrushKind.Fountain) {
                assertEquals(0f, b.speedDamping, kind.id)
                assertEquals(0f, b.minWidth, kind.id)
            }
            if (kind != BrushKind.Charcoal && kind != BrushKind.CharcoalV2) assertTrue(!b.usesTilt, "$kind ignores tilt")
        }
    }

    @Test
    fun theCharcoalCoverageFitStaysCloseToBooxs() {
        // Mean stamp alpha of BOOX's charcoal, upright, relative to full pressure: 16, 28, 44 and 55 of 255.
        val pf = BrushSpec.defaults(BrushKind.Charcoal).pressureFlow
        var worst = 0f
        for ((p, a) in listOf(0.1f to 16f, 0.3f to 28f, 0.7f to 44f, 1f to 55f)) worst = maxOf(worst, abs(1f - pf + pf * p - a / 55f))
        assertTrue(worst < 0.035f, "within 0.035 of full coverage, 2 of 255 in BOOX's alpha: $worst")
    }

    @Test
    fun charcoalsSendTheirTiltSettings() {
        assertFloats(listOf(1f, 3f), params(BrushKind.Charcoal))
        assertFloats(listOf(1f, 3f), params(BrushKind.CharcoalV2))
        assertFloats(DisplayParams.displayDefaults(HardwareStyle.Charcoal)!!, params(BrushKind.Charcoal), "BOOX's own setting")
        assertFloats(listOf(1f, 2f), params(BrushKind.Charcoal) { it.copy(tiltScale = 2f) })
        assertFloats(listOf(0f, 1f), params(BrushKind.Charcoal) { it.copy(tiltScale = BrushSpec.NO_TILT) }, "tilt off")
        assertFloats(listOf(1f, BrushSpec.MAX_TILT_SCALE), params(BrushKind.CharcoalV2) { it.copy(tiltScale = 50f) })
    }

    @Test
    fun calligraphySendsItsNibAsTheSquarePenDoes() {
        assertFloats(listOf(2f, 8f, 45f, 0.4f), params(BrushKind.Calligraphy), "Latin: +45 degrees")
        assertFloats(listOf(2f, 8f, -45f, 0.4f), params(BrushKind.CalligraphyAsian), "Asian: -45 degrees")
        assertFloats(listOf(2f, 10f, 45f, 0.4f), params(BrushKind.Calligraphy, viewScale = 3f), "the width is capped at 10")
        assertFloats(listOf(2f, 8f, -45f, 0.4f), params(BrushKind.Calligraphy, rotation = (PI / 2).toFloat()), "a page turned a quarter clockwise turns the nib too")
        assertFloats(listOf(2f, 4f, 45f, 0.35f), params(BrushKind.SquarePen))
        assertEquals(24f, BrushSpec.defaults(BrushKind.Calligraphy).hardwarePreview(BLACK, 3f).widthPx, "the stroke width itself isn't capped")
    }

    @Test
    fun stylesWithoutParametersSendNone() {
        for (kind in listOf(BrushKind.Pencil, BrushKind.BrushPen, BrushKind.NeoBrush, BrushKind.Marker, BrushKind.Highlighter, BrushKind.Airbrush)) {
            assertEquals(emptyList(), params(kind, viewScale = 4f), kind.id)
        }
        assertEquals(listOf(5f), BrushSpec.defaults(BrushKind.Dash).hardwarePreview(BLACK, 1f).params)
        assertEquals(listOf(1f, 16f), DisplayParams.displayDefaults(HardwareStyle.Marker), "the display's marker values, left alone")
        assertNull(DisplayParams.displayDefaults(HardwareStyle.SquarePen))
    }

    @Test
    fun nibAnglesBecomeDegreesWithinAHalfTurn() {
        assertEquals(45f, DisplayParams.nibDegrees((PI / 4).toFloat()))
        assertEquals(-45f, DisplayParams.nibDegrees((-PI / 4).toFloat()))
        assertEquals(-45f, DisplayParams.nibDegrees((3 * PI / 4).toFloat()))
        assertEquals(90f, DisplayParams.nibDegrees((PI / 2).toFloat()))
        assertEquals(90f, DisplayParams.nibDegrees((-PI / 2).toFloat()))
        assertEquals(0f, DisplayParams.nibDegrees(PI.toFloat()), 1e-3f)
        assertEquals(30f, DisplayParams.nibDegrees((PI / 6 + 4 * PI).toFloat()), 1e-3f)
        assertEquals(0f, DisplayParams.nibDegrees(Float.NaN))
    }

    @Test
    fun everyPreviewIsOpaqueUnlessTheMarkerAsksForGrey() {
        val colours = listOf(BLACK, 0x80D2232A.toInt(), 0x001F4FB8, 0xFFF2C300.toInt())
        for (kind in BrushKind.entries) for (c in colours) for (scale in listOf(0.5f, 1f, 4f)) {
            val p = BrushSpec.defaults(kind).hardwarePreview(c, scale)
            assertEquals(0xFF, p.argb ushr 24, "$kind in ${Integer.toHexString(c)} at $scale")
            if (BrushSpec.defaults(kind).preview.color == PreviewColor.Brush) assertEquals(c and 0xFFFFFF, p.argb and 0xFFFFFF, "colour passes unchanged")
            val grey = BrushSpec.defaults(kind).hardwarePreview(c, scale, marker = MarkerPreview.SeeThroughGrey)
            assertEquals(if (grey.style == HardwareStyle.Marker) Preview.MARKER_ALPHA else 0xFF, grey.argb ushr 24)
        }
    }

    @Test
    fun theAsianNibIsTheLatinOneMirrored() {
        val latin = BrushSpec.defaults(BrushKind.Calligraphy)
        val asian = BrushSpec.defaults(BrushKind.CalligraphyAsian)
        assertEquals(latin.copy(kind = BrushKind.CalligraphyAsian, nibAngle = asian.nibAngle), asian)
        assertEquals(BrushKind.CalligraphyAsian, BrushKind.fromId("calligraphy_asian"))
        assertEquals(BrushSpec.widthRange(BrushKind.Calligraphy), BrushSpec.widthRange(BrushKind.CalligraphyAsian))
    }

    @Test
    fun eachBrushGivesItsParametersAsAPlainArray() {
        assertTrue(floatArrayOf(1f, 3f).contentEquals(BrushSpec.defaults(BrushKind.Charcoal).displayParams()))
        assertTrue(floatArrayOf(2f, 8f, -45f, 0.4f).contentEquals(BrushSpec.defaults(BrushKind.CalligraphyAsian).displayParams()))
        assertEquals(0, BrushSpec.defaults(BrushKind.Pencil).displayParams(viewScale = 4f).size, "the pencil style takes none")
        for (kind in BrushKind.entries) for (scale in listOf(0.5f, 1f, 3f)) {
            val b = BrushSpec.defaults(kind)
            assertTrue(b.hardwarePreview(BLACK, scale).paramsArray().contentEquals(b.displayParams(scale)), "$kind at $scale")
        }
    }

    @Test
    fun theTipAndTheEraserEndOfThePen() {
        val tip = PenButtons.tip(BrushSpec.defaults(BrushKind.Fountain), 0x80D2232A.toInt(), 2f)
        assertEquals(1, tip.style)
        assertEquals(6f, tip.widthPx)
        assertEquals(0xFFD2232A.toInt(), tip.argb, "opaque")
        assertFloats(listOf(0.3f, 0.6f), tip.params.toList())
        val calligraphy = PenButtons.tip(BrushSpec.defaults(BrushKind.Calligraphy), BLACK, 1f, viewRotation = (PI / 2).toFloat())
        assertEquals(HardwareStyle.SquarePen.code, calligraphy.style)
        assertEquals(-45f, calligraphy.params[2], 1e-3f)

        val eraser = PenButtons.eraserEnd(24f)
        assertEquals(PenButtonConfig(8, 24f, -1, floatArrayOf(24f, 0.5f, 0.1f)), eraser)
        assertEquals(eraser.hashCode(), PenButtonConfig(8, 24f, -1, floatArrayOf(24f, 0.5f, 0.1f)).hashCode())
        eraser.params[0] = 99f
        assertEquals(24f, eraser.params[0], "the parameters can't be changed from outside")
        val pixel = PenButtons.eraserEnd(BrushSpec.defaults(BrushKind.PixelEraser), 0.5f)
        assertEquals(PenButtons.ERASER_STYLE, pixel.style)
        assertEquals(12f, pixel.widthPx, "the eraser's own width times the zoom")
        assertTrue(floatArrayOf(12f, 0.5f, 0.1f).contentEquals(pixel.params))
        assertEquals(1f, PenButtons.eraserEnd(Float.NaN).widthPx)
    }

    private companion object {
        const val BLACK = -0x1000000
    }
}
