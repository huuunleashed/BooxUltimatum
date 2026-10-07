package app.booxultimatum.nib.engine.render

import app.booxultimatum.nib.engine.BLACK
import app.booxultimatum.nib.engine.RecordingSink
import app.booxultimatum.nib.engine.RecordingSink.Call
import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.brush.TiltResponse
import app.booxultimatum.nib.engine.doc.PackedPoints
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.index.HitTest
import app.booxultimatum.nib.engine.input.PressureCurve
import app.booxultimatum.nib.engine.line
import app.booxultimatum.nib.engine.stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Nib's brushes against BOOX's own pens, measured with penlab on the firmware's pen library driven as BOOX Notes drives
 * it (Note Air6 C, FW 4.3). Widths are full stroke widths in pixels, measured as penlab's `widths.py` does: the median
 * over the middle 60 % of a straight stroke at constant pressure and speed.
 */
class NativePenRenderTest {
    private val deg = (PI / 180).toFloat()

    /** A straight stroke along y = 100: [n] points [step] px apart every [dt] ms, all at [pressure] and [tilt]. */
    private fun straight(brush: BrushSpec, pressure: Float = 1f, tilt: Float = 0f, step: Float = 3f, dt: Long = 6L, n: Int = 61): Stroke {
        val b = PackedPoints.Builder()
        for (i in 0 until n) b.add(20f + step * i, 100f, pressure, tilt, 0.3f, if (i == 0) 0L else dt)
        return Stroke(7, brush, BLACK, b.build())
    }

    private fun radii(s: Stroke): List<Float> {
        val c = StrokeRenderer.centreline(s)
        return (0 until c.size / 3).map { c[3 * it + 2] }
    }

    /** The full width over the middle 60 % of the stroke's centreline (the median). */
    private fun width(s: Stroke): Float {
        val r = radii(s)
        val from = (r.size * 0.2).toInt()
        val mid = r.subList(from, maxOf((r.size * 0.8).toInt(), from + 1)).sorted()
        return 2f * mid[mid.size / 2]
    }

    private fun dabs(s: Stroke) = RecordingSink().also { StrokeRenderer.render(s, it) }.calls.filterIsInstance<Call.Dab>()

    private fun inkedRows(s: Stroke, x: Int): Int {
        val r = SoftwareRaster(260, 200)
        StrokeRenderer.render(s, r)
        return (0 until 200).count { r.alpha(x, it) > 0.02f }
    }

    @Test
    fun theFountainDrawsBooxsWidthAtEverySensitivity() {
        // Configured 11 px wide (BOOX Notes' 8 px pen plus the 3 it adds), 0.5 px/ms: sensitivity to widths at
        // pressures 0.05, 0.2, 0.5, 0.8 and 1.
        val measured = mapOf(
            0f to listOf(10.61f, 10.61f, 10.61f, 10.61f, 10.61f),
            0.15f to listOf(4.32f, 6.54f, 8.61f, 9.92f, 10.61f),
            0.3f to listOf(2.00f, 4.04f, 7.00f, 9.28f, 10.61f),
            0.5f to listOf(2.00f, 2.12f, 5.30f, 8.48f, 10.61f),
            0.75f to listOf(2.00f, 2.00f, 3.75f, 7.59f, 10.61f),
            1f to listOf(2.00f, 2.00f, 2.65f, 6.79f, 10.61f),
        )
        val pressures = listOf(0.05f, 0.2f, 0.5f, 0.8f, 1f)
        for ((s, widths) in measured) {
            val brush = BrushSpec.defaults(BrushKind.Fountain).copy(width = 11f, curve = PressureCurve.ofSensitivity(s))
            for ((p, w) in pressures.zip(widths)) assertEquals(w, width(straight(brush, pressure = p)), 0.03f, "sensitivity $s at pressure $p")
        }
    }

