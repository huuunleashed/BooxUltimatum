package app.booxultimatum.nib.engine.render

import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.doc.Stroke
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The line a stroke is drawn along: the stroke's points without near duplicates, with a radius per point (pressure,
 * speed, taper and nib applied), and extra points on long segments along a centripetal Catmull-Rom curve so fast
 * strokes stay round. Reused between strokes.
 */
internal class Centreline {
    var n = 0
        private set
    var x = FloatArray(256)
        private set
    var y = FloatArray(256)
        private set
    var r = FloatArray(256)
        private set
    var p = FloatArray(256)
        private set

    private var m = 0
    private var ix = FloatArray(256)
    private var iy = FloatArray(256)
    private var ir = FloatArray(256)
    private var ip = FloatArray(256)
    private var io = FloatArray(256)
    private var it = FloatArray(256)

    fun build(stroke: Stroke) {
        collect(stroke)
        radii(stroke)
        subdivide()
        if (usesNib(stroke.brush.kind)) applyNib(stroke)
    }

    private fun collect(stroke: Stroke) {
        val pts = stroke.points
        ensureInput(pts.size)
        m = 0
        var time = 0f
        for (i in 0 until pts.size) {
            time += pts.deltaMillis(i)
            val px = pts.x(i)
            val py = pts.y(i)
            val pp = pts.pressure(i)
            if (m > 0) {
                val dx = px - ix[m - 1]
                val dy = py - iy[m - 1]
                if (dx * dx + dy * dy < DUPLICATE * DUPLICATE) {
                    if (pp > ip[m - 1]) ip[m - 1] = pp
                    continue
                }
            }
            ix[m] = px
            iy[m] = py
            ip[m] = pp
            io[m] = pts.orientation(i)
            it[m] = time
            m++
        }
    }

    private fun radii(stroke: Stroke) {
        val brush = stroke.brush
        val half = brush.width * 0.5f
        for (k in 0 until m) ir[k] = half * brush.curve.factor(ip[k])
        if (brush.speedInfluence > 0f && m > 1) {
            var speed = 0f
            var accD = 0f
            var accT = 0f
            val influence = brush.speedInfluence.coerceIn(0f, 1f)
            for (k in 1 until m) {
                accD += dist(ix[k - 1], iy[k - 1], ix[k], iy[k])
                accT += it[k] - it[k - 1]
                if (accT >= 1f) {
                    speed += SPEED_SMOOTHING * (accD / accT - speed)
                    accD = 0f
                    accT = 0f
                }
                val u = (speed / REFERENCE_SPEED).coerceIn(0f, 1f)
                ir[k] *= 1f - influence * u * u * (3f - 2f * u)
            }
        }
        if (brush.taper > 0f && m > 1) {
            var total = 0f
            for (k in 1 until m) total += dist(ix[k - 1], iy[k - 1], ix[k], iy[k])
            val length = min(brush.taper * brush.width, total * 0.5f)
            if (length > 0f) {
                var s = 0f
                for (k in 0 until m) {
                    if (k > 0) s += dist(ix[k - 1], iy[k - 1], ix[k], iy[k])
                    val u = (min(s, total - s) / length).coerceIn(0f, 1f)
                    ir[k] *= TAPER_MIN + (1f - TAPER_MIN) * u * (2f - u)
                }
            }
        }
        for (k in 0 until m) if (!(ir[k] >= MIN_RADIUS)) ir[k] = MIN_RADIUS
    }

    private fun subdivide() {
        n = 0
        if (m == 0) return
        ensureOutput(m)
        if (m == 1) {
            put(ix[0], iy[0], ir[0], ip[0])
            return
        }
        for (k in 0 until m - 1) {
            put(ix[k], iy[k], ir[k], ip[k])
            val len = dist(ix[k], iy[k], ix[k + 1], iy[k + 1])
            if (len <= SUBDIVIDE) continue
            val pieces = min(MAX_PIECES, ceil(len / SUBDIVIDE).toInt())
            // Phantom end points mirror the neighbours, so the curve starts and ends straight.
            val x0 = if (k > 0) ix[k - 1] else 2f * ix[0] - ix[1]
            val y0 = if (k > 0) iy[k - 1] else 2f * iy[0] - iy[1]
            val x3 = if (k + 2 < m) ix[k + 2] else 2f * ix[k + 1] - ix[k]
            val y3 = if (k + 2 < m) iy[k + 2] else 2f * iy[k + 1] - iy[k]
            val t1 = sqrt(dist(x0, y0, ix[k], iy[k]))
            val t2 = t1 + sqrt(len)
            val t3 = t2 + sqrt(dist(ix[k + 1], iy[k + 1], x3, y3))
            val linear = !(t1 > 0f) || !(t3 > t2)
            ensureOutput(n + pieces + m)
            for (j in 1 until pieces) {
                val u = j.toFloat() / pieces
                var qx: Float
                var qy: Float
                if (linear) {
                    qx = ix[k] + (ix[k + 1] - ix[k]) * u
                    qy = iy[k] + (iy[k + 1] - iy[k]) * u
                } else {
                    val t = t1 + (t2 - t1) * u
                    qx = barryGoldman(x0, ix[k], ix[k + 1], x3, t1, t2, t3, t)
                    qy = barryGoldman(y0, iy[k], iy[k + 1], y3, t1, t2, t3, t)
                    if (!qx.isFinite() || !qy.isFinite()) {
                        qx = ix[k] + (ix[k + 1] - ix[k]) * u
                        qy = iy[k] + (iy[k + 1] - iy[k]) * u
                    }
                }
                put(qx, qy, ir[k] + (ir[k + 1] - ir[k]) * u, ip[k] + (ip[k + 1] - ip[k]) * u)
            }
        }
        put(ix[m - 1], iy[m - 1], ir[m - 1], ip[m - 1])
    }

