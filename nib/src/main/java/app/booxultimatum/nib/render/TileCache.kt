package app.booxultimatum.nib.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.doc.Layer
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.geom.Box
import app.booxultimatum.nib.engine.geom.Viewport
import app.booxultimatum.nib.engine.render.StrokeRenderer
import app.booxultimatum.nib.engine.render.TileGrid
import app.booxultimatum.nib.engine.render.TileKey
import app.booxultimatum.nib.engine.render.TileRange
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Per-layer tile caches for the canvas. Each layer's strokes are rendered, without the layer's opacity or blend, into
 * 256 px ARGB tiles at the current zoom level ([TileGrid.scaleBucket]); tiles are made only where the layer has ink,
 * and the canvas composites them each frame with the layer's own opacity, blend and visibility.
 *
 * - A finished stroke is drawn straight into the tiles it touches (recorded once as a [Picture], played into each),
 *   on the main thread, so the committed stroke is in the next frame.
 * - Anything else (undo, zoom, eviction) re-renders tiles from the vectors on a background thread; the old pixels,
 *   or the tiles of the previous zoom level, stand in until the new ones arrive.
 * - Tiles off screen are dropped least recently used first once the total passes the budget.
 *
 * Main thread only, except the worker it owns.
 */
class TileCache(private val grid: TileGrid, budgetBytes: Long, private val onReady: () -> Unit) {
    private val log = Logbook.logger("nib.render")

    private enum class State { Missing, Ready, Stale }

    private class Tile(val layer: Long, val key: TileKey) {
        var bitmap: Bitmap? = null
        var state = State.Missing
        var gen = 0
        var inFlight = false
        val ref = Ref(layer, key)
    }

    private data class Ref(val layer: Long, val key: TileKey)

    private class Job(val layer: Long, val key: TileKey, val gen: Int, val strokes: List<Stroke>)

    private val layers = HashMap<Long, HashMap<TileKey, Tile>>()
    private val lru = TileLru<Ref>(budgetBytes)
    private val main = Handler(Looper.getMainLooper())
    private val pool = ArrayDeque<Bitmap>()
    private val queue = LinkedBlockingDeque<Job>()
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tileCanvas = Canvas()
    private val sink = CanvasSink()
    private val matrix = Matrix()
    private val dst = Rect()
    private val pinned = HashSet<Ref>()

    // Bitmaps dropped during a frame may still be drawn by that frame, so they reach the pool only at the next one.
    private val graveyard = ArrayList<Bitmap>()
    private var level = 0
    private var frameComplete = true

    private var outstanding = 0
    private var batchCount = 0
    private var batchMs = 0.0
    private var batchMax = 0.0

    val tileSize: Int get() = grid.tileSize
    val tileCount: Int get() = lru.size
    val usedBytes: Long get() = lru.usedBytes
    /** Tile renders asked for and not yet back. */
    val pendingJobs: Int get() = outstanding

    @Volatile private var worker: Thread? = null

    /** Starts a frame at zoom [level]; jobs for other levels are dropped. */
    fun beginFrame(level: Int) {
        if (graveyard.isNotEmpty()) {
            for (b in graveyard) recycle(b)
            graveyard.clear()
        }
        if (level != this.level) {
            for (job in queue.toTypedArray()) if (job.key.level != level && queue.removeFirstOccurrence(job)) outstanding--
            for (m in layers.values) for (t in m.values) if (t.key.level != level) t.inFlight = false
            this.level = level
        }
        pinned.clear()
        frameComplete = true
    }

