package app.booxultimatum.nib.render

import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.geom.Box
import app.booxultimatum.nib.engine.input.InputSample
import app.booxultimatum.nib.engine.input.StrokeBuilder
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TileLruTest {
    @Test fun nothingGoesWhileUnderBudget() {
        val lru = TileLru<String>(300)
        lru.put("a", 100)
        lru.put("b", 100)
        assertEquals(emptyList(), lru.evict { false })
        assertEquals(200, lru.usedBytes)
    }

    @Test fun theLeastRecentlyUsedGoFirst() {
        val lru = TileLru<String>(250)
        lru.put("a", 100)
        lru.put("b", 100)
        lru.put("c", 100)
        lru.touch("a")
        assertEquals(listOf("b"), lru.evict { false })
        assertEquals(listOf("c", "a"), lru.keys())
        assertEquals(200, lru.usedBytes)
    }

    @Test fun pinnedTilesStayEvenOverBudget() {
        val lru = TileLru<String>(100)
        for (k in listOf("a", "b", "c")) lru.put(k, 100)
        assertEquals(listOf("b"), lru.evict { it != "b" })
        assertEquals(200, lru.usedBytes, "what's on screen runs over the budget rather than going blank")
        assertEquals(emptyList(), lru.evict { true })
    }

    @Test fun resizingAndRemovingKeepTheTotal() {
        val lru = TileLru<String>(1000)
        lru.put("a", 100)
        lru.put("a", 300)
        assertEquals(300, lru.usedBytes)
        lru.remove("a")
        lru.remove("missing")
        assertEquals(0, lru.usedBytes)
        assertEquals(0, lru.size)
    }

    @Test fun budgetScalesWithMemoryClass() {
        assertEquals(24L shl 20, TileCache.budgetFor(32))
        assertEquals(64L shl 20, TileCache.budgetFor(192))
        assertEquals(192L shl 20, TileCache.budgetFor(1024))
    }
}

class TileReachTest {
    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float = 2f) =
        StrokeBuilder(BrushSpec.defaults(BrushKind.Fineliner).withWidth(width), -0x1000000, 1).apply {
            add(InputSample(x0, y0))
            add(InputSample(x1, y1))
        }.finish()

    @Test fun aDiagonalReachesOnlyTheTilesItCrosses() {
        val s = line(0f, 0f, 1000f, 1000f)
        assertTrue(TileCache.reaches(s, Box(0f, 0f, 256f, 256f)))
        assertTrue(TileCache.reaches(s, Box(512f, 512f, 768f, 768f)))
        assertFalse(TileCache.reaches(s, Box(768f, 0f, 1024f, 256f)), "inside the bounds, but far off the line")
    }

    @Test fun theBrushWidthCountsNearTheEdge() {
        val thin = line(0f, 254f, 1000f, 254f, width = 2f)
        val wide = line(0f, 240f, 1000f, 240f, width = 30f)
        val below = Box(0f, 256f, 256f, 512f)
        assertTrue(TileCache.reaches(thin, below), "within the antialiasing margin")
        assertTrue(TileCache.reaches(wide, below))
        assertFalse(TileCache.reaches(line(0f, 100f, 1000f, 100f), below))
    }

    @Test fun segmentBoxTest() {
        val b = Box(10f, 10f, 20f, 20f)
        assertTrue(TileCache.segmentHits(0f, 15f, 30f, 15f, b))
        assertTrue(TileCache.segmentHits(12f, 12f, 13f, 13f, b), "wholly inside")
        assertFalse(TileCache.segmentHits(0f, 0f, 30f, 5f, b))
        assertFalse(TileCache.segmentHits(0f, 25f, 25f, 40f, b))
        assertTrue(TileCache.segmentHits(15f, 0f, 15f, 30f, b), "vertical")
    }
}
