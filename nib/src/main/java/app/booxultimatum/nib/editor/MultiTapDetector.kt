package app.booxultimatum.nib.editor

import kotlin.math.hypot

/**
 * Recognises finger taps with several fingers: two fingers tapped together undo, three redo, as in Boox Notes. A tap
 * is every finger down and up within [maxDurationMs] without any of them travelling more than [slopPx]. Pure: the
 * view feeds it pointer counts and positions.
 */
class MultiTapDetector(private val maxDurationMs: Long = 300L, private val slopPx: Float = 24f) {
    enum class Result { None, Undo, Redo }

    private var startMs = 0L
    private var maxPointers = 0
    private var moved = false
    private var active = false
    private val downX = HashMap<Int, Float>()
    private val downY = HashMap<Int, Float>()

    /** The first finger touched. */
    fun start(timeMs: Long, id: Int, x: Float, y: Float) {
        active = true
        moved = false
        startMs = timeMs
        maxPointers = 1
        downX.clear()
        downY.clear()
        downX[id] = x
        downY[id] = y
    }

    /** Another finger touched; [count] is how many are down now. */
    fun pointerDown(id: Int, x: Float, y: Float, count: Int) {
        if (!active) return
        downX[id] = x
        downY[id] = y
        if (count > maxPointers) maxPointers = count
    }

    fun move(id: Int, x: Float, y: Float) {
        if (!active || moved) return
        val x0 = downX[id] ?: return
        val y0 = downY[id] ?: return
        if (hypot(x - x0, y - y0) > slopPx) moved = true
    }

    /** Something else took the gesture over (a pinch, the pen). */
    fun cancel() {
        active = false
    }

    /** The last finger lifted. */
    fun end(timeMs: Long): Result {
        if (!active) return Result.None
        active = false
        if (moved || timeMs - startMs > maxDurationMs) return Result.None
        return when (maxPointers) {
            2 -> Result.Undo
            3 -> Result.Redo
            else -> Result.None
        }
    }
}
