package app.booxultimatum.nib.engine.input

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Maps pressure (0..1) to a width factor: `floor + (ceiling - floor) * pressure^exponent`, so the factor is [floor] at
 * no pressure and [ceiling] at full pressure. An exponent above 1 makes light pressure lighter.
 */
data class PressureCurve(val exponent: Float = 1f, val floor: Float = 0f, val ceiling: Float = 1f) {
    init {
        require(exponent > 0f && exponent.isFinite()) { "exponent must be positive, was $exponent" }
        require(floor >= 0f && ceiling >= 0f && floor.isFinite() && ceiling.isFinite()) {
            "floor and ceiling must be finite and non-negative"
        }
    }

    /** The width factor for [pressure]; out-of-range or NaN pressure is clamped to 0..1. */
    fun factor(pressure: Float): Float {
        val p = if (pressure.isNaN()) 0f else pressure.coerceIn(0f, 1f)
        if (floor == ceiling) return floor
        val shaped = if (exponent == 1f) p else p.pow(exponent)
        return floor + (ceiling - floor) * shaped
    }

    /** True when pressure has no effect. */
    val isConstant: Boolean get() = floor == ceiling

    /** The largest factor the curve can produce. */
    val maxFactor: Float get() = max(floor, ceiling)

    /**
     * The display fountain style's `pressureSensitivity` s, 0..1, that draws like this curve. BOOX's fountain pen draws
     * `pressure^(2s)` of its full width (see [ofSensitivity]), so for any curve s is
     * `ln(factor(0.5) / factor(1)) / (2 ln 0.5)`: the s that draws the same share of the full width at half pressure,
     * clamped to 0..1. That's exact for a curve without a floor ([exponent] / 2, so a [LINEAR] curve is s = 0.5), and
     * for one with a floor, as Nib's fountain pen had before 0.3, it matches at half pressure. 0 for a constant curve,
     * and for one that thickens as pressure falls.
     */
    val sensitivity: Float
        get() {
            val full = factor(1f)
            if (!(full > 0f) || floor >= ceiling) return 0f
            if (floor == 0f) return (exponent / 2f).coerceIn(0f, 1f)
            val s = ln(factor(0.5f) / full) / (2f * ln(0.5f))
            return if (s.isNaN()) 0f else s.coerceIn(0f, 1f)
        }

    companion object {
        /** Ignores pressure: always 1. */
        val CONSTANT = PressureCurve(1f, 1f, 1f)

        /** Factor equals pressure. */
        val LINEAR = PressureCurve(1f, 0f, 1f)

        /** The native fountain pen's default `pressureSensitivity` on the Note Air6 C. */
        const val NATIVE_FOUNTAIN_SENSITIVITY = 0.3f

        /**
         * BOOX's fountain pen's curve for a `pressureSensitivity` of [sensitivity] (clamped to 0..1):
         * `pressure^(2 * sensitivity)`, full width at full pressure and none at no pressure (the pen's
         * [app.booxultimatum.nib.engine.brush.BrushSpec.minWidth] keeps the line visible), and constant at 0. Measured
         * on the firmware's pen library, driven as BOOX Notes drives it, for sensitivities 0.15, 0.3, 0.5, 0.75 and 1
         * (Note Air6 C, FW 4.3). Its [sensitivity] is [sensitivity] again.
         */
        fun ofSensitivity(sensitivity: Float): PressureCurve {
            val s = if (sensitivity.isNaN()) 0f else sensitivity.coerceIn(0f, 1f)
            return if (s == 0f) CONSTANT else PressureCurve(2f * s, 0f, 1f)
        }

        /**
         * The Note Air6 C's pen pressure range (the display's `GET_MAX_TOUCH_PRESSURE`, 4096.0 on FW 4.3). BOOX's own
         * note app divides raw pressure by it before any pen uses it.
         */
        const val DEVICE_PRESSURE_MAX = 4096f

        /** Turns a raw digitiser reading into 0..1, dividing by [max] (see [DEVICE_PRESSURE_MAX]). */
        fun normalize(raw: Float, max: Float): Float {
            if (!(max > 0f) || raw.isNaN()) return 0f
            return (raw / max).coerceIn(0f, 1f)
        }
    }
}
