package app.booxultimatum.nib.diag

import app.booxultimatum.nib.engine.brush.HardwareStyle
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProbesTest {
    @Test fun theStyleSweepAsksForEveryFirmwareStyleAtFourPixels() {
        val bands = Probe.Styles.bands
        assertEquals((0..7).toList(), bands.map { it.preview.style })
        assertTrue(bands.all { it.preview.widthPx == 4f && it.preview.argb == Probe.BLACK })
        for (b in bands) {
            assertEquals(b.preview.style, b.brush.preview.style.code, "the app's own stroke is the brush that asks for ${b.key}")
        }
    }

    @Test fun widthSweepsSendTheRawWidthsWithNoMinimum() {
        for (probe in listOf(Probe.WidthsFountain, Probe.WidthsPencil)) {
            assertEquals(listOf(0.5f, 0.75f, 1f, 1.5f, 2f, 3f), probe.bands.map { it.preview.widthPx })
            assertEquals(probe.bands.map { it.preview.widthPx }, probe.bands.map { it.brush.width })
        }
        assertTrue(Probe.WidthsPencil.bands.all { it.preview.style == HardwareStyle.Pencil.code }, "thin pencil is asked for as pencil, no stand-in")
    }

    @Test fun colourProbeIncludesTranslucentBlackOnBothStyles() {
        val argbs = Probe.Colours.bands.map { it.preview.argb }
        assertEquals(6, argbs.size)
        assertEquals(2, argbs.count { it == Probe.HALF_BLACK })
        assertEquals(setOf(HardwareStyle.Fountain.code, HardwareStyle.Pencil.code), Probe.Colours.bands.filter { it.preview.argb == Probe.HALF_BLACK }.map { it.preview.style }.toSet())
    }

    @Test fun markerColourProbeUsesTheMarkerStyleThroughout() {
        val bands = Probe.MarkerColours.bands
        assertEquals(8, bands.size)
        assertTrue(bands.all { it.preview.style == HardwareStyle.Marker.code && it.preview.widthPx == 16f })
        assertEquals(0x80D2232A.toInt(), bands.first().preview.argb, "the first band is the preview Nib sends today")
        assertTrue(bands.any { it.preview.argb ushr 24 == 0xFF }, "opaque variants are included")
    }

    @Test fun swapProbeStepsTheDelay() {
        assertEquals(listOf(0L, 16L, 50L, 120L), Probe.SwapDelay.bands.map { it.swapDelayMs })
    }

    @Test fun bandKeysAreUniqueWithinAProbe() {
        for (p in Probe.entries) assertEquals(p.bands.size, p.bands.map { it.key }.distinct().size, p.id)
    }
}
