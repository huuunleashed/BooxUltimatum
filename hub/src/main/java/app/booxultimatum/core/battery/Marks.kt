package app.booxultimatum.core.battery

import app.booxultimatum.core.JournalEntry

/** OWNER: A2. Marks and before-and-after. */
object Marks {
    /** Rows with reason `mark`: the label is the row's note. */
    fun fromRows(rows: List<BatteryRow>): List<Mark> = TODO("A2")

    /** Journal entries whose action is `apply` or `undo` (tweaks), as marks: label "Applied <subject>" or "Undid <subject>". */
    fun fromJournal(entries: List<JournalEntry>): List<Mark> = TODO("A2")

    fun beforeAfter(t: Timeline, mark: Mark, baseline: Baseline): BeforeAfter = TODO("A2")
}