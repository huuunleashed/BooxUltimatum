package app.booxultimatum.core.battery

import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The owner's own log from 2026-10-12 14:40 to 2026-10-13 07:12, after Android restarted by itself on 10-11 and before the
 * tablet was restarted (app names removed): seven stretches of 28 to 39 mA that were wrongly "display on while asleep"
 * Problems, sleeps in which the processor never slept, and the night the display really stuck on and the battery ran out.
 */
class RestartDayTest {
    private val tz = ZoneOffset.ofHours(7)
    private val minute = 60_000L
    private fun at(text: String): Long = LocalDateTime.parse(text).toInstant(tz).toEpochMilli()
    private val now = at("2026-10-13T07:12")

    private val timeline: Timeline by lazy {
        val rows = javaClass.getResourceAsStream("/battery/restart-2026-10-12.csv")!!
            .bufferedReader().useLines { BatteryRows.parse(it) }
        Timelines.build(rows, nowMs = now)
    }

    private val verdict: Verdict by lazy {
        val signals = Signals(hubBackgroundAllowed = true, recordingEnabled = true, shizukuRunning = true, restartAtMs = null)
        Findings.evaluate(timeline, Baselines.of(timeline, now), signals, now)
    }

    @Test fun onlyTheStuckNightIsDisplayOn() {
        val on = timeline.episodes.filter { it.kind == EpisodeKind.DisplayOn && it.hours >= 10.0 / 60 }
        assertEquals(1, on.size)
        assertTrue(abs(on.single().startMs - at("2026-10-13T01:43")) <= 10 * minute)
        assertTrue(abs(on.single().endMs - at("2026-10-13T06:31")) <= 10 * minute)
        assertTrue((on.single().avgMa ?: 0.0) > 300.0)
    }

    @Test fun theSleepsAroundItAreOnesInWhichTheProcessorStayedAwake() {
        val awake = timeline.episodes.filter { it.processorStayedAwake && it.hours >= 0.5 }
        assertTrue(awake.size >= 3, "found ${awake.size}")
        assertTrue(awake.all { (it.avgMa ?: 0.0) < 60.0 })
    }

    @Test fun theFindingsAreOneProblemAndOnlyWatchesBesides() {
        val problems = verdict.findings.filter { it.severity == Severity.Problem }
        assertEquals(listOf(FindingKind.DisplayOn), problems.map { it.kind })
        assertTrue(verdict.findings.count { it.kind == FindingKind.NotSleeping } >= 3)
        assertTrue(verdict.findings.none { it.kind == FindingKind.StandbyHigh })
    }

    @Test fun theirHoursDoNotBecomeWhatIsNormal() {
        assertNull(Baselines.of(timeline, now).quietAsleepMa)
    }
}