package app.booxultimatum.nib.engine.input

import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.doc.PackedPoints
import app.booxultimatum.nib.engine.doc.Stroke
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Turns pen samples into a [Stroke].
 *
 * Samples closer than [MIN_DISTANCE] to the previous kept one are dropped, except that the last sample is always kept
 * by [finish]. Positions are smoothed with an exponential moving average whose strength is [BrushSpec.smoothing]; the
 * last few points ease back onto the raw samples, so the stroke ends exactly where the pen lifted, without a kink.
 * Not thread-safe.
 */
class StrokeBuilder(val brush: BrushSpec, val color: Int, val id: Long) {
    private var n = 0
    private var rawX = FloatArray(64)
    private var rawY = FloatArray(64)
    private var smoothX = FloatArray(64)
    private var smoothY = FloatArray(64)
    private var pressure = FloatArray(64)
    private var tilt = FloatArray(64)
    private var orientation = FloatArray(64)
    private var time = LongArray(64)
    private var pending: InputSample? = null
    private var finished = false

    private val alpha = 1f - MAX_SMOOTHING * brush.smoothing.coerceIn(0f, 1f)

    /** How many points ease back onto the raw samples at the end: about twice the average's lag. */
    private val tail = (2f * (1f - alpha) / alpha).roundToInt().coerceIn(1, MAX_TAIL) + 1

    /** Kept samples so far. */
    val size: Int get() = n

    val isEmpty: Boolean get() = n == 0 && pending == null

    fun add(sample: InputSample) {
        check(!finished) { "stroke already finished" }
        if (!sample.x.isFinite() || !sample.y.isFinite()) return
        if (n > 0) {
            val dx = sample.x - rawX[n - 1]
            val dy = sample.y - rawY[n - 1]
            if (dx * dx + dy * dy < MIN_DISTANCE * MIN_DISTANCE) {
                pending = sample
                return
            }
        }
        pending = null
        append(sample)
    }

    fun addAll(samples: Iterable<InputSample>) {
        for (s in samples) add(s)
    }

    /** A snapshot of the stroke so far, ending at the latest sample. */
    fun current(): Stroke = build(pending)

    /** Ends the stroke, keeping the last sample even if it was a near duplicate. */
    fun finish(): Stroke {
        if (!finished) {
            pending?.let { append(it) }
            pending = null
            finished = true
        }
        return build(null)
    }

    private fun append(s: InputSample) {
        if (n == rawX.size) grow()
        rawX[n] = s.x
        rawY[n] = s.y
        if (n == 0) {
            smoothX[0] = s.x
            smoothY[0] = s.y
        } else {
            smoothX[n] = smoothX[n - 1] + alpha * (s.x - smoothX[n - 1])
            smoothY[n] = smoothY[n - 1] + alpha * (s.y - smoothY[n - 1])
        }
        pressure[n] = s.pressure
        tilt[n] = s.tilt
        orientation[n] = s.orientation
        time[n] = s.timeNanos
        n++
    }

    private fun build(extra: InputSample?): Stroke {
        val total = n + if (extra != null) 1 else 0
        val b = PackedPoints.Builder(maxOf(1, total))
        val last = total - 1
        var prevTime = if (n > 0) time[0] else extra?.timeNanos ?: 0L
        for (i in 0 until total) {
            val fromExtra = i >= n
            val rx = if (fromExtra) extra!!.x else rawX[i]
            val ry = if (fromExtra) extra!!.y else rawY[i]
            // Samples in the tail ease from the smoothed path onto the raw one; the extra sample is raw.
            val sx = if (fromExtra) rx else smoothX[i]
            val sy = if (fromExtra) ry else smoothY[i]
            val fromEnd = last - i
            val w = if (fromEnd >= tail) 0f else 1f - fromEnd.toFloat() / tail
            val x = sx + (rx - sx) * w
            val y = sy + (ry - sy) * w
            val t = if (fromExtra) extra!!.timeNanos else time[i]
            val p = if (fromExtra) extra!!.pressure else pressure[i]
            val tl = if (fromExtra) extra!!.tilt else tilt[i]
            val o = if (fromExtra) extra!!.orientation else orientation[i]
            b.add(x, y, p, tl, o, ((t - prevTime) / 1_000_000.0).roundToLong())
            prevTime = t
        }
        return Stroke(id, brush, color, b.build())
    }

    private fun grow() {
        val size = rawX.size * 2
        rawX = rawX.copyOf(size)
        rawY = rawY.copyOf(size)
        smoothX = smoothX.copyOf(size)
        smoothY = smoothY.copyOf(size)
        pressure = pressure.copyOf(size)
        tilt = tilt.copyOf(size)
        orientation = orientation.copyOf(size)
        time = time.copyOf(size)
    }

    companion object {
        /** Samples closer than this to the previous kept one are dropped, in document pixels. */
        const val MIN_DISTANCE = 0.25f

        /** The moving average's weight on history at full smoothing. */
        private const val MAX_SMOOTHING = 0.85f
        private const val MAX_TAIL = 16
    }
}
