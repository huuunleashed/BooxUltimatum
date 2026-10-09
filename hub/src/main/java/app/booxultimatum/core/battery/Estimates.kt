package app.booxultimatum.core.battery

import kotlin.math.max
import kotlin.math.min

/** OWNER: A2. Time left. */
object Estimates {
    private const val DAY_MS = 86_400_000L
    private const val RANGE_DAYS = 7
    private const val RANGE_MIN_DAYS = 3
    private const val DAY_MIN_HOURS = 2.0
    private const val FLOOR_MA = 1.0
    private const val LOW_FACTOR = 0.7
    private const val HIGH_FACTOR = 1.4

    /** Null when the log holds under a day of unplugged time, or no baseline rates. */
    fun timeLeft(t: Timeline, baseline: Baseline, storedMah: Double, nowMs: Long): Estimate? {
        val quiet = baseline.quietAsleepMa ?: return null
        val inUse = baseline.inUseMa ?: return null
        val share = baseline.asleepShare ?: return null
        if (baseline.basisDays < 1.0 || storedMah <= 0.0) return null
        val hours = storedMah / max(share * quiet + (1 - share) * inUse, FLOOR_MA)
        val draws = Baselines.dailyRates(
            t.episodes.filter { it.kind in Baselines.UNPLUGGED },
            nowMs - RANGE_DAYS * DAY_MS,
            nowMs,
            DAY_MIN_HOURS,
        ).map { max(it, FLOOR_MA) }.sorted()
        val (low, high) = if (draws.size >= RANGE_MIN_DAYS) {
            storedMah / Baselines.quantile(draws, 0.75) to storedMah / Baselines.quantile(draws, 0.25)
        } else {
            hours * LOW_FACTOR to hours * HIGH_FACTOR
        }
        return Estimate(
            hours = hours,
            lowHours = min(low, hours),
            highHours = max(high, hours),
            basisDays = baseline.basisDays,
            quietMa = quiet,
            inUseMa = inUse,
            asleepShare = share,
        )
    }
}
