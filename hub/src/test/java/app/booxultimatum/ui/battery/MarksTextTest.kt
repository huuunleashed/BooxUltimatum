package app.booxultimatum.ui.battery

import app.booxultimatum.core.battery.BeforeAfter
import app.booxultimatum.core.battery.BeforeAfterVerdict
import app.booxultimatum.core.battery.Mark
import app.booxultimatum.core.battery.MarkSource
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun result(
    verdict: BeforeAfterVerdict,
    beforeMa: Double? = 9.0,
    afterMa: Double? = 6.0,
    beforeHours: Double = 12.0,
    afterHours: Double = 12.0,
    spreadMa: Double? = 2.0,
) = BeforeAfter(Mark(0L, "Applied Wi-Fi", MarkSource.Tweak), beforeMa, afterMa, beforeHours, afterHours, spreadMa, verdict)

class MarksTextTest {
    @Test
    fun notEnoughDataKeepsTheHoursOnEachSide() {
        val line = beforeAfterLine(result(BeforeAfterVerdict.NotEnoughData, null, null, 3.5, 0.0))
        assertEquals(BeforeAfterLine.NotEnough(3.5, 0.0), line)
        assertTrue((line as BeforeAfterLine.NotEnough).showHours)
    }

    @Test
    fun notEnoughDataWithNoAsleepTimeSaysNoHours() {
        val line = beforeAfterLine(result(BeforeAfterVerdict.NotEnoughData, null, null, 0.0, 0.0))
        assertFalse((line as BeforeAfterLine.NotEnough).showHours)
    }

    @Test
    fun aSmallChangeIsNotCalledAResult() {
        assertEquals(BeforeAfterLine.WithinNormal(9.0, 9.4, 2.0), beforeAfterLine(result(BeforeAfterVerdict.WithinNormal, 9.0, 9.4, spreadMa = 2.0)))
    }

    @Test
    fun aFallAndARiseKeepTheirDirectionAndSpread() {
        assertEquals(BeforeAfterLine.Lower(9.0, 6.0, null), beforeAfterLine(result(BeforeAfterVerdict.Lower, 9.0, 6.0, spreadMa = null)))
        assertEquals(BeforeAfterLine.Higher(6.0, 9.0, 2.0), beforeAfterLine(result(BeforeAfterVerdict.Higher, 6.0, 9.0, spreadMa = 2.0)))
    }

    @Test
    fun aMissingRateCountsAsNotEnoughData() {
        assertEquals(BeforeAfterLine.NotEnough(12.0, 12.0), beforeAfterLine(result(BeforeAfterVerdict.WithinNormal, null, 9.0)))
    }

    @Test
    fun labelsAreCutAtFortyCharacters() {
        assertEquals("turned off Wi-Fi", clipLabel("turned off Wi-Fi"))
        assertEquals("a".repeat(40), clipLabel("a".repeat(41)))
    }

    @Test
    fun aCutNeverSplitsAnEmoji() {
        val emoji = "\uD83D\uDE00"
        assertEquals("a".repeat(39) + emoji, clipLabel("a".repeat(39) + emoji + "b"))
        assertEquals("a".repeat(40), clipLabel("a".repeat(40) + emoji))
    }
}
