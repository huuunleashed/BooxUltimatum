package app.booxultimatum.kit.ink.input

import app.booxultimatum.kit.ink.FakeTime
import app.booxultimatum.kit.ink.session.InkGuard
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PalmGuardTest {
    private val time = FakeTime()
    private val guard = InkGuard(object : InkGuard.Undo {
        override fun endLiveSession() = false
        override fun notifyAppDied(pid: Int) = Unit
        override fun clearFastMode() = Unit
        override fun restoreTouch() = Unit
        override fun restoreParams(style: Int, params: FloatArray) = Unit
    })

    private class FakePanel(var accept: Boolean = true) : PalmGuard.Panel {
        val calls = mutableListOf<String>()
        override fun suppress(disable: IntArray, keep: IntArray) = accept.also { calls += "off ${disable.joinToString(",")} keep ${keep.joinToString(",")}" }
        override fun reset() = true.also { calls += "reset" }
    }

    private val canvas = intArrayOf(0, 0, 1860, 2480)
    private val rail = intArrayOf(0, 300, 120, 1400)

    @Test fun offWhileThePenIsNearBackShortlyAfterItLeaves() {
        val p = FakePanel()
        val g = PalmGuard(time, guard, p, awayMs = 500).apply { enabled = true }
        g.penNear(canvas, listOf(rail))
        g.penNear(canvas, listOf(rail))
        assertEquals(listOf("off 0,0,1860,2480 keep 0,300,120,1400"), p.calls, "sent once while unchanged")
        assertTrue(guard.outstanding)
        g.penAway()
        time.advance(499)
        g.penNear(canvas, listOf(rail))
        time.advance(1_000)
        assertEquals(1, p.calls.size, "the pen came back in time")
        g.penAway()
        time.advance(500)
        assertEquals("reset", p.calls.last())
        assertFalse(guard.outstanding)
    }

    @Test fun disabledOrRefusedDoesNothingFurther() {
        val p = FakePanel()
        PalmGuard(time, guard, p).penNear(canvas, emptyList())
        assertTrue(p.calls.isEmpty())
        val refusing = FakePanel(accept = false)
        val g = PalmGuard(time, guard, refusing).apply { enabled = true }
        g.penNear(canvas, emptyList()); g.penNear(intArrayOf(0, 0, 1, 1), emptyList())
        assertEquals(1, refusing.calls.size, "refused once, not asked again")
        assertFalse(g.active)
    }

    @Test fun reportsEachChangeOnce() {
        val p = FakePanel()
        val changes = mutableListOf<Boolean>()
        val g = PalmGuard(time, guard, p, awayMs = 500).apply { enabled = true; onChanged = { changes += it } }
        g.penNear(canvas, emptyList())
        g.penNear(canvas, listOf(rail))
        g.penAway()
        time.advance(500)
        g.stop()
        assertEquals(listOf(true, false), changes)
    }

    @Test fun turningItOffResetsAtOnce() {
        val p = FakePanel()
        val g = PalmGuard(time, guard, p).apply { enabled = true }
        g.penNear(canvas, emptyList())
        g.enabled = false
        assertEquals("reset", p.calls.last())
    }
}
