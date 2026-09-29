package app.booxultimatum.nib.engine.input

import app.booxultimatum.nib.engine.BLACK
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.doc.PackedPoints
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StationaryFilterTest {
    private val step = 1f / 4096f

    private fun s(x: Float, y: Float, ms: Long, p: Float = 0.5f) = InputSample(x, y, p, 0f, 0f, ms * 1_000_000L)

    @Test
    fun slowAndSteadyIsARepeat() {
        val kept = s(0f, 0f, 0L)
        assertTrue(StationaryFilter.isStationary(kept, s(0.3f, 0.1f, 100L)), "0.4 px in 100 ms is 0.004 px/ms")
        assertFalse(StationaryFilter.isStationary(kept, s(0.4f, 0.2f, 100L)), "0.6 px in 100 ms is 0.006 px/ms")
        assertFalse(StationaryFilter.isStationary(kept, s(0.3f, 0.2f, 100L)), "exactly 0.005 px/ms is movement")
        assertTrue(StationaryFilter.isStationary(kept, s(0.2f, 0.2f, 100L)), "Manhattan, not straight-line, distance")
    }

    @Test
    fun aPressureChangeOfMoreThanTwoLevelsIsKept() {
        val kept = s(0f, 0f, 0L, 2048f * step)
        assertTrue(StationaryFilter.isStationary(kept, s(0.1f, 0f, 50L, 2050f * step)), "two levels")
        assertTrue(StationaryFilter.isStationary(kept, s(0.1f, 0f, 50L, 2046f * step)))
        assertFalse(StationaryFilter.isStationary(kept, s(0.1f, 0f, 50L, 2051f * step)), "three levels")
        assertFalse(StationaryFilter.isStationary(kept, s(0.1f, 0f, 50L, Float.NaN)))
    }

    @Test
    fun onlyAPositiveTimeDifferenceCounts() {
        val kept = s(5f, 5f, 10L)
        assertFalse(StationaryFilter.isStationary(kept, s(5f, 5f, 10L)), "no time passed")
        assertFalse(StationaryFilter.isStationary(kept, s(5f, 5f, 9L)), "time ran backwards")
        assertTrue(StationaryFilter.isStationary(kept, s(5f, 5f, 11L)))
    }

    @Test
    fun speedIsMeasuredOnScreen() {
        val kept = s(0f, 0f, 0L)
        val next = s(0.3f, 0f, 100L)
        assertTrue(StationaryFilter.isStationary(kept, next, viewScale = 1f))
        assertFalse(StationaryFilter.isStationary(kept, next, viewScale = 2f), "0.6 screen px zoomed in")
        assertTrue(StationaryFilter.isStationary(kept, next, viewScale = Float.NaN), "a bad scale reads as 1")
    }

    @Test
    fun theBuilderDropsARestingPensRepeatsButKeepsDownAndUp() {
        val fineliner = BrushSpec.defaults(BrushKind.Fineliner).copy(smoothing = 0f)
        fun build(filter: Boolean, pressureStep: Float = 0f): Int {
            val b = StrokeBuilder(fineliner, BLACK, 1L, dropStationary = filter)
            b.add(s(0f, 0f, 0L))
            // Beyond the 0.25 px near-duplicate distance, but far slower than 0.005 px/ms.
            for (i in 1..9) b.add(s(0.3f * i, 0f, 100L * i, 0.5f + pressureStep * i))
            b.add(s(3f, 0f, 1000L, 0.5f + pressureStep * 10))
            return b.finish().points.size
        }
        assertEquals(3, build(filter = true), "the down, the first sample past 2 px, and the up")
        assertEquals(11, build(filter = false), "the flag turns it off")
        assertEquals(11, build(filter = true, pressureStep = 3f * step), "pressing harder is kept")
    }

    @Test
    fun aSlowSteadyLineKeepsItsShape() {
        val b = StrokeBuilder(BrushSpec.defaults(BrushKind.Fineliner).copy(smoothing = 0f), BLACK, 1L)
        // 4 px/s, under the native speed threshold, with perfectly steady pressure: a slow, careful curve.
        for (i in 0..50) b.add(s(i * 0.4f, (i * 0.4f) * (i * 0.4f) / 20f, i * 100L))
        val kept = b.finish().points.size
        assertTrue(kept >= 8, "a point about every 2 px survives, so the curve stays a curve (kept $kept)")
    }

    @Test
    fun aSampleFartherThanTwoPixelsIsNeverDropped() {
        val kept = s(0f, 0f, 0L)
        assertFalse(StationaryFilter.isStationary(kept, s(1.5f, 0.6f, 1_000L)), "2.1 px, however slowly")
        assertTrue(StationaryFilter.isStationary(kept, s(1.5f, 0.4f, 1_000L)), "1.9 px in a second is resting")
    }

    @Test
    fun theBuilderKeepsOrdinaryStrokesAndShowsTheRestingPen() {
        val b = StrokeBuilder(BrushSpec.defaults(BrushKind.Fineliner).copy(smoothing = 0f), BLACK, 1L)
        for (i in 0..20) b.add(s(i * 2f, 0f, i * 4L))
        assertEquals(21, b.size, "0.5 px/ms is drawing, not resting")
        b.add(s(40.4f, 0f, 400L))
        assertEquals(21, b.size, "a repeat is held back")
        assertEquals(PackedPoints.quantize(40.4f), b.current().let { it.points.x(it.points.size - 1) }, "but the live stroke still reaches the pen")
        assertEquals(22, b.finish().points.size, "and the up keeps it")
        val zoomed = StrokeBuilder(BrushSpec.defaults(BrushKind.Fineliner), BLACK, 2L, viewScale = 4f)
        zoomed.add(s(0f, 0f, 0L))
        zoomed.add(s(0.3f, 0f, 100L))
        assertEquals(2, zoomed.size, "1.2 screen px in 100 ms at 4x is movement")
    }
}
