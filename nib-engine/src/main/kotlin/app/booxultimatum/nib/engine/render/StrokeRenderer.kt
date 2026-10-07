package app.booxultimatum.nib.engine.render

import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.geom.Box
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws finished strokes through a [RenderSink], deterministically: the same stroke always makes the same calls, so
 * re-rendering is pixel-identical. Thread-safe; scratch buffers are per thread, so nothing is allocated per point.
 */
object StrokeRenderer {
    /** The default largest gap between a curve and its chords, in document pixels. Pass `0.25 / viewScale` to match a zoom. */
    const val DEFAULT_TOLERANCE = 0.1f

    /** The outline brushes' extra margin for mitred vertices, and every brush's antialiasing margin. */
    private const val MITRE_SLACK = 1.01f
    private const val AA_MARGIN = 1f
    private const val SQRT2 = 1.4143f
    private const val OPAQUE_BLACK = -0x1000000
    private const val TWO_PI = (2 * PI).toFloat()

    /** How a brush puts ink down. */
    internal enum class Mode { Outline, Dabs, Stipple, Dashed, None }

    private class Scratch {
        val line = Centreline()
        val outline = OutlineBuilder()
        val poly = FloatBuf(2048)
        val xy = FloatBuf(1024)
        val dash = FloatArray(2)
    }

    private val scratch = ThreadLocal.withInitial { Scratch() }

    internal fun mode(kind: BrushKind): Mode = when {
        kind.rendersAsDabs -> Mode.Dabs
        kind.rendersAsStipple -> Mode.Stipple
        kind == BrushKind.Dash -> Mode.Dashed
        !kind.isRendered -> Mode.None
        else -> Mode.Outline
    }

    internal fun texture(kind: BrushKind): Texture = when (kind) {
        BrushKind.GrainPencil, BrushKind.Graphite -> Texture.Grain
        BrushKind.Charcoal, BrushKind.CharcoalV2 -> Texture.Charcoal
        BrushKind.Airbrush -> Texture.Soft
        else -> Texture.Solid
    }

    private fun cap(kind: BrushKind): Cap = if (kind == BrushKind.SquarePen) Cap.Square else Cap.Round

    /** Draws [stroke] into [sink]. Strokes that leave no ink (stroke and lasso erasers) draw nothing. */
    fun render(stroke: Stroke, sink: RenderSink, tolerance: Float = DEFAULT_TOLERANCE) {
        val brush = stroke.brush
        val mode = mode(brush.kind)
        if (mode == Mode.None || stroke.points.size == 0) return
        val s = scratch.get()
        val line = s.line
        line.build(stroke)
        if (line.n == 0) return

        val color = if (brush.blend == Blend.Erase) OPAQUE_BLACK else stroke.color
        val groupAlpha = brush.opacity.coerceIn(0f, 1f) * ((color ushr 24) / 255f)
        if (!(groupAlpha > 0f)) return
        val overlaps = mode != Mode.Outline && (brush.blend == Blend.Multiply || brush.blend == Blend.Atop)
        val grouped = groupAlpha < 1f || overlaps
        val drawColor = if (grouped) color or OPAQUE_BLACK else color
        val drawBlend = if (grouped) Blend.Normal else brush.blend
        if (grouped) sink.beginGroup(groupAlpha, brush.blend)
        when (mode) {
            Mode.Outline -> drawOutline(stroke, s, drawColor, drawBlend, tolerance, sink)
            Mode.Dabs -> drawDabs(stroke, line, drawColor, drawBlend, sink)
            Mode.Stipple -> drawStipple(line, drawColor, drawBlend, sink)
            Mode.Dashed -> drawDashed(stroke, s, drawColor, drawBlend, sink)
            Mode.None -> Unit
        }
        if (grouped) sink.endGroup()
    }

