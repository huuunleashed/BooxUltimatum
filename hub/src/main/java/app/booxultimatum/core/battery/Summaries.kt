package app.booxultimatum.core.battery

import java.time.Instant
import java.util.EnumMap
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

/** OWNER: A1. Totals over a window of a [Timeline]. Episodes and segments that cross a window edge count in proportion. */
object Summaries {
    private const val HOUR_MS = 3_600_000.0
    private const val MIN_APP_HOURS = 1.0 / 60
    private val unplugged = setOf(EpisodeKind.InUse, EpisodeKind.Asleep, EpisodeKind.DisplayOn)

    fun byState(t: Timeline, fromMs: Long, toMs: Long): List<StateTotal> {
        val sums = Sums()
        for (e in t.episodes) sums.add(e, share(e.startMs, e.endMs, fromMs, toMs))
        return EpisodeKind.entries.filter { sums.hours(it) > 0 }.map { StateTotal(it, sums.hours(it), sums.mah(it)) }
    }

    fun byDay(t: Timeline, zone: TimeZone, fromMs: Long, toMs: Long): List<DaySummary> {
        val zoneId = zone.toZoneId()
        val last = Instant.ofEpochMilli(toMs).atZone(zoneId).toLocalDate()
        val days = mutableListOf<DaySummary>()
        var day = Instant.ofEpochMilli(fromMs).atZone(zoneId).toLocalDate()
        while (!day.isAfter(last)) {
            val start = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val from = max(start, fromMs)
            val to = min(end, toMs)
            if (to > from) {
                val sums = Sums()
                for (e in t.episodes) sums.add(e, share(e.startMs, e.endMs, from, to))
                val summary = DaySummary(
                    dayStartMs = start,
                    usedMah = unplugged.sumOf { sums.mah(it) },
                    inUseHours = sums.hours(EpisodeKind.InUse),
                    asleepHours = sums.hours(EpisodeKind.Asleep),
                    displayOnHours = sums.hours(EpisodeKind.DisplayOn),
                    chargingHours = sums.hours(EpisodeKind.Charging),
                    displayOnMah = sums.mah(EpisodeKind.DisplayOn),
                )
                if (summary.inUseHours + summary.asleepHours + summary.displayOnHours + summary.chargingHours > 0) days += summary
            }
            day = day.plusDays(1)
        }
        return days
    }

    fun sessions(t: Timeline): List<DischargeSession> {
        val runs = mutableListOf<MutableList<Episode>>()
        var open = mutableListOf<Episode>()
        for (e in t.episodes) {
            if (e.kind in unplugged) {
                open += e
            } else if (open.isNotEmpty()) {
                runs += open
                open = mutableListOf()
            }
        }
        if (open.isNotEmpty()) runs += open
        return runs.map { run ->
            DischargeSession(
                startMs = run.first().startMs,
                endMs = run.last().endMs,
                level0 = run.first().level0,
                level1 = run.last().level1,
                mah = run.sumOf { it.mah ?: 0.0 },
                inUseHours = run.hoursOf(EpisodeKind.InUse),
                asleepHours = run.hoursOf(EpisodeKind.Asleep),
                displayOnHours = run.hoursOf(EpisodeKind.DisplayOn),
            )
        }
    }

    /** In-use segments' charge split among the apps in front, largest first. Segments with no known app go to an app with pkg "". */
    fun byApp(t: Timeline, fromMs: Long, toMs: Long, foreground: List<ForegroundSpan> = emptyList()): List<AppUse> {
        val sums = LinkedHashMap<String, AppSum>()
        for (s in t.segments) {
            if (s.kind != EpisodeKind.InUse) continue
            val from = max(s.startMs, fromMs)
            val to = min(s.endMs, toMs)
            if (to <= from) continue
            val segmentMs = (s.endMs - s.startMs).toDouble()
            for ((app, ms) in apportion(s, from, to, foreground)) {
                val sum = sums.getOrPut(app) { AppSum() }
                sum.hours += ms / HOUR_MS
                sum.mah += (s.mah ?: 0.0) * ms / segmentMs
            }
        }
        return sums.map { (app, sum) -> AppUse(app, sum.hours, sum.mah) }
            .filter { it.mah > 0 || it.hours >= MIN_APP_HOURS }
            .sortedByDescending { it.mah }
    }

    /** The milliseconds of [from, to] that each app had in front: the foreground spans, then the row's top for the rest. */
    private fun apportion(s: Segment, from: Long, to: Long, foreground: List<ForegroundSpan>): Map<String, Double> {
        val windowMs = (to - from).toDouble()
        val spans = LinkedHashMap<String, Double>()
        for (span in foreground) {
            val overlap = min(span.endMs, to) - max(span.startMs, from)
            if (overlap > 0) spans[span.pkg] = (spans[span.pkg] ?: 0.0) + overlap
        }
        val covered = spans.values.sum()
        val scale = if (covered > windowMs) windowMs / covered else 1.0
        val parts = LinkedHashMap<String, Double>()
        for ((pkg, ms) in spans) parts[pkg] = ms * scale
        val rest = windowMs - parts.values.sum()
        if (rest > 0) {
            val top = s.from.top.ifEmpty { s.to.top }
            parts[top] = (parts[top] ?: 0.0) + rest
        }
        return parts
    }

    /** The fraction of an episode's time that lies inside the window; zero for an empty episode. */
    private fun share(startMs: Long, endMs: Long, fromMs: Long, toMs: Long): Double {
        val length = endMs - startMs
        if (length <= 0) return 0.0
        val inside = min(endMs, toMs) - max(startMs, fromMs)
        return if (inside > 0) inside.toDouble() / length else 0.0
    }

    private fun List<Episode>.hoursOf(kind: EpisodeKind): Double = filter { it.kind == kind }.sumOf { it.hours }

    private class Sums {
        private val hoursByKind = EnumMap<EpisodeKind, Double>(EpisodeKind::class.java)
        private val mahByKind = EnumMap<EpisodeKind, Double>(EpisodeKind::class.java)

        fun add(e: Episode, share: Double) {
            if (share <= 0) return
            hoursByKind[e.kind] = hours(e.kind) + e.hours * share
            mahByKind[e.kind] = mah(e.kind) + (e.mah ?: 0.0) * share
        }

        fun hours(kind: EpisodeKind): Double = hoursByKind[kind] ?: 0.0

        fun mah(kind: EpisodeKind): Double = mahByKind[kind] ?: 0.0
    }

    private class AppSum {
        var hours = 0.0
        var mah = 0.0
    }
}
