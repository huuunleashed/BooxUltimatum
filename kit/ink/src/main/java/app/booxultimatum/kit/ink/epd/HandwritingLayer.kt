package app.booxultimatum.kit.ink.epd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Pictures drawn straight into the display's handwriting layer while the app's frames are held, which is how the
 * native note app keeps its toolbar current during a writing session without letting a single frame through. A
 * picture larger than one call allows ([Epd.MAX_BITMAP_BYTES]) goes in bands.
 *
 * Whether the layer keeps what's pushed until the frames are let through, and how it shows colour, is *[verify]* on
 * each firmware; callers treat a false return as "not shown" and fall back to letting frames through.
 */
object HandwritingLayer {
    /** Pushes [bitmap] with its top-left corner at screen ([x], [y]). Returns whether every band was accepted. */
    fun push(bitmap: Bitmap, x: Int, y: Int): Boolean {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
        val src = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return false
        try {
            val rowBytes = src.width * 4
            val bandRows = max(1, Epd.MAX_BITMAP_BYTES / rowBytes)
            var top = 0
            while (top < src.height) {
                val rows = min(bandRows, src.height - top)
                val band = if (top == 0 && rows == src.height) src else Bitmap.createBitmap(src, 0, top, src.width, rows)
                val pixels = ByteArray(band.width * band.height * 4)
                band.copyPixelsToBuffer(ByteBuffer.wrap(pixels))
                if (band !== src) band.recycle()
                if (!Epd.drawHandwritingBitmap(x, y + top, src.width, rows, pixels)) return false
                top += rows
            }
            return true
        } finally {
            if (src !== bitmap) src.recycle()
        }
    }

    /** Pushes what [view] shows now, at its place on screen. Main thread (it draws the view). */
    fun pushView(view: View): Boolean {
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return false
        val bitmap = capture(view, Rect(0, 0, view.width, view.height)) ?: return false
        val at = IntArray(2).also { view.getLocationOnScreen(it) }
        return try { push(bitmap, at[0], at[1]) } finally { bitmap.recycle() }
    }

    /** Pushes the part of [view] inside [area] (in the view's own coordinates), at its place on screen. Main thread. */
    fun pushArea(view: View, area: Rect): Boolean {
        val r = Rect(area)
        if (!r.intersect(0, 0, view.width, view.height) || r.isEmpty) return false
        val bitmap = capture(view, r) ?: return false
        val at = IntArray(2).also { view.getLocationOnScreen(it) }
        return try { push(bitmap, at[0] + r.left, at[1] + r.top) } finally { bitmap.recycle() }
    }

    private fun capture(view: View, area: Rect): Bitmap? = runCatching {
        Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            c.translate(-area.left.toFloat(), -area.top.toFloat())
            view.draw(c)
        }
    }.getOrNull()
}
