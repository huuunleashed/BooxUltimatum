package app.booxultimatum.nib.brush

import app.booxultimatum.nib.engine.brush.BrushCatalog
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.brush.HardwareStyle
import app.booxultimatum.nib.engine.input.PressureCurve
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresetsTest {
    @Test fun presetsSurviveTheirTextForm() {
        for (p in BrushPreset.DEFAULTS + BrushPreset(BrushKind.CharcoalV2, 12f, PressurePreset.Firm, 0xFF6D2A9E.toInt())) {
            assertEquals(p, BrushPreset.decode(p.encode()))
        }
    }

    @Test fun damagedOrForeignPresetsAreRefused() {
        assertNull(BrushPreset.decode(null))
        assertNull(BrushPreset.decode(""))
        assertNull(BrushPreset.decode("nonsense:1:medium:ff000000"))
        assertNull(BrushPreset.decode("fountain:abc:medium:ff000000"))
        assertNull(BrushPreset.decode("pixel_eraser:10:medium:ff000000"), "erasers don't go in pen slots")
        assertEquals(40f, BrushPreset.decode("fineliner:900:medium:ff000000")?.width, "widths are clamped to the brush")
        assertEquals(PressurePreset.Medium, BrushPreset.decode("fountain:3:unknown:ff000000")?.pressure)
    }

    @Test fun thePresetSpecIsTheBrushAtItsWidthAndCurve() {
        val p = BrushPreset(BrushKind.Fountain, 0.5f, PressurePreset.Firm, 0xFF000000.toInt())
        val s = p.spec()
        assertEquals(BrushKind.Fountain, s.kind)
        assertEquals(0.5f, s.width)
        assertEquals(PressurePreset.Firm.curve(BrushKind.Fountain), s.curve)
    }

    @Test fun pressurePresetsShapeOnlyPressureBrushes() {
        assertFalse(PressurePreset.applies(BrushKind.Fineliner))
        assertEquals(PressureCurve.CONSTANT, PressurePreset.Soft.curve(BrushKind.Fineliner))
        val medium = PressurePreset.Medium.curve(BrushKind.Fountain)
        assertEquals(BrushSpec.defaults(BrushKind.Fountain).curve, medium)
        val soft = PressurePreset.Soft.curve(BrushKind.Fountain)
        val firm = PressurePreset.Firm.curve(BrushKind.Fountain)
        assertTrue(soft.factor(0.3f) > medium.factor(0.3f), "soft: a light touch goes further")
        assertTrue(firm.factor(0.3f) < medium.factor(0.3f), "firm: a light touch stays thin")
        assertEquals(medium.factor(1f), firm.factor(1f), "full pressure is full width either way")
    }

    @Test fun everyNonEraserBrushSitsInExactlyOneGroup() {
        for (kind in BrushCatalog.kinds.filter { !it.isEraser }) {
            assertEquals(1, BrushGroup.entries.count { kind in it.kinds }, kind.id)
        }
        assertTrue(BrushGroup.entries.none { g -> g.kinds.any { it.isEraser } })
    }

    @Test fun unverifiedStylesUseAVerifiedStandIn() {
        val charcoal = BrushSpec.defaults(BrushKind.Charcoal)
        assertEquals(HardwareStyle.Pencil, PreviewPolicy.standIn(charcoal, tryUnverified = false))
        val p = PreviewPolicy.preview(charcoal, 0xFF000000.toInt(), 1f, tryUnverified = false)
        assertEquals(HardwareStyle.Pencil.code, p.style)
        assertEquals(HardwareStyle.Charcoal.code, PreviewPolicy.preview(charcoal, 0xFF000000.toInt(), 1f, tryUnverified = true).style)
        assertNull(PreviewPolicy.standIn(charcoal, tryUnverified = true))

        val square = BrushSpec.defaults(BrushKind.SquarePen)
        assertEquals(HardwareStyle.Fountain, PreviewPolicy.standIn(square, false))
        assertNull(PreviewPolicy.standIn(BrushSpec.defaults(BrushKind.Fountain), false), "verified styles need no stand-in")
    }

    @Test fun previewWidthFollowsTheZoom() {
        val b = BrushSpec.defaults(BrushKind.Fineliner).withWidth(2f)
        assertEquals(2f, PreviewPolicy.preview(b, -0x1000000, 1f, false).widthPx)
        assertEquals(8f, PreviewPolicy.preview(b, -0x1000000, 4f, false).widthPx)
        assertNotNull(PreviewPolicy.preview(b, -0x1000000, 0.25f, false).widthPx.takeIf { it >= 1f }, "never thinner than the minimum")
    }

    @Test fun previewColourIsOpaqueExceptTheMarker() {
        val red = 0x80D2232A.toInt()
        assertEquals(0xFF, PreviewPolicy.preview(BrushSpec.defaults(BrushKind.Fountain), red, 1f, false).argb ushr 24)
        assertEquals(128, PreviewPolicy.preview(BrushSpec.defaults(BrushKind.Marker), red, 1f, false).argb ushr 24)
    }
}
