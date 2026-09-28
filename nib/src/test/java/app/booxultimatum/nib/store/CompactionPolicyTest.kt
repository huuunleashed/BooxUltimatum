package app.booxultimatum.nib.store

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompactionPolicyTest {
    private val p = CompactionPolicy()

    @Test fun everyFiftyCommands() {
        assertFalse(p.afterCommand(49, 1_000))
        assertTrue(p.afterCommand(50, 1_000))
        assertTrue(p.afterCommand(51, 1_000), "a count carried over from replay also compacts")
    }

    @Test fun aBigJournalCompactsEarly() {
        assertTrue(p.afterCommand(3, 8L shl 20))
        assertFalse(p.afterCommand(3, (8L shl 20) - 1))
    }

    @Test fun stoppingSavesOnlyWhatIsUnsaved() {
        assertFalse(p.onStop(0))
        assertTrue(p.onStop(1))
    }

    @Test fun theIntervalIsConfigurable() {
        val every10 = CompactionPolicy(everyCommands = 10)
        assertFalse(every10.afterCommand(9, 0))
        assertTrue(every10.afterCommand(10, 0))
    }
}