    /** Everything [stroke] can paint, in document pixels, including an antialiasing margin. */
    fun bounds(stroke: Stroke): Box {
        val brush = stroke.brush
        val mode = mode(brush.kind)
        if (stroke.points.size == 0) return Box.EMPTY
        if (mode == Mode.None) {
            val pb = stroke.points.bounds
            val h = brush.width * 0.5f + AA_MARGIN
            return Box(pb.left - h, pb.top - h, pb.right + h, pb.bottom + h)
        }
        val line = scratch.get().line
        line.build(stroke)
        val factor = when (mode) {
            Mode.Outline -> if (cap(brush.kind) == Cap.Square) SQRT2 else MITRE_SLACK
            Mode.Dabs -> (1f + brush.jitter.coerceAtLeast(0f)) * MITRE_SLACK
            else -> MITRE_SLACK
        }
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        val dashRadius = if (mode == Mode.Dashed) maxRadius(line) else 0f
        for (i in 0 until line.n) {
            val rad = (if (mode == Mode.Dashed) dashRadius else line.r[i]) * factor
            l = min(l, line.x[i] - rad)
            t = min(t, line.y[i] - rad)
            r = max(r, line.x[i] + rad)
            b = max(b, line.y[i] + rad)
        }
        return Box(l - AA_MARGIN, t - AA_MARGIN, r + AA_MARGIN, b + AA_MARGIN)
    }

    /**
     * The polygon an outline brush fills, as interleaved x, y (a copy). Empty for dab, dashed and non-rendering
     * brushes.
     */
    fun outline(stroke: Stroke, tolerance: Float = DEFAULT_TOLERANCE): FloatArray {
        if (mode(stroke.brush.kind) != Mode.Outline || stroke.points.size == 0) return FloatArray(0)
        val s = scratch.get()
        s.line.build(stroke)
        buildOutline(stroke, s, tolerance)
        return s.poly.toArray()
    }

    /** The centreline the stroke is drawn along, as x, y, radius triplets (a copy). For tests and tools. */
    internal fun centreline(stroke: Stroke): FloatArray {
        val line = scratch.get().line
        line.build(stroke)
        val out = FloatArray(line.n * 3)
        for (i in 0 until line.n) {
            out[3 * i] = line.x[i]
            out[3 * i + 1] = line.y[i]
            out[3 * i + 2] = line.r[i]
        }
        return out
    }

    private fun buildOutline(stroke: Stroke, s: Scratch, tolerance: Float) {
        val line = s.line
        val cap = cap(stroke.brush.kind)
        if (line.n == 1) {
            val a = stroke.brush.nibAngle
            s.outline.dot(line.x[0], line.y[0], line.r[0], cap, cos(a), -sin(a), tolerance, s.poly)
        } else {
            s.outline.build(line.x, line.y, line.r, line.n, cap, tolerance, s.poly)
        }
    }

    private fun drawOutline(stroke: Stroke, s: Scratch, color: Int, blend: Blend, tolerance: Float, sink: RenderSink) {
        val line = s.line
        if (line.n == 1 && cap(stroke.brush.kind) == Cap.Round) {
            sink.fillCircle(line.x[0], line.y[0], line.r[0], color, blend)
            return
        }
        buildOutline(stroke, s, tolerance)
        if (s.poly.points >= 3) sink.fillPath(s.poly.data, s.poly.points, color, blend)
    }

