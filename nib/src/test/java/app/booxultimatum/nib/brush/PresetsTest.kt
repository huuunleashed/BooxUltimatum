package app.booxultimatum.nib.brush

import app.booxultimatum.nib.engine.brush.BrushCatalog
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.brush.HardwareStyle
import app.booxultimatum.nib.engine.brush.MarkerPreview
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

    @Test fun untrustedStylesUseAVerifiedStandIn() {
        val charcoal = BrushSpec.defaults(BrushKind.Charcoal)
        assertNull(PreviewPolicy.standIn(charcoal, tryUnverified = false), "BOOX's own charcoal previews in this style")
        assertEquals(HardwareStyle.Charcoal.code, PreviewPolicy.preview(charcoal, 0xFF000000.toInt(), 1f, tryUnverified = false).style)
        assertNull(PreviewPolicy.standIn(BrushSpec.defaults(BrushKind.SquarePen), false))

        val dash = BrushSpec.defaults(BrushKind.Dash)
        assertEquals(HardwareStyle.Fountain, PreviewPolicy.standIn(dash, tryUnverified = false), "no BOOX pen previews in the dash style")
        assertEquals(HardwareStyle.Fountain.code, PreviewPolicy.preview(dash, 0xFF000000.toInt(), 1f, tryUnverified = false).style)
        assertEquals(HardwareStyle.Dash.code, PreviewPolicy.preview(dash, 0xFF000000.toInt(), 1f, tryUnverified = true).style)
        assertNull(PreviewPolicy.standIn(dash, tryUnverified = true))
        assertNull(PreviewPolicy.standIn(BrushSpec.defaults(BrushKind.Fountain), false), "verified styles need no stand-in")
    }

    @Test fun previewWidthFollowsTheZoom() {
        val b = BrushSpec.defaults(BrushKind.Fineliner).withWidth(2f)
        assertEquals(2f, PreviewPolicy.preview(b, -0x1000000, 1f, false).widthPx)
        assertEquals(8f, PreviewPolicy.preview(b, -0x1000000, 4f, false).widthPx)
        assertNotNull(PreviewPolicy.preview(b, -0x1000000, 0.25f, false).widthPx.takeIf { it >= 1f }, "never thinner than the minimum")
    }

    @Test fun previewColourIsOpaqueExceptTheSeeThroughMarker() {
        val red = 0x80D2232A.toInt()
        assertEquals(0xFF, PreviewPolicy.preview(BrushSpec.defaults(BrushKind.Fountain), red, 1f, false).argb ushr 24)
        assertEquals(128, PreviewPolicy.preview(BrushSpec.defaults(BrushKind.Marker), red, 1f, false, marker = MarkerPreview.SeeThroughGrey).argb ushr 24)
    }

    @Test fun previewsCarryTheBrushsOwnStyleParameters() {
        val fountain = PreviewPolicy.preview(BrushSpec.defaults(BrushKind.Fountain), -0x1000000, 1f, false)
        assertEquals(listOf(PressureCurve.NATIVE_FOUNTAIN_SENSITIVITY, BrushSpec.NATIVE_FOUNTAIN_SMOOTHING), fountain.params?.toList())
        assertNull(PreviewPolicy.preview(BrushSpec.defaults(BrushKind.Marker), -0x1000000, 1f, false).params, "the marker's own are left alone")
        val thinFineliner = PreviewPolicy.preview(BrushSpec.defaults(BrushKind.Fineliner).withWidth(1f), -0x1000000, 1f, false)
        assertEquals(0, thinFineliner.style, "a thin fineliner keeps the pencil style, which draws clean thin lines")
        assertNull(thinFineliner.params, "the pencil style takes no parameters")
        val charcoalAsPencil = PreviewPolicy.preview(BrushSpec.defaults(BrushKind.Charcoal), -0x1000000, 1f, false)
        assertEquals(4, charcoalAsPencil.style)
        val calligraphy = PreviewPolicy.preview(BrushSpec.defaults(BrushKind.Calligraphy), -0x1000000, 1f, false)
        val turned = PreviewPolicy.preview(BrushSpec.defaults(BrushKind.Calligraphy), -0x1000000, 1f, false, viewRotation = (Math.PI / 4).toFloat())
        assertEquals(HardwareStyle.SquarePen.code, calligraphy.style)
        assertEquals(45f, calligraphy.params!![2], 0.01f)
        assertEquals(0f, turned.params!![2], 0.01f, "the nib keeps its angle to the paper on a turned page")
    }

    @Test fun markerPreviewsFollowTheMarkerSetting() {
        val red = 0xFFD2232A.toInt()
        val marker = BrushSpec.defaults(BrushKind.Marker)
        assertEquals(red, PreviewPolicy.preview(marker, red, 1f, false).argb, "solid colour by default")
        assertEquals(0x80585858.toInt(), PreviewPolicy.preview(marker, red, 1f, false, marker = MarkerPreview.SeeThroughGrey).argb)
        assertEquals(0x80585858.toInt(), PreviewPolicy.preview(BrushSpec.defaults(BrushKind.Highlighter), red, 1f, false, marker = MarkerPreview.SeeThroughGrey).argb, "the highlighter previews in the marker style too")
        assertEquals(red, PreviewPolicy.preview(BrushSpec.defaults(BrushKind.Fountain), red, 1f, false, marker = MarkerPreview.SeeThroughGrey).argb, "other styles keep their colour")
    }
}
