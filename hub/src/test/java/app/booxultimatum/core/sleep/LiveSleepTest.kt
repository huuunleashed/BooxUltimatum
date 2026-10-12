package app.booxultimatum.core.sleep

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private val utc = java.util.TimeZone.getTimeZone("UTC")

    /** Today at [hour]:[minute] UTC, as epoch millis; the date is irrelevant to the rule. */
    private fun at(hour: Int, minute: Int = 0): Long = (20_000L * 86_400_000L) + hour * 3_600_000L + minute * 60_000L

    @Test fun quietHoursThatWrapMidnightAreOneNight() {
        val p = LivePrefs(quiet = true, quietFrom = 23, quietTo = 7)
        assertTrue(LiveSleep.inQuiet(p, at(23), utc))
        assertTrue(LiveSleep.inQuiet(p, at(0, 30), utc))
        assertTrue(LiveSleep.inQuiet(p, at(6, 59), utc))
        assertFalse(LiveSleep.inQuiet(p, at(7), utc))
        assertFalse(LiveSleep.inQuiet(p, at(22, 59), utc))
        assertFalse(LiveSleep.inQuiet(p, at(12), utc))
    }

    @Test fun quietHoursInsideOneDayAreRead() {
        val p = LivePrefs(quiet = true, quietFrom = 13, quietTo = 15)
        assertTrue(LiveSleep.inQuiet(p, at(13), utc))
        assertTrue(LiveSleep.inQuiet(p, at(14, 59), utc))
        assertFalse(LiveSleep.inQuiet(p, at(15), utc))
        assertFalse(LiveSleep.inQuiet(p, at(3), utc))
    }

    @Test fun quietHoursOffOrEmptyNeverApply() {
        assertFalse(LiveSleep.inQuiet(LivePrefs(quiet = false, quietFrom = 23, quietTo = 7), at(1), utc))
        assertFalse(LiveSleep.inQuiet(LivePrefs(quiet = true, quietFrom = 5, quietTo = 5), at(5), utc))
    }

    @Test fun everyHourCanBeChosenAsABoundary() {
        for (from in 0..23) for (to in 0..23) {
            val p = LivePrefs(quiet = true, quietFrom = from, quietTo = to)
            val inQuiet = (0..23).count { LiveSleep.inQuiet(p, at(it), utc) }
            val expected = if (from == to) 0 else (to - from + 24) % 24
            assertEquals(expected, inQuiet, "from $from to $to")
        }
    }
}
