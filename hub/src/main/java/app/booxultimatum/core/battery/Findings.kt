package app.booxultimatum.core.battery

/** OWNER: A2. The rules of docs/10-battery.md section 4.1. */
object Findings {
    /** Findings and the verdict over [t]'s whole span (the caller passes a timeline built for its window). */
    fun evaluate(t: Timeline, baseline: Baseline, signals: Signals, nowMs: Long): Verdict = TODO("A2")
}