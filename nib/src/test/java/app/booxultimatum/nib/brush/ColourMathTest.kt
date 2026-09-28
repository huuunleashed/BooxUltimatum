package app.booxultimatum.nib.brush

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ColourMathTest {
    @Test fun hexReadsAndWritesAsPeopleTypeIt() {
        assertEquals(0xFF1F4FB8.toInt(), Palette.parseHex("#1F4FB8"))
        assertEquals(0xFF1F4FB8.toInt(), Palette.parseHex(" 1f4fb8 "))
        assertEquals(0xFF11FFBB.toInt(), Palette.parseHex("#1FB"))
        assertNull(Palette.parseHex("#12345"))
        assertNull(Palette.parseHex("#GGGGGG"))
        assertNull(Palette.parseHex(""))
        assertEquals("#1F4FB8", Palette.toHex(0x801F4FB8.toInt()))
    }

    @Test fun hsvRoundTripsThroughTheSquare() {
        for (s in Palette.SWATCHES) {
            val (h, sat, v) = Palette.toHsv(s.argb)
            val back = Palette.hsv(h, sat, v)
            for (shift in listOf(16, 8, 0)) {
                val a = (s.argb shr shift) and 0xFF
                val b = (back shr shift) and 0xFF
                assertEquals(a.toFloat(), b.toFloat(), 1.5f, s.key)
            }
        }
        assertEquals(0xFFFF0000.toInt(), Palette.hsv(0f, 1f, 1f))
        assertEquals(0xFF000000.toInt(), Palette.hsv(123f, 0.7f, 0f))
        assertEquals(0f, Palette.toHsv(0xFF808080.toInt()).second, "grey has no saturation")
    }

    @Test fun recentColoursKeepTheNewestFirstWithoutRepeats() {
        var r = emptyList<Int>()
        for (c in 1..10) r = Palette.pushRecent(r, c)
        assertEquals((10 downTo 3).toList(), r)
        r = Palette.pushRecent(r, 5)
        assertEquals(listOf(5, 10, 9, 8, 7, 6, 4, 3), r)
    }
}