package app.booxultimatum.nib.engine.render

import app.booxultimatum.nib.engine.BLACK
import app.booxultimatum.nib.engine.RecordingSink
import app.booxultimatum.nib.engine.RecordingSink.Call
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.brush.HardwareStyle
import app.booxultimatum.nib.engine.brush.TiltShading
import app.booxultimatum.nib.engine.doc.PackedPoints
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.geom.Affine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Nib's pencil against BOOX's charcoal v2 stipple as penlab measured it (Note Air6 C, FW 4.3, 2026-10-07; the numbers
 * are in `docs/09-ink.md` › *Measured pens*).
 */
class StippleTest {
    private val pencil = BrushSpec.defaults(BrushKind.Pencil)
    private val deg = (PI / 180).toFloat()

    /** A horizontal stroke through y = [y], committed as the canvas commits it at [zoom]. */
    private fun line(width: Float, pressure: Float, zoom: Float = 1f, tilt: Float = 0f, x0: Float = 20f, y: Float = 100f, n: Int = 80): Stroke {
        val b = PackedPoints.Builder()
        for (i in 0 until n) b.add(x0 + 2f * i, y, pressure, tilt, 0f, if (i == 0) 0L else 4L)
        return Stroke(11, pencil.copy(width = width).inkAt(zoom), BLACK, b.build())
    }

    private fun raster(s: Stroke, scale: Float = 1f, size: Int = 220): SoftwareRaster =
        SoftwareRaster((size * scale).roundToInt(), (size * scale).roundToInt(), Affine(scaleX = scale, scaleY = scale)).also { StrokeRenderer.render(s, it) }

    private fun inked(r: SoftwareRaster): Set<Pair<Int, Int>> {
        val out = HashSet<Pair<Int, Int>>()
        for (y in 0 until r.height) for (x in 0 until r.width) if (r.alpha(x, y) > 0f) out.add(x to y)
        return out
    }

    /** The share of pixels on in rows [y0]..[y1] over the stroke's middle. */
    private fun cover(r: SoftwareRaster, y0: Int, y1: Int, x0: Int = 60, x1: Int = 150): Float {
        var on = 0
        var n = 0
        for (y in y0..y1) for (x in x0 until x1) {
            n++
            if (r.alpha(x, y) > 0f) on++
        }
        return on.toFloat() / n
    }

    @Test
    fun everyPixelIsFullyOnOrOff() {
        val r = raster(line(4f, 0.5f))
        for (y in 0 until r.height) for (x in 0 until r.width) {
            val a = r.alpha(x, y)
            assertTrue(a == 0f || a == 1f, "pixel $x,$y is $a: the display's stipple is never grey")
        }
    }

    @Test
    fun theCentreCoversThePressureAsTheDisplaysDoes() {
        // penlab, on the centre line: 0.20 at 0.2, 0.53 at 0.5, 0.87 at 0.85, all of it at 1.
        for (p in listOf(0.2f, 0.5f, 0.85f)) {
            assertEquals(p, cover(raster(line(4f, p)), 98, 101, 30, 170), 0.04f, "pressure $p")
        }
        assertEquals(1f, cover(raster(line(4f, 1f)), 98, 101), "solid at full pressure")
        assertEquals(0f, cover(raster(line(4f, 0f)), 90, 110), "nothing without pressure")
    }

    @Test
    fun theStampIsSolidToSixTenthsOfItsRadiusThenFades() {
        // A 4 px pencil is sent 4 px and drawn 1.16 * 4 + 5 = 9.64 px across: solid out to 2.9 px, nothing past 4.8.
        val r = raster(line(4f, 1f))
        assertEquals(1f, cover(r, 98, 101), "the solid core")
        val edge = cover(r, 104, 104)
        assertTrue(edge > 0f && edge < 0.6f, "fading at the edge: $edge")
        assertEquals(0f, cover(r, 106, 110), "nothing beyond the stamp")
        assertEquals(0f, cover(r, 90, 94), "on either side")
    }

