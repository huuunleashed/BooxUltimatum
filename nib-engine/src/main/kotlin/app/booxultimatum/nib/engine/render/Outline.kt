package app.booxultimatum.nib.engine.render

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Builds the filled outline of a variable-width line as one polygon for the nonzero rule.
 *
 * The left offsets run forward and the right offsets run back, joined by caps, which makes a tube whose inside winds
 * the same way everywhere. At gentle turns each side gets one mitred vertex; at sharp turns the outer side gets a
 * round join and the inner side passes through the centre point, so there are no spikes. Where the line curls tighter
 * than its own radius, or the radius changes faster than the line moves, the inner offsets fold over; there a disc of
 * the point's radius is added, wound the same way and joined by a zero-width bridge, so the fill has no holes and each
 * pixel is still covered once. Reused between strokes.
 */
internal class OutlineBuilder {
    private var ux = FloatArray(256)
    private var uy = FloatArray(256)
    private var len = FloatArray(256)
    private val back = FloatBuf(64)

    /**
     * Writes the outline of the [n] points ([x], [y]) with radii [r] into [out] as interleaved x, y. [tolerance] is the
     * largest allowed gap between an arc and its chords, in the same units. [n] must be at least 2 and consecutive
     * points must differ.
     */
    fun build(x: FloatArray, y: FloatArray, r: FloatArray, n: Int, cap: Cap, tolerance: Float, out: FloatBuf) {
        out.clear()
        if (n < 2) return
        if (ux.size < n) {
            var c = ux.size
            while (c < n) c *= 2
            ux = FloatArray(c)
            uy = FloatArray(c)
            len = FloatArray(c)
        }
        for (i in 0 until n - 1) {
            val dx = x[i + 1] - x[i]
            val dy = y[i + 1] - y[i]
            val l = sqrt(dx * dx + dy * dy)
            len[i] = l
            if (l > 0f) {
                ux[i] = dx / l
                uy[i] = dy / l
            } else if (i > 0) {
                ux[i] = ux[i - 1]
                uy[i] = uy[i - 1]
            } else {
                ux[i] = 1f
                uy[i] = 0f
            }
        }
        val tol = max(1e-3f, tolerance)
        val last = n - 1

        offset(out, x[0], y[0], r[0], ux[0], uy[0], 1f)
        for (i in 1 until last) {
            join(out, x, y, r, i, 1f, tol)
            if (needsDisc(r, i)) disc(out, x[i], y[i], r[i], tol)
        }
        offset(out, x[last], y[last], r[last], ux[last - 1], uy[last - 1], 1f)
        cap(out, x[last], y[last], r[last], ux[last - 1], uy[last - 1], cap, tol)
        offset(out, x[last], y[last], r[last], ux[last - 1], uy[last - 1], -1f)
        for (i in last - 1 downTo 1) {
            back.clear()
            join(back, x, y, r, i, -1f, tol)
            val d = back.data
            var k = back.size - 2
            while (k >= 0) {
                out.add(d[k], d[k + 1])
                k -= 2
            }
        }
        offset(out, x[0], y[0], r[0], ux[0], uy[0], -1f)
        cap(out, x[0], y[0], r[0], -ux[0], -uy[0], cap, tol)
    }

    /** The outline of a single point: a disc, or a square turned to ([dirX], [dirY]) for square caps. */
    fun dot(px: Float, py: Float, radius: Float, cap: Cap, dirX: Float, dirY: Float, tolerance: Float, out: FloatBuf) {
        out.clear()
        if (cap == Cap.Square) {
            val ax = dirX * radius
            val ay = dirY * radius
            out.add(px + ax - ay, py + ay + ax)
            out.add(px + ax + ay, py + ay - ax)
            out.add(px - ax + ay, py - ay - ax)
            out.add(px - ax - ay, py - ay + ax)
        } else {
            out.add(px + radius, py)
            arc(out, px, py, radius, 1f, 0f, (-2 * PI).toFloat(), max(1e-3f, tolerance))
        }
    }

    private fun offset(out: FloatBuf, cx: Float, cy: Float, r: Float, dx: Float, dy: Float, side: Float) {
        out.add(cx - side * dy * r, cy + side * dx * r)
    }

