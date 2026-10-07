package app.booxultimatum.nib.editor

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.geom.Affine
import app.booxultimatum.nib.engine.geom.Box
import app.booxultimatum.nib.engine.geom.Viewport
import app.booxultimatum.nib.engine.render.StrokeRenderer
import app.booxultimatum.nib.engine.render.TileGrid
import app.booxultimatum.nib.engine.render.TileKey
import app.booxultimatum.nib.engine.render.TileRange
import app.booxultimatum.nib.render.CanvasSink
import app.booxultimatum.nib.render.TileCache
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * A selection lifted off its layer for a drag. Its strokes are drawn once into a bitmap at the tiles' zoom level and on
 * the tiles' own pixel lattice. When the drag starts, each of the layer's tiles under them gets a copy with exactly those
 * pixels taken out (a hole), made in the tile's own pixels so nothing of them is left at the edges once it's scaled to the
 * screen. Every frame of a move, scale or turn then draws the holes in place of their tiles and the lifted bitmap where
 * the strokes are going: bitmap draws, however heavy the strokes. Drawing the strokes themselves twice a frame made a
 * drag crawl, since a stipple pencil stroke alone is thousands of dots.
 *
 * After the drop the tiles are drawn again in the background, and until each is current the holes and the lifted
 * strokes stand in for it ([settle]), so nothing jumps back to where it was and the main thread doesn't wait.
 *
 * The bitmap is drawn ahead of time on a thread of its own when the strokes are picked, moved or the view settles
 * ([prepare]), so a drag rarely waits for it. Main thread only, apart from that thread, which reads immutable strokes.
 */
class SelectionLift(private val grid: TileGrid, private val onReady: () -> Unit) {
    private val log = Logbook.logger("nib.ui")
    private val main = Handler(Looper.getMainLooper())

    private class Request(val layerId: Long, val strokes: List<Stroke>, val range: TileRange, val gen: Int)

    private class Lifted(val layerId: Long, val strokes: List<Stroke>, val range: TileRange, val bitmap: Bitmap, val shrink: Float) {
        /** The layer's tiles under the strokes with their pixels taken out, made when a drag starts. */
        val holes = HashMap<TileKey, Bitmap>()
        var holesMade = false
    }

    private class Settling(val lifted: Lifted, val affine: Affine)

