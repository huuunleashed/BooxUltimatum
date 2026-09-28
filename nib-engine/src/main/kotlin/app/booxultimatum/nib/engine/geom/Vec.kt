package app.booxultimatum.nib.engine.geom

import kotlin.math.sqrt

/** A point or a vector, in document or view pixels. */
data class Vec(val x: Float, val y: Float) {
    operator fun plus(o: Vec): Vec = Vec(x + o.x, y + o.y)

    operator fun minus(o: Vec): Vec = Vec(x - o.x, y - o.y)

    operator fun times(k: Float): Vec = Vec(x * k, y * k)

    operator fun unaryMinus(): Vec = Vec(-x, -y)

    infix fun dot(o: Vec): Float = x * o.x + y * o.y

    /** The z component of the 3D cross product: positive when [o] turns clockwise from this on a y-down screen. */
    infix fun cross(o: Vec): Float = x * o.y - y * o.x

    val length: Float get() = sqrt(x * x + y * y)

    fun distanceTo(o: Vec): Float {
        val dx = o.x - x
        val dy = o.y - y
        return sqrt(dx * dx + dy * dy)
    }

    /** This vector scaled to length 1, or [ZERO] when it has no length. */
    fun normalized(): Vec {
        val l = length
        return if (l > 0f) Vec(x / l, y / l) else ZERO
    }

    companion object {
        val ZERO = Vec(0f, 0f)
    }
}
