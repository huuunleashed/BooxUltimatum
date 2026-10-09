package app.booxultimatum.ui.battery

import app.booxultimatum.core.battery.Episode
import app.booxultimatum.core.battery.EpisodeKind
import app.booxultimatum.core.battery.StateTotal
import app.booxultimatum.core.battery.Timeline
import app.booxultimatum.ui.screens.DayStandby
import app.booxultimatum.ui.screens.dailyStandby
import app.booxultimatum.ui.screens.medianOf
import app.booxultimatum.ui.screens.unpluggedAverageMa
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryMathTest {
    private val zone = TimeZone.getTimeZone("GMT+07:00")
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val day0 = LocalDate.of(2026, 10, 8).atStartOfDay(ZoneOffset.ofHours(7)).toInstant().toEpochMilli()

    private fun episode(kind: EpisodeKind, fromMs: Long, toMs: Long, mah: Double?) =
        Episode(kind = kind, startMs = fromMs, endMs = toMs, level0 = 80, level1 = 79, mah = mah, sleptShare = 0.0, topApp = null)

    private fun timeline(vararg episodes: Episode) = Timeline(episodes.toList(), emptyList(), 3700.0, day0, day0 + 3 * day)

    private fun assertDay(expected: DayStandby, actual: DayStandby) {
        assertEquals(expected.dayStartMs, actual.dayStartMs)
        assertEquals(expected.ma, actual.ma, 1e-9)
        assertEquals(expected.asleepHours, actual.asleepHours, 1e-9)
        assertEquals(expected.displayOn, actual.displayOn)
    }

    @Test fun aSleepingDayGivesItsChargePerHour() {
        val days = dailyStandby(timeline(episode(EpisodeKind.Asleep, day0 + hour, day0 + 7 * hour, 60.0)), zone)
        assertEquals(1, days.size)
        assertDay(DayStandby(day0, 10.0, 6.0, false), days[0])
    }

    @Test fun anEpisodeCrossingMidnightCountsOnBothDays() {
        val days = dailyStandby(timeline(episode(EpisodeKind.Asleep, day0 - hour, day0 + hour, 40.0)), zone)
        assertEquals(listOf(day0 - day, day0), days.map { it.dayStartMs })
        assertDay(DayStandby(day0 - day, 20.0, 1.0, false), days[0])
        assertDay(DayStandby(day0, 20.0, 1.0, false), days[1])
    }

    @Test fun aDayWithLessThanAnHourAsleepIsLeftOut() {
        val days = dailyStandby(
            timeline(
                episode(EpisodeKind.Asleep, day0 + 2 * hour, day0 + 2 * hour + 30 * minute, 5.0),
                episode(EpisodeKind.Asleep, day0 + day + hour, day0 + day + 4 * hour, 30.0),
            ),
            zone,
        )
        assertEquals(listOf(day0 + day), days.map { it.dayStartMs })
        assertDay(DayStandby(day0 + day, 10.0, 3.0, false), days[0])
    }

    @Test fun tenMinutesOfDisplayOnWhileAsleepHatchesTheDayAndNineDoesNot() {
        val days = dailyStandby(
            timeline(
                episode(EpisodeKind.Asleep, day0 + hour, day0 + 3 * hour, 20.0),
                episode(EpisodeKind.DisplayOn, day0 + 3 * hour, day0 + 3 * hour + 10 * minute, 4.0),
                episode(EpisodeKind.Asleep, day0 + 3 * hour + 10 * minute, day0 + 7 * hour + 10 * minute, 40.0),
                episode(EpisodeKind.Asleep, day0 + day + hour, day0 + day + 3 * hour, 20.0),
                episode(EpisodeKind.DisplayOn, day0 + day + 3 * hour, day0 + day + 3 * hour + 9 * minute, 3.0),
            ),
            zone,
        )
        assertDay(DayStandby(day0, 10.0, 6.0, true), days[0])
        assertDay(DayStandby(day0 + day, 10.0, 2.0, false), days[1])
    }

    @Test fun aDisplayOnEpisodeCrossingMidnightHatchesBothDays() {
        val days = dailyStandby(
            timeline(
                episode(EpisodeKind.Asleep, day0 - 3 * hour, day0 - hour, 20.0),
                episode(EpisodeKind.DisplayOn, day0 - 5 * minute, day0 + 5 * minute, 2.0),
                episode(EpisodeKind.Asleep, day0 + hour, day0 + 3 * hour, 20.0),
            ),
            zone,
        )
        assertEquals(listOf(true, true), days.map { it.displayOn })
    }

    @Test fun onlyAsleepEpisodesWithChargeCount() {
        val days = dailyStandby(
            timeline(
                episode(EpisodeKind.InUse, day0, day0 + hour, 100.0),
                episode(EpisodeKind.Charging, day0 + 2 * hour, day0 + 3 * hour, 200.0),
                episode(EpisodeKind.Asleep, day0 + 4 * hour, day0 + 6 * hour, null),
            ),
            zone,
        )
        assertEquals(emptyList(), days)
    }

    @Test fun theMedianOfAnEvenOrOddCountOrNone() {
        assertEquals(3.0, medianOf(listOf(3.0)))
        assertEquals(2.0, medianOf(listOf(5.0, 1.0, 2.0)))
        assertEquals(2.5, medianOf(listOf(4.0, 1.0, 3.0, 2.0)))
        assertNull(medianOf(emptyList()))
    }

    @Test fun theUnpluggedAverageLeavesOutCharging() {
        val states = listOf(
            StateTotal(EpisodeKind.InUse, 1.0, 120.0),
            StateTotal(EpisodeKind.Asleep, 3.0, 30.0),
            StateTotal(EpisodeKind.DisplayOn, 0.5, 60.0),
            StateTotal(EpisodeKind.Charging, 2.0, 300.0),
        )
        assertEquals(210.0 / 4.5, unpluggedAverageMa(states)!!, 1e-9)
        assertNull(unpluggedAverageMa(listOf(StateTotal(EpisodeKind.Charging, 2.0, 300.0))))
    }
}
