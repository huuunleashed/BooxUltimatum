package app.booxultimatum.nib.engine.index

import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.geom.Box
import app.booxultimatum.nib.engine.geom.Geometry
import kotlin.math.max
import kotlin.math.min

/** Exact hit tests on single strokes; [StrokeIndex] uses them after narrowing the candidates. */
object HitTest {
    /**
     * Everywhere [stroke] can be hit or can paint: its render bounds together with its points inflated by the
     * brush's largest radius. The index files strokes by this box.
     */
    fun reach(stroke: Stroke): Box {
        if (stroke.points.size == 0) return Box.EMPTY
        val pb = stroke.points.bounds
        val h = stroke.brush.maxRadius + 1f
        return Box(pb.left - h, pb.top - h, pb.right + h, pb.bottom + h).union(stroke.bounds)
    }
    /** The radius used for hit tests at point [i]: half the width times the pressure factor (speed, taper and nib ignored). */
    fun radiusAt(stroke: Stroke, i: Int): Float =
        stroke.brush.width * 0.5f * stroke.brush.curve.factor(stroke.points.pressure(i))

    /**
     * True when an eraser of [radius] dragged along the first [count] points of [path] touches [stroke]: the distance
     * from the eraser's path to the stroke's centreline is at most the eraser's radius plus the stroke's.
     */
    fun hitsEraser(stroke: Stroke, path: FloatArray, count: Int, radius: Float): Boolean {
        val pts = stroke.points
        if (count <= 0 || pts.size == 0) return false
        var pl = Float.POSITIVE_INFINITY
        var pt = Float.POSITIVE_INFINITY
        var pr = Float.NEGATIVE_INFINITY
        var pb = Float.NEGATIVE_INFINITY
        for (j in 0 until count) {
            val x = path[2 * j]
            val y = path[2 * j + 1]
            pl = min(pl, x)
            pr = max(pr, x)
            pt = min(pt, y)
            pb = max(pb, y)
        }
        val reach = radius + stroke.brush.maxRadius
        val sb = pts.bounds
        if (!overlaps(sb.left, sb.top, sb.right, sb.bottom, pl - reach, pt - reach, pr + reach, pb + reach)) return false
        val eraserSegments = max(1, count - 1)
        if (pts.size == 1) {
            val lim = radius + radiusAt(stroke, 0)
            return Geometry.distanceToPolyline(pts.x(0), pts.y(0), path, count) <= lim
        }
        var rPrev = radiusAt(stroke, 0)
        for (i in 0 until pts.size - 1) {
            val rNext = radiusAt(stroke, i + 1)
            val lim = radius + max(rPrev, rNext)
            rPrev = rNext
            val ax = pts.x(i)
            val ay = pts.y(i)
            val bx = pts.x(i + 1)
            val by = pts.y(i + 1)
            if (!overlaps(min(ax, bx), min(ay, by), max(ax, bx), max(ay, by), pl - lim, pt - lim, pr + lim, pb + lim)) continue
            val lim2 = lim * lim
            for (j in 0 until eraserSegments) {
                val j1 = if (count == 1) 0 else j + 1
                val d = Geometry.segmentDistSq(
                    ax, ay, bx, by,
                    path[2 * j], path[2 * j + 1], path[2 * j1], path[2 * j1 + 1],
                )
                if (d <= lim2) return true
            }
        }
        return false
    }

    /** The share of [stroke]'s points inside the lasso polygon (even-odd rule), 0..1. */
    fun lassoFraction(stroke: Stroke, polygon: FloatArray, count: Int): Float {
        val pts = stroke.points
        if (pts.size == 0 || count < 3) return 0f
        val box = Box.of(polygon, count)
        val sb = pts.bounds
        if (!overlaps(sb.left, sb.top, sb.right, sb.bottom, box.left, box.top, box.right, box.bottom)) return 0f
        var inside = 0
        for (i in 0 until pts.size) {
            val x = pts.x(i)
            val y = pts.y(i)
            if (x >= box.left && x <= box.right && y >= box.top && y <= box.bottom && Geometry.pointInPolygon(x, y, polygon, count)) {
                inside++
            }
        }
        return inside.toFloat() / pts.size
    }

    /** True when at least [minFraction] of the stroke's points lie inside the lasso. */
    fun insideLasso(stroke: Stroke, polygon: FloatArray, count: Int, minFraction: Float = 0.5f): Boolean =
        lassoFraction(stroke, polygon, count) >= minFraction

    private fun overlaps(l1: Float, t1: Float, r1: Float, b1: Float, l2: Float, t2: Float, r2: Float, b2: Float): Boolean =
        l1 <= r2 && l2 <= r1 && t1 <= b2 && t2 <= b1
}