    private fun applyNib(stroke: Stroke) {
        if (n < 2) return
        val brush = stroke.brush
        val fixedX = cos(brush.nibAngle)
        val fixedY = -sin(brush.nibAngle)
        val minRatio = brush.minRatio.coerceIn(0.01f, 1f)
        // Orientation is kept per input point; subdivided points take the nearest earlier input point's value.
        var input = 0
        for (i in 0 until n) {
            while (input + 1 < m && ix[input + 1] == x[i] && iy[input + 1] == y[i]) input++
            val a = max(0, i - 1)
            val b = min(n - 1, i + 1)
            var dx = x[b] - x[a]
            var dy = y[b] - y[a]
            val l = sqrt(dx * dx + dy * dy)
            if (l <= 0f) continue
            dx /= l
            dy /= l
            val nx: Float
            val ny: Float
            if (brush.nibFromOrientation) {
                nx = cos(io[input])
                ny = sin(io[input])
            } else {
                nx = fixedX
                ny = fixedY
            }
            r[i] = max(MIN_RADIUS, r[i] * max(minRatio, abs(dx * ny - dy * nx)))
        }
    }

    private fun put(px: Float, py: Float, pr: Float, pp: Float) {
        if (n == x.size) ensureOutput(n + 1)
        x[n] = px
        y[n] = py
        r[n] = pr
        p[n] = pp
        n++
    }

    private fun ensureInput(size: Int) {
        if (size <= ix.size) return
        val c = grow(ix.size, size)
        ix = ix.copyOf(c)
        iy = iy.copyOf(c)
        ir = ir.copyOf(c)
        ip = ip.copyOf(c)
        io = io.copyOf(c)
        it = it.copyOf(c)
    }

    private fun ensureOutput(size: Int) {
        if (size <= x.size) return
        val c = grow(x.size, size)
        x = x.copyOf(c)
        y = y.copyOf(c)
        r = r.copyOf(c)
        p = p.copyOf(c)
    }

    companion object {
        /** Points closer than this are merged, in document pixels. */
        const val DUPLICATE = 0.01f

        /** Segments longer than this get curve points in between. */
        const val SUBDIVIDE = 3f
        const val MAX_PIECES = 32
        const val MIN_RADIUS = 0.02f

        /** Speed, in document pixels per millisecond, at which speed thinning is complete. */
        const val REFERENCE_SPEED = 3f
        const val SPEED_SMOOTHING = 0.35f
        const val TAPER_MIN = 0.15f

        fun usesNib(kind: BrushKind): Boolean =
            kind == BrushKind.Calligraphy || kind == BrushKind.SquarePen || kind == BrushKind.Highlighter

        fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax
            val dy = by - ay
            return sqrt(dx * dx + dy * dy)
        }

        private fun grow(current: Int, needed: Int): Int {
            var c = maxOf(16, current)
            while (c < needed) c *= 2
            return c
        }

        /** Centripetal Catmull-Rom by the Barry-Goldman pyramid, with knots 0, t1, t2, t3, at [t] in t1..t2. */
        private fun barryGoldman(p0: Float, p1: Float, p2: Float, p3: Float, t1: Float, t2: Float, t3: Float, t: Float): Float {
            val a1 = ((t1 - t) * p0 + t * p1) / t1
            val a2 = ((t2 - t) * p1 + (t - t1) * p2) / (t2 - t1)
            val a3 = ((t3 - t) * p2 + (t - t2) * p3) / (t3 - t2)
            val b1 = ((t2 - t) * a1 + t * a2) / t2
            val b2 = ((t3 - t) * a2 + (t - t1) * a3) / (t3 - t1)
            return ((t2 - t) * b1 + (t - t1) * b2) / (t2 - t1)
        }
    }
}
