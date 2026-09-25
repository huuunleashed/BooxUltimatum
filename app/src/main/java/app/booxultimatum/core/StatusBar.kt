package app.booxultimatum.core

import android.content.Context
import androidx.annotation.StringRes
import app.booxultimatum.R
import app.booxultimatum.core.exec.Diagnostics

/** A status bar icon slot SystemUI knows about. */
data class StatusSlot(val id: String, @StringRes val label: Int?, val raw: String)

/**
 * System-wide status bar control through Android's own mechanisms, so it applies over every screen including
 * the Boox home: `icon_blacklist` (secure) picks which icons SystemUI draws, and `status_bar_show_battery_percent`
 * (system) shows the figure. Verified on NA6C FW 4.3: hiding "wifi,battery" removed both from the real bar.
 */
object StatusBar {
    private const val KEY = "icon_blacklist"
    private const val JOURNAL_ID = "statusbar.icons"

    /** Slots worth offering, with names; Boox's own slots included. Order follows the bar, left to right. */
    private val KNOWN = linkedMapOf(
        "clock" to R.string.sb_clock,
        "refresh_mode" to R.string.sb_refresh_mode,
        "tp_touch_mode" to R.string.sb_touch_mode,
        "media_mute" to R.string.sb_media_mute,
        "alarm_clock" to R.string.sb_alarm,
        "zen" to R.string.sb_zen,
        "volume" to R.string.sb_volume,
        "status_bar_mute" to R.string.sb_mute,
        "headset" to R.string.sb_headset,
        "rotate" to R.string.sb_rotate,
        "location" to R.string.sb_location,
        "camera" to R.string.sb_camera,
        "microphone" to R.string.sb_microphone,
        "screen_record" to R.string.sb_screen_record,
        "cast" to R.string.sb_cast,
        "connected_display" to R.string.sb_display,
        "vpn" to R.string.sb_vpn,
        "hotspot" to R.string.sb_hotspot,
        "data_saver" to R.string.sb_data_saver,
        "bluetooth" to R.string.sb_bluetooth,
        "wifi" to R.string.sb_wifi,
        "airplane" to R.string.sb_airplane,
        "battery" to R.string.sb_battery,
        "sensors_off" to R.string.sb_sensors,
    )

    /** Live slots from SystemUI where readable, so Boox-added icons (like BOOXDrop's transfer tile) appear too. */
    suspend fun slots(): List<StatusSlot> {
        val live = runCatching {
            val r = Diagnostics.dumpsys("activity service com.android.systemui/.SystemUIService")
            Regex("""^\s+\d+:\((.+?)\) holder=StatusBarIconHolder""", RegexOption.MULTILINE).findAll(r.out).map { it.groupValues[1] }.toList()
        }.getOrDefault(emptyList())
        // Battery and a few core slots are drawn outside the icon holder list, so they are always offered.
        val core = listOf("clock", "battery", "wifi", "bluetooth", "airplane", "zen", "alarm_clock", "volume")
        val ids = (core + live.ifEmpty { KNOWN.keys.toList() }).distinct()
        return ids.mapNotNull { id ->
            when {
                id in KNOWN -> StatusSlot(id, KNOWN[id], id)
                id.startsWith("custom(") -> StatusSlot(id, null, id)
                else -> null
            }
        }.sortedBy { s -> KNOWN.keys.indexOf(s.id).let { if (it < 0) -1 else it } }
    }

    suspend fun hidden(context: Context): Set<String> =
        SystemSettings.get(context, Namespace.Secure, KEY)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    suspend fun setHidden(context: Context, slot: String, hide: Boolean): Result<Unit> {
        val current = hidden(context)
        if (Journal.original(context, JOURNAL_ID) == null) {
            Journal.rememberOriginal(context, JOURNAL_ID, org.json.JSONObject().put("value", current.joinToString(",")))
        }
        val next = if (hide) current + slot else current - slot
        // The value is a plain comma list; custom(...) slot names contain characters settings rejects, so they are quoted by the shell path.
        return putList(context, next).also { Journal.log(context, "statusbar", slot, if (hide) "hidden" else "shown", it.isSuccess) }
    }

    private suspend fun putList(context: Context, set: Set<String>): Result<Unit> {
        val value = set.joinToString(",").ifEmpty { null }
        if (app.booxultimatum.core.exec.Privileged.ready()) {
            val cmd = if (value == null) "settings delete secure $KEY" else "settings put secure $KEY '${value.replace("'", "")}'"
            val r = app.booxultimatum.core.exec.Privileged.sh(cmd)
            return if (r.ok) Result.success(Unit) else Result.failure(IllegalStateException(r.message))
        }
        return runCatching {
            check(android.provider.Settings.Secure.putString(context.contentResolver, KEY, value)) { "Android refused the change" }
        }
    }

    /** Back to exactly what the tablet had before BooxUltimatum first changed the bar. */
    suspend fun restore(context: Context): Result<Unit> {
        val original = Journal.original(context, JOURNAL_ID)?.optString("value").orEmpty()
        val r = putList(context, original.split(',').filter { it.isNotBlank() }.toSet())
        if (r.isSuccess) Journal.forget(context, JOURNAL_ID)
        Journal.log(context, "statusbar", "restore", "", r.isSuccess)
        return r
    }

    fun changed(context: Context) = Journal.original(context, JOURNAL_ID) != null

    /** The readable name of a slot, when it is one BooxUltimatum knows. */
    @StringRes fun label(id: String): Int? = KNOWN[id]

    suspend fun batteryPercent(context: Context): Boolean = SystemSettings.get(context, Namespace.System, "status_bar_show_battery_percent") == "1"

    suspend fun setBatteryPercent(context: Context, on: Boolean) = SystemSettings.put(context, Namespace.System, "status_bar_show_battery_percent", if (on) "1" else "0")
}
