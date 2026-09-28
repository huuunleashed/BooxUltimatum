package app.booxultimatum.nib.engine.render

import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.doc.Document
import app.booxultimatum.nib.engine.doc.Layer
import app.booxultimatum.nib.engine.geom.Box

/** Draws layers and whole documents through a [RenderSink]. */
object DocumentRenderer {
    /**
     * Draws a visible layer's strokes in order, skipping those outside [clip]. The layer goes into its own group when
     * [Layer.needsIsolation], so its erasers and blends act on this layer only.
     */
    fun renderLayer(layer: Layer, sink: RenderSink, clip: Box? = null, tolerance: Float = StrokeRenderer.DEFAULT_TOLERANCE) {
        if (!layer.visible || layer.opacity <= 0f) return
        val isolate = layer.needsIsolation
        if (isolate) sink.beginGroup(layer.opacity.coerceIn(0f, 1f), layer.blend)
        for (s in layer.strokes) {
            if (clip == null || s.bounds.intersects(clip)) StrokeRenderer.render(s, sink, tolerance)
        }
        if (isolate) sink.endGroup()
    }

    /** Draws the paper (when [paintBackground]) and every visible layer, bottom to top. */
    fun render(
        document: Document,
        sink: RenderSink,
        clip: Box? = null,
        tolerance: Float = StrokeRenderer.DEFAULT_TOLERANCE,
        paintBackground: Boolean = true,
    ) {
        if (paintBackground) {
            val page = if (clip == null) document.bounds else document.bounds.intersect(clip)
            if (!page.isEmpty) {
                val xy = floatArrayOf(page.left, page.top, page.right, page.top, page.right, page.bottom, page.left, page.bottom)
                sink.fillPath(xy, 4, document.background, Blend.Normal)
            }
        }
        for (layer in document.layers) renderLayer(layer, sink, clip, tolerance)
    }
}
