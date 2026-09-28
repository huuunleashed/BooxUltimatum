package app.booxultimatum.nib.engine.input

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class PressureCurveTest {
    @Test
    fun endpoints() {
        for (curve in listOf(PressureCurve(0.5f, 0.2f, 1f), PressureCurve(1f, 0f, 1f), PressureCurve(2.5f, 0.3f, 0.9f))) {
            assertEquals(curve.floor, curve.factor(0f), 1e-6f)
            assertEquals(curve.ceiling, curve.factor(1f), 1e-6f)
        }
        assertEquals(0.25f, PressureCurve(2f, 0f, 1f).factor(0.5f), 1e-6f)
        assertEquals(0.6f, PressureCurve(1f, 0.2f, 1f).factor(0.5f), 1e-6f)
    }

    @Test
    fun monotonicAndClamped() {
        for (e in listOf(0.3f, 1f, 1.7f, 4f)) {
            val c = PressureCurve(e, 0.1f, 1.2f)
            var prev = c.factor(0f)
            for (i in 1..1000) {
                val f = c.factor(i / 1000f)
                assertTrue(f >= prev, "exponent $e not monotonic at $i")
                prev = f
            }
            assertEquals(c.floor, c.factor(-3f))
            assertEquals(c.ceiling, c.factor(7f))
            assertEquals(c.floor, c.factor(Float.NaN))
        }
    }

    @Test
    fun constantAndNormalize() {
        assertTrue(PressureCurve.CONSTANT.isConstant)
        assertEquals(1f, PressureCurve.CONSTANT.factor(0.1f))
        assertEquals(1f, PressureCurve.LINEAR.maxFactor)
        assertEquals(0.5f, PressureCurve.normalize(2047.5f, 4095f), 1e-6f)
        assertEquals(1f, PressureCurve.normalize(5000f, 4095f))
        assertEquals(0f, PressureCurve.normalize(-1f, 4095f))
        assertEquals(0f, PressureCurve.normalize(100f, 0f))
        assertEquals(0f, PressureCurve.normalize(Float.NaN, 4095f))
        assertFailsWith<IllegalArgumentException> { PressureCurve(0f, 0f, 1f) }
        assertFailsWith<IllegalArgumentException> { PressureCurve(1f, -1f, 1f) }
    }
}
