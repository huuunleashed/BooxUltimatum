package app.booxultimatum.ui.battery

import android.content.Context
import android.text.format.DateFormat
import app.booxultimatum.R
import app.booxultimatum.core.battery.Episode
import app.booxultimatum.core.battery.EpisodeKind
import app.booxultimatum.core.battery.Finding
import app.booxultimatum.core.battery.FindingKind
import app.booxultimatum.core.battery.Severity
import app.booxultimatum.core.battery.Timeline
import app.booxultimatum.core.battery.VerdictState
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24 * HOUR_MS
private const val RECORDING_OFF = "recording-off"

/** OWNER: D1. Words and numbers of the Battery section, all from string resources. The signatures are final. */
object BatteryText {
    fun duration(context: Context, ms: Long): String {
        if (ms < MINUTE_MS) return context.getString(R.string.bt_duration_under_minute)
        val minutes = ms / MINUTE_MS
        val days = minutes / (24 * 60)
        val hours = (minutes / 60) % 24
        val mins = minutes % 60
        return when {
            days > 0 -> context.getString(R.string.bt_duration_dh, days, hours)
            hours > 0 -> context.getString(R.string.bt_duration_hm, hours, mins)
            else -> context.getString(R.string.bt_duration_m, mins)
        }
    }

    fun mah(context: Context, v: Double): String = context.getString(R.string.bt_mah, integers(context).format(v))

    fun ma(context: Context, v: Double): String {
        val number = if (abs(v) < 10) decimals(context, 1).format(v) else integers(context).format(v)
        return context.getString(R.string.bt_ma, number)
    }

    fun pctPerHour(context: Context, ma: Double, capacityMah: Double): String {
        if (capacityMah <= 0) return context.getString(R.string.bt_unknown)
        val pct = ma / capacityMah * 100
        return context.getString(R.string.bt_pct_per_hour, decimals(context, if (abs(pct) < 1) 2 else 1).format(pct))
    }

    fun kindLabel(context: Context, kind: EpisodeKind): String {
        val id = when (kind) {
            EpisodeKind.InUse -> R.string.bt_kind_in_use
            EpisodeKind.Asleep -> R.string.bt_kind_asleep
            EpisodeKind.DisplayOn -> R.string.bt_kind_display_on
            EpisodeKind.Charging -> R.string.bt_kind_charging
            EpisodeKind.Off -> R.string.bt_kind_off
        }
        return context.getString(id)
    }

    fun timeRange(context: Context, startMs: Long, endMs: Long): String {
        val end = if (endMs - startMs > DAY_MS) dayAndClock(context, endMs) else clock(context, endMs)
        return context.getString(R.string.bt_time_range, clock(context, startMs), end)
    }

    fun day(context: Context, ms: Long): String {
        val locale = locale(context)
        return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEE d MMM"), locale).format(Date(ms))
    }

    fun findingTitle(context: Context, f: Finding): String {
        val id = when (f.kind) {
            FindingKind.DisplayOn -> R.string.bt_finding_display_on
            FindingKind.StandbyHigh -> R.string.bt_finding_standby_high
            FindingKind.NotSleeping -> R.string.bt_finding_not_sleeping
            FindingKind.WakeLock -> R.string.bt_finding_wake_lock
            FindingKind.Restart -> R.string.bt_finding_restart
            FindingKind.HubRestricted -> if (f.subject == RECORDING_OFF) R.string.bt_finding_recording_paused else R.string.bt_finding_hub_restricted
            FindingKind.AppHeavy -> R.string.bt_finding_app_heavy
            FindingKind.Died -> R.string.bt_finding_died
        }
        return context.getString(id)
    }

    fun findingEvidence(context: Context, f: Finding): String {
        val facts = mutableListOf<String>()
        when (f.kind) {
            FindingKind.WakeLock -> {
                f.subject?.let { facts += it }
                facts += context.getString(R.string.bt_held_for, duration(context, f.endMs - f.startMs))
            }
            FindingKind.Restart -> facts += dayAndClock(context, f.startMs)
            FindingKind.AppHeavy -> {
                f.subject?.let { facts += appLabel(context, it) }
                f.avgMa?.let { facts += ma(context, it) }
            }
            else -> {
                facts += timeRange(context, f.startMs, f.endMs)
                facts += duration(context, f.endMs - f.startMs)
                val from = f.level0
                val to = f.level1
                if (from != null && to != null) facts += levelRange(context, from, to)
                f.mah?.let { facts += mah(context, it) }
                f.avgMa?.let { facts += ma(context, it) }
                f.sleptShare?.let { facts += slept(context, it) }
            }
        }
        if (f.inferred) facts += context.getString(R.string.bt_inferred)
        return facts.joinToString(context.getString(R.string.bt_evidence_sep))
    }

