package app.booxultimatum.nib.engine.geom

import kotlin.math.max
import kotlin.math.min

/**
 * An axis-aligned float rectangle. [right] and [bottom] are exclusive for [contains].
 * A box is empty unless it has positive width and height; empty boxes are ignored by [union].
 */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    /** True unless the box has positive width and height (NaN edges count as empty). */
    val isEmpty: Boolean get() = !(right > left && bottom > top)

    /** The smallest box holding both; an empty operand is ignored. */
    fun union(o: Box): Box = when {
        isEmpty -> o
        o.isEmpty -> this
        else -> Box(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
    }

    /** The overlap of both boxes, or [EMPTY]. */
    fun intersect(o: Box): Box {
        val r = Box(max(left, o.left), max(top, o.top), min(right, o.right), min(bottom, o.bottom))
        return if (r.isEmpty) EMPTY else r
    }

    /** True when both are non-empty and overlap with positive area. */
    fun intersects(o: Box): Boolean =
        !isEmpty && !o.isEmpty && left < o.right && o.left < right && top < o.bottom && o.top < bottom

    /** Grows every edge outwards by [d] (shrinks for negative [d]); an empty box stays as it is. */
    fun inflate(d: Float): Box = if (isEmpty) this else Box(left - d, top - d, right + d, bottom + d)

    fun offset(dx: Float, dy: Float): Box = Box(left + dx, top + dy, right + dx, bottom + dy)

    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

    fun contains(p: Vec): Boolean = contains(p.x, p.y)

    /** True when [o] lies wholly inside this box. An empty [o] is contained by any non-empty box. */
    fun contains(o: Box): Boolean =
        !isEmpty && (o.isEmpty || (o.left >= left && o.top >= top && o.right <= right && o.bottom <= bottom))

    companion object {
        val EMPTY = Box(0f, 0f, 0f, 0f)

        /** The square of half-size [r] around ([x], [y]). */
        fun around(x: Float, y: Float, r: Float): Box = Box(x - r, y - r, x + r, y + r)

        /** The bounding box of the first [count] points of an interleaved x, y array (may be degenerate). */
        fun of(xy: FloatArray, count: Int = xy.size / 2): Box {
            if (count <= 0) return EMPTY
            var l = Float.POSITIVE_INFINITY
            var t = Float.POSITIVE_INFINITY
            var r = Float.NEGATIVE_INFINITY
            var b = Float.NEGATIVE_INFINITY
            for (i in 0 until count) {
                val x = xy[2 * i]
                val y = xy[2 * i + 1]
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
            return Box(l, t, r, b)
        }
    }
}
