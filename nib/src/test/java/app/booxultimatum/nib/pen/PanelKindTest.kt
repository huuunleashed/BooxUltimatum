package app.booxultimatum.nib.pen

import app.booxultimatum.kit.core.BooxSeries
import app.booxultimatum.kit.core.TabletProfile
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PanelKindTest {
    private fun tablet(model: String, boox: Boolean = true) =
        TabletProfile(boox, "ONYX", model, if (boox) BooxSeries.NoteAir else BooxSeries.NotBoox, "4.3", "16", "mt8188", null, emptyList())

    @Test fun colourModelsAreRecognisedByName() {
        for (m in listOf("NoteAir6C", "NoteAir4C", "Tab Ultra C", "TabUltraCPro", "Go Color 7", "Tab_Mini_C", "NovaAirC")) assertTrue(PanelKind.isColourModel(m), m)
        for (m in listOf("NoteAir3", "NoteMax", "Page", "Palma2", "Poke5", "TabX")) assertFalse(PanelKind.isColourModel(m), m)
    }

    @Test fun colourPanelsWaitLongerBeforeTheyReArm() {
        assertEquals(500L, PanelKind.rearmMs(tablet("NoteAir6C")))
        assertEquals(200L, PanelKind.rearmMs(tablet("NoteMax")))
        assertEquals(200L, PanelKind.rearmMs(tablet("Pixel C", boox = false)), "other tablets have no such panel")
    }
}
