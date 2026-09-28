package app.booxultimatum.nib.engine.index

import app.booxultimatum.nib.engine.doc.Document
import app.booxultimatum.nib.engine.doc.Layer
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.geom.Box
import kotlin.math.floor

/**
 * A uniform grid over one layer's strokes, keyed by their render bounds. Inserts and removals are incremental;
 * [sync] brings it in line with a new version of the layer by comparing stroke identities, which is cheap because
 * edits share untouched strokes. Query results are unordered. Not thread-safe.
 */
class StrokeIndex(val cellSize: Float = DEFAULT_CELL_SIZE) {
    init {
        require(cellSize > 0f) { "cellSize must be positive" }
    }

    private val cells = HashMap<Long, ArrayList<Stroke>>()
    private val byId = HashMap<Long, Stroke>()
    private val large = ArrayList<Stroke>()

    /** The stroke list of the layer version last passed to [sync], while nothing else has changed the index. */
    private var synced: List<Stroke>? = null

    val size: Int get() = byId.size

    fun contains(id: Long): Boolean = byId.containsKey(id)

    operator fun get(id: Long): Stroke? = byId[id]

    val strokes: Collection<Stroke> get() = byId.values

    /** Adds [stroke], replacing any stroke with the same id. */
    fun insert(stroke: Stroke) {
        synced = null
        byId[stroke.id]?.let { removeFromCells(it) }
        byId[stroke.id] = stroke
        val b = HitTest.reach(stroke)
        if (b.isEmpty) {
            large.add(stroke)
            return
        }
        val x0 = cell(b.left)
        val y0 = cell(b.top)
        val x1 = cell(b.right)
        val y1 = cell(b.bottom)
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) > MAX_CELLS) {
            large.add(stroke)
            return
        }
        for (cy in y0..y1) for (cx in x0..x1) cells.getOrPut(key(cx, cy)) { ArrayList(4) }.add(stroke)
    }

    /** Removes the stroke with [id]; false when it wasn't there. */
    fun remove(id: Long): Boolean {
        val s = byId.remove(id) ?: return false
        synced = null
        removeFromCells(s)
        return true
    }

    fun clear() {
        synced = null
        cells.clear()
        byId.clear()
        large.clear()
    }

    /** Makes the index match [layer]. Appending to the layer last synced costs only the new strokes. */
    fun sync(layer: Layer) {
        val now = layer.strokes
        val prev = synced
        if (prev != null && byId.size == prev.size && prev.size <= now.size && isPrefix(prev, now)) {
            for (i in prev.size until now.size) insert(now[i])
            synced = now
            return
        }
        var matched = 0
        val toAdd = ArrayList<Stroke>()
        for (s in now) {
            val cur = byId[s.id]
            if (cur === s) {
                matched++
            } else {
                if (cur != null) remove(cur.id)
                toAdd.add(s)
            }
        }
        if (byId.size > matched) {
            val ids = HashSet<Long>(now.size * 2)
            for (s in now) ids.add(s.id)
            val gone = byId.keys.filter { it !in ids }
            for (id in gone) remove(id)
        }
        for (s in toAdd) insert(s)
        synced = now
    }

    private fun isPrefix(prev: List<Stroke>, now: List<Stroke>): Boolean {
        if (prev === now) return true
        for (i in prev.indices) if (prev[i] !== now[i]) return false
        return true
    }

    /** Strokes whose render bounds intersect [box]. */
    fun query(box: Box): List<Stroke> = collect(box).filter { it.bounds.intersects(box) }

    /**
     * Strokes touched by an eraser of [radius] dragged along the first [count] points of [path]; pixel-eraser
     * strokes are left out unless [includeErasers].
     */
    fun hitByEraser(path: FloatArray, count: Int, radius: Float, includeErasers: Boolean = false): List<Stroke> {
        if (count <= 0) return emptyList()
        val p = Box.of(path, count)
        val area = Box(p.left - radius - EDGE, p.top - radius - EDGE, p.right + radius + EDGE, p.bottom + radius + EDGE)
        return collect(area).filter {
            (includeErasers || !it.brush.kind.isEraser) && HitTest.hitsEraser(it, path, count, radius)
        }
    }

    /** Strokes with at least [minFraction] of their points inside the lasso polygon. */
    fun insideLasso(polygon: FloatArray, count: Int, minFraction: Float = 0.5f, includeErasers: Boolean = false): List<Stroke> {
        if (count < 3) return emptyList()
        val p = Box.of(polygon, count)
        val area = Box(p.left - EDGE, p.top - EDGE, p.right + EDGE, p.bottom + EDGE)
        return collect(area).filter {
            (includeErasers || !it.brush.kind.isEraser) && HitTest.insideLasso(it, polygon, count, minFraction)
        }
    }

    /** Strokes whose [HitTest.reach] may overlap [area] (a superset). */
    private fun collect(area: Box): List<Stroke> {
        if (area.isEmpty || byId.isEmpty()) return emptyList()
        val x0 = cell(area.left)
        val y0 = cell(area.top)
        val x1 = cell(area.right)
        val y1 = cell(area.bottom)
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) > cells.size.toLong() + 16) {
            return byId.values.filter { HitTest.reach(it).intersects(area) }
        }
        val out = ArrayList<Stroke>()
        val seen = HashSet<Long>()
        for (cy in y0..y1) for (cx in x0..x1) {
            val list = cells[key(cx, cy)] ?: continue
            for (s in list) if (seen.add(s.id)) out.add(s)
        }
        for (s in large) if (seen.add(s.id)) out.add(s)
        return out
    }

    private fun removeFromCells(s: Stroke) {
        if (large.removeIf { it === s }) return
        val b = HitTest.reach(s)
        if (b.isEmpty) return
        val x0 = cell(b.left)
        val y0 = cell(b.top)
        val x1 = cell(b.right)
        val y1 = cell(b.bottom)
        for (cy in y0..y1) for (cx in x0..x1) {
            val k = key(cx, cy)
            val list = cells[k] ?: continue
            list.removeIf { it === s }
            if (list.isEmpty()) cells.remove(k)
        }
    }

    private fun cell(v: Float): Int = floor(v / cellSize).toDouble().coerceIn(-1e9, 1e9).toInt()

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

    companion object {
        const val DEFAULT_CELL_SIZE = 256f

        /** Strokes spanning more cells than this sit in a list that every query checks. */
        private const val MAX_CELLS = 4096L
        private const val EDGE = 1e-3f
    }
}

