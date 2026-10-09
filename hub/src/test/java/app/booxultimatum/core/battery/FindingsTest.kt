package app.booxultimatum.core.battery

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

private const val T0 = 1_767_225_600_000L
private const val NOW_MIN = 2_000

private fun at(minutes: Int): Long = T0 + minutes * 60_000L

private fun episode(
    kind: EpisodeKind,
    fromMin: Int,
    toMin: Int,
    ma: Double?,
    slept: Double = 0.9,
    level0: Int = 80,
    level1: Int = 79,
    inferred: Boolean = false,
) = Episode(kind, at(fromMin), at(toMin), level0, level1, ma?.let { it * (toMin - fromMin) / 60.0 }, slept, null, inferred)

private val BASE = Baseline(quietAsleepMa = 9.0, dayToDaySpreadMa = 1.0, inUseMa = 291.0, asleepShare = 0.78, basisDays = 7.0)

private val SIGNALS = Signals(hubBackgroundAllowed = true, recordingEnabled = true, shizukuRunning = true, restartAtMs = null)

private fun restartAt(minutes: Int) = SIGNALS.copy(restartAtMs = at(minutes))

private fun verdict(
    vararg episodes: Episode,
    baseline: Baseline = BASE,
    signals: Signals = SIGNALS,
    toMin: Int = NOW_MIN,
): Verdict = Findings.evaluate(Timeline(episodes.toList(), emptyList(), 3750.0, T0, at(toMin)), baseline, signals, at(NOW_MIN))

private fun assertNear(expected: Double, actual: Double?) =
    assertEquals(expected, actual ?: fail("expected $expected, was null"), 1e-6)

class FindingsTest {
    @Test
    fun displayOnNeedsTenMinutesWhenMeasured() {
        val v = verdict(
            episode(EpisodeKind.DisplayOn, 0, 9, 300.0),
            episode(EpisodeKind.DisplayOn, 100, 110, 300.0, level0 = 60, level1 = 58),
        )
        val f = v.findings.single { it.kind == FindingKind.DisplayOn }
        assertEquals(at(100), f.startMs)
        assertEquals(at(110), f.endMs)
        assertEquals(Severity.Problem, f.severity)
        assertEquals(60, f.level0)
        assertEquals(58, f.level1)
        assertNear(50.0, f.mah)
        assertNear(300.0, f.avgMa)
        assertNear(0.9, f.sleptShare)
        assertEquals(false, f.inferred)
    }

    @Test
    fun inferredDisplayOnNeedsHalfAnHour() {
        assertTrue(verdict(episode(EpisodeKind.DisplayOn, 0, 20, 300.0, inferred = true)).findings.isEmpty())
        assertTrue(verdict(episode(EpisodeKind.DisplayOn, 0, 30, 300.0, inferred = true)).findings.single().inferred)
    }

    @Test
    fun standbyIsWatchAboveThreeTimesTheQuietRateAndThirtyMilliamps() {
        val v = verdict(episode(EpisodeKind.Asleep, 0, 120, 40.0), episode(EpisodeKind.Asleep, 200, 320, 25.0))
        val f = v.findings.single()
        assertEquals(FindingKind.StandbyHigh, f.kind)
        assertEquals(Severity.Watch, f.severity)
        assertEquals(at(0), f.startMs)
        assertEquals(VerdictState.Watch, v.state)
    }

    @Test
    fun standbyLimitFollowsTheBaseline() {
        val raised = BASE.copy(quietAsleepMa = 20.0)
        assertTrue(verdict(episode(EpisodeKind.Asleep, 0, 120, 50.0), baseline = raised).findings.isEmpty())
        assertEquals(1, verdict(episode(EpisodeKind.Asleep, 0, 120, 70.0), baseline = raised).findings.size)
    }

    @Test
    fun standbyAtOneHundredMilliampsIsAProblem() {
        assertEquals(Severity.Problem, verdict(episode(EpisodeKind.Asleep, 0, 120, 100.0)).findings.single().severity)
        assertEquals(Severity.Watch, verdict(episode(EpisodeKind.Asleep, 0, 120, 99.0)).findings.single().severity)
    }

    @Test
    fun standbyNeedsAnHourAndFallsBackToThirtyWithoutABaseline() {
        assertTrue(verdict(episode(EpisodeKind.Asleep, 0, 50, 200.0)).findings.isEmpty())
        val noBaseline = BASE.copy(quietAsleepMa = null)
        assertEquals(Severity.Watch, verdict(episode(EpisodeKind.Asleep, 0, 120, 35.0), baseline = noBaseline).findings.single().severity)
        assertTrue(verdict(episode(EpisodeKind.Asleep, 0, 120, 25.0), baseline = noBaseline).findings.isEmpty())
    }

