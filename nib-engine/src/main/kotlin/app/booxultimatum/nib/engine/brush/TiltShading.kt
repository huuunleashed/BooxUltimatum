package app.booxultimatum.nib.engine.brush

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How a tilted pen changes a line, for a brush whose [BrushSpec.tiltScale] is above 1. [code] is written to files and
 * never reused.
 */
enum class TiltResponse(val code: Int) {
    /**
     * Nib's first guess, in the 0.3.0 test builds: a pen held as for writing, up to [TiltShading.START] from upright,
     * draws as if upright; beyond, the line eases to [BrushSpec.tiltScale] times as broad at [TiltShading.FULL], and a
     * textured brush's ink grows lighter as it spreads. Only strokes drawn before brushes had a response use it, so
     * they look as they did.
     */
    Eased(0),

    /**
     * BOOX's charcoal, measured on the firmware's pen library as BOOX Notes drives it (Note Air6 C, FW 4.3): the line
     * is `1 + tiltScale * g(tilt)` times as broad, with g from [TiltShading.broadening], and it's as dark broad as
     * upright. Every brush drawn from format 1.2 uses it.
     */
    Native(1),
    ;

    companion object {
        /** The response written as [code]; one this version doesn't know reads as [Eased]. */
        fun fromCode(code: Int): TiltResponse = entries.firstOrNull { it.code == code } ?: Eased
    }
}

/**
 * How a tilted pen lays ink, like a charcoal stick or a pencil laid on its side, for a brush whose
 * [BrushSpec.tiltScale] is above 1; its [BrushSpec.tiltResponse] says which way.
 *
 * Tilt is the pen's angle from upright, 0..PI/2, as Android's `AXIS_TILT` (both of the pen's tilt axes combined: tilts
 * of 45 and 45 degrees are 60 degrees from upright).
 */
object TiltShading {
    /** [TiltResponse.Eased]: up to this tilt (30 degrees) a pen draws as if upright. */
    val START: Float = (PI / 6).toFloat()

    /** [TiltResponse.Eased]: at this tilt (60 degrees) and beyond the pen lays its broadest stroke. */
    val FULL: Float = (PI / 3).toFloat()

    /**
     * BOOX's charcoal broadening g at every 5 degrees from upright (0) to lying flat (90), measured with a tilt scale
     * of 3 on the Note Air6 C: 0 up to 10 degrees, then 0.067 at 20, 0.2 at 30, 0.51 at 45, 1.04 at 60, 1.49 at 70
     * and 2.02 at 80, about 2.3 flat. 15 and 85 degrees are interpolated. Close to `1 / cos(tilt) - 1` up to 60.
     */
    private val BROADENING = floatArrayOf(
        0f, 0f, 0f, 0.0335f, 0.067f, 0.133f, 0.2f, 0.33f, 0.4f, 0.51f,
        0.71f, 0.84f, 1.04f, 1.24f, 1.49f, 1.76f, 2.02f, 2.16f, 2.3f,
    )

    private const val DEGREES_PER_KNOT = 5f
    private val RADIANS_TO_DEGREES = (180.0 / PI).toFloat()

    /** The largest [broadening], for a pen lying flat. */
    val MAX_BROADENING: Float = BROADENING.last()

    /** [TiltResponse.Eased]: how far into the tilt range [tilt] is, 0 up to [START], easing to 1 at [FULL]. NaN reads as upright. */
    fun amount(tilt: Float): Float {
        if (!(tilt > START)) return 0f
        val u = ((tilt - START) / (FULL - START)).coerceAtMost(1f)
        return u * u * (3f - 2f * u)
    }

    /**
     * BOOX's charcoal broadening g at [tilt] (see [TiltResponse.Native]): linear between the measured angles, and flat
     * beyond 90 degrees. NaN and negative tilts read as upright.
     */
    fun broadening(tilt: Float): Float {
        if (!(tilt > 0f)) return 0f
        val x = min(tilt * RADIANS_TO_DEGREES, 90f) / DEGREES_PER_KNOT
        val i = x.toInt().coerceAtMost(BROADENING.size - 2)
        val f = x - i
        return BROADENING[i] + (BROADENING[i + 1] - BROADENING[i]) * f
    }

    /** The [TiltResponse.Eased] width factor at [tilt] for a tilt scale of [scale]: 1 when upright or when [scale] is 1 or less. */
    fun widthFactor(tilt: Float, scale: Float): Float {
        if (!(scale > 1f)) return 1f
        return 1f + (scale.coerceAtMost(BrushSpec.MAX_TILT_SCALE) - 1f) * amount(tilt)
    }

    /** The width factor at [tilt] for a tilt scale of [scale] and [response]: 1 when [scale] is 1 or less. */
    fun widthFactor(tilt: Float, scale: Float, response: TiltResponse): Float = when (response) {
        TiltResponse.Eased -> widthFactor(tilt, scale)
        TiltResponse.Native -> if (!(scale > 1f)) 1f else 1f + scale.coerceAtMost(BrushSpec.MAX_TILT_SCALE) * broadening(tilt)
    }

    /** The largest [widthFactor] a tilt scale of [scale] and [response] can reach. */
    fun maxWidthFactor(scale: Float, response: TiltResponse): Float {
        if (!(scale > 1f)) return 1f
        val s = scale.coerceAtMost(BrushSpec.MAX_TILT_SCALE)
        return when (response) {
            TiltResponse.Eased -> s
            TiltResponse.Native -> 1f + s * MAX_BROADENING
        }
    }

    /** The [TiltResponse.Eased] coverage factor that goes with [widthFactor]: ink spread over a broader line is lighter. */
    fun coverage(widthFactor: Float): Float = if (widthFactor > 1f) 1f / sqrt(widthFactor) else 1f

    /** The coverage factor that goes with [widthFactor] for [response]; BOOX's charcoal is as dark broad as upright. */
    fun coverage(widthFactor: Float, response: TiltResponse): Float = when (response) {
        TiltResponse.Eased -> coverage(widthFactor)
        TiltResponse.Native -> 1f
    }
}
