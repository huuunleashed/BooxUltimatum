package app.booxultimatum.nib.engine.render

import app.booxultimatum.nib.engine.BLACK
import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.geom.Affine
import app.booxultimatum.nib.engine.geom.Box
import kotlin.math.PI
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class SoftwareRasterTest {
    private fun rect(l: Float, t: Float, r: Float, b: Float) = floatArrayOf(l, t, r, t, r, b, l, b)

    @Test
    fun polygonsHaveExactCoverage() {
        val r = SoftwareRaster(20, 20)
        r.fillPath(rect(2f, 2f, 8f, 8f), 4, BLACK, Blend.Normal)
        assertEquals(1f, r.alpha(5, 5))
        assertEquals(1f, r.alpha(2, 2))
        assertEquals(0f, r.alpha(8, 5))
        assertEquals(0f, r.alpha(1, 5))
        assertEquals(36f, r.coverage() * 400f, 1e-3f)
        val half = SoftwareRaster(20, 20)
        half.fillPath(rect(2.5f, 2.5f, 7.5f, 7.5f), 4, BLACK, Blend.Normal)
        assertEquals(0.5f, half.alpha(2, 5), 1e-6f)
        assertEquals(0.25f, half.alpha(2, 2), 1e-6f)
        assertEquals(1f, half.alpha(4, 4))
        assertEquals(Box(2f, 2f, 8f, 8f), half.inkBounds())
    }

    @Test
    fun nonzeroRuleCountsOverlapsOnce() {
        // Two squares wound the same way, joined by a bridge walked there and back: the overlap is filled once.
        val same = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f, 0f, 0f, 5f, 5f, 15f, 5f, 15f, 15f, 5f, 15f, 5f, 5f)
        val r = SoftwareRaster(20, 20)
        r.fillPath(same, same.size / 2, 0x80000000.toInt(), Blend.Normal)
        assertEquals(128 / 255f, r.alpha(7, 7), 1e-4f)
        assertEquals(128 / 255f, r.alpha(2, 2), 1e-4f)
        // Wound the other way, the overlap cancels out.
        val opposite = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f, 0f, 0f, 5f, 5f, 5f, 15f, 15f, 15f, 15f, 5f, 5f, 5f)
        val h = SoftwareRaster(20, 20)
        h.fillPath(opposite, opposite.size / 2, BLACK, Blend.Normal)
        assertEquals(0f, h.alpha(7, 7))
        assertEquals(1f, h.alpha(2, 2))
        assertEquals(1f, h.alpha(12, 12))
    }

    @Test
    fun circlesHaveTheRightArea() {
        val r = SoftwareRaster(100, 100)
        r.fillCircle(50f, 50f, 20f, BLACK, Blend.Normal)
        assertEquals((PI * 400).toFloat(), r.coverage() * 10000f, 5f)
        assertEquals(1f, r.alpha(50, 50))
        assertEquals(0f, r.alpha(75, 50))
    }

    @Test
    fun blendsFollowTheW3cMaths() {
        val r = SoftwareRaster(10, 1)
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val px = rect(0f, 0f, 1f, 1f)
        r.fillPath(px, 4, blue, Blend.Normal)
        r.fillPath(px, 4, 0x80FF0000.toInt(), Blend.Normal)
        assertEquals(0xFF80007F.toInt(), r.pixel(0, 0), "half red over blue")

        val grey = 0xFF808080.toInt()
        val m = rect(1f, 0f, 2f, 1f)
        r.fillPath(m, 4, grey, Blend.Normal)
        r.fillPath(m, 4, grey, Blend.Multiply)
        assertEquals(0xFF404040.toInt(), r.pixel(1, 0), "grey times grey")

        val e = rect(2f, 0f, 3f, 1f)
        r.fillPath(e, 4, red, Blend.Normal)
        r.fillPath(e, 4, 0x80000000.toInt(), Blend.Erase)
        assertEquals(1f - 128 / 255f, r.alpha(2, 0), 1e-4f)
        assertEquals(red and 0xFFFFFF, r.pixel(2, 0) and 0xFFFFFF, "erasing keeps the colour")

        val a = rect(3f, 0f, 5f, 1f)
        r.fillPath(rect(3f, 0f, 4f, 1f), 4, red, Blend.Normal)
        r.fillPath(a, 4, blue, Blend.Atop)
        assertEquals(blue, r.pixel(3, 0), "atop paints over ink")
        assertEquals(0f, r.alpha(4, 0), "and nowhere else")
    }

    @Test
    fun groupsCompositeOnce() {
        val r = SoftwareRaster(30, 30)
        r.beginGroup(0.5f, Blend.Normal)
        r.fillPath(rect(0f, 0f, 20f, 20f), 4, BLACK, Blend.Normal)
        r.fillPath(rect(10f, 10f, 30f, 30f), 4, BLACK, Blend.Normal)
        assertEquals(1, r.depth)
        assertEquals(0f, r.alpha(15, 15), "nothing lands before the group ends")
        r.endGroup()
        assertEquals(0, r.depth)
        assertEquals(0.5f, r.alpha(15, 15), 1e-6f)
        assertEquals(0.5f, r.alpha(5, 5), 1e-6f)
        // Nested: an erase group inside a normal group only erases inside the outer group.
        val n = SoftwareRaster(10, 10)
        n.fillPath(rect(0f, 0f, 10f, 10f), 4, 0xFFFF0000.toInt(), Blend.Normal)
        n.beginGroup(1f, Blend.Normal)
        n.fillPath(rect(0f, 0f, 10f, 10f), 4, BLACK, Blend.Normal)
        n.beginGroup(1f, Blend.Erase)
        n.fillCircle(5f, 5f, 3f, BLACK, Blend.Normal)
        n.endGroup()
        n.endGroup()
        assertEquals(0xFFFF0000.toInt(), n.pixel(5, 5), "the hole shows the red beneath")
        assertEquals(BLACK, n.pixel(0, 0))
        n.endGroup()
        assertEquals(0, n.depth, "an extra endGroup is ignored")
    }

    @Test
    fun transformsAndPolylines() {
        val r = SoftwareRaster(20, 20, Affine.scale(0.5f))
        r.fillPath(rect(0f, 0f, 20f, 20f), 4, BLACK, Blend.Normal)
        assertEquals(Box(0f, 0f, 10f, 10f), r.inkBounds())
        val line = SoftwareRaster(40, 20)
        line.strokePolyline(floatArrayOf(10f, 10f, 30f, 10f), 2, 4f, BLACK, Cap.Butt, null, Blend.Normal)
        assertEquals(Box(10f, 8f, 30f, 12f), line.inkBounds(0.5f))
        val round = SoftwareRaster(40, 20)
        round.strokePolyline(floatArrayOf(10f, 10f, 30f, 10f), 2, 4f, BLACK, Cap.Round, null, Blend.Normal)
        assertEquals(Box(8f, 8f, 32f, 12f), round.inkBounds(0.5f))
        val dashed = SoftwareRaster(40, 20)
        dashed.strokePolyline(floatArrayOf(0f, 10f, 40f, 10f), 2, 2f, BLACK, Cap.Butt, floatArrayOf(5f, 5f), Blend.Normal)
        assertEquals(1f, dashed.alpha(2, 10))
        assertEquals(0f, dashed.alpha(7, 10))
        assertEquals(1f, dashed.alpha(12, 10))
    }

    @Test
    fun texturedDabs() {
        val r = SoftwareRaster(100, 40)
        r.dab(20f, 20f, 10f, 0f, BLACK, 1f, Texture.Soft, Blend.Normal)
        assertTrue(r.alpha(20, 20) > 0.9f)
        assertTrue(r.alpha(27, 20) in 0.05f..0.6f, "soft dabs fade")
        r.dab(50f, 20f, 8f, 0f, BLACK, 1f, Texture.Grain, Blend.Normal)
        r.dab(80f, 20f, 8f, 0f, BLACK, 1f, Texture.Charcoal, Blend.Normal)
        val grain = (45..55).map { r.alpha(it, 20) }.toSet()
        val charcoal = (75..85).map { r.alpha(it, 20) }.toSet()
        assertTrue(grain.size > 5 && charcoal.size > 3, "textures vary across the dab")
        assertTrue(r.alpha(50, 20) <= 1f && r.alpha(50, 20) >= 0.2f)
        r.dab(50f, 5f, 3f, 0f, BLACK, 0f, Texture.Solid, Blend.Normal)
        assertEquals(0f, r.alpha(50, 5), "zero alpha dabs leave nothing")
    }

    @Test
    fun readingAndExport() {
        val r = SoftwareRaster(4, 3)
        assertEquals(0x00FFFFFF, r.pixel(0, 0))
        assertEquals(0f, r.darkness(0, 0))
        r.fillPath(rect(0f, 0f, 1f, 1f), 4, BLACK, Blend.Normal)
        assertEquals(1f, r.darkness(0, 0))
        assertEquals(1, r.inkedPixels())
        val ppm = r.toPpm()
        val header = "P6\n4 3\n255\n".toByteArray()
        assertEquals(header.size + 36, ppm.size)
        assertTrue(ppm.copyOf(header.size).contentEquals(header))
        assertEquals(0, ppm[header.size].toInt())
        assertEquals(-1, ppm[header.size + 3].toInt(), "white over the paper")
        assertEquals(BLACK, r.toArgb()[0])
        assertEquals(-1, r.composite()[1])
        r.clear()
        assertEquals(0f, r.coverage())
        assertEquals(Box.EMPTY, r.inkBounds())
    }
}