    @Test
    fun notSleepingNeedsHalfAnHourAndATenthOrLessSlept() {
        val v = verdict(
            episode(EpisodeKind.Asleep, 0, 60, 5.0, slept = 0.05),
            episode(EpisodeKind.Asleep, 100, 130, 5.0, slept = 0.0),
            episode(EpisodeKind.Asleep, 200, 220, 5.0, slept = 0.0),
            episode(EpisodeKind.Asleep, 300, 360, 5.0, slept = 0.5),
            episode(EpisodeKind.Asleep, 400, 460, 5.0, slept = 0.10),
        )
        val found = v.findings.filter { it.kind == FindingKind.NotSleeping }
        assertEquals(listOf(at(0), at(100), at(400)), found.map { it.startMs }.sorted())
        assertTrue(found.all { it.severity == Severity.Watch })
    }

    @Test
    fun notSleepingYieldsToADisplayOnFindingAndToStandby() {
        val v = verdict(
            episode(EpisodeKind.Asleep, 0, 60, 5.0, slept = 0.05),
            episode(EpisodeKind.DisplayOn, 30, 45, 300.0),
            episode(EpisodeKind.Asleep, 100, 160, 150.0, slept = 0.05),
            episode(EpisodeKind.Asleep, 200, 260, 5.0, slept = 0.05),
            episode(EpisodeKind.DisplayOn, 210, 215, 300.0),
        )
        assertEquals(listOf(at(200)), v.findings.filter { it.kind == FindingKind.NotSleeping }.map { it.startMs })
        val standby = v.findings.single { it.kind == FindingKind.StandbyHigh }
        assertEquals(at(100), standby.startMs)
        assertEquals(Severity.Problem, standby.severity)
    }

    @Test
    fun wakeLockWatchesHoldsOfAnHourOrMore() {
        val signals = SIGNALS.copy(
            heldLocks = listOf(HeldLock("com.a", "AlarmManager", 1.5), HeldLock("com.b", "short", 0.5), HeldLock("", "TICK", 2.0)),
        )
        val locks = verdict(signals = signals).findings.filter { it.kind == FindingKind.WakeLock }
        assertEquals(listOf("com.a AlarmManager", "TICK"), locks.map { it.subject })
        assertTrue(locks.all { it.severity == Severity.Watch })
        assertEquals(at(NOW_MIN) - 5_400_000L, locks.first().startMs)
        assertEquals(at(NOW_MIN), locks.first().endMs)
    }

    @Test
    fun restartIsInfoInTheWindow() {
        val info = verdict(episode(EpisodeKind.Asleep, 0, 60, 5.0), signals = restartAt(100)).findings.single()
        assertEquals(FindingKind.Restart, info.kind)
        assertEquals(Severity.Info, info.severity)
        assertEquals(at(100), info.startMs)
        assertEquals(at(100), info.endMs)
        assertTrue(verdict(episode(EpisodeKind.Asleep, 0, 60, 5.0), signals = SIGNALS.copy(restartAtMs = T0 - 60_000L)).findings.isEmpty())
    }

    @Test
    fun restartBecomesWatchWhenADisplayOrStandbyFindingFollowsWithinADay() {
        fun restartSeverity(vararg episodes: Episode) =
            verdict(*episodes, signals = restartAt(100)).findings.single { it.kind == FindingKind.Restart }.severity

        assertEquals(Severity.Watch, restartSeverity(episode(EpisodeKind.DisplayOn, 300, 320, 300.0)))
        assertEquals(Severity.Watch, restartSeverity(episode(EpisodeKind.Asleep, 300, 420, 40.0)))
        assertEquals(Severity.Info, restartSeverity(episode(EpisodeKind.DisplayOn, 1_600, 1_620, 300.0)))
        assertEquals(Severity.Info, restartSeverity(episode(EpisodeKind.DisplayOn, 50, 70, 300.0)))
    }

    @Test
    fun hubRestrictedWatchesBlockedBackgroundAndInfosRecordingOff() {
        val blocked = verdict(signals = SIGNALS.copy(hubBackgroundAllowed = false)).findings.single()
        assertEquals(FindingKind.HubRestricted, blocked.kind)
        assertEquals(Severity.Watch, blocked.severity)
        assertEquals(at(NOW_MIN), blocked.startMs)
        assertEquals(at(NOW_MIN), blocked.endMs)
        val off = verdict(signals = SIGNALS.copy(recordingEnabled = false)).findings.single()
        assertEquals(Severity.Info, off.severity)
        assertEquals("recording-off", off.subject)
        assertTrue(verdict(signals = SIGNALS.copy(hubBackgroundAllowed = null)).findings.isEmpty())
    }

