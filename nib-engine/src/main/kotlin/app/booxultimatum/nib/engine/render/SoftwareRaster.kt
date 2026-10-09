package app.booxultimatum.nib.engine.render

import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.geom.Affine
import app.booxultimatum.nib.engine.geom.Box
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A software [RenderSink] over a premultiplied float RGBA buffer that starts transparent. Used by the JVM tests and
 * for thumbnails. Pixel (x, y) covers document area [x, x+1) x [y, y+1) after [transform].
 *
 * Polygons and discs use 4x4 supersampled coverage with the nonzero rule; blends follow [Blend] with W3C maths;
 * groups render into their own buffer and composite once. Dab textures: [Texture.Grain] and [Texture.Charcoal] are
 * fixed to the pixel grid (like paper tooth) and ignore the dab's angle; [Texture.Soft] falls off smoothly to the rim.
 * Not thread-safe.
 */
class SoftwareRaster(val width: Int, val height: Int, var transform: Affine = Affine.IDENTITY) : RenderSink {
    init {
        require(width > 0 && height > 0) { "raster must have a size" }
    }

    private class Frame(val buffer: FloatArray, val alpha: Float, val blend: Blend) {
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = Int.MIN_VALUE
        var y1 = Int.MIN_VALUE

        fun mark(ax: Int, ay: Int, bx: Int, by: Int) {
            if (ax < x0) x0 = ax
            if (ay < y0) y0 = ay
            if (bx > x1) x1 = bx
            if (by > y1) y1 = by
        }

        val isEmpty: Boolean get() = x1 <= x0 || y1 <= y0
    }

    private val base = FloatArray(width * height * 4)
    private val frames = ArrayList<Frame>().apply { add(Frame(base, 1f, Blend.Normal)) }
    private val pool = ArrayList<FloatArray>()
    private val cov = IntArray(width)

    private var mapped = FloatArray(64)
    private var ex0 = FloatArray(64)
    private var ey0 = FloatArray(64)
    private var ey1 = FloatArray(64)
    private var slope = FloatArray(64)
    private var dir = IntArray(64)
    private var order = LongArray(64)
    private var active = IntArray(64)
    private var crossX = FloatArray(64)
    private var crossD = IntArray(64)

    private val outline = OutlineBuilder()
    private var stippleFrom: Affine? = null
    private var stippleInverse: Affine? = null
    private val piece = FloatBuf(64)
    private val poly = FloatBuf(256)
    private var px = FloatArray(64)
    private var py = FloatArray(64)
    private var pr = FloatArray(64)

    /** Open groups. */
    val depth: Int get() = frames.size - 1

    // region RenderSink

    override fun beginGroup(alpha: Float, blend: Blend) {
        val buffer = if (pool.isEmpty()) FloatArray(width * height * 4) else pool.removeAt(pool.size - 1)
        frames.add(Frame(buffer, alpha.coerceIn(0f, 1f), blend))
    }

    override fun endGroup() {
        if (frames.size <= 1) return
        val g = frames.removeAt(frames.size - 1)
        val parent = frames[frames.size - 1]
        if (!g.isEmpty) {
            val buf = g.buffer
            val a = g.alpha
            for (y in g.y0 until g.y1) {
                var o = (y * width + g.x0) * 4
                for (x in g.x0 until g.x1) {
                    val sa = buf[o + 3] * a
                    if (sa > 0f) put(parent.buffer, o, buf[o] * a, buf[o + 1] * a, buf[o + 2] * a, sa, g.blend)
                    buf[o] = 0f
                    buf[o + 1] = 0f
                    buf[o + 2] = 0f
                    buf[o + 3] = 0f
                    o += 4
                }
            }
            parent.mark(g.x0, g.y0, g.x1, g.y1)
        }
        pool.add(g.buffer)
    }

    override fun fillPath(xy: FloatArray, count: Int, color: Int, blend: Blend) {
        if (count < 3) return
        val pts = map(xy, count)
        fillPolygon(pts, count, color, 1f, blend)
    }

