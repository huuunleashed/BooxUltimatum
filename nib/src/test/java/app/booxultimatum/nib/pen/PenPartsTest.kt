package app.booxultimatum.nib.pen

import app.booxultimatum.kit.ink.epd.Epd
import app.booxultimatum.kit.ink.session.InkStroke
import app.booxultimatum.nib.brush.PreviewPolicy
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.brush.PenButtons
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PenPartsTest {
    private val black = 0xFF000000.toInt()

    @Test fun theTipIsTheStrokeTheSessionPreviews() {
        val stroke = PreviewPolicy.preview(BrushSpec.defaults(BrushKind.Fountain).withWidth(4f), black, 2f, tryUnverified = false)
        val tip = PenParts.tip(stroke)
        assertEquals(Epd.PenButton.TIP, tip.button)
        assertEquals(stroke.style, tip.style)
        assertEquals(stroke.widthPx, tip.widthPx)
        assertEquals(stroke.argb, tip.argb)
        assertEquals(stroke.params!!.toList(), tip.params, "the fountain's own sensitivity and smoothing")
        assertFalse(tip.eraserPreview)
        assertEquals(0, tip.eraserPainter)
    }

    @Test fun theEraserEndPreviewsAnEraserTrackAtItsWidthOnScreen() {
        val eraser = BrushSpec.defaults(BrushKind.PixelEraser).withWidth(20f)
        val cap = PenParts.of(PenButtons.eraserEnd(eraser, 1.5f), Epd.PenButton.CAP)
        assertEquals(Epd.PenButton.CAP, cap.button)
        assertEquals(PenButtons.ERASER_STYLE, cap.style)
        assertEquals(30f, cap.widthPx)
        assertEquals(listOf(30f, PenButtons.ERASER_PARAM_1, PenButtons.ERASER_PARAM_2), cap.params)
        assertTrue(cap.eraserPreview)
        assertEquals(PenParts.ERASER_PAINTER, cap.eraserPainter)
        assertEquals(8, cap.eraserPainter)
    }

    @Test fun theDisplaysFormCarriesEveryField() {
        val cap = PenParts.of(PenButtons.eraserEnd(24f), Epd.PenButton.CAP).toButton()
        assertEquals(Epd.PenButton.CAP, cap.button)
        assertEquals(8, cap.style)
        assertEquals(24f, cap.widthPx)
        assertContentEquals(floatArrayOf(24f, 0.5f, 0.1f), cap.params)
        assertTrue(cap.eraserPreview)
        assertEquals(8, cap.eraserPainter)
        val pencil = PenParts.tip(InkStroke(0, 3f, black)).toButton()
        assertNull(pencil.params, "a style without parameters sends none")
    }

    @Test fun bothPartsInTheNativeOrderAndComparable() {
        val tip = InkStroke.lasso()
        val a = PenParts.both(tip, PenButtons.eraserEnd(12f))
        assertEquals(listOf(Epd.PenButton.TIP, Epd.PenButton.CAP), a.map { it.button })
        assertEquals(a, PenParts.both(InkStroke.lasso(), PenButtons.eraserEnd(12f)), "unchanged parts aren't sent again")
        assertNotEquals(a, PenParts.both(tip, PenButtons.eraserEnd(13f)))
    }
}