    @Test
    fun theFountainThinsWithSpeedAsBooxsDoes() {
        val brush = BrushSpec.defaults(BrushKind.Fountain).copy(width = 11f)
        // Sensitivity 0.3, pressure 0.5, a point every 8 ms: 0.0625, 0.5, 1, 2 and 4 px/ms.
        for ((step, w) in listOf(0.5f to 7.22f, 4f to 7.00f, 8f to 6.73f, 16f to 6.26f, 32f to 5.55f)) {
            assertEquals(w, width(straight(brush, pressure = 0.5f, step = step, dt = 8L)), 0.04f, "$step px every 8 ms")
        }
        assertEquals(11f * 0.5f.pow(0.6f), width(straight(brush, pressure = 0.5f, dt = 0L)), 1e-3f, "without timing nothing thins")
        val before = brush.copy(speedDamping = 0f)
        assertEquals(width(straight(before, step = 0.5f, dt = 8L)), width(straight(before, step = 32f, dt = 8L)), "a fountain pen from before ignores speed")
    }

    @Test
    fun aFountainPenDrawsTheInkTheDisplayPreviews() {
        // BOOX Notes sends the display a fountain pen w wide and draws its ink at w + 3 (penlab, 0.5 px/ms): the widths
        // its pens of 2, 4 and 8 px lay down at pressures 0.05, 0.2, 0.5, 0.8 and 1. A Nib fountain pen as wide, drawn
        // at 100 %, lays down the same.
        val measured = mapOf(
            2f to listOf(2.00f, 2.00f, 3.18f, 4.22f, 4.82f),
            4f to listOf(2.00f, 2.57f, 4.45f, 5.90f, 6.75f),
            8f to listOf(2.00f, 4.04f, 7.00f, 9.28f, 10.61f),
        )
        val pressures = listOf(0.05f, 0.2f, 0.5f, 0.8f, 1f)
        for ((w, widths) in measured) {
            val pen = BrushSpec.defaults(BrushKind.Fountain).withWidth(w)
            for ((p, expected) in pressures.zip(widths)) {
                assertEquals(expected, width(straight(pen.inkAt(1f), pressure = p, step = 4f, dt = 8L)), 0.04f, "$w px at pressure $p")
            }
        }
        // At 200 % the same pen covers twice the screen, so it's drawn as a 4 px pen would be at 100 %, on screen: its
        // pad, floor and speed are screen pixels, and the stroke's document pixels are half as many.
        val pen = BrushSpec.defaults(BrushKind.Fountain).withWidth(2f)
        for ((p, expected) in pressures.zip(measured.getValue(4f))) {
            val doc = width(straight(pen.inkAt(2f), pressure = p, step = 2f, dt = 8L))
            assertEquals(expected, doc * 2f, 0.04f, "2 px at 200 %, pressure $p")
        }
    }

    @Test
    fun theFountainNeverDrawsThinnerThanTwoPixels() {
        val brush = BrushSpec.defaults(BrushKind.Fountain).copy(width = 11f)
        val light = straight(brush, pressure = 0.01f)
        assertEquals(2f, width(light), 1e-4f)
        assertTrue(radii(light).all { it >= 1f }, "anywhere along the stroke")
        assertEquals(1f, HitTest.radiusAt(light, 30), 1e-5f, "and it can be erased where it inks")
        assertEquals(1.5f, width(straight(brush.copy(width = 1.5f), pressure = 0.01f)), 1e-4f, "a pen thinner than the floor keeps its own width")
        assertTrue(width(straight(brush.copy(minWidth = 0f), pressure = 0.01f)) < 1f, "a fountain pen from before has no floor")
    }

