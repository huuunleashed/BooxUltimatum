package app.booxultimatum.core.battery

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MIN = 60_000L

class SleepGuardPolicyTest {
    @Test fun actsWhenAsleepOnBatteryAndIdle() = assertTrue(SleepGuardPolicy.shouldAct(false, false, null, null))

    @Test fun neverWhileInteractive() = assertFalse(SleepGuardPolicy.shouldAct(true, false, null, null))

    @Test fun neverWhileDocked() = assertFalse(SleepGuardPolicy.shouldAct(false, true, null, null))

    @Test fun onlyTheDockCountsAsDocked() {
        assertTrue(SleepGuardPolicy.isDock(8))
        listOf(0, 1, 2, 4).forEach { assertFalse(SleepGuardPolicy.isDock(it)) }
    }

    @Test fun waitsTwentyMinutesAfterTheLastAction() {
        assertFalse(SleepGuardPolicy.shouldAct(false, false, 19 * MIN, null))
        assertTrue(SleepGuardPolicy.shouldAct(false, false, 20 * MIN, null))
    }

    @Test fun waitsAMinuteAfterInput() {
        assertFalse(SleepGuardPolicy.shouldAct(false, false, null, MIN - 1))
        assertTrue(SleepGuardPolicy.shouldAct(false, false, null, MIN))
    }

    @Test fun reportsTheFirstRuleThatApplies() {
        assertEquals(GuardBlock.Awake, SleepGuardPolicy.block(true, true, 0, 0))
        assertEquals(GuardBlock.Docked, SleepGuardPolicy.block(false, true, 0, 0))
        assertEquals(GuardBlock.RecentAction, SleepGuardPolicy.block(false, false, 0, 0))
        assertEquals(GuardBlock.RecentInput, SleepGuardPolicy.block(false, false, null, 0))
        assertNull(SleepGuardPolicy.block(false, false, null, null))
    }

    @Test fun aClockThatWentBackDoesNotBlockForever() = assertTrue(SleepGuardPolicy.shouldAct(false, false, -MIN, -MIN))

    @Test fun theFirstHealingStepIsTheOutcome() {
        val steps = listOf(StepResult(1, StepOutcome.NotHealed), StepResult(2, StepOutcome.Healed), StepResult(3, StepOutcome.Skipped))
        assertEquals(2, SleepGuardPolicy.outcome(steps))
    }

    @Test fun skippedStepsDoNotHeal() {
        val steps = listOf(StepResult(1, StepOutcome.NotHealed), StepResult(2, StepOutcome.Skipped), StepResult(3, StepOutcome.Healed))
        assertEquals(3, SleepGuardPolicy.outcome(steps))
    }

    @Test fun noHealingStepMeansNotHealed() {
        assertNull(SleepGuardPolicy.outcome(listOf(StepResult(1, StepOutcome.NotHealed), StepResult(2, StepOutcome.Skipped))))
        assertNull(SleepGuardPolicy.outcome(emptyList()))
    }
}
