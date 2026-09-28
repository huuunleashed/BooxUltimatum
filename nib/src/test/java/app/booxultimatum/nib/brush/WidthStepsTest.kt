package app.booxultimatum.nib.brush

import app.booxultimatum.nib.engine.brush.BrushCatalog
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WidthStepsTest {
    @Test fun thinEndHasQuarterPixelSteps() {
        val steps = WidthSteps.forRange(BrushSpec.widthRange(BrushKind.Fineliner))
        assertEquals(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f), steps.take(6))
        assertEquals(40f, steps.last())
    }

    @Test fun everyBrushGetsItsRangeEndsAndNothingOutside() {
        for (kind in BrushCatalog.kinds) {
            val range = BrushSpec.widthRange(kind)
            val steps = WidthSteps.forRange(range)
            assertEquals(range.start, steps.first(), kind.id)
            assertEquals(range.endInclusive, steps.last(), kind.id)
            assertTrue(steps.all { it in range }, kind.id)
            assertEquals(steps.sorted(), steps, kind.id)
            assertEquals(steps.distinct(), steps, kind.id)
        }
    }

    @Test fun stepsMoveAndStopAtTheEnds() {
        val r = BrushSpec.widthRange(BrushKind.Fountain)
        assertEquals(0.75f, WidthSteps.step(r, 0.5f, 1))
        assertEquals(0.5f, WidthSteps.step(r, 0.5f, -3))
        assertEquals(60f, WidthSteps.step(r, 56f, 5))
        assertEquals(2.5f, WidthSteps.step(r, 2.4f, 0), "snaps to the nearest step")
    }

    @Test fun labelsReadLikeWidths() {
        assertEquals("0.5", WidthSteps.label(0.5f))
        assertEquals("0.75", WidthSteps.label(0.75f))
        assertEquals("2", WidthSteps.label(2f))
        assertEquals("2.5", WidthSteps.label(2.5f))
        assertEquals("1.25", WidthSteps.label(1.25f))
    }
}
