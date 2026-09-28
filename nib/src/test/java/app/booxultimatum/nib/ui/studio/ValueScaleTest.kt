package app.booxultimatum.nib.ui.studio

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ValueScaleTest {
    private val width = ValueScale.width(0.5f..60f)

    @Test fun theWidthTrackIsLogarithmicWithBothEndsReachable() {
        assertEquals(0.5f, width.value(0f))
        assertEquals(60f, width.value(1f))
        assertEquals(0f, width.fraction(0.5f))
        assertEquals(1f, width.fraction(60f))
        // Half the track is the geometric middle, not 30 px: thin widths get most of the travel.
        val mid = width.value(0.5f)
        assertTrue(mid in 5f..6f, "middle of the track was $mid")
        assertTrue(width.fraction(2f) > 0.28f, "2 px sits well along the track")
    }

    @Test fun thinWidthsSnapToQuarterPixels() {
        assertEquals(0.75f, width.snap(0.8f))
        assertEquals(1.25f, width.snap(1.2f))
        assertEquals(4.5f, width.snap(4.4f))
        assertEquals(12f, width.snap(12.3f))
        assertEquals(42f, width.snap(41.3f))
        // Every point on the track lands on a step.
        for (i in 0..100) {
            val v = width.value(i / 100f)
            assertEquals(v, width.snap(v), "track at $i%")
        }
    }

    @Test fun minusAndPlusStepThroughTheGrid() {
        assertEquals(0.75f, width.stepped(0.5f, 1))
        assertEquals(1f, width.stepped(0.75f, 1))
        assertEquals(1.75f, width.stepped(2f, -1))
        assertEquals(2.5f, width.stepped(2f, 1))
        assertEquals(6f, width.stepped(5f, 1))
        assertEquals(4.5f, width.stepped(5f, -1))
        assertEquals(0.5f, width.stepped(0.5f, -1), "held at the bottom")
        assertEquals(60f, width.stepped(59f, 3), "held at the top")
        // A typed value off the grid steps to the next grid value in that direction.
        assertEquals(0.75f, width.stepped(0.6f, 1))
        assertEquals(0.5f, width.stepped(0.6f, -1))
    }

    @Test fun typedValuesAreReadTolerantlyAndClamped() {
        assertEquals(ValueScale.Parsed.Ok(0.6f, clamped = false), width.parse("0.6"))
        assertEquals(ValueScale.Parsed.Ok(0.75f, clamped = false), width.parse(" 0,75 px"))
        assertEquals(ValueScale.Parsed.Ok(60f, clamped = true), width.parse("900"))
        assertEquals(ValueScale.Parsed.Ok(0.5f, clamped = true), width.parse("0.1"))
        assertIs<ValueScale.Parsed.Invalid>(width.parse(""))
        assertIs<ValueScale.Parsed.Invalid>(width.parse("abc"))
        assertIs<ValueScale.Parsed.Invalid>(width.parse("1.2.3"))
        assertIs<ValueScale.Parsed.Invalid>(width.parse("3-"))
        assertEquals(ValueScale.Parsed.Ok(1.23f, clamped = false), width.parse("1.234"), "two decimals kept")
    }

    @Test fun percentagesShowAndReadAsPercent() {
        val p = ValueScale.percent(0.05f, 1f)
        assertEquals("50", p.format(0.5f))
        assertEquals(ValueScale.Parsed.Ok(0.35f, clamped = false), p.parse("35 %"))
        assertEquals(ValueScale.Parsed.Ok(0.05f, clamped = true), p.parse("0"))
        assertEquals(0.51f, p.stepped(0.5f, 1), 1e-5f)
        assertEquals(1f, p.value(1f))
    }

    @Test fun numbersReadAsPeopleWriteThem() {
        assertEquals("0.75", ValueScale.formatNumber(0.75f))
        assertEquals("3", ValueScale.formatNumber(3f))
        assertEquals("12.5", ValueScale.formatNumber(12.5f))
        assertEquals("0.3", ValueScale.formatNumber(0.30000001f))
        assertEquals("5.1", ValueScale.formatNumber(5.08f, 1))
    }

    @Test fun factorsStepByTheirStepOnALogTrack() {
        val f = ValueScale.factor(0.3f, 1.6f, 0.05f)
        assertEquals(1.05f, f.stepped(1f, 1), 1e-5f)
        assertEquals(0.95f, f.stepped(1f, -1), 1e-5f)
        assertEquals(0.3f, f.value(0f))
        assertEquals(1.6f, f.value(1f))
    }
}