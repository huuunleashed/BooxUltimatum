package app.booxultimatum.nib.engine.render

import app.booxultimatum.nib.engine.BLACK
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.circle
import app.booxultimatum.nib.engine.doc.PackedPoints
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.geom.Geometry
import app.booxultimatum.nib.engine.input.PressureCurve
import app.booxultimatum.nib.engine.line
import app.booxultimatum.nib.engine.scribble
import app.booxultimatum.nib.engine.stroke
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.assertTrue
import org.junit.Test

class OutlineTest {
    private val outlineKinds = listOf(
        BrushKind.Fineliner, BrushKind.Fountain, BrushKind.Ballpoint, BrushKind.Marker, BrushKind.Highlighter,
        BrushKind.BrushPen, BrushKind.Calligraphy, BrushKind.NeoBrush, BrushKind.SquarePen, BrushKind.PixelEraser,
    )

    private fun randomStrokes(seed: Int, count: Int): List<Stroke> {
        val rnd = Random(seed)
        return (0 until count).map { i ->
            val kind = outlineKinds[i % outlineKinds.size]
            val range = BrushSpec.widthRange(kind)
            val width = range.start + rnd.nextFloat() * (min(range.endInclusive, 40f) - range.start)
            scribble(rnd, i.toLong() + 1, kind, 20 + rnd.nextInt(200), 40f, 40f, 160f, 160f, width = width, step = 0.5f + rnd.nextFloat() * 8f)
        }
    }

    @Test
    fun outlineIsFiniteClosedAndWithinBounds() {
        for (s in randomStrokes(11, 80)) {
            val poly = StrokeRenderer.outline(s)
            val n = poly.size / 2
            assertTrue(n >= 3, "${s.brush.kind} outline has points")
            val bounds = StrokeRenderer.bounds(s)
            val cl = StrokeRenderer.centreline(s)
            var maxR = 0f
            var maxSeg = 0f
            for (i in 0 until cl.size / 3) {
                maxR = max(maxR, cl[3 * i + 2])
                if (i > 0) maxSeg = max(maxSeg, dist(cl[3 * i - 3], cl[3 * i - 2], cl[3 * i], cl[3 * i + 1]))
            }
            val maxEdge = 2.5f * maxR * 1.42f + maxSeg + 0.5f
            for (i in 0 until n) {
                val x = poly[2 * i]
                val y = poly[2 * i + 1]
                assertTrue(x.isFinite() && y.isFinite(), "no NaN in ${s.brush.kind}")
                assertTrue(bounds.contains(x, y), "${s.brush.kind} vertex ($x, $y) outside $bounds")
                val j = (i + 1) % n
                val edge = dist(x, y, poly[2 * j], poly[2 * j + 1])
                assertTrue(edge <= maxEdge, "${s.brush.kind} edge $i of $edge > $maxEdge: the ring is not continuous")
            }
        }
    }

    @Test
    fun outlineCoversItsCentreline() {
        for (s in randomStrokes(12, 60)) {
            val poly = StrokeRenderer.outline(s)
            val cl = StrokeRenderer.centreline(s)
            for (i in 0 until cl.size / 3) {
                assertTrue(winding(cl[3 * i], cl[3 * i + 1], poly) != 0, "${s.brush.kind} centre point $i uncovered")
            }
        }
    }

    @Test
    fun rasterMatchesTheDistanceField() {
        for (s in randomStrokes(13, 40).filter { it.brush.kind != BrushKind.SquarePen && it.brush.kind != BrushKind.PixelEraser }) {
            checkAgainstDistanceField(s)
        }
    }

    @Test
    fun tightCurlsHaveNoHoles() {
        // A 40 px marker circling a radius of 8 px: the inner offsets fold over and must not punch a hole.
        val s = stroke(1, BrushKind.Fineliner, circle(100f, 100f, 8f, 60, turns = 1.5f), width = 40f)
        val raster = SoftwareRaster(200, 200)
        StrokeRenderer.render(s, raster)
        assertTrue(raster.alpha(100, 100) > 0.99f, "centre of the curl is inked")
        checkAgainstDistanceField(s)
        val wide = stroke(2, BrushKind.Fountain, circle(100f, 100f, 3f, 40, turns = 3f), width = 30f)
        checkAgainstDistanceField(wide)
    }

    @Test
    fun hairpinsHaveNoSpikes() {
        val zigzag = FloatArray(40)
        for (i in 0 until 20) {
            zigzag[2 * i] = 60f + i * 4f
            zigzag[2 * i + 1] = if (i % 2 == 0) 60f else 140f
        }
        for (w in listOf(2f, 9f, 25f)) {
            checkAgainstDistanceField(stroke(1, BrushKind.Fineliner, zigzag, width = w))
        }
        val reversal = floatArrayOf(50f, 100f, 150f, 100f, 50f, 100.5f)
        checkAgainstDistanceField(stroke(3, BrushKind.Fineliner, reversal, width = 12f))
    }

    @Test
    fun pressureSpikesStayRound() {
        val pts = line(40f, 100f, 160f, 100f, 60)
        val b = PackedPoints.Builder(60)
        for (i in 0 until 60) b.add(pts[2 * i], pts[2 * i + 1], if (i == 30) 1f else 0.05f)
        val s = Stroke(1, BrushSpec.defaults(BrushKind.Fountain).copy(width = 40f, curve = PressureCurve.LINEAR), BLACK, b.build())
        checkAgainstDistanceField(s)
    }

    private fun checkAgainstDistanceField(s: Stroke) {
        val raster = SoftwareRaster(200, 200)
        StrokeRenderer.render(s, raster)
        val cl = StrokeRenderer.centreline(s)
        val n = cl.size / 3
        val bounds = StrokeRenderer.bounds(s)
        for (y in 0 until 200) for (x in 0 until 200) {
            val px = x + 0.5f
            val py = y + 0.5f
            val a = raster.alpha(x, y)
            if (!bounds.contains(px, py)) {
                assertTrue(a == 0f, "${s.brush.kind} ink at ($x, $y) outside bounds")
                continue
            }
            var inner = Float.POSITIVE_INFINITY
            var outer = Float.POSITIVE_INFINITY
            if (n == 1) {
                val d = dist(px, py, cl[0], cl[1])
                inner = d - cl[2]
                outer = d - cl[2]
            }
            for (i in 0 until n - 1) {
                val d = Geometry.distanceToSegment(px, py, cl[3 * i], cl[3 * i + 1], cl[3 * i + 3], cl[3 * i + 4])
                inner = min(inner, d - min(cl[3 * i + 2], cl[3 * i + 5]))
                outer = min(outer, d - max(cl[3 * i + 2], cl[3 * i + 5]))
            }
            if (inner < -1f) assertTrue(a > 0.5f * s.brush.opacity, "${s.brush.kind} w=${s.brush.width} hole at ($x, $y): alpha $a, depth $inner")
            if (outer > 1f) assertTrue(a < 0.02f, "${s.brush.kind} w=${s.brush.width} spill at ($x, $y): alpha $a, distance $outer")
        }
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        return sqrt(dx * dx + dy * dy)
    }

    private fun winding(x: Float, y: Float, poly: FloatArray): Int {
        val n = poly.size / 2
        var w = 0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = poly[2 * i]
            val ay = poly[2 * i + 1]
            val bx = poly[2 * j]
            val by = poly[2 * j + 1]
            val side = (bx - ax) * (y - ay) - (x - ax) * (by - ay)
            if (ay <= y) {
                if (by > y && side > 0f) w++
            } else if (by <= y && side < 0f) {
                w--
            }
        }
        return w
    }
}
