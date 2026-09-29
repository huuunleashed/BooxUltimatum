package app.booxultimatum.kit.ink.session

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InkGuardTest {
    private class Recorder : InkGuard.Undo {
        val done = mutableListOf<String>()
        var live = true
        override fun endLiveSession() = live.also { done += "session" }
        override fun clearFastMode() { done += "fast" }
        override fun restoreTouch() { done += "touch" }
        override fun restoreParams(style: Int, params: FloatArray) { done += "params $style ${params.joinToString(",")}" }
    }

    @Test fun aRecordFromAnEndedProcessIsUndoneInFull() {
        val u = Recorder()
        val text = InkGuard.Record(12345, 1L, session = true, fastMode = true, touch = true, params = mapOf(1 to floatArrayOf(0.3f, 0.6f))).format()
        val done = InkGuard(u).undoRecord(text)
        assertEquals(listOf("session", "fast", "touch", "params 1 0.3,0.6"), u.done)
        assertEquals(listOf("session", "fast mode", "finger touch", "style 1 parameters"), done)
    }

    @Test fun aPausedSessionLeftBehindIsLeftAlone() {
        val u = Recorder().apply { live = false }
        val text = InkGuard.Record(12345, 1L, session = true, fastMode = false, touch = false, params = emptyMap()).format()
        assertEquals(emptyList(), InkGuard(u).undoRecord(text), "a paused session draws nothing and may be another app's by now")
    }

    @Test fun thisProcessesOwnRecordAndGarbageChangeNothing() {
        val u = Recorder()
        val own = InkGuard.Record(android.os.Process.myPid(), 1L, true, true, true, emptyMap()).format()
        assertEquals(emptyList(), InkGuard(u).undoRecord(own))
        assertEquals(emptyList(), InkGuard(u).undoRecord("nonsense"))
        assertTrue(u.done.isEmpty())
    }

    @Test fun theRecordFileFollowsWhatIsOutstanding() {
        val f = java.io.File(System.getProperty("java.io.tmpdir"), "ink-guard-${System.nanoTime()}")
        val g = InkGuard(Recorder())
        g.attach(f)
        assertFalse(f.exists(), "nothing outstanding, no file")
        g.sessionOpened()
        g.paramsChanged(4, floatArrayOf(1f, 3f))
        val r = InkGuard.Record.parse(f.readText())!!
        assertTrue(r.session)
        assertEquals(listOf(1f, 3f), r.params[4]?.toList())
        g.paramsChanged(4, floatArrayOf(9f, 9f))
        assertEquals(listOf(1f, 3f), g.changedParams()[4]?.toList(), "the first original is kept")
        g.sessionClosed(); g.paramsRestored(4)
        assertFalse(f.exists(), "all undone, file gone")
    }

    @Test fun attachingUndoesAStaleRecordFirst() {
        val f = java.io.File(System.getProperty("java.io.tmpdir"), "ink-guard-${System.nanoTime()}")
        f.writeText(InkGuard.Record(4242, 1L, session = false, fastMode = true, touch = false, params = emptyMap()).format())
        val u = Recorder()
        InkGuard(u).attach(f)
        assertEquals(listOf("fast"), u.done)
        assertFalse(f.exists())
    }
}