    @Test
    fun theStampIsAsWideAsTheDisplaysForEveryWidthPenlabMeasured() {
        // penlab's charcoal v2 stamps, in pixels, for the widths it was sent; the fit is 1.16 w + 5.
        val measured = listOf(0.5f to 6, 1f to 7, 1.5f to 7, 2f to 7, 3f to 8, 4f to 9, 6f to 12, 8f to 14, 10f to 16, 16f to 24, 24f to 33)
        for ((w, size) in measured) {
            val d = Stipple.diameter(w, 1f)
            assertTrue(abs(d - size) <= 1f, "$w px: drawn $d across, BOOX's stamp is $size")
        }
    }

    @Test
    fun aLighterStrokeIsASubsetOfADarkerOneAtTheSamePlace() {
        val light = inked(raster(line(6f, 0.3f)))
        val dark = inked(raster(line(6f, 0.7f)))
        assertTrue(light.isNotEmpty())
        assertTrue(dark.containsAll(light), "the page's threshold decides, so lighter pixels are among the darker ones")
        assertTrue(dark.size > 1.8f * light.size)
    }

    @Test
    fun theGrainStaysWithThePageWhenTheStrokeMoves() {
        // penlab: the same stroke 1 px further kept 0.96 of its pixels in place.
        val a = inked(raster(line(6f, 0.5f, x0 = 20f)))
        val b = inked(raster(line(6f, 0.5f, x0 = 21f)))
        val kept = a.count { it in b }.toFloat() / a.size
        assertTrue(kept > 0.9f, "kept $kept of its pixels in place")
        val again = inked(raster(line(6f, 0.5f)))
        assertEquals(a, again, "the same stroke gives the same pixels")
    }

    @Test
    fun overlappingStampsDontBuildUp() {
        val once = raster(line(4f, 0.4f))
        val b = PackedPoints.Builder()
        for (i in 0 until 160) {
            val x = if (i < 80) 20f + 2f * i else 178.01f - 2f * (i - 80)
            b.add(x, 100f, 0.4f, 0f, 0f, if (i == 0) 0L else 4L)
        }
        val twice = raster(Stroke(12, pencil.copy(width = 4f).inkAt(1f), BLACK, b.build()))
        assertEquals(cover(once, 98, 101, 30, 170), cover(twice, 98, 101, 30, 170), 0.02f, "going over the line again doesn't darken it")
    }

    @Test
    fun tiltBroadensItWithoutMakingItLighter() {
        val upright = raster(line(4f, 0.6f))
        val tilted = raster(line(4f, 0.6f, tilt = 45f * deg))
        val factor = TiltShading.widthFactor(45f * deg, pencil.tiltScale, pencil.tiltResponse)
        assertEquals(2.53f, factor, 0.1f, "the display's charcoal law at 45 degrees with a tilt scale of 3")
        assertTrue(cover(tilted, 108, 108) > 0.3f && cover(upright, 108, 108) == 0f, "broader")
        assertEquals(cover(upright, 99, 100), cover(tilted, 99, 100), 0.06f, "and no lighter")
    }

    @Test
    fun itsDotsStayOneScreenPixelAtAnyZoom() {
        // At 2x the display is sent what makes it draw twice the stamp a 4 px pencil has at 100 % (1.16 * 4 + 5 = 9.64 px),
        // 19.3 screen px: the committed width is the page's 9.64, and the dots are the screen's own.
        val s = line(4f, 0.5f, zoom = 2f)
        assertEquals(1.16f * 4f + 5f, s.brush.width, 1e-4f)
        val r = raster(s, scale = 2f)
        assertEquals(0.5f, cover(r, 198, 201, 120, 300), 0.05f, "the same cover at 2x")
        var lone = 0
        for (y in 196..204) for (x in 120 until 300) {
            if (r.alpha(x, y) > 0f && r.alpha(x + 1, y) == 0f && r.alpha(x - 1, y) == 0f) lone++
        }
        assertTrue(lone > 50, "single-pixel dots, not 2 x 2 blocks: $lone")
    }