    private fun drawDabs(stroke: Stroke, line: Centreline, color: Int, blend: Blend, sink: RenderSink) {
        val brush = stroke.brush
        val texture = texture(brush.kind)
        val spacing = brush.spacing.coerceIn(0.02f, 4f)
        val jitter = brush.jitter.coerceAtLeast(0f)
        val grain = brush.grain.coerceIn(0f, 1f)
        val pressureFlow = brush.pressureFlow.coerceIn(0f, 1f)
        val flow = brush.flow.coerceIn(0f, 1f)
        val seed = stroke.id
        var index = 0

        fun dab(px: Float, py: Float, radius: Float, pressure: Float, shade: Float) {
            val jx = (Hash.unit(seed, index, 0) * 2f - 1f) * jitter * radius
            val jy = (Hash.unit(seed, index, 1) * 2f - 1f) * jitter * radius
            val angle = Hash.unit(seed, index, 2) * TWO_PI
            val vary = 1f - grain * 0.6f * Hash.unit(seed, index, 3)
            val alpha = flow * vary * (1f - pressureFlow + pressureFlow * pressure) * shade
            index++
            if (alpha > 0f) sink.dab(px + jx, py + jy, radius, angle, color, alpha, texture, blend)
        }

        val x = line.x
        val y = line.y
        val r = line.r
        val p = line.p
        val a = line.shade
        dab(x[0], y[0], r[0], p[0], a[0])
        if (line.n == 1) return
        var next = step(r[0], spacing)
        for (i in 0 until line.n - 1) {
            val segLen = Centreline.dist(x[i], y[i], x[i + 1], y[i + 1])
            var along = next
            while (along <= segLen) {
                val u = if (segLen > 0f) along / segLen else 0f
                val rr = r[i] + (r[i + 1] - r[i]) * u
                dab(x[i] + (x[i + 1] - x[i]) * u, y[i] + (y[i + 1] - y[i]) * u, rr, p[i] + (p[i + 1] - p[i]) * u, a[i] + (a[i + 1] - a[i]) * u)
                along += step(rr, spacing)
            }
            next = along - segLen
        }
    }

    private fun step(radius: Float, spacing: Float): Float = max(MIN_DAB_STEP, spacing * 2f * radius)

    private const val MIN_DAB_STEP = 0.25f

    /**
     * Stipple stamps along the line, close enough that their edges merge: overlapping stamps never build up (a pixel
     * is on where the page's threshold is below the strongest stamp over it), so the spacing only smooths the edge.
     */
    private fun drawStipple(line: Centreline, color: Int, blend: Blend, sink: RenderSink) {
        val x = line.x
        val y = line.y
        val r = line.r
        val p = line.p
        val a = line.shade
        fun stamp(px: Float, py: Float, radius: Float, pressure: Float, shade: Float) {
            val density = (pressure * shade).coerceIn(0f, 1f)
            if (density > 0f) sink.stipple(px, py, radius, density, color, blend)
        }
        stamp(x[0], y[0], r[0], p[0], a[0])
        if (line.n == 1) return
        var next = stippleStep(r[0])
        for (i in 0 until line.n - 1) {
            val segLen = Centreline.dist(x[i], y[i], x[i + 1], y[i + 1])
            var along = next
            while (along <= segLen) {
                val u = if (segLen > 0f) along / segLen else 0f
                val rr = r[i] + (r[i + 1] - r[i]) * u
                stamp(x[i] + (x[i + 1] - x[i]) * u, y[i] + (y[i + 1] - y[i]) * u, rr, p[i] + (p[i + 1] - p[i]) * u, a[i] + (a[i + 1] - a[i]) * u)
                along += stippleStep(rr)
            }
            next = along - segLen
        }
        val last = line.n - 1
        stamp(x[last], y[last], r[last], p[last], a[last])
    }

    private fun stippleStep(radius: Float): Float = (STIPPLE_STEP * 2f * radius).coerceIn(MIN_STIPPLE_STEP, MAX_STIPPLE_STEP)

    private const val STIPPLE_STEP = 0.15f
    private const val MIN_STIPPLE_STEP = 0.4f
    private const val MAX_STIPPLE_STEP = 2f

    private fun drawDashed(stroke: Stroke, s: Scratch, color: Int, blend: Blend, sink: RenderSink) {
        val line = s.line
        val radius = maxRadius(line)
        if (line.n == 1) {
            sink.fillCircle(line.x[0], line.y[0], radius, color, blend)
            return
        }
        val width = 2f * radius
        s.xy.clear()
        s.xy.ensure(line.n * 2)
        for (i in 0 until line.n) s.xy.add(line.x[i], line.y[i])
        s.dash[0] = max(0.1f, stroke.brush.dashOn * width)
        s.dash[1] = max(0.1f, stroke.brush.dashOff * width)
        sink.strokePolyline(s.xy.data, line.n, width, color, Cap.Butt, s.dash, blend)
    }

    private fun maxRadius(line: Centreline): Float {
        var m = 0f
        for (i in 0 until line.n) if (line.r[i] > m) m = line.r[i]
        return m
    }
}
