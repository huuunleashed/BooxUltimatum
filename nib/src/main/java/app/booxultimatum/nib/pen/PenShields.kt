package app.booxultimatum.nib.pen

/**
 * Where the floating controls lie over the canvas, in window pixels, and which of them changed what they show. The
 * canvas treats these areas as not its own and hands all of them to the display as the preview's exclusions, so the
 * pen over a card neither draws on it nor stops the session; during a writing session the controls whose content
 * changed are pushed into the display's layer. Written by the controls as they're laid out ([set], [changed]); main
 * thread only.
 */
class PenShields {
    private class Shield(var left: Float, var top: Float, var right: Float, var bottom: Float) {
        fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
        fun screen(dx: Int, dy: Int) = intArrayOf(
            kotlin.math.floor(left).toInt() + dx, kotlin.math.floor(top).toInt() + dy,
            kotlin.math.ceil(right).toInt() + dx, kotlin.math.ceil(bottom).toInt() + dy,
        )
    }

    private val shields = LinkedHashMap<Any, Shield>()
    private val dirty = LinkedHashSet<Any>()

    /** Called when a control appears, moves, changes size or goes. */
    var onAreasChanged: (() -> Unit)? = null

    /** Called when a control's content changed ([changed]). */
    var onContentChanged: (() -> Unit)? = null

    fun set(key: Any, left: Float, top: Float, right: Float, bottom: Float) {
        val s = shields[key]
        if (s != null && s.left == left && s.top == top && s.right == right && s.bottom == bottom) return
        if (s == null) shields[key] = Shield(left, top, right, bottom) else { s.left = left; s.top = top; s.right = right; s.bottom = bottom }
        onAreasChanged?.invoke()
    }

    fun remove(key: Any) {
        dirty.remove(key)
        if (shields.remove(key) != null) onAreasChanged?.invoke()
    }

    /** What [key] shows changed, so its pixels on the display are out of date while frames are held. */
    fun changed(key: Any) {
        if (key !in shields) return
        if (dirty.add(key)) onContentChanged?.invoke()
    }

    val hasChanged: Boolean get() = dirty.isNotEmpty()

    /** The areas of the controls that changed since the last call, in window pixels (left, top, right, bottom), and forgets them. */
    fun takeChanged(): List<IntArray> {
        val out = dirty.mapNotNull { shields[it]?.screen(0, 0) }
        dirty.clear()
        return out
    }

    /** Whether the window point lies on a control. */
    fun covers(x: Float, y: Float): Boolean = shields.values.any { it.contains(x, y) }

    /** Every control's area on screen, for a window whose top-left corner is at ([dx], [dy]) on screen. */
    fun areas(dx: Int = 0, dy: Int = 0): List<IntArray> = shields.values.map { it.screen(dx, dy) }

    val size: Int get() = shields.size
}

/** Rectangles on screen as the display takes them: left, top, right, bottom. */
object ScreenAreas {
    /**
     * The strips of [window] outside [view] (both left, top, right, bottom), as up to four rectangles: the parts of the
     * screen around a drawing surface, which the preview must leave alone like any control.
     */
    fun around(view: IntArray, window: IntArray): List<IntArray> {
        val out = ArrayList<IntArray>(4)
        val wl = window[0]; val wt = window[1]; val wr = window[2]; val wb = window[3]
        val l = view[0].coerceIn(wl, wr); val t = view[1].coerceIn(wt, wb)
        val r = view[2].coerceIn(wl, wr); val b = view[3].coerceIn(wt, wb)
        if (t > wt) out += intArrayOf(wl, wt, wr, t)
        if (b < wb) out += intArrayOf(wl, b, wr, wb)
        if (l > wl) out += intArrayOf(wl, t, l, b)
        if (r < wr) out += intArrayOf(r, t, wr, b)
        return out
    }

    /** The smallest rectangle holding both, or the other when one is null. */
    fun union(a: IntArray?, b: IntArray?): IntArray? = when {
        a == null -> b?.copyOf()
        b == null -> a.copyOf()
        else -> intArrayOf(minOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[2], b[2]), maxOf(a[3], b[3]))
    }

    /** [r] grown by [by] on every side. */
    fun inflate(r: IntArray, by: Int): IntArray = intArrayOf(r[0] - by, r[1] - by, r[2] + by, r[3] + by)

    /** How many pixels [r] covers. */
    fun pixels(r: IntArray): Long = maxOf(0, r[2] - r[0]).toLong() * maxOf(0, r[3] - r[1]).toLong()
}
