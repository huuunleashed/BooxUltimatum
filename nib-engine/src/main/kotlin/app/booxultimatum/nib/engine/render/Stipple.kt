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
        if (!(radius > 0f) || !(density > 0f) || !x.isFinite() || !y.isFinite()) return
        val scale = toDevice.meanScale
        if (!(scale > 0f)) return
        val inverse = toDevice.invert() ?: return
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
}
