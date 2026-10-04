package app.booxultimatum.core.sleep

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sticker the Transparent style shows is decoded by Boox at the panel's size in the rotation the tablet sleeps in,
 * with `CENTER_CROP`: a sheet shown the other way up keeps only the central 0.75 of its long side. Every plate must
 * therefore be drawn inside that centred square, or it disappears when the tablet sleeps on its side.
 *
 * These tests render each plate at the panel's full size and check the pixels, and they drop a PNG per plate in the
 * app's external files so the composition can be looked at (and cropped the way Boox would) without a sleep.
 */
@RunWith(AndroidJUnit4::class)
class SleepOverlayTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun render(overlay: SleepOverlay, landscape: Boolean): Bitmap {
        val (w, h) = if (landscape) 2480 to 1860 else 1860 to 2480
        val spec = SleepStore.load(context).copy(
            mode = SleepMode.Overlay,
            overlay = overlay,
            active = true,
            elements = SleepElement.entries.toSet(),
            ownerName = "A. Owner",
            ownerContact = "owner@example.com",
            note = "A note for the plate",
        )
        return SleepStudio.render(context, spec, exact = true, into = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)).bitmap
    }

    /** The centred square Boox keeps in either rotation: 0.75 of the short side. */
    private fun cropSafe(w: Int, h: Int): RectF {
        val side = SleepCrop.safeSide(minOf(w, h).toFloat())
        val left = (w - side) / 2f
        val top = (h - side) / 2f
        return RectF(left, top, left + side, top + side)
    }

    private fun inkOutside(bitmap: Bitmap, keep: RectF): Pair<Int, IntArray> {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var count = 0
        var worst = intArrayOf(0, 0, 0)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (px[y * bitmap.width + x] ushr 24 == 0) continue
                if (x >= keep.left && x <= keep.right && y >= keep.top && y <= keep.bottom) continue
                count++
                val d = max(
                    max(keep.left - x, x - keep.right).toInt(),
                    max(keep.top - y, y - keep.bottom).toInt(),
                )
                if (d > worst[0]) worst = intArrayOf(d, x, y)
            }
        }
        return count to worst
    }

    @Test
    fun everyPlateStaysInsideTheSquareBooxKeeps() {
        for (landscape in listOf(false, true)) {
            for (o in SleepOverlay.entries) {
                val b = render(o, landscape)
                val keep = cropSafe(b.width, b.height)
                val (count, worst) = inkOutside(b, keep)
                assertTrue(
                    "$o ${b.width}x${b.height}: $count pixels outside the crop-safe square, worst ${worst[0]} px at ${worst[1]},${worst[2]}",
                    count == 0,
                )
            }
        }
    }

    /** Drops a PNG per plate, and the same sheet cropped the way Boox crops it in the other rotation. */
    @Test
    fun writePlatePreviews() {
        val dir = File(context.getExternalFilesDir(null), "plates").apply { mkdirs() }
        for (landscape in listOf(false, true)) {
            for (o in SleepOverlay.entries) {
                val b = render(o, landscape)
                val tag = "${o.name}${if (landscape) "-landscape" else ""}"
                save(onPaper(b), File(dir, "$tag.png"))
                save(croppedByBoox(b), File(dir, "$tag-cropped.png"))
            }
        }
        assertTrue("no previews written", dir.listFiles().orEmpty().isNotEmpty())
    }

    /** The plate over a mid grey, so a transparent sheet can be looked at. */
    private fun onPaper(b: Bitmap): Bitmap = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).also { out ->
        Canvas(out).apply {
            drawColor(Color.rgb(0xC8, 0xC8, 0xC8))
            drawBitmap(b, 0f, 0f, Paint())
        }
    }

    /** Boox's CENTER_CROP into the other rotation: scale to fill, then keep the middle. */
    private fun croppedByBoox(b: Bitmap): Bitmap {
        val targetW = b.height
        val targetH = b.width
        val scale = max(targetW.toFloat() / b.width, targetH.toFloat() / b.height)
        val sw = b.width * scale
        val sh = b.height * scale
        return Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888).also { out ->
            Canvas(out).apply {
                drawColor(Color.rgb(0xC8, 0xC8, 0xC8))
                translate((targetW - sw) / 2f, (targetH - sh) / 2f)
                scale(scale, scale)
                drawBitmap(b, 0f, 0f, Paint())
            }
        }
    }

    private fun save(b: Bitmap, f: File) {
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
