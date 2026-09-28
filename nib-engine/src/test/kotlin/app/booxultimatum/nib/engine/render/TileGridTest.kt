package app.booxultimatum.nib.engine.render

import app.booxultimatum.nib.engine.geom.Box
import app.booxultimatum.nib.engine.geom.Viewport
import kotlin.math.floor
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class TileGridTest {
    private val grid = TileGrid(256)

    @Test
    fun scaleBucketsArePowersOfRootTwo() {
        assertEquals(0, TileGrid.scaleBucket(1f))
        assertEquals(1, TileGrid.scaleBucket(1.4142f))
        assertEquals(2, TileGrid.scaleBucket(2f))
        assertEquals(-2, TileGrid.scaleBucket(0.5f))
        assertEquals(8, TileGrid.scaleBucket(16f))
        assertEquals(0, TileGrid.scaleBucket(1.1f), "small zoom changes reuse the level")
        assertEquals(0, TileGrid.scaleBucket(0.9f))
        assertEquals(0, TileGrid.scaleBucket(0f))
        val rnd = Random(4)
        repeat(500) {
            val s = 0.1f + rnd.nextFloat() * 16f
            val ratio = s / TileGrid.bucketScale(TileGrid.scaleBucket(s))
            assertTrue(ratio in 2f.pow(-0.2501f)..2f.pow(0.2501f), "scale $s within a quarter octave of its level")
        }
    }

    @Test
    fun rangesCoverTheirBoxExactly() {
        val rnd = Random(8)
        repeat(300) {
            val level = rnd.nextInt(-6, 9)
            val l = rnd.nextFloat() * 2000f - 200f
            val t = rnd.nextFloat() * 2000f - 200f
            val box = Box(l, t, l + 1f + rnd.nextFloat() * 800f, t + 1f + rnd.nextFloat() * 800f)
            val range = grid.range(box, level)
            val s = TileGrid.bucketScale(level)
            repeat(20) {
                val x = box.left + rnd.nextFloat() * box.width * 0.999f
                val y = box.top + rnd.nextFloat() * box.height * 0.999f
                val key = TileKey(level, floor(x * s / 256f).toInt(), floor(y * s / 256f).toInt())
                assertTrue(key in range, "($x, $y) at level $level")
                assertTrue(grid.docBox(key).contains(x, y))
            }
            for (k in range) assertTrue(grid.docBox(k).intersects(box), "tile $k touches $box")
            assertEquals(range.count, range.toList().size)
        }
        assertTrue(grid.range(Box.EMPTY, 0).isEmpty)
        assertEquals(0, grid.range(Box.EMPTY, 0).count)
    }

    @Test
    fun tileTransformsMatchTheirBoxes() {
        for (key in listOf(TileKey(0, 0, 0), TileKey(2, 3, -1), TileKey(-3, 1, 5))) {
            val box = grid.docBox(key)
            val m = grid.docToTile(key)
            assertEquals(0f, m.mapX(box.left, box.top), 1e-2f)
            assertEquals(0f, m.mapY(box.left, box.top), 1e-2f)
            assertEquals(256f, m.mapX(box.right, box.bottom), 1e-2f)
            assertEquals(256f, m.mapY(box.right, box.bottom), 1e-2f)
        }
        val v = Viewport(1.2f, -300f, 40f)
        val view = Box(0f, 0f, 1860f, 2480f)
        val tiles = grid.visibleTiles(v, 1860f, 2480f)
        var union = Box.EMPTY
        for (k in tiles) union = union.union(grid.viewBox(k, v))
        assertTrue(union.contains(view), "tiles $union cover the view")
        assertEquals(TileGrid.scaleBucket(1.2f), tiles.level)
    }

    @Test
    fun trackerInvalidatesOverlappingTilesAtEveryLevel() {
        val tracker = TileTracker(grid)
        val page = Box(0f, 0f, 1860f, 2480f)
        for (level in listOf(0, 1, -2)) for (k in grid.range(page, level)) tracker.markValid(k)
        val total = tracker.validCount
        assertEquals(grid.range(page, 0).count + grid.range(page, 1).count + grid.range(page, -2).count, total)
        val dirty = Box(300f, 300f, 310f, 310f)
        val removed = tracker.invalidate(dirty)
        val expected = listOf(0, 1, -2).sumOf { grid.range(dirty, it).count }
        assertEquals(expected, removed)
        assertEquals(total - expected, tracker.validCount)
        assertFalse(tracker.isValid(TileKey(0, 1, 1)))
        assertTrue(tracker.isValid(TileKey(0, 0, 0)))
        assertEquals(listOf(TileKey(0, 1, 1)), tracker.dirtyIn(grid.range(Box(260f, 260f, 270f, 270f), 0)))
        assertEquals(0, tracker.invalidate(Box.EMPTY))
        tracker.retainLevel(0)
        assertEquals(grid.range(page, 0).count - 1, tracker.validCount)
        tracker.invalidateAll()
        assertEquals(0, tracker.validCount)
    }
}
