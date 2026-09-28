package app.booxultimatum.nib.engine.input

/** What touched the screen. */
enum class Tool { Pen, Eraser, Finger }

/**
 * One pen or touch sample in document pixels.
 *
 * @property pressure normalised pressure, 0..1 (see [PressureCurve.normalize]).
 * @property tilt radians away from perpendicular to the screen, 0..PI/2.
 * @property orientation radians, -PI..PI, as Android's `AXIS_ORIENTATION`: 0 when the pen points to the top of the
 *   screen, positive clockwise.
 * @property timeNanos the event time in nanoseconds (any monotonic origin).
 */
data class InputSample(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    val tilt: Float = 0f,
    val orientation: Float = 0f,
    val timeNanos: Long = 0L,
)
