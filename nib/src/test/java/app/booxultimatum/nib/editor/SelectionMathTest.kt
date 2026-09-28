package app.booxultimatum.nib.editor

import app.booxultimatum.nib.engine.geom.Affine
import app.booxultimatum.nib.engine.geom.Box
import app.booxultimatum.nib.engine.geom.Vec
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SelectionMathTest {
    private val sel = Selection(1L, listOf(5L, 6L), Box(100f, 200f, 300f, 400f))

    private fun near(a: Vec, b: Vec, eps: Float = 1e-2f) = assertTrue(hypot(a.x - b.x, a.y - b.y) < eps, "$a is not $b")

    @Test fun movingShiftsTheFrame() {
        val moved = sel.transformed(SelectionMath.move(30f, -20f))
        val c = moved.corners()
        assertEquals(130f, c[0], 1e-3f)
        assertEquals(180f, c[1], 1e-3f)
        near(Vec(230f, 280f), moved.centre)
    }

    @Test fun scalingFromACornerKeepsTheOppositeCornerStill() {
        val c = sel.corners()
        // Drag the bottom right corner (2) from where it is to twice as far from the top left.
        val start = Vec(c[4], c[5])
        val a = SelectionMath.scaleFromCorner(c, 2, start, Vec(500f, 600f))
        near(Vec(100f, 200f), a.map(Vec(100f, 200f)))
        near(Vec(500f, 600f), a.map(start))
        // A drag off the diagonal is projected onto it: the scale stays uniform.
        val b = SelectionMath.scaleFromCorner(c, 2, start, Vec(500f, 400f))
        assertEquals(b.scaleX, b.scaleY)
        assertEquals(0f, b.skewX)
        // Dragging through the opposite corner never flips or collapses the strokes.
        val tiny = SelectionMath.scaleFromCorner(c, 2, start, Vec(0f, 0f))
        assertEquals(SelectionMath.MIN_SCALE, tiny.scaleX, 1e-6f)
    }

    @Test fun turningKeepsTheCentreStillAndSnapsToEighths() {
        val centre = sel.centre
        val start = Vec(centre.x, centre.y - 100f)
        val quarter = SelectionMath.rotate(centre, start, Vec(centre.x + 100f, centre.y))
        near(centre, quarter.map(centre))
        val p = quarter.map(Vec(centre.x + 10f, centre.y))
        assertEquals((PI / 2).toFloat(), atan2(p.y - centre.y, p.x - centre.x), 1e-3f)
        // 44° snaps to 45°; 30° stays 30°.
        val a44 = Math.toRadians(44.0)
        val snapped = SelectionMath.rotate(centre, Vec(centre.x + 100f, centre.y), Vec(centre.x + 100f * kotlin.math.cos(a44).toFloat(), centre.y + 100f * kotlin.math.sin(a44).toFloat()))
        assertEquals((PI / 4).toFloat(), atan2(snapped.skewY, snapped.scaleX), 1e-4f)
        val a30 = Math.toRadians(30.0)
        val free = SelectionMath.rotate(centre, Vec(centre.x + 100f, centre.y), Vec(centre.x + 100f * kotlin.math.cos(a30).toFloat(), centre.y + 100f * kotlin.math.sin(a30).toFloat()))
        assertEquals(a30.toFloat(), atan2(free.skewY, free.scaleX), 1e-4f)
        assertEquals(Affine.IDENTITY, SelectionMath.rotate(centre, start, start))
    }

    @Test fun framesAccumulateSoTheHandlesTurnWithTheStrokes() {
        val turned = sel.transformed(Affine.rotate((PI / 2).toFloat(), sel.centre.x, sel.centre.y))
        val c = turned.corners()
        // The top left corner of a 200 by 200 box turned a quarter about its centre goes to the top right.
        near(Vec(300f, 200f), Vec(c[0], c[1]))
        near(sel.centre, turned.centre)
    }

    @Test fun touchesFindTheirHandle() {
        val c = floatArrayOf(100f, 100f, 300f, 100f, 300f, 300f, 100f, 300f)
        val rot = SelectionMath.rotateHandle(c, 40f)
        near(Vec(200f, 60f), rot)
        assertIs<SelectionHandle.Rotate>(SelectionMath.hit(c, rot, 205f, 62f, 20f))
        assertEquals(SelectionHandle.Corner(2), SelectionMath.hit(c, rot, 295f, 310f, 20f))
        assertIs<SelectionHandle.Inside>(SelectionMath.hit(c, rot, 200f, 200f, 20f))
        assertIs<SelectionHandle.Outside>(SelectionMath.hit(c, rot, 500f, 200f, 20f))
        // A turned frame is a diamond: its bounding box's corner isn't inside it.
        val diamond = floatArrayOf(200f, 100f, 300f, 200f, 200f, 300f, 100f, 200f)
        assertIs<SelectionHandle.Outside>(SelectionMath.hit(diamond, Vec(0f, 0f), 115f, 115f, 5f))
        assertIs<SelectionHandle.Inside>(SelectionMath.hit(diamond, Vec(0f, 0f), 200f, 200f, 5f))
    }
}