    private var lifted: Lifted? = null
    private var settling: Settling? = null
    private var drawing: Request? = null
    private var generation = 0
    private val matrix = Matrix()
    private val src = Rect()
    private val dst = Rect()
    private val clip = Rect()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val erase = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.DST_OUT }
    private val exactErase = Paint().apply { blendMode = BlendMode.DST_OUT }

    /** Draws the lifted strokes ahead of time, unless they're drawn already or being drawn. */
    fun prepare(layerId: Long, strokes: List<Stroke>, level: Int, visible: Box) {
        val range = rangeFor(strokes, level, visible) ?: return clear()
        if (matches(lifted?.layerId, lifted?.strokes, lifted?.range, layerId, strokes, range)) return
        if (matches(drawing?.layerId, drawing?.strokes, drawing?.range, layerId, strokes, range)) return
        val req = Request(layerId, strokes, range, ++generation)
        drawing = req
        thread(name = "nib-lift", isDaemon = true) {
            val made = runCatching { render(req) }.onFailure { log.e("selection lift failed", error = it) }.getOrNull()
            main.post {
                if (req.gen != generation) return@post
                drawing = null
                if (made != null) {
                    lifted = made
                    onReady()
                }
            }
        }
    }

    /**
     * Gets the strokes lifted for a frame of a drag, here and now if they weren't ready, with the holes in [tiles] they
     * leave. Returns false when there's nothing to lift.
     */
    fun lift(tiles: TileCache, layerId: Long, strokes: List<Stroke>, level: Int, visible: Box): Boolean {
        val range = rangeFor(strokes, level, visible) ?: return false
        var l = lifted
        if (l == null || !matches(l.layerId, l.strokes, l.range, layerId, strokes, range)) {
            generation++
            drawing = null
            l = render(Request(layerId, strokes, range, generation))
            lifted = l
        }
        if (!l.holesMade) makeHoles(tiles, l)
        return true
    }

    /** What the drag draws in place of [key]'s tile: the tile without the lifted strokes, or null to draw it as it is. */
    fun hole(key: TileKey): Bitmap? = lifted?.holes?.get(key)

    /** Draws the lifted strokes where the drag has them, through [affine] (in the upright view's pixels, through [flat]). */
    fun drawMoved(canvas: Canvas, flat: Viewport, affine: Affine) {
        val l = lifted ?: return
        drawLifted(canvas, flat, l, affine)
    }

    /** The strokes were dropped through [affine]; until their layer's tiles are current, they settle ([drawSettling]). */
    fun settle(layerId: Long, affine: Affine) {
        val l = lifted ?: return
        if (l.layerId == layerId) settling = Settling(l, affine)
    }

    /** Whether dropped strokes of [layerId] are still shown over tiles that haven't caught up. */
    fun settling(layerId: Long): Boolean = settling?.lifted?.layerId == layerId

    /** What stands in for [key]'s tile while dropped strokes settle and it isn't current: the tile without them. */
    fun settlingHole(key: TileKey): Bitmap? = settling?.lifted?.holes?.get(key)

    /** Stops showing dropped strokes over the tiles, which are current or about to be drawn again some other way. */
    fun endSettling() {
        settling = null
    }

    /**
     * Over each of [layerId]'s tiles in [range] that isn't current yet, draws the dropped strokes where they went, which
     * is what the tile will show (its hole has already taken them out where they were); a current tile shows them
     * already. Ends once every tile is current.
     */
    fun drawSettling(canvas: Canvas, tiles: TileCache, flat: Viewport, layerId: Long, level: Int, range: TileRange) {
        val s = settling ?: return
        if (s.lifted.layerId != layerId) return
        if (s.lifted.range.level != level) {
            settling = null
            return
        }
        var pending = 0
        tiles.forEachPending(layerId, range) { key ->
            pending++
            tiles.viewRect(key, flat, clip)
            canvas.save()
            canvas.clipRect(clip)
            drawLifted(canvas, flat, s.lifted, s.affine)
            canvas.restore()
        }
        if (pending == 0) settling = null
    }

    /** Forgets the lifted strokes: the selection ended or changed. Dropped ones still settle. */
    fun clear() {
        generation++
        drawing = null
        lifted = null
    }

    /** Each tile of the layer under the strokes, copied with exactly the lifted pixels taken out, in its own pixels. */
    private fun makeHoles(tiles: TileCache, l: Lifted) {
        l.holesMade = true
        // A lift drawn smaller than the tiles can't take their pixels out exactly; it's erased on screen instead.
        if (l.shrink != 1f) return
        val start = SystemClock.elapsedRealtimeNanos()
        val size = grid.tileSize
        val canvas = Canvas()
        for (key in l.range) {
            val tile = tiles.bitmapOf(l.layerId, key) ?: continue
            val hole = tile.copy(Bitmap.Config.ARGB_8888, true) ?: continue
            val u = (key.tx - l.range.tx0) * size
            val v = (key.ty - l.range.ty0) * size
            src.set(u, v, u + size, v + size)
            dst.set(0, 0, size, size)
            canvas.setBitmap(hole)
            canvas.drawBitmap(l.bitmap, src, dst, exactErase)
            l.holes[key] = hole
        }
        canvas.setBitmap(null)
        log.d("selection holes made", "tiles" to l.holes.size, "ms" to Math.round((SystemClock.elapsedRealtimeNanos() - start) / 1e4) / 100.0)
    }

    private fun drawLifted(canvas: Canvas, flat: Viewport, l: Lifted, affine: Affine) {
        val bs = TileGrid.bucketScale(l.range.level) * l.shrink
        val ox = l.range.tx0 * grid.tileSize.toFloat()
        val oy = l.range.ty0 * grid.tileSize.toFloat()
        // The bitmap's pixel (u, v) is document ((u + ox * shrink) / bs, (v + oy * shrink) / bs).
        val fromBitmap = Affine(scaleX = 1f / bs, transX = ox * l.shrink / bs, scaleY = 1f / bs, transY = oy * l.shrink / bs)
        val docToView = flat.docToView()
        if (l.shrink != 1f) {
            matrix.setValues(docToView.compose(fromBitmap).toMatrixValues())
            canvas.drawBitmap(l.bitmap, matrix, erase)
        }
        matrix.setValues(docToView.compose(affine).compose(fromBitmap).toMatrixValues())
        canvas.drawBitmap(l.bitmap, matrix, paint)
    }

    /** The whole tiles the lifted strokes need: where they reach, within the visible page and a margin around it. */
    private fun rangeFor(strokes: List<Stroke>, level: Int, visible: Box): TileRange? {
        if (strokes.isEmpty() || visible.isEmpty) return null
        var bounds = Box.EMPTY
        for (s in strokes) bounds = bounds.union(s.bounds)
        val mw = (visible.right - visible.left) * MARGIN
        val mh = (visible.bottom - visible.top) * MARGIN
        val around = Box(visible.left - mw, visible.top - mh, visible.right + mw, visible.bottom + mh)
        var range = grid.range(bounds.intersect(around), level)
        if (range.isEmpty) return null
        if (pixels(range) > MAX_PIXELS) range = grid.range(bounds.intersect(visible), level)
        return range.takeUnless { it.isEmpty }
    }

    private fun pixels(r: TileRange): Long = (r.tx1 - r.tx0 + 1).toLong() * (r.ty1 - r.ty0 + 1) * grid.tileSize * grid.tileSize

    private fun matches(lid: Long?, ls: List<Stroke>?, lr: TileRange?, layerId: Long, strokes: List<Stroke>, range: TileRange): Boolean {
        if (lid != layerId || ls == null || lr == null || ls.size != strokes.size) return false
        for (i in strokes.indices) if (ls[i] !== strokes[i]) return false
        return lr.level == range.level && lr.tx0 <= range.tx0 && lr.ty0 <= range.ty0 && lr.tx1 >= range.tx1 && lr.ty1 >= range.ty1
    }

    private fun render(req: Request): Lifted {
        val start = SystemClock.elapsedRealtimeNanos()
        val r = req.range
        val size = grid.tileSize
        val px = pixels(r)
        // A selection larger than the screen several times over is drawn smaller for the drag, which then blurs a little.
        val shrink = if (px > MAX_PIXELS) sqrt(MAX_PIXELS.toFloat() / px) else 1f
        val w = ceil((r.tx1 - r.tx0 + 1) * size * shrink).toInt().coerceAtLeast(1)
        val h = ceil((r.ty1 - r.ty0 + 1) * size * shrink).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val bs = TileGrid.bucketScale(r.level) * shrink
        val toBitmap = Affine(scaleX = bs, transX = -r.tx0 * size * shrink, scaleY = bs, transY = -r.ty0 * size * shrink)
        val canvas = Canvas(bmp)
        canvas.setMatrix(Matrix().apply { setValues(toBitmap.toMatrixValues()) })
        val sink = CanvasSink().on(canvas)
        val tolerance = 0.25f / bs
        for (s in req.strokes) StrokeRenderer.render(s, sink, tolerance)
        canvas.setBitmap(null)
        log.d(
            "selection lifted", "strokes" to req.strokes.size, "px" to "${w}x$h",
            "ms" to Math.round((SystemClock.elapsedRealtimeNanos() - start) / 1e4) / 100.0,
            "thread" to Thread.currentThread().name,
        )
        return Lifted(req.layerId, req.strokes, r, bmp, shrink)
    }

    private companion object {
        /** How much of the visible page's size around it a lift also covers, so a drag can bring strokes into view. */
        const val MARGIN = 0.25f

        /** The most pixels a lift takes (32 MB). */
        const val MAX_PIXELS = 8L * 1024 * 1024
    }
}