    @Test
    fun theNeoBrushIsBooxsBrush() {
        // BOOX's brush configured 8 px wide draws 2 * 8 * sqrt(pressure), whatever the speed; Nib's width is the
        // full-pressure width, 16.
        val brush = BrushSpec.defaults(BrushKind.NeoBrush).copy(width = 16f)
        for ((p, w) in listOf(0.05f to 3.58f, 0.2f to 7.16f, 0.5f to 11.31f, 0.8f to 14.31f, 1f to 16f)) {
            assertEquals(w, width(straight(brush, pressure = p)), 0.01f, "pressure $p")
            assertEquals(w, width(straight(brush, pressure = p, step = 32f, dt = 8L)), 0.01f, "fast, at pressure $p")
        }
        val r = radii(straight(brush, pressure = 0.5f))
        assertEquals(r[r.size / 2], r.first(), 1e-5f, "no taper")
        assertEquals(r[r.size / 2], r.last(), 1e-5f, "no taper")
        assertEquals(8f, brush.hardwarePreview(BLACK, 1f).widthPx, "the display is sent BOOX's configured width")
        val pen = BrushSpec.defaults(BrushKind.BrushPen).copy(width = 16f, taper = 0f, speedInfluence = 0f)
        for (p in listOf(0.05f, 0.2f, 0.5f, 0.8f, 1f)) {
            assertEquals(width(straight(brush, pressure = p)), width(straight(pen, pressure = p)), 1e-4f, "the brush pen follows the brush style's pressure at $p")
        }
        assertEquals(3f, BrushSpec.defaults(BrushKind.BrushPen).hardwarePreview(BLACK, 1f).widthPx, "so is the brush pen's, which the display draws in the same style")
    }

    @Test
    fun theHighlighterNarrowsAsTheMarkerDoes() {
        val brush = BrushSpec.defaults(BrushKind.Highlighter)
        val full = width(straight(brush, pressure = 1f))
        for ((p, share) in listOf(0.05f to 0.8f, 0.2f to 0.8f, 0.5f to 0.9f, 0.8f to 1f)) {
            assertEquals(share, width(straight(brush, pressure = p)) / full, 0.05f, "pressure $p")
        }
    }

    @Test
    fun theMarkerNarrowsALittleAtALightTouch() {
        // BOOX's marker 16 px wide: 12.8, 12.8, 14.4, 16 and 16 at pressures 0.05, 0.2, 0.5, 0.8 and 1, whatever the speed.
        val brush = BrushSpec.defaults(BrushKind.Marker)
        assertEquals(16f, brush.width)
        for ((p, w) in listOf(0.05f to 12.8f, 0.2f to 12.8f, 0.5f to 14.4f, 0.8f to 16f, 1f to 16f)) {
            assertEquals(w, width(straight(brush, pressure = p)), 0.05f * 16f, "pressure $p")
            assertEquals(width(straight(brush, pressure = p)), width(straight(brush, pressure = p, step = 32f, dt = 8L)), "speed is ignored")
        }
        assertEquals(14.4f, width(straight(brush, pressure = 0.5f)), 1e-4f)
    }

    @Test
    fun theBallpointKeepsItsWidth() {
        val brush = BrushSpec.defaults(BrushKind.Ballpoint).copy(width = 8f)
        for (p in listOf(0.05f, 0.2f, 0.5f, 0.8f, 1f)) for (step in listOf(0.5f, 4f, 32f)) {
            assertEquals(8f, width(straight(brush, pressure = p, step = step, dt = 8L)), 1e-5f, "pressure $p, $step px every 8 ms")
        }
    }