    @Test
    fun thePreviewIsTheDisplaysCharcoalV2WithTilt() {
        val p = pencil.copy(width = 3f).hardwarePreview(BLACK, 2f)
        assertEquals(HardwareStyle.CharcoalV2, p.style)
        assertEquals((Stipple.diameter(3f, 1f) * 2f - Stipple.SIZE_BASE) / Stipple.SIZE_SLOPE, p.widthPx, 1e-4f, "sent what draws twice its stamp at 100 %: its width times the zoom, plus the display's 5 px base less once at 2x")
        assertEquals(listOf(1f, BrushSpec.NATIVE_TILT_SCALE), p.params, "tilt on, at the display's own scale")
        assertTrue(pencil.curve.isConstant, "pressure sets the cover, never the width")
        assertEquals(1f, pencil.opacity)
    }

    @Test
    fun theRendererLaysStampsAtThePressure() {
        val sink = RecordingSink()
        StrokeRenderer.render(line(4f, 0.35f), sink)
        val stamps = sink.calls.filterIsInstance<Call.Stipple>()
        assertTrue(stamps.size > 100, "close enough to merge: ${stamps.size}")
        assertTrue(stamps.all { abs(it.density - 0.35f) < 1e-4f })
        assertTrue(stamps.all { abs(2f * it.radius - Stipple.diameter(4f, 1f)) < 1e-3f })
        assertTrue(sink.calls.none { it is Call.Dab || it is Call.Path }, "stipple only")
    }

    @Test
    fun aRunLightsEachPixelOfItsStampsOnce() {
        // A stroke's stamps at once, as the canvas lays them, against the same stamps one by one.
        val xs = FloatArray(60) { 30f + 1.4f * it }
        val ys = FloatArray(60) { 40f + 6f * kotlin.math.sin(it * 0.3f) }
        val rs = FloatArray(60) { 4f + (it % 5) * 0.3f }
        val ds = FloatArray(60) { 0.2f + 0.8f * (it % 7) / 6f }
        for (toDevice in listOf(Affine(scaleX = 0.78f, scaleY = 0.78f, transX = 3f, transY = -2f), Affine(scaleX = 2f, scaleY = 2f), Affine.IDENTITY)) {
            val inverse = toDevice.invert()!!
            val single = HashSet<Pair<Int, Int>>()
            for (i in xs.indices) Stipple.stamp(xs[i], ys[i], rs[i], ds[i], toDevice, inverse, 500, 500) { x, y -> single.add(x to y) }
            val run = ArrayList<Pair<Int, Int>>()
            Stipple.stamps(xs, ys, rs, ds, xs.size, toDevice, inverse, 500, 500) { x, y -> run.add(x to y) }
            assertEquals(single, run.toSet(), "the same pixels at $toDevice")
            assertEquals(run.size, run.toSet().size, "each pixel once")
        }
    }

