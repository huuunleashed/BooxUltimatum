package app.booxultimatum.nib.brush

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The widths the pen panel offers, in document pixels: fine steps at the thin end, where a quarter pixel shows, and
 * coarser ones as lines get broad. Each brush sees the steps inside its own range, plus the range's ends.
 */
object WidthSteps {
    val ALL: List<Float> = listOf(
        0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f, 4f, 5f, 6f, 8f, 10f, 12f, 16f, 20f, 28f, 40f,
        56f, 80f, 120f, 160f, 200f, 280f, 400f,
    )

    /** The steps inside [range], always including both ends. */
    fun forRange(range: ClosedFloatingPointRange<Float>): List<Float> =
        (ALL.filter { it in range } + range.start + range.endInclusive).distinct().sorted()

    /** The index of the step closest to [width]. */
    fun nearestIndex(steps: List<Float>, width: Float): Int {
        var best = 0
        for (i in steps.indices) if (abs(steps[i] - width) < abs(steps[best] - width)) best = i
        return best
    }

    /** The step [delta] places from the one closest to [width], held inside [range]. */
    fun step(range: ClosedFloatingPointRange<Float>, width: Float, delta: Int): Float {
        val steps = forRange(range)
        val i = (nearestIndex(steps, width) + delta).coerceIn(0, steps.lastIndex)
        return steps[i]
    }

    /** A width as people write it: 0.75, 2, 2.5. */
    fun label(width: Float): String {
        val hundredths = (width * 100f).roundToInt()
        if (hundredths % 100 == 0) return (hundredths / 100).toString()
        return (hundredths / 100f).toString()
    }
}
