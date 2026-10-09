package app.booxultimatum.core.battery

import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SummariesTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val start = 1_790_000_000_000L
    private val zone = TimeZone.getTimeZone("GMT+07:00")

    private fun at(atMin: Long): Long = start + atMin * minute

    private fun episode(kind: EpisodeKind, fromMs: Long, toMs: Long, mah: Double?, level0: Int = 80, level1: Int = 79) = Episode(
        kind = kind, startMs = fromMs, endMs = toMs, level0 = level0, level1 = level1, mah = mah, sleptShare = 0.0, topApp = null,
    )

    private fun timeline(vararg episodes: Episode) = Timeline(episodes.toList(), emptyList(), 3700.0, start, start + 10 * hour)

    private fun row(atMin: Long, top: String = ""): BatteryRow = BatteryRow(
        epoch = at(atMin), reason = "level", level = 80, chargeMah = null, voltageMv = null, tempC = null, plugged = false,
        interactive = true, elapsedMs = atMin * minute, uptimeMs = atMin * minute, bootCount = 1, currentMa = null,
        frontlight = null, warmth = null, wifi = "", idle = "", saver = false, display = null, top = top, note = "",
    )

    private fun inUse(fromMin: Long, toMin: Long, mah: Double, top: String = "") =
        Segment(from = row(fromMin, top), to = row(toMin), kind = EpisodeKind.InUse, mah = mah, sleptShare = 0.0)

    private fun segments(vararg segments: Segment) = Timeline(emptyList(), segments.toList(), 3700.0, start, start + 2 * hour)

    @Test fun byStateClipsEpisodesToTheWindowAndCountsACrossingOneInProportion() {
        val t = timeline(
            episode(EpisodeKind.Asleep, at(0), at(60), 60.0),
            episode(EpisodeKind.InUse, at(60), at(120), 120.0),
            episode(EpisodeKind.DisplayOn, at(120), at(180), 90.0),
        )
        val totals = Summaries.byState(t, at(30), at(90))
        assertEquals(listOf(EpisodeKind.InUse, EpisodeKind.Asleep), totals.map { it.kind })
        assertEquals(0.5, totals[0].hours, 1e-9)
        assertEquals(60.0, totals[0].mah, 1e-9)
        assertEquals(0.5, totals[1].hours, 1e-9)
        assertEquals(30.0, totals[1].mah, 1e-9)
    }

    @Test fun byStateListsEachKindPresentInTheWindowInOrder() {
        val t = timeline(
            episode(EpisodeKind.DisplayOn, at(120), at(180), 90.0),
            episode(EpisodeKind.InUse, at(60), at(120), 120.0),
            episode(EpisodeKind.Asleep, at(0), at(60), 60.0),
        )
        val totals = Summaries.byState(t, at(0), at(180))
        assertEquals(listOf(EpisodeKind.InUse, EpisodeKind.Asleep, EpisodeKind.DisplayOn), totals.map { it.kind })
        assertEquals(listOf(120.0, 60.0, 90.0), totals.map { it.mah })
    }

    @Test fun byDaySplitsAtLocalMidnightAndLeavesChargingAndOffOutOfUsedCharge() {
        val day0 = LocalDate.of(2026, 10, 8).atStartOfDay(ZoneOffset.ofHours(7)).toInstant().toEpochMilli()
        val t = timeline(
            episode(EpisodeKind.DisplayOn, day0 + 2 * hour, day0 + 3 * hour, 30.0),
            episode(EpisodeKind.InUse, day0 + 9 * hour, day0 + 10 * hour, 50.0),
            episode(EpisodeKind.Charging, day0 + 12 * hour, day0 + 13 * hour, 100.0),
            episode(EpisodeKind.Off, day0 + 14 * hour, day0 + 15 * hour, null),
            episode(EpisodeKind.Asleep, day0 + 23 * hour, day0 + 25 * hour, 20.0),
        )
        val days = Summaries.byDay(t, zone, day0, day0 + 25 * hour)
        assertEquals(listOf(day0, day0 + 24 * hour), days.map { it.dayStartMs })
        val first = days[0]
        assertEquals(1.0, first.asleepHours, 1e-9)
        assertEquals(1.0, first.inUseHours, 1e-9)
        assertEquals(1.0, first.displayOnHours, 1e-9)
        assertEquals(1.0, first.chargingHours, 1e-9)
        assertEquals(30.0, first.displayOnMah, 1e-9)
        assertEquals(90.0, first.usedMah, 1e-9) // 10 asleep, 50 in use, 30 display on
        val second = days[1]
        assertEquals(1.0, second.asleepHours, 1e-9)
        assertEquals(0.0, second.chargingHours, 1e-9)
        assertEquals(10.0, second.usedMah, 1e-9)
    }

    @Test fun byDayClipsToTheWindowAndLeavesOutDaysWithNoTimeInIt() {
        val day0 = LocalDate.of(2026, 10, 8).atStartOfDay(ZoneOffset.ofHours(7)).toInstant().toEpochMilli()
        val t = timeline(episode(EpisodeKind.Asleep, day0 + 23 * hour, day0 + 25 * hour, 20.0))
        val clipped = Summaries.byDay(t, zone, day0 + 23 * hour + 30 * minute, day0 + 24 * hour + 30 * minute)
        assertEquals(listOf(0.5, 0.5), clipped.map { it.asleepHours })
        val offOnly = timeline(episode(EpisodeKind.Off, day0, day0 + 2 * hour, null))
        assertTrue(Summaries.byDay(offOnly, zone, day0, day0 + 2 * hour).isEmpty())
    }

    @Test fun sessionsRunFromUnplugToPlugNewestLast() {
        val t = timeline(
            episode(EpisodeKind.InUse, at(0), at(10), 30.0, level0 = 90, level1 = 88),
            episode(EpisodeKind.Asleep, at(10), at(70), 20.0, level0 = 88, level1 = 84),
            episode(EpisodeKind.Charging, at(70), at(80), 300.0, level0 = 84, level1 = 95),
            episode(EpisodeKind.Asleep, at(80), at(120), 10.0, level0 = 95, level1 = 94),
            episode(EpisodeKind.Off, at(120), at(125), null, level0 = 94, level1 = 94),
            episode(EpisodeKind.DisplayOn, at(125), at(155), 70.0, level0 = 93, level1 = 80),
        )
        val sessions = Summaries.sessions(t)
        assertEquals(3, sessions.size)
        assertEquals(at(0), sessions[0].startMs)
        assertEquals(at(70), sessions[0].endMs)
        assertEquals(90, sessions[0].level0)
        assertEquals(84, sessions[0].level1)
        assertEquals(50.0, sessions[0].mah, 1e-9)
        assertEquals(10.0 / 60, sessions[0].inUseHours, 1e-9)
        assertEquals(1.0, sessions[0].asleepHours, 1e-9)
        assertEquals(at(80), sessions[1].startMs)
        assertEquals(at(120), sessions[1].endMs)
        assertEquals(10.0, sessions[1].mah, 1e-9)
        assertEquals(40.0 / 60, sessions[1].asleepHours, 1e-9)
        assertEquals(at(125), sessions[2].startMs)
        assertEquals(93, sessions[2].level0)
        assertEquals(80, sessions[2].level1)
        assertEquals(0.5, sessions[2].displayOnHours, 1e-9)
    }

    @Test fun byAppGivesEachAppInFrontItsShareOfTheChargeByTime() {
        val apps = Summaries.byApp(
            segments(inUse(0, 60, 90.0)),
            at(0),
            at(60),
            listOf(ForegroundSpan("A", at(0), at(40)), ForegroundSpan("B", at(40), at(60))),
        )
        assertEquals(listOf("A", "B"), apps.map { it.pkg })
        assertEquals(60.0, apps[0].mah, 1e-9) // two thirds of 90 mAh
        assertEquals(40.0 / 60, apps[0].hours, 1e-9)
        assertEquals(30.0, apps[1].mah, 1e-9)
        assertEquals(20.0 / 60, apps[1].hours, 1e-9)
    }

    @Test fun timeNoSpanCoversGoesToTheRowsTopApp() {
        val apps = Summaries.byApp(segments(inUse(0, 60, 60.0, top = "C")), at(0), at(60), listOf(ForegroundSpan("A", at(0), at(40))))
        assertEquals(listOf("A", "C"), apps.map { it.pkg })
        assertEquals(40.0, apps[0].mah, 1e-9)
        assertEquals(20.0, apps[1].mah, 1e-9)
    }

    @Test fun chargeWithNoKnownAppGoesToAnAppWithAnEmptyName() {
        val apps = Summaries.byApp(segments(inUse(0, 60, 60.0)), at(0), at(60))
        assertEquals(listOf(""), apps.map { it.pkg })
        assertEquals(60.0, apps[0].mah, 1e-9)
    }

    @Test fun byAppCountsOnlyTheWindowAndOnlyInUseSegments() {
        val t = segments(
            inUse(0, 60, 90.0, top = "A"),
            Segment(from = row(0), to = row(60), kind = EpisodeKind.Asleep, mah = 50.0, sleptShare = 0.0),
        )
        val apps = Summaries.byApp(t, at(30), at(60))
        assertEquals(listOf("A"), apps.map { it.pkg })
        assertEquals(45.0, apps[0].mah, 1e-9)
        assertEquals(0.5, apps[0].hours, 1e-9)
    }

    @Test fun appsWithNoChargeAndUnderAMinuteAreDropped() {
        val apps = Summaries.byApp(
            segments(inUse(0, 60, 0.0)),
            at(0),
            at(60),
            listOf(ForegroundSpan("X", at(0), start + minute / 2), ForegroundSpan("Y", start + minute / 2, at(60))),
        )
        assertEquals(listOf("Y"), apps.map { it.pkg })
    }
}
