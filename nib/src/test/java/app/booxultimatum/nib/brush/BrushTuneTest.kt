package app.booxultimatum.nib.brush

import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrushTuneTest {
    private val tuned = BrushPreset(
        BrushKind.Charcoal, 10f, PressurePreset.Soft, 0xFF1F4FB8.toInt(),
        BrushTune(opacity = 0.6f, exponent = 1.7f, floor = 0.2f, ceiling = 1.2f, smoothing = 0.1f, spacing = 0.3f, flow = 0.5f, grain = 0.9f, jitter = 0.25f),
    )

    @Test fun tunedPresetsSurviveTheirTextForm() {
        assertEquals(tuned, BrushPreset.decode(tuned.encode()))
        val nib = BrushPreset(BrushKind.Calligraphy, 8f, PressurePreset.Medium, -0x1000000, BrushTune(nibAngle = 1.2f, nibFromOrientation = true, speedInfluence = 0.3f, taper = 2f))
        assertEquals(nib, BrushPreset.decode(nib.encode()))
    }

    @Test fun untunedPresetsKeepTheFormNib01Wrote() {
        val plain = BrushPreset(BrushKind.Fountain, 3f, PressurePreset.Medium, -0x1000000)
        assertEquals(4, plain.encode().split(":").size)
        assertEquals(plain, BrushPreset.decode("fountain:3.0:medium:ff000000"))
    }

    @Test fun damagedTuningIsSkippedAndOutOfRangeValuesAreClamped() {
        val p = BrushPreset.decode("pencil:2.5:medium:ff404040:o=9;e=abc;g=-3;zz=1;ao=maybe;s=0.5")!!
        assertEquals(1f, p.tune.opacity)
        assertNull(p.tune.exponent)
        assertEquals(0f, p.tune.grain)
        assertNull(p.tune.nibFromOrientation)
        assertEquals(0.5f, p.tune.smoothing)
        assertNull(BrushPreset.decode("pencil:2.5:medium:ff404040:o=1:extra"), "too many parts")
    }

    @Test fun theSpecCarriesEveryTunedProperty() {
        val s = tuned.spec()
        assertEquals(10f, s.width)
        assertEquals(0.6f, s.opacity)
        assertEquals(BrushSpec.defaults(BrushKind.Charcoal).curve, s.curve, "the charcoal style draws pressure its own way, so its curve isn't tuned")
        val fountain = tuned.copy(kind = BrushKind.Fountain).spec()
        assertEquals(1.7f, fountain.curve.exponent, "the fountain style takes it as its sensitivity")
        assertEquals(0.2f, fountain.curve.floor)
        assertEquals(1.2f, fountain.curve.ceiling)
        assertEquals(0.1f, s.smoothing)
        assertEquals(0.3f, s.spacing)
        assertEquals(0.5f, s.flow)
        assertEquals(0.9f, s.grain)
        assertEquals(0.25f, s.jitter)
        // What isn't tuned stays the brush's own.
        val base = BrushSpec.defaults(BrushKind.Charcoal)
        assertEquals(base.pressureFlow, s.pressureFlow)
        assertEquals(base.preview, s.preview)
    }

    @Test fun choosingAPressurePresetReplacesATunedSensitivity() {
        assertTrue(tuned.customCurve)
        val firm = tuned.copy(kind = BrushKind.Fountain).withPressure(PressurePreset.Firm)
        assertNull(firm.tune.exponent)
        assertEquals(PressurePreset.Firm.curve(BrushKind.Fountain).exponent, firm.spec().curve.exponent)
        assertEquals(0.2f, firm.spec().curve.floor, "the floor stays as tuned")
        assertFalse(BrushPreset(BrushKind.Fountain, 3f, PressurePreset.Soft, -0x1000000).customCurve)
    }

    @Test fun onlyTheFountainPensPressureResponseCanBeChanged() {
        // Every other style draws pressure its own way, and a reshaped curve made the ink differ from the preview.
        val fineliner = BrushPreset(BrushKind.Fineliner, 2f, PressurePreset.Medium, -0x1000000, BrushTune(floor = 0.3f))
        assertTrue(fineliner.spec().curve.isConstant, "the plain pen style draws a constant width")
        val brush = BrushPreset(BrushKind.BrushPen, 6f, PressurePreset.Soft, -0x1000000, BrushTune(exponent = 0.2f))
        assertEquals(BrushSpec.NEO_BRUSH_CURVE, brush.spec().curve, "the brush style's square root, preset or not")
        for (k in listOf(BrushKind.Calligraphy, BrushKind.CalligraphyAsian, BrushKind.SquarePen)) {
            assertTrue(BrushPreset(k, 8f, PressurePreset.Firm, -0x1000000).spec().curve.isConstant, "$k: the square pen style ignores pressure")
        }
        assertEquals(listOf(BrushKind.Fountain), BrushKind.entries.filter { PressurePreset.applies(it) })
        assertFalse(BrushPreset(BrushKind.Fountain, 3f, PressurePreset.Soft, -0x1000000).spec().curve == BrushPreset(BrushKind.Fountain, 3f, PressurePreset.Firm, -0x1000000).spec().curve)
    }
}