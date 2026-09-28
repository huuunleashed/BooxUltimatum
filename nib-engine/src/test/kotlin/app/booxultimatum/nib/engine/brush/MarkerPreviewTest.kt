package app.booxultimatum.nib.engine.brush

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkerPreviewTest {
    private val grey = MarkerPreview.SeeThroughGrey
    private val solid = MarkerPreview.SolidColour

    @Test fun colouredPreviewsBecomeAHalfAlphaGreyAsLightAsTheColour() {
        // The two greys the tablet showed in place of red and blue (Marker colours probe, 2026-09-28).
        assertEquals(0x80585858.toInt(), grey.adapt(0x80D2232A.toInt()))
        assertEquals(0x804D4D4D.toInt(), grey.adapt(0x801F4FB8.toInt()))
    }

    @Test fun greysPassAsTheyAreUpToTheLightestSeen() {
        assertEquals(0x80000000.toInt(), grey.adapt(0x80000000.toInt()))
        assertEquals(0x80808080.toInt(), grey.adapt(0x80808080.toInt()))
        assertEquals(0x80BBBBBB.toInt(), grey.adapt(0x80BBBBBB.toInt()))
        assertEquals(0x80BBBBBB.toInt(), grey.adapt(0x80FFFFFF.toInt()), "white would vanish, so it's sent at the lightest grey seen")
    }

    @Test fun paleColoursAreCappedAtTheLightestGreySeen() {
        assertEquals(0x80BBBBBB.toInt(), grey.adapt(0x80F2C300.toInt()), "the palette's yellow is exactly as light as the cap")
        assertEquals(0x80BBBBBB.toInt(), grey.adapt(0x80FFC0CB.toInt()), "pink is lighter than any grey seen, so it's capped")
    }

    @Test fun solidColourSendsTheColourOpaque() {
        assertEquals(0xFFD2232A.toInt(), solid.adapt(0x80D2232A.toInt()))
        assertEquals(0xFF1F4FB8.toInt(), solid.adapt(0x801F4FB8.toInt()))
    }

    @Test fun idsRoundTripAndUnknownFallsBackToSolidColour() {
        for (m in MarkerPreview.entries) assertEquals(m, MarkerPreview.of(m.id))
        assertEquals(MarkerPreview.SolidColour, MarkerPreview.of(null))
        assertEquals(MarkerPreview.SolidColour, MarkerPreview.of("nonsense"))
    }
}
