package app.booxultimatum.core.battery

import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelinesTest {
    private val tz = ZoneOffset.ofHours(7)
    private val minute = 60_000L
    private val start = 1_790_000_000_000L

    /** A row [atMin] minutes after [start]; the processor was awake for [uptimeMin] of those minutes (default: all of them). */
    private fun row(
        atMin: Long,
        level: Int,
        mah: Double?,
        uptimeMin: Double = atMin.toDouble(),
        reason: String = "level",
        plugged: Boolean = false,
        screenOn: Boolean = false,
        boot: Int? = 1,
        display: DisplayState? = null,
        top: String = "",
    ): BatteryRow {
        val elapsed = atMin * minute
        return BatteryRow(
            epoch = start + elapsed, reason = reason, level = level, chargeMah = mah, voltageMv = null, tempC = null,
            plugged = plugged, interactive = screenOn, elapsedMs = elapsed, uptimeMs = (uptimeMin * minute).toLong(),
            bootCount = boot, currentMa = null, frontlight = null, warmth = null, wifi = "", idle = "", saver = false,
            display = display, top = top, note = "",
        )
    }

    private fun at(text: String): Long = LocalDateTime.parse(text).toInstant(tz).toEpochMilli()

    private fun aboutAt(text: String, ms: Long): Boolean = abs(ms - at(text)) <= 10 * minute

    private fun assertAbout(text: String, ms: Long) {
        assertTrue(aboutAt(text, ms), "expected about $text, was ${Instant.ofEpochMilli(ms).atOffset(tz)}")
    }

    private val night: Timeline by lazy {
        val rows = javaClass.getResourceAsStream("/battery/night-2026-10-08.csv")!!
            .bufferedReader().useLines { BatteryRows.parse(it) }
        Timelines.build(rows, nowMs = at("2026-10-09T08:30"))
    }

    @Test fun stuckNightReadsAsTwoInferredDisplayOnEpisodes() {
        val on = night.episodes.filter { it.kind == EpisodeKind.DisplayOn }
        assertEquals(2, on.size)
        assertAbout("2026-10-08T18:46", on[0].startMs)
        assertAbout("2026-10-08T21:01", on[0].endMs)
        assertAbout("2026-10-08T23:54", on[1].startMs)
        assertAbout("2026-10-09T06:17", on[1].endMs)
        assertTrue(on.all { it.inferred && it.sleptShare < 0.1 })
    }

    @Test fun displayOnEpisodesDrawBetween2400And3200Mah() {
        val drawn = night.episodes.filter { it.kind == EpisodeKind.DisplayOn }.sumOf { it.mah ?: 0.0 }
        assertTrue(drawn in 2400.0..3200.0, "drew $drawn mAh")
    }

    @Test fun quietSleepsEitherSideOfTheStuckDisplayDrawLittle() {
        val asleep = night.episodes.filter { it.kind == EpisodeKind.Asleep }
        val before = asleep.single { aboutAt("2026-10-08T17:07", it.startMs) }
        assertTrue(before.avgMa!! < 40, "drew ${before.avgMa} mA")
        val between = asleep.single { aboutAt("2026-10-08T22:16", it.startMs) }
        assertAbout("2026-10-08T23:54", between.endMs)
        // 23:20 to 23:54 drew 88 mA with the processor awake. That is under the 120 mA rule, so it stays Asleep and
        // lifts the episode's average to about 46 mA.
        assertTrue(between.avgMa!! < 50, "drew ${between.avgMa} mA")
    }

    @Test fun restartAfterTheBatteryDiedIsOffUntilTheBoot() {
        val off = night.episodes.single { it.kind == EpisodeKind.Off }
        assertAbout("2026-10-09T06:17", off.startMs)
        assertAbout("2026-10-09T07:05", off.endMs)
    }

    @Test fun inUseCoversTheEveningUse() {
        assertTrue(
            night.episodes.any {
                it.kind == EpisodeKind.InUse && aboutAt("2026-10-08T21:03", it.startMs) && aboutAt("2026-10-08T21:58", it.endMs)
            },
        )
    }

    @Test fun capacityIsLearnedFromTheLastReadingAtHalfChargeOrMore() {
        assertTrue(night.capacityMah in 3500.0..3900.0)
        assertEquals(3754.0, night.capacityMah, 0.5) // 1 877 mAh at 50 %
    }

    @Test fun aBootCountChangeMakesTheSegmentOff() {
        val t = Timelines.build(listOf(row(0, 80, 3000.0, boot = 6), row(30, 79, 2960.0, boot = 7)), nowMs = start)
        assertEquals(EpisodeKind.Off, t.segments.single().kind)
        assertNull(t.segments.single().mah)
    }

    @Test fun aGapOfMoreThanADayIsOff() {
        val t = Timelines.build(listOf(row(0, 80, 3000.0), row(25 * 60, 70, 2600.0)), nowMs = start)
        assertEquals(EpisodeKind.Off, t.segments.single().kind)
    }

    @Test fun aDisplayColumnOfOnMakesDisplayOnThatIsNotInferred() {
        val t = Timelines.build(
            listOf(
                row(0, 80, 3000.0, display = DisplayState.On),
                row(60, 79, 2960.0, display = DisplayState.On),
            ),
            nowMs = start,
        )
        val segment = t.segments.single()
        assertEquals(EpisodeKind.DisplayOn, segment.kind)
        assertFalse(segment.inferred)
    }

    @Test fun chargingAddsTheCounterRiseOrTheLevelStepAtTheDefaultCapacity() {
        val counted = Timelines.build(
            listOf(row(0, 60, 2000.0, plugged = true), row(30, 62, 2100.0, plugged = true)),
            nowMs = start,
        )
        assertEquals(EpisodeKind.Charging, counted.segments.single().kind)
        assertEquals(100.0, counted.segments.single().mah)
        val byLevel = Timelines.build(
            listOf(row(0, 50, null, plugged = true), row(60, 60, null, plugged = true)),
            nowMs = start,
        )
        assertEquals(370.0, byLevel.segments.single().mah) // 10 % of 3 700 mAh
    }

    @Test fun aLongHighDrawWithTheProcessorAwakeIsInferredDisplayOn() {
        val t = Timelines.build(
            listOf(
                row(0, 90, 3000.0, reason = "screen_off", uptimeMin = 0.0),
                row(60, 85, 2650.0, uptimeMin = 60.0),
            ),
            nowMs = start,
        )
        val episode = t.episodes.single()
        assertEquals(EpisodeKind.DisplayOn, episode.kind)
        assertTrue(episode.inferred)
    }

    @Test fun aQuietSleepWithTheProcessorAsleepIsAsleep() {
        val t = Timelines.build(
            listOf(
                row(0, 90, 3000.0, reason = "screen_off", uptimeMin = 0.0),
                row(60, 89, 2991.0, uptimeMin = 6.0),
            ),
            nowMs = start,
        )
        assertEquals(EpisodeKind.Asleep, t.episodes.single().kind)
    }

    @Test fun aShortSegmentContinuesADisplayOnRunButCannotStartOne() {
        val t = Timelines.build(
            listOf(
                row(0, 90, 3000.0, reason = "screen_off", uptimeMin = 0.0),
                row(3, 89, 2990.0, uptimeMin = 3.0),
                row(63, 85, 2650.0, uptimeMin = 63.0),
                row(66, 84, 2640.0, uptimeMin = 66.0),
            ),
            nowMs = start,
        )
        assertEquals(listOf(EpisodeKind.Asleep, EpisodeKind.DisplayOn), t.episodes.map { it.kind })
        assertEquals(66 * minute, t.episodes.last().endMs - start)
    }

    @Test fun inUseTakesTheAppInFrontForTheMostHoursFromTheRows() {
        val t = Timelines.build(
            listOf(
                row(0, 80, 3000.0, reason = "screen_on", screenOn = true, top = "a.reader"),
                row(20, 79, 2960.0, screenOn = true, top = "b.mail"),
                row(60, 77, 2900.0, reason = "screen_off"),
            ),
            nowMs = start,
        )
        assertEquals("b.mail", t.episodes.single().topApp)
    }

    @Test fun inUseFallsBackToTheForegroundSpanWhenTheRowsHaveNoTop() {
        val rows = listOf(row(0, 80, 3000.0, reason = "screen_on", screenOn = true), row(60, 77, 2900.0, reason = "screen_off"))
        val spans = listOf(
            ForegroundSpan("c.maps", start, start + 45 * minute),
            ForegroundSpan("d.notes", start + 45 * minute, start + 60 * minute),
        )
        assertEquals("c.maps", Timelines.build(rows, nowMs = start, foreground = spans).episodes.single().topApp)
        assertNull(Timelines.build(rows, nowMs = start).episodes.single().topApp)
    }

    @Test fun capacityIsTheLastCounterReadingAtHalfChargeOrMoreOtherwise3700() {
        val learned = Timelines.build(listOf(row(0, 60, 2400.0), row(30, 55, 2100.0)), nowMs = start)
        assertEquals(2100.0 * 100 / 55, learned.capacityMah, 1e-9)
        val fallback = Timelines.build(listOf(row(0, 40, 1480.0)), nowMs = start)
        assertEquals(3700.0, fallback.capacityMah)
    }

    @Test fun noRowsGiveAnEmptyTimelineAtTheDefaultCapacity() {
        val t = Timelines.build(emptyList(), nowMs = start)
        assertTrue(t.episodes.isEmpty())
        assertTrue(t.segments.isEmpty())
        assertEquals(3700.0, t.capacityMah)
        assertEquals(start, t.fromMs)
        assertEquals(start, t.toMs)
    }

    @Test fun theTimelineRunsFromTheFirstRowToNowOrToTheLastRowWhicheverIsLater() {
        val rows = listOf(row(0, 80, 3000.0), row(60, 79, 2960.0))
        val early = Timelines.build(rows, nowMs = start + 30 * minute)
        assertEquals(start, early.fromMs)
        assertEquals(start + 60 * minute, early.toMs)
        assertEquals(start + 90 * minute, Timelines.build(rows, nowMs = start + 90 * minute).toMs)
    }
}
