package app.booxultimatum.nib.editor

import app.booxultimatum.nib.engine.doc.PackedPoints
import app.booxultimatum.nib.engine.doc.Stroke
import kotlin.math.hypot
import kotlin.math.max

/**
 * Notices the pen held still: [held] turns true once every sample for [holdMs] has stayed within [slop] of where the
 * pen stopped. Fed with view pixels and milliseconds; pure, so it's tested without a clock.
 */
class HoldDetector(private val slop: Float, private val holdMs: Long) {
    private var anchorX = Float.NaN
    private var anchorY = 0f
    private var since = 0L

    fun reset() {
        anchorX = Float.NaN
    }

    /** A pen sample at ([x], [y]) at time [ms]. Returns true when the pen moved on, so the wait starts again. */
    fun move(x: Float, y: Float, ms: Long): Boolean {
        if (anchorX.isNaN() || hypot(x - anchorX, y - anchorY) > slop) {
            anchorX = x
            anchorY = y
            since = ms
            return true
        }
        return false
    }

    /** Whether the pen has been still for long enough by [nowMs]. */
    fun held(nowMs: Long): Boolean = !anchorX.isNaN() && nowMs - since >= holdMs

    /** How much longer the pen must stay still, from [nowMs]; 0 once it has been held. */
    fun remaining(nowMs: Long): Long = if (anchorX.isNaN()) holdMs else (holdMs - (nowMs - since)).coerceAtLeast(0L)
}

/**
 * The predictive straight line: a stroke whose pen rests at its end for about half a second becomes a straight
 * segment from where it began to where the pen is, keeping the pressure it was drawn with along its length.
 */
object StraightLine {
    const val HOLD_MS = 500L

    /** Shorter strokes, in view pixels, are dots or ticks, and stay as drawn. */
    const val MIN_LENGTH_DP = 24f

    /** How far the resting pen may wander, in dp, and still count as still. */
    const val SLOP_DP = 6f

    /** The drawn path's length, in document pixels. */
    fun length(stroke: Stroke): Float {
        val p = stroke.points
        var l = 0f
        for (i in 1 until p.size) l += hypot(p.x(i) - p.x(i - 1), p.y(i) - p.y(i - 1))
        return l
    }

    /**
     * [stroke] as a straight segment from its first point to ([endX], [endY]), in document pixels. Pressure, tilt and
     * orientation are taken from the drawn path at the same fraction of its length, and time is spread evenly.
     */
    fun straighten(stroke: Stroke, endX: Float, endY: Float): Stroke {
        val p = stroke.points
        if (p.size == 0) return stroke
        val n = p.size.coerceIn(2, MAX_POINTS)
        val cum = FloatArray(p.size)
        for (i in 1 until p.size) cum[i] = cum[i - 1] + hypot(p.x(i) - p.x(i - 1), p.y(i) - p.y(i - 1))
        val total = cum.last()
        val x0 = p.x(0)
        val y0 = p.y(0)
        val dt = max(0L, p.durationMillis / (n - 1))
        val b = PackedPoints.Builder(n)
        var j = 0
        for (k in 0 until n) {
            val f = k / (n - 1f)
            val at = f * total
            while (j + 1 < p.size - 1 && cum[j + 1] < at) j++
            val span = if (j + 1 < p.size) cum[j + 1] - cum[j] else 0f
            val u = if (span > 0f) ((at - cum[j]) / span).coerceIn(0f, 1f) else 0f
            val k1 = (j + 1).coerceAtMost(p.size - 1)
            val pressure = p.pressure(j) + (p.pressure(k1) - p.pressure(j)) * u
            b.add(x0 + (endX - x0) * f, y0 + (endY - y0) * f, pressure, p.tilt(j), p.orientation(j), if (k == 0) 0L else dt)
        }
        return stroke.copy(points = b.build())
    }

    private const val MAX_POINTS = 64
}