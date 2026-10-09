package app.booxultimatum.core.battery

/** OWNER: A2. Time left. */
object Estimates {
    /** Null when the log holds under a day of unplugged time, or no baseline rates. */
    fun timeLeft(t: Timeline, baseline: Baseline, storedMah: Double, nowMs: Long): Estimate? = TODO("A2")
}