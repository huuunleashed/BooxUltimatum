package app.booxultimatum.nib.ui.studio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How a slider maps its track to a value, snaps it, steps it with its − and + keys, and reads a typed value. Pure, so
 * every rule is tested on the JVM.
 *
 * @property log a logarithmic track: equal distances multiply the value, so thin widths get most of the travel.
 * @property step the snap step at a value (it can grow with the value).
 * @property display how a stored value is shown and typed: 100 for a fraction shown as a percentage.
 * @property decimals the most decimals a typed value keeps, in display units.
 */
class ValueScale(
    val min: Float,
    val max: Float,
    val log: Boolean = false,
    val display: Float = 1f,
    val decimals: Int = 2,
    private val step: (Float) -> Float,
) {
    init {
        require(max > min && min.isFinite() && max.isFinite()) { "bad range $min..$max" }
        require(!log || min > 0f) { "a log scale needs a positive minimum" }
    }

    fun clamp(v: Float): Float = if (v.isNaN()) min else v.coerceIn(min, max)

    /** Where [v] sits on the track, 0 to 1. */
    fun fraction(v: Float): Float {
        val c = clamp(v)
        return if (log) (ln(c / min) / ln(max / min)).coerceIn(0f, 1f) else ((c - min) / (max - min)).coerceIn(0f, 1f)
    }

    /** The snapped value at [fraction] of the track. */
    fun value(fraction: Float): Float {
        val f = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
        val raw = if (log) min * (max / min).pow(f) else min + (max - min) * f
        return snap(raw)
    }

    /** [v] on the step grid, held in range; both ends always stay reachable. */
    fun snap(v: Float): Float {
        val c = clamp(v)
        if (c <= min || c >= max) return c
        val s = step(c)
        if (!(s > 0f)) return c
        return clamp(tidy((c / s).roundToInt() * s))
    }

    /** One step up ([delta] > 0) or down from [v], onto the grid, held in range. */
    fun stepped(v: Float, delta: Int): Float {
        var x = clamp(v)
        repeat(abs(delta)) {
            x = if (delta > 0) next(x) else previous(x)
        }
        return x
    }

    private fun next(v: Float): Float {
        var s = snap(v)
        if (s <= v + EPS) s = tidy(s + step(s))
        return clamp(s)
    }

    private fun previous(v: Float): Float {
        var s = snap(v)
        if (s >= v - EPS) s = tidy(s - step((s - EPS).coerceAtLeast(min)))
        return clamp(s)
    }

    /** What was typed, read as a value: tolerant of a unit, a comma for the decimal point and spaces. */
    fun parse(text: String): Parsed {
        val cleaned = text.trim().replace(',', '.').filter { it.isDigit() || it == '.' || it == '-' }
        if (cleaned.isEmpty() || cleaned.count { it == '.' } > 1 || cleaned.lastIndexOf('-') > 0) return Parsed.Invalid
        val shown = cleaned.toFloatOrNull()?.takeIf { it.isFinite() } ?: return Parsed.Invalid
        val factor = 10f.pow(decimals)
        val rounded = (shown * factor).roundToInt() / factor
        val v = rounded / display
        val c = clamp(v)
        return Parsed.Ok(c, clamped = abs(c - v) > EPS / display)
    }

    /** [v] as it's shown and typed: "0.75", "3", "40". */
    fun format(v: Float): String = formatNumber(v * display, decimals)

    sealed interface Parsed {
        data class Ok(val value: Float, val clamped: Boolean) : Parsed
        data object Invalid : Parsed
    }

    companion object {
        private const val EPS = 1e-4f

        /** A number with at most [decimals] decimals and no trailing zeros, with a point whatever the locale. */
        fun formatNumber(v: Float, decimals: Int = 2): String {
            val factor = 10.0.pow(decimals)
            val r = Math.round(v * factor) / factor
            if (r == Math.rint(r)) return r.toLong().toString()
            return r.toBigDecimal().stripTrailingZeros().toPlainString()
        }

        private fun tidy(v: Float): Float = (v * 10_000f).roundToInt() / 10_000f

        /** The width steps: a quarter pixel at the thin end, where it shows, coarser as the line gets broad. */
        fun widthStep(w: Float): Float = when {
            w < 2f -> 0.25f
            w < 5f -> 0.5f
            w < 20f -> 1f
            w < 60f -> 2f
            w < 200f -> 5f
            else -> 10f
        }

        /** A brush width in document pixels, on a log track. */
        fun width(range: ClosedFloatingPointRange<Float>): ValueScale =
            ValueScale(range.start, range.endInclusive, log = true, step = ::widthStep)

        /** A fraction shown as a percentage, snapped to [stepPercent]. */
        fun percent(min: Float = 0f, max: Float = 1f, stepPercent: Float = 1f): ValueScale =
            ValueScale(min, max, display = 100f, decimals = 0, step = { stepPercent / 100f })

        fun linear(min: Float, max: Float, step: Float, decimals: Int = 2): ValueScale =
            ValueScale(min, max, decimals = decimals, step = { step })

        /** A multiplier around 1, on a log track so halving and doubling are the same distance. */
        fun factor(min: Float, max: Float, step: Float): ValueScale =
            ValueScale(min, max, log = true, step = { step })
    }
}