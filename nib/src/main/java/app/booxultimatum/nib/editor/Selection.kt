package app.booxultimatum.nib.editor

import app.booxultimatum.nib.engine.geom.Affine
import app.booxultimatum.nib.engine.geom.Box
import app.booxultimatum.nib.engine.geom.Vec
import app.booxultimatum.nib.engine.geom.Viewport
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Strokes picked with the lasso on one layer. [box] is their bounds when picked; [frame] is every move, scale and turn
 * made since, so the handles turn with the strokes instead of growing an ever larger upright box.
 */
data class Selection(val layerId: Long, val ids: List<Long>, val box: Box, val frame: Affine = Affine.IDENTITY) {
    /** The frame's corners in document pixels: top left, top right, bottom right, bottom left, as x, y pairs. */
    fun corners(): FloatArray {
        val xy = floatArrayOf(box.left, box.top, box.right, box.top, box.right, box.bottom, box.left, box.bottom)
        frame.mapPoints(xy, 4)
        return xy
    }

    val centre: Vec get() = frame.map(Vec(box.centerX, box.centerY))

    /** The selection after [affine] was applied to its strokes. */
    fun transformed(affine: Affine): Selection = copy(frame = frame.then(affine))
}

/** What a touch on a selection grabs. */
sealed interface SelectionHandle {
    data class Corner(val index: Int) : SelectionHandle
    data object Rotate : SelectionHandle
    data object Inside : SelectionHandle
    data object Outside : SelectionHandle
}

/** The lasso's moves, scales and turns as affine transforms in document pixels. Pure. */
object SelectionMath {
    /** The smallest a selection can be scaled to in one drag. */
    const val MIN_SCALE = 0.05f

    fun move(dx: Float, dy: Float): Affine = Affine.translate(dx, dy)

    /**
     * Scales about the corner opposite [corner] (so that one stays put) so the dragged corner follows [pointer],
     * the same amount across and down: the drag is projected onto the diagonal. [corners] are the frame's corners.
     */
    fun scaleFromCorner(corners: FloatArray, corner: Int, start: Vec, pointer: Vec): Affine {
        val o = (corner + 2) % 4
        val ox = corners[2 * o]
        val oy = corners[2 * o + 1]
        val dx = start.x - ox
        val dy = start.y - oy
        val len2 = dx * dx + dy * dy
        if (len2 <= 1e-6f) return Affine.IDENTITY
        val t = ((pointer.x - ox) * dx + (pointer.y - oy) * dy) / len2
        val s = max(MIN_SCALE, t)
        return Affine.scale(s, s, ox, oy)
    }

    /**
     * Turns about [centre] by the angle the pointer swept from [start] to [pointer], snapped to a multiple of 45°
     * when within [snap] radians of one.
     */
    fun rotate(centre: Vec, start: Vec, pointer: Vec, snap: Float = SNAP): Affine {
        val a0 = atan2(start.y - centre.y, start.x - centre.x)
        val a1 = atan2(pointer.y - centre.y, pointer.x - centre.x)
        val raw = Viewport.normalize(a1 - a0)
        val eighth = (PI / 4).toFloat()
        val q = (raw / eighth).roundToInt() * eighth
        val angle = if (abs(raw - q) <= snap) q else raw
        return if (angle == 0f) Affine.IDENTITY else Affine.rotate(angle, centre.x, centre.y)
    }

    /**
     * What the view point ([x], [y]) grabs, given the frame's corners and rotate handle in view pixels and the
     * handles' touch radius.
     */
    fun hit(cornersView: FloatArray, rotateView: Vec, x: Float, y: Float, radius: Float): SelectionHandle {
        if (hypot(x - rotateView.x, y - rotateView.y) <= radius) return SelectionHandle.Rotate
        var best = -1
        var bestD = radius
        for (i in 0 until 4) {
            val d = hypot(x - cornersView[2 * i], y - cornersView[2 * i + 1])
            if (d <= bestD) {
                best = i
                bestD = d
            }
        }
        if (best >= 0) return SelectionHandle.Corner(best)
        return if (inside(cornersView, x, y)) SelectionHandle.Inside else SelectionHandle.Outside
    }

    /** Where the rotate handle sits: above the middle of the top edge, [lift] view pixels out, in view pixels. */
    fun rotateHandle(cornersView: FloatArray, lift: Float): Vec {
        val mx = (cornersView[0] + cornersView[2]) / 2f
        val my = (cornersView[1] + cornersView[3]) / 2f
        val cx = (cornersView[0] + cornersView[2] + cornersView[4] + cornersView[6]) / 4f
        val cy = (cornersView[1] + cornersView[3] + cornersView[5] + cornersView[7]) / 4f
        var nx = mx - cx
        var ny = my - cy
        val l = hypot(nx, ny)
        if (l < 1e-3f) {
            nx = 0f
            ny = -1f
        } else {
            nx /= l
            ny /= l
        }
        return Vec(mx + nx * lift, my + ny * lift)
    }

    /** Point in a convex or concave polygon of four corners (even-odd). */
    fun inside(poly: FloatArray, x: Float, y: Float): Boolean {
        var inside = false
        val n = poly.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[2 * i]
            val yi = poly[2 * i + 1]
            val xj = poly[2 * j]
            val yj = poly[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    private val SNAP = Math.toRadians(3.0).toFloat()
}