package app.booxultimatum.nib.editor

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.geom.Affine
import app.booxultimatum.nib.engine.geom.Viewport
import app.booxultimatum.nib.engine.render.StrokeRenderer
import app.booxultimatum.nib.render.CanvasSink
import kotlin.math.roundToInt

/**
 * What the stroke eraser has crossed, kept as one alpha picture of the upright view. Each stroke is drawn into it once,
 * when the eraser first reaches it, and every frame then takes the picture out of the layer with a single bitmap draw,
 * however many strokes it holds: drawing the crossed strokes themselves each frame made the eraser slower with every
 * stroke it passed (a stipple pencil stroke alone is thousands of dots).
 *
 * The picture sits on the pixel lattice the tiles do (the view's offset rounded, as a tile's place is), so a stroke is
 * taken out with exactly the dots it was drawn with. It is made for one view; when the view moves it is dropped and
 * drawn again from the strokes it is given. Main thread only.
 */
class EraseMask {
    private class Sig(val width: Int, val height: Int, val scale: Float, val x: Int, val y: Int) {
        fun same(o: Sig): Boolean = width == o.width && height == o.height && scale == o.scale && x == o.x && y == o.y
    }

    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private var sig: Sig? = null
    private val drawn = HashSet<Long>()
    private val sink = CanvasSink()
    private val matrix = Matrix()
    private val paint = Paint().apply { blendMode = BlendMode.DST_OUT }

    /** Whether any stroke is in it, so the layer it belongs to must be taken out of with it. */
    val showing: Boolean get() = drawn.isNotEmpty()

    /** Whether it was made for [flat] in a [width] by [height] view, and so can still be drawn over its tiles. */
    fun fits(flat: Viewport, width: Int, height: Int): Boolean = sig?.same(sigFor(flat, width, height)) != false

    /** Draws into it each of [strokes] it doesn't hold yet, for [flat] (the upright view) of [width] by [height] pixels. */
    fun sync(strokes: List<Stroke>, flat: Viewport, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val now = sigFor(flat, width, height)
        val old = sig
        if (old == null || !old.same(now) || bitmap == null) {
            drawn.clear()
            val b = bitmap
            if (b == null || b.width != width || b.height != height) {
                b?.recycle()
                val fresh = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
                bitmap = fresh
                canvas = Canvas(fresh)
            } else {
                b.eraseColor(0)
            }
            sig = now
        }
        val c = canvas ?: return
        val pending = strokes.filter { it.id !in drawn }
        if (pending.isEmpty()) return
        val a = flat.docToView()
        matrix.setValues(Affine(a.scaleX, 0f, now.x.toFloat(), 0f, a.scaleY, now.y.toFloat()).toMatrixValues())
        c.save()
        c.setMatrix(matrix)
        val tolerance = 0.25f / flat.scale
        val on = sink.on(c)
        for (s in pending) {
            // As full ink whatever the stroke was: its opacity, colour and blend don't change what it takes out.
            StrokeRenderer.render(s.copy(brush = s.brush.copy(blend = Blend.Normal, opacity = 1f), color = OPAQUE), on, tolerance)
            drawn.add(s.id)
        }
        c.restore()
    }

    /** Takes what it holds out of whatever the canvas is drawing into, in the upright view's pixels. */
    fun draw(target: Canvas) {
        val b = bitmap ?: return
        if (drawn.isEmpty()) return
        target.drawBitmap(b, 0f, 0f, paint)
    }

    /** Forgets every stroke; the picture is kept for the next, since it is as big as the screen. */
    fun clear() {
        if (drawn.isEmpty() && sig == null) return
        drawn.clear()
        sig = null
        bitmap?.eraseColor(0)
    }

    /** Frees the picture. */
    fun release() {
        drawn.clear()
        sig = null
        canvas = null
        bitmap?.recycle()
        bitmap = null
    }

    private fun sigFor(flat: Viewport, width: Int, height: Int): Sig {
        val a = flat.docToView()
        return Sig(width, height, a.scaleX, a.transX.roundToInt(), a.transY.roundToInt())
    }

    private companion object {
        const val OPAQUE = -0x1000000
    }
}
