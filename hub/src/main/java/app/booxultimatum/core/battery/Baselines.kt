package app.booxultimatum.core.battery

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** OWNER: A2. What is normal on this tablet. */
object Baselines {
    private const val DAY_MS = 86_400_000L
    private const val WINDOW_MS = 28 * DAY_MS
    private const val HOUR_MS = 3_600_000.0
    private const val MEDIAN_MIN_HOURS = 0.25
    private const val QUIET_MIN_HOURS = 6.0
    private const val DAY_MIN_HOURS = 2.0
    private const val SPREAD_MIN_DAYS = 3
    private const val IN_USE_MIN_HOURS = 0.5
    private const val SHARE_MIN_HOURS = 1.0

    internal val UNPLUGGED = setOf(EpisodeKind.InUse, EpisodeKind.Asleep, EpisodeKind.DisplayOn)

    /** From the last 28 days of [t] before [nowMs], unplugged time only, display-on episodes excluded from the quiet rate. */
    fun of(t: Timeline, nowMs: Long): Baseline {
        val recent = t.episodes.filter { it.startMs >= nowMs - WINDOW_MS && it.kind in UNPLUGGED }
        val asleep = recent.filter { it.kind == EpisodeKind.Asleep }
        val asleepHours = asleep.sumOf { it.hours }
        val unpluggedHours = recent.sumOf { it.hours }
        val quiet = asleep.filter { it.hours >= MEDIAN_MIN_HOURS }.mapNotNull { e -> e.avgMa?.let { it to e.hours } }
        val days = dailyRates(asleep, Long.MIN_VALUE, Long.MAX_VALUE, DAY_MIN_HOURS).sorted()
        return Baseline(
            quietAsleepMa = if (quiet.sumOf { it.second } < QUIET_MIN_HOURS) null else weightedMedian(quiet),
            dayToDaySpreadMa = if (days.size < SPREAD_MIN_DAYS) null else quantile(days, 0.75) - quantile(days, 0.25),
            inUseMa = pooledRate(recent.filter { it.kind == EpisodeKind.InUse }, IN_USE_MIN_HOURS),
            asleepShare = if (unpluggedHours < SHARE_MIN_HOURS) null else asleepHours / unpluggedHours,
            basisDays = unpluggedHours / 24,
        )
    }

    /**
     * The charge rate (mA) of each UTC day with at least [minHours] of charged time in [episodes], counting only what
     * falls between [fromMs] and [toMs]. An episode that crosses midnight shares its charge between the days, by time.
     */
    internal fun dailyRates(episodes: List<Episode>, fromMs: Long, toMs: Long, minHours: Double): List<Double> {
        val days = HashMap<Long, DayTotal>()
        for (e in episodes) {
            val charge = e.mah ?: continue
            val start = max(e.startMs, fromMs)
            val end = min(e.endMs, toMs)
            if (end <= start) continue
            val span = (e.endMs - e.startMs).toDouble()
            var day = start.floorDiv(DAY_MS)
            while (day * DAY_MS < end) {
                val part = min(end, (day + 1) * DAY_MS) - max(start, day * DAY_MS)
                val total = days.getOrPut(day) { DayTotal() }
                total.hours += part / HOUR_MS
                total.mah += charge * part / span
                day++
            }
        }
        return days.values.filter { it.hours >= minHours }.map { it.mah / it.hours }
    }

    /** The hours of [e] that fall between [fromMs] and [toMs]. */
    internal fun overlapHours(e: Episode, fromMs: Long, toMs: Long): Double {
        val ms = min(e.endMs, toMs) - max(e.startMs, fromMs)
        return if (ms > 0) ms / HOUR_MS else 0.0
    }

    /** The [q] quantile of [sorted] (ascending, not empty), interpolated linearly between the two values either side. */
    internal fun quantile(sorted: List<Double>, q: Double): Double {
        val position = q * (sorted.size - 1)
        val low = floor(position).toInt()
        val high = ceil(position).toInt()
        return sorted[low] + (position - low) * (sorted[high] - sorted[low])
    }

    /** The value at which the hours of [samples] (value to hours) first reach half of their total. */
    private fun weightedMedian(samples: List<Pair<Double, Double>>): Double {
        val sorted = samples.sortedBy { it.first }
        val half = sorted.sumOf { it.second } / 2
        var crossed = 0.0
        for ((value, hours) in sorted) {
            crossed += hours
            if (crossed >= half) return value
        }
        return sorted.last().first
    }

    private fun pooledRate(episodes: List<Episode>, minHours: Double): Double? {
        val charged = episodes.filter { it.mah != null }
        val hours = charged.sumOf { it.hours }
        return if (hours < minHours) null else charged.sumOf { it.mah ?: 0.0 } / hours
    }

    private class DayTotal {
        var hours = 0.0
        var mah = 0.0
    }
}
