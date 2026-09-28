package app.booxultimatum.nib.engine.doc

import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.geom.Affine
import app.booxultimatum.nib.engine.geom.Box
import app.booxultimatum.nib.engine.render.StrokeRenderer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * One finished stroke: its brush, its ARGB colour and its points. [id] is unique within the document and never reused.
 */
data class Stroke(val id: Long, val brush: BrushSpec, val color: Int, val points: PackedPoints) {
    /** Everything this stroke can paint, in document pixels (computed once). */
    val bounds: Box by lazy(LazyThreadSafetyMode.PUBLICATION) { StrokeRenderer.bounds(this) }

    val size: Int get() = points.size

    /**
     * The stroke mapped through [affine]. The width scales by [Affine.meanScale] (not clamped to the brush's range)
     * and a nib's angle turns with the transform, so the stroke looks the same after moving, scaling or rotating.
     */
    fun transformed(affine: Affine): Stroke {
        val uniform = affine.skewX == 0f && affine.skewY == 0f && affine.scaleX == affine.scaleY && affine.scaleX > 0f
        val nib = if (uniform) {
            brush.nibAngle
        } else {
            val vx = cos(brush.nibAngle)
            val vy = -sin(brush.nibAngle)
            val mx = affine.scaleX * vx + affine.skewX * vy
            val my = affine.skewY * vx + affine.scaleY * vy
            atan2(-my, mx)
        }
        val scale = if (uniform) affine.scaleX else affine.meanScale
        return copy(brush = brush.copy(width = brush.width * scale, nibAngle = nib), points = points.transformed(affine))
    }
}
