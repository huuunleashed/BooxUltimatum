package app.booxultimatum.core.battery

import android.content.Context
import androidx.core.content.edit

/**
 * The settings of the battery alerts and what they last did: the morning report's switch, the sleep guard's switch and
 * its last action, and the markers that keep one report from being sent twice. Kept in their own preferences, not the log.
 */
object BatteryAlerts {
    private const val PREFS = "battery_alerts"
    private const val REPORT_ON = "report_on"
    private const val GUARD_ON = "guard_on"
    private const val GUARD_AT = "guard_at"
    private const val GUARD_SINCE = "guard_since"
    private const val GUARD_HEALED = "guard_healed"
    private const val REPORT_KEY = "report_key"
    private const val DIED_KEY = "died_key"
    private const val WAKE_AT = "wake_at"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** On by default: the note is short, and it says what the owner's own nights looked like. */
    fun reportEnabled(context: Context): Boolean = prefs(context).getBoolean(REPORT_ON, true)

    fun setReportEnabled(context: Context, on: Boolean) = prefs(context).edit { putBoolean(REPORT_ON, on) }

    /** Off until the owner turns it on: the guard is Experimental. */
    fun guardEnabled(context: Context): Boolean = prefs(context).getBoolean(GUARD_ON, false)

    fun setGuardEnabled(context: Context, on: Boolean) = prefs(context).edit { putBoolean(GUARD_ON, on) }

    fun lastGuard(context: Context): GuardRecord? {
        val p = prefs(context)
        val at = p.getLong(GUARD_AT, 0)
        if (at <= 0) return null
        return GuardRecord(at, p.getLong(GUARD_SINCE, 0), p.getInt(GUARD_HEALED, 0).takeIf { it > 0 })
    }

    fun saveGuard(context: Context, record: GuardRecord) = prefs(context).edit {
        putLong(GUARD_AT, record.atMs)
        putLong(GUARD_SINCE, record.sinceMs)
        putInt(GUARD_HEALED, record.healedByStep ?: 0)
    }

    /** The start of the sleep that the last morning report was sent for. */
    fun lastReportKey(context: Context): Long? = prefs(context).getLong(REPORT_KEY, 0).takeIf { it > 0 }

    fun setLastReportKey(context: Context, key: Long) = prefs(context).edit { putLong(REPORT_KEY, key) }

    /** The start of the death that the last "ran out" report was sent for. */
    fun lastDiedKey(context: Context): Long? = prefs(context).getLong(DIED_KEY, 0).takeIf { it > 0 }

    fun setLastDiedKey(context: Context, key: Long) = prefs(context).edit { putLong(DIED_KEY, key) }

    /** When the tablet last woke: the owner's last input, as far as the hub saw it. */
    fun lastWakeMs(context: Context): Long? = prefs(context).getLong(WAKE_AT, 0).takeIf { it > 0 }

    fun noteWake(context: Context, atMs: Long) = prefs(context).edit { putLong(WAKE_AT, atMs) }
}

/** What the guard did: [atMs] when it began, [sinceMs] when the display went on, and [healedByStep] (1 to 3), or null when no step healed it. */
data class GuardRecord(val atMs: Long, val sinceMs: Long, val healedByStep: Int?)
