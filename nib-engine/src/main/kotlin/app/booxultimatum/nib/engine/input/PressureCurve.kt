package app.booxultimatum.nib.engine.input

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

    companion object {
        /** Ignores pressure: always 1. */
        val CONSTANT = PressureCurve(1f, 1f, 1f)

        /** Factor equals pressure. */
        val LINEAR = PressureCurve(1f, 0f, 1f)

        /** Turns a raw digitiser reading into 0..1 (the Note Air6 C reports up to 4095). */
        fun normalize(raw: Float, max: Float): Float {
            if (!(max > 0f) || raw.isNaN()) return 0f
            return (raw / max).coerceIn(0f, 1f)
        }
    }
}
