package app.booxultimatum.core.battery

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestartAdviceTest {
    private val boot = 1_000_000L

    @Test fun aRestartAfterTheBootIsAdvisedOnce() {
        assertTrue(RestartAdvice.shouldAdvise(restartAtMs = boot + 5_000, bootAtMs = boot, advisedAtMs = 0))
        assertFalse(RestartAdvice.shouldAdvise(restartAtMs = boot + 5_000, bootAtMs = boot, advisedAtMs = boot + 5_000))
    }

    @Test fun aNewerRestartIsAdvisedAgain() {
        assertTrue(RestartAdvice.shouldAdvise(restartAtMs = boot + 9_000, bootAtMs = boot, advisedAtMs = boot + 5_000))
    }

    @Test fun aRestartBeforeTheBootIsNotAdvised() {
        // The reader also finds a crash up to twelve hours before the boot, which is what rebooted the tablet.
        assertFalse(RestartAdvice.shouldAdvise(restartAtMs = boot - 60_000, bootAtMs = boot, advisedAtMs = 0))
    }

    @Test fun noRestartMeansNoAdvice() {
        assertFalse(RestartAdvice.shouldAdvise(restartAtMs = null, bootAtMs = boot, advisedAtMs = 0))
    }
}
