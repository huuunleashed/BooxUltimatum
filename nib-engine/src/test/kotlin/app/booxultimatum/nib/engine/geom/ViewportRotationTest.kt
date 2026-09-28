package app.booxultimatum.nib.engine.geom

import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class ViewportRotationTest {
    private val angles = listOf(0f, 0.2f, -0.7f, (PI / 2).toFloat(), PI.toFloat(), (-PI / 2).toFloat(), 2.5f, (PI / 4).toFloat())

    @Test
    fun roundTripsAtEveryAngle() {
        val rnd = Random(3)
        for (a in angles) repeat(60) {
            val v = Viewport(0.2f + rnd.nextFloat() * 6f, rnd.nextFloat() * 2000f - 1000f, rnd.nextFloat() * 2000f - 1000f, rotation = a)
            val p = Vec(rnd.nextFloat() * 3000f, rnd.nextFloat() * 3000f)
            val back = v.toDoc(v.toView(p))
            assertEquals(p.x, back.x, 2e-2f, "angle $a")
            assertEquals(p.y, back.y, 2e-2f, "angle $a")
            val m = v.docToView().map(p)
            val direct = v.toView(p)
            assertEquals(direct.x, m.x, 1e-2f)
            assertEquals(direct.y, m.y, 1e-2f)
            val inv = v.viewToDoc().map(direct)
            assertEquals(p.x, inv.x, 2e-2f)
            assertEquals(p.y, inv.y, 2e-2f)
        }
    }

    @Test
    fun quarterTurnsAreExact() {
        val v = Viewport(2f, 100f, 50f, rotation = (PI / 2).toFloat())
        // Clockwise on a y-down screen: +x in the document points down the view.
        assertEquals(Vec(100f, 52f), v.toView(Vec(1f, 0f)))
        assertEquals(Vec(98f, 50f), v.toView(Vec(0f, 1f)))
        assertEquals(90, v.rotationDegrees)
        assertEquals(180, Viewport(rotation = PI.toFloat()).rotationDegrees)
        assertEquals(270, Viewport(rotation = (-PI / 2).toFloat()).rotationDegrees)
    }

    @Test
    fun rotatingAroundAFocusKeepsThatPointStill() {
        val rnd = Random(4)
        repeat(200) {
            val v = Viewport(0.3f + rnd.nextFloat() * 4f, rnd.nextFloat() * 800f, rnd.nextFloat() * 800f, rotation = rnd.nextFloat() * 6f - 3f)
            val fx = rnd.nextFloat() * 1860f
            val fy = rnd.nextFloat() * 2480f
            val delta = rnd.nextFloat() * 4f - 2f
            val before = v.toDoc(Vec(fx, fy))
            val r = v.rotateAround(fx, fy, delta)
            val after = r.toDoc(Vec(fx, fy))
            assertEquals(before.x, after.x, 0.05f)
            assertEquals(before.y, after.y, 0.05f)
            assertEquals(v.scale, r.scale, "turning never zooms")
            assertTrue(r.rotation > -PI.toFloat() - 1e-6f && r.rotation <= PI.toFloat() + 1e-6f, "kept in range")
        }
    }

    @Test
    fun zoomAndPanWorkOnATurnedPage() {
        val v = Viewport(1f, 300f, 200f, rotation = 0.6f)
        val before = v.toDoc(Vec(500f, 700f))
        val z = v.zoomAround(500f, 700f, 2.5f)
        val after = z.toDoc(Vec(500f, 700f))
        assertEquals(before.x, after.x, 0.02f)
        assertEquals(before.y, after.y, 0.02f)
        assertEquals(0.6f, z.rotation)
        val p = v.pan(10f, -4f)
        val a = v.toView(Vec(40f, 40f))
        val b = p.toView(Vec(40f, 40f))
        assertEquals(a.x + 10f, b.x, 1e-3f)
        assertEquals(a.y - 4f, b.y, 1e-3f)
    }

    @Test
    fun fitStandsThePageUpright() {
        val turned = Viewport(3f, -50f, 900f, rotation = 1.1f)
        val fit = turned.fit(1860f, 2480f, 1000f, 1000f, margin = 20f)
        assertEquals(0f, fit.rotation)
        assertTrue(fit.isUpright)
        assertEquals(960f / 2480f, fit.scale, 1e-5f)
        val shown = fit.docRectToView(Box(0f, 0f, 1860f, 2480f))
        assertEquals(20f, shown.top, 0.01f)
        assertEquals(500f, shown.centerX, 0.01f)
    }

    @Test
    fun theUnrotatedFrameTimesTheTurnIsTheWholeMapping() {
        val rnd = Random(5)
        for (a in angles) {
            val v = Viewport(1.7f, 120f, -40f, rotation = a)
            val composed = v.turn().compose(v.unrotated().docToView())
            repeat(20) {
                val p = Vec(rnd.nextFloat() * 2000f, rnd.nextFloat() * 2000f)
                val want = v.toView(p)
                val got = composed.map(p)
                assertEquals(want.x, got.x, 2e-2f)
                assertEquals(want.y, got.y, 2e-2f)
            }
        }
    }

    @Test
    fun theVisibleAreaCoversEveryCornerOfTheView() {
        val v = Viewport(0.5f, 400f, 100f, rotation = 0.8f)
        val box = v.visibleDocRect(1000f, 800f)
        for ((x, y) in listOf(0f to 0f, 1000f to 0f, 1000f to 800f, 0f to 800f, 500f to 400f)) {
            val d = v.toDoc(Vec(x, y))
            assertTrue(d.x >= box.left - 0.01f && d.x <= box.right + 0.01f && d.y >= box.top - 0.01f && d.y <= box.bottom + 0.01f, "($x, $y)")
        }
    }

    @Test
    fun quarterTurnsSnapWithinTheirTolerance() {
        val five = Math.toRadians(5.0).toFloat()
        assertEquals((PI / 2).toFloat(), Viewport.snapToQuarter((PI / 2).toFloat() + 0.05f, five), 1e-6f)
        assertEquals(0f, Viewport.snapToQuarter(-0.07f, five))
        assertEquals(0.3f, Viewport.snapToQuarter(0.3f, five))
        assertTrue(abs(Viewport.snapToQuarter(PI.toFloat() - 0.02f, five) - PI.toFloat()) < 1e-6f)
        assertEquals(0f, Viewport.normalize((4 * PI).toFloat()), 1e-5f)
    }
}