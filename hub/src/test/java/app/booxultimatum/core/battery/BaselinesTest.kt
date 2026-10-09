package app.booxultimatum.core.battery

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

private const val T0 = 1_767_225_600_000L
private const val DAY = 1_440
private const val NOW_MIN = 10_000

private fun at(minutes: Int): Long = T0 + minutes * 60_000L

/** An episode at a steady [ma] from [fromMin] to [toMin] (minutes after T0); a null [ma] leaves its charge unknown. */
private fun episode(kind: EpisodeKind, fromMin: Int, toMin: Int, ma: Double?) =
    Episode(kind, at(fromMin), at(toMin), 80, 79, ma?.let { it * (toMin - fromMin) / 60.0 }, 0.9, null)

private fun asleep(fromMin: Int, toMin: Int, ma: Double?) = episode(EpisodeKind.Asleep, fromMin, toMin, ma)

private fun baseline(vararg episodes: Episode, nowMin: Int = NOW_MIN): Baseline =
    Baselines.of(Timeline(episodes.toList(), emptyList(), 3750.0, T0, at(nowMin)), at(nowMin))

private fun assertNear(expected: Double, actual: Double?) =
    assertEquals(expected, actual ?: fail("expected $expected, was null"), 1e-6)

class BaselinesTest {
    @Test
    fun quietAsleepIsTheHoursWeightedMedian() {
        val b = baseline(asleep(0, 120, 5.0), asleep(120, 300, 9.0), asleep(300, 420, 40.0))
        assertNear(9.0, b.quietAsleepMa)
    }

    @Test
    fun quietAsleepNeedsSixHoursAsleep() {
        assertNull(baseline(asleep(0, 330, 9.0)).quietAsleepMa)
        assertNear(9.0, baseline(asleep(0, 360, 9.0)).quietAsleepMa)
    }

    @Test
    fun shortEpisodesAndChargelessOnesStayOutOfTheRates() {
        val withShort = baseline(
            asleep(0, 345, 9.0), asleep(345, 353, 9.0), asleep(353, 361, 9.0), asleep(361, 369, 9.0), asleep(369, 377, 9.0),
        )
        assertNull(withShort.quietAsleepMa)
        assertNull(baseline(asleep(0, 300, null), asleep(300, 330, 9.0)).quietAsleepMa)
    }

    @Test
    fun dayToDaySpreadIsTheInterquartileRangeOfTheDailyRates() {
        val b = baseline(
            asleep(60, 240, 8.0),
            asleep(DAY + 60, DAY + 240, 9.0),
            asleep(2 * DAY + 60, 2 * DAY + 240, 10.0),
            asleep(3 * DAY + 60, 3 * DAY + 240, 11.0),
            asleep(4 * DAY + 60, 4 * DAY + 240, 12.0),
        )
        assertNear(2.0, b.dayToDaySpreadMa)
        assertNear(10.0, b.quietAsleepMa)
    }

    @Test
    fun daysWithLessThanTwoHoursAsleepAreLeftOutOfTheSpread() {
        val b = baseline(
            asleep(60, 240, 8.0),
            asleep(DAY + 60, DAY + 240, 9.0),
            asleep(2 * DAY + 60, 2 * DAY + 240, 10.0),
            asleep(3 * DAY + 60, 3 * DAY + 150, 100.0),
        )
        assertNear(1.0, b.dayToDaySpreadMa)
    }

    @Test
    fun fewerThanThreeDaysHaveNoSpread() {
        val b = baseline(asleep(60, 240, 8.0), asleep(DAY + 60, DAY + 240, 9.0))
        assertNull(b.dayToDaySpreadMa)
    }

    @Test
    fun aNightAcrossMidnightCountsOnEachDay() {
        val rates = Baselines.dailyRates(
            listOf(episode(EpisodeKind.Asleep, 22 * 60, 26 * 60, 10.0)),
            Long.MIN_VALUE,
            Long.MAX_VALUE,
            2.0,
        )
        assertEquals(listOf(10.0, 10.0), rates)
    }

    @Test
    fun inUseNeedsHalfAnHourAndPoolsTheCharge() {
        assertNear(300.0, baseline(episode(EpisodeKind.InUse, 0, 15, 400.0), episode(EpisodeKind.InUse, 15, 30, 200.0)).inUseMa)
        assertNull(baseline(episode(EpisodeKind.InUse, 0, 15, 400.0)).inUseMa)
    }

    @Test
    fun asleepShareCountsUnpluggedTimeOnly() {
        val b = baseline(
            asleep(0, 360, 9.0),
            episode(EpisodeKind.InUse, 360, 480, 300.0),
            episode(EpisodeKind.DisplayOn, 480, 600, 300.0),
            episode(EpisodeKind.Charging, 600, 900, null),
        )
        assertNear(0.6, b.asleepShare)
        assertNear(10.0 / 24, b.basisDays)
        assertNear(9.0, b.quietAsleepMa)
    }

    @Test
    fun asleepShareNeedsAnHourOfUnpluggedTime() {
        val b = baseline(asleep(0, 30, 9.0), episode(EpisodeKind.InUse, 30, 45, 300.0))
        assertNull(b.asleepShare)
        assertNear(0.75 / 24, b.basisDays)
    }

    @Test
    fun episodesBeforeTheTwentyEightDayWindowAreIgnored() {
        val b = baseline(asleep(0, 600, 9.0), asleep(20_000, 20_600, 20.0), nowMin = 50_000)
        assertNear(20.0, b.quietAsleepMa)
        assertNear(10.0 / 24, b.basisDays)
    }
}
