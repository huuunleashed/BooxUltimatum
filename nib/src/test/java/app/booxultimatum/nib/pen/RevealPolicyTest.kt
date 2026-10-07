package app.booxultimatum.nib.pen

import app.booxultimatum.kit.ink.canvas.InkCanvasController.Reveal
import app.booxultimatum.nib.brush.BrushPreset
import app.booxultimatum.nib.brush.BrushTune
import app.booxultimatum.nib.brush.PressurePreset
import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.brush.BrushCatalog
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.brush.MarkerPreview
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RevealPolicyTest {
    private val black = 0xFF000000.toInt()
    private val red = 0xFFD2232A.toInt()

    private fun faithful(kind: BrushKind, color: Int = black) = RevealPolicy.faithful(BrushSpec.defaults(kind), color)

    @Test fun theNativeMatchedPensWaitForBreaks() {
        val native = listOf(
            BrushKind.Fineliner, BrushKind.Fountain, BrushKind.Ballpoint, BrushKind.Marker, BrushKind.BrushPen, BrushKind.NeoBrush,
            BrushKind.Calligraphy, BrushKind.CalligraphyAsian, BrushKind.SquarePen, BrushKind.Pencil,
        )
        for (k in native) assertTrue(faithful(k), k.id)
        for (k in native) assertEquals(Reveal.AtBreaks, RevealPolicy.effective(RevealChoice.Auto, BrushSpec.defaults(k), black), k.id)
    }

    @Test fun texturedBlendedAndStandInPensWaitForAPause() {
        val pushed = listOf(BrushKind.GrainPencil, BrushKind.Graphite, BrushKind.Charcoal, BrushKind.CharcoalV2, BrushKind.Airbrush, BrushKind.Highlighter, BrushKind.Dash)
        for (k in pushed) {
            assertFalse(faithful(k), k.id)
            assertEquals(Reveal.AfterPause, RevealPolicy.effective(RevealChoice.Auto, BrushSpec.defaults(k), black), k.id)
        }
    }

    @Test fun everyBrushInTheCatalogueIsDecided() {
        val decided = BrushCatalog.kinds.filter { !it.isEraser }.associateWith { faithful(it) }
        assertEquals(10, decided.count { it.value }, "the nine native-matched pens and the stipple pencil")
    }

    @Test fun seeThroughInkIsPushed() {
        val fountain = BrushPreset(BrushKind.Fountain, 3f, PressurePreset.Medium, black, BrushTune(opacity = 0.5f)).spec()
        assertFalse(RevealPolicy.faithful(fountain, black), "the owner made it translucent")
        assertFalse(faithful(BrushKind.Fountain, 0x80000000.toInt()), "a translucent colour")
        assertTrue(faithful(BrushKind.Ballpoint), "the ballpoint's own lighter ink is its native look")
    }

    @Test fun aLayerThatChangesTheInkIsPushed() {
        val spec = BrushSpec.defaults(BrushKind.Fountain)
        assertFalse(RevealPolicy.faithful(spec, black, RevealPolicy.LayerLook(opacity = 0.5f)))
        assertFalse(RevealPolicy.faithful(spec, black, RevealPolicy.LayerLook(blend = Blend.Multiply)))
        assertFalse(RevealPolicy.faithful(spec, black, RevealPolicy.LayerLook(alphaLock = true)))
        assertTrue(RevealPolicy.faithful(spec, black, RevealPolicy.LayerLook()))
    }

    @Test fun aSeeThroughGreyMarkerPreviewOfAColourIsPushed() {
        val marker = BrushSpec.defaults(BrushKind.Marker)
        assertTrue(RevealPolicy.faithful(marker, red, marker = MarkerPreview.SolidColour))
        assertFalse(RevealPolicy.faithful(marker, red, marker = MarkerPreview.SeeThroughGrey), "the preview is grey, the ink red")
        assertTrue(RevealPolicy.faithful(marker, 0xFF808080.toInt(), marker = MarkerPreview.SeeThroughGrey), "a grey marker previews as itself")
    }

    @Test fun trustingTheDashStyleStillLeavesItUnmatched() {
        assertFalse(RevealPolicy.faithful(BrushSpec.defaults(BrushKind.Dash), black, verifiedOnly = false), "no BOOX pen previews in the dash style")
    }

    @Test fun theOtherChoicesAreTakenAsTheyAre() {
        val charcoal = BrushSpec.defaults(BrushKind.Charcoal)
        assertEquals(Reveal.AtBreaks, RevealPolicy.effective(RevealChoice.AtBreaks, charcoal, black))
        assertEquals(Reveal.AfterPause, RevealPolicy.effective(RevealChoice.AfterPause, BrushSpec.defaults(BrushKind.Fountain), black))
        assertEquals(Reveal.EveryStroke, RevealPolicy.effective(RevealChoice.EveryStroke, charcoal, black))
    }

    @Test fun choicesSurviveTheirIdsAndThePauseIsClamped() {
        for (c in RevealChoice.entries) assertEquals(c, RevealChoice.of(c.id))
        assertEquals(RevealChoice.Auto, RevealChoice.of(null))
        assertEquals(RevealChoice.Auto, RevealChoice.of("nonsense"))
        assertEquals(400, RevealPolicy.clampPause(10))
        assertEquals(2000, RevealPolicy.clampPause(9000))
        assertEquals(800, RevealPolicy.clampPause(RevealPolicy.DEFAULT_PAUSE_MS))
    }
}
