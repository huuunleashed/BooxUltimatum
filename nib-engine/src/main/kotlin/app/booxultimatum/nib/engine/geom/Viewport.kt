package app.booxultimatum.nib.engine.geom

import kotlin.math.max
import kotlin.math.min

/**
 * Maps document pixels to view pixels: `view = doc * scale + offset`. Immutable; every change returns a new viewport.
 * [scale] is kept within [minScale]..[maxScale] by [zoomAround] and [fit].
 */
data class Viewport(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val minScale: Float = DEFAULT_MIN_SCALE,
    val maxScale: Float = DEFAULT_MAX_SCALE,
) {
    init {
        require(scale > 0f && scale.isFinite()) { "scale must be positive, was $scale" }
        require(minScale > 0f && maxScale >= minScale) { "invalid scale limits $minScale..$maxScale" }
    }

    fun toViewX(docX: Float): Float = docX * scale + offsetX

    fun toViewY(docY: Float): Float = docY * scale + offsetY

    fun toDocX(viewX: Float): Float = (viewX - offsetX) / scale

    fun toDocY(viewY: Float): Float = (viewY - offsetY) / scale

    fun toView(p: Vec): Vec = Vec(toViewX(p.x), toViewY(p.y))

    fun toDoc(p: Vec): Vec = Vec(toDocX(p.x), toDocY(p.y))

    fun docRectToView(b: Box): Box = Box(toViewX(b.left), toViewY(b.top), toViewX(b.right), toViewY(b.bottom))

    fun viewRectToDoc(b: Box): Box = Box(toDocX(b.left), toDocY(b.top), toDocX(b.right), toDocY(b.bottom))

    /** The document area visible in a view of [viewW] by [viewH] pixels. */
    fun visibleDocRect(viewW: Float, viewH: Float): Box = viewRectToDoc(Box(0f, 0f, viewW, viewH))

    /**
     * Zooms by [factor] keeping the document point under the focus (in view pixels) fixed on screen.
     * The resulting scale is clamped to [minScale]..[maxScale].
     */
    fun zoomAround(focusViewX: Float, focusViewY: Float, factor: Float): Viewport {
        if (!(factor > 0f) || !factor.isFinite()) return this
        val newScale = (scale * factor).coerceIn(minScale, maxScale)
        val docX = toDocX(focusViewX)
        val docY = toDocY(focusViewY)
        return copy(scale = newScale, offsetX = focusViewX - docX * newScale, offsetY = focusViewY - docY * newScale)
    }

    /** Moves the document by ([dx], [dy]) view pixels. */
    fun pan(dx: Float, dy: Float): Viewport = copy(offsetX = offsetX + dx, offsetY = offsetY + dy)

    /** Scales and centres a [docW] by [docH] document inside a [viewW] by [viewH] view, leaving [margin] view pixels. */
    fun fit(docW: Float, docH: Float, viewW: Float, viewH: Float, margin: Float = 0f): Viewport {
        if (!(docW > 0f) || !(docH > 0f)) return this
        val availW = max(1f, viewW - 2f * margin)
        val availH = max(1f, viewH - 2f * margin)
        val s = min(availW / docW, availH / docH).coerceIn(minScale, maxScale)
        return copy(scale = s, offsetX = (viewW - docW * s) * 0.5f, offsetY = (viewH - docH * s) * 0.5f)
    }

    /** The on-screen width of a line [docWidth] document pixels wide. */
    fun previewWidth(docWidth: Float): Float = docWidth * scale

    /** The same mapping as an affine transform, for a canvas matrix. */
    fun docToView(): Affine = Affine(scaleX = scale, transX = offsetX, scaleY = scale, transY = offsetY)

    companion object {
        const val DEFAULT_MIN_SCALE = 0.1f
        const val DEFAULT_MAX_SCALE = 16f
    }
}
