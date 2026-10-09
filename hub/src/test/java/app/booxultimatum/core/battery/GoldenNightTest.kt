package app.booxultimatum.core.battery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The night of 2026-10-08 as the log recorded it, read end to end: rows, timeline, baseline, findings. */
class GoldenNightTest {
    private val rows = BatteryRows.parse(
        checkNotNull(javaClass.getResourceAsStream("/battery/night-2026-10-08.csv")).bufferedReader().lineSequence(),
    )
    private val nowMs = rows.last().epoch + 3_600_000L
    private val timeline = Timelines.build(rows, nowMs)
    private val baseline = Baselines.of(timeline, nowMs)
    private val signals = Signals(hubBackgroundAllowed = true, recordingEnabled = true, shizukuRunning = true, restartAtMs = null)

    @Test
    fun theNightIsAProblemWithTwoDisplayOnFindings() {
        val verdict = Findings.evaluate(timeline, baseline, signals, nowMs)
        assertEquals(VerdictState.Problem, verdict.state)
        val displayOn = verdict.findings.filter { it.kind == FindingKind.DisplayOn }
        assertEquals(2, displayOn.size)
        assertTrue(displayOn.all { it.severity == Severity.Problem && it.inferred })
        assertTrue(displayOn.sumOf { it.mah ?: 0.0 } in 2400.0..3200.0)
    }

    @Test
    fun theWorstFindingComesFirst() {
        val verdict = Findings.evaluate(timeline, baseline, signals, nowMs)
        assertEquals(FindingKind.DisplayOn, verdict.findings.first().kind)
    }

    @Test
    fun oneNightIsNotEnoughForABaseline() {
        assertNull(baseline.quietAsleepMa)
        assertTrue(timeline.episodes.filter { it.kind == EpisodeKind.Asleep }.all { (it.avgMa ?: 0.0) < 60.0 })
    }

    @Test
    fun theBatteryRanOutAndTheTabletCameBack() {
        val verdict = Findings.evaluate(timeline, baseline, signals, nowMs)
        assertTrue(verdict.findings.any { it.kind == FindingKind.Died } || timeline.episodes.any { it.kind == EpisodeKind.Off })
    }
}
