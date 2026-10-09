package app.booxultimatum.core.battery

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val MIN = 60_000L
private const val HOUR = 60 * MIN
private const val T0 = 1_767_225_600_000L

private fun at(minutes: Long): Long = T0 + minutes * MIN

private fun episode(kind: EpisodeKind, fromMin: Long, toMin: Long) = Episode(kind, at(fromMin), at(toMin), 80, 79, null, 0.9, null)

private fun finding(kind: FindingKind, fromMin: Long, toMin: Long, severity: Severity = Severity.Problem) =
    Finding(kind, severity, at(fromMin), at(toMin))

private val DISPLAY_ON = finding(FindingKind.DisplayOn, 60, 300)

private val GUARD = GuardRecord(atMs = at(400), sinceMs = at(380), healedByStep = 2)

class MorningReportTest {
    @Test fun threeHoursIsTooShort() = assertFalse(MorningReport.qualifies(3 * HOUR, 0))

    @Test fun fourHoursOnBatteryQualifies() = assertTrue(MorningReport.qualifies(4 * HOUR, 0))

    @Test fun mostlyChargingDoesNotQualify() = assertFalse(MorningReport.qualifies(8 * HOUR, 5 * HOUR))

    @Test fun halfChargingDoesNotQualify() = assertFalse(MorningReport.qualifies(8 * HOUR, 4 * HOUR))

    @Test fun lessThanHalfChargingQualifies() = assertTrue(MorningReport.qualifies(8 * HOUR, 4 * HOUR - MIN))

    @Test fun chargingCountsOnlyInsideTheNight() {
        val episodes = listOf(episode(EpisodeKind.Charging, -600, 60), episode(EpisodeKind.Asleep, 60, 480))
        assertEquals(60 * MIN, MorningReport.chargingMs(episodes, at(0), at(480)))
    }

    @Test fun normalNightIsDescribedByItsFigures() {
        val content = MorningReport.compose(ReportInput(8 * HOUR + 12 * MIN, 97, 94, null, null, null))
        assertEquals(ReportContent.Normal(8 * HOUR + 12 * MIN, 97, 94, null), content)
    }

    @Test fun nightWithAFindingReportsIt() {
        val content = MorningReport.compose(ReportInput(8 * HOUR, 97, 94, DISPLAY_ON, null, null))
        assertEquals(ReportContent.WithFinding(DISPLAY_ON, null), content)
    }

    @Test fun guardRecordGoesWithTheFinding() {
        val content = MorningReport.compose(ReportInput(8 * HOUR, 97, 94, DISPLAY_ON, null, GUARD))
        assertEquals(ReportContent.WithFinding(DISPLAY_ON, GUARD), content)
    }

    @Test fun guardRecordWithoutAFindingKeepsTheNormalNight() {
        val content = MorningReport.compose(ReportInput(8 * HOUR, 97, 94, null, null, GUARD))
        assertEquals(ReportContent.Normal(8 * HOUR, 97, 94, GUARD), content)
    }

    @Test fun deathCarriesTheDisplayOnBeforeIt() {
        val died = finding(FindingKind.Died, 360, 420, Severity.Info)
        val content = MorningReport.compose(ReportInput(8 * HOUR, 0, 0, died, DISPLAY_ON, null))
        assertEquals(ReportContent.Died(died, DISPLAY_ON), content)
    }

    @Test fun sleepSpansJoinSleepAndDisplayOn() {
        val spans = MorningReport.sleepSpans(
            listOf(
                episode(EpisodeKind.Asleep, 0, 60),
                episode(EpisodeKind.DisplayOn, 60, 300),
                episode(EpisodeKind.InUse, 300, 310),
                episode(EpisodeKind.Asleep, 310, 400),
            ),
        )
        assertEquals(listOf(at(0) to at(300), at(310) to at(400)), spans)
    }
}
