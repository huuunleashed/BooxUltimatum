package app.booxultimatum.nib.engine

import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.doc.PackedPoints
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.render.Cap
import app.booxultimatum.nib.engine.render.RenderSink
import app.booxultimatum.nib.engine.render.Texture
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

const val BLACK: Int = -0x1000000
const val YELLOW: Int = -0x100

/** A stroke straight from the given points, bypassing smoothing. */
fun stroke(
    id: Long,
    kind: BrushKind,
    xy: FloatArray,
    pressure: Float = 1f,
    color: Int = BLACK,
    width: Float? = null,
    deltaMillis: Long = 3L,
    brush: BrushSpec? = null,
): Stroke {
    val b = PackedPoints.Builder(xy.size / 2)
    for (i in 0 until xy.size / 2) b.add(xy[2 * i], xy[2 * i + 1], pressure, 0f, 0f, if (i == 0) 0L else deltaMillis)
    val spec = brush ?: BrushSpec.defaults(kind).let { if (width != null) it.copy(width = width) else it }
    return Stroke(id, spec, color, b.build())
}

/** [n] evenly spaced points from (x0, y0) to (x1, y1). */
fun line(x0: Float, y0: Float, x1: Float, y1: Float, n: Int): FloatArray {
    val out = FloatArray(n * 2)
    for (i in 0 until n) {
        val t = if (n == 1) 0f else i.toFloat() / (n - 1)
        out[2 * i] = x0 + (x1 - x0) * t
        out[2 * i + 1] = y0 + (y1 - y0) * t
    }
    return out
}

/** A circle of [n] points. */
fun circle(cx: Float, cy: Float, r: Float, n: Int, turns: Float = 1f): FloatArray {
    val out = FloatArray(n * 2)
    for (i in 0 until n) {
        val a = 2.0 * PI * turns * i / (n - 1)
        out[2 * i] = (cx + r * cos(a)).toFloat()
        out[2 * i + 1] = (cy + r * sin(a)).toFloat()
    }
    return out
}

/** A random scribble inside [x0, x1) x [y0, y1) with random pressure and timing. */
fun scribble(
    rnd: Random,
    id: Long,
    kind: BrushKind,
    n: Int,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    width: Float? = null,
    color: Int = BLACK,
    step: Float = 3f,
): Stroke {
    val b = PackedPoints.Builder(n)
    var x = x0 + rnd.nextFloat() * (x1 - x0)
    var y = y0 + rnd.nextFloat() * (y1 - y0)
    var heading = rnd.nextFloat() * 6.283f
    var p = 0.3f + rnd.nextFloat() * 0.7f
    for (i in 0 until n) {
        b.add(x, y, p, rnd.nextFloat() * 1.2f, rnd.nextFloat() * 6f - 3f, if (i == 0) 0L else rnd.nextLong(1, 5))
        heading += (rnd.nextFloat() - 0.5f) * 1.2f
        val d = step * (0.3f + rnd.nextFloat())
        x = (x + cos(heading) * d).coerceIn(x0, x1)
        y = (y + sin(heading) * d).coerceIn(y0, y1)
        p = (p + (rnd.nextFloat() - 0.5f) * 0.2f).coerceIn(0.05f, 1f)
    }
    val spec = BrushSpec.defaults(kind).let { if (width != null) it.copy(width = width) else it }
    return Stroke(id, spec, color, b.build())
}

/** A sink that remembers every call, for checking what the renderer asks for. */
class RecordingSink : RenderSink {
    sealed class Call {
        data class Begin(val alpha: Float, val blend: Blend) : Call()
        data object End : Call()
        data class Path(val xy: FloatArray, val color: Int, val blend: Blend) : Call()
        data class Circle(val x: Float, val y: Float, val r: Float, val color: Int, val blend: Blend) : Call()
        data class Dab(
            val x: Float, val y: Float, val radius: Float, val angle: Float, val color: Int, val alpha: Float,
            val texture: Texture, val blend: Blend,
        ) : Call()
        data class Polyline(val xy: FloatArray, val width: Float, val color: Int, val cap: Cap, val dash: FloatArray?, val blend: Blend) : Call()
        data class Stipple(val x: Float, val y: Float, val radius: Float, val density: Float, val color: Int, val blend: Blend) : Call()
    }

    val calls = ArrayList<Call>()

    override fun beginGroup(alpha: Float, blend: Blend) {
        calls.add(Call.Begin(alpha, blend))
    }

    override fun endGroup() {
        calls.add(Call.End)
    }

    override fun fillPath(xy: FloatArray, count: Int, color: Int, blend: Blend) {
        calls.add(Call.Path(xy.copyOf(count * 2), color, blend))
    }

    override fun fillCircle(x: Float, y: Float, r: Float, color: Int, blend: Blend) {
        calls.add(Call.Circle(x, y, r, color, blend))
    }

    override fun dab(x: Float, y: Float, radius: Float, angle: Float, color: Int, alpha: Float, texture: Texture, blend: Blend) {
        calls.add(Call.Dab(x, y, radius, angle, color, alpha, texture, blend))
    }

    override fun strokePolyline(xy: FloatArray, count: Int, width: Float, color: Int, cap: Cap, dash: FloatArray?, blend: Blend) {
        calls.add(Call.Polyline(xy.copyOf(count * 2), width, color, cap, dash?.copyOf(), blend))
    }

    override fun stipple(x: Float, y: Float, radius: Float, density: Float, color: Int, blend: Blend) {
        calls.add(Call.Stipple(x, y, radius, density, color, blend))
    }
}

/** A sink that does nothing, for timing the renderer alone. */
class NullSink : RenderSink {
    var points = 0L

    override fun beginGroup(alpha: Float, blend: Blend) = Unit

    override fun endGroup() = Unit

    override fun fillPath(xy: FloatArray, count: Int, color: Int, blend: Blend) {
        points += count
    }

    override fun fillCircle(x: Float, y: Float, r: Float, color: Int, blend: Blend) {
        points++
    }

    override fun dab(x: Float, y: Float, radius: Float, angle: Float, color: Int, alpha: Float, texture: Texture, blend: Blend) {
        points++
    }

    override fun stipple(x: Float, y: Float, radius: Float, density: Float, color: Int, blend: Blend) {
        points++
    }

    override fun strokePolyline(xy: FloatArray, count: Int, width: Float, color: Int, cap: Cap, dash: FloatArray?, blend: Blend) {
        points += count
    }
}
