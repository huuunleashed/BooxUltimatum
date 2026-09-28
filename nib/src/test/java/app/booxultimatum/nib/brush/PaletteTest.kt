package app.booxultimatum.nib.brush

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaletteTest {
    @Test fun twelveOpaqueDistinctSwatches() {
        assertEquals(12, Palette.SWATCHES.size)
        assertEquals(12, Palette.SWATCHES.map { it.argb }.distinct().size)
        assertEquals(12, Palette.SWATCHES.map { it.key }.distinct().size)
        assertTrue(Palette.SWATCHES.all { (it.argb ushr 24) == 0xFF })
        assertEquals(listOf("black", "dark_grey", "mid_grey", "white"), Palette.SWATCHES.take(4).map { it.key })
    }

    @Test fun hslCornersAreRight() {
        assertEquals(0xFF000000.toInt(), Palette.hsl(0f, 0f))
        assertEquals(0xFFFFFFFF.toInt(), Palette.hsl(200f, 1f))
        assertEquals(0xFFFF0000.toInt(), Palette.hsl(0f, 0.5f, 1f))
        assertEquals(0xFF00FF00.toInt(), Palette.hsl(120f, 0.5f, 1f))
        assertEquals(0xFF0000FF.toInt(), Palette.hsl(240f, 0.5f, 1f))
        assertEquals(Palette.hsl(10f, 0.4f), Palette.hsl(370f, 0.4f), "hue wraps")
    }

    @Test fun hueAndLightnessRoundTrip() {
        for (h in listOf(0f, 30f, 95f, 180f, 250f, 330f)) {
            for (l in listOf(0.2f, 0.45f, 0.7f)) {
                val (h2, l2) = Palette.hueLightness(Palette.hsl(h, l))
                assertTrue(abs(h2 - h) < 2f || abs(h2 - h) > 358f, "hue $h came back $h2")
                assertTrue(abs(l2 - l) < 0.01f, "lightness $l came back $l2")
            }
        }
    }

    @Test fun paleColoursAreRecognised() {
        assertTrue(Palette.isPale(0xFFFFFFFF.toInt()))
        assertTrue(Palette.isPale(0xFFF2F2A0.toInt()))
        assertFalse(Palette.isPale(0xFF000000.toInt()))
        assertFalse(Palette.isPale(0xFF1F4FB8.toInt()))
    }

    @Test fun swatchLookupIgnoresAlpha() {
        assertEquals("red", Palette.swatchOf(0x80D2232A.toInt())?.key)
        assertNull(Palette.swatchOf(0xFF123456.toInt()))
    }
}
