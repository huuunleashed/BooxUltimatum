package app.booxultimatum.nib.render

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.geom.Affine
import app.booxultimatum.nib.engine.render.Cap
import app.booxultimatum.nib.engine.render.RenderSink
import app.booxultimatum.nib.engine.render.Stipple
import app.booxultimatum.nib.engine.render.Texture
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The most cells a piece of a stipple stroke on the page's lattice spans each way (a piece's cells fit [Stipple]'s table). */
private const val STIPPLE_PIECE = 1024

/** Android's blend for an engine [Blend]. Multiply is the separable W3C one, so it paints over transparency too. */
fun Blend.toBlendMode(): BlendMode = when (this) {
    Blend.Normal -> BlendMode.SRC_OVER
    Blend.Multiply -> BlendMode.MULTIPLY
    Blend.Erase -> BlendMode.DST_OUT
    Blend.Atop -> BlendMode.SRC_ATOP
}

/**
 * The dab textures, made once per process from a fixed hash so they never change between runs: white pixels whose
 * alpha carries the grain. A colour filter turns them into ink of any colour. Read-only once made, so tile workers
 * and the main thread share them.
 */
object DabTextures {
    const val SIZE = 64

    /** Fine paper grain, one texel per document pixel. */
    val grain: Bitmap by lazy { noise { x, y -> 0.4f + 0.6f * hash(x, y, 1) } }

    /** Coarse, broken grain in 2 × 2 cells, mostly on or mostly off. */
    val charcoal: Bitmap by lazy {
        noise { x, y ->
            val h = hash(x shr 1, y shr 1, 2)
            if (h > 0.42f) 0.65f + 0.35f * hash(x, y, 3) else 0.12f * h
        }
    }

    /** A disc that fades smoothly to its rim. */
    val soft: Bitmap by lazy {
        noise { x, y ->
            val dx = (x + 0.5f) / SIZE * 2f - 1f
            val dy = (y + 0.5f) / SIZE * 2f - 1f
            val d = (1f - (dx * dx + dy * dy)).coerceIn(0f, 1f)
            d * d
        }
    }

    private inline fun noise(alpha: (Int, Int) -> Float): Bitmap {
        val px = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val a = (alpha(x, y).coerceIn(0f, 1f) * 255f).roundToInt()
            px[y * SIZE + x] = (a shl 24) or 0xFFFFFF
        }
        return Bitmap.createBitmap(px, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }

    private fun hash(x: Int, y: Int, salt: Int): Float {
        var z = (x.toLong() shl 32) xor (y.toLong() and 0xFFFFFFFFL) xor (salt.toLong() shl 58)
        z += -0x61C8864680B583EBL
        z = (z xor (z ushr 30)) * -0x40A7B892E31B1A47L
        z = (z xor (z ushr 27)) * -0x6B2FB644ECCEEE15L
        z = z xor (z ushr 31)
        return (z ushr 40).toFloat() / (1 shl 24).toFloat()
    }
}

/**
 * The engine's [RenderSink] on an Android [Canvas]. Coordinates are document pixels: the caller sets the canvas
 * matrix (document to tile, or document to view). Paths fill with the nonzero rule, groups are `saveLayer`s, and
 * blends map through [toBlendMode]. One instance per thread; [on] points it at a canvas.
 */
