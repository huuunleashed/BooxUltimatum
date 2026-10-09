package app.booxultimatum.core.battery

/** OWNER: A1. Rows to segments to episodes. */
object Timelines {
    /**
     * Classifies each consecutive pair of rows, merges runs into episodes and learns the capacity. [foreground] (UsageStats
     * spans) names the in-use app where the rows' own `top` column is empty. Pure: no Android, no clock but [nowMs].
     */
    fun build(rows: List<BatteryRow>, nowMs: Long, foreground: List<ForegroundSpan> = emptyList()): Timeline = TODO("A1")
}