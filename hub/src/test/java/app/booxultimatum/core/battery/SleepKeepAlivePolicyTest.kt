package app.booxultimatum.core.battery

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val MIN = 60_000L

class SleepKeepAlivePolicyTest {
    private val slept = 1_000_000_000L

    @Test fun theIntervalIsAThirdOfTheNinetyMinutesTheDisplayStuckAfter() {
        assertEquals(30 * MIN, SleepKeepAlivePolicy.INTERVAL_MS)
        assertTrue(SleepKeepAlivePolicy.INTERVAL_MS * 3 <= 90 * MIN)
    }

    @Test fun theFirstAlarmIsAnIntervalAfterTheSleepBegan() {
        assertEquals(slept + 30 * MIN, SleepKeepAlivePolicy.nextAtMs(slept, slept))
    }

    @Test fun aRefreshIsDueOnlyOnceAnIntervalHasPassedWithoutOne() {
        assertFalse(SleepKeepAlivePolicy.due(slept, slept + 10 * MIN))
        assertFalse(SleepKeepAlivePolicy.due(slept, slept + 28 * MIN))
        assertTrue(SleepKeepAlivePolicy.due(slept, slept + 29 * MIN))
        assertTrue(SleepKeepAlivePolicy.due(slept, slept + 30 * MIN))
        assertTrue(SleepKeepAlivePolicy.due(slept, slept + 8 * 60 * MIN))
    }

    @Test fun aLiveUpdateRestartsTheClockSoTheAlarmOnlyMovesOn() {
        val liveUpdate = slept + 28 * MIN
        val alarm = slept + 30 * MIN
        assertFalse(SleepKeepAlivePolicy.due(liveUpdate, alarm))
        assertEquals(liveUpdate + 30 * MIN, SleepKeepAlivePolicy.nextAtMs(liveUpdate, alarm))
    }

    @Test fun theNextAlarmIsNeverRightNow() {
        val now = slept + 31 * MIN
        assertEquals(now + 30_000L, SleepKeepAlivePolicy.nextAtMs(slept, now))
    }

    @Test fun aLongSleepWithNoRefreshAtAllIsMissedAndAShortOneIsNot() {
        assertTrue(SleepKeepAlivePolicy.missed(slept, slept + 60 * MIN, lastRefreshMs = slept))
        assertFalse(SleepKeepAlivePolicy.missed(slept, slept + 59 * MIN, lastRefreshMs = slept))
        assertFalse(SleepKeepAlivePolicy.missed(slept, slept + 8 * 60 * MIN, lastRefreshMs = slept + 30 * MIN))
        assertFalse(SleepKeepAlivePolicy.missed(0, slept, lastRefreshMs = 0))
    }
}