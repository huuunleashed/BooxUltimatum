package app.booxultimatum.nib.editor

import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.input.InputSample
import app.booxultimatum.nib.engine.input.StrokeBuilder
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StraightLineTest {
    @Test fun thePenHeldStillIsNoticedAfterTheHold() {
        val d = HoldDetector(slop = 10f, holdMs = 500L)
        assertFalse(d.held(0L), "nothing yet")
        d.move(0f, 0f, 0L)
        d.move(100f, 0f, 100L)
        d.move(200f, 0f, 200L)
        assertFalse(d.held(650L), "moving until 200 ms, so only 450 ms still")
        // Jitter within the slop counts as still.
        d.move(203f, 2f, 400L)
        d.move(198f, -3f, 600L)
        assertTrue(d.held(700L))
        // A real move starts the wait again.
        d.move(260f, 0f, 750L)
        assertFalse(d.held(1000L))
        assertTrue(d.held(1250L))
        assertEquals(0L, d.remaining(1300L))
        assertEquals(400L, d.remaining(850L), "100 ms still of the 500")
        // A pen at rest that keeps reporting doesn't restart the wait; one that moves on does.
        assertFalse(d.move(262f, 1f, 1300L))
        assertTrue(d.move(300f, 0f, 1310L))
        d.reset()
        assertFalse(d.held(5000L))
    }

    @Test fun aHeldStrokeBecomesAStraightSegmentThatKeepsItsPressure() {
        val b = StrokeBuilder(BrushSpec.defaults(BrushKind.Fountain), -0x1000000, 3L)
        for (i in 0..60) {
            val t = i / 60f
            b.add(InputSample(100f + 400f * t, 300f + 40f * sin(t * 9f), 0.2f + 0.7f * t, timeNanos = i * 8_000_000L))
        }
        val drawn = b.finish()
        val s = StraightLine.straighten(drawn, 520f, 310f)
        val p = s.points
        assertTrue(p.size >= 2)
        assertEquals(drawn.points.x(0), p.x(0), 0.02f)
        assertEquals(drawn.points.y(0), p.y(0), 0.02f)
        assertEquals(520f, p.x(p.size - 1), 0.02f)
        assertEquals(310f, p.y(p.size - 1), 0.02f)
        // Every point lies on the segment.
        val x0 = p.x(0)
        val y0 = p.y(0)
        for (i in 0 until p.size) {
            val cross = (520f - x0) * (p.y(i) - y0) - (310f - y0) * (p.x(i) - x0)
            assertTrue(abs(cross) / 420f < 0.05f, "point $i is off the line")
        }
        assertTrue(p.pressure(0) < 0.3f && p.pressure(p.size - 1) > 0.8f, "the pressure ramp is kept")
        assertEquals(drawn.id, s.id)
        assertEquals(drawn.brush, s.brush)
        assertTrue(StraightLine.length(s) < StraightLine.length(drawn), "straight is shorter than wavy")
    }
}