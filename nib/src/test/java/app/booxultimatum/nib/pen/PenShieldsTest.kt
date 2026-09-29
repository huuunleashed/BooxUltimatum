package app.booxultimatum.nib.pen

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PenShieldsTest {
    @Test fun everyControlIsAnAreaOnScreen() {
        val s = PenShields()
        s.set("rail", 12f, 300.4f, 120.6f, 1400f)
        s.set("pill", 1500f, 12f, 1848f, 110f)
        val areas = s.areas(0, 60)
        assertEquals(2, areas.size)
        assertContentEquals(intArrayOf(12, 360, 121, 1460), areas[0], "rounded outwards, moved by the window's place")
        assertTrue(s.covers(50f, 500f))
        assertFalse(s.covers(500f, 500f))
    }

    @Test fun movesAndRemovalsAreReportedOnlyWhenSomethingChanged() {
        val s = PenShields()
        var moved = 0
        s.onAreasChanged = { moved++ }
        s.set("a", 0f, 0f, 10f, 10f)
        s.set("a", 0f, 0f, 10f, 10f)
        s.set("a", 5f, 0f, 15f, 10f)
        s.remove("a")
        s.remove("a")
        assertEquals(3, moved)
        assertEquals(0, s.size)
    }

    @Test fun changedControlsAreTakenOnceEach() {
        val s = PenShields()
        var changes = 0
        s.onContentChanged = { changes++ }
        s.set("undo", 0f, 0f, 100f, 50f)
        s.set("rail", 0f, 100f, 60f, 900f)
        s.changed("undo")
        s.changed("undo")
        s.changed("unknown")
        assertEquals(1, changes, "one notice until the change is taken")
        assertTrue(s.hasChanged)
        assertEquals(listOf(listOf(0, 0, 100, 50)), s.takeChanged().map { it.toList() })
        assertFalse(s.hasChanged)
        assertTrue(s.takeChanged().isEmpty())
        s.changed("rail")
        s.remove("rail")
        assertFalse(s.hasChanged, "a control that went has nothing to push")
    }

    @Test fun theScreenAroundASurfaceIsUpToFourStrips() {
        val window = intArrayOf(0, 0, 1860, 2480)
        val strips = ScreenAreas.around(intArrayOf(0, 60, 1860, 2480), window)
        assertEquals(listOf(listOf(0, 0, 1860, 60)), strips.map { it.toList() }, "the status strip above the canvas")
        val probe = ScreenAreas.around(intArrayOf(100, 300, 1400, 2300), window)
        assertEquals(4, probe.size)
        assertTrue(ScreenAreas.around(window, window).isEmpty())
    }

    @Test fun rectangleHelpers() {
        assertNull(ScreenAreas.union(null, null))
        assertContentEquals(intArrayOf(0, 0, 30, 40), ScreenAreas.union(intArrayOf(0, 10, 20, 40), intArrayOf(5, 0, 30, 20)))
        assertContentEquals(intArrayOf(-2, -2, 12, 12), ScreenAreas.inflate(intArrayOf(0, 0, 10, 10), 2))
        assertEquals(200L, ScreenAreas.pixels(intArrayOf(0, 0, 10, 20)))
        assertEquals(0L, ScreenAreas.pixels(intArrayOf(10, 0, 0, 20)))
    }
}
