package app.booxultimatum.nib.engine.input

import kotlin.math.ceil
import kotlin.math.floor

/**
 * The positional smoothing of BOOX's fountain pen: the low-pass filter the firmware runs as
 * `neo3pen12SmoothFilter` in `libneopen_jni.so`, for pen type 6 (`neo3pen16NeoFountainPenV2`).
 *
 * The constants come from that binary, recorded in `knowledge/experiments.md` and `docs/09-ink.md` › *Inside the pen library*: the 15 taps of the base kernel are the
 * literal table at file offset `0x2c1028`, the level's scale is the `50.0f` at `0x334650`, the 1..50 clamp is the
 * `csel` pair at `0x31bbac…0x31bbbc`, and the resampling onto `n` taps is the weight loop at `0x31bc9c…0x31bcf8`.
 *
 * The kernel's orientation is verified in the binary: the window appends a sample at its end (`0x31c56c`), trims one
 * from its front (`0x31c5d0`) and pads its front (`0x31c5a4`), so tap `n - 1` weights the newest sample and the filter
 * is causal. That orientation trails the pen: the kernel's centroid sits 1.63 samples behind tap `n - 1` at 15 taps,
 * about 3.5 behind at 30 and about 6.0 behind at 50.
 *
 * The firmware pads the front of the window by repeating the first sample, so a run starts with `values[0]` in the
 * slots that precede it, and `out[0]` is `values[0]` to rounding.
 *
 * The native stage is incremental, and [Filter] models it. The running path emits a point for every sample
 * (`0x31c314`); the pen-up path emits all but the last (`0x31c01c`), re-seeds the window with the lift point (§7) and
 * re-appends it `floor(4n/15)` times, four at the least (`0x31c078`). That flush is what stops a stroke ending
 * short: the filter is causal, so without it the last output trails the lift point by up to a window, and the stroke
 * stops before the pen leaves the screen. [apply] smooths a whole run in one pass and does neither the re-seed nor the
 * flush, so a run smoothed with it ends short; it stays as the offline reference the stateful form is tested against.
 * Both halves of the native blob are filtered alike (§8.3), so any channel is smoothed the same way.
 *
 * [StrokeBuilder] smooths every new stroke's positions with [Filter], one per channel; [apply] stays the offline
 * reference the stateful form is tested against.
 */
object PenSmoothing {
    /** The firmware's `50.0f` at `0x334650`: the scale from a level to a tap count. */
    private const val LEVEL_SCALE = 50f

    /** The firmware's ceiling on the window, `0x32` at `0x31bbbc`. */
    const val MAX_WINDOW = 50

    /** Taps in the base kernel, 60 bytes of them at `0x2c1028`. */
    const val BASE_TAPS = 15

    /**
     * The firmware's 15 taps in memory order, tap 0 first, the table at `0x2c1028`. It is already normalised: it
     * sums to 0.99999999. Tap 14, the one [apply] gives the newest sample, is 0.1149, and tap 12 is 0.3471. It is
     * private because a caller could change it; [baseKernel] hands out a copy instead.
     */
    private val BASE_KERNEL = floatArrayOf(
        -6.000000212225132e-06f, -1.3899999612476677e-04f, -1.8499999714549630e-04f,
        4.1400000918656588e-04f, 2.3570000194013120e-03f, 3.3569999504834414e-03f,
        -3.1349998898804188e-03f, -2.39279996603727e-02f, -4.290900006890297e-02f,
        -1.7858000472187996e-02f, 9.652499854564667e-02f, 2.5469198822975159e-01f,
        3.470720052719116e-01f, 2.688100039958954e-01f, 1.1493299901485443e-01f,
    )

    /**
     * A copy of the raw 15 taps at `0x2c1028`, for a test that checks their bits. The copy means a caller cannot
     * change the table behind the filter.
     */
    internal fun baseKernel(): FloatArray = BASE_KERNEL.copyOf()

    /**
     * The window [smoothLevel] asks for: `(smoothLevel * 50)` truncated, then clamped to 1..50. Truncation, not
     * rounding, is what the firmware does (`fcvtzu`), so 0.02 asks for 1 tap and 0.999 for 49. A level outside 0..1
     * is clamped. A NaN level reads as 0, so it asks for 1 tap.
     */
    fun windowLength(smoothLevel: Float): Int {
        val level = if (smoothLevel.isNaN()) 0f else smoothLevel.coerceIn(0f, 1f)
        return (level * LEVEL_SCALE).toInt().coerceIn(1, MAX_WINDOW)
    }

    /**
     * The taps for an [n]-tap window, oldest first: the base kernel resampled by linear interpolation at
     * `u = i * 14 / (n - 1)`, then divided by their sum so they sum to 1. The firmware also scales the resampled taps
     * by `15 / n`, which cancels in the normalisation, so this leaves it out; at `n = 15` the firmware takes a
     * shortcut that skips both steps, which is the same thing because resampling onto 15 taps is the identity. One
     * tap or fewer gets `[1f]`, which [apply] passes through.
     */
    fun weights(n: Int): FloatArray {
        if (n <= 1) return floatArrayOf(1f)
        val out = FloatArray(n)
        var sum = 0f
        for (i in 0 until n) {
            val u = i * (BASE_TAPS - 1).toFloat() / (n - 1).toFloat()
            val lo = floor(u).toInt()
            val hi = ceil(u).toInt()
            out[i] = BASE_KERNEL[lo] + (BASE_KERNEL[hi] - BASE_KERNEL[lo]) * (u - lo)
            sum += out[i]
        }
        for (i in out.indices) out[i] /= sum
        return out
    }

