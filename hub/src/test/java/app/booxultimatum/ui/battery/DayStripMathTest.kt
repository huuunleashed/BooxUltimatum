package app.booxultimatum.ui.battery

import app.booxultimatum.core.battery.Episode
import app.booxultimatum.core.battery.EpisodeKind
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HOUR_IN_MS = 3_600_000L

private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

private fun episode(from: String, to: String) = Episode(EpisodeKind.Asleep, at(from), at(to), 50, 40, null, 0.0, null)

class DayStripMathTest {
    @Test
    fun overlappingEpisodesAreKeptInTimeOrder() {
        val from = at("2026-10-08T00:00:00Z")
        val to = at("2026-10-09T00:00:00Z")
        val straddleStart = episode("2026-10-07T23:00:00Z", "2026-10-08T02:00:00Z")
        val inside = episode("2026-10-08T12:00:00Z", "2026-10-08T13:00:00Z")
        val straddleEnd = episode("2026-10-08T23:30:00Z", "2026-10-09T01:00:00Z")
        val endsAtFrom = episode("2026-10-07T20:00:00Z", "2026-10-08T00:00:00Z")
        val startsAtTo = episode("2026-10-09T00:00:00Z", "2026-10-09T02:00:00Z")

        val overlapping = episodesOverlapping(listOf(inside, startsAtTo, straddleEnd, endsAtFrom, straddleStart), from, to)

        assertEquals(listOf(straddleStart, inside, straddleEnd), overlapping)
    }

    @Test
    fun timeMapsLinearlyAcrossTheChart() {
        val from = at("2026-10-08T00:00:00Z")
        val to = from + 24 * HOUR_IN_MS
        assertEquals(64f, timeToX(from, from, to, 64f, 800f))
        assertEquals(464f, timeToX(from + 12 * HOUR_IN_MS, from, to, 64f, 800f))
        assertEquals(864f, timeToX(to, from, to, 64f, 800f))
    }

    @Test
    fun dayWindowTicksEveryThirdLocalHour() {
        val from = at("2026-10-08T22:00:00Z")
        val to = at("2026-10-09T22:00:00Z")

        val ticks = tickTimes(from, to, ZoneOffset.UTC)

        assertEquals((0..7).map { at("2026-10-09T00:00:00Z") + it * 3 * HOUR_IN_MS }, ticks)
        assertEquals(
            listOf("00", "03", "06", "09", "12", "15", "18", "21"),
            ticks.map { tickText(it, ZoneOffset.UTC, daily = false, locale = Locale.US) },
        )
    }

    @Test
    fun ticksFollowTheLocalZone() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        val ticks = tickTimes(at("2026-10-08T22:00:00Z"), at("2026-10-09T22:00:00Z"), kolkata)

        assertEquals(8, ticks.size)
        assertEquals(at("2026-10-09T00:30:00Z"), ticks.first())
        ticks.forEach { ms ->
            val local = Instant.ofEpochMilli(ms).atZone(kolkata)
            assertEquals(0, local.minute)
            assertEquals(0, local.hour % 3)
        }
    }

    @Test
    fun ticksFollowDaylightSaving() {
        val london = ZoneId.of("Europe/London")
        val ticks = tickTimes(at("2026-03-28T22:00:00Z"), at("2026-03-29T22:00:00Z"), london)

        assertEquals(8, ticks.size)
        assertTrue(at("2026-03-29T02:00:00Z") in ticks)
    }

    @Test
    fun windowsOverThirtySixHoursTickEveryMidnight() {
        assertFalse(isDailyTicks(0, 36 * HOUR_IN_MS))
        assertTrue(isDailyTicks(0, 36 * HOUR_IN_MS + 1))

        val ticks = tickTimes(at("2026-10-07T12:00:00Z"), at("2026-10-10T12:00:00Z"), ZoneOffset.UTC)

        assertEquals(listOf(at("2026-10-08T00:00:00Z"), at("2026-10-09T00:00:00Z"), at("2026-10-10T00:00:00Z")), ticks)
        assertEquals("Thu", tickText(ticks.first(), ZoneOffset.UTC, daily = true, locale = Locale.US))
    }

    @Test
    fun keysStepThroughEpisodesAndStopAtTheEnds() {
        assertEquals(0, stepTo(null, 3, forward = true))
        assertEquals(2, stepTo(null, 3, forward = false))
        assertEquals(1, stepTo(0, 3, forward = true))
        assertNull(stepTo(0, 3, forward = false))
        assertNull(stepTo(2, 3, forward = true))
        assertEquals(1, stepTo(2, 3, forward = false))
        assertEquals(0, stepTo(7, 3, forward = true))
        assertNull(stepTo(null, 0, forward = true))
    }
}
