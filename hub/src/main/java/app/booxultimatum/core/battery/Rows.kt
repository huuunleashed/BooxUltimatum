package app.booxultimatum.core.battery

import android.content.Context

/** OWNER: A1. Reads the battery log's CSV files into rows. */
object BatteryRows {
    /**
     * Parses CSV lines of any layout. A line starting with `epoch,` is a header and sets the columns for the lines after
     * it (a file can hold several headers, since the log appends a new one when its layout grows); lines before any
     * header use the oldest layout. Bad lines are skipped. Rows come back sorted by epoch.
     */
    fun parse(lines: Sequence<String>): List<BatteryRow> = TODO("A1")

    /** Every `battery-*.csv` in the log folder, parsed, keeping rows at or after [sinceMs]. Blocking: call off the main thread. */
    fun read(context: Context, sinceMs: Long): List<BatteryRow> = TODO("A1")
}