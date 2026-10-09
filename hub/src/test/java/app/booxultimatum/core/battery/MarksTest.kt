package app.booxultimatum.core.battery

import app.booxultimatum.core.JournalEntry
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

private const val T0 = 1_767_225_600_000L
private const val MARK = 6_000

private fun at(minutes: Int): Long = T0 + minutes * 60_000L

private fun episode(kind: EpisodeKind, fromMin: Int, toMin: Int, ma: Double?) =
    Episode(kind, at(fromMin), at(toMin), 80, 79, ma?.let { it * (toMin - fromMin) / 60.0 }, 0.9, null)

private fun asleep(fromMin: Int, toMin: Int, ma: Double?) = episode(EpisodeKind.Asleep, fromMin, toMin, ma)

private val BASE = Baseline(quietAsleepMa = 9.0, dayToDaySpreadMa = 2.0, inUseMa = 291.0, asleepShare = 0.78, basisDays = 7.0)

private fun row(epoch: Long, reason: String, note: String = "") = BatteryRow(
    epoch = epoch,
    reason = reason,
    level = 50,
    chargeMah = null,
    voltageMv = null,
    tempC = null,
    plugged = false,
    interactive = false,
    elapsedMs = 0L,
    uptimeMs = 0L,
    bootCount = null,
    currentMa = null,
    frontlight = null,
    warmth = null,
    wifi = "",
    idle = "",
    saver = false,
    display = null,
    top = "",
    note = note,
)

/** A mark at [MARK], with the timeline ending at [toMin] (by default 72 hours after the mark). */
private fun around(
    vararg episodes: Episode,
    toMin: Int = MARK + 4_320,
    baseline: Baseline = BASE,
): BeforeAfter = Marks.beforeAfter(
    Timeline(episodes.toList(), emptyList(), 3750.0, T0, at(toMin)),
    Mark(at(MARK), "Test", MarkSource.Manual),
    baseline,
)

private fun assertNear(expected: Double, actual: Double?) =
    assertEquals(expected, actual ?: fail("expected $expected, was null"), 1e-6)

class MarksTest {
    @Test
    fun fromRowsKeepsTheMarkRowsAsManualMarks() {
        val marks = Marks.fromRows(
            listOf(row(at(0), "tick"), row(at(10), "mark", "Changed the font"), row(at(20), "unplug"), row(at(30), "mark", "Night 2")),
        )
        assertEquals(listOf(Mark(at(10), "Changed the font", MarkSource.Manual), Mark(at(30), "Night 2", MarkSource.Manual)), marks)
    }

    @Test
    fun fromJournalLabelsAppliedAndUndoneTweaks() {
        val marks = Marks.fromJournal(
            listOf(
                JournalEntry(1_000, "apply", "Stop the status bar", "", true),
                JournalEntry(2_000, "undo", "Stop the status bar", "", true),
                JournalEntry(3_000, "apply", "Fonts", "failed", false),
                JournalEntry(4_000, "font", "Serif", "", true),
                JournalEntry(5_000, "undo", "Serif", "", false),
            ),
        )
        assertEquals(
            listOf(
                Mark(1_000, "Applied Stop the status bar", MarkSource.Tweak),
                Mark(2_000, "Undid Stop the status bar", MarkSource.Tweak),
            ),
            marks,
        )
    }

    @Test
    fun beforeAndAfterNeedTwelveHoursAsleepOnEachSide() {
        val short = around(asleep(MARK - 660, MARK, 9.0), asleep(MARK, MARK + 1_200, 9.0))
        assertEquals(BeforeAfterVerdict.NotEnoughData, short.verdict)
        assertNear(11.0, short.beforeHours)
        assertNear(20.0, short.afterHours)
        assertNear(9.0, short.beforeMa)
        assertEquals(
            BeforeAfterVerdict.NotEnoughData,
            around(asleep(MARK - 1_200, MARK, 9.0), asleep(MARK, MARK + 660, 9.0)).verdict,
        )
    }

    @Test
    fun aChangeSmallerThanTheSpreadIsWithinNormal() {
        val b = around(asleep(MARK - 720, MARK, 9.0), asleep(MARK, MARK + 720, 10.0))
        assertEquals(Mark(at(MARK), "Test", MarkSource.Manual), b.mark)
        assertEquals(BeforeAfterVerdict.WithinNormal, b.verdict)
        assertNear(2.0, b.spreadMa)
        assertNear(9.0, b.beforeMa)
        assertNear(10.0, b.afterMa)
    }

    @Test
    fun aChangeBeyondTheSpreadIsLowerOrHigher() {
        assertEquals(BeforeAfterVerdict.Lower, around(asleep(MARK - 720, MARK, 9.0), asleep(MARK, MARK + 720, 6.0)).verdict)
        assertEquals(BeforeAfterVerdict.Higher, around(asleep(MARK - 720, MARK, 9.0), asleep(MARK, MARK + 720, 13.0)).verdict)
    }

    @Test
    fun withoutASpreadTheThresholdIsTheLargerOfTwoMilliampsAndAQuarterOfBefore() {
        val noSpread = BASE.copy(dayToDaySpreadMa = null)
        val within = around(asleep(MARK - 720, MARK, 20.0), asleep(MARK, MARK + 720, 24.0), baseline = noSpread)
        assertEquals(BeforeAfterVerdict.WithinNormal, within.verdict)
        assertNull(within.spreadMa)
        assertEquals(
            BeforeAfterVerdict.Higher,
            around(asleep(MARK - 720, MARK, 20.0), asleep(MARK, MARK + 720, 26.0), baseline = noSpread).verdict,
        )
        assertEquals(
            BeforeAfterVerdict.Higher,
            around(asleep(MARK - 720, MARK, 4.0), asleep(MARK, MARK + 720, 7.0), baseline = noSpread).verdict,
        )
    }

    @Test
    fun theWindowsAreSeventyTwoHoursEitherSideAndStopAtTheTimelineEnd() {
        val full = around(asleep(0, MARK, 9.0), asleep(MARK, 12_000, 9.0))
        assertNear(72.0, full.beforeHours)
        assertNear(72.0, full.afterHours)
        val cut = around(asleep(MARK, 12_000, 9.0), toMin = 8_000)
        assertNear(2_000 / 60.0, cut.afterHours)
    }

    @Test
    fun anEpisodeStraddlingTheMarkIsSplitByTime() {
        val b = around(asleep(5_000, 7_000, 9.0))
        assertNear(1_000 / 60.0, b.beforeHours)
        assertNear(1_000 / 60.0, b.afterHours)
        assertNear(9.0, b.beforeMa)
        assertNear(9.0, b.afterMa)
    }

    @Test
    fun onlyAsleepTimeWithChargeCounts() {
        val b = around(
            asleep(MARK - 720, MARK, 9.0),
            asleep(MARK, MARK + 720, 10.0),
            episode(EpisodeKind.DisplayOn, MARK + 720, MARK + 1_320, 500.0),
            episode(EpisodeKind.InUse, MARK + 1_320, MARK + 1_500, 300.0),
            asleep(MARK + 1_500, MARK + 1_600, null),
        )
        assertNear(10.0, b.afterMa)
        assertNear(12.0, b.afterHours)
    }

    @Test
    fun noAsleepTimeMeansNoRates() {
        val b = around(episode(EpisodeKind.InUse, 0, 600, 300.0))
        assertEquals(BeforeAfterVerdict.NotEnoughData, b.verdict)
        assertNull(b.beforeMa)
        assertNull(b.afterMa)
        assertEquals(0.0, b.afterHours)
    }
}