    @Test
    fun thePencilIsBooxsPencil() {
        // BOOX's pencil with BOOX Notes' settings (pressure and speed sensitivity 0), 2, 4 and 8 px wide: every stamp the
        // full width at pressures 0.05 to 1, at an opacity of 0.03, 0.12, 0.3, 0.48 and 0.6 (0.6 * pressure), stamps
        // 0.58, 1.13 and 2.13 px apart on the path, each turned to a random angle; tilt and speed ignored.
        val opacity = listOf(0.05f to 0.03f, 0.2f to 0.12f, 0.5f to 0.3f, 0.8f to 0.48f, 1f to 0.6f)
        for ((w, gap) in listOf(2f to 0.58f, 4f to 1.13f, 8f to 2.13f)) {
            val brush = BrushSpec.defaults(BrushKind.GrainPencil).copy(width = w)
            for ((p, a) in opacity) {
                val d = dabs(straight(brush, pressure = p))
                assertTrue(d.all { it.radius * 2f == w }, "$w px: every stamp the full width at pressure $p")
                assertTrue(d.all { abs(it.alpha - a) < 1e-5f }, "$w px: every stamp at $a at pressure $p")
                assertTrue(d.all { it.y == 100f }, "$w px: on the path, without scatter")
            }
            val d = dabs(straight(brush, pressure = 0.5f))
            val steps = d.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.sorted()
            assertEquals(gap, steps[steps.size / 2], 0.06f, "$w px: stamps as far apart as BOOX's")
            val angles = d.map { it.angle }
            assertTrue(angles.all { it >= 0f && it < 2f * PI.toFloat() }, "$w px: angles in a full turn")
            assertTrue(angles.distinct().size > angles.size * 0.9, "$w px: each stamp turned to its own angle")
            assertEquals(d, dabs(straight(brush, pressure = 0.5f, tilt = 70f * deg)), "$w px: BOOX Notes turns the pencil's tilt off")
            assertEquals(listOf(w / 2f), dabs(straight(brush, pressure = 0.5f, step = 32f, dt = 8L)).map { it.radius }.distinct(), "$w px: speed changes nothing")
        }
        assertTrue(!BrushSpec.defaults(BrushKind.GrainPencil).usesTilt)
    }

    @Test
    fun graphiteIsAFinerHarderPencilThatKeepsItsWidth() {
        val graphite = BrushSpec.defaults(BrushKind.Graphite).copy(width = 4f)
        for (p in listOf(0.05f, 0.5f, 1f)) {
            val d = dabs(straight(graphite, pressure = p))
            assertTrue(d.all { it.radius == 2f }, "a constant width at pressure $p, as its pencil-style preview draws")
            assertTrue(d.all { it.y == 100f }, "and no scatter beyond it")
        }
        val full = dabs(straight(graphite, pressure = 1f))
        val half = dabs(straight(graphite, pressure = 0.5f))
        assertEquals(0.5, half.sumOf { it.alpha.toDouble() } / full.sumOf { it.alpha.toDouble() }, 1e-4, "opacity in proportion to pressure")
        val pencil = dabs(straight(BrushSpec.defaults(BrushKind.GrainPencil).copy(width = 4f), pressure = 1f))
        assertTrue(full.map { it.alpha }.average() < pencil.map { it.alpha }.average(), "lighter than the pencil")
        assertTrue(BrushSpec.defaults(BrushKind.Graphite).width < BrushSpec.defaults(BrushKind.GrainPencil).width, "and finer")
    }

    @Test
    fun charcoalPressureSetsHowDarkNotHowWide() {
        // Upright, BOOX's charcoal stamps have a mean alpha of 16, 28, 44 and 55 of 255 at pressures 0.1, 0.3, 0.7 and 1.
        for (kind in listOf(BrushKind.Charcoal, BrushKind.CharcoalV2)) {
            val brush = BrushSpec.defaults(kind).copy(width = 8f)
            val full = dabs(straight(brush, pressure = 1f))
            for ((p, alpha) in listOf(0.1f to 16.0, 0.3f to 28.0, 0.7f to 44.0)) {
                val d = dabs(straight(brush, pressure = p))
                assertEquals(full.map { it.radius }, d.map { it.radius }, "$kind: the same size at pressure $p")
                val ratio = d.sumOf { it.alpha.toDouble() } / full.sumOf { it.alpha.toDouble() }
                assertEquals(alpha / 55.0, ratio, 0.035, "$kind: as dark as BOOX's at pressure $p")
            }
        }
    }

