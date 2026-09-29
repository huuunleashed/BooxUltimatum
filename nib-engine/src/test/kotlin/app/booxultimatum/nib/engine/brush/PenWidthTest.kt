package app.booxultimatum.nib.engine.brush

import kotlin.math.PI
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class PenWidthTest {
    @Test
    fun millimetresAreThreeHundredPpiPanelPixels() {
        assertEquals(11.811f, PenWidth.mmToPx(1f), 1e-3f)
        assertEquals(1.1811f, PenWidth.mmToPx(0.1f), 1e-4f)
        assertEquals(23.622f, PenWidth.mmToPx(2f), 1e-3f)
        assertEquals(94.488f, PenWidth.mmToPx(8f), 1e-3f)
        assertEquals(0.25f, PenWidth.pxToMm(PenWidth.mmToPx(0.25f)), 1e-6f)
        assertEquals(0.35f, PenWidth.snapMm(0.37f), 1e-6f)
        assertEquals(0.4f, PenWidth.snapMm(0.38f), 1e-6f)
    }

    @Test
    fun nibsRangesHoldTheNativeOnesAndGoFiner() {
        assertEquals(PenWidth.NATIVE_MARKER_MM, PenWidth.nativeRangeMm(BrushKind.Marker))
        assertEquals(PenWidth.NATIVE_MARKER_MM, PenWidth.nativeRangeMm(BrushKind.Highlighter))
        assertEquals(PenWidth.NATIVE_PEN_MM, PenWidth.nativeRangeMm(BrushKind.Fountain))
        assertNull(PenWidth.nativeRangeMm(BrushKind.Airbrush))
        assertNull(PenWidth.nativeRangeMm(BrushKind.PixelEraser))
        for (kind in BrushKind.entries) {
            val native = PenWidth.nativeRangePx(kind) ?: continue
            val own = BrushSpec.widthRange(kind)
            assertTrue(own.start <= native.start && own.endInclusive >= native.endInclusive, "$kind: $own holds $native")
        }
        assertTrue(BrushSpec.widthRange(BrushKind.Fineliner).start < PenWidth.mmToPx(PenWidth.NATIVE_PEN_MM.start), "Nib's plain pen goes finer than BOOX's")
    }
}

class TiltShadingTest {
    private val deg = (PI / 180).toFloat()

    @Test
    fun aPenHeldForWritingDrawsAsIfUpright() {
        for (t in listOf(0f, 10f * deg, 30f * deg, Float.NaN, -1f)) {
            assertEquals(0f, TiltShading.amount(t))
            assertEquals(1f, TiltShading.widthFactor(t, 3f))
        }
    }

    @Test
    fun aLaidDownPenBroadensUpToItsScale() {
        assertEquals(3f, TiltShading.widthFactor(60f * deg, 3f), 1e-5f)
        assertEquals(3f, TiltShading.widthFactor(90f * deg, 3f), 1e-5f)
        assertEquals(2f, TiltShading.widthFactor(45f * deg, 3f), 1e-5f, "halfway through the range")
        var last = 1f
        for (d in 30..90) {
            val f = TiltShading.widthFactor(d * deg, 3f)
            assertTrue(f >= last, "never narrower as the pen tilts further")
            last = f
        }
        assertEquals(1f, TiltShading.widthFactor(80f * deg, BrushSpec.NO_TILT), "a brush without tilt ignores it")
        assertEquals(BrushSpec.MAX_TILT_SCALE, TiltShading.widthFactor(80f * deg, 100f))
    }

    @Test
    fun broaderInkIsLighter() {
        assertEquals(1f, TiltShading.coverage(1f))
        assertEquals(0.5f, TiltShading.coverage(4f), 1e-6f)
        assertTrue(TiltShading.coverage(3f) < TiltShading.coverage(2f))
        assertEquals(TiltShading.coverage(4f), TiltShading.coverage(4f, TiltResponse.Eased))
    }

    @Test
    fun booxsCharcoalBroadensAsMeasured() {
        val measured = listOf(
            0 to 0f, 10 to 0f, 20 to 0.067f, 25 to 0.133f, 30 to 0.2f, 35 to 0.33f, 40 to 0.4f, 45 to 0.51f, 50 to 0.71f,
            55 to 0.84f, 60 to 1.04f, 65 to 1.24f, 70 to 1.49f, 75 to 1.76f, 80 to 2.02f, 90 to 2.3f,
        )
        for ((d, g) in measured) {
            assertEquals(g, TiltShading.broadening(d * deg), 1e-4f, "$d degrees")
            assertEquals(1f + 3f * g, TiltShading.widthFactor(d * deg, 3f, TiltResponse.Native), 1e-4f, "$d degrees at tilt scale 3")
        }
        assertEquals(0.1f, TiltShading.broadening(22.5f * deg), 1e-4f, "linear in between")
        assertEquals(2.3f, TiltShading.broadening(2f), 1e-6f, "flat beyond lying down")
        for (t in listOf(Float.NaN, -1f, 0f)) assertEquals(0f, TiltShading.broadening(t))
        var last = 0f
        for (d in 0..90) {
            val g = TiltShading.broadening(d * deg)
            assertTrue(g >= last, "never narrower as the pen tilts further ($d degrees)")
            last = g
        }
        assertEquals(1f, TiltShading.widthFactor(80f * deg, BrushSpec.NO_TILT, TiltResponse.Native), "tilt off")
        assertEquals(1f + BrushSpec.MAX_TILT_SCALE * 2.02f, TiltShading.widthFactor(80f * deg, 100f, TiltResponse.Native), 1e-4f)
        assertEquals(1f + 3f * TiltShading.MAX_BROADENING, TiltShading.maxWidthFactor(3f, TiltResponse.Native))
        assertEquals(3f, TiltShading.maxWidthFactor(3f, TiltResponse.Eased))
        assertEquals(1f, TiltShading.maxWidthFactor(BrushSpec.NO_TILT, TiltResponse.Native))
        assertEquals(1f, TiltShading.coverage(7f, TiltResponse.Native), "as dark broad as upright")
    }

    @Test
    fun theEasedResponseIsTheOneStrokesHadBefore() {
        for (d in 0..90) {
            assertEquals(TiltShading.widthFactor(d * deg, 3f), TiltShading.widthFactor(d * deg, 3f, TiltResponse.Eased))
        }
        assertEquals(TiltResponse.Eased, TiltResponse.fromCode(0))
        assertEquals(TiltResponse.Native, TiltResponse.fromCode(1))
        assertEquals(TiltResponse.Eased, TiltResponse.fromCode(99), "a response from a later version reads as the eased one")
        assertEquals(TiltResponse.entries.size, TiltResponse.entries.map { it.code }.toSet().size)
    }
}
