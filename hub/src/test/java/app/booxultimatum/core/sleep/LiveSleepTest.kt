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

    /** Measured cost of the one-minute step on battery was ~30 mAh a day, so it runs only while charging. */
    @Test fun oneMinuteRunsAtFiveOnBattery() {
        assertEquals(5, LiveSleep.effectiveStep(LivePrefs(stepMin = 1), charging = false))
    }

    @Test fun oneMinuteIsHonouredWhileCharging() {
        assertEquals(1, LiveSleep.effectiveStep(LivePrefs(stepMin = 1), charging = true))
    }

    @Test fun stepsOfFiveOrMoreAreUntouched() {
        listOf(5, 10, 15, 30, 60).forEach { step ->
            assertEquals(step, LiveSleep.effectiveStep(LivePrefs(stepMin = step), charging = false))
            assertEquals(step, LiveSleep.effectiveStep(LivePrefs(stepMin = step), charging = true))
        }
    }

    @Test fun theShortestStepOnBatteryIsStillOffered() {
        assertTrue(LivePrefs.STEPS.contains(LiveSleep.MIN_STEP_ON_BATTERY))
    }
}
