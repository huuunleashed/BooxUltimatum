package app.booxultimatum.nib.engine.render

import app.booxultimatum.nib.engine.geom.Affine
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * BOOX's 1-bit stipple: the ink of the display's charcoal v2 style, which Nib's pencil lays down so that its ink looks
 * like its preview. Measured on BOOX's own pen library with penlab (Note Air6 C, FW 4.3, `docs/09-ink.md` › *Measured
 * pens*):
 * - every pixel is fully on or off, never grey;
 * - a pixel is on where a fixed threshold at that pixel of the page, uniform and uncorrelated, is below the pressure,
 *   so the centre of a line covers just the pressure (all of it at full pressure), a lighter stroke's pixels are a
 *   subset of a darker one's at the same place, and overlapping stamps don't build up;
 * - each stamp is [diameter] across, solid out to [SOLID] of its radius, then fading linearly to nothing at its edge;
 * - tilt scales the whole stamp without making it lighter, and speed changes nothing.
 *
 * The dots are the sink's own pixels, so they stay one screen pixel at any zoom as the display draws them; the
 * threshold is fixed to the page at the sink's scale, so the grain stays put while the page moves.
 */
object Stipple {
    /** The display draws a stamp of `SIZE_SLOPE * w + SIZE_BASE` screen pixels for a width w it's sent (fitted, ±0.9 px). */
    const val SIZE_SLOPE = 1.16f
    const val SIZE_BASE = 5f

    /** The share of a stamp's radius that's solid before its edge fades. */
    const val SOLID = 0.6f

    private const val SALT = 23

    /**
     * The stamp's diameter in document pixels at [viewScale] (screen pixels per document pixel) for a stroke the
     * display is sent [sentPx] screen pixels wide: the display draws `SIZE_SLOPE * sentPx + SIZE_BASE` screen pixels.
     */
    fun diameter(sentPx: Float, viewScale: Float): Float {
        val z = if (viewScale > 0f && viewScale.isFinite()) viewScale else 1f
        return (SIZE_SLOPE * max(0f, sentPx) + SIZE_BASE) / z
    }

    /** How much of the pressure a pixel at [u] of the stamp's radius from its centre gets: 1 to [SOLID], then down to 0. */
    fun falloff(u: Float): Float = when {
        u <= SOLID -> 1f
        u >= 1f -> 0f
        else -> (1f - u) / (1f - SOLID)
    }

    /** The page's fixed threshold at cell ([gx], [gy]) of its pixel grid: uniform in [0, 1), the same every time. */
    fun threshold(gx: Int, gy: Int): Float = Hash.pixel(gx, gy, SALT)

    /** Receives the pixels a stamp turns on. */
    fun interface Pixels {
        fun on(x: Int, y: Int)
    }

    /**
     * Lays one stamp centred at document ([x], [y]), [radius] document pixels across half, at [density] (the pressure,
     * 0..1), through [toDevice] (document to the sink's pixels), and reports each pixel it turns on within
     * [clipWidth] × [clipHeight]. The threshold grid is the page at the device scale: cell `floor(scale * document)`,
     * which for a tile (a scale and a whole-pixel offset) is the tile's pixel less its offset.
     */
    fun stamp(x: Float, y: Float, radius: Float, density: Float, toDevice: Affine, clipWidth: Int, clipHeight: Int, out: Pixels) {
        val inverse = toDevice.invert() ?: return
        stamp(x, y, radius, density, toDevice, inverse, clipWidth, clipHeight, out)
    }

    /** [stamp] with [toDocument], the inverse of [toDevice], worked out once by the caller for many stamps. */
    fun stamp(x: Float, y: Float, radius: Float, density: Float, toDevice: Affine, toDocument: Affine, clipWidth: Int, clipHeight: Int, out: Pixels) {
        if (!(radius > 0f) || !(density > 0f) || !x.isFinite() || !y.isFinite()) return
        val scale = toDevice.meanScale
        if (!(scale > 0f)) return
        val inverse = toDocument
        val cx = toDevice.mapX(x, y)
        val cy = toDevice.mapY(x, y)
        val r = radius * scale
        val x0 = max(0, floor(cx - r).toInt())
        val x1 = min(clipWidth, ceil(cx + r).toInt() + 1)
        val y0 = max(0, floor(cy - r).toInt())
        val y1 = min(clipHeight, ceil(cy + r).toInt() + 1)
        if (x1 <= x0 || y1 <= y0) return
        val d = min(1f, density)
        val r2 = r * r
        for (py in y0 until y1) {
            val dy = py + 0.5f - cy
            for (px in x0 until x1) {
                val dx = px + 0.5f - cx
                val d2 = dx * dx + dy * dy
                if (d2 >= r2) continue
                val level = d * falloff(sqrt(d2) / r)
                if (!(level > 0f)) continue
                val fx = px + 0.5f
                val fy = py + 0.5f
                val gx = floor(scale * inverse.mapX(fx, fy)).toInt()
                val gy = floor(scale * inverse.mapY(fx, fy)).toInt()
                if (threshold(gx, gy) < level) out.on(px, py)
            }
        }
    }

