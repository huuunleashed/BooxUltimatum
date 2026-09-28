package app.booxultimatum.nib.ui

import app.booxultimatum.nib.store.DrawingInfo
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryTest {
    private val list = listOf(
        DrawingInfo("a", "Harbour", modified = 3, width = 1, height = 1, bytes = 500),
        DrawingInfo("b", "apple tree", modified = 9, width = 1, height = 1, bytes = 90),
        DrawingInfo("c", "", modified = 5, width = 1, height = 1, bytes = 9000),
    )

    @Test fun theShelfSortsBySomethingUseful() {
        assertEquals(listOf("b", "c", "a"), LibrarySort.Recent.apply(list, "", "Untitled").map { it.id })
        assertEquals(listOf("b", "a", "c"), LibrarySort.Name.apply(list, "", "Untitled").map { it.id }, "any case; untitled sorts as its word")
        assertEquals(listOf("c", "a", "b"), LibrarySort.Size.apply(list, "", "Untitled").map { it.id })
        assertEquals(LibrarySort.Name, LibrarySort.of("name"))
        assertEquals(LibrarySort.Recent, LibrarySort.of("nonsense"))
    }

    @Test fun searchFindsNamesInAnyCase() {
        assertEquals(listOf("a"), LibrarySort.Recent.apply(list, "HARB", "Untitled").map { it.id })
        assertEquals(listOf("c"), LibrarySort.Recent.apply(list, "untit", "Untitled").map { it.id })
        assertEquals(emptyList(), LibrarySort.Recent.apply(list, "zzz", "Untitled"))
    }

    @Test fun pageSizesAreAtThePanelsResolution() {
        val p = PagePresets.all(1860, 2480).associateBy { it.key }
        assertEquals(1860 to 2480, p.getValue("panel_portrait").let { it.width to it.height })
        assertEquals(2480 to 1860, p.getValue("panel_landscape").let { it.width to it.height })
        assertEquals(2480 to 3508, p.getValue("a4").let { it.width to it.height })
        assertEquals(1748 to 2480, p.getValue("a5").let { it.width to it.height })
        assertEquals(2480 to 2480, p.getValue("square").let { it.width to it.height })
    }

    @Test fun customSizesAreReadInPixelsOrMillimetresAndChecked() {
        assertEquals(1000, PagePresets.custom(1000f, mm = false))
        assertEquals(2480, PagePresets.custom(210f, mm = true))
        assertNull(PagePresets.custom(10f, mm = false), "too small")
        assertNull(PagePresets.custom(900f, mm = true), "too large at 300 ppi")
        assertNull(PagePresets.custom(Float.NaN, mm = false))
        assertEquals(210f, PagePresets.pxToMm(2480), 0.1f)
    }
}