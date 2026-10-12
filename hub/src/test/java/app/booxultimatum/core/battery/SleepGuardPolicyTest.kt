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

    @Test fun waitsFiveMinutesAfterTheLastActionOfAnotherEpisode() {
        assertFalse(SleepGuardPolicy.shouldAct(false, false, 4 * MIN, null))
        assertTrue(SleepGuardPolicy.shouldAct(false, false, 5 * MIN, null))
    }

    @Test fun roundsFallDueAtTheScheduledMinutesThenHourly() {
        assertEquals(listOf(2, 5, 10, 20, 40, 100, 160, 220), (0..7).map { SleepGuardPolicy.roundDueMin(it) })
    }

    @Test fun aLateCheckRunsOneRoundNotABurst() {
        assertEquals(0, SleepGuardPolicy.roundsDue(0))
        assertEquals(0, SleepGuardPolicy.roundsDue(2 * MIN - 1))
        assertEquals(1, SleepGuardPolicy.roundsDue(2 * MIN))
        assertEquals(3, SleepGuardPolicy.roundsDue(10 * MIN))
        // Five hours with the display on: the five scheduled rounds and four hourly ones (100, 160, 220 and 280 minutes).
        assertEquals(9, SleepGuardPolicy.roundsDue(5 * 60 * MIN))
    }

    @Test fun theLadderIsLeastDrasticFirstAndCoversEveryStepOnce() {
        val ladder = SleepGuardPolicy.LADDER
        assertEquals(GuardStep.values().toSet(), ladder.toSet())
        assertEquals(GuardStep.Refresh, ladder.first())
        assertEquals(GuardStep.Key, ladder.last())
        // The stored ids are part of the log format and the last-action record: they must not move.
        assertEquals(listOf(1, 2, 3, 4, 5), GuardStep.values().map { it.id }.sorted())
        assertEquals(GuardStep.Refresh.id, 1)
        assertEquals(GuardStep.Lock.id, 2)
        assertEquals(GuardStep.Key.id, 3)
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