    /**
     * Lays [n] stamps of one stroke (centres [xs], [ys], radii [rs], densities [ds], in document pixels) as [stamp]
     * would one by one, but reports each pixel that turns on once, and doesn't work out again a pixel an earlier stamp
     * already turned on. A stroke's stamps overlap about seven deep, so that's most of the work and of the dots a
     * sink would otherwise draw several times over.
     */
    fun stamps(
        xs: FloatArray, ys: FloatArray, rs: FloatArray, ds: FloatArray, n: Int,
        toDevice: Affine, toDocument: Affine, clipWidth: Int, clipHeight: Int, out: Pixels,
    ) {
        val scale = toDevice.meanScale
        if (n <= 0 || !(scale > 0f)) return
        var bx0 = Int.MAX_VALUE
        var by0 = Int.MAX_VALUE
        var bx1 = Int.MIN_VALUE
        var by1 = Int.MIN_VALUE
        for (i in 0 until n) {
            if (!(rs[i] > 0f) || !(ds[i] > 0f) || !xs[i].isFinite() || !ys[i].isFinite()) continue
            val cx = toDevice.mapX(xs[i], ys[i])
            val cy = toDevice.mapY(xs[i], ys[i])
            val r = rs[i] * scale
            bx0 = min(bx0, floor(cx - r).toInt())
            by0 = min(by0, floor(cy - r).toInt())
            bx1 = max(bx1, ceil(cx + r).toInt() + 1)
            by1 = max(by1, ceil(cy + r).toInt() + 1)
        }
        bx0 = max(0, bx0)
        by0 = max(0, by0)
        bx1 = min(clipWidth, bx1)
        by1 = min(clipHeight, by1)
        if (bx1 <= bx0 || by1 <= by0) return
        val w = bx1 - bx0
        val h = by1 - by0
        if (w.toLong() * h > MAX_RUN_PIXELS) {
            for (i in 0 until n) stamp(xs[i], ys[i], rs[i], ds[i], toDevice, toDocument, clipWidth, clipHeight, out)
            return
        }
        val lit = java.util.BitSet(w * h)
        val inverse = toDocument
        for (i in 0 until n) {
            val radius = rs[i]
            val density = ds[i]
            if (!(radius > 0f) || !(density > 0f) || !xs[i].isFinite() || !ys[i].isFinite()) continue
            val cx = toDevice.mapX(xs[i], ys[i])
            val cy = toDevice.mapY(xs[i], ys[i])
            val r = radius * scale
            val x0 = max(bx0, floor(cx - r).toInt())
            val x1 = min(bx1, ceil(cx + r).toInt() + 1)
            val y0 = max(by0, floor(cy - r).toInt())
            val y1 = min(by1, ceil(cy + r).toInt() + 1)
            if (x1 <= x0 || y1 <= y0) continue
            val d = min(1f, density)
            val r2 = r * r
            for (py in y0 until y1) {
                val dy = py + 0.5f - cy
                val row = (py - by0) * w - bx0
                for (px in x0 until x1) {
                    if (lit.get(row + px)) continue
                    val dx = px + 0.5f - cx
                    val d2 = dx * dx + dy * dy
                    if (d2 >= r2) continue
                    val level = d * falloff(sqrt(d2) / r)
                    if (!(level > 0f)) continue
                    val fx = px + 0.5f
                    val fy = py + 0.5f
                    val gx = floor(scale * inverse.mapX(fx, fy)).toInt()
                    val gy = floor(scale * inverse.mapY(fx, fy)).toInt()
                    if (threshold(gx, gy) < level) {
                        lit.set(row + px)
                        out.on(px, py)
                    }
                }
            }
        }
    }

    /** The largest area [stamps] keeps track of; a run over more (a long stroke at a high zoom) goes stamp by stamp. */
    private const val MAX_RUN_PIXELS = 16L * 1024 * 1024
}
