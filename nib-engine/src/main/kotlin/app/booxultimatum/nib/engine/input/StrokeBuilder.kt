package app.booxultimatum.nib.engine.input

import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.doc.PackedPoints
import app.booxultimatum.nib.engine.doc.Stroke
import kotlin.math.roundToLong

/**
 * Turns pen samples into a [Stroke].
 *
 * Samples closer than [MIN_DISTANCE] to the previous kept one are dropped, and so, with [dropStationary], are the
 * resting pen's repeats BOOX's own pen reader drops (see [StationaryFilter]; [viewScale] is the view's screen pixels
 * per document pixel, so its speed is measured on screen as BOOX's is). The first sample is always kept, and so is the
 * last, by [finish].
 *
 * What survives is smoothed by BOOX's own filter, [PenSmoothing.Filter], at the brush's [BrushSpec.smoothing]: the
 * low-pass filter the firmware runs before its fountain pen's spline, whose taps and pen-up rule are read from
 * `libneopen_jni.so` (recorded in `knowledge/experiments.md` and `docs/09-ink.md` › *Inside the pen library*). Nib
 * runs it for every brush. A stroke builds one filter for x and one for y, with the stroke itself, so each stroke
 * starts fresh and neither allocates per sample. Pressure, tilt, orientation and time are the samples' own: only
 * positions are smoothed.
 *
 * The filter runs after the distance and stationary rules, on the points they keep, never before them: those rules
 * compare the pen's own samples, and the filter only sees the stroke that is stored. That order is deliberate.
 *
 * The filter is causal, so it trails the pen. [finish] therefore ends the stroke the way the firmware does: it emits
 * the points it has, then the filter's pen-up tail, the lift point appended `floor(4 * window / 15)` times. That tail
 * runs on towards where the pen left the screen, but it settles near the lift point rather than on it: at fifteen taps
 * a ramp's tail ends at 41.142 for a lift at 41. So the last few points also ease from the filtered path onto the raw
 * samples, as they did before the filter took over: the tail onto the lift point, the point before it onto its own
 * sample. The stroke therefore ends exactly where the pen lifted, without a kink.
 *
 * Not thread-safe.
 */
class StrokeBuilder(
    val brush: BrushSpec,
    val color: Int,
    val id: Long,
    val dropStationary: Boolean = true,
    val viewScale: Float = 1f,
) {
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

    /** The filter's pen-up tail, filled by [finish]: points to emit after the last kept one, at the lift point. */
    private var tailX = FloatArray(0)
    private var tailY = FloatArray(0)
    private var tailN = 0

    /** BOOX's filter smooths one channel at a time, so a stroke builds two: a builder is one stroke. */
    private val filterX = PenSmoothing.Filter(brush.smoothing)
    private val filterY = PenSmoothing.Filter(brush.smoothing)

    /** The pen-up tail's length: `floor(4 * window / 15)`, as the firmware counts it. */
    private val penUpTail = PenSmoothing.windowLength(brush.smoothing) * 4 / 15

    /** How many points ease back onto the raw samples at the end: the whole pen-up tail, and the point before it. */
    private val ease = penUpTail + 1

    /** Kept samples so far; the pen-up tail is not counted. */
    val size: Int get() = n

    val isEmpty: Boolean get() = n == 0 && pending == null

    fun add(sample: InputSample) {
        check(!finished) { "stroke already finished" }
        if (!sample.x.isFinite() || !sample.y.isFinite()) return
        if (n > 0) {
            val k = n - 1
            val dx = sample.x - rawX[k]
            val dy = sample.y - rawY[k]
            val near = dx * dx + dy * dy < MIN_DISTANCE * MIN_DISTANCE
            if (near || (dropStationary && StationaryFilter.isStationary(rawX[k], rawY[k], pressure[k], time[k], sample.x, sample.y, sample.pressure, sample.timeNanos, viewScale))) {
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

    /**
     * Ends the stroke, keeping the last sample even if it is a near duplicate, and adding the filter's pen-up tail.
     */
    fun finish(): Stroke {
        if (!finished) {
            pending?.let { append(it) }
            pending = null
            finished = true
            // The pen-up tail: the lift point pushed `floor(4 * window / 15)` more times, which runs the stroke on
            // towards where the pen left the screen. A one-point stroke is a dot, and its tail would only repeat it.
            if (n > 1) {
                tailX = filterX.flush()
                tailY = filterY.flush()
                tailN = minOf(tailX.size, tailY.size)
            }
        }
        return build(null)
    }

    private fun append(s: InputSample) {
        if (n == rawX.size) grow()
        rawX[n] = s.x
        rawY[n] = s.y
        // After the rules in add, never before them: the filter smooths the points they kept, in the pen's order.
        smoothX[n] = filterX.push(s.x)
        smoothY[n] = filterY.push(s.y)
        pressure[n] = s.pressure
        tilt[n] = s.tilt
        orientation[n] = s.orientation
        time[n] = s.timeNanos
        n++
    }

    private fun build(extra: InputSample?): Stroke {
        val total = n + tailN + if (extra != null) 1 else 0
        val b = PackedPoints.Builder(maxOf(1, total))
        val last = total - 1
        // The lift point, the last kept sample. A tail is only filled for a stroke of two points or more.
        val lift = n - 1
        var prevTime = if (n > 0) time[0] else extra?.timeNanos ?: 0L
        for (i in 0 until total) {
            val inTail = i >= n && i < n + tailN
            val fromExtra = i >= n + tailN
            val src = if (inTail) i - n else i
            // The position the filter gave, and the raw one the point belongs to: its own sample, or the lift point
            // for the pen-up tail, whose points are the lift point pushed again.
            val sx: Float
            val sy: Float
            val rx: Float
            val ry: Float
            val p: Float
            val tl: Float
            val o: Float
            val t: Long
            when {
                fromExtra -> {
                    val s = extra!!
                    sx = s.x
                    sy = s.y
                    rx = s.x
                    ry = s.y
                    p = s.pressure
                    tl = s.tilt
                    o = s.orientation
                    t = s.timeNanos
                }
                inTail -> {
                    sx = tailX[src]
                    sy = tailY[src]
                    rx = rawX[lift]
                    ry = rawY[lift]
                    p = pressure[lift]
                    tl = tilt[lift]
                    o = orientation[lift]
                    t = time[lift]
                }
                else -> {
                    sx = smoothX[src]
                    sy = smoothY[src]
                    rx = rawX[src]
                    ry = rawY[src]
                    p = pressure[src]
                    tl = tilt[src]
                    o = orientation[src]
                    t = time[src]
                }
            }
            // Points in the tail ease from the filtered path onto the raw one; the lift point lands exactly on itself.
            val fromEnd = last - i
            val w = if (fromEnd >= ease) 0f else 1f - fromEnd.toFloat() / ease
            val x = sx + (rx - sx) * w
            val y = sy + (ry - sy) * w
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
    }
}