    override fun fillCircle(x: Float, y: Float, r: Float, color: Int, blend: Blend) {
        disc(transform.mapX(x, y), transform.mapY(x, y), r * scale(), color, 1f, Texture.Solid, blend)
    }

    override fun dab(x: Float, y: Float, radius: Float, angle: Float, color: Int, alpha: Float, texture: Texture, blend: Blend) {
        disc(transform.mapX(x, y), transform.mapY(x, y), radius * scale(), color, alpha, texture, blend)
    }

    override fun stipple(x: Float, y: Float, radius: Float, density: Float, color: Int, blend: Blend, dot: Float) {
        val ca = (color ushr 24) / 255f
        if (!(ca > 0f)) return
        val t = transform
        if (t != stippleFrom) {
            stippleInverse = t.invert()
            stippleFrom = t
        }
        val inverse = stippleInverse ?: return
        val frame = frames[frames.size - 1]
        val buf = frame.buffer
        val cr = ((color shr 16) and 0xFF) / 255f * ca
        val cg = ((color shr 8) and 0xFF) / 255f * ca
        val cb = (color and 0xFF) / 255f * ca
        var x0 = width
        var y0 = height
        var x1 = 0
        var y1 = 0
        val on = Stipple.Pixels { px, py ->
            put(buf, (py * width + px) * 4, cr, cg, cb, ca, blend)
            if (px < x0) x0 = px
            if (py < y0) y0 = py
            if (px >= x1) x1 = px + 1
            if (py >= y1) y1 = py + 1
        }
        val perDot = scale() * dot
        if (!(perDot > 0f) || kotlin.math.abs(perDot - 1f) < 1e-3f) {
            // The cells are the pixels: the display's dots.
            Stipple.stamp(x, y, radius, density, transform, inverse, width, height, on)
        } else {
            // The cells are the page's, scaled with it: each pixel takes the cell under its centre.
            val cx = transform.mapX(x, y)
            val cy = transform.mapY(x, y)
            val reach = (radius + dot) * scale() + 1f
            val z = 1f / dot
            for (py in maxOf(0, kotlin.math.floor(cy - reach).toInt()) until minOf(height, kotlin.math.ceil(cy + reach).toInt() + 1)) {
                for (px in maxOf(0, kotlin.math.floor(cx - reach).toInt()) until minOf(width, kotlin.math.ceil(cx + reach).toInt() + 1)) {
                    val fx = px + 0.5f
                    val fy = py + 0.5f
                    val gx = kotlin.math.floor(inverse.mapX(fx, fy) * z).toInt()
                    val gy = kotlin.math.floor(inverse.mapY(fx, fy) * z).toInt()
                    if (Stipple.cellOn(gx, gy, x * z, y * z, radius * z, density)) on.on(px, py)
                }
            }
        }
        if (x1 > x0 && y1 > y0) frame.mark(x0, y0, x1, y1)
    }

    override fun strokePolyline(xy: FloatArray, count: Int, width: Float, color: Int, cap: Cap, dash: FloatArray?, blend: Blend) {
        if (count <= 0) return
        val s = scale()
        val pts = map(xy, count)
        val radius = width * 0.5f * s
        if (!(radius > 0f)) return
        if (count == 1) {
            if (cap == Cap.Round) disc(pts[0], pts[1], radius, color, 1f, Texture.Solid, blend)
            return
        }
        val pattern = dash?.takeIf { d -> d.size >= 2 && d.all { it >= 0f } && d.sum() > 0f }
        piece.clear()
        if (pattern == null) {
            for (i in 0 until count) addPiecePoint(pts[2 * i], pts[2 * i + 1])
            flushPiece(radius, cap, color, blend)
            return
        }
        var pi = 0
        var remaining = pattern[0] * s
        var on = true
        addPiecePoint(pts[0], pts[1])
        for (i in 0 until count - 1) {
            val ax = pts[2 * i]
            val ay = pts[2 * i + 1]
            val bx = pts[2 * i + 2]
            val by = pts[2 * i + 3]
            val len = Centreline.dist(ax, ay, bx, by)
            var used = 0f
            while (len - used > remaining) {
                used += remaining
                val qx = ax + (bx - ax) * used / len
                val qy = ay + (by - ay) * used / len
                if (on) {
                    addPiecePoint(qx, qy)
                    flushPiece(radius, cap, color, blend)
                }
                piece.clear()
                on = !on
                pi = (pi + 1) % pattern.size
                remaining = pattern[pi] * s
                if (on) addPiecePoint(qx, qy)
            }
            remaining -= len - used
            if (on) addPiecePoint(bx, by)
        }
        if (on) flushPiece(radius, cap, color, blend)
    }

