package app.booxultimatum.nib.ui

import app.booxultimatum.nib.engine.doc.Layer
import org.junit.Test
import kotlin.test.assertEquals

class LayoutAndNamesTest {
    @Test fun libraryFitsCardsToTheScreen() {
        assertEquals(3, LibraryLayout.columns(992f), "portrait Note Air6 C")
        assertEquals(5, LibraryLayout.columns(1323f), "landscape")
        assertEquals(2, LibraryLayout.columns(300f), "never fewer than two")
        assertEquals(3, LibraryLayout.rows(1100f))
        assertEquals(1, LibraryLayout.rows(100f))
    }

    @Test fun pagesRoundUp() {
        assertEquals(1, LibraryLayout.pages(0, 9))
        assertEquals(1, LibraryLayout.pages(9, 9))
        assertEquals(2, LibraryLayout.pages(10, 9))
    }

    @Test fun newLayersTakeTheNextFreeNumber() {
        val name: (Int) -> String = { "Layer $it" }
        assertEquals("Layer 2", nextLayerName(listOf(Layer(1, "Layer 1")), name))
        assertEquals("Layer 4", nextLayerName(listOf(Layer(1, "Layer 1"), Layer(2, "Layer 3"), Layer(3, "Sky")), name))
        assertEquals("Layer 3", nextLayerName(listOf(Layer(1, "Layer 1"), Layer(5, "Layer 2")), name))
    }

    @Test fun routesSurviveTheirSavedForm() {
        for (r in listOf(Route.Library, Route.Editor("3f2a-11"), Route.Diagnostics, Route.MatchPreview, Route.About, Route.Settings)) {
            assertEquals(r, Route.decode(r.encode()))
        }
        assertEquals(Route.Library, Route.decode("garbage"))
    }

    @Test fun zoomReadout() {
        assertEquals("100 %", zoomLabel(1f))
        assertEquals("85 %", zoomLabel(0.849f))
        assertEquals("1600 %", zoomLabel(16f))
        assertEquals("85 % · 12°", viewLabel(app.booxultimatum.nib.engine.geom.Viewport(0.85f, rotation = Math.toRadians(12.0).toFloat())))
        assertEquals("100 % · 270°", viewLabel(app.booxultimatum.nib.engine.geom.Viewport(1f, rotation = (-Math.PI / 2).toFloat())))
    }
}
