package app.booxultimatum.nib.engine.geom

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A 2x3 affine transform in Android `Matrix` order:
 * `x' = scaleX * x + skewX * y + transX`, `y' = skewY * x + scaleY * y + transY`.
 */
data class Affine(
    val scaleX: Float = 1f,
    val skewX: Float = 0f,
    val transX: Float = 0f,
    val skewY: Float = 0f,
    val scaleY: Float = 1f,
    val transY: Float = 0f,
) {
    fun mapX(x: Float, y: Float): Float = scaleX * x + skewX * y + transX

    fun mapY(x: Float, y: Float): Float = skewY * x + scaleY * y + transY

    fun map(p: Vec): Vec = Vec(mapX(p.x, p.y), mapY(p.x, p.y))

    /** Maps a direction, ignoring translation. */
    fun mapVector(v: Vec): Vec = Vec(scaleX * v.x + skewX * v.y, skewY * v.x + scaleY * v.y)

    /** Maps the first [count] points of an interleaved x, y array in place. */
    fun mapPoints(xy: FloatArray, count: Int = xy.size / 2) {
        for (i in 0 until count) {
            val x = xy[2 * i]
            val y = xy[2 * i + 1]
            xy[2 * i] = mapX(x, y)
            xy[2 * i + 1] = mapY(x, y)
        }
    }

    /** The bounding box of the mapped corners of [b]. */
    fun mapBox(b: Box): Box {
        if (b.isEmpty) return Box.EMPTY
        val xy = floatArrayOf(b.left, b.top, b.right, b.top, b.right, b.bottom, b.left, b.bottom)
        mapPoints(xy, 4)
        return Box.of(xy, 4)
    }

    /** The transform that applies [other] first and then this one. */
    fun compose(other: Affine): Affine = Affine(
        scaleX = scaleX * other.scaleX + skewX * other.skewY,
        skewX = scaleX * other.skewX + skewX * other.scaleY,
        transX = scaleX * other.transX + skewX * other.transY + transX,
        skewY = skewY * other.scaleX + scaleY * other.skewY,
        scaleY = skewY * other.skewX + scaleY * other.scaleY,
        transY = skewY * other.transX + scaleY * other.transY + transY,
    )

    /** The transform that applies this one first and then [next]. */
    fun then(next: Affine): Affine = next.compose(this)

    val determinant: Float get() = scaleX * scaleY - skewX * skewY

    /** How much the transform scales lengths on average: `sqrt(|det|)`. Used to scale brush widths. */
    val meanScale: Float get() = sqrt(abs(determinant))

    val isIdentity: Boolean get() = this == IDENTITY

    /** The inverse transform, or null when this one is singular. */
    fun invert(): Affine? {
        val det = determinant
        if (det == 0f || !det.isFinite()) return null
        val a = scaleY / det
        val c = -skewX / det
        val b = -skewY / det
        val d = scaleX / det
        return Affine(
            scaleX = a,
            skewX = c,
            transX = -(a * transX + c * transY),
            skewY = b,
            scaleY = d,
            transY = -(b * transX + d * transY),
        )
    }

    /** The nine values for Android's `Matrix.setValues`. */
    fun toMatrixValues(): FloatArray = floatArrayOf(scaleX, skewX, transX, skewY, scaleY, transY, 0f, 0f, 1f)

    companion object {
        val IDENTITY = Affine()

        fun translate(dx: Float, dy: Float): Affine = Affine(transX = dx, transY = dy)

        /** Scales by [sx], [sy] around the pivot. */
        fun scale(sx: Float, sy: Float = sx, pivotX: Float = 0f, pivotY: Float = 0f): Affine =
            Affine(scaleX = sx, transX = pivotX - sx * pivotX, scaleY = sy, transY = pivotY - sy * pivotY)

        /** Rotates by [radians] (clockwise on a y-down screen) around the pivot. */
        fun rotate(radians: Float, pivotX: Float = 0f, pivotY: Float = 0f): Affine {
            val c = cos(radians)
            val s = sin(radians)
            return Affine(
                scaleX = c,
                skewX = -s,
                transX = pivotX - c * pivotX + s * pivotY,
                skewY = s,
                scaleY = c,
                transY = pivotY - s * pivotX - c * pivotY,
            )
        }
    }
}