    @Test
    fun theFastRunAgreesWithThePlainFormula() {
        // Each pixel is on where the page's threshold there is below the strongest stamp over it: density * falloff(distance / radius).
        val xs = FloatArray(90) { 12f + 1.7f * it }
        val ys = FloatArray(90) { 60f + 14f * kotlin.math.sin(it * 0.15f) }
        val rs = FloatArray(90) { 3.5f + (it % 6) * 0.45f }
        val ds = FloatArray(90) { 0.15f + 0.85f * (it % 11) / 10f }
        for (toDevice in listOf(
            Affine(scaleX = 0.78f, scaleY = 0.78f, transX = -5f, transY = 3f),
            Affine(scaleX = 1.41421f, scaleY = 1.41421f, transX = 10f, transY = -20f),
            Affine(scaleX = 2.8284f, scaleY = 2.8284f, transX = -20f, transY = -100f),
        )) {
            val size = 400
            val run = HashSet<Pair<Int, Int>>()
            Stipple.stamps(xs, ys, rs, ds, xs.size, toDevice, toDevice.invert()!!, size, size) { x, y -> run.add(x to y) }
            val plain = HashSet<Pair<Int, Int>>()
            val s = toDevice.meanScale
            for (py in 0 until size) for (px in 0 until size) {
                for (i in xs.indices) {
                    val dx = px + 0.5f - toDevice.mapX(xs[i], ys[i])
                    val dy = py + 0.5f - toDevice.mapY(xs[i], ys[i])
                    val u = kotlin.math.sqrt(dx * dx + dy * dy) / (rs[i] * s)
                    val level = ds[i] * Stipple.falloff(u)
                    val gx = kotlin.math.floor(px + 0.5f - toDevice.transX).toInt()
                    val gy = kotlin.math.floor(py + 0.5f - toDevice.transY).toInt()
                    if (level > 0f && Stipple.threshold(gx, gy) < level) {
                        plain.add(px to py)
                        break
                    }
                }
            }
            assertTrue(plain.size > 500, "a real run to compare: ${plain.size}")
            // Only a pixel exactly on a stamp's edge or threshold can differ, by the last bit of a float.
            val differ = (run - plain).size + (plain - run).size
            assertTrue(differ <= plain.size / 1000, "$differ of ${plain.size} pixels differ at $toDevice")
        }
    }

    @Test
    fun zoomingScalesTheGrainWithThePage() {
        // Drawn at 2x, the dots are half a document pixel: one screen pixel there, as the display drew them. Seen at 4x each
        // is two pixels across, and the same dots, the very same ones, are on: the stroke is the same picture, magnified.
        val s = line(5f, 0.5f, zoom = 2f)
        assertEquals(0.5f, s.brush.dot)
        val drawn = inked(raster(s, scale = 2f))
        val magnified = inked(raster(s, scale = 4f))
        assertTrue(drawn.size > 400, "a real stroke to compare: ${drawn.size}")
        assertEquals(4 * drawn.size, magnified.size, "every dot is a 2 x 2 block")
        assertTrue(drawn.all { (x, y) -> (2 * x to 2 * y) in magnified && (2 * x + 1 to 2 * y + 1) in magnified }, "and sits where it was, doubled")
        val r = raster(s, scale = 3f)
        var odd = 0
        var n = 0
        for (y in 0 until r.height step 3) for (x in 0 until r.width - 3 step 3) {
            // At 3x a dot is 1.5 pixels: a pixel and its neighbour agree more often than not.
            n++
            if ((r.alpha(x, y) > 0f) != (r.alpha(x + 1, y) > 0f)) odd++
        }
        assertTrue(odd < n / 8, "dots are bigger than pixels at 3x: $odd of $n pairs differ")
    }

    @Test
    fun aStrokeKeepsItsToneAtAnyZoom() {
        // The cover at the centre of the line is the pressure, whatever the zoom it's looked at, in the dots it was drawn with.
        val s = line(4f, 0.5f, zoom = 1.5f)
        for (scale in listOf(0.75f, 1.5f, 3f)) {
            val r = raster(s, scale = scale)
            val y = (100f * scale).roundToInt()
            assertEquals(0.5f, cover(r, y - 1, y + 1, (60 * scale).roundToInt(), (150 * scale).roundToInt()), 0.1f, "at $scale")
        }
    }

    @Test
    fun aStrokeFromBeforeDotsAreOneDocumentPixel() {
        val s = Stroke(11, pencil.copy(width = 9f), BLACK, line(4f, 0.5f).points)
        assertEquals(1f, s.brush.dot)
        val a = inked(raster(s, scale = 1f))
        val b = inked(raster(s, scale = 2f))
        assertEquals(4 * a.size, b.size, "scaled with the page at 2x, as every stroke is")
    }}
