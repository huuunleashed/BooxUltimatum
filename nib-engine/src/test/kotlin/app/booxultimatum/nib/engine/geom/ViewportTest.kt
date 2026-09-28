package app.booxultimatum.nib.engine.geom

import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class ViewportTest {
    @Test
    fun roundTrips() {
        val rnd = Random(1)
        repeat(100) {
            val v = Viewport(0.1f + rnd.nextFloat() * 10f, rnd.nextFloat() * 2000f - 1000f, rnd.nextFloat() * 2000f - 1000f)
            val p = Vec(rnd.nextFloat() * 3000f, rnd.nextFloat() * 3000f)
            val back = v.toDoc(v.toView(p))
            assertEquals(p.x, back.x, 1e-2f)
            assertEquals(p.y, back.y, 1e-2f)
            val box = Box(p.x, p.y, p.x + 100f, p.y + 50f)
            val boxBack = v.viewRectToDoc(v.docRectToView(box))
            assertEquals(box.left, boxBack.left, 1e-2f)
            assertEquals(box.bottom, boxBack.bottom, 1e-2f)
        }
        val v = Viewport(2f, 10f, 20f)
        assertEquals(Vec(12f, 24f), v.toView(Vec(1f, 2f)))
        assertEquals(Box(10f, 20f, 30f, 40f), v.docRectToView(Box(0f, 0f, 10f, 10f)))
    }

    @Test
    fun zoomKeepsTheFocusedPointStill() {
        val rnd = Random(2)
        repeat(200) {
            val v = Viewport(0.2f + rnd.nextFloat() * 5f, rnd.nextFloat() * 500f, rnd.nextFloat() * 500f)
            val fx = rnd.nextFloat() * 1860f
            val fy = rnd.nextFloat() * 2480f
            val factor = 0.01f + rnd.nextFloat() * 40f
            val before = v.toDoc(Vec(fx, fy))
            val z = v.zoomAround(fx, fy, factor)
            val after = z.toDoc(Vec(fx, fy))
            assertEquals(before.x, after.x, 0.05f)
            assertEquals(before.y, after.y, 0.05f)
            assertTrue(z.scale in z.minScale..z.maxScale)
        }
    }

    @Test
    fun zoomClampsToLimits() {
        val v = Viewport(1f)
        assertEquals(Viewport.DEFAULT_MAX_SCALE, v.zoomAround(0f, 0f, 1000f).scale)
        assertEquals(Viewport.DEFAULT_MIN_SCALE, v.zoomAround(0f, 0f, 0.0001f).scale)
        val custom = Viewport(1f, minScale = 0.5f, maxScale = 2f)
        assertEquals(2f, custom.zoomAround(100f, 100f, 10f).scale)
        assertEquals(0.5f, custom.zoomAround(100f, 100f, 0.1f).scale)
        assertEquals(custom, custom.zoomAround(1f, 1f, Float.NaN), "a bad factor changes nothing")
        assertEquals(custom, custom.zoomAround(1f, 1f, -2f))
    }

    @Test
    fun panFitAndPreviewWidth() {
        val v = Viewport(2f, 1f, 1f).pan(10f, -5f)
        assertEquals(11f, v.offsetX)
        assertEquals(-4f, v.offsetY)
        val fit = Viewport().fit(1860f, 2480f, 1000f, 1000f, margin = 20f)
        assertEquals(960f / 2480f, fit.scale, 1e-5f)
        val shown = fit.docRectToView(Box(0f, 0f, 1860f, 2480f))
        assertEquals(20f, shown.top, 0.01f)
        assertEquals(980f, shown.bottom, 0.01f)
        assertEquals(500f, shown.centerX, 0.01f, "centred horizontally")
        assertEquals(6f, Viewport(3f).previewWidth(2f))
        val visible = Viewport(2f, -100f, -50f).visibleDocRect(200f, 100f)
        assertEquals(Box(50f, 25f, 150f, 75f), visible)
        val m = Viewport(2f, 5f, 7f).docToView()
        assertEquals(Vec(9f, 13f), m.map(Vec(2f, 3f)))
    }
}
