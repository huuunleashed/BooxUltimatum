package app.booxultimatum.core.battery

import kotlin.math.max
import kotlin.math.min

/** OWNER: A1. Rows to segments to episodes. */
object Timelines {
    private const val HOUR_MS = 3_600_000.0
    private const val DAY_MS = 86_400_000L
    private const val DEFAULT_CAPACITY_MAH = 3700.0
    private const val STUCK_MA = 120.0
    private const val QUIET_SLEPT_MAX = 0.10
    private const val RUN_START_HOURS = 0.1

    /**
     * Classifies each consecutive pair of rows, merges runs into episodes and learns the capacity. [foreground] (UsageStats
     * spans) names the in-use app where the rows' own `top` column is empty. Pure: no Android, no clock but [nowMs].
     */
    fun build(rows: List<BatteryRow>, nowMs: Long, foreground: List<ForegroundSpan> = emptyList()): Timeline {
        val sorted = rows.sortedBy { it.epoch }
        val capacity = capacityOf(sorted)
        val segments = segmentsOf(sorted, capacity)
        return Timeline(
            episodes = episodesOf(segments, foreground),
            segments = segments,
            capacityMah = capacity,
            fromMs = sorted.firstOrNull()?.epoch ?: nowMs,
            toMs = max(nowMs, sorted.lastOrNull()?.epoch ?: nowMs),
        )
    }

    private fun capacityOf(rows: List<BatteryRow>): Double =
        rows.asReversed().firstNotNullOfOrNull { row ->
            row.chargeMah?.takeIf { it > 0 && row.level >= 50 }?.let { it * 100 / row.level }
        } ?: DEFAULT_CAPACITY_MAH

    private fun segmentsOf(rows: List<BatteryRow>, capacity: Double): List<Segment> {
        val segments = ArrayList<Segment>(max(0, rows.size - 1))
        for (i in 1 until rows.size) {
            segments += segmentOf(rows[i - 1], rows[i], capacity, segments.lastOrNull()?.kind)
        }
        return segments
    }

    private fun segmentOf(a: BatteryRow, b: BatteryRow, capacity: Double, previous: EpisodeKind?): Segment {
        if (restarted(a, b)) return Segment(a, b, EpisodeKind.Off, mah = null, sleptShare = 0.0)
        val elapsed = b.elapsedMs - a.elapsedMs
        val hours = elapsed / HOUR_MS
        val sleptShare = if (elapsed > 0) (1 - (b.uptimeMs - a.uptimeMs) / elapsed.toDouble()).coerceIn(0.0, 1.0) else 0.0
        val counters = counters(a, b)
        if (a.plugged || b.plugged) {
            val added = counters?.let { (before, after) -> after - before } ?: ((b.level - a.level) * capacity / 100)
            return Segment(a, b, EpisodeKind.Charging, max(0.0, added), sleptShare)
        }
        val drawn = max(0.0, counters?.let { (before, after) -> before - after } ?: ((a.level - b.level) * capacity / 100))
        if (interactive(a)) return Segment(a, b, EpisodeKind.InUse, drawn, sleptShare)
        if (a.display == DisplayState.On || a.reason == "display_on" || a.reason == "display_stuck") {
            return Segment(a, b, EpisodeKind.DisplayOn, drawn, sleptShare)
        }
        val rate = if (hours > 0) drawn / hours else 0.0
        // A segment under six minutes is too short to start a run on its own, but it continues the display-on run before it.
        val stuck = rate >= STUCK_MA && sleptShare <= QUIET_SLEPT_MAX && (hours >= RUN_START_HOURS || previous == EpisodeKind.DisplayOn)
        return if (stuck) {
            Segment(a, b, EpisodeKind.DisplayOn, drawn, sleptShare, inferred = true)
        } else {
            Segment(a, b, EpisodeKind.Asleep, drawn, sleptShare)
        }
    }

    private fun restarted(a: BatteryRow, b: BatteryRow): Boolean =
        b.elapsedMs < a.elapsedMs ||
            (a.bootCount != null && b.bootCount != null && a.bootCount != b.bootCount) ||
            b.epoch - a.epoch > DAY_MS

    private fun interactive(a: BatteryRow): Boolean = when (a.reason) {
        "screen_on" -> true
        "screen_off" -> false
        else -> a.interactive
    }

    /** Both charge counters, when both rows have one and both read above zero. */
    private fun counters(a: BatteryRow, b: BatteryRow): Pair<Double, Double>? {
        val before = a.chargeMah ?: return null
        val after = b.chargeMah ?: return null
        return if (before > 0 && after > 0) before to after else null
    }

    private fun episodesOf(segments: List<Segment>, foreground: List<ForegroundSpan>): List<Episode> {
        val runs = mutableListOf<MutableList<Segment>>()
        for (s in segments) {
            val run = runs.lastOrNull()
            if (run != null && run.last().kind == s.kind) run += s else runs += mutableListOf(s)
        }
        return runs.map { episodeOf(it, foreground) }
    }

    private fun episodeOf(run: List<Segment>, foreground: List<ForegroundSpan>): Episode {
        val first = run.first()
        val last = run.last()
        val mahs = run.mapNotNull { it.mah }
        val hours = run.sumOf { it.hours }
        return Episode(
            kind = first.kind,
            startMs = first.startMs,
            endMs = last.endMs,
            level0 = first.from.level,
            level1 = last.to.level,
            mah = if (mahs.isEmpty()) null else mahs.sum(),
            sleptShare = if (hours > 0) run.sumOf { it.sleptShare * it.hours } / hours else run.map { it.sleptShare }.average(),
            topApp = if (first.kind == EpisodeKind.InUse) topAppOf(run, foreground) else null,
            inferred = first.kind == EpisodeKind.DisplayOn && run.all { it.inferred },
        )
    }

    /** The app in front for the most hours by the rows, else the foreground span with the most overlap. */
    private fun topAppOf(run: List<Segment>, foreground: List<ForegroundSpan>): String? {
        val hoursByApp = LinkedHashMap<String, Double>()
        for (s in run) {
            val app = s.from.top.ifEmpty { s.to.top }
            if (app.isNotEmpty()) hoursByApp[app] = (hoursByApp[app] ?: 0.0) + s.hours
        }
        hoursByApp.maxByOrNull { it.value }?.let { return it.key }
        val start = run.first().startMs
        val end = run.last().endMs
        val overlapByApp = LinkedHashMap<String, Long>()
        for (span in foreground) {
            val overlap = min(span.endMs, end) - max(span.startMs, start)
            if (overlap > 0) overlapByApp[span.pkg] = (overlapByApp[span.pkg] ?: 0L) + overlap
        }
        return overlapByApp.maxByOrNull { it.value }?.key
    }
}