    @Test
    fun aTiltedCharcoalBroadensAsBooxsDoesAndStaysAsDark() {
        // Tilt scale 3: 1 + 3 * g, with g measured at these angles from upright.
        val g = listOf(0 to 0f, 10 to 0f, 20 to 0.067f, 30 to 0.2f, 40 to 0.4f, 45 to 0.51f, 50 to 0.71f, 60 to 1.04f, 70 to 1.49f, 75 to 1.76f, 80 to 2.02f)
        for (kind in listOf(BrushKind.Charcoal, BrushKind.CharcoalV2)) {
            val brush = BrushSpec.defaults(kind).copy(width = 8f, jitter = 0f)
            assertEquals(TiltResponse.Native, brush.tiltResponse)
            val upright = straight(brush, pressure = 0.5f)
            for ((d, gd) in g) {
                val expected = 1f + 3f * gd
                assertEquals(expected, width(straight(brush, pressure = 0.5f, tilt = d * deg)) / width(upright), expected * 0.01f, "$kind at $d degrees")
            }
            val laid = straight(brush, pressure = 0.5f, tilt = 75f * deg)
            val a0 = dabs(upright).map { it.alpha }.average()
            val a1 = dabs(laid).map { it.alpha }.average()
            assertEquals(a0, a1, a0 * 0.1, "$kind is as dark laid down: $a0 -> $a1")
            assertTrue(inkedRows(laid, 100) > inkedRows(upright, 100) * 3, "$kind covers more paper")
            val bounds = StrokeRenderer.bounds(laid)
            assertTrue(bounds.height >= 8f * (1f + 3f * 1.76f), "$kind bounds grow with it: $bounds")
            assertEquals(4f * (1f + 3f * 2.02f), HitTest.radiusAt(straight(brush, tilt = 80f * deg), 5), 0.2f, "$kind is hit where it inks")
        }
    }

    @Test
    fun pencilsIgnoreTiltUnlessTheOwnerGivesThemATiltScale() {
        for (kind in listOf(BrushKind.GrainPencil, BrushKind.Graphite)) {
            val brush = BrushSpec.defaults(kind).copy(width = 8f, jitter = 0f)
            assertEquals(dabs(straight(brush)), dabs(straight(brush, tilt = 70f * deg)), "$kind previews in the pencil style, which doesn't broaden")
            val tuned = brush.copy(tiltScale = 3f)
            assertEquals(1f + 3f * 1.49f, width(straight(tuned, tilt = 70f * deg)) / width(straight(tuned)), 0.05f, "$kind given a tilt scale tilts as BOOX's charcoal")
        }
    }

    @Test
    fun strokesFromBeforeKeepTheEasedTilt() {
        // A 0.3.0-test graphite stroke: tilt scale 2.5 with the eased response, broader and lighter from 30 degrees.
        val brush = BrushSpec.defaults(BrushKind.Graphite).copy(width = 8f, jitter = 0f, tiltScale = 2.5f, tiltResponse = TiltResponse.Eased)
        val upright = straight(brush)
        val laid = straight(brush, tilt = 70f * deg)
        assertEquals(2.5f, width(laid) / width(upright), 1e-3f, "broader by its tilt scale")
        val a0 = dabs(upright).map { it.alpha }.average()
        val a1 = dabs(laid).map { it.alpha }.average()
        assertTrue(a1 < a0 * 0.75, "and lighter: $a0 -> $a1")
        assertTrue(inkedRows(laid, 100) > inkedRows(upright, 100) * 2, "covering more paper")
        assertEquals(dabs(upright), dabs(straight(brush, tilt = 25f * deg)), "held as for writing, it draws as if upright")
    }