    /**
     * Draws [layer]'s tiles covering [range] (at the frame's level) onto [canvas] in view pixels, asking the worker for
     * any that are missing. [hasInk] says whether any stroke of the layer reaches a document box.
     */
    fun drawLayer(canvas: Canvas, layer: Layer, viewport: Viewport, range: TileRange, hasInk: (Box) -> Boolean) {
        val tiles = layers.getOrPut(layer.id) { HashMap() }
        var fallback: List<Tile>? = null
        for (key in range) {
            var t = tiles[key]
            if (t == null) {
                t = Tile(layer.id, key)
                tiles[key] = t
            }
            pinned.add(t.ref)
            if (t.state == State.Missing && !t.inFlight && !hasInk(grid.docBox(key))) {
                t.state = State.Ready
                t.gen++
            }
            if (t.state != State.Ready && !t.inFlight) request(t, layer)
            val bmp = t.bitmap
            if (t.state == State.Ready || (t.state == State.Stale && bmp != null)) {
                if (bmp != null) {
                    lru.touch(t.ref)
                    viewRect(key, viewport, dst)
                    canvas.drawBitmap(bmp, null, dst, tilePaint)
                }
                if (t.state == State.Stale) frameComplete = false
                continue
            }
            frameComplete = false
            if (fallback == null) fallback = tiles.values.filter { it.key.level != key.level && it.bitmap != null }
            if (fallback.isEmpty()) continue
            viewRect(key, viewport, dst)
            val box = grid.docBox(key)
            canvas.save()
            canvas.clipRect(dst)
            for (f in fallback) {
                if (!grid.docBox(f.key).intersects(box)) continue
                viewRect(f.key, viewport, TMP)
                canvas.drawBitmap(f.bitmap!!, null, TMP, tilePaint)
            }
            canvas.restore()
        }
    }

    /** Ends the frame: once every tile on screen is current, other levels go; then the budget is enforced. */
    fun endFrame() {
        if (frameComplete) dropOtherLevels()
        for (ref in lru.evict { it in pinned }) remove(ref)
    }

    /**
     * Draws a stroke just appended to [layerId] into the tiles it touches. Tiles not yet rendered are left to the
     * worker, which reads the new layer. Returns how long it took, in milliseconds.
     */
    fun appendStroke(layerId: Long, stroke: Stroke): Double {
        val start = SystemClock.elapsedRealtimeNanos()
        val bounds = stroke.bounds
        val tiles = layers[layerId] ?: return 0.0
        dropOtherLevels(layerId, bounds)
        val range = grid.range(bounds, level)
        var picture: Picture? = null
        var dabs: DabRecording? = null
        var prepared = false
        var drawn = 0
        var fresh = 0
        var recordMs = 0.0
        for (key in range) {
            val t = tiles[key] ?: continue
            t.gen++
            // Tiles in the bounds that the stroke never crosses keep their pixels as they are.
            if (t.state == State.Ready && !reaches(stroke, grid.docBox(key))) continue
            if (t.state != State.Ready) {
                t.inFlight = false
                t.state = if (t.bitmap != null) State.Stale else State.Missing
                continue
            }
            var bmp = t.bitmap
            if (bmp == null) {
                if (stroke.brush.blend == Blend.Erase || stroke.brush.blend == Blend.Atop) continue
                bmp = obtain()
                fresh++
                t.bitmap = bmp
                lru.put(t.ref, bmp.allocationByteCount.toLong())
            }
            if (!prepared) {
                // Recorded once: dab strokes as their dabs, so each tile replays only its own; the rest as a Picture.
                // Stipple is worked out on each tile's own pixels, so it's drawn into each tile directly.
                prepared = true
                val r0 = SystemClock.elapsedRealtimeNanos()
                val tolerance = 0.25f / TileGrid.bucketScale(level)
                if (stroke.brush.kind in DAB_KINDS) {
                    val rec = DabRecording()
                    StrokeRenderer.render(stroke, rec, tolerance)
                    if (!rec.general) dabs = rec
                }
                if (dabs == null && !stroke.brush.kind.rendersAsStipple) picture = record(stroke)
                recordMs = (SystemClock.elapsedRealtimeNanos() - r0) / 1e6
            }
            tileCanvas.setBitmap(bmp)
            tileCanvas.setMatrix(tileMatrix(key))
            val d = dabs
            val p = picture
            when {
                d != null -> d.replay(sink.on(tileCanvas), grid.docBox(key))
                p != null -> tileCanvas.drawPicture(p)
                else -> StrokeRenderer.render(stroke, sink.on(tileCanvas), 0.25f / TileGrid.bucketScale(level))
            }
            tileCanvas.setBitmap(null)
            drawn++
        }
        val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
        log.d("stroke committed to tiles", "tiles" to drawn, "new" to fresh, "ms" to round2(ms), "record ms" to round2(recordMs), "points" to stroke.size)
        return ms
    }

