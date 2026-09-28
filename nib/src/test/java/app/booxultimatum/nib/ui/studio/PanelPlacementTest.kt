package app.booxultimatum.nib.ui.studio

import app.booxultimatum.nib.MemoryPrefs
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PanelPlacementTest {
    private val portrait = Area(0f, 0f, 1860f, 2400f)
    private val landscape = Area(0f, 0f, 2480f, 1780f)
    private val margin = 24f
    private val gap = 24f

    private fun inside(s: Spot, w: Float, h: Float, a: Area) =
        s.x >= a.left + margin - 0.01f && s.y >= a.top + margin - 0.01f && s.x + w <= a.right - margin + 0.01f && s.y + h <= a.bottom - margin + 0.01f

    @Test fun aPanelOpensBesideItsKey() {
        // A key in the top right pill: the panel hangs below it, its right edge lined up with the key's.
        val key = Area(1700f, 24f, 1800f, 124f)
        val s = PanelPlacement.place(900f, 1200f, portrait, key, null, margin, gap)
        assertEquals(1800f - 900f, s.x)
        assertEquals(124f + gap, s.y)
        // A key on the left rail: the panel sits to its right, level with it.
        val rail = Area(24f, 800f, 124f, 900f)
        val r = PanelPlacement.place(900f, 1000f, portrait, rail, null, margin, gap)
        assertEquals(124f + gap, r.x)
        assertEquals(800f, r.y)
        // A key in the bottom band (the view chip): the panel stands above it, centred on it.
        val chip = Area(800f, 2280f, 1000f, 2380f)
        val c = PanelPlacement.place(700f, 600f, portrait, chip, null, margin, gap)
        assertEquals(900f - 350f, c.x)
        assertEquals(2280f - gap - 600f, c.y)
        // The same key when the rail sits on the right: the panel goes to its left.
        val right = Area(1736f, 800f, 1836f, 900f)
        assertEquals(1736f - gap - 900f, PanelPlacement.place(900f, 1000f, portrait, right, null, margin, gap).x)
    }

    @Test fun panelsAlwaysStayWhollyOnScreen() {
        val low = Area(24f, 2200f, 124f, 2300f)
        val s = PanelPlacement.place(900f, 1000f, portrait, low, null, margin, gap)
        assertTrue(inside(s, 900f, 1000f, portrait), "$s")
        val far = PanelPlacement.place(900f, 1000f, portrait, null, Spot(5000f, -300f), margin, gap)
        assertTrue(inside(far, 900f, 1000f, portrait), "a spot left off screen is pulled back: $far")
        // A panel taller than the screen keeps its top on it.
        val tall = PanelPlacement.clamp(Spot(100f, 400f), 900f, 3000f, landscape, margin)
        assertEquals(margin, tall.y)
    }

    @Test fun whereAPanelWasLeftIsKeptPerOrientation() {
        val memory = PanelMemory(MemoryPrefs())
        assertNull(memory.spot("layers", landscape = false))
        memory.setSpot("layers", landscape = false, Spot(300f, 500f))
        assertEquals(Spot(300f, 500f), memory.spot("layers", landscape = false))
        assertNull(memory.spot("layers", landscape = true), "landscape keeps its own place")
        memory.setSpot("layers", landscape = true, Spot(900f, 80f))
        // A place saved in the wide landscape frame still lands inside portrait's narrower one.
        val px = memory.spot("layers", landscape = true)!!
        val s = PanelPlacement.place(900f, 1000f, portrait, null, Spot(px.x * 2f, px.y * 2f), margin, gap)
        assertTrue(inside(s, 900f, 1000f, portrait))
        memory.setPinned("layers", true)
        assertTrue(memory.pinned("layers"))
        memory.forgetSpots()
        assertNull(memory.spot("layers", landscape = false))
        assertNull(memory.spot("layers", landscape = true))
        assertTrue(memory.pinned("layers"), "putting panels back keeps their pins")
    }
}