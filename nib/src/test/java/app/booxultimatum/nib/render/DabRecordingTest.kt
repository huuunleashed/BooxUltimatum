package app.booxultimatum.nib.render

import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.geom.Box
import app.booxultimatum.nib.engine.input.InputSample
import app.booxultimatum.nib.engine.input.StrokeBuilder
import app.booxultimatum.nib.engine.render.Cap
import app.booxultimatum.nib.engine.render.RenderSink
import app.booxultimatum.nib.engine.render.StrokeRenderer
import app.booxultimatum.nib.engine.render.Texture
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Counts what reaches it. */
private class CountingSink : RenderSink {
    val calls = mutableListOf<String>()
    var dabs = 0

    override fun beginGroup(alpha: Float, blend: Blend) {
        calls += "begin"
    }
    override fun endGroup() {
        calls += "end"
    }
    override fun fillPath(xy: FloatArray, count: Int, color: Int, blend: Blend) {
        calls += "path"
    }
    override fun fillCircle(x: Float, y: Float, r: Float, color: Int, blend: Blend) {
        calls += "circle"
    }
    override fun dab(x: Float, y: Float, radius: Float, angle: Float, color: Int, alpha: Float, texture: Texture, blend: Blend) {
        dabs++
    }
    override fun strokePolyline(xy: FloatArray, count: Int, width: Float, color: Int, cap: Cap, dash: FloatArray?, blend: Blend) {
        calls += "polyline"
    }
}

class DabRecordingTest {
    private fun stroke(spec: BrushSpec) = StrokeBuilder(spec, -0x1000000, 42).apply {
        for (i in 0..40) add(InputSample(10f + i * 20f, 100f, 0.8f, timeNanos = i * 5_000_000L))
    }.finish()

    @Test fun eachTileReplaysOnlyItsOwnDabs() {
        val s = stroke(BrushSpec.defaults(BrushKind.Charcoal))
        val rec = DabRecording()
        StrokeRenderer.render(s, rec)
        assertFalse(rec.general)
        val whole = CountingSink().also { StrokeRenderer.render(s, it) }.dabs
        assertEquals(whole, rec.size, "the recording holds every dab")
        val left = CountingSink()
        val right = CountingSink()
        rec.replay(left, Box(0f, 0f, 256f, 256f))
        rec.replay(right, Box(256f, 0f, 512f, 256f))
        assertTrue(left.dabs in 1 until whole)
        assertTrue(right.dabs in 1 until whole)
        assertTrue(left.dabs + right.dabs < whole, "tiles far along the stroke get none of the first tiles' dabs")
        assertEquals(0, CountingSink().also { rec.replay(it, Box(0f, 400f, 256f, 656f)) }.dabs)
    }

    @Test fun aGroupedStrokeKeepsItsGroupInEachTile() {
        val s = stroke(BrushSpec.defaults(BrushKind.Pencil).copy(opacity = 0.5f))
        val rec = DabRecording()
        StrokeRenderer.render(s, rec)
        assertFalse(rec.general)
        val sink = CountingSink()
        rec.replay(sink, Box(0f, 0f, 256f, 256f))
        assertEquals(listOf("begin", "end"), sink.calls)
        val empty = CountingSink()
        rec.replay(empty, Box(0f, 400f, 256f, 656f))
        assertEquals(emptyList(), empty.calls, "no dabs, no group")
    }

    @Test fun outlineBrushesAreNotDabs() {
        val rec = DabRecording()
        StrokeRenderer.render(stroke(BrushSpec.defaults(BrushKind.Fountain)), rec)
        assertTrue(rec.general)
    }

    @Test fun theDabKindsMatchTheEngine() {
        for (kind in BrushKind.entries.filter { it.isRendered }) {
            val rec = DabRecording()
            StrokeRenderer.render(stroke(BrushSpec.defaults(kind)), rec)
            assertEquals(kind in TileCache.DAB_KINDS, !rec.general && rec.size > 0, kind.id)
        }
    }
}