    /**
     * Re-renders [layer]'s tiles touching [bounds] here and now, from [strokesIn] (the layer's strokes reaching a
     * document box, in layer order), so a moved or recoloured selection never shows its old self while the worker
     * catches up. More than [RENDER_NOW_MAX] tiles are left to the worker instead; returns whether it rendered.
     */
    fun renderNow(layer: Layer, bounds: Box, strokesIn: (Box) -> List<Stroke>): Boolean {
        val tiles = layers[layer.id] ?: return true
        if (bounds.isEmpty) return true
        dropOtherLevels(layer.id, bounds)
        val keys = grid.range(bounds, level).filter { tiles[it] != null }
        if (keys.size > RENDER_NOW_MAX) {
            invalidate(layer.id, bounds)
            return false
        }
        val start = SystemClock.elapsedRealtimeNanos()
        val tolerance = 0.25f / TileGrid.bucketScale(level)
        for (key in keys) {
            val t = tiles[key]!!
            t.gen++
            t.inFlight = false
            val box = grid.docBox(key)
            val reaching = strokesIn(box).filter { reaches(it, box) }
            if (reaching.isEmpty()) {
                lru.remove(t.ref)
                t.bitmap?.let { graveyard.add(it) }
                t.bitmap = null
                t.state = State.Ready
                continue
            }
            var bmp = t.bitmap
            if (bmp == null) {
                bmp = obtain()
                t.bitmap = bmp
                lru.put(t.ref, bmp.allocationByteCount.toLong())
            } else {
                bmp.eraseColor(0)
            }
            tileCanvas.setBitmap(bmp)
            tileCanvas.setMatrix(tileMatrix(key))
            for (s in reaching) {
                if (s.brush.kind in DAB_KINDS) {
                    val rec = DabRecording()
                    StrokeRenderer.render(s, rec, tolerance)
                    if (!rec.general) {
                        rec.replay(sink.on(tileCanvas), box)
                        continue
                    }
                }
                StrokeRenderer.render(s, sink.on(tileCanvas), tolerance)
            }
            tileCanvas.setBitmap(null)
            t.state = State.Ready
        }
        log.d("tiles rendered at once", "tiles" to keys.size, "ms" to round2((SystemClock.elapsedRealtimeNanos() - start) / 1e6))
        return true
    }

    /** Marks [layerId]'s tiles touching [bounds] out of date; they re-render from the vectors. */
    fun invalidate(layerId: Long, bounds: Box) {
        val tiles = layers[layerId] ?: return
        if (bounds.isEmpty) return
        dropOtherLevels(layerId, bounds)
        for (key in grid.range(bounds, level)) {
            val t = tiles[key] ?: continue
            t.gen++
            t.inFlight = false
            t.state = if (t.bitmap != null) State.Stale else State.Missing
        }
    }

    /** Forgets everything about [layerId] (the layer was removed). */
    fun dropLayer(layerId: Long) {
        val tiles = layers.remove(layerId) ?: return
        for (t in tiles.values) release(t)
    }

    /** Keeps only the layers in [ids]. */
    fun retainLayers(ids: Set<Long>) {
        for (id in layers.keys.filter { it !in ids }) dropLayer(id)
    }

    fun clear() {
        for (id in layers.keys.toList()) dropLayer(id)
        val dropped = ArrayList<Job>()
        queue.drainTo(dropped)
        outstanding -= dropped.size
    }

    /** Stops the worker and frees every bitmap; the cache starts again, empty, when next drawn. */
    fun stop() {
        val w = worker
        worker = null
        w?.interrupt()
        clear()
        graveyard.forEach { it.recycle() }
        graveyard.clear()
        synchronized(pool) {
            pool.forEach { it.recycle() }
            pool.clear()
        }
    }