    /**
     * Smooths [values] with the window [smoothLevel] asks for, in one causal pass over the whole run: `out[i]` is the
     * sum of `weights[k] * values[max(0, i - n + 1 + k)]` over `k` in `0 until n`. The last tap weights `values[i]`
     * itself, and the first weights the oldest sample in the window. The slots before the run repeat `values[0]`, so
     * `out[0]` is `values[0]` to rounding.
     *
     * This is the offline reference; [Filter] is the same pass, one sample at a time, for a stroke as it is drawn.
     *
     * [values] must be finite: a NaN spreads to each output its window reaches, which is `n` of them.
     *
     * The call returns [values] itself, unsmoothed, when [values] holds fewer than 2 values or the window is one tap.
     */
    fun apply(values: FloatArray, smoothLevel: Float): FloatArray =
        apply(values, weights(windowLength(smoothLevel)))

    /**
     * [apply] with the taps already built by [weights], for a caller that smooths many runs at the same level and
     * builds them once. The call returns [values] itself, unsmoothed, when [values] holds fewer than 2 values or
     * [weights] holds one tap or fewer. [values] must be finite, for the reason [apply] gives.
     */
    fun apply(values: FloatArray, weights: FloatArray): FloatArray {
        val n = weights.size
        if (n <= 1 || values.size <= 1) return values
        val out = FloatArray(values.size)
        for (i in values.indices) {
            val oldest = i - n + 1
            var acc = 0f
            for (k in 0 until n) acc += weights[k] * values[maxOf(oldest + k, 0)]
            out[i] = acc
        }
        return out
    }

    /**
     * One channel of the firmware's smoothing, sample by sample, for a stroke as it is drawn: the stateful form of
     * [apply], with the pen-up flush the native stage runs.
     *
     * A stroke builds one filter, [push]es each sample as the pen reports it and keeps the value that comes back.
     * A filter smooths one channel: smooth x and y with two of them, built together when the stroke starts and pushed
     * from the same loop, so neither channel allocates per sample.
     *
     * ```
     * val fx = PenSmoothing.Filter(level)
     * val fy = PenSmoothing.Filter(level)
     * val x = fx.push(point.x)
     * val y = fy.push(point.y)
     * ```
     *
     * The taps and the window are allocated once, in the constructor: [push] and [reset] allocate nothing, and
     * [flush] builds only the tail it returns, which is the one array a stroke makes.
     *
     * At pen-up the caller emits the stroke's points, then the values [flush] returns, which carry the stroke onto
     * the lift point. [StrokeBuilder] builds two, one per channel, for each stroke.
     *
     * [smoothLevel] is the pen's smoothing, 0 to 1, the same value [apply] takes. A level that asks for one tap passes
     * samples through unchanged, tail included.
     */
    class Filter(smoothLevel: Float) {
        /** The taps for this window, oldest first, exactly as [apply] uses them. */
        private val taps = PenSmoothing.weights(PenSmoothing.windowLength(smoothLevel))

        /** Taps in the window, and slots in [window]. */
        private val n = taps.size

        /** The last [n] samples: the window's slots, in no fixed order because it is a ring. */
        private val window = FloatArray(n)

        /** The slot holding the oldest sample, which the next push overwrites. */
        private var head = 0

        /** False until the first push, which pads the window by repeating its own sample. */
        private var started = false

        /** The newest sample, which the pen-up tail is built from. */
        private var last = 0f

        /** How many samples the stroke has pushed, which caps the pen-up tail. */
        private var pushed = 0

        /**
         * Appends one sample and returns the filtered value for it, the same number [apply] gives that position of the
         * run. Before the window fills, the slots before the run repeat the first sample, as [apply] pads, so a stroke
         * starts at its own value rather than easing in from zero.
         *
         * Allocates nothing: a push writes into the window it already has and returns a float. [value] must be finite,
         * for the reason [apply] gives.
         */
        fun push(value: Float): Float {
            if (started) {
                window[head] = value
                head++
                if (head == n) head = 0
            } else {
                window.fill(value)
                started = true
            }
            last = value
            pushed++
            var acc = 0f
            var slot = head
            for (k in 0 until n) {
                acc += taps[k] * window[slot]
                slot++
                if (slot == n) slot = 0
            }
            return acc
        }

        /**
         * Ends the stroke and returns the tail to emit after the last value [push] gave: `floor(4n / 15)` values, for
         * an [n]-tap window, at most as many as the stroke had samples.
         *
         * The firmware emits every point but the last, then appends the lift point `floor(4n / 15)` times and emits
         * what each append puts out (`0x31c01c`, `0x31c078`, with the count from `0x31bc10`). Each append slides the
         * window one sample on, so the tail is a decaying run-on that converges towards the lift point rather than the
         * lift point repeated: on a unit ramp at fifteen taps it reads 39.372, 40.257, 40.873, 41.142 for a lift at
         * 41. That is what stops a stroke ending short, since the filter is causal and its last output trails the
         * lift point by up to a window.
         *
         * The window is left as those appends left it, and the filter is closed: pushing again is not supported, and
         * a second flush returns an empty array. Call [reset] before the next stroke. A filter that has not been
         * pushed returns an empty array.
         */
        fun flush(): FloatArray {
            if (!started) return FloatArray(0)
            val k = minOf(n * 4 / 15, pushed)
            val tail = FloatArray(k) { push(last) }
            started = false
            return tail
        }

        /**
         * Clears the filter for the next stroke, so it behaves exactly as a new one: the next [push] pads its window
         * with its own sample. Allocates nothing.
         */
        fun reset() {
            head = 0
            started = false
            last = 0f
            pushed = 0
        }
    }
}
