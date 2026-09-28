package app.booxultimatum.nib.render

import android.graphics.Bitmap
import android.graphics.Canvas
import app.booxultimatum.nib.engine.doc.Document
import app.booxultimatum.nib.engine.render.DocumentRenderer
import app.booxultimatum.nib.engine.render.StrokeRenderer
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Whole-document pictures: the flattened export and the thumbnails the library shows. Safe on any thread. */
object DocumentPainter {
    private val sinks = ThreadLocal.withInitial { CanvasSink() }

    /** The document flattened over its paper at [scale] (1 = full size). */
    fun render(doc: Document, scale: Float = 1f): Bitmap {
        val w = max(1, (doc.width * scale).roundToInt())
        val h = max(1, (doc.height * scale).roundToInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(doc.background or -0x1000000)
        canvas.scale(w / doc.width.toFloat(), h / doc.height.toFloat())
        canvas.clipRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
        DocumentRenderer.render(doc, sinks.get()!!.on(canvas), tolerance = StrokeRenderer.DEFAULT_TOLERANCE / scale.coerceAtMost(1f), paintBackground = true)
        return bmp
    }

    /** A PNG no larger than [maxSide] on its long side, for the library. */
    fun thumbnailPng(doc: Document, maxSide: Int = THUMB_SIDE): ByteArray {
        val scale = (maxSide.toFloat() / max(doc.width, doc.height)).coerceAtMost(1f)
        val bmp = render(doc, scale)
        return try {
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    const val THUMB_SIDE = 480
}
