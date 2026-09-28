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
import android.graphics.RectF
import android.graphics.Shader
import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.render.Cap
import app.booxultimatum.nib.engine.render.RenderSink
import app.booxultimatum.nib.engine.render.Texture
import kotlin.math.roundToInt

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
