package app.booxultimatum.nib.engine.brush

import app.booxultimatum.nib.engine.input.PressureCurve
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class BrushSpecTest {
    @Test
    fun previewWidthScalesWithZoomAndHasAFloor() {
        val p = Preview(HardwareStyle.Fountain, widthFactor = 0.5f, minWidthPx = 1f)
        assertEquals(4f, p.widthPx(4f, 2f))
        assertEquals(8f, p.widthPx(4f, 4f))
        assertEquals(1f, p.widthPx(0.5f, 1f), "never thinner than the minimum")
        assertEquals(1f, p.widthPx(4f, 0f))
        assertEquals(Preview.DEFAULT_MIN_WIDTH_PX, Preview(HardwareStyle.Pencil).minWidthPx)
    }

    @Test
    fun thinPencilPreviewsKeepThePencilStyle() {
        val p = Preview(HardwareStyle.Pencil)
        assertEquals(HardwareStyle.Pencil, p.styleFor(0.5f, 1f), "clean from 0.5 to 3 px on the tablet (Widths · pencil probe)")
        assertEquals(HardwareStyle.Pencil, p.styleFor(2f, 2f))
        assertEquals(HardwareStyle.Pencil, p.styleFor(3f, 1f))
        assertEquals(HardwareStyle.SquarePen, Preview(HardwareStyle.SquarePen).styleFor(5f, 1f))
        assertEquals(HardwareStyle.SquarePen, Preview(HardwareStyle.SquarePen).styleFor(5f, 1f, verifiedOnly = true), "BOOX's calligraphy uses it")
        assertEquals(HardwareStyle.CharcoalV2, Preview(HardwareStyle.CharcoalV2).styleFor(1f, 1f, verifiedOnly = true), "BOOX's charcoal uses it")
        assertEquals(HardwareStyle.Dash, Preview(HardwareStyle.Dash).styleFor(5f, 1f))
        assertEquals(HardwareStyle.Fountain, Preview(HardwareStyle.Dash).styleFor(5f, 1f, verifiedOnly = true), "no BOOX pen uses the dash")
    }

    @Test
    fun onlyTheMarkerStyleIsTranslucent() {
        assertEquals(Preview.MARKER_ALPHA, Preview(HardwareStyle.Marker).alpha)
        assertEquals(255, Preview(HardwareStyle.Fountain).alpha)
        assertFailsWith<IllegalArgumentException> { Preview(HardwareStyle.Fountain, alpha = 128) }
        assertEquals(0x80FF0000.toInt(), Preview(HardwareStyle.Marker).argb(0x40FF0000))
        assertEquals(-0x1000000, Preview(HardwareStyle.Fountain, color = PreviewColor.Black).argb(0x12345678))
        assertEquals(-1, Preview(HardwareStyle.Fountain, color = PreviewColor.White).argb(0))
        assertEquals(0xFF345678.toInt(), Preview(HardwareStyle.Fountain).argb(0x12345678), "brush colour made opaque")
    }

    @Test
    fun hardwareStyles() {
        assertEquals((0..7).toList(), HardwareStyle.entries.map { it.code })
        assertEquals(HardwareStyle.entries.toSet() - HardwareStyle.Dash, HardwareStyle.entries.filter { it.verified }.toSet(), "every style BOOX's own pens use")
        assertEquals(setOf(HardwareStyle.Pencil, HardwareStyle.Fountain, HardwareStyle.Marker), HardwareStyle.entries.filter { it.seenOnTablet }.toSet())
        assertEquals(listOf(0, 1, 2, 3, 4, 6, 7), HardwareStyle.entries.filter { it.nativePen }.map { it.code })
        for (s in HardwareStyle.entries) {
            assertTrue(s.fallback.seenOnTablet)
            assertEquals(s, HardwareStyle.fromCode(s.code))
        }
    }

    @Test
    fun everyKindHasSaneDefaults() {
        for (kind in BrushKind.entries) {
            val b = BrushSpec.defaults(kind)
            assertEquals(kind, b.kind)
            assertTrue(b.width in b.widthRange, "$kind default width in range")
            assertTrue(b.opacity in 0f..1f)
            assertTrue(b.smoothing in 0f..1f)
            assertTrue(b.preview.alpha == 255 || b.preview.style == HardwareStyle.Marker, "$kind preview opaque")
            assertEquals(b.widthRange.start, b.withWidth(-5f).width)
            assertEquals(b.widthRange.endInclusive, b.withWidth(1e6f).width)
            assertEquals(b.width, b.withWidth(Float.NaN).width)
        }
        assertEquals(0.5f..40f, BrushSpec.widthRange(BrushKind.Fineliner))
        assertEquals(4f..200f, BrushSpec.widthRange(BrushKind.Marker))
        assertTrue(BrushSpec.defaults(BrushKind.Fineliner).curve.isConstant)
        assertFalse(BrushSpec.defaults(BrushKind.Fountain).curve.isConstant)
        assertEquals(128f / 255f, BrushSpec.defaults(BrushKind.Marker).opacity)
        assertEquals(Blend.Multiply, BrushSpec.defaults(BrushKind.Highlighter).blend)
        assertEquals(Blend.Erase, BrushSpec.defaults(BrushKind.PixelEraser).blend)
        assertEquals(HardwareStyle.SquarePen, BrushSpec.defaults(BrushKind.Calligraphy).preview.style)
        assertEquals(HardwareStyle.Pencil, BrushSpec.defaults(BrushKind.Graphite).preview.style)
        assertEquals(PreviewColor.White, BrushSpec.defaults(BrushKind.PixelEraser).preview.color)
        assertEquals(8f, BrushSpec.defaults(BrushKind.Fountain).copy(width = 16f).maxRadius)
        assertEquals(1f, BrushSpec.defaults(BrushKind.Fountain).copy(width = 16f, curve = PressureCurve(1f, 0f, 0.1f)).maxRadius, "never below the 2 px floor")
        assertEquals(4f * (1f + 3f * TiltShading.MAX_BROADENING), BrushSpec.defaults(BrushKind.Charcoal).copy(width = 8f).maxRadius, 1e-5f, "a charcoal laid flat reaches 7.9 times as far")
        assertEquals(2f, BrushSpec.defaults(BrushKind.Graphite).copy(width = 4f).maxRadius, 1e-5f, "graphite doesn't tilt unless tuned")
        assertEquals(5f, BrushSpec.defaults(BrushKind.Graphite).copy(width = 4f, tiltScale = 2.5f, tiltResponse = TiltResponse.Eased).maxRadius, 1e-5f, "a 0.3.0-test graphite's eased tilt reaches its tilt scale")
        assertEquals(2f, BrushSpec.defaults(BrushKind.Fountain).widthFloor)
        assertEquals(1f, BrushSpec.defaults(BrushKind.Fountain).copy(width = 1f).widthFloor, "no wider than the pen")
        assertEquals(0f, BrushSpec.defaults(BrushKind.Fineliner).widthFloor)
        val pencil = BrushSpec.defaults(BrushKind.Pencil).copy(width = 2f)
        val thin = pencil.hardwarePreview(0x80123456.toInt(), 1f)
        assertEquals(HardwarePreview(HardwareStyle.Pencil, 2f, 0xFF123456.toInt(), emptyList()), thin, "a thin pencil keeps its own style, which takes no parameters")
        assertEquals(HardwareStyle.Pencil, pencil.hardwarePreview(0, 2f).style)
        val marker = BrushSpec.defaults(BrushKind.Marker).hardwarePreview(0xFF00FF00.toInt(), 0.5f)
        assertEquals(HardwarePreview(HardwareStyle.Marker, 8f, 0xFF00FF00.toInt()), marker, "solid colour, no parameters")
        assertEquals(0x8096_9696.toInt(), BrushSpec.defaults(BrushKind.Marker).hardwarePreview(0xFF00FF00.toInt(), 1f, marker = MarkerPreview.SeeThroughGrey).argb)
        assertEquals(HardwareStyle.SquarePen, BrushSpec.defaults(BrushKind.Calligraphy).hardwarePreview(0, 1f, verifiedOnly = true).style)
        assertEquals(HardwareStyle.Fountain, BrushSpec.defaults(BrushKind.Dash).hardwarePreview(0, 1f, verifiedOnly = true).style)
    }

    @Test
    fun catalogListsEveryKindOnceWithStableIds() {
        assertEquals(BrushKind.entries.toSet(), BrushCatalog.kinds.toSet())
        assertEquals(BrushKind.entries.size, BrushCatalog.kinds.size)
        assertEquals(BrushCatalog.entries.size, BrushCatalog.entries.map { it.id }.toSet().size)
        assertEquals("fineliner", BrushCatalog.entries.first().id)
        assertEquals(BrushKind.LassoEraser, BrushCatalog.kinds.last())
        for (e in BrushCatalog.entries) {
            assertEquals(e.kind, BrushKind.fromId(e.id))
            assertNotNull(BrushCatalog.byId(e.id))
            assertTrue(e.id.matches(Regex("[a-z0-9_]+")))
        }
        assertTrue(BrushCatalog.entries.filter { it.isEraser }.map { it.kind }.containsAll(listOf(BrushKind.PixelEraser, BrushKind.StrokeEraser, BrushKind.LassoEraser)))
        assertFalse(BrushKind.StrokeEraser.isRendered)
        assertTrue(BrushKind.PixelEraser.isRendered)
        assertEquals(Blend.Atop, Blend.fromCode(3))
        assertEquals(Blend.Normal, Blend.fromCode(99))
    }
}
