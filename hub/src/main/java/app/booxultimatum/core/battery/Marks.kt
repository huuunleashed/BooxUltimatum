package app.booxultimatum.core.battery

import app.booxultimatum.core.JournalEntry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** OWNER: A2. Marks and before-and-after. */
object Marks {
    private const val WINDOW_MS = 72 * 3_600_000L
    private const val MIN_HOURS = 12.0
    private const val SPREAD_FLOOR_MA = 2.0
    private const val SPREAD_SHARE = 0.25

    /** Rows with reason `mark`: the label is the row's note. */
    fun fromRows(rows: List<BatteryRow>): List<Mark> =
        rows.filter { it.reason == "mark" }.map { Mark(it.epoch, it.note, MarkSource.Manual) }

    /** Journal entries whose action is `apply` or `undo` (tweaks), as marks: label "Applied <subject>" or "Undid <subject>". */
    fun fromJournal(entries: List<JournalEntry>): List<Mark> =
        entries.filter { it.ok && (it.action == "apply" || it.action == "undo") }.map {
            Mark(it.at, if (it.action == "apply") "Applied ${it.subject}" else "Undid ${it.subject}", MarkSource.Tweak)
        }

    fun beforeAfter(t: Timeline, mark: Mark, baseline: Baseline): BeforeAfter {
        val before = quietRate(t.episodes, mark.epoch - WINDOW_MS, mark.epoch)
        val after = quietRate(t.episodes, mark.epoch, min(t.toMs, mark.epoch + WINDOW_MS))
        val beforeMa = before.ma
        val afterMa = after.ma
        val verdict = when {
            beforeMa == null || afterMa == null || before.hours < MIN_HOURS || after.hours < MIN_HOURS ->
                BeforeAfterVerdict.NotEnoughData
            else -> {
                val diff = afterMa - beforeMa
                val spread = baseline.dayToDaySpreadMa ?: max(SPREAD_FLOOR_MA, SPREAD_SHARE * beforeMa)
                when {
                    abs(diff) <= spread -> BeforeAfterVerdict.WithinNormal
                    diff < 0 -> BeforeAfterVerdict.Lower
                    else -> BeforeAfterVerdict.Higher
                }
            }
        }
        return BeforeAfter(mark, beforeMa, afterMa, before.hours, after.hours, baseline.dayToDaySpreadMa, verdict)
    }

    private class Rate(val ma: Double?, val hours: Double)

    private fun quietRate(episodes: List<Episode>, fromMs: Long, toMs: Long): Rate {
        var hours = 0.0
        var mah = 0.0
        for (e in episodes) {
            val charge = e.mah ?: continue
            if (e.kind != EpisodeKind.Asleep) continue
            val inside = Baselines.overlapHours(e, fromMs, toMs)
            if (inside <= 0.0) continue
            hours += inside
            mah += charge * inside / e.hours
        }
        return Rate(if (hours > 0.0) mah / hours else null, hours)
    }
}
