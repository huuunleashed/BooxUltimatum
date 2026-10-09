package app.booxultimatum.ui.battery

import android.content.Context
import app.booxultimatum.core.battery.EpisodeKind
import app.booxultimatum.core.battery.Finding
import app.booxultimatum.core.battery.Severity
import app.booxultimatum.core.battery.Timeline
import app.booxultimatum.core.battery.VerdictState

/** OWNER: D1. Words and numbers of the Battery section, all from string resources. The signatures are final. */
object BatteryText {
    fun duration(context: Context, ms: Long): String = TODO("D1")

    fun mah(context: Context, v: Double): String = TODO("D1")

    fun ma(context: Context, v: Double): String = TODO("D1")

    fun pctPerHour(context: Context, ma: Double, capacityMah: Double): String = TODO("D1")

    fun kindLabel(context: Context, kind: EpisodeKind): String = TODO("D1")

    fun timeRange(context: Context, startMs: Long, endMs: Long): String = TODO("D1")

    fun day(context: Context, ms: Long): String = TODO("D1")

    fun findingTitle(context: Context, f: Finding): String = TODO("D1")

    fun findingEvidence(context: Context, f: Finding): String = TODO("D1")

    fun severityLabel(context: Context, s: Severity): String = TODO("D1")

    fun verdictTitle(context: Context, v: VerdictState): String = TODO("D1")

    fun summarySentence(context: Context, timeline: Timeline, fromMs: Long, toMs: Long): String = TODO("D1")
}