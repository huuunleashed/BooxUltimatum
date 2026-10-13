package app.booxultimatum.core.battery

import org.junit.Test
import kotlin.test.assertEquals
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

    private val hour = 3_600_000L

    @Test fun aNewRestartIsSaidAtOnce() {
        assertEquals(RestartAdvice.Advice.First, RestartAdvice.decide(boot + 5_000, boot, advisedAtMs = 0, remindedAtMs = 0, nowMs = boot + hour))
    }

    @Test fun aRestartStillPendingIsRemindedEveryTwelveHours() {
        val advised = boot + 5_000
        assertEquals(RestartAdvice.Advice.None, RestartAdvice.decide(advised, boot, advised, remindedAtMs = boot + hour, nowMs = boot + 12 * hour))
        assertEquals(RestartAdvice.Advice.Reminder, RestartAdvice.decide(advised, boot, advised, remindedAtMs = boot + hour, nowMs = boot + 13 * hour))
    }

    @Test fun aTabletThatWasAdvisedBeforeRemindersExistedIsRemindedAtOnce() {
        val advised = boot + 5_000
        assertEquals(RestartAdvice.Advice.Reminder, RestartAdvice.decide(advised, boot, advised, remindedAtMs = 0, nowMs = 1_700_000_000_000L))
    }

    @Test fun aRebootEndsTheReminders() {
        assertEquals(RestartAdvice.Advice.None, RestartAdvice.decide(boot - 60_000, boot, boot - 60_000, remindedAtMs = 0, nowMs = boot + 99 * hour))
        assertEquals(RestartAdvice.Advice.None, RestartAdvice.decide(null, boot, 0, 0, boot + hour))
    }
}