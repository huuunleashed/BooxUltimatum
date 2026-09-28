package app.booxultimatum.nib.engine.doc

import app.booxultimatum.nib.engine.BLACK
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.geom.Affine
import app.booxultimatum.nib.engine.geom.Box
import app.booxultimatum.nib.engine.input.InputSample
import kotlin.math.PI
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

class PackedPointsTest {
    @Test
    fun packsEachChannel() {
        val rnd = Random(3)
        val samples = (0 until 500).map {
            InputSample(
                rnd.nextFloat() * 2000f - 100f,
                rnd.nextFloat() * 3000f,
                rnd.nextFloat(),
                rnd.nextFloat() * (PI / 2).toFloat(),
                rnd.nextFloat() * (2 * PI).toFloat() - PI.toFloat(),
                it * 2_380_000L,
            )
        }
        val p = PackedPoints.of(samples)
        assertEquals(500, p.size)
        for (i in samples.indices) {
            val s = samples[i]
            assertEquals(s.x, p.x(i), 1f / 128f + 1e-4f)
            assertEquals(s.y, p.y(i), 1f / 128f + 1e-4f)
            assertEquals(p.x(i), PackedPoints.quantize(p.x(i)), "positions sit on the grid")
            assertEquals(s.pressure, p.pressure(i), 1f / 65535f)
            assertEquals(s.tilt, p.tilt(i), (PI / 2 / 255).toFloat())
            val turn = (s.orientation - p.orientation(i) + 3 * PI.toFloat()) % (2 * PI.toFloat()) - PI.toFloat()
            assertEquals(0f, turn, (PI / 128).toFloat() + 1e-5f, "orientation is kept up to a full turn")
        }
        assertEquals(0, p.deltaMillis(0))
        assertTrue((1 until 500).all { p.deltaMillis(it) in 2..3 }, "2.38 ms steps round to 2 or 3")
        assertEquals(p.bounds, Box.of(p.toXY(), p.size))
    }

    @Test
    fun clampsAndSaturates() {
        val b = PackedPoints.Builder()
        b.add(Float.NaN, 1e9f, 2f, -1f, 100f, 100_000L)
        b.add(-1e9f, 0f, -1f, 9f, Float.NaN, -5L)
        val p = b.build()
        assertEquals(0f, p.x(0))
        assertEquals(PackedPoints.MAX_COORD, p.y(0))
        assertEquals(-PackedPoints.MAX_COORD, p.x(1))
        assertEquals(1f, p.pressure(0))
        assertEquals(0f, p.pressure(1))
        assertEquals(0f, p.tilt(0))
        assertEquals((PI / 2).toFloat(), p.tilt(1), 1e-5f)
        assertEquals(65535, p.deltaMillis(0))
        assertEquals(0, p.deltaMillis(1))
        assertEquals(0f, p.orientation(1))
        val o = PackedPoints.Builder().add(0f, 0f, 1f, 0f, PI.toFloat()).build()
        assertEquals(-PI.toFloat(), o.orientation(0), 1e-5f, "PI wraps to -PI, the same direction")
    }

    @Test
    fun equalityAndTransforms() {
        val a = PackedPoints.Builder().add(1f, 2f, 0.5f, 0f, 0.5f, 0L).add(3f, 4f, 1f, 0f, 0.5f, 3L).build()
        val b = PackedPoints.Builder().add(1f, 2f, 0.5f, 0f, 0.5f, 0L).add(3f, 4f, 1f, 0f, 0.5f, 3L).build()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, PackedPoints.Builder().add(1f, 2f, 0.5f).build())
        val moved = a.transformed(Affine.translate(10f, -1f))
        assertEquals(11f, moved.x(0))
        assertEquals(1f, moved.y(0))
        assertEquals(a.orientation(0), moved.orientation(0), "translation keeps orientation")
        assertEquals(a.deltaMillis(1), moved.deltaMillis(1))
        val turned = a.transformed(Affine.rotate((PI / 2).toFloat()))
        assertEquals(-2f, turned.x(0), 1e-2f)
        assertEquals(1f, turned.y(0), 1e-2f)
        assertEquals(0.5f + (PI / 2).toFloat(), turned.orientation(0), (PI / 128).toFloat())
        assertEquals(3L, a.durationMillis)
    }

    @Test
    fun strokeTransformsScaleWidthAndTurnTheNib() {
        val pts = PackedPoints.Builder().add(0f, 0f, 1f).add(10f, 0f, 1f).build()
        val s = Stroke(1, BrushSpec.defaults(BrushKind.Calligraphy).copy(width = 8f), BLACK, pts)
        val scaled = s.transformed(Affine.scale(2f))
        assertEquals(16f, scaled.brush.width)
        assertEquals(s.brush.nibAngle, scaled.brush.nibAngle)
        assertEquals(20f, scaled.points.x(1))
        val turned = s.transformed(Affine.rotate((PI / 2).toFloat()))
        assertEquals(8f, turned.brush.width, 1e-4f)
        // A clockwise quarter turn on screen lowers a counter-clockwise nib angle by PI/2.
        assertEquals(s.brush.nibAngle - (PI / 2).toFloat(), turned.brush.nibAngle, 1e-4f)
        assertEquals(s.id, turned.id)
        assertTrue(scaled.bounds.width > s.bounds.width * 1.8f)
    }
}