    // endregion

    // region Reading

    /** The pixel as straight (not premultiplied) ARGB; fully transparent pixels read 0x00FFFFFF. */
    fun pixel(x: Int, y: Int): Int {
        val o = (y * width + x) * 4
        val a = base[o + 3]
        if (a <= 0f) return 0x00FFFFFF
        val r = channel(base[o] / a)
        val g = channel(base[o + 1] / a)
        val b = channel(base[o + 2] / a)
        return (channel(a) shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** The pixel's alpha, 0..1. */
    fun alpha(x: Int, y: Int): Float = base[(y * width + x) * 4 + 3]

    /** How dark the pixel looks over [background] (opaque ARGB): 0 for the background's white, 1 for black. */
    fun darkness(x: Int, y: Int, background: Int = WHITE): Float {
        val o = (y * width + x) * 4
        val a = base[o + 3]
        val bgR = ((background shr 16) and 0xFF) / 255f
        val bgG = ((background shr 8) and 0xFF) / 255f
        val bgB = (background and 0xFF) / 255f
        val r = base[o] + bgR * (1f - a)
        val g = base[o + 1] + bgG * (1f - a)
        val b = base[o + 2] + bgB * (1f - a)
        return 1f - (0.2126f * r + 0.7152f * g + 0.0722f * b)
    }

    /** The mean alpha over [region] (the whole raster by default). */
    fun coverage(region: Box? = null): Float = mean(region) { x, y -> alpha(x, y) }

    /** The mean [darkness] over [region] (the whole raster by default). */
    fun meanDarkness(region: Box? = null, background: Int = WHITE): Float = mean(region) { x, y -> darkness(x, y, background) }

    /** How many pixels have alpha above [threshold]. */
    fun inkedPixels(threshold: Float = 0.5f): Int {
        var n = 0
        for (i in 0 until width * height) if (base[i * 4 + 3] > threshold) n++
        return n
    }

    /** The bounding box of pixels with alpha above [threshold], or [Box.EMPTY]. */
    fun inkBounds(threshold: Float = 0.02f): Box {
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = Int.MIN_VALUE
        var b = Int.MIN_VALUE
        for (y in 0 until height) for (x in 0 until width) {
            if (base[(y * width + x) * 4 + 3] > threshold) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
        }
        return if (r < l) Box.EMPTY else Box(l.toFloat(), t.toFloat(), (r + 1).toFloat(), (b + 1).toFloat())
    }

    /** Every pixel as straight ARGB, row by row. */
    fun toArgb(): IntArray = IntArray(width * height) { pixel(it % width, it / width) }

    /** Every pixel composited over opaque [background], as opaque ARGB. */
    fun composite(background: Int = WHITE): IntArray {
        val bgR = ((background shr 16) and 0xFF) / 255f
        val bgG = ((background shr 8) and 0xFF) / 255f
        val bgB = (background and 0xFF) / 255f
        return IntArray(width * height) {
            val o = it * 4
            val k = 1f - base[o + 3]
            val r = channel(base[o] + bgR * k)
            val g = channel(base[o + 1] + bgG * k)
            val b = channel(base[o + 2] + bgB * k)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** A binary PPM (P6) of the raster over [background], for looking at test output. */
    fun toPpm(background: Int = WHITE): ByteArray {
        val out = ByteArrayOutputStream(width * height * 3 + 32)
        out.write("P6\n$width $height\n255\n".toByteArray(Charsets.US_ASCII))
        for (c in composite(background)) {
            out.write((c shr 16) and 0xFF)
            out.write((c shr 8) and 0xFF)
            out.write(c and 0xFF)
        }
        return out.toByteArray()
    }

    /** True when both rasters hold exactly the same values. */
    fun contentEquals(other: SoftwareRaster): Boolean =
        width == other.width && height == other.height && base.contentEquals(other.base)

    /** Makes every pixel transparent again and drops open groups. */
    fun clear() {
        while (frames.size > 1) pool.add(frames.removeAt(frames.size - 1).buffer.also { it.fill(0f) })
        base.fill(0f)
    }

    // endregion

    // region Rasterising

    private fun scale(): Float = if (transform.isIdentity) 1f else transform.meanScale

    private fun map(xy: FloatArray, count: Int): FloatArray {
        if (transform.isIdentity) return xy
        if (mapped.size < count * 2) mapped = FloatArray(count * 2)
        for (i in 0 until count) {
            val x = xy[2 * i]
            val y = xy[2 * i + 1]
            mapped[2 * i] = transform.mapX(x, y)
            mapped[2 * i + 1] = transform.mapY(x, y)
        }
        return mapped
    }

    private fun addPiecePoint(x: Float, y: Float) {
        val n = piece.points
        if (n > 0) {
            val lx = piece.data[piece.size - 2]
            val ly = piece.data[piece.size - 1]
            if (Centreline.dist(lx, ly, x, y) < 1e-4f) return
        }
        piece.add(x, y)
    }

    private fun flushPiece(radius: Float, cap: Cap, color: Int, blend: Blend) {
        val n = piece.points
        if (n < 2) return
        if (px.size < n) {
            px = FloatArray(n * 2)
            py = FloatArray(n * 2)
            pr = FloatArray(n * 2)
        }
        for (i in 0 until n) {
            px[i] = piece.data[2 * i]
            py[i] = piece.data[2 * i + 1]
            pr[i] = radius
        }
        outline.build(px, py, pr, n, cap, TOLERANCE, poly)
        if (poly.points >= 3) fillPolygon(poly.data, poly.points, color, 1f, blend)
    }

    private fun ensureEdges(n: Int) {
        if (ex0.size >= n) return
        val c = max(n, ex0.size * 2)
        ex0 = FloatArray(c)
        ey0 = FloatArray(c)
        ey1 = FloatArray(c)
        slope = FloatArray(c)
        dir = IntArray(c)
        order = LongArray(c)
        active = IntArray(c)
        crossX = FloatArray(c)
        crossD = IntArray(c)
    }

    /** Nonzero fill of a polygon already in pixel coordinates, 4x4 supersampled. */
    private fun fillPolygon(pts: FloatArray, count: Int, color: Int, alpha: Float, blend: Blend) {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until count) {
            val x = pts[2 * i]
            val y = pts[2 * i + 1]
            if (!x.isFinite() || !y.isFinite()) return
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val x0 = max(0, floor(minX).toInt())
        val x1 = min(width, ceil(maxX).toInt() + 1)
        val y0 = max(0, floor(minY).toInt())
        val y1 = min(height, ceil(maxY).toInt() + 1)
        if (x1 <= x0 || y1 <= y0) return

        ensureEdges(count)
        var e = 0
        for (i in 0 until count) {
            val j = if (i + 1 == count) 0 else i + 1
            val ax = pts[2 * i]
            val ay = pts[2 * i + 1]
            val bx = pts[2 * j]
            val by = pts[2 * j + 1]
            if (ay == by) continue
            if (ay < by) {
                ex0[e] = ax
                ey0[e] = ay
                ey1[e] = by
                slope[e] = (bx - ax) / (by - ay)
                dir[e] = 1
            } else {
                ex0[e] = bx
                ey0[e] = by
                ey1[e] = ay
                slope[e] = (ax - bx) / (ay - by)
                dir[e] = -1
            }
            order[e] = (sortable(ey0[e]).toLong() shl 32) or e.toLong()
            e++
        }
        if (e == 0) return
        java.util.Arrays.sort(order, 0, e)

        val frame = frames[frames.size - 1]
        val buf = frame.buffer
        val ca = ((color ushr 24) / 255f) * alpha
        val cr = ((color shr 16) and 0xFF) / 255f
        val cg = ((color shr 8) and 0xFF) / 255f
        val cb = (color and 0xFF) / 255f
        var next = 0
        var activeCount = 0
        val subMin = x0 * 4
        val subMax = x1 * 4 - 1
        for (row in y0 until y1) {
            var touchedMin = Int.MAX_VALUE
            var touchedMax = Int.MIN_VALUE
            for (sub in 0 until 4) {
                val sy = row + (sub + 0.5f) * 0.25f
                while (next < e) {
                    val idx = (order[next] and 0xFFFFFFFFL).toInt()
                    if (ey0[idx] > sy) break
                    active[activeCount++] = idx
                    next++
                }
                var keep = 0
                for (k in 0 until activeCount) {
                    val idx = active[k]
                    if (ey1[idx] > sy) active[keep++] = idx
                }
                activeCount = keep
                if (activeCount < 2) continue
                for (k in 0 until activeCount) {
                    val idx = active[k]
                    val cx = ex0[idx] + (sy - ey0[idx]) * slope[idx]
                    val cd = dir[idx]
                    var m = k
                    while (m > 0 && crossX[m - 1] > cx) {
                        crossX[m] = crossX[m - 1]
                        crossD[m] = crossD[m - 1]
                        m--
                    }
                    crossX[m] = cx
                    crossD[m] = cd
                }
                var winding = 0
                for (k in 0 until activeCount - 1) {
                    winding += crossD[k]
                    if (winding == 0) continue
                    var j0 = ceil(crossX[k] * 4f - 0.5f).toInt()
                    var j1 = ceil(crossX[k + 1] * 4f - 0.5f).toInt() - 1
                    if (j0 < subMin) j0 = subMin
                    if (j1 > subMax) j1 = subMax
                    if (j1 < j0) continue
                    if (j0 < touchedMin) touchedMin = j0
                    if (j1 > touchedMax) touchedMax = j1
                    for (j in j0..j1) cov[j shr 2]++
                }
            }
            if (touchedMax < touchedMin) continue
            val pxMin = touchedMin shr 2
            val pxMax = touchedMax shr 2
            var o = (row * width + pxMin) * 4
            for (x in pxMin..pxMax) {
                val c = cov[x]
                if (c > 0) {
                    cov[x] = 0
                    val sa = ca * min(16, c) / 16f
                    put(buf, o, cr * sa, cg * sa, cb * sa, sa, blend)
                }
                o += 4
            }
            frame.mark(pxMin, row, pxMax + 1, row + 1)
        }
    }

    /** A disc or dab in pixel coordinates. */
    private fun disc(cx: Float, cy: Float, r: Float, color: Int, alpha: Float, texture: Texture, blend: Blend) {
        if (!(r > 0f) || !cx.isFinite() || !cy.isFinite()) return
        val x0 = max(0, floor(cx - r).toInt())
        val x1 = min(width, ceil(cx + r).toInt() + 1)
        val y0 = max(0, floor(cy - r).toInt())
        val y1 = min(height, ceil(cy + r).toInt() + 1)
        if (x1 <= x0 || y1 <= y0) return
        val frame = frames[frames.size - 1]
        val buf = frame.buffer
        val ca = ((color ushr 24) / 255f) * alpha.coerceIn(0f, 1f)
        if (!(ca > 0f)) return
        val cr = ((color shr 16) and 0xFF) / 255f
        val cg = ((color shr 8) and 0xFF) / 255f
        val cb = (color and 0xFF) / 255f
        val r2 = r * r
        val inner = if (r > HALF_DIAGONAL) (r - HALF_DIAGONAL) * (r - HALF_DIAGONAL) else -1f
        val outer = (r + HALF_DIAGONAL) * (r + HALF_DIAGONAL)
        for (y in y0 until y1) {
            var o = (y * width + x0) * 4
            for (x in x0 until x1) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val d2 = dx * dx + dy * dy
                var c: Float
                if (d2 >= outer) {
                    c = 0f
                } else if (texture == Texture.Soft) {
                    c = if (r > 2f) falloff(d2 / r2) else supersample(x, y, cx, cy, r2, soft = true)
                } else if (d2 <= inner) {
                    c = 1f
                } else {
                    c = supersample(x, y, cx, cy, r2, soft = false)
                }
                if (c > 0f) {
                    when (texture) {
                        Texture.Grain -> c *= 0.2f + 0.8f * Hash.pixel(x, y, 1)
                        Texture.Charcoal -> {
                            val h = Hash.pixel(x shr 1, y shr 1, 2)
                            c *= 0.05f + 0.95f * h * h
                        }
                        else -> Unit
                    }
                    val sa = ca * c
                    if (sa > 0f) put(buf, o, cr * sa, cg * sa, cb * sa, sa, blend)
                }
                o += 4
            }
        }
        frame.mark(x0, y0, x1, y1)
    }

    private fun supersample(x: Int, y: Int, cx: Float, cy: Float, r2: Float, soft: Boolean): Float {
        var sum = 0f
        for (sy in 0 until 4) {
            val dy = y + (sy + 0.5f) * 0.25f - cy
            for (sx in 0 until 4) {
                val dx = x + (sx + 0.5f) * 0.25f - cx
                val d2 = dx * dx + dy * dy
                if (d2 < r2) sum += if (soft) falloff(d2 / r2) else 1f
            }
        }
        return sum / 16f
    }

    private fun falloff(t: Float): Float {
        if (t >= 1f) return 0f
        val k = 1f - t
        return k * k
    }

    // endregion

    companion object {
        const val WHITE: Int = -1
        private const val TOLERANCE = 0.1f
        private val HALF_DIAGONAL = sqrt(0.5f)

        /** Composites premultiplied source (sr, sg, sb, sa) into [buf] at offset [o]. */
        private fun put(buf: FloatArray, o: Int, sr: Float, sg: Float, sb: Float, sa: Float, blend: Blend) {
            val dr = buf[o]
            val dg = buf[o + 1]
            val db = buf[o + 2]
            val da = buf[o + 3]
            when (blend) {
                Blend.Normal -> {
                    val k = 1f - sa
                    buf[o] = sr + dr * k
                    buf[o + 1] = sg + dg * k
                    buf[o + 2] = sb + db * k
                    buf[o + 3] = sa + da * k
                }
                Blend.Multiply -> {
                    val ks = 1f - sa
                    val kd = 1f - da
                    buf[o] = sr * kd + dr * ks + sr * dr
                    buf[o + 1] = sg * kd + dg * ks + sg * dg
                    buf[o + 2] = sb * kd + db * ks + sb * db
                    buf[o + 3] = sa + da - sa * da
                }
                Blend.Erase -> {
                    val k = 1f - sa
                    buf[o] = dr * k
                    buf[o + 1] = dg * k
                    buf[o + 2] = db * k
                    buf[o + 3] = da * k
                }
                Blend.Atop -> {
                    val k = 1f - sa
                    buf[o] = sr * da + dr * k
                    buf[o + 1] = sg * da + dg * k
                    buf[o + 2] = sb * da + db * k
                }
            }
        }

        private fun channel(v: Float): Int = (v * 255f).roundToInt().coerceIn(0, 255)

        /** Maps a float to an int with the same ordering, for sorting edges without boxing. */
        private fun sortable(v: Float): Int {
            val bits = java.lang.Float.floatToIntBits(v)
            return bits xor ((bits shr 31) and 0x7FFFFFFF)
        }
    }

    private inline fun mean(region: Box?, value: (Int, Int) -> Float): Float {
        val x0 = if (region == null) 0 else max(0, floor(region.left).toInt())
        val y0 = if (region == null) 0 else max(0, floor(region.top).toInt())
        val x1 = if (region == null) width else min(width, ceil(region.right).toInt())
        val y1 = if (region == null) height else min(height, ceil(region.bottom).toInt())
        if (x1 <= x0 || y1 <= y0) return 0f
        var sum = 0.0
        for (y in y0 until y1) for (x in x0 until x1) sum += value(x, y)
        return (sum / ((x1 - x0).toLong() * (y1 - y0))).toFloat()
    }
}
