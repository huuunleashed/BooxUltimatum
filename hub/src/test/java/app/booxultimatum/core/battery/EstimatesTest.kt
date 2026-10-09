package app.booxultimatum.core.battery

import org.junit.Test
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

private const val T0 = 1_767_225_600_000L
private const val DAY = 1_440
private const val NOW_MIN = 10 * DAY

private fun at(minutes: Int): Long = T0 + minutes * 60_000L

private fun episode(kind: EpisodeKind, fromMin: Int, toMin: Int, ma: Double?) =
    Episode(kind, at(fromMin), at(toMin), 80, 79, ma?.let { it * (toMin - fromMin) / 60.0 }, 0.9, null)

/** Four hours asleep at [ma] on day [day] (days counted from T0). */
private fun dayAt(day: Int, ma: Double) = episode(EpisodeKind.Asleep, day * DAY + 60, day * DAY + 300, ma)

private val BASE = Baseline(quietAsleepMa = 9.0, dayToDaySpreadMa = 1.0, inUseMa = 291.0, asleepShare = 0.78, basisDays = 7.0)

private fun estimate(vararg episodes: Episode, baseline: Baseline = BASE, stored: Double = 3750.0): Estimate? =
    Estimates.timeLeft(Timeline(episodes.toList(), emptyList(), 3750.0, T0, at(NOW_MIN)), baseline, stored, at(NOW_MIN))

private fun assertNear(expected: Double, actual: Double?) =
    assertEquals(expected, actual ?: fail("expected $expected, was null"), 1e-6)

class EstimatesTest {
    @Test
    fun workedExampleFromTheDesignDoc() {
        val e = estimate()!!
        assertNear(3750.0 / 71.04, e.hours)
        assertEquals(53, e.hours.roundToInt())
        assertNear(0.7 * e.hours, e.lowHours)
        assertNear(1.4 * e.hours, e.highHours)
        assertNear(7.0, e.basisDays)
        assertNear(9.0, e.quietMa)
        assertNear(291.0, e.inUseMa)
        assertNear(0.78, e.asleepShare)
    }

    @Test
    fun noEstimateWithoutBasisRatesOrCharge() {
        assertNull(estimate(baseline = BASE.copy(basisDays = 0.9)))
        assertNull(estimate(baseline = BASE.copy(quietAsleepMa = null)))
        assertNull(estimate(baseline = BASE.copy(inUseMa = null)))
        assertNull(estimate(baseline = BASE.copy(asleepShare = null)))
        assertNull(estimate(stored = 0.0))
        assertNull(estimate(stored = -5.0))
    }

    @Test
    fun theRangeComesFromTheLastSevenDaysOfDailyDraws() {
        val e = estimate(dayAt(4, 60.0), dayAt(5, 70.0), dayAt(6, 80.0), dayAt(0, 500.0))!!
        assertNear(50.0, e.lowHours)
        assertNear(3750.0 / 65, e.highHours)
        assertNear(3750.0 / 71.04, e.hours)
    }

    @Test
    fun aRangeNeedsThreeDaysOfTwoHoursOrMore() {
        val e = estimate(dayAt(4, 60.0), dayAt(5, 70.0), episode(EpisodeKind.Asleep, 6 * DAY + 60, 6 * DAY + 150, 500.0))!!
        assertNear(0.7 * e.hours, e.lowHours)
        assertNear(1.4 * e.hours, e.highHours)
    }

    @Test
    fun theRangeNeverExcludesTheEstimate() {
        val busy = estimate(dayAt(4, 200.0), dayAt(5, 200.0), dayAt(6, 200.0))!!
        assertNear(3750.0 / 200, busy.lowHours)
        assertNear(busy.hours, busy.highHours)
        val calm = estimate(dayAt(4, 20.0), dayAt(5, 20.0), dayAt(6, 20.0))!!
        assertNear(busy.hours, calm.lowHours)
        assertNear(3750.0 / 20, calm.highHours)
    }
}
