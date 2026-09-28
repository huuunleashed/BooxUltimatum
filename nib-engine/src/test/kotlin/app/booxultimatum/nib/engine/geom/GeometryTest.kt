package app.booxultimatum.nib.engine.geom

import kotlin.math.PI
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class GeometryTest {
    @Test
    fun boxOperations() {
        val a = Box(0f, 0f, 10f, 10f)
        val b = Box(5f, 5f, 15f, 20f)
        assertEquals(Box(0f, 0f, 15f, 20f), a.union(b))
        assertEquals(Box(5f, 5f, 10f, 10f), a.intersect(b))
        assertTrue(a.intersects(b))
        assertFalse(a.intersects(Box(10f, 0f, 20f, 10f)), "touching edges don't overlap")
        assertEquals(Box.EMPTY, a.intersect(Box(20f, 20f, 30f, 30f)))
        assertEquals(Box(-1f, -1f, 11f, 11f), a.inflate(1f))
        assertTrue(a.contains(0f, 0f))
        assertFalse(a.contains(10f, 5f), "right edge is exclusive")
        assertTrue(a.contains(Box(1f, 1f, 9f, 9f)))
        assertFalse(a.contains(b))
        assertTrue(Box.EMPTY.isEmpty)
        assertTrue(Box(0f, 0f, 0f, 5f).isEmpty)
        assertTrue(Box(0f, 0f, Float.NaN, 5f).isEmpty)
        assertEquals(a, Box.EMPTY.union(a))
        assertEquals(a, a.union(Box.EMPTY))
        assertEquals(Box.EMPTY, Box.EMPTY.inflate(3f))
        assertEquals(Box(1f, 2f, 5f, 7f), Box.of(floatArrayOf(5f, 2f, 1f, 7f, 3f, 3f), 3))
        assertEquals(Box.around(2f, 3f, 1f), Box(1f, 2f, 3f, 4f))
    }

    @Test
    fun pointInPolygon() {
        val square = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertTrue(Geometry.pointInPolygon(5f, 5f, square))
        assertFalse(Geometry.pointInPolygon(15f, 5f, square))
        // A U shape: the notch is outside.
        val u = floatArrayOf(0f, 0f, 3f, 0f, 3f, 7f, 7f, 7f, 7f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertFalse(Geometry.pointInPolygon(5f, 3f, u))
        assertTrue(Geometry.pointInPolygon(1f, 3f, u))
        assertTrue(Geometry.pointInPolygon(5f, 9f, u))
        assertFalse(Geometry.pointInPolygon(1f, 1f, floatArrayOf(0f, 0f, 2f, 2f), 2), "fewer than 3 points is no polygon")
    }

    @Test
    fun distances() {
        assertEquals(5f, Geometry.distanceToSegment(0f, 5f, -10f, 0f, 10f, 0f), 1e-5f)
        assertEquals(5f, Geometry.distanceToSegment(13f, 4f, 0f, 0f, 10f, 0f), 1e-5f, "beyond the end measures to the end")
        assertEquals(1f, Geometry.distanceToSegment(3f, 1f, 3f, 0f, 3f, 0f), 1e-5f, "degenerate segment is a point")
        val poly = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f)
        assertEquals(2f, Geometry.distanceToPolyline(12f, 5f, poly), 1e-5f)
        assertEquals(Float.POSITIVE_INFINITY, Geometry.distanceToPolyline(0f, 0f, FloatArray(0), 0))
        assertEquals(0f, Geometry.polylineDistance(line(0f, -5f, 0f, 5f), 2, line(-5f, 0f, 5f, 0f), 2))
        assertEquals(3f, Geometry.polylineDistance(line(0f, 0f, 10f, 0f), 2, line(0f, 3f, 10f, 3f), 2), 1e-5f)
        assertEquals(5f, Geometry.polylineDistance(floatArrayOf(0f, 0f), 1, floatArrayOf(3f, 4f), 1), 1e-5f)
        assertTrue(Geometry.segmentsIntersect(0f, 0f, 10f, 10f, 0f, 10f, 10f, 0f))
        assertFalse(Geometry.segmentsIntersect(0f, 0f, 1f, 1f, 2f, 2f, 3f, 5f))
        assertTrue(Geometry.segmentsIntersect(0f, 0f, 4f, 0f, 2f, 0f, 6f, 0f), "collinear overlap")
        assertEquals(100f, Geometry.polygonArea(floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)), 1e-4f)
    }

    @Test
    fun segmentDistanceMatchesSampling() {
        val rnd = Random(7)
        repeat(200) {
            val s = FloatArray(8) { rnd.nextFloat() * 20f }
            val exact = kotlin.math.sqrt(Geometry.segmentDistSq(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7]))
            var sampled = Float.POSITIVE_INFINITY
            for (i in 0..200) {
                val t = i / 200f
                val px = s[0] + (s[2] - s[0]) * t
                val py = s[1] + (s[3] - s[1]) * t
                sampled = minOf(sampled, Geometry.distanceToSegment(px, py, s[4], s[5], s[6], s[7]))
            }
            assertTrue(exact <= sampled + 1e-3f && exact >= sampled - 0.2f, "exact $exact sampled $sampled")
        }
    }

    @Test
    fun affineComposeAndInvert() {
        val t = Affine.translate(5f, -3f)
        val s = Affine.scale(2f, 3f, pivotX = 1f, pivotY = 1f)
        val r = Affine.rotate((PI / 2).toFloat(), pivotX = 10f, pivotY = 10f)
        assertEquals(Vec(6f, -1f), t.map(Vec(1f, 2f)))
        assertEquals(Vec(1f, 1f), s.map(Vec(1f, 1f)), "the pivot stays put")
        assertEquals(Vec(3f, 4f), s.map(Vec(2f, 2f)))
        val rotated = r.map(Vec(11f, 10f))
        assertEquals(10f, rotated.x, 1e-4f)
        assertEquals(11f, rotated.y, 1e-4f, "a quarter turn is clockwise on a y-down screen")
        val chain = t.then(s).then(r)
        val p = Vec(3.5f, -7.25f)
        val expected = r.map(s.map(t.map(p)))
        val got = chain.map(p)
        assertEquals(expected.x, got.x, 1e-3f)
        assertEquals(expected.y, got.y, 1e-3f)
        val inv = chain.invert()!!
        val back = inv.map(got)
        assertEquals(p.x, back.x, 1e-3f)
        assertEquals(p.y, back.y, 1e-3f)
        assertNull(Affine.scale(0f, 1f).invert())
        assertTrue(Affine.IDENTITY.isIdentity)
        assertEquals(6f, s.meanScale * s.meanScale, 1e-4f)
        assertEquals(Box(0f, 0f, 2f, 3f), Affine.scale(2f, 3f).mapBox(Box(0f, 0f, 1f, 1f)))
        assertEquals(9, t.toMatrixValues().size)
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float) = floatArrayOf(x0, y0, x1, y1)
}
