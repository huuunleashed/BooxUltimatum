package app.booxultimatum.nib.render

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import app.booxultimatum.nib.engine.doc.Document
import app.booxultimatum.nib.engine.doc.Layer
import app.booxultimatum.nib.engine.render.DocumentRenderer
import app.booxultimatum.nib.engine.render.StrokeRenderer
import app.booxultimatum.nib.store.Paper
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Whole-document pictures: the exports, the library's thumbnails and the layers panel's. Safe on any thread. */
object DocumentPainter {
    private val sinks = ThreadLocal.withInitial { CanvasSink() }

    /**
     * The document at [scale] (1 = full size): over [paper] (the document's own background when null), or on
     * transparency when [withPaper] is false; the paper's guides are drawn under the ink when [withGuides].
     */
    fun render(doc: Document, scale: Float = 1f, paper: Paper? = null, withPaper: Boolean = true, withGuides: Boolean = false, legible: Boolean = false): Bitmap {
        val w = max(1, (doc.width * scale).roundToInt())
        val h = max(1, (doc.height * scale).roundToInt())
        val bmp = createBitmap(w, h)
        val canvas = Canvas(bmp)
        if (withPaper) canvas.drawColor((paper?.colour ?: doc.background) or -0x1000000)
        canvas.scale(w / doc.width.toFloat(), h / doc.height.toFloat())
        canvas.clipRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
        if (withGuides && paper != null) GuidesPainter.draw(canvas, paper, doc.width, doc.height, doc.bounds, lineWidth = max(1f, paper.spacing / 50f))
        val shown = if (legible) legibleAt(doc, scale) else doc
        DocumentRenderer.render(shown, sinks.get()!!.on(canvas), tolerance = StrokeRenderer.DEFAULT_TOLERANCE / scale.coerceAtMost(1f), paintBackground = false)
        return bmp
    }

    /**
     * [doc] with every stroke at least [MIN_THUMB_PX] wide once drawn at [scale], so thin lines stay visible in the small
     * pictures (thumbnails only; the drawing itself never changes).
     */
    fun legibleAt(doc: Document, scale: Float): Document {
        if (scale >= 1f) return doc
        val min = MIN_THUMB_PX / scale
        return doc.copy(layers = doc.layers.map { l -> legible(l, min) })
    }

    private fun legible(layer: Layer, min: Float): Layer {
        if (layer.strokes.none { it.brush.width < min }) return layer
        return layer.copy(strokes = layer.strokes.map { s -> if (s.brush.width < min) s.copy(brush = s.brush.copy(width = min)) else s })
    }

    /** The thinnest a line is drawn in a thumbnail, in its own pixels. */
    const val MIN_THUMB_PX = 1.3f

    /** One layer alone on transparency, at [scale], hidden or not, with its own opacity. */
    fun renderLayer(doc: Document, layer: Layer, scale: Float = 1f): Bitmap {
        val w = max(1, (doc.width * scale).roundToInt())
        val h = max(1, (doc.height * scale).roundToInt())
        val bmp = createBitmap(w, h)
        val canvas = Canvas(bmp)
        canvas.scale(w / doc.width.toFloat(), h / doc.height.toFloat())
        canvas.clipRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
        DocumentRenderer.renderLayer(layer.copy(visible = true), sinks.get()!!.on(canvas), tolerance = StrokeRenderer.DEFAULT_TOLERANCE / scale.coerceAtMost(1f))
        return bmp
    }

    /** A PNG no larger than [maxSide] on its long side, for the library. */
    fun thumbnailPng(doc: Document, paper: Paper? = null, maxSide: Int = THUMB_SIDE): ByteArray {
        val scale = (maxSide.toFloat() / max(doc.width, doc.height)).coerceAtMost(1f)
        val bmp = render(doc, scale, paper, withPaper = true, withGuides = paper != null, legible = true)
        return try {
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    const val THUMB_SIDE = 480
}