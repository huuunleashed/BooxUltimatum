package app.booxultimatum.core.sleep

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveSleepTest {
    @Test fun stepsOfferOneMinute() {
        assertTrue(LivePrefs.STEPS.contains(1))
    }

    @Test fun oneMinuteTickLandsOnRoundMinutes() {
        val base = 60_000L * 1_000
        assertEquals(base + 60_000L, LiveSleep.nextTick(base, 1))
        assertEquals(base + 60_000L, LiveSleep.nextTick(base + 30_000L, 1))
    }

    @Test fun oneMinuteTickSkipsOnlyVeryCloseAlarms() {
        val base = 60_000L * 1_000
        assertEquals(base + 120_000L, LiveSleep.nextTick(base + 50_000L, 1))
    }

    @Test fun fiveMinuteGuardUnchanged() {
        val base = 60_000L * 1_000
        assertEquals(base + 300_000L, LiveSleep.nextTick(base + 240_000L, 5))
        assertEquals(base + 600_000L, LiveSleep.nextTick(base + 290_000L, 5))
    }
}
