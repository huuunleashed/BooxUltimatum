package app.booxultimatum.nib.engine.render

import app.booxultimatum.nib.engine.geom.Affine
import app.booxultimatum.nib.engine.geom.Box
import app.booxultimatum.nib.engine.geom.Viewport
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One tile: zoom [level] (see [TileGrid.scaleBucket]) and its column and row in view pixels at that level's scale. */
data class TileKey(val level: Int, val tx: Int, val ty: Int)

/** An inclusive block of tiles at one level. Empty when [tx1] < [tx0] or [ty1] < [ty0]. */
data class TileRange(val level: Int, val tx0: Int, val ty0: Int, val tx1: Int, val ty1: Int) : Iterable<TileKey> {
    val isEmpty: Boolean get() = tx1 < tx0 || ty1 < ty0
    val count: Int get() = if (isEmpty) 0 else (tx1 - tx0 + 1) * (ty1 - ty0 + 1)

    operator fun contains(key: TileKey): Boolean =
        key.level == level && key.tx in tx0..tx1 && key.ty in ty0..ty1

    override fun iterator(): Iterator<TileKey> = object : Iterator<TileKey> {
        private var x = tx0
        private var y = ty0

        override fun hasNext(): Boolean = !isEmpty && y <= ty1

        override fun next(): TileKey {
            if (!hasNext()) throw NoSuchElementException()
            val k = TileKey(level, x, y)
            if (++x > tx1) {
                x = tx0
                y++
            }
            return k
        }
    }

    companion object {
        fun empty(level: Int): TileRange = TileRange(level, 0, 0, -1, -1)
    }
}

/**
 * Square tiles of [tileSize] view pixels. Zoom is quantised into levels a factor of sqrt(2) apart, so a tile rendered
 * at a level's scale is reused (drawn scaled by at most 2^(1/4)) across small zoom changes. Tile (tx, ty) at level L
 * covers view pixels [tx * tileSize, (tx + 1) * tileSize) at [bucketScale] (L), independent of panning.
 */
class TileGrid(val tileSize: Int = 256) {
    init {
        require(tileSize > 0) { "tileSize must be positive" }
    }

    /** The tiles at [level] that cover [docBox]. */
    fun range(docBox: Box, level: Int): TileRange {
        if (docBox.isEmpty) return TileRange.empty(level)
        val s = bucketScale(level) / tileSize
        return TileRange(
            level,
            floor(docBox.left * s).toInt(),
            floor(docBox.top * s).toInt(),
            ceil(docBox.right * s).toInt() - 1,
            ceil(docBox.bottom * s).toInt() - 1,
        )
    }

    /** The tiles covering [docBox] at the level for [scale]. */
    fun tilesCovering(docBox: Box, scale: Float): TileRange = range(docBox, scaleBucket(scale))

    /** The tiles needed to fill a [viewW] by [viewH] view through [viewport]. */
    fun visibleTiles(viewport: Viewport, viewW: Float, viewH: Float): TileRange =
        tilesCovering(viewport.visibleDocRect(viewW, viewH), viewport.scale)

    /** The document area a tile covers. */
    fun docBox(key: TileKey): Box {
        val size = tileSize / bucketScale(key.level)
        return Box(key.tx * size, key.ty * size, (key.tx + 1) * size, (key.ty + 1) * size)
    }

    /** Maps document pixels to the tile's own pixels (0..tileSize), for rendering the tile. */
    fun docToTile(key: TileKey): Affine {
        val s = bucketScale(key.level)
        return Affine(scaleX = s, transX = -key.tx.toFloat() * tileSize, scaleY = s, transY = -key.ty.toFloat() * tileSize)
    }

    /** Where the tile lands in the view, in view pixels. */
    fun viewBox(key: TileKey, viewport: Viewport): Box = viewport.docRectToView(docBox(key))

    companion object {
        private val SQRT2 = sqrt(2.0)

        /** The zoom level for [scale]: the nearest power of sqrt(2). */
        fun scaleBucket(scale: Float): Int {
            if (!(scale > 0f)) return 0
            return (ln(scale.toDouble()) / ln(SQRT2)).roundToInt()
        }

        /** The scale tiles at [level] are rendered at. */
        fun bucketScale(level: Int): Float = SQRT2.pow(level).toFloat()
    }
}

/**
 * Which tiles hold up-to-date pixels. The app marks a tile valid after rendering it and invalidates the document
 * areas each edit reports; everything else about caching (memory, eviction) is the app's.
 */
class TileTracker(val grid: TileGrid) {
    private val valid = HashMap<Int, HashSet<Long>>()

    val validCount: Int get() = valid.values.sumOf { it.size }

    fun isValid(key: TileKey): Boolean = valid[key.level]?.contains(pack(key.tx, key.ty)) == true

    fun markValid(key: TileKey) {
        valid.getOrPut(key.level) { HashSet() }.add(pack(key.tx, key.ty))
    }

    /** The tiles in [range] that need rendering. */
    fun dirtyIn(range: TileRange): List<TileKey> = range.filter { !isValid(it) }

    /** Invalidates every tile, at every level, that overlaps [docBox]; returns how many were valid. */
    fun invalidate(docBox: Box): Int {
        if (docBox.isEmpty) return 0
        var removed = 0
        for ((level, set) in valid) {
            if (set.isEmpty()) continue
            val r = grid.range(docBox, level)
            if (r.count > set.size) {
                val it = set.iterator()
                while (it.hasNext()) {
                    val p = it.next()
                    val tx = (p shr 32).toInt()
                    val ty = p.toInt()
                    if (tx in r.tx0..r.tx1 && ty in r.ty0..r.ty1) {
                        it.remove()
                        removed++
                    }
                }
            } else {
                for (k in r) if (set.remove(pack(k.tx, k.ty))) removed++
            }
        }
        return removed
    }

    fun invalidateAll() {
        valid.clear()
    }

    /** Forgets tiles at levels other than [keep], to free their memory in the app's cache. */
    fun retainLevel(keep: Int) {
        valid.keys.retainAll(setOf(keep))
    }

    private fun pack(tx: Int, ty: Int): Long = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
}
