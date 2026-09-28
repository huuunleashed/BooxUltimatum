package app.booxultimatum.nib.pen

import android.graphics.RectF

/**
 * Where the floating controls lie over the canvas, in window pixels. The canvas treats these areas as not its own, so
 * the pen hovering or touching a card pauses the display's session exactly as the old toolbar did, while the rest of
 * the canvas around a pinned panel still draws. Written by the controls as they're laid out; main thread only.
 */
class PenShields {
    private val rects = LinkedHashMap<Any, RectF>()

    fun set(key: Any, left: Float, top: Float, right: Float, bottom: Float) {
        val r = rects.getOrPut(key) { RectF() }
        r.set(left, top, right, bottom)
    }

    fun remove(key: Any) {
        rects.remove(key)
    }

    /** Whether the window point lies on a control. */
    fun covers(x: Float, y: Float): Boolean = rects.values.any { it.contains(x, y) }

    /** The control under the window point, in window pixels, if any. */
    fun at(x: Float, y: Float): RectF? = rects.values.lastOrNull { it.contains(x, y) }

    val size: Int get() = rects.size
}