package app.booxultimatum.nib.engine.input

import app.booxultimatum.nib.engine.BLACK
import app.booxultimatum.nib.engine.RecordingSink
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.doc.PackedPoints
import app.booxultimatum.nib.engine.render.StrokeRenderer
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class StrokeBuilderTest {
    private val fineliner = BrushSpec.defaults(BrushKind.Fineliner)

    private fun sample(x: Float, y: Float, ms: Long = 0L, p: Float = 0.5f) = InputSample(x, y, p, 0.3f, 1f, ms * 1_000_000L)

    @Test
    fun dropsNearDuplicatesButKeepsTheLast() {
        val b = StrokeBuilder(fineliner, BLACK, 1L)
        b.add(sample(0f, 0f))
        b.add(sample(0.1f, 0f))
        b.add(sample(0.2f, 0f))
        assertEquals(1, b.size, "samples within 0.25 px are dropped")
        b.add(sample(1f, 0f))
        b.add(sample(1.1f, 0.05f))
        assertEquals(2, b.size)
        val s = b.finish()
        assertEquals(3, s.points.size, "the last sample is kept on finish")
        assertEquals(PackedPoints.quantize(1.1f), s.points.x(2))
        assertEquals(PackedPoints.quantize(0.05f), s.points.y(2))
    }

    @Test
    fun ignoresNonFiniteSamples() {
        val b = StrokeBuilder(fineliner, BLACK, 1L)
        b.add(sample(Float.NaN, 0f))
        b.add(sample(0f, Float.POSITIVE_INFINITY))
        assertTrue(b.isEmpty)
        b.add(sample(3f, 3f))
        assertEquals(1, b.finish().points.size)
    }

    @Test
    fun smoothingEndsOnTheTrueLastSample() {
        val brush = fineliner.copy(smoothing = 1f)
        val b = StrokeBuilder(brush, BLACK, 7L)
        // A zigzag along x: smoothing should flatten the zigzag but still end exactly at the last sample.
        for (i in 0..60) b.add(sample(i * 2f, if (i % 2 == 0) 0f else 4f, i.toLong()))
        b.add(sample(130f, 50f, 61L))
        val s = b.finish()
        val last = s.points.size - 1
        assertEquals(130f, s.points.x(last))
        assertEquals(50f, s.points.y(last))
        assertEquals(0f, s.points.x(0))
        var rawSpread = 0f
        var smoothSpread = 0f
        for (i in 20..40) {
            rawSpread += if (i % 2 == 0) 0f else 4f
            smoothSpread += abs(s.points.y(i) - 2f)
        }
        assertTrue(smoothSpread < rawSpread * 0.25f, "zigzag flattened: $smoothSpread vs $rawSpread")
    }

    @Test
    fun noSmoothingKeepsRawPositions() {
        val b = StrokeBuilder(fineliner.copy(smoothing = 0f), BLACK, 1L)
        val xs = listOf(0f, 3f, 5f, 9f, 20f)
        xs.forEachIndexed { i, x -> b.add(sample(x, x * 0.5f, i.toLong())) }
        val s = b.finish()
        xs.forEachIndexed { i, x ->
            assertEquals(PackedPoints.quantize(x), s.points.x(i))
            assertEquals(PackedPoints.quantize(x * 0.5f), s.points.y(i))
        }
    }

    @Test
    fun currentSnapshotReachesThePen() {
        val b = StrokeBuilder(fineliner.copy(smoothing = 0.8f), BLACK, 1L)
        for (i in 0..10) b.add(sample(i * 3f, 0f, i.toLong()))
        b.add(sample(30.1f, 0f, 11L))
        val snap = b.current()
        assertEquals(12, snap.points.size, "the pending near-duplicate is included")
        assertEquals(PackedPoints.quantize(30.1f), snap.points.x(11))
        b.add(sample(40f, 0f, 12L))
        assertEquals(PackedPoints.quantize(40f), b.current().points.x(b.current().points.size - 1))
    }

    @Test
    fun keepsPressureTiltOrientationAndTiming() {
        val b = StrokeBuilder(fineliner, BLACK, 1L)
        b.add(InputSample(0f, 0f, 0.25f, 0.5f, -1f, 1_000_000_000L))
        b.add(InputSample(5f, 0f, 0.75f, 1f, 2f, 1_003_000_000L))
        b.add(InputSample(10f, 0f, 1f, 1.5f, 3f, 1_010_000_000L))
        val s = b.finish()
        assertEquals(0.25f, s.points.pressure(0), 1e-4f)
        assertEquals(0.75f, s.points.pressure(1), 1e-4f)
        assertEquals(0.5f, s.points.tilt(0), 0.01f)
        assertEquals(-1f, s.points.orientation(0), 0.02f)
        assertEquals(0, s.points.deltaMillis(0))
        assertEquals(3, s.points.deltaMillis(1))
        assertEquals(7, s.points.deltaMillis(2))
        assertEquals(10L, s.points.durationMillis)
        assertEquals(1L, s.id)
    }

    @Test
    fun singleSampleRendersAsADot() {
        val b = StrokeBuilder(fineliner.copy(width = 6f), BLACK, 3L)
        b.add(sample(10f, 12f))
        val s = b.finish()
        assertEquals(1, s.points.size)
        val sink = RecordingSink()
        StrokeRenderer.render(s, sink)
        val dot = sink.calls.single() as RecordingSink.Call.Circle
        assertEquals(10f, dot.x)
        assertEquals(12f, dot.y)
        assertEquals(3f, dot.r, 1e-4f)
    }
}
