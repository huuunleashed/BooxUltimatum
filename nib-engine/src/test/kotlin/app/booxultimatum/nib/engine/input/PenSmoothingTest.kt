package app.booxultimatum.nib.engine.input

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class PenSmoothingTest {
    /** Levels that reach the interesting windows: 1, 1, 3, 4, 15, 30, 49 and 50 taps. */
    private val levels = listOf(0f, 0.02f, 0.06f, 0.08f, 0.3f, 0.6f, 0.999f, 1f)

    /** The firmware's pen-up tail length: floor(4n / 15), with no floor of its own. */
    private fun tailLength(level: Float): Int = PenSmoothing.windowLength(level) * 4 / 15
    @Test
    fun theBaseKernelIsTheFirmwaresTable() {
        val kernel = PenSmoothing.baseKernel()
        assertEquals(PenSmoothing.BASE_TAPS, kernel.size)
        // The raw bits from the table at file offset 0x2c1028, in memory order. These are the exact check.
        val bits = intArrayOf(
            0xb6c9539c.toInt(), 0xb911c087.toInt(), 0xb941fc8f.toInt(), 0x39d90e24.toInt(), 0x3b1a77e6.toInt(),
            0x3b5c011d.toInt(), 0xbb4d7492.toInt(), 0xbcc404a7.toInt(), 0xbd2fc159.toInt(), 0xbc924af1.toInt(),
            0x3dc5aee6.toInt(), 0x3e8266fd.toInt(), 0x3eb1b36c.toInt(), 0x3e89a177.toInt(), 0x3deb61fe.toInt(),
        )
        for (i in bits.indices) {
            assertEquals(bits[i], kernel[i].toRawBits(), "tap $i")
        }
        // The table is already normalised, and its mass sits at the high end.
        var sum = 0f
        for (v in kernel) sum += v
        assertEquals(1f, sum, 1e-6f)
        assertTrue(kernel[14] > 0.11f)
        assertTrue(kernel[0] > -1e-5f)
        // The accessor hands out a copy, so no caller can change the table behind the filter.
        kernel[0] = 0f
        assertTrue(PenSmoothing.baseKernel()[0] != 0f)
    }

    @Test
    fun theWindowIsTruncatedAndClamped() {
        assertEquals(1, PenSmoothing.windowLength(0f))
        assertEquals(1, PenSmoothing.windowLength(0.02f))
        assertEquals(30, PenSmoothing.windowLength(0.6f))
        assertEquals(50, PenSmoothing.windowLength(1f))
        assertEquals(1, PenSmoothing.windowLength(-1f))
        assertEquals(50, PenSmoothing.windowLength(2f))
        assertEquals(50, PenSmoothing.windowLength(Float.POSITIVE_INFINITY))
        assertEquals(1, PenSmoothing.windowLength(Float.NaN))
        assertEquals(1, PenSmoothing.windowLength(0.039f), "1.95 truncates to 1, so this is truncation, not rounding")
        assertEquals(49, PenSmoothing.windowLength(0.999f), "49.95 truncates to 49")
    }

    @Test
    fun everyWindowSumsToOne() {
        for (n in listOf(1, 2, 3, 15, 50)) {
            val w = PenSmoothing.weights(n)
            assertEquals(n, w.size, "n = $n")
            var sum = 0f
            for (v in w) sum += v
            assertEquals(1f, sum, 1e-6f, "n = $n")
        }
        assertEquals(1, PenSmoothing.weights(1).size)
        assertEquals(1f, PenSmoothing.weights(1)[0])
        assertEquals(1f, PenSmoothing.weights(0)[0], "one tap or fewer")
        // At 15 taps the resampling is the identity, up to the normalisation.
        val kernel = PenSmoothing.baseKernel()
        val w15 = PenSmoothing.weights(15)
        for (i in w15.indices) assertEquals(kernel[i], w15[i], 1e-6f, "tap $i")
    }

    @Test
    fun aConstantComesBackUnchanged() {
        val values = FloatArray(40) { 0.4f }
        for (n in listOf(1, 2, 3, 15, 50)) {
            val out = PenSmoothing.apply(values, PenSmoothing.weights(n))
            for (i in out.indices) assertEquals(0.4f, out[i], 1e-6f, "n = $n at $i")
        }
        // Through the level too, at the native fountain pen's own smoothing of 0.6: 30 taps.
        val byLevel = PenSmoothing.apply(values, 0.6f)
        for (i in byLevel.indices) assertEquals(0.4f, byLevel[i], 1e-6f)
        // The window pads its front with the first value, so no stroke starts with a dip toward zero.
        val ramp = floatArrayOf(5f, 6f, 7f, 8f, 9f)
        for (n in listOf(2, 3, 15, 50)) {
            assertEquals(5f, PenSmoothing.apply(ramp, PenSmoothing.weights(n))[0], 1e-6f, "n = $n")
        }
    }

    @Test
    fun theNewestSampleIsWeightedByTheLastTap() {
        val w = PenSmoothing.weights(15)
        val values = FloatArray(60)
        values[30] = 1f
        val out = PenSmoothing.apply(values, w)
        for (i in 0 until 30) assertEquals(0f, out[i], 1e-9f)
        assertEquals(w[14], out[30], 1e-6f, "the impulse is weighted by the last tap")
        assertEquals(w[13], out[31], 1e-6f, "and a sample later by the one before it")
        assertEquals(w[12], out[32], 1e-6f)
        assertEquals(w[0], out[44], 1e-6f, "the first tap weights the impulse 14 samples on")
        for (i in 45 until 60) assertEquals(0f, out[i], 1e-9f)
    }

    @Test
    fun aStepTakesOneWindowToSettle() {
        val w = PenSmoothing.weights(15)
        val n = w.size
        val at = 20
        val values = FloatArray(60) { if (it < at) 0f else 1f }
        val out = PenSmoothing.apply(values, w)

        for (i in 0 until at) assertEquals(0f, out[i], "nothing arrives before the step")
        assertEquals(w[n - 1], out[at], 1e-6f, "the first sample past the step weighs only itself")
        // The taps are signed, so this response cannot be monotonic: it climbs to about 1.082 after five samples,
        // dips to about 0.994 and rings out inside 1 %. What holds is that the climb is monotone, that from the peak
        // on nothing leaves the kernel's own 8.3 % bound, and that a full window in, the step is the level exactly.
        for (i in at until at + 4) assertTrue(out[i] < out[i + 1], "climbing at $i, was ${out[i]} then ${out[i + 1]}")
        assertEquals(1.082f, out[at + 4], 1e-3f)
        for (i in at + 4 until at + n - 1) {
            assertTrue(abs(out[i] - 1f) <= 0.083f, "rings inside 8.3 % at $i, was ${out[i]}")
        }
        for (i in at + n - 1 until out.size) assertEquals(1f, out[i], 1e-6f, "a full window in at $i")
    }

    @Test
    fun aThreeTapWindowFollowsItsOwnFormula() {
        // Level 0.06 asks for three taps: 0.06 * 50 = 3, and float rounding keeps that exact.
        assertEquals(3, PenSmoothing.windowLength(0.06f))
        // At three taps the resampling samples the kernel at 0, 7 and 14, which are -0.000006, -0.023928 and
        // 0.114933. They sum to 0.090999, so the taps are -0.000066, -0.262948 and 1.263014.
        val values = floatArrayOf(1f, 2f, 4f)
        val out = PenSmoothing.apply(values, 0.06f)
        assertEquals(1f, out[0], 1e-6f, "the window is values[0] three times")
        // i = 1: values[0] twice, then values[1].
        assertEquals(-0.000066f * 1f - 0.262948f * 1f + 1.263014f * 2f, out[1], 5e-4f, "out[1] is 2.263014")
        // i = 2: a full window, values[0], values[1] and values[2].
        assertEquals(-0.000066f * 1f - 0.262948f * 2f + 1.263014f * 4f, out[2], 5e-4f, "out[2] is 4.526093")
    }

    @Test
    fun aFourTapWindowStartsWithTheFirstValueRepeated() {
        // Unequal values, so a window that slips by one sample changes the answer.
        val values = floatArrayOf(1f, 2f, 3f, 4f, 5f)
        assertEquals(4, PenSmoothing.windowLength(0.08f))
        // The four taps, written out: the kernel resampled at 0, 4.667, 9.333 and 14 gives -0.000043, 0.021876,
        // 0.146647 and 0.831520, and they sum to 1.
        val out = PenSmoothing.apply(values, 0.08f)
        // i = 1 sums values[0] three times (the slots before the run repeat it), then values[1]: 1.831520.
        assertEquals(-0.000043f * 1f + 0.021876f * 1f + 0.146647f * 1f + 0.831520f * 2f, out[1], 5e-4f, "out[1]")
        // i = 2 opens at values[0] twice, then values[1] and values[2]: 2.809688. A window off by one would give
        // 1.0 and 1.831520 here.
        assertEquals(-0.000043f * 1f + 0.021876f * 1f + 0.146647f * 2f + 0.831520f * 3f, out[2], 5e-4f, "out[2]")
    }

    @Test
    fun aUnitRampReproducesTheNativeNumbers() {
        // values[i] = i, so every output is a weighted sum of indices and any slip in the padding shows up.
        val values = FloatArray(60) { it.toFloat() }
        val expected = mapOf(
            15 to floatArrayOf(0f, 0.1149f, 0.4987f, 1.2295f, 2.2150f),
            30 to floatArrayOf(0f, 0.0572f, 0.2085f, 0.4910f, 0.9247f),
            50 to floatArrayOf(0f, 0.0343f, 0.1159f, 0.2580f, 0.4737f),
        )
        for ((n, want) in expected) {
            val out = PenSmoothing.apply(values, PenSmoothing.weights(n))
            for (i in want.indices) assertEquals(want[i], out[i], 5e-4f, "n = $n at $i")
        }
    }

    @Test
    fun oneTapIsTheIdentity() {
        val values = floatArrayOf(3f, -1f, 0.25f, 7f, 0f)
        assertSame(values, PenSmoothing.apply(values, 0f))
        assertSame(values, PenSmoothing.apply(values, 0.02f))
        assertSame(values, PenSmoothing.apply(values, PenSmoothing.weights(1)))
        assertEquals(values.toList(), PenSmoothing.apply(values, 0f).toList())
    }

    @Test
    fun anEmptyOrSingleValueArrayIsReturnedAsItIs() {
        val none = FloatArray(0)
        val one = floatArrayOf(0.3f)
        assertSame(none, PenSmoothing.apply(none, 1f))
        assertSame(one, PenSmoothing.apply(one, 1f))
        assertSame(none, PenSmoothing.apply(none, PenSmoothing.weights(50)))
        assertSame(one, PenSmoothing.apply(one, PenSmoothing.weights(50)))
    }

    // Filter.push is allocation-free by construction: its window and its taps are allocated once, in the constructor,
    // and a push only writes into them and returns a float. That is read in the source rather than asserted here,
    // since a JVM test has no portable way to count another method's allocations.

    @Test
    fun theStatefulFilterAgreesWithTheWholeRunPass() {
        val sequences = mapOf(
            "a ramp" to FloatArray(120) { it * 0.5f },
            "a sine" to FloatArray(120) { sin(it * 0.11f) * 300f },
            "a step" to FloatArray(120) { if (it < 40) 0f else 1f },
            "an alternating pair" to FloatArray(120) { if (it % 2 == 0) -3f else 6f },
            "fewer samples than the window" to floatArrayOf(3f, -1f, 4.5f, 2f),
            "one sample" to floatArrayOf(7f),
            "empty" to FloatArray(0),
        )
        for ((name, values) in sequences) {
            for (level in levels) {
                val wholeRun = PenSmoothing.apply(values, level)
                val filter = PenSmoothing.Filter(level)
                for (i in values.indices) {
                    assertEquals(
                        wholeRun[i],
                        filter.push(values[i]),
                        1e-6f,
                        "$name at level $level, sample $i of ${values.size}",
                    )
                }
            }
        }
    }

    @Test
    fun thePenUpFlushRunsOnTowardsTheLiftPoint() {
        // A unit ramp: each step is one sample, so the tail's distance from the lift point is readable in samples.
        val values = FloatArray(200) { it.toFloat() }
        val lift = values.last()
        for (level in levels) {
            val n = PenSmoothing.windowLength(level)
            val filter = PenSmoothing.Filter(level)
            var lastOutput = 0f
            for (v in values) lastOutput = filter.push(v)
            val tail = filter.flush()
            assertEquals(tailLength(level), tail.size, "level $level, window $n: floor(4n / 15)")
            if (tail.isEmpty()) {
                // A window shorter than four taps has no pen-up tail: floor(4n / 15) is 0 there.
                continue
            }
            // The tail is a run-on, not the lift point repeated: each append slides the window on, so it starts short
            // and converges. A filter that re-seeded the window would snap straight to the lift point here.
            // The tail is a run-on, not the lift point repeated: a filter that re-seeded the window would return the
            // lift point for every value here.
            assertTrue(tail.any { abs(it - lift) > 0.01f }, "level $level: the tail is not the lift point repeated")
            assertTrue(abs(lift - tail.last()) <= abs(lift - lastOutput) + 0.01f, "level $level: the tail does not run away from the lift point")
        }
    }

    @Test
    fun theFlushOfAShortStrokeIsAsLongAsTheStroke() {
        // Two samples, any window: the tail cannot outrun the stroke it closes.
        val values = floatArrayOf(4f, 5f)
        for (level in levels) {
            val filter = PenSmoothing.Filter(level)
            for (v in values) filter.push(v)
            val tail = filter.flush()
            assertEquals(minOf(tailLength(level), values.size), tail.size, "level $level: the tail cannot outrun the stroke")
        }
        // A filter that has not been pushed has no lift point to flush.
        assertEquals(0, PenSmoothing.Filter(0.6f).flush().size)
    }

    @Test
    fun resetStartsTheNextStrokeFresh() {
        val first = FloatArray(80) { it * 2f - 20f }
        val second = floatArrayOf(5f, 4f, 3f, 1f)
        for (level in levels) {
            val used = PenSmoothing.Filter(level)
            for (v in first) used.push(v)
            used.flush()
            used.reset()
            val fresh = PenSmoothing.Filter(level)
            val wholeRun = PenSmoothing.apply(second, level)
            for (i in second.indices) {
                val fromReset = used.push(second[i])
                val fromFresh = fresh.push(second[i])
                assertEquals(wholeRun[i], fromReset, 1e-6f, "level $level, sample $i after a reset")
                assertEquals(fromFresh, fromReset, "level $level, sample $i: bit for bit, as a new filter")
            }
            val resetTail = used.flush()
            val freshTail = fresh.flush()
            assertEquals(freshTail.size, resetTail.size, "level $level: the tail after a reset")
            assertEquals(freshTail.toList(), resetTail.toList(), "level $level: the tail after a reset")

        }
    }

    @Test
    fun oneTapPassesThroughTheStatefulForm() {
        assertEquals(1, PenSmoothing.windowLength(0f))
        val filter = PenSmoothing.Filter(0f)
        val values = floatArrayOf(3f, -1f, 0.25f, 7f, 0f)
        for (i in values.indices) {
            assertEquals(values[i], filter.push(values[i]), 0f, "sample $i comes back unchanged")
        }
        // One tap passes every sample through, so the last push is already the lift point and the pen-up tail is
        // empty: there is nothing left to run on.
        assertEquals(0, filter.flush().size)
    }

    @Test
    fun aLongStrokeDoesNotRescanItsRunPerSample() {
        // 420 Hz for about five seconds, at the longest window: 2000 pushes of fifty taps, which is 100 000
        // multiply-adds. A push that rescanned the run would do a thousand times that, and this bound would pass no
        // more.
        val values = FloatArray(2000) { sin(it * 0.011f) * 300f + 900f }
        repeat(3) {
            val warm = PenSmoothing.Filter(1f)
            for (v in values) warm.push(v)
            warm.flush()
        }
        val filter = PenSmoothing.Filter(1f)
        val start = System.nanoTime()
        for (v in values) filter.push(v)
        val ms = (System.nanoTime() - start) / 1_000_000.0
        assertTrue(ms < 50.0, "2000 samples at fifty taps took $ms ms")
    }
}
