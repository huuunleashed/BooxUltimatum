package app.booxultimatum.nib

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.doc.PackedPoints
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.render.StrokeRenderer
import app.booxultimatum.nib.render.CanvasSink
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The stipple pencil on a real canvas at several zooms: its dots are the page's, scaled with it. */
@RunWith(AndroidJUnit4::class)
class StippleSinkTest {
    /** A pencil stroke committed at [zoom], so its dots are one screen pixel there. */
    private fun stroke(pressure: Float, zoom: Float): Stroke {
        val b = PackedPoints.Builder()
        for (i in 0 until 80) b.add(20f + 2f * i, 60f + 10f * kotlin.math.sin(i * 0.12f), pressure, 0f, 0f, if (i == 0) 0L else 4L)
        return Stroke(7, BrushSpec.defaults(BrushKind.Pencil).copy(width = 4f).inkAt(zoom), BLACK, b.build())
    }

    /** The stroke drawn at [scale], the page's (x, y) at the canvas's ([tx], [ty]) of a [w] by [h] bitmap, alpha only. */
    private fun render(s: Stroke, scale: Float, w: Int, h: Int, tx: Float = 0f, ty: Float = 0f): IntArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.setMatrix(Matrix().apply { setScale(scale, scale); postTranslate(tx, ty) })
        StrokeRenderer.render(s, CanvasSink().on(c), 0.25f / scale)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return IntArray(w * h) { px[it] ushr 24 }
    }

    @Test fun magnifyingTheZoomItWasDrawnAtMagnifiesTheSameDots() {
        val s = stroke(0.5f, zoom = 2f)
        assertEquals(0.5f, s.brush.dot)
        val drawn = render(s, 2f, 440, 300)
        val big = render(s, 4f, 880, 600)
        assertTrue(drawn.count { it > 0 } > 400, "a real stroke to compare")
        for (y in 0 until 300) for (x in 0 until 440) {
            val a = drawn[y * 440 + x]
            for (j in 0..1) for (i in 0..1) assertEquals(a, big[(2 * y + j) * 880 + 2 * x + i], "pixel $x,$y")
        }
        assertTrue(big.all { it == 0 || it == 255 }, "magnified, a dot is a block of pixels, never grey")
    }

    @Test fun aFractionalMagnificationIsStillOnOrOff() {
        val s = stroke(0.6f, zoom = 2f)
        val r = render(s, 3f, 660, 450)
        assertTrue(r.count { it > 0 } > 1000, "lit ${r.count { it > 0 }}")
        assertTrue(r.all { it == 0 || it == 255 }, "grey ${r.count { it != 0 && it != 255 }} of lit ${r.count { it > 0 }}")
    }

    @Test fun shrunkTheStrokeKeepsItsTone() {
        val s = stroke(0.5f, zoom = 2f)
        fun tone(scale: Float): Float {
            val w = (440 * scale / 2f).toInt()
            val h = (300 * scale / 2f).toInt()
            val r = render(s, scale, w, h)
            // The middle of the line: from x = 60 to 150 and about its y.
            var sum = 0f
            var n = 0
            for (y in (55 * scale).toInt()..(65 * scale).toInt()) for (x in (60 * scale).toInt()..(150 * scale).toInt()) {
                sum += r[y * w + x] / 255f
                n++
            }
            return sum / n
        }
        val atDrawn = tone(2f)
        for (scale in listOf(1f, 0.5f, 0.7f, 1.3f)) assertEquals(atDrawn, tone(scale), 0.12f, "at $scale")
    }

    @Test fun tilesAtAnyZoomMeetWithoutASeam() {
        val s = stroke(0.5f, zoom = 2f)
        for (scale in listOf(1f, 3f)) {
            val whole = render(s, scale, 512, 128)
            val left = render(s, scale, 256, 128)
            val right = render(s, scale, 256, 128, tx = -256f)
            var differ = 0
            for (y in 0 until 128) for (x in 0 until 256) {
                if (whole[y * 512 + x] != left[y * 256 + x]) differ++
                if (whole[y * 512 + 256 + x] != right[y * 256 + x]) differ++
            }
            assertTrue(differ == 0, "at $scale the tiles differ from the whole page in $differ pixels")
        }
    }

    @Test fun atTheZoomItWasDrawnAtTheDotsAreThePixels() {
        // The preview's dots exactly: single pixels, as many as the pressure says.
        val s = stroke(0.5f, zoom = 1.5f)
        val r = render(s, 1.5f, 330, 180)
        assertTrue(r.all { it == 0 || it == 255 })
        var lone = 0
        for (y in 1 until 179) for (x in 1 until 329) {
            if (r[y * 330 + x] == 255 && r[y * 330 + x + 1] == 0 && r[y * 330 + x - 1] == 0 && r[(y + 1) * 330 + x] == 0 && r[(y - 1) * 330 + x] == 0) lone++
        }
        assertTrue(lone > 40, "single-pixel dots: $lone")
        assertTrue(abs(r.count { it > 0 } - render(s, 1.5f, 330, 180).count { it > 0 }) == 0, "the same every time")
    }

    private companion object {
        const val BLACK = -0x1000000
    }
}
