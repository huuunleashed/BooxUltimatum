package app.booxultimatum.nib.engine.geom

import kotlin.math.sqrt

/** Distance and containment helpers on interleaved x, y float arrays. */
object Geometry {
    /** Even-odd point-in-polygon test for the first [count] vertices of [poly]; the polygon closes itself. */
    fun pointInPolygon(x: Float, y: Float, poly: FloatArray, count: Int = poly.size / 2): Boolean {
        if (count < 3) return false
        var inside = false
        var j = count - 1
        for (i in 0 until count) {
            val xi = poly[2 * i]
            val yi = poly[2 * i + 1]
            val xj = poly[2 * j]
            val yj = poly[2 * j + 1]
            if ((yi > y) != (yj > y)) {
                val cross = xi + (y - yi) * (xj - xi) / (yj - yi)
                if (x < cross) inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Squared distance from P to the segment AB. */
    fun distSqToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = if (len2 > 0f) ((px - ax) * dx + (py - ay) * dy) / len2 else 0f
        if (t < 0f) t = 0f else if (t > 1f) t = 1f
        val cx = ax + t * dx - px
        val cy = ay + t * dy - py
        return cx * cx + cy * cy
    }

    fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float =
        sqrt(distSqToSegment(px, py, ax, ay, bx, by))

    /** Distance from P to the polyline of the first [count] points of [xy]; infinite for an empty polyline. */
    fun distanceToPolyline(px: Float, py: Float, xy: FloatArray, count: Int = xy.size / 2): Float {
        if (count <= 0) return Float.POSITIVE_INFINITY
        if (count == 1) return sqrt(sq(px - xy[0]) + sq(py - xy[1]))
        var best = Float.POSITIVE_INFINITY
        for (i in 0 until count - 1) {
            val d = distSqToSegment(px, py, xy[2 * i], xy[2 * i + 1], xy[2 * i + 2], xy[2 * i + 3])
            if (d < best) best = d
        }
        return sqrt(best)
    }

    /** True when segments AB and CD touch or cross. */
    fun segmentsIntersect(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
    ): Boolean {
        val d1 = orient(cx, cy, dx, dy, ax, ay)
        val d2 = orient(cx, cy, dx, dy, bx, by)
        val d3 = orient(ax, ay, bx, by, cx, cy)
        val d4 = orient(ax, ay, bx, by, dx, dy)
        if (((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) && ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))) return true
        return (d1 == 0f && onSegment(cx, cy, dx, dy, ax, ay)) ||
            (d2 == 0f && onSegment(cx, cy, dx, dy, bx, by)) ||
            (d3 == 0f && onSegment(ax, ay, bx, by, cx, cy)) ||
            (d4 == 0f && onSegment(ax, ay, bx, by, dx, dy))
    }

    /** Squared distance between segments AB and CD (zero when they cross). */
    fun segmentDistSq(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
    ): Float {
        if (segmentsIntersect(ax, ay, bx, by, cx, cy, dx, dy)) return 0f
        var best = distSqToSegment(ax, ay, cx, cy, dx, dy)
        best = minOf(best, distSqToSegment(bx, by, cx, cy, dx, dy))
        best = minOf(best, distSqToSegment(cx, cy, ax, ay, bx, by))
        best = minOf(best, distSqToSegment(dx, dy, ax, ay, bx, by))
        return best
    }

    /** The smallest distance between two polylines (a single point counts as a polyline). */
    fun polylineDistance(a: FloatArray, aCount: Int, b: FloatArray, bCount: Int): Float {
        if (aCount <= 0 || bCount <= 0) return Float.POSITIVE_INFINITY
        var best = Float.POSITIVE_INFINITY
        val aSegs = maxOf(1, aCount - 1)
        val bSegs = maxOf(1, bCount - 1)
        for (i in 0 until aSegs) {
            val i1 = if (aCount == 1) 0 else i + 1
            for (j in 0 until bSegs) {
                val j1 = if (bCount == 1) 0 else j + 1
                val d = segmentDistSq(
                    a[2 * i], a[2 * i + 1], a[2 * i1], a[2 * i1 + 1],
                    b[2 * j], b[2 * j + 1], b[2 * j1], b[2 * j1 + 1],
                )
                if (d < best) {
                    best = d
                    if (best == 0f) return 0f
                }
            }
        }
        return sqrt(best)
    }

    /** Signed area of the polygon (positive when clockwise on a y-down screen). */
    fun polygonArea(poly: FloatArray, count: Int = poly.size / 2): Float {
        if (count < 3) return 0f
        var sum = 0.0
        var j = count - 1
        for (i in 0 until count) {
            sum += poly[2 * j].toDouble() * poly[2 * i + 1] - poly[2 * i].toDouble() * poly[2 * j + 1]
            j = i
        }
        return (sum * 0.5).toFloat()
    }

    private fun sq(v: Float) = v * v

    private fun orient(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Float =
        (bx - ax) * (py - ay) - (by - ay) * (px - ax)

    private fun onSegment(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Boolean =
        px >= minOf(ax, bx) && px <= maxOf(ax, bx) && py >= minOf(ay, by) && py <= maxOf(ay, by)
}
