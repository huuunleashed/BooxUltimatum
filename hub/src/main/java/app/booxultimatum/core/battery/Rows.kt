package app.booxultimatum.core.battery

import android.content.Context
import app.booxultimatum.core.BatteryLog

/** OWNER: A1. Reads the battery log's CSV files into rows. */
object BatteryRows {
    private val v1 = Columns(
        listOf("epoch", "reason", "level", "charge_mAh", "voltage_mV", "temp_C", "plugged", "screen_on", "elapsed_ms", "uptime_ms"),
    )

    /**
     * Parses CSV lines of any layout. A line starting with `epoch,` is a header and sets the columns for the lines after
     * it (a file can hold several headers, since the log appends a new one when its layout grows); lines before any
     * header use the oldest layout. Bad lines are skipped. Rows come back sorted by epoch.
     */
    fun parse(lines: Sequence<String>): List<BatteryRow> {
        var columns = v1
        val rows = ArrayList<BatteryRow>()
        for (line in lines) {
            if (line.isBlank()) continue
            if (line.startsWith("epoch,")) {
                columns = Columns(line.split(",").map { it.trim() })
                continue
            }
            columns.row(line)?.let { rows += it }
        }
        return rows.sortedBy { it.epoch }
    }

    /** Every `battery-*.csv` in the log folder, parsed, keeping rows at or after [sinceMs]. Blocking: call off the main thread. */
    fun read(context: Context, sinceMs: Long): List<BatteryRow> {
        val files = BatteryLog.dir(context).listFiles { f -> f.name.startsWith("battery-") && f.name.endsWith(".csv") }.orEmpty()
        return files.sortedBy { it.name }
            .flatMap { file -> file.bufferedReader().use { parse(it.lineSequence()) } }
            .filter { it.epoch >= sinceMs }
            .sortedBy { it.epoch }
    }
}

/** The names of one header's columns, so every line is read by name and a layout can grow without moving a field. */
private class Columns(names: List<String>) {
    private val at = names.withIndex().associate { (i, name) -> name to i }
    private val width = names.size
    private val required = if (names.lastOrNull() == "note") width - 1 else width

    /** The row a line holds, or null when it is short or holds a number that doesn't parse. */
    fun row(line: String): BatteryRow? {
        val values = line.split(",", limit = width)
        if (values.size < required) return null
        val f = Fields(at, values)
        return try {
            BatteryRow(
                epoch = f.text("epoch").toLong(),
                reason = f.text("reason"),
                level = f.int("level") ?: 0,
                chargeMah = f.double("charge_mAh"),
                voltageMv = f.int("voltage_mV"),
                tempC = f.double("temp_C"),
                plugged = f.flag("plugged"),
                interactive = f.flag("screen_on"),
                elapsedMs = f.long("elapsed_ms"),
                uptimeMs = f.long("uptime_ms"),
                bootCount = f.int("boot_count"),
                currentMa = f.double("current_mA"),
                frontlight = f.int("frontlight"),
                warmth = f.int("frontlight_ct"),
                wifi = f.text("wifi"),
                idle = f.text("idle"),
                saver = f.flag("saver"),
                display = DisplayState.parse(f.text("display")),
                top = f.text("top"),
                note = f.text("note"),
            )
        } catch (e: NumberFormatException) {
            null
        }
    }
}

/** One line's values, read by column name. A blank field is empty text, null, false or 0, as the row type says. */
private class Fields(private val at: Map<String, Int>, private val values: List<String>) {
    fun text(name: String): String = at[name]?.let { values.getOrNull(it) }?.trim().orEmpty()

    fun long(name: String): Long = text(name).takeIf { it.isNotEmpty() }?.toLong() ?: 0L

    fun int(name: String): Int? = text(name).takeIf { it.isNotEmpty() }?.toInt()

    fun double(name: String): Double? = text(name).takeIf { it.isNotEmpty() }?.toDouble()

    fun flag(name: String): Boolean = text(name).let { it == "1" || it.equals("true", ignoreCase = true) }
}