class CanvasSink : RenderSink {
    private lateinit var canvas: Canvas
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val dabPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val groupPaint = Paint()
    private val path = Path().apply { fillType = Path.FillType.WINDING }
    private val rect = RectF()
    private val shaderMatrix = Matrix()
    private val grain = BitmapShader(DabTextures.grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    private val charcoal = BitmapShader(DabTextures.charcoal, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    private val filters = object : LinkedHashMap<Int, ColorFilter>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ColorFilter>?): Boolean = size > 16
    }
    private val deviceMatrix = Matrix()
    private val deviceValues = FloatArray(9)
    private var toDevice = Affine.IDENTITY
    private var toDocument = Affine.IDENTITY
    private var stipplePoints = FloatArray(2048)
    private var stippleCount = 0
    private val stipplePaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
    }

    fun on(canvas: Canvas): CanvasSink {
        this.canvas = canvas
        return this
    }

    override fun beginGroup(alpha: Float, blend: Blend) {
        groupPaint.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        groupPaint.blendMode = blend.toBlendMode()
        canvas.saveLayer(null, groupPaint)
    }

    override fun endGroup() {
        canvas.restore()
    }

    override fun fillPath(xy: FloatArray, count: Int, color: Int, blend: Blend) {
        if (count < 3) return
        path.rewind()
        path.fillType = Path.FillType.WINDING
        path.moveTo(xy[0], xy[1])
        for (i in 1 until count) path.lineTo(xy[2 * i], xy[2 * i + 1])
        path.close()
        fill.color = color
        fill.blendMode = blend.toBlendMode()
        canvas.drawPath(path, fill)
    }

    override fun fillCircle(x: Float, y: Float, r: Float, color: Int, blend: Blend) {
        fill.color = color
        fill.blendMode = blend.toBlendMode()
        canvas.drawCircle(x, y, r, fill)
    }

    override fun dab(x: Float, y: Float, radius: Float, angle: Float, color: Int, alpha: Float, texture: Texture, blend: Blend) {
        val a = ((color ushr 24) * alpha.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        if (a == 0 || !(radius > 0f)) return
        when (texture) {
            Texture.Solid -> {
                fill.color = color
                fill.alpha = a
                fill.blendMode = blend.toBlendMode()
                canvas.drawCircle(x, y, radius, fill)
            }
            Texture.Grain -> textured(grain, x, y, radius, null, color, a, blend)
            Texture.Charcoal -> textured(charcoal, x, y, radius, angle, color, a, blend)
            Texture.Soft -> {
                dabPaint.shader = null
                dabPaint.colorFilter = filter(color)
                dabPaint.alpha = a
                dabPaint.blendMode = blend.toBlendMode()
                rect.set(x - radius, y - radius, x + radius, y + radius)
                canvas.drawBitmap(DabTextures.soft, null, rect, dabPaint)
            }
        }
    }

    // Grain stays fixed to the page; charcoal's coarse grain turns with each dab so it never lines up in rows.
    private fun textured(shader: BitmapShader, x: Float, y: Float, radius: Float, angle: Float?, color: Int, a: Int, blend: Blend) {
        if (angle != null) {
            shaderMatrix.setRotate(Math.toDegrees(angle.toDouble()).toFloat(), x, y)
            shader.setLocalMatrix(shaderMatrix)
        }
        dabPaint.shader = shader
        dabPaint.colorFilter = filter(color)
        dabPaint.alpha = a
        dabPaint.blendMode = blend.toBlendMode()
        canvas.drawCircle(x, y, radius, dabPaint)
        dabPaint.shader = null
    }

    /**
     * The stipple's dots are cells of the page's lattice, each [dot] document pixels across. Where a cell is one of the
     * canvas's own pixels (a canvas at the zoom the stroke was drawn at, which is the preview's dots exactly) the cells
     * are worked out on its pixels and each that turns on is drawn back as a square exactly one pixel wide, without
     * antialiasing: the display's own dots, never grey. At any other zoom they are worked out on the lattice and
     * scaled with the page ([latticeRun]).
     */
    override fun stipple(x: Float, y: Float, radius: Float, density: Float, color: Int, blend: Blend, dot: Float) {
        if ((color ushr 24) == 0 || !deviceMatrix()) return
        if (!cellsArePixels(dot)) {
            oneXs[0] = x
            oneYs[0] = y
            oneRs[0] = radius
            oneDs[0] = density
            latticeRun(oneXs, oneYs, oneRs, oneDs, 1, color, blend, dot)
            return
        }
        stippleCount = 0
        val hardware = canvas.isHardwareAccelerated
        Stipple.stamp(
            x, y, radius, density, toDevice, toDocument,
            if (hardware) Int.MAX_VALUE else canvas.width, if (hardware) Int.MAX_VALUE else canvas.height, collectStipple,
        )
        drawStipple(color, blend)
    }

    private val oneXs = FloatArray(1)
    private val oneYs = FloatArray(1)
    private val oneRs = FloatArray(1)
    private val oneDs = FloatArray(1)

    /**
     * Whether a cell [dot] document pixels across is one pixel of the current canvas, so the stamps can be worked out on
     * the pixels as the display does. It also holds where it can't be done any other way: a hardware canvas, which the
     * live stroke is drawn on at the zoom it was started at, and a matrix that turns or skews.
     */
    private fun cellsArePixels(dot: Float): Boolean {
        val uniform = toDevice.skewX == 0f && toDevice.skewY == 0f && toDevice.scaleX == toDevice.scaleY && toDevice.scaleX > 0f
        return canvas.isHardwareAccelerated || !uniform || !(dot > 0f) || abs(toDevice.scaleX * dot - 1f) < 1e-3f
    }

    /** A stroke's stamps at once, each cell drawn once ([Stipple.stamps]), in one draw call. */
    override fun stippleRun(xs: FloatArray, ys: FloatArray, rs: FloatArray, ds: FloatArray, n: Int, color: Int, blend: Blend, dot: Float) {
        if ((color ushr 24) == 0 || n <= 0 || !deviceMatrix()) return
        if (!cellsArePixels(dot)) {
            latticeRun(xs, ys, rs, ds, n, color, blend, dot)
            return
        }
        if (canvas.isHardwareAccelerated) {
            stippleCount = 0
            Stipple.stamps(xs, ys, rs, ds, n, toDevice, toDocument, Int.MAX_VALUE, Int.MAX_VALUE, collectStipple)
            drawStipple(color, blend)
            return
        }
        // On a bitmap the dots are its own pixels: they go in as one 1-bit mask, drawn unscaled at their place, which
        // is many times quicker than as thousands of points.
        litCount = 0
        litMinX = Int.MAX_VALUE
        litMinY = Int.MAX_VALUE
        litMaxX = Int.MIN_VALUE
        litMaxY = Int.MIN_VALUE
        Stipple.stamps(xs, ys, rs, ds, n, toDevice, toDocument, canvas.width, canvas.height, collectLit)
        if (litCount == 0) return
        val w = litMaxX - litMinX + 1
        val h = litMaxY - litMinY + 1
        val m = mask(w, h)
        val stride = m.rowBytes
        val bytes = maskBytes
        for (row in 0 until h) java.util.Arrays.fill(bytes, row * stride, row * stride + w, 0)
        for (k in 0 until litCount) bytes[(litYs[k] - litMinY) * stride + (litXs[k] - litMinX)] = -1
        m.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        maskPaint.color = color
        maskPaint.blendMode = blend.toBlendMode()
        maskPaint.isFilterBitmap = false
        maskSrc.set(0, 0, w, h)
        maskDst.set(litMinX, litMinY, litMinX + w, litMinY + h)
        canvas.save()
        canvas.setMatrix(null)
        canvas.drawBitmap(m, maskSrc, maskDst, maskPaint)
        canvas.restore()
    }

    /**
     * Stamps on the page's lattice of cells [dot] document pixels across, drawn at a zoom where a cell isn't a pixel of
     * this canvas: the cells are worked out once on the lattice, which is the same at every zoom, then scaled with the
     * page. Magnified, each cell is a block of pixels (nearest, so still never grey); shrunk, the cells under a pixel are
     * averaged, which is the stroke's tone, in grey. The blocks are those of the whole lattice, not of this canvas, so
     * neighbouring tiles meet without a seam. A canvas the cells would fill too many of is done a piece at a time.
     */
    private fun latticeRun(xs: FloatArray, ys: FloatArray, rs: FloatArray, ds: FloatArray, n: Int, color: Int, blend: Blend, dot: Float) {
        val s = toDevice.scaleX
        val z = 1f / dot
        val q = s * dot
        // Cells per averaged block: the whole number of them that fit in a pixel, when they're smaller than one.
        val f = if (q < 1f) floor(1f / q + 1e-3f).toInt().coerceAtLeast(1) else 1
        // The cells the canvas can show (a block more all round, for the filter), and the cells the stamps can reach.
        val pad = 2 * f
        val vl = floor(-toDevice.transX / s * z).toInt() - pad
        val vt = floor(-toDevice.transY / s * z).toInt() - pad
        val vr = ceil((canvas.width - toDevice.transX) / s * z).toInt() + pad
        val vb = ceil((canvas.height - toDevice.transY) / s * z).toInt() + pad
        var sl = Int.MAX_VALUE
        var st = Int.MAX_VALUE
        var sr = Int.MIN_VALUE
        var sb = Int.MIN_VALUE
        for (i in 0 until n) {
            if (!(rs[i] > 0f) || !(ds[i] > 0f) || !xs[i].isFinite() || !ys[i].isFinite()) continue
            sl = min(sl, floor((xs[i] - rs[i]) * z).toInt())
            st = min(st, floor((ys[i] - rs[i]) * z).toInt())
            sr = max(sr, ceil((xs[i] + rs[i]) * z).toInt())
            sb = max(sb, ceil((ys[i] + rs[i]) * z).toInt())
        }
        // Whole blocks, from a multiple of the block size, so every canvas cuts the lattice in the same places.
        val x0 = Math.floorDiv(max(vl, sl), f) * f
        val y0 = Math.floorDiv(max(vt, st), f) * f
        val x1 = min(vr, sr + 1)
        val y1 = min(vb, sb + 1)
        if (x1 <= x0 || y1 <= y0) return
        val piece = (STIPPLE_PIECE / f).coerceAtLeast(1) * f
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                latticePiece(xs, ys, rs, ds, n, color, blend, z, q, f, x, y, min(piece, x1 - x), min(piece, y1 - y))
                x += piece
            }
            y += piece
        }
    }

    /** One piece of [latticeRun]: the cells from ([cx], [cy]) on, [cw] by [ch] of them, whole blocks of [f] from a block's corner. */
    private fun latticePiece(
        xs: FloatArray, ys: FloatArray, rs: FloatArray, ds: FloatArray, n: Int, color: Int, blend: Blend,
        z: Float, q: Float, f: Int, cx: Int, cy: Int, cw: Int, ch: Int,
    ) {
        // The lattice's cell (gx, gy) is this piece's pixel (gx - cx, gy - cy): a whole-pixel offset, so the threshold of
        // every cell is the same whichever piece or canvas asks.
        val toCells = Affine(z, 0f, -cx.toFloat(), 0f, z, -cy.toFloat())
        val toDoc = toCells.invert() ?: return
        litCount = 0
        litMinX = Int.MAX_VALUE
        litMinY = Int.MAX_VALUE
        litMaxX = Int.MIN_VALUE
        litMaxY = Int.MIN_VALUE
        Stipple.stamps(xs, ys, rs, ds, n, toCells, toDoc, cw, ch, collectLit)
        if (litCount == 0) return
        // The mask is one texel per cell, or per f x f cells' share of them, with an empty texel all round so the filter
        // has a neighbour to blend with beyond the stamps.
        val gx0 = litMinX / f
        val gy0 = litMinY / f
        val w = litMaxX / f - gx0 + 1
        val h = litMaxY / f - gy0 + 1
        val mw = w + 2
        val mh = h + 2
        val m = mask(mw, mh)
        val stride = m.rowBytes
        val bytes = maskBytes
        for (row in 0 until mh) java.util.Arrays.fill(bytes, row * stride, row * stride + mw, 0)
        if (f == 1) {
            for (k in 0 until litCount) bytes[(litYs[k] - gy0 + 1) * stride + (litXs[k] - gx0 + 1)] = -1
        } else {
            if (coverage.size < w * h) coverage = IntArray(w * h)
            java.util.Arrays.fill(coverage, 0, w * h, 0)
            for (k in 0 until litCount) coverage[(litYs[k] / f - gy0) * w + (litXs[k] / f - gx0)]++
            val full = f * f
            for (row in 0 until h) for (col in 0 until w) {
                val c = coverage[row * w + col]
                if (c > 0) bytes[(row + 1) * stride + col + 1] = ((c * 255 + full / 2) / full).toByte()
            }
        }
        m.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        // A texel is q * f pixels across, and the piece's cell (cx, cy) is the document's (cx, cy) * dot: whole cells from
        // the page's origin, so a texel's corner is at its cell index times q, plus the canvas's translation.
        val unit = q * f
        val left = (cx + gx0 * f) * q + toDevice.transX
        val top = (cy + gy0 * f) * q + toDevice.transY
        maskPaint.color = color
        maskPaint.blendMode = blend.toBlendMode()
        // Magnified, or exactly 1:1, nearest keeps every pixel on or off; shrunk by a fraction, the filter blends.
        maskPaint.isFilterBitmap = unit < 1f - 1e-3f
        // The whole mask, empty border included: an edge that falls between pixels is then soft only where nothing is on.
        maskSrc.set(0, 0, mw, mh)
        maskDstF.set(left - unit, top - unit, left + (w + 1) * unit, top + (h + 1) * unit)
        canvas.save()
        canvas.setMatrix(null)
        canvas.drawBitmap(m, maskSrc, maskDstF, maskPaint)
        canvas.restore()
    }

    private var coverage = IntArray(0)
    private val maskDstF = RectF()

    private var litXs = IntArray(4096)
    private var litYs = IntArray(4096)
    private var litCount = 0
    private var litMinX = 0
    private var litMinY = 0
    private var litMaxX = 0
    private var litMaxY = 0
    private var maskBitmap: Bitmap? = null
    private var maskBytes = ByteArray(0)
    private val maskPaint = Paint()
    private val maskSrc = Rect()
    private val maskDst = Rect()

    private val collectLit = Stipple.Pixels { px, py ->
        if (litCount == litXs.size) {
            litXs = litXs.copyOf(litCount * 2)
            litYs = litYs.copyOf(litCount * 2)
        }
        litXs[litCount] = px
        litYs[litCount] = py
        litCount++
        if (px < litMinX) litMinX = px
        if (px > litMaxX) litMaxX = px
        if (py < litMinY) litMinY = py
        if (py > litMaxY) litMaxY = py
    }

    /** An alpha-only bitmap at least [w] × [h], kept for the next run, with [maskBytes] laid out as its pixels. */
    private fun mask(w: Int, h: Int): Bitmap {
        val m = maskBitmap
        if (m != null && m.width >= w && m.height >= h) return m
        val next = Bitmap.createBitmap(maxOf(w, m?.width ?: 0), maxOf(h, m?.height ?: 0), Bitmap.Config.ALPHA_8)
        maskBitmap = next
        maskBytes = ByteArray(next.rowBytes * next.height)
        return next
    }

    /** Reads the canvas's matrix into [toDevice] and [toDocument]; false when it can't be inverted. */
    private fun deviceMatrix(): Boolean {
        @Suppress("DEPRECATION")
        canvas.getMatrix(deviceMatrix)
        deviceMatrix.getValues(deviceValues)
        val v = deviceValues
        val d = toDevice
        if (d.scaleX != v[0] || d.skewX != v[1] || d.transX != v[2] || d.skewY != v[3] || d.scaleY != v[4] || d.transY != v[5]) {
            val next = Affine(v[0], v[1], v[2], v[3], v[4], v[5])
            toDocument = next.invert() ?: return false
            toDevice = next
        }
        return true
    }

    private fun drawStipple(color: Int, blend: Blend) {
        if (stippleCount == 0) return
        stipplePaint.color = color
        stipplePaint.blendMode = blend.toBlendMode()
        stipplePaint.strokeWidth = 1f / toDevice.meanScale
        canvas.drawPoints(stipplePoints, 0, stippleCount * 2, stipplePaint)
    }

    private val collectStipple = Stipple.Pixels { px, py ->
        if (2 * stippleCount + 2 > stipplePoints.size) stipplePoints = stipplePoints.copyOf(stipplePoints.size * 2)
        val cx = px + 0.5f
        val cy = py + 0.5f
        stipplePoints[2 * stippleCount] = toDocument.mapX(cx, cy)
        stipplePoints[2 * stippleCount + 1] = toDocument.mapY(cx, cy)
        stippleCount++
    }

    override fun strokePolyline(xy: FloatArray, count: Int, width: Float, color: Int, cap: Cap, dash: FloatArray?, blend: Blend) {
        if (count < 2) return
        path.rewind()
        path.moveTo(xy[0], xy[1])
        for (i in 1 until count) path.lineTo(xy[2 * i], xy[2 * i + 1])
        line.color = color
        line.strokeWidth = width
        line.strokeCap = when (cap) {
            Cap.Round -> Paint.Cap.ROUND
            Cap.Butt -> Paint.Cap.BUTT
            Cap.Square -> Paint.Cap.SQUARE
        }
        line.pathEffect = dash?.takeIf { it.size >= 2 && it.sum() > 0f }?.let { DashPathEffect(it.copyOf(it.size - it.size % 2), 0f) }
        line.blendMode = blend.toBlendMode()
        canvas.drawPath(path, line)
        line.pathEffect = null
    }

    private fun filter(color: Int): ColorFilter {
        val c = color or -0x1000000
        return filters.getOrPut(c) { PorterDuffColorFilter(c, PorterDuff.Mode.SRC_IN) }
    }
}