    fun severityLabel(context: Context, s: Severity): String {
        val id = when (s) {
            Severity.Info -> R.string.bt_severity_info
            Severity.Watch -> R.string.bt_severity_watch
            Severity.Problem -> R.string.bt_severity_problem
        }
        return context.getString(id)
    }

    fun verdictTitle(context: Context, v: VerdictState): String {
        val id = when (v) {
            VerdictState.Normal -> R.string.bt_verdict_normal
            VerdictState.Watch -> R.string.bt_verdict_watch
            VerdictState.Problem -> R.string.bt_verdict_problem
            VerdictState.NoData -> R.string.bt_verdict_no_data
        }
        return context.getString(id)
    }

    fun summarySentence(context: Context, timeline: Timeline, fromMs: Long, toMs: Long): String {
        val shown = timeline.episodes.filter { overlaps(it.startMs, it.endMs, fromMs, toMs) }
        if (shown.isEmpty()) return context.getString(R.string.bt_sum_nothing)
        val start = levelAt(shown.first(), fromMs)
        val end = levelAt(shown.last(), toMs)
        val window = if (isAboutADay(fromMs, toMs)) {
            context.getString(R.string.bt_sum_last_24h)
        } else {
            context.getString(R.string.bt_sum_from_to, day(context, fromMs), day(context, toMs))
        }
        val sentences = mutableListOf(
            when {
                end < start -> context.getString(R.string.bt_sum_fell, window, levelRange(context, start, end))
                end > start -> context.getString(R.string.bt_sum_rose, window, levelRange(context, start, end))
                else -> context.getString(R.string.bt_sum_stayed, window, percent(context, start))
            },
        )
        shown.filter { it.kind == EpisodeKind.DisplayOn }.forEach {
            val id = if (it.inferred) R.string.bt_sum_display_on_inferred else R.string.bt_sum_display_on
            sentences += context.getString(id, timeRange(context, it.startMs, it.endMs))
        }
        val inUseMs = shown.filter { it.kind == EpisodeKind.InUse }.sumOf { minOf(it.endMs, toMs) - maxOf(it.startMs, fromMs) }
        if (inUseMs > 0) sentences += context.getString(R.string.bt_sum_in_use, duration(context, inUseMs))
        return sentences.joinToString(" ")
    }

    private fun levelRange(context: Context, from: Int, to: Int): String =
        context.getString(R.string.bt_level_range, percent(context, from), percent(context, to))

    private fun percent(context: Context, level: Int): String = context.getString(R.string.bt_percent, level)

    private fun slept(context: Context, share: Double): String {
        val pct = (share * 100).roundToInt()
        return if (pct == 0) context.getString(R.string.bt_slept_never) else context.getString(R.string.bt_slept_share, percent(context, pct))
    }

    private fun appLabel(context: Context, pkg: String): String {
        val pm = context.packageManager
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    private fun dayAndClock(context: Context, ms: Long): String = context.getString(R.string.bt_day_time, day(context, ms), clock(context, ms))

    private fun clock(context: Context, ms: Long): String = DateFormat.getTimeFormat(context).format(Date(ms))

    private fun locale(context: Context): Locale = context.resources.configuration.locales[0]

    private fun integers(context: Context): NumberFormat = NumberFormat.getIntegerInstance(locale(context))

    private fun decimals(context: Context, digits: Int): NumberFormat = NumberFormat.getNumberInstance(locale(context)).apply {
        minimumFractionDigits = digits
        maximumFractionDigits = digits
    }
}

/** The level [episode] shows at [atMs]: a straight line between its two ends, held at them outside its span. */
internal fun levelAt(episode: Episode, atMs: Long): Int {
    val span = episode.endMs - episode.startMs
    if (span <= 0 || atMs <= episode.startMs) return episode.level0
    if (atMs >= episode.endMs) return episode.level1
    return (episode.level0 + (episode.level1 - episode.level0) * (atMs - episode.startMs).toDouble() / span).roundToInt()
}

/** Whether a span of time touches the window from [fromMs] to [toMs]. */
internal fun overlaps(startMs: Long, endMs: Long, fromMs: Long, toMs: Long): Boolean = startMs < toMs && endMs > fromMs

/** A window of about a day, 23 to 25 hours, so a day with a clock change still counts. */
internal fun isAboutADay(fromMs: Long, toMs: Long): Boolean = toMs - fromMs in 23 * HOUR_MS..25 * HOUR_MS
