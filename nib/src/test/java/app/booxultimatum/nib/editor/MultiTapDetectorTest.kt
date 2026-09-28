package app.booxultimatum.nib.editor

import org.junit.Test
import kotlin.test.assertEquals

class MultiTapDetectorTest {
    private val d = MultiTapDetector(maxDurationMs = 300, slopPx = 20f)

    private fun tap(fingers: Int, durationMs: Long = 120, travel: Float = 0f): MultiTapDetector.Result {
        d.start(0, 0, 100f, 100f)
        for (i in 1 until fingers) d.pointerDown(i, 100f + 80f * i, 100f, i + 1)
        for (i in 0 until fingers) d.move(i, 100f + 80f * i + travel, 100f)
        return d.end(durationMs)
    }

    @Test fun twoFingersUndoThreeRedo() {
        assertEquals(MultiTapDetector.Result.Undo, tap(2))
        assertEquals(MultiTapDetector.Result.Redo, tap(3))
    }

    @Test fun oneOrFourFingersDoNothing() {
        assertEquals(MultiTapDetector.Result.None, tap(1))
        assertEquals(MultiTapDetector.Result.None, tap(4))
    }

    @Test fun aSlowOrMovingTouchIsNoTap() {
        assertEquals(MultiTapDetector.Result.None, tap(2, durationMs = 400))
        assertEquals(MultiTapDetector.Result.None, tap(2, travel = 40f), "that was a pinch or a pan")
        assertEquals(MultiTapDetector.Result.Undo, tap(2, travel = 10f), "a little wobble is still a tap")
    }

    @Test fun cancelledGesturesDoNothing() {
        d.start(0, 0, 0f, 0f)
        d.pointerDown(1, 50f, 0f, 2)
        d.cancel()
        assertEquals(MultiTapDetector.Result.None, d.end(100))
    }
}
