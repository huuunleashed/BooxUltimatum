package app.booxultimatum.core.battery

import kotlin.math.max

/** OWNER: A2. The rules of docs/10-battery.md section 4.1. */
object Findings {
    private const val HOUR_MS = 3_600_000.0
    private const val DAY_MS = 86_400_000L
    private const val DISPLAY_MIN_HOURS = 10.0 / 60
    private const val DISPLAY_MIN_HOURS_INFERRED = 0.5
    private const val STANDBY_MIN_HOURS = 1.0
    private const val STANDBY_RATIO = 3.0
    private const val STANDBY_FLOOR_MA = 30.0
    private const val STANDBY_PROBLEM_MA = 100.0
    private const val NOT_SLEEPING_MIN_HOURS = 0.5
    private const val NOT_SLEEPING_MAX_SLEPT = 0.10
    private const val WAKE_LOCK_MIN_HOURS = 1.0
    private const val RESTART_WINDOW_MS = DAY_MS
    private const val DIED_MAX_LEVEL = 3
    private const val APP_MIN_HOURS = 0.5
    private const val APP_RATIO = 2.5
    private const val RECORDED_MIN_HOURS = 1.0

    /** Findings and the verdict over [t]'s whole span (the caller passes a timeline built for its window). */
    fun evaluate(t: Timeline, baseline: Baseline, signals: Signals, nowMs: Long): Verdict {
        val displayOn = t.episodes.filter { e ->
            e.kind == EpisodeKind.DisplayOn && e.hours >= (if (e.inferred) DISPLAY_MIN_HOURS_INFERRED else DISPLAY_MIN_HOURS)
        }.map { it.finding(FindingKind.DisplayOn, Severity.Problem) }

        val standbyLimit = baseline.quietAsleepMa?.let { max(STANDBY_RATIO * it, STANDBY_FLOOR_MA) } ?: STANDBY_FLOOR_MA
        val asleep = t.episodes.filter { it.kind == EpisodeKind.Asleep }
        val standbyEpisodes = asleep.filter { it.hours >= STANDBY_MIN_HOURS && (it.avgMa ?: 0.0) > standbyLimit }
        val standby = standbyEpisodes.map { e ->
            e.finding(FindingKind.StandbyHigh, if ((e.avgMa ?: 0.0) >= STANDBY_PROBLEM_MA) Severity.Problem else Severity.Watch)
        }
        val notSleeping = asleep.filter { e ->
            e.hours >= NOT_SLEEPING_MIN_HOURS && e.sleptShare <= NOT_SLEEPING_MAX_SLEPT && e !in standbyEpisodes &&
                displayOn.none { it.startMs < e.endMs && e.startMs < it.endMs }
        }.map { it.finding(FindingKind.NotSleeping, Severity.Watch) }

        val wakeLocks = signals.heldLocks.filter { it.hours >= WAKE_LOCK_MIN_HOURS }.map { lock ->
            Finding(
                FindingKind.WakeLock,
                Severity.Watch,
                nowMs - (lock.hours * HOUR_MS).toLong(),
                nowMs,
                subject = "${lock.pkg} ${lock.tag}".trim(),
            )
        }
        val restart = signals.restartAtMs?.takeIf { it in t.fromMs..t.toMs }?.let { restartMs ->
            val stuckSoon = (displayOn + standby).any { it.startMs in restartMs..(restartMs + RESTART_WINDOW_MS) }
            Finding(FindingKind.Restart, if (stuckSoon) Severity.Watch else Severity.Info, restartMs, restartMs)
        }
        val hub = buildList {
            if (signals.hubBackgroundAllowed == false) add(Finding(FindingKind.HubRestricted, Severity.Watch, nowMs, nowMs))
            if (!signals.recordingEnabled) {
                add(Finding(FindingKind.HubRestricted, Severity.Info, nowMs, nowMs, subject = "recording-off"))
            }
        }
        val died = t.episodes.zipWithNext().filter { (before, after) ->
            after.kind == EpisodeKind.Off && before.level1 <= DIED_MAX_LEVEL
        }.map { (before, off) -> Finding(FindingKind.Died, Severity.Info, before.endMs, off.endMs, level0 = before.level1) }

        val findings = (displayOn + standby + notSleeping + wakeLocks + listOfNotNull(restart) + hub + died)
            .sortedWith(compareByDescending<Finding> { it.severity }.thenByDescending { it.startMs })
        val recordedHours = t.episodes.filter { it.kind != EpisodeKind.Off }.sumOf { it.hours }
        val state = when {
            findings.any { it.severity == Severity.Problem } -> VerdictState.Problem
            findings.any { it.severity == Severity.Watch } -> VerdictState.Watch
            recordedHours < RECORDED_MIN_HOURS -> VerdictState.NoData
            else -> VerdictState.Normal
        }
        return Verdict(state, findings, recordedHours, (t.toMs - t.fromMs) / HOUR_MS)
    }

    /** Info findings for apps in front well above the tablet's usual in-use draw. Kept out of [evaluate]; the caller merges them. */
    fun appHeavy(apps: List<AppUse>, baseline: Baseline, nowMs: Long): List<Finding> {
        val usual = baseline.inUseMa ?: return emptyList()
        return apps.filter { it.pkg.isNotEmpty() && it.hours >= APP_MIN_HOURS && it.avgMa > APP_RATIO * usual }.map {
            Finding(
                FindingKind.AppHeavy,
                Severity.Info,
                nowMs - (it.hours * HOUR_MS).toLong(),
                nowMs,
                mah = it.mah,
                avgMa = it.avgMa,
                subject = it.pkg,
            )
        }
    }

    private fun Episode.finding(kind: FindingKind, severity: Severity) =
        Finding(kind, severity, startMs, endMs, level0, level1, mah, avgMa, sleptShare, inferred = inferred)
}
