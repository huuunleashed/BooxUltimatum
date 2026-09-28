package app.booxultimatum.nib.engine.geom

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Maps document pixels to view pixels: `view = R(rotation) * (doc * scale) + offset`, where `R` turns clockwise on a
 * y-down screen by [rotation] radians. Immutable; every change returns a new viewport. [scale] is kept within
 * [minScale]..[maxScale] by [zoomAround] and [fit]; [rotation] is kept in (-PI, PI].
 */
data class Viewport(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val minScale: Float = DEFAULT_MIN_SCALE,
    val maxScale: Float = DEFAULT_MAX_SCALE,
    val rotation: Float = 0f,
) {
    init {
        require(scale > 0f && scale.isFinite()) { "scale must be positive, was $scale" }
        require(minScale > 0f && maxScale >= minScale) { "invalid scale limits $minScale..$maxScale" }
        require(rotation.isFinite()) { "rotation must be finite, was $rotation" }
    }

    // Quarter turns map exactly, so a page turned by 90 degrees keeps its pixels on the grid.
    private val cos: Float = exactCos(rotation)
    private val sin: Float = exactSin(rotation)

    /** True when the page isn't turned at all. */
    val isUpright: Boolean get() = rotation == 0f

    /** The rotation in whole degrees, 0 to 359, clockwise. */
    val rotationDegrees: Int get() = ((Math.toDegrees(rotation.toDouble()).roundToInt() % 360) + 360) % 360

    fun toViewX(docX: Float, docY: Float): Float = scale * (cos * docX - sin * docY) + offsetX

    fun toViewY(docX: Float, docY: Float): Float = scale * (sin * docX + cos * docY) + offsetY

    fun toDocX(viewX: Float, viewY: Float): Float = (cos * (viewX - offsetX) + sin * (viewY - offsetY)) / scale

    fun toDocY(viewX: Float, viewY: Float): Float = (-sin * (viewX - offsetX) + cos * (viewY - offsetY)) / scale

    fun toView(p: Vec): Vec = Vec(toViewX(p.x, p.y), toViewY(p.x, p.y))

    fun toDoc(p: Vec): Vec = Vec(toDocX(p.x, p.y), toDocY(p.x, p.y))

    /** The view box covering [b]: exact when upright, otherwise the bounds of its turned corners. */
    fun docRectToView(b: Box): Box {
        if (isUpright) return Box(toViewX(b.left, 0f), toViewY(0f, b.top), toViewX(b.right, 0f), toViewY(0f, b.bottom))
        val xy = floatArrayOf(b.left, b.top, b.right, b.top, b.right, b.bottom, b.left, b.bottom)
        for (i in 0 until 4) {
            val x = xy[2 * i]
            val y = xy[2 * i + 1]
            xy[2 * i] = toViewX(x, y)
            xy[2 * i + 1] = toViewY(x, y)
        }
        return Box.of(xy, 4)
    }

    /** The document box covering the view box [b]: exact when upright, otherwise the bounds of its corners. */
    fun viewRectToDoc(b: Box): Box {
        if (isUpright) return Box(toDocX(b.left, offsetY), toDocY(offsetX, b.top), toDocX(b.right, offsetY), toDocY(offsetX, b.bottom))
        val xy = floatArrayOf(b.left, b.top, b.right, b.top, b.right, b.bottom, b.left, b.bottom)
        for (i in 0 until 4) {
            val x = xy[2 * i]
            val y = xy[2 * i + 1]
            xy[2 * i] = toDocX(x, y)
            xy[2 * i + 1] = toDocY(x, y)
        }
        return Box.of(xy, 4)
    }

    /** The document area visible in a view of [viewW] by [viewH] pixels (its bounds, when the page is turned). */
    fun visibleDocRect(viewW: Float, viewH: Float): Box = viewRectToDoc(Box(0f, 0f, viewW, viewH))

    /**
     * Zooms by [factor] keeping the document point under the focus (in view pixels) fixed on screen.
     * The resulting scale is clamped to [minScale]..[maxScale].
     */
    fun zoomAround(focusViewX: Float, focusViewY: Float, factor: Float): Viewport {
        if (!(factor > 0f) || !factor.isFinite()) return this
        val newScale = (scale * factor).coerceIn(minScale, maxScale)
        return placed(focusViewX, focusViewY, newScale, rotation)
    }

    /**
     * Turns the page by [delta] radians (clockwise on screen) about the focus (in view pixels), which stays fixed
     * on screen.
     */
    fun rotateAround(focusViewX: Float, focusViewY: Float, delta: Float): Viewport {
        if (!delta.isFinite() || delta == 0f) return this
        return placed(focusViewX, focusViewY, scale, normalize(rotation + delta))
    }

    /** Turns the page to [angle] radians about the focus. */
    fun rotatedTo(focusViewX: Float, focusViewY: Float, angle: Float): Viewport {
        if (!angle.isFinite()) return this
        val target = normalize(angle)
        if (target == rotation) return this
        return placed(focusViewX, focusViewY, scale, target)
    }

    /** Moves the document by ([dx], [dy]) view pixels. */
    fun pan(dx: Float, dy: Float): Viewport = copy(offsetX = offsetX + dx, offsetY = offsetY + dy)

    /**
     * Upright again, scaled and centred so a [docW] by [docH] document sits inside a [viewW] by [viewH] view, leaving
     * [margin] view pixels.
     */
    fun fit(docW: Float, docH: Float, viewW: Float, viewH: Float, margin: Float = 0f): Viewport {
        if (!(docW > 0f) || !(docH > 0f)) return this
        val availW = max(1f, viewW - 2f * margin)
        val availH = max(1f, viewH - 2f * margin)
        val s = min(availW / docW, availH / docH).coerceIn(minScale, maxScale)
        return copy(scale = s, offsetX = (viewW - docW * s) * 0.5f, offsetY = (viewH - docH * s) * 0.5f, rotation = 0f)
    }

    /** The on-screen width of a line [docWidth] document pixels wide; turning the page doesn't change it. */
    fun previewWidth(docWidth: Float): Float = docWidth * scale

    /** The same mapping as an affine transform, for a canvas matrix. */
    fun docToView(): Affine = Affine(
        scaleX = scale * cos, skewX = -scale * sin, transX = offsetX,
        skewY = scale * sin, scaleY = scale * cos, transY = offsetY,
    )

    /** The mapping from view pixels back to document pixels. */
    fun viewToDoc(): Affine = Affine(
        scaleX = cos / scale, skewX = sin / scale, transX = -(cos * offsetX + sin * offsetY) / scale,
        skewY = -sin / scale, scaleY = cos / scale, transY = (sin * offsetX - cos * offsetY) / scale,
    )

    /**
     * The same view with the turn taken out: `docToView = turn() then unrotated().docToView`. Tiles are laid out upright
     * in this frame and the canvas is turned once, so they stay rendered in document space at every angle.
     */
    fun unrotated(): Viewport {
        if (isUpright) return this
        return copy(offsetX = cos * offsetX + sin * offsetY, offsetY = -sin * offsetX + cos * offsetY, rotation = 0f)
    }

    /** The turn alone, about the view's origin. */
    fun turn(): Affine = Affine(scaleX = cos, skewX = -sin, skewY = sin, scaleY = cos)

    private fun placed(focusViewX: Float, focusViewY: Float, newScale: Float, newRotation: Float): Viewport {
        val docX = toDocX(focusViewX, focusViewY)
        val docY = toDocY(focusViewX, focusViewY)
        val c = exactCos(newRotation)
        val s = exactSin(newRotation)
        return copy(
            scale = newScale,
            rotation = newRotation,
            offsetX = focusViewX - newScale * (c * docX - s * docY),
            offsetY = focusViewY - newScale * (s * docX + c * docY),
        )
    }

    companion object {
        const val DEFAULT_MIN_SCALE = 0.1f
        const val DEFAULT_MAX_SCALE = 16f

        private const val TWO_PI = (2 * PI).toFloat()
        private const val HALF_PI = (PI / 2).toFloat()

        /** [angle] brought into (-PI, PI]. */
        fun normalize(angle: Float): Float {
            if (!angle.isFinite()) return 0f
            var a = angle % TWO_PI
            if (a <= -PI.toFloat()) a += TWO_PI
            if (a > PI.toFloat()) a -= TWO_PI
            return if (abs(a) < 1e-7f) 0f else a
        }

        /** The nearest quarter turn to [angle] when within [tolerance] radians of it; otherwise [angle] itself. */
        fun snapToQuarter(angle: Float, tolerance: Float): Float {
            val q = Math.round(angle / HALF_PI).toFloat() * HALF_PI
            return if (abs(angle - q) <= tolerance) normalize(q) else normalize(angle)
        }

        private fun quarter(angle: Float): Int? {
            val q = angle / HALF_PI
            val r = Math.round(q)
            return if (abs(q - r) < 1e-6f) ((r % 4) + 4) % 4 else null
        }

        private fun exactCos(angle: Float): Float = when (quarter(angle)) {
            0 -> 1f
            1 -> 0f
            2 -> -1f
            3 -> 0f
            else -> cos(angle)
        }

        private fun exactSin(angle: Float): Float = when (quarter(angle)) {
            0 -> 0f
            1 -> 1f
            2 -> 0f
            3 -> -1f
            else -> sin(angle)
        }
    }
}