package app.booxultimatum.nib.engine.doc

import app.booxultimatum.nib.engine.geom.Affine
import app.booxultimatum.nib.engine.geom.Box
import app.booxultimatum.nib.engine.input.InputSample
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * A stroke's samples, packed: x and y as floats on a 1/64 px grid (so files round-trip exactly), pressure in 16 bits,
 * tilt and orientation in a byte each, and the time since the previous point in milliseconds (saturating at 65535).
 * Immutable.
 */
class PackedPoints private constructor(
    private val xs: FloatArray,
    private val ys: FloatArray,
    private val pressures: ShortArray,
    private val tilts: ByteArray,
    private val orientations: ByteArray,
    private val dts: ShortArray,
) {
    val size: Int get() = xs.size

    fun x(i: Int): Float = xs[i]

    fun y(i: Int): Float = ys[i]

    /** Pressure 0..1. */
    fun pressure(i: Int): Float = (pressures[i].toInt() and 0xFFFF) / PRESSURE_MAX

    /** Tilt in radians, 0..PI/2. */
    fun tilt(i: Int): Float = (tilts[i].toInt() and 0xFF) * TILT_STEP

    /** Orientation in radians, -PI..PI. */
    fun orientation(i: Int): Float = orientations[i] * ORIENTATION_STEP

    /** Milliseconds since the previous point; 0 for the first. */
    fun deltaMillis(i: Int): Int = dts[i].toInt() and 0xFFFF

    /** The time from the first point to the last, in milliseconds. */
    val durationMillis: Long by lazy {
        var t = 0L
        for (i in 0 until size) t += deltaMillis(i)
        t
    }

    /** The bounding box of the sample positions (not inflated by any width; may be degenerate). */
    val bounds: Box by lazy {
        if (size == 0) {
            Box.EMPTY
        } else {
            var l = xs[0]
            var r = xs[0]
            var t = ys[0]
            var b = ys[0]
            for (i in 1 until size) {
                val x = xs[i]
                val y = ys[i]
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
            Box(l, t, r, b)
        }
    }

    /** The positions as an interleaved x, y array (a copy). */
    fun toXY(): FloatArray {
        val out = FloatArray(size * 2)
        for (i in 0 until size) {
            out[2 * i] = xs[i]
            out[2 * i + 1] = ys[i]
        }
        return out
    }

    /** The points mapped through [affine]; orientations turn with the transform, tilt and timing are kept. */
    fun transformed(affine: Affine): PackedPoints {
        val nx = FloatArray(size)
        val ny = FloatArray(size)
        val no = orientations.copyOf()
        val rotates = !(affine.skewX == 0f && affine.skewY == 0f && affine.scaleX == affine.scaleY && affine.scaleX > 0f)
        for (i in 0 until size) {
            nx[i] = quantize(affine.mapX(xs[i], ys[i]))
            ny[i] = quantize(affine.mapY(xs[i], ys[i]))
            if (rotates) {
                val o = orientation(i)
                val vx = cos(o)
                val vy = sin(o)
                val mx = affine.scaleX * vx + affine.skewX * vy
                val my = affine.skewY * vx + affine.scaleY * vy
                no[i] = encodeOrientation(atan2(my, mx))
            }
        }
        return PackedPoints(nx, ny, pressures, tilts, no, dts)
    }

    internal fun rawX(i: Int): Int = (xs[i] * GRID).roundToInt()

    internal fun rawY(i: Int): Int = (ys[i] * GRID).roundToInt()

    internal fun rawPressure(i: Int): Int = pressures[i].toInt() and 0xFFFF

    internal fun rawTilt(i: Int): Int = tilts[i].toInt() and 0xFF

    internal fun rawOrientation(i: Int): Int = orientations[i].toInt()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PackedPoints) return false
        return xs.contentEquals(other.xs) && ys.contentEquals(other.ys) &&
            pressures.contentEquals(other.pressures) && tilts.contentEquals(other.tilts) &&
            orientations.contentEquals(other.orientations) && dts.contentEquals(other.dts)
    }

    override fun hashCode(): Int {
        var h = xs.contentHashCode()
        h = 31 * h + ys.contentHashCode()
        h = 31 * h + pressures.contentHashCode()
        h = 31 * h + tilts.contentHashCode()
        h = 31 * h + orientations.contentHashCode()
        h = 31 * h + dts.contentHashCode()
        return h
    }

    override fun toString(): String = "PackedPoints(size=$size, bounds=$bounds)"

    /** Accumulates points without per-point allocation. */
    class Builder(capacity: Int = 16) {
        private var xs = FloatArray(maxOf(1, capacity))
        private var ys = FloatArray(maxOf(1, capacity))
        private var ps = ShortArray(maxOf(1, capacity))
        private var ts = ByteArray(maxOf(1, capacity))
        private var os = ByteArray(maxOf(1, capacity))
        private var ds = ShortArray(maxOf(1, capacity))

        var size: Int = 0
            private set

        /** Adds a point; non-finite values are replaced by 0, [deltaMillis] saturates at 65535. */
        fun add(x: Float, y: Float, pressure: Float, tilt: Float = 0f, orientation: Float = 0f, deltaMillis: Long = 0L): Builder {
            if (size == xs.size) grow()
            xs[size] = quantize(x)
            ys[size] = quantize(y)
            ps[size] = encodePressure(pressure)
            ts[size] = encodeTilt(tilt)
            os[size] = encodeOrientation(orientation)
            ds[size] = encodeDelta(deltaMillis)
            size++
            return this
        }

        fun build(): PackedPoints =
            PackedPoints(xs.copyOf(size), ys.copyOf(size), ps.copyOf(size), ts.copyOf(size), os.copyOf(size), ds.copyOf(size))

        private fun grow() {
            val n = xs.size * 2
            xs = xs.copyOf(n)
            ys = ys.copyOf(n)
            ps = ps.copyOf(n)
            ts = ts.copyOf(n)
            os = os.copyOf(n)
            ds = ds.copyOf(n)
        }
    }

    companion object {
        /** Positions are kept on a grid of this many steps per pixel. */
        const val GRID = 64f

        /** The largest coordinate magnitude kept; larger values are clamped. */
        const val MAX_COORD = 131072f

        private const val PRESSURE_MAX = 65535f
        private val TILT_STEP = (PI / 2 / 255).toFloat()
        private val ORIENTATION_STEP = (PI / 128).toFloat()

        val EMPTY: PackedPoints = Builder(1).build()

        /** Rounds [v] to the position grid (non-finite values become 0). */
        fun quantize(v: Float): Float {
            if (!v.isFinite()) return 0f
            val c = v.coerceIn(-MAX_COORD, MAX_COORD)
            return (c * GRID).roundToInt() / GRID
        }

        /** Packs [samples], taking each point's time delta from [InputSample.timeNanos]. */
        fun of(samples: List<InputSample>): PackedPoints {
            val b = Builder(samples.size)
            var prev = samples.firstOrNull()?.timeNanos ?: 0L
            for (s in samples) {
                b.add(s.x, s.y, s.pressure, s.tilt, s.orientation, ((s.timeNanos - prev) / 1_000_000.0).roundToLong())
                prev = s.timeNanos
            }
            return b.build()
        }

        internal fun encodePressure(p: Float): Short {
            val c = if (p.isNaN()) 0f else p.coerceIn(0f, 1f)
            return (c * PRESSURE_MAX).roundToInt().toShort()
        }

        internal fun encodeTilt(t: Float): Byte {
            val c = if (t.isNaN()) 0f else t
            return (c / TILT_STEP).roundToInt().coerceIn(0, 255).toByte()
        }

        internal fun encodeOrientation(o: Float): Byte {
            if (!o.isFinite()) return 0
            var steps = (o / ORIENTATION_STEP).roundToInt() % 256
            if (steps >= 128) steps -= 256
            if (steps < -128) steps += 256
            return steps.toByte()
        }

        internal fun encodeDelta(ms: Long): Short = ms.coerceIn(0L, 65535L).toInt().toShort()

        /** Builds points from their packed values, as stored in files. */
        internal fun fromRaw(
            xq: IntArray, yq: IntArray, pressure: IntArray, tilt: IntArray, orientation: IntArray, deltaMillis: IntArray,
        ): PackedPoints {
            val n = xq.size
            val xs = FloatArray(n) { (xq[it] / GRID).coerceIn(-MAX_COORD, MAX_COORD) }
            val ys = FloatArray(n) { (yq[it] / GRID).coerceIn(-MAX_COORD, MAX_COORD) }
            val ps = ShortArray(n) { pressure[it].coerceIn(0, 65535).toShort() }
            val ts = ByteArray(n) { tilt[it].coerceIn(0, 255).toByte() }
            val os = ByteArray(n) { orientation[it].coerceIn(-128, 127).toByte() }
            val ds = ShortArray(n) { deltaMillis[it].coerceIn(0, 65535).toShort() }
            return PackedPoints(xs, ys, ps, ts, os, ds)
        }
    }
}
