package app.booxultimatum.core.battery

import java.util.TimeZone

/** OWNER: A1. Totals over a window of a [Timeline]. Episodes and segments that cross a window edge count in proportion. */
object Summaries {
    fun byState(t: Timeline, fromMs: Long, toMs: Long): List<StateTotal> = TODO("A1")

    fun byDay(t: Timeline, zone: TimeZone, fromMs: Long, toMs: Long): List<DaySummary> = TODO("A1")

    fun sessions(t: Timeline): List<DischargeSession> = TODO("A1")

    /** In-use segments' charge split among the apps in front, largest first. Segments with no known app go to an app with pkg "". */
    fun byApp(t: Timeline, fromMs: Long, toMs: Long, foreground: List<ForegroundSpan> = emptyList()): List<AppUse> = TODO("A1")
}