    /** Where a tile lands in an upright [viewport], rounded so neighbours share their edges exactly. */
    fun viewRect(key: TileKey, viewport: Viewport, out: Rect) {
        val size = grid.tileSize / TileGrid.bucketScale(key.level)
        out.set(
            viewport.toViewX(key.tx * size, 0f).roundToInt(),
            viewport.toViewY(0f, key.ty * size).roundToInt(),
            viewport.toViewX((key.tx + 1) * size, 0f).roundToInt(),
            viewport.toViewY(0f, (key.ty + 1) * size).roundToInt(),
        )
    }

    private fun request(t: Tile, layer: Layer) {
        if (worker == null) {
            worker = Thread({ workLoop() }, "nib-tiles").apply {
                isDaemon = true
                start()
            }
        }
        t.inFlight = true
        outstanding++
        queue.addLast(Job(layer.id, t.key, t.gen, layer.strokes))
    }

    private fun record(stroke: Stroke): Picture {
        val b = stroke.bounds
        val p = Picture()
        val c = p.beginRecording(max(1, ceil(b.right).toInt() + 2), max(1, ceil(b.bottom).toInt() + 2))
        StrokeRenderer.render(stroke, sink.on(c), 0.25f / TileGrid.bucketScale(level))
        p.endRecording()
        return p
    }

    private fun tileMatrix(key: TileKey): Matrix {
        matrix.setValues(grid.docToTile(key).toMatrixValues())
        return matrix
    }

    companion object Reach {
        /**
         * Whether [stroke] can put ink in [box]: some segment of its points passes within its widest reach (radius,
         * dab scatter and antialiasing) of the box. Conservative: it may say yes for a near miss, never no for a hit.
         */
        fun reaches(stroke: Stroke, box: Box): Boolean {
            val p = stroke.points
            if (p.size == 0) return false
            val b = box.inflate(stroke.brush.maxRadius * (2f + stroke.brush.jitter.coerceAtLeast(0f)) + 2f)
            if (p.size == 1) return b.contains(p.x(0), p.y(0))
            for (i in 0 until p.size - 1) if (segmentHits(p.x(i), p.y(i), p.x(i + 1), p.y(i + 1), b)) return true
            return false
        }

        /** Liang–Barsky: does the segment from (x0, y0) to (x1, y1) meet [b]? */
        fun segmentHits(x0: Float, y0: Float, x1: Float, y1: Float, b: Box): Boolean {
            var t0 = 0f
            var t1 = 1f
            val dx = x1 - x0
            val dy = y1 - y0
            val p = floatArrayOf(-dx, dx, -dy, dy)
            val q = floatArrayOf(x0 - b.left, b.right - x0, y0 - b.top, b.bottom - y0)
            for (i in 0 until 4) {
                if (p[i] == 0f) {
                    if (q[i] < 0f) return false
                } else {
                    val r = q[i] / p[i]
                    if (p[i] < 0f) {
                        if (r > t1) return false
                        if (r > t0) t0 = r
                    } else {
                        if (r < t0) return false
                        if (r < t1) t1 = r
                    }
                }
            }
            return true
        }

        private const val POOL_MAX = 24
        private const val POOL_WARM = 8
        private const val WARM_AFTER_MS = 400L

        /** The brushes the engine draws as dabs (its StrokeRenderer's Dabs mode). */
        val DAB_KINDS = BrushKind.entries.filter { it.rendersAsDabs }.toSet()

        /** The most tiles [renderNow] draws on the main thread. */
        const val RENDER_NOW_MAX = 24
        private val TMP = Rect()

        /** A third of the app's memory class for tiles, within sensible limits. */
        fun budgetFor(memoryClassMb: Int): Long = (memoryClassMb / 3).coerceIn(24, 192).toLong() shl 20
    }

    private fun dropOtherLevels() {
        for (tiles in layers.values) {
            val it = tiles.values.iterator()
            while (it.hasNext()) {
                val t = it.next()
                if (t.key.level != level) {
                    release(t)
                    it.remove()
                }
            }
        }
    }