    @Test
    fun brushesWithoutTiltIgnoreIt() {
        val kinds = listOf(
            BrushKind.Fineliner, BrushKind.Fountain, BrushKind.Ballpoint, BrushKind.GrainPencil, BrushKind.Graphite, BrushKind.Marker,
            BrushKind.Highlighter, BrushKind.NeoBrush, BrushKind.BrushPen, BrushKind.Calligraphy,
        )
        for (kind in kinds) {
            val brush = BrushSpec.defaults(kind)
            assertEquals(radii(straight(brush, pressure = 0.6f)), radii(straight(brush, pressure = 0.6f, tilt = 80f * deg)), "$kind ignores tilt")
        }
        val charcoal = BrushSpec.defaults(BrushKind.Charcoal)
        assertEquals(dabs(straight(charcoal)), dabs(straight(charcoal, tilt = 8f * deg)), "within 10 degrees of upright charcoal draws as if upright")
        val off = charcoal.copy(tiltScale = BrushSpec.NO_TILT)
        assertEquals(dabs(straight(off)), dabs(straight(off, tilt = 80f * deg)), "charcoal with tilt off")
    }

    @Test
    fun aTiltedStrokeIsErasedWhereItInks() {
        val path = line(20f, 100f, 180f, 100f, 41)
        val eraser = floatArrayOf(60f, 90f, 140f, 90f)
        val brush = BrushSpec.defaults(BrushKind.Charcoal).copy(width = 8f)
        fun tilted(tilt: Float): Stroke {
            val b = PackedPoints.Builder()
            for (i in 0 until path.size / 2) b.add(path[2 * i], path[2 * i + 1], 1f, tilt, 0.3f, if (i == 0) 0L else 4L)
            return Stroke(7, brush, BLACK, b.build())
        }
        assertTrue(!HitTest.hitsEraser(tilted(0f), eraser, 2, 2f), "upright: 4 px either side")
        assertTrue(HitTest.hitsEraser(tilted(70f * deg), eraser, 2, 2f), "laid down: 22 px either side")
    }

    @Test
    fun theMarkerIsDrawnOpaqueAndLaidDownAtAlpha128() {
        val calls = RecordingSink().also { StrokeRenderer.render(stroke(1, BrushKind.Marker, line(20f, 50f, 180f, 50f, 20), color = 0xFFD2232A.toInt()), it) }.calls
        assertEquals(Call.Begin(128f / 255f, Blend.Normal), calls.first())
        assertEquals(0xFFD2232A.toInt(), (calls[1] as Call.Path).color, "opaque inside the group")
        assertEquals(Call.End, calls.last())
        val r = SoftwareRaster(200, 100)
        StrokeRenderer.render(stroke(1, BrushKind.Marker, floatArrayOf(20f, 50f, 180f, 50f, 180f, 30f, 100f, 30f, 100f, 80f), width = 16f), r)
        assertEquals(128f / 255f, r.alpha(60, 50), 0.01f)
        assertEquals(128f / 255f, r.alpha(100, 50), 0.01f, "no darker where it crosses itself")
    }

    @Test
    fun asianCalligraphyIsThinOnTheOtherDiagonal() {
        fun ink(kind: BrushKind, x0: Float, y0: Float, x1: Float, y1: Float): Int =
            SoftwareRaster(200, 200).also { StrokeRenderer.render(stroke(1, kind, line(x0, y0, x1, y1, 40), width = 16f), it) }.inkedPixels()
        val latinRising = ink(BrushKind.Calligraphy, 50f, 150f, 150f, 50f)
        val latinFalling = ink(BrushKind.Calligraphy, 50f, 50f, 150f, 150f)
        val asianRising = ink(BrushKind.CalligraphyAsian, 50f, 150f, 150f, 50f)
        val asianFalling = ink(BrushKind.CalligraphyAsian, 50f, 50f, 150f, 150f)
        assertTrue(latinFalling > 3 * latinRising, "Latin: thin rising to the right ($latinRising vs $latinFalling)")
        assertTrue(asianRising > 3 * asianFalling, "Asian: thin falling to the right ($asianFalling vs $asianRising)")
        assertEquals(latinRising.toFloat(), asianFalling.toFloat(), latinRising * 0.05f, "mirror images")
    }
}