    @Test
    fun diedFiresWhenOffFollowsALowLevel() {
        val died = verdict(
            episode(EpisodeKind.Asleep, 0, 60, 5.0, level1 = 3),
            episode(EpisodeKind.Off, 60, 300, null),
        ).findings.single()
        assertEquals(FindingKind.Died, died.kind)
        assertEquals(Severity.Info, died.severity)
        assertEquals(at(60), died.startMs)
        assertEquals(at(300), died.endMs)
        assertEquals(3, died.level0)
        assertTrue(verdict(episode(EpisodeKind.Asleep, 0, 60, 5.0, level1 = 4), episode(EpisodeKind.Off, 60, 300, null)).findings.isEmpty())
        assertTrue(verdict(episode(EpisodeKind.Off, 0, 60, null)).findings.isEmpty())
    }

    @Test
    fun appHeavyFlagsAppsWellAboveTheUsualInUseDraw() {
        val apps = listOf(
            AppUse("com.chrome", 1.0, 867.0),
            AppUse("com.short", 0.4, 400.0),
            AppUse("", 2.0, 2_000.0),
            AppUse("com.calm", 2.0, 400.0),
        )
        val found = Findings.appHeavy(apps, BASE, at(NOW_MIN))
        assertEquals(listOf("com.chrome"), found.map { it.subject })
        val chrome = found.single()
        assertEquals(FindingKind.AppHeavy, chrome.kind)
        assertEquals(Severity.Info, chrome.severity)
        assertEquals(at(NOW_MIN) - 3_600_000L, chrome.startMs)
        assertEquals(at(NOW_MIN), chrome.endMs)
        assertNear(867.0, chrome.mah)
        assertNear(867.0, chrome.avgMa)
        assertTrue(Findings.appHeavy(apps, BASE.copy(inUseMa = null), at(NOW_MIN)).isEmpty())
    }

    @Test
    fun findingsSortBySeverityThenNewestFirst() {
        val v = verdict(
            episode(EpisodeKind.Asleep, 0, 60, 5.0, level1 = 2),
            episode(EpisodeKind.Off, 60, 120, null),
            episode(EpisodeKind.InUse, 120, 180, 300.0),
            episode(EpisodeKind.Asleep, 180, 240, 5.0, slept = 0.05),
            episode(EpisodeKind.DisplayOn, 300, 320, 300.0),
            episode(EpisodeKind.DisplayOn, 400, 420, 300.0),
        )
        assertEquals(
            listOf(Severity.Problem to at(400), Severity.Problem to at(300), Severity.Watch to at(180), Severity.Info to at(60)),
            v.findings.map { it.severity to it.startMs },
        )
    }

    @Test
    fun stateFollowsTheWorstFinding() {
        val quiet = episode(EpisodeKind.Asleep, 0, 120, 5.0)
        assertEquals(VerdictState.Normal, verdict(quiet).state)
        assertEquals(VerdictState.Watch, verdict(quiet, episode(EpisodeKind.Asleep, 200, 320, 40.0)).state)
        assertEquals(VerdictState.Problem, verdict(quiet, episode(EpisodeKind.DisplayOn, 200, 220, 300.0)).state)
        val lowLevel = episode(EpisodeKind.Asleep, 0, 120, 5.0, level1 = 3)
        assertEquals(VerdictState.Normal, verdict(lowLevel, episode(EpisodeKind.Off, 120, 200, null)).state)
    }

    @Test
    fun noDataUnderAnHourOfRecording() {
        assertEquals(VerdictState.NoData, verdict(episode(EpisodeKind.Asleep, 0, 40, 5.0)).state)
        assertEquals(VerdictState.NoData, verdict(episode(EpisodeKind.Asleep, 0, 30, 5.0), episode(EpisodeKind.Off, 30, 90, null)).state)
        assertEquals(VerdictState.Normal, verdict(episode(EpisodeKind.Asleep, 0, 60, 5.0)).state)
    }

    @Test
    fun aFindingUnderAnHourOfRecordingStillSetsTheState() {
        assertEquals(VerdictState.Problem, verdict(episode(EpisodeKind.DisplayOn, 0, 10, 300.0)).state)
        assertEquals(
            VerdictState.Watch,
            verdict(episode(EpisodeKind.Asleep, 0, 30, 5.0), signals = SIGNALS.copy(hubBackgroundAllowed = false)).state,
        )
    }

    @Test
    fun verdictReportsRecordedAndWindowHours() {
        val v = verdict(
            episode(EpisodeKind.Asleep, 0, 60, 5.0),
            episode(EpisodeKind.InUse, 60, 90, 300.0),
            episode(EpisodeKind.Charging, 90, 120, null),
            episode(EpisodeKind.Off, 120, 180, null),
            toMin = 240,
        )
        assertNear(2.0, v.recordedHours)
        assertNear(4.0, v.windowHours)
    }
}
