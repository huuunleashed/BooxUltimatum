package app.booxultimatum.nib.engine.index

import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.doc.Layer
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.geom.Affine
import app.booxultimatum.nib.engine.geom.Box
import app.booxultimatum.nib.engine.line
import app.booxultimatum.nib.engine.scribble
import app.booxultimatum.nib.engine.stroke
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StrokeIndexTest {
    private fun randomStrokes(rnd: Random, n: Int): List<Stroke> = (0 until n).map { i ->
        val kind = BrushKind.entries[rnd.nextInt(16)]
        val x = rnd.nextFloat() * 1800f
        val y = rnd.nextFloat() * 2400f
        val size = 5f + rnd.nextFloat() * if (rnd.nextInt(20) == 0) 1500f else 150f
        scribble(rnd, i + 1L, kind, 1 + rnd.nextInt(60), x, y, x + size, y + size, step = size / 10f)
    }

    private fun ids(list: Collection<Stroke>) = list.map { it.id }.toSet()

    private fun randomPolygon(rnd: Random, cx: Float, cy: Float, r: Float): FloatArray {
        val n = 3 + rnd.nextInt(12)
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val a = i * 6.283f / n + rnd.nextFloat() * 0.3f
            val rr = r * (0.3f + rnd.nextFloat())
            out[2 * i] = cx + cos(a) * rr
            out[2 * i + 1] = cy + sin(a) * rr
        }
        return out
    }

    @Test
    fun queriesMatchBruteForce() {
        val rnd = Random(17)
        val strokes = randomStrokes(rnd, 600)
        val index = StrokeIndex(128f)
        strokes.forEach(index::insert)
        assertEquals(600, index.size)
        repeat(300) {
            val x = rnd.nextFloat() * 2000f - 100f
            val y = rnd.nextFloat() * 2600f - 100f
            val box = Box(x, y, x + rnd.nextFloat() * 600f + 1f, y + rnd.nextFloat() * 600f + 1f)
            assertEquals(ids(strokes.filter { it.bounds.intersects(box) }), ids(index.query(box)))
        }
        assertEquals(600, index.query(Box(-1e6f, -1e6f, 1e6f, 1e6f)).size, "a huge box sees everything")
    }

    @Test
    fun eraserHitsMatchBruteForce() {
        val rnd = Random(18)
        val strokes = randomStrokes(rnd, 500)
        val index = StrokeIndex()
        strokes.forEach(index::insert)
        repeat(200) {
            val n = 1 + rnd.nextInt(8)
            var x = rnd.nextFloat() * 1800f
            var y = rnd.nextFloat() * 2400f
            val path = FloatArray(n * 2)
            for (i in 0 until n) {
                path[2 * i] = x
                path[2 * i + 1] = y
                x += rnd.nextFloat() * 60f - 30f
                y += rnd.nextFloat() * 60f - 30f
            }
            val radius = 1f + rnd.nextFloat() * 30f
            val expected = strokes.filter { !it.brush.kind.isEraser && HitTest.hitsEraser(it, path, n, radius) }
            assertEquals(ids(expected), ids(index.hitByEraser(path, n, radius)))
            val withErasers = strokes.filter { HitTest.hitsEraser(it, path, n, radius) }
            assertEquals(ids(withErasers), ids(index.hitByEraser(path, n, radius, includeErasers = true)))
        }
    }

    @Test
    fun lassoMatchesBruteForce() {
        val rnd = Random(19)
        val strokes = randomStrokes(rnd, 500)
        val index = StrokeIndex()
        strokes.forEach(index::insert)
        repeat(200) {
            val poly = randomPolygon(rnd, rnd.nextFloat() * 1800f, rnd.nextFloat() * 2400f, 50f + rnd.nextFloat() * 400f)
            val n = poly.size / 2
            val expected = strokes.filter { !it.brush.kind.isEraser && HitTest.insideLasso(it, poly, n) }
            assertEquals(ids(expected), ids(index.insideLasso(poly, n)))
        }
    }

    @Test
    fun hitTestsAreExact() {
        val s = stroke(1, BrushKind.Fineliner, line(0f, 0f, 100f, 0f, 11), width = 10f)
        assertTrue(HitTest.hitsEraser(s, floatArrayOf(50f, 14f), 1, 9.5f), "5 + 9.5 reaches 14")
        assertFalse(HitTest.hitsEraser(s, floatArrayOf(50f, 14f), 1, 8.5f))
        assertTrue(HitTest.hitsEraser(s, floatArrayOf(50f, -40f, 50f, 40f), 2, 0.1f), "a crossing path hits")
        assertFalse(HitTest.hitsEraser(s, floatArrayOf(120f, -40f, 120f, 40f), 2, 10f))
        val dot = stroke(2, BrushKind.Fineliner, floatArrayOf(10f, 10f), width = 4f)
        assertTrue(HitTest.hitsEraser(dot, floatArrayOf(0f, 13f, 20f, 13f), 2, 1.5f))
        val square = floatArrayOf(-1f, -5f, 55f, -5f, 55f, 5f, -1f, 5f)
        assertEquals(6f / 11f, HitTest.lassoFraction(s, square, 4), 1e-6f)
        assertTrue(HitTest.insideLasso(s, square, 4))
        val smaller = floatArrayOf(-1f, -5f, 45f, -5f, 45f, 5f, -1f, 5f)
        assertFalse(HitTest.insideLasso(s, smaller, 4), "5 of 11 points is under half")
        assertEquals(0f, HitTest.lassoFraction(s, floatArrayOf(0f, 0f, 1f, 1f), 2))
    }

    @Test
    fun incrementalEditsAndSync() {
        val rnd = Random(20)
        val strokes = randomStrokes(rnd, 200)
        val index = StrokeIndex()
        strokes.forEach(index::insert)
        for (st in strokes.take(50)) assertTrue(index.remove(st.id))
        assertFalse(index.remove(strokes[0].id))
        assertEquals(150, index.size)
        val everything = Box(-1e5f, -1e5f, 1e5f, 1e5f)
        assertEquals(ids(strokes.drop(50)), ids(index.query(everything)))
        // Re-inserting a stroke with the same id replaces it.
        val moved = strokes[60].transformed(Affine.translate(5000f, 5000f))
        index.insert(moved)
        assertEquals(150, index.size)
        assertEquals(setOf(moved.id), ids(index.query(moved.bounds)))
        // Sync to layer versions: additions, removals and replacements.
        val layer = Layer(1, strokes = strokes.take(120))
        index.sync(layer)
        assertEquals(120, index.size)
        assertEquals(ids(strokes.take(120)), ids(index.query(everything)))
        val replaced = layer.strokes[20].copy(color = 0)
        val next = layer.copy(strokes = layer.strokes.drop(10).map { if (it.id == replaced.id) replaced else it } + strokes.drop(150))
        index.sync(next)
        assertEquals(ids(next.strokes), ids(index.strokes))
        for (st in next.strokes) assertTrue(index[st.id] === st)
        assertTrue(index[replaced.id] === replaced, "a replaced stroke is swapped in")
        index.clear()
        assertEquals(0, index.size)
        assertTrue(index.query(everything).isEmpty())
    }
}