    /** The join vertices at point [i] on [side], in forward order. */
    private fun join(out: FloatBuf, x: FloatArray, y: FloatArray, r: FloatArray, i: Int, side: Float, tol: Float) {
        val cx = x[i]
        val cy = y[i]
        val rr = r[i]
        val ax = -side * uy[i - 1]
        val ay = side * ux[i - 1]
        val bx = -side * uy[i]
        val by = side * ux[i]
        val cosTurn = (ux[i - 1] * ux[i] + uy[i - 1] * uy[i]).coerceIn(-1f, 1f)
        if (cosTurn > COS_GENTLE) {
            var mx = ax + bx
            var my = ay + by
            val ml = sqrt(mx * mx + my * my)
            if (ml > 0f) {
                mx /= ml
                my /= ml
            } else {
                mx = ax
                my = ay
            }
            val k = rr / max(0.5f, mx * ax + my * ay)
            out.add(cx + mx * k, cy + my * k)
            return
        }
        val cross = ux[i - 1] * uy[i] - uy[i - 1] * ux[i]
        out.add(cx + ax * rr, cy + ay * rr)
        if (side * cross < 0f) {
            arc(out, cx, cy, rr, ax, ay, -side * acos(cosTurn), tol)
        } else {
            out.add(cx, cy)
        }
        out.add(cx + bx * rr, cy + by * rr)
    }

    /** Whether the offsets fold over at [i], so the tube alone would leave a gap. */
    private fun needsDisc(r: FloatArray, i: Int): Boolean {
        val rr = r[i]
        val lPrev = len[i - 1]
        val lNext = len[i]
        if (rr - r[i - 1] > 0.5f * lPrev || rr - r[i + 1] > 0.5f * lNext) return true
        val cosTurn = (ux[i - 1] * ux[i] + uy[i - 1] * uy[i]).coerceIn(-1f, 1f)
        val sinHalf = sqrt((1f - cosTurn) * 0.5f)
        return minOf(lPrev, lNext) < 2.1f * rr * sinHalf
    }

    /** A full disc wound like the tube, reached from the current point by a bridge that is walked back afterwards. */
    private fun disc(out: FloatBuf, cx: Float, cy: Float, r: Float, tol: Float) {
        val fromX = out.data[out.size - 2]
        val fromY = out.data[out.size - 1]
        out.add(cx + r, cy)
        arc(out, cx, cy, r, 1f, 0f, (-2 * PI).toFloat(), tol)
        out.add(cx + r, cy)
        out.add(fromX, fromY)
    }

    private fun cap(out: FloatBuf, cx: Float, cy: Float, r: Float, dx: Float, dy: Float, cap: Cap, tol: Float) {
        when (cap) {
            Cap.Round -> arc(out, cx, cy, r, -dy, dx, (-PI).toFloat(), tol)
            Cap.Square -> {
                out.add(cx - dy * r + dx * r, cy + dx * r + dy * r)
                out.add(cx + dy * r + dx * r, cy - dx * r + dy * r)
            }
            Cap.Butt -> Unit
        }
    }

    companion object {
        /** Turns gentler than this (about 10 degrees) get a single mitred vertex per side. */
        private val COS_GENTLE = cos(10.0 * PI / 180.0).toFloat()

        /**
         * Adds the inside points of an arc of [radius] around ([cx], [cy]) that starts at unit vector ([ax], [ay]) and
         * turns by [sweep] radians; the end points are left to the caller.
         */
        fun arc(out: FloatBuf, cx: Float, cy: Float, radius: Float, ax: Float, ay: Float, sweep: Float, tol: Float) {
            val maxStep = if (tol >= radius) (PI / 2).toFloat() else 2f * acos(1f - tol / radius)
            val steps = ceil(kotlin.math.abs(sweep) / max(maxStep, 0.05f)).toInt().coerceIn(1, 128)
            if (steps <= 1) return
            val step = sweep / steps
            val cs = cos(step)
            val sn = sin(step)
            var vx = ax
            var vy = ay
            out.ensure(2 * steps)
            for (k in 1 until steps) {
                val nx = vx * cs - vy * sn
                val ny = vx * sn + vy * cs
                vx = nx
                vy = ny
                out.add(cx + vx * radius, cy + vy * radius)
            }
        }
    }
}
