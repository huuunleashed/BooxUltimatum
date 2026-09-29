package app.booxultimatum.nib.engine.input

import kotlin.math.abs

/**
 * The stationary-sample rule of BOOX's own pen reader: a move sample is dropped when, against the previous kept sample
 * of the same stroke, the pen has hardly moved and hardly changed its pressure. Precisely, when the time difference is
 * positive, the speed (Manhattan distance `|dx| + |dy|` in screen pixels over the time in milliseconds) is below
 * [MIN_SPEED], and the pressure differs by at most [MAX_PRESSURE_CHANGE] (2 of the pen's 4096 levels). Down and up
 * samples are always kept; [StrokeBuilder] keeps the first and the last.
 *
 * One addition to the native rule: only a sample within [MAX_DISTANCE] of the kept one can be dropped. Compared with
 * the last kept sample, a slow, steady stroke (under 5 px/s) would otherwise never qualify as moving and collapse to a
 * straight line between its ends; the native reader's pressure noise mostly hides that, but a slow careful line
 * shouldn't depend on noise.
 */
object StationaryFilter {
    /** Slower than this, in screen pixels per millisecond, counts as not moving. */
    const val MIN_SPEED = 0.005f

    /** A pressure change up to this, on the 0..1 scale, counts as none: 2 of the pen's raw levels. */
    const val MAX_PRESSURE_CHANGE = 2f / PressureCurve.DEVICE_PRESSURE_MAX

    /** Farther than this from the kept sample, in screen pixels (Manhattan), a sample always counts as moving. */
    const val MAX_DISTANCE = 2f

    /** Room for rounding in a pressure normalised from raw levels. */
    private const val PRESSURE_SLACK = 1e-6f

    /**
     * True when [next] repeats [kept] and would be dropped. Positions are document pixels, turned into screen pixels by
     * [viewScale] (screen pixels per document pixel); a time difference of zero or less never drops a sample.
     */
    fun isStationary(kept: InputSample, next: InputSample, viewScale: Float = 1f): Boolean =
        isStationary(kept.x, kept.y, kept.pressure, kept.timeNanos, next.x, next.y, next.pressure, next.timeNanos, viewScale)

    /** [isStationary] on plain values, so a caller that keeps samples in arrays needn't allocate. */
    fun isStationary(
        keptX: Float, keptY: Float, keptPressure: Float, keptNanos: Long,
        x: Float, y: Float, pressure: Float, nanos: Long,
        viewScale: Float = 1f,
    ): Boolean {
        val dtNanos = nanos - keptNanos
        if (dtNanos <= 0L) return false
        if (!(abs(pressure - keptPressure) <= MAX_PRESSURE_CHANGE + PRESSURE_SLACK)) return false
        val scale = if (viewScale > 0f && viewScale.isFinite()) viewScale else 1f
        val distance = (abs(x - keptX) + abs(y - keptY)) * scale
        if (distance > MAX_DISTANCE) return false
        val ms = dtNanos / 1_000_000.0
        return distance / ms < MIN_SPEED
    }
}
