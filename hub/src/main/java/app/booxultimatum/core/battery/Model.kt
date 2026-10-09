package app.booxultimatum.core.battery

/**
 * The battery section's shared model: one log, read as rows, turned into segments and episodes, judged by findings.
 * Only data lives here. The functions that build and judge them are in the neighbouring files, one owner each, so the
 * types below are the contract between them and the screens: change one only on purpose.
 */

/** The panel's display state when a row was written. The log's `display` column holds the lower-case name. */
enum class DisplayState {
    On, Doze, Off, Other;

    val column: String get() = name.lowercase()

    companion object {
        fun parse(text: String?): DisplayState? = when (text?.trim()?.lowercase()) {
            "on" -> On
            "doze" -> Doze
            "off" -> Off
            "other" -> Other
            else -> null
        }
    }
}

/**
 * One row of `battery-YYYY-MM.csv`, whatever layout it was written in. Columns a layout lacks are null, 0 or empty.
 * [reason] is one of: tick, screen_on, screen_off, plug, unplug, level, doze_deep, doze_light, saver, open, manual, boot,
 * unstop, update, display_on, display_stuck, mark. [note] is reason-specific: for display_on and display_stuck the epoch
 * (ms) at which the display went on, for mark the label.
 */
data class BatteryRow(
    val epoch: Long,
    val reason: String,
    val level: Int,
    val chargeMah: Double?,
    val voltageMv: Int?,
    val tempC: Double?,
    val plugged: Boolean,
    /** The `screen_on` column: the tablet is interactive (awake for the user), not merely showing something. */
    val interactive: Boolean,
    val elapsedMs: Long,
    val uptimeMs: Long,
    val bootCount: Int?,
    val currentMa: Double?,
    val frontlight: Int?,
    val warmth: Int?,
    /** off, on, connected or empty. */
    val wifi: String,
    /** none, light, deep or empty. */
    val idle: String,
    val saver: Boolean,
    val display: DisplayState?,
    /** The package in front when the row was written; empty when the tablet wasn't interactive or it isn't known. */
    val top: String,
    val note: String,
)

enum class EpisodeKind {
    /** Interactive: the owner is using the tablet. */
    InUse,

    /** Not interactive and drawing little: ordinary sleep. */
    Asleep,

    /** Not interactive but the display is ON (or the drain says so): the fault of 2026-10-08. */
    DisplayOn,

    /** Plugged in. */
    Charging,

    /** The tablet was off or the log has nothing for the time (a restart, a shutdown). */
    Off,
}

/** The piece of log between two consecutive rows, classified. An [Episode] is a run of segments of one kind. */
data class Segment(
    val from: BatteryRow,
    val to: BatteryRow,
    val kind: EpisodeKind,
    /** Charge drawn (>= 0), or for [EpisodeKind.Charging] charge added; null when neither end has the counter and no estimate exists. */
    val mah: Double?,
    /** Share of the time the SoC was suspended: 1 - dUptime / dElapsed, clamped to 0..1. */
    val sleptShare: Double,
    /** True when [kind] is DisplayOn but was deduced from drain and a busy SoC, not read from the display column. */
    val inferred: Boolean = false,
) {
    val startMs: Long get() = from.epoch
    val endMs: Long get() = to.epoch
    val hours: Double get() = (endMs - startMs) / 3_600_000.0
    val avgMa: Double? get() = mah?.let { if (hours > 0) it / hours else null }
}

data class Episode(
    val kind: EpisodeKind,
    val startMs: Long,
    val endMs: Long,
    val level0: Int,
    val level1: Int,
    val mah: Double?,
    val sleptShare: Double,
    /** InUse: the package in front for the longest part of the episode, when known. */
    val topApp: String?,
    val inferred: Boolean = false,
) {
    val hours: Double get() = (endMs - startMs) / 3_600_000.0
    val avgMa: Double? get() = mah?.let { if (hours > 0) it / hours else null }
}

/** A span of the foreground app from UsageStats, used to apportion in-use charge to apps. */
data class ForegroundSpan(val pkg: String, val startMs: Long, val endMs: Long)

/**
 * Everything the screens draw, for the rows read: [segments] (the finest unit, used for per-app attribution),
 * [episodes] (merged runs, newest last), the battery's capacity as the log learned it and the span covered.
 */
data class Timeline(
    val episodes: List<Episode>,
    val segments: List<Segment>,
    val capacityMah: Double,
    val fromMs: Long,
    val toMs: Long,
)

/** What is normal on this tablet, worked out from its own log. Null fields mean there is not enough data. */
data class Baseline(
    /** Median current while asleep and not displaying (mA). */
    val quietAsleepMa: Double?,
    /** Interquartile spread of the daily quiet-asleep rate (mA), so a change smaller than this is not a result. */
    val dayToDaySpreadMa: Double?,
    /** Average current while in use (mA). */
    val inUseMa: Double?,
    /** Share of unplugged time spent asleep, 0..1, over the days used. */
    val asleepShare: Double?,
    /** Days of unplugged log the figures rest on. */
    val basisDays: Double,
)

enum class Severity { Info, Watch, Problem }

enum class FindingKind { DisplayOn, StandbyHigh, NotSleeping, WakeLock, Restart, HubRestricted, AppHeavy, Died }

/**
 * One thing the log shows. It carries numbers, never prose: the screens turn it into words from string resources.
 * [subject] is the package or wake lock tag a finding names, when it names one.
 */
data class Finding(
    val kind: FindingKind,
    val severity: Severity,
    val startMs: Long,
    val endMs: Long,
    val level0: Int? = null,
    val level1: Int? = null,
    val mah: Double? = null,
    val avgMa: Double? = null,
    val sleptShare: Double? = null,
    val subject: String? = null,
    val inferred: Boolean = false,
)

enum class VerdictState { Normal, Watch, Problem, NoData }

/** The worst finding's severity as a state, with the findings that make it (most severe, then newest, first). */
data class Verdict(
    val state: VerdictState,
    val findings: List<Finding>,
    /** Hours of the window the log covers, and the window's length, for the "recorded x of y" line. */
    val recordedHours: Double,
    val windowHours: Double,
)

/** Readings from outside the log that findings need. Unknown is null; the reader of Android state fills them in. */
data class Signals(
    /** The hub's own RUN_ANY_IN_BACKGROUND is allowed. */
    val hubBackgroundAllowed: Boolean?,
    val recordingEnabled: Boolean,
    val shizukuRunning: Boolean,
    /** When the framework last restarted since the last boot (dropbox system_server_crash or SYSTEM_RESTART), else null. */
    val restartAtMs: Long?,
    /** Wake locks held an hour or more while asleep. */
    val heldLocks: List<HeldLock> = emptyList(),
)

data class HeldLock(val pkg: String, val tag: String, val hours: Double)

/** How long the tablet should last at its usual use, with the range and what it rests on. */
data class Estimate(
    val hours: Double,
    val lowHours: Double,
    val highHours: Double,
    val basisDays: Double,
    val quietMa: Double,
    val inUseMa: Double,
    val asleepShare: Double,
)

data class StateTotal(val kind: EpisodeKind, val hours: Double, val mah: Double)

/** One day (local midnight to midnight) of unplugged and plugged time. */
data class DaySummary(
    val dayStartMs: Long,
    val usedMah: Double,
    val inUseHours: Double,
    val asleepHours: Double,
    val displayOnHours: Double,
    val chargingHours: Double,
    val displayOnMah: Double,
)

/** From one unplug to the next plug (or now). */
data class DischargeSession(
    val startMs: Long,
    val endMs: Long,
    val level0: Int,
    val level1: Int,
    val mah: Double,
    val inUseHours: Double,
    val asleepHours: Double,
    val displayOnHours: Double,
)

/** In-use charge apportioned to one app by how long it was in front within each segment. */
data class AppUse(val pkg: String, val hours: Double, val mah: Double) {
    val avgMa: Double get() = if (hours > 0) mah / hours else 0.0
}

enum class MarkSource { Manual, Tweak, Measure }

/** A labelled moment. Tweaks applied or undone become marks without asking; [epoch] is wall-clock ms. */
data class Mark(val epoch: Long, val label: String, val source: MarkSource)

enum class BeforeAfterVerdict { NotEnoughData, WithinNormal, Lower, Higher }

/** Quiet-asleep rates either side of a mark, with the tablet's own day-to-day spread so a small change isn't called a result. */
data class BeforeAfter(
    val mark: Mark,
    val beforeMa: Double?,
    val afterMa: Double?,
    val beforeHours: Double,
    val afterHours: Double,
    val spreadMa: Double?,
    val verdict: BeforeAfterVerdict,
)

/** Wake sources between two times, from the three-hourly deep snapshots. */
data class WakeSource(val pkg: String, val wakeups: Int, val lockCount: Int, val cpuMs: Long)