/** A [StrokeIndex] per layer of a document. */
class DocumentIndex(document: Document, val cellSize: Float = StrokeIndex.DEFAULT_CELL_SIZE) {
    private val layers = HashMap<Long, StrokeIndex>()

    init {
        rebuild(document)
    }

    fun layer(layerId: Long): StrokeIndex? = layers[layerId]

    fun rebuild(document: Document) {
        layers.clear()
        for (l in document.layers) layers[l.id] = StrokeIndex(cellSize).also { it.sync(l) }
    }

    /** Brings the given layers in line with [document]; layers no longer in it are dropped. */
    fun sync(document: Document, layerIds: Collection<Long>) {
        for (id in layerIds) {
            val layer = document.layer(id)
            if (layer == null) {
                layers.remove(id)
            } else {
                layers.getOrPut(id) { StrokeIndex(cellSize) }.sync(layer)
            }
        }
    }

    fun query(layerId: Long, box: Box): List<Stroke> = layers[layerId]?.query(box) ?: emptyList()

    fun hitByEraser(layerId: Long, path: FloatArray, count: Int, radius: Float, includeErasers: Boolean = false): List<Stroke> =
        layers[layerId]?.hitByEraser(path, count, radius, includeErasers) ?: emptyList()

    fun insideLasso(
        layerId: Long,
        polygon: FloatArray,
        count: Int,
        minFraction: Float = 0.5f,
        includeErasers: Boolean = false,
    ): List<Stroke> = layers[layerId]?.insideLasso(polygon, count, minFraction, includeErasers) ?: emptyList()
}
