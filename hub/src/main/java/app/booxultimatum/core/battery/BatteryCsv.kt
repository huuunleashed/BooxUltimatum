package app.booxultimatum.core.battery

import android.view.Display

/**
 * The battery log's row layout and the pure helpers that write its columns. Version 3 adds display, top and note at the
 * end. [BatteryRows] reads the files by column name; the Battery page reads the first ten columns by position, so those
 * keep their order.
 */
object BatteryCsv {
    const val HEADER = "epoch,reason,level,charge_mAh,voltage_mV,temp_C,plugged,screen_on,elapsed_ms,uptime_ms," +
        "boot_count,current_mA,frontlight,frontlight_ct,wifi,idle,saver,display,top,note"

    private const val HEADER_START = "epoch,"
    private const val NOTE_MAX = 80

    /** Commas and line breaks would split or break the row, so each becomes a space; the note is then trimmed and cut. */
    fun sanitizeNote(note: String): String =
        note.replace(',', ' ').replace('\r', ' ').replace('\n', ' ').trim().take(NOTE_MAX)

    /** The values joined with commas, null as empty. No line break: the caller adds it. */
    fun line(values: List<Any?>): String = values.joinToString(",") { it?.toString().orEmpty() }

    /** The last header line of a file (a line starting with `epoch,`), or null when it has none. */
    fun lastHeader(lines: Sequence<String>): String? = lines.lastOrNull { it.startsWith(HEADER_START) }

    /** The `display` column for a [Display] state: on, doze (doze or doze suspended), off, and other for any other state. */
    fun displayColumn(state: Int): String = when (state) {
        Display.STATE_ON -> "on"
        Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> "doze"
        Display.STATE_OFF -> "off"
        else -> "other"
    }

    /**
     * The `top` column from resume (true) and pause (false) events in time order: the latest resumed package that
     * hasn't been paused since, else the latest resumed one, else empty.
     */
    fun frontmost(events: List<Pair<String, Boolean>>): String {
        val open = LinkedHashSet<String>()
        var lastResumed = ""
        for ((pkg, resumed) in events) {
            open.remove(pkg)
            if (resumed) {
                open.add(pkg)
                lastResumed = pkg
            }
        }
        return open.lastOrNull() ?: lastResumed
    }
}
