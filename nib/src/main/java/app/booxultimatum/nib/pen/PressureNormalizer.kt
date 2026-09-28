package app.booxultimatum.nib.pen

/**
 * Turns a MotionEvent's pressure into 0..1. Android usually hands out pressure already scaled to 0..1, and says so
 * in the device's motion range; a firmware that passes the digitiser's raw reading through reports its maximum there
 * instead. When neither is known, the display's own maximum (4096 on the Note Air6 C) is the fallback for readings
 * above 1.
 */
class PressureNormalizer(private val rangeMax: Float?, private val displayMax: Float?) {
    fun normalize(raw: Float): Float {
        if (raw.isNaN() || raw <= 0f) return 0f
        val max = when {
            rangeMax != null && rangeMax > 0f -> rangeMax
            raw > 1f && displayMax != null && displayMax > 0f -> displayMax
            else -> 1f
        }
        return (raw / max).coerceIn(0f, 1f)
    }
}
