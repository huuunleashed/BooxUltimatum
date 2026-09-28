package app.booxultimatum.nib.pen

import org.junit.Test
import kotlin.test.assertEquals

class PressureNormalizerTest {
    @Test fun androidScaledPressureStaysAsItIs() {
        val n = PressureNormalizer(rangeMax = 1f, displayMax = 4096f)
        assertEquals(0.5f, n.normalize(0.5f))
        assertEquals(1f, n.normalize(1.2f), "overshoot is clamped")
    }

    @Test fun rawReadingsAreScaledByTheReportedRange() {
        assertEquals(0.5f, PressureNormalizer(4096f, null).normalize(2048f))
    }

    @Test fun withoutARangeTheDisplayMaximumIsTheFallbackForRawReadings() {
        val n = PressureNormalizer(null, 4096f)
        assertEquals(0.25f, n.normalize(1024f))
        assertEquals(0.75f, n.normalize(0.75f), "a reading already in 0..1 is left alone")
    }

    @Test fun nothingOrNonsenseIsZero() {
        val n = PressureNormalizer(null, null)
        assertEquals(0f, n.normalize(Float.NaN))
        assertEquals(0f, n.normalize(-1f))
        assertEquals(1f, n.normalize(5f))
    }
}
