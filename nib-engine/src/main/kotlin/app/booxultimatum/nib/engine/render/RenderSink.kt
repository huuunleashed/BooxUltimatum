package app.booxultimatum.nib.engine.render

import app.booxultimatum.nib.engine.brush.Blend

/** A dab's texture. The sink decides how each looks; the renderer only chooses one. */
enum class Texture {
    /** A hard, antialiased disc. */
    Solid,

    /** Fine paper grain, fixed to the page, like graphite on paper. */
    Grain,

    /** Coarse, broken grain. */
    Charcoal,

    /** A disc that fades smoothly to its edge. */
    Soft,
}

/** Line ends for [RenderSink.strokePolyline]. */
enum class Cap { Round, Butt, Square }

/**
 * Where strokes are drawn: an Android `Canvas` in the app, a [SoftwareRaster] in tests and thumbnails.
 *
 * Coordinates are document pixels; the sink applies its own view transform. Arrays passed in are reused by the
 * caller, so a sink must not keep them after the call returns. Colours are ARGB; the colour's alpha multiplies
 * any other alpha. Blends follow [Blend].
 */
interface RenderSink {
    /**
     * Starts an isolated group, like `saveLayerAlpha`: what is drawn until [endGroup] lands in a transparent buffer
     * that is then composited once with [alpha] and [blend]. Groups nest.
     */
    fun beginGroup(alpha: Float, blend: Blend)

    fun endGroup()

    /** Fills one closed polygon (the first [count] points of [xy], interleaved x, y) with the nonzero rule. */
    fun fillPath(xy: FloatArray, count: Int, color: Int, blend: Blend)

    fun fillCircle(x: Float, y: Float, r: Float, color: Int, blend: Blend)

    /**
     * Stamps one textured dab of [radius] at ([x], [y]), rotated by [angle] radians, with [alpha] 0..1 on top of the
     * colour's own alpha.
     */
    fun dab(x: Float, y: Float, radius: Float, angle: Float, color: Int, alpha: Float, texture: Texture, blend: Blend)

    /**
     * Lays one stamp of BOOX's 1-bit stipple ([Stipple]) of [radius] at ([x], [y]), with [density] 0..1 (the pen's
     * pressure): pixels of the sink's own grid within the radius turn fully to [color] where the page's fixed threshold
     * is below the density at that distance from the centre. Never grey, never antialiased.
     */
    fun stipple(x: Float, y: Float, radius: Float, density: Float, color: Int, blend: Blend)

    /**
     * Lays [n] stipple stamps of one stroke at once (centres [xs], [ys], radii [rs], densities [ds]), as [stipple] does
     * one by one. A sink may draw each pixel only once however many stamps turn it on ([Stipple.stamps]).
     */
    fun stippleRun(xs: FloatArray, ys: FloatArray, rs: FloatArray, ds: FloatArray, n: Int, color: Int, blend: Blend) {
        for (i in 0 until n) stipple(xs[i], ys[i], rs[i], ds[i], color, blend)
    }

    /**
     * Strokes an open polyline [width] wide with round joins. [dash], when given, alternates on and off lengths in
     * document pixels, starting with on.
     */
    fun strokePolyline(xy: FloatArray, count: Int, width: Float, color: Int, cap: Cap, dash: FloatArray?, blend: Blend)
}