    private fun dropOtherLevels(layerId: Long, bounds: Box) {
        val tiles = layers[layerId] ?: return
        val it = tiles.values.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (t.key.level != level && grid.docBox(t.key).intersects(bounds)) {
                release(t)
                it.remove()
            }
        }
    }

    private fun remove(ref: Ref) {
        val tiles = layers[ref.layer] ?: return
        val t = tiles.remove(ref.key) ?: return
        release(t)
    }

    private fun release(t: Tile) {
        lru.remove(t.ref)
        t.bitmap?.let { graveyard.add(it) }
        t.bitmap = null
        t.inFlight = false
        t.gen++
    }

    private fun obtain(): Bitmap {
        val b = synchronized(pool) { pool.removeLastOrNull() }
        if (b != null) {
            b.eraseColor(0)
            return b
        }
        return Bitmap.createBitmap(grid.tileSize, grid.tileSize, Bitmap.Config.ARGB_8888)
    }

    private fun warmPool() {
        while (worker === Thread.currentThread()) {
            val need = synchronized(pool) { pool.size < POOL_WARM }
            if (!need) return
            val b = Bitmap.createBitmap(grid.tileSize, grid.tileSize, Bitmap.Config.ARGB_8888)
            synchronized(pool) { pool.addLast(b) }
        }
    }

    private fun recycle(b: Bitmap) {
        synchronized(pool) {
            if (pool.size < POOL_MAX) pool.addLast(b) else b.recycle()
        }
    }

    // ---- The worker ----

    private fun workLoop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY + 2) }
        val canvas = Canvas()
        val sink = CanvasSink()
        val m = Matrix()
        while (worker === Thread.currentThread()) {
            val job = try {
                // While idle, keep a few blank tiles ready so a commit never waits to allocate one.
                queue.pollFirst(WARM_AFTER_MS, TimeUnit.MILLISECONDS) ?: run {
                    warmPool()
                    queue.takeFirst()
                }
            } catch (_: InterruptedException) {
                break
            }
            val start = SystemClock.elapsedRealtimeNanos()
            val box = grid.docBox(job.key)
            var bmp: Bitmap? = null
            try {
                for (s in job.strokes) {
                    if (!s.bounds.intersects(box) || !reaches(s, box)) continue
                    if (bmp == null) {
                        bmp = obtain()
                        canvas.setBitmap(bmp)
                        m.setValues(grid.docToTile(job.key).toMatrixValues())
                        canvas.setMatrix(m)
                    }
                    val tolerance = 0.25f / TileGrid.bucketScale(job.key.level)
                    if (s.brush.kind in DAB_KINDS) {
                        val rec = DabRecording()
                        StrokeRenderer.render(s, rec, tolerance)
                        if (!rec.general) {
                            rec.replay(sink.on(canvas), box)
                            continue
                        }
                    }
                    StrokeRenderer.render(s, sink.on(canvas), tolerance)
                }
            } catch (e: Exception) {
                log.e("tile render failed", "level" to job.key.level, error = e)
            } finally {
                canvas.setBitmap(null)
            }
            val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
            val result = bmp
            main.post { install(job, result, ms) }
        }
    }

    private fun install(job: Job, bmp: Bitmap?, ms: Double) {
        outstanding = (outstanding - 1).coerceAtLeast(0)
        batchCount++
        batchMs += ms
        batchMax = max(batchMax, ms)
        if (outstanding == 0) logBatch(job.key.level)
        val t = layers[job.layer]?.get(job.key)
        if (t == null || t.gen != job.gen || worker == null) {
            bmp?.let { recycle(it) }
            return
        }
        t.inFlight = false
        t.bitmap?.let { graveyard.add(it) }
        t.bitmap = bmp
        t.state = State.Ready
        if (bmp != null) lru.put(t.ref, bmp.allocationByteCount.toLong()) else lru.remove(t.ref)
        onReady()
    }

    private fun logBatch(level: Int) {
        log.d(
            "tiles rendered",
            "count" to batchCount, "ms" to round2(batchMs), "max ms" to round2(batchMax),
            "level" to level, "tiles" to lru.size, "mb" to round2(lru.usedBytes / 1048576.0),
        )
        batchCount = 0
        batchMs = 0.0
        batchMax = 0.0
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
