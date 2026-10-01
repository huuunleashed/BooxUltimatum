package app.booxultimatum.core.sleep

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.edit
import app.booxultimatum.core.Journal
import app.booxultimatum.core.exec.Privileged
import app.booxultimatum.kit.log.Logbook
import org.json.JSONObject
import java.util.Calendar

/** The owner's choices for live updates while asleep. Kept apart from the face, so changing them never redraws it. */
data class LivePrefs(
    val enabled: Boolean = false,
    val stepMin: Int = 5,
    val onlyCharging: Boolean = false,
    /** No updates overnight, from [quietFrom] to [quietTo] o'clock; the face stays as last drawn. */
    val quiet: Boolean = false,
    val quietFrom: Int = 23,
    val quietTo: Int = 7,
) {
    companion object {
        val STEPS = listOf(1, 5, 10, 15, 30, 60)
        private const val FILE = "sleep_live"

        fun load(c: Context): LivePrefs {
            val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return LivePrefs(
                enabled = p.getBoolean("enabled", false),
                stepMin = p.getInt("step", 5).takeIf { it in STEPS } ?: 5,
                onlyCharging = p.getBoolean("only_charging", false),
                quiet = p.getBoolean("quiet", false),
                quietFrom = p.getInt("quiet_from", 23).coerceIn(0, 23),
                quietTo = p.getInt("quiet_to", 7).coerceIn(0, 23),
            )
        }

        fun save(c: Context, v: LivePrefs) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putBoolean("enabled", v.enabled).putInt("step", v.stepMin).putBoolean("only_charging", v.onlyCharging)
                .putBoolean("quiet", v.quiet).putInt("quiet_from", v.quietFrom).putInt("quiet_to", v.quietTo)
        }
    }
}

/** What happened during the last sleep, for the status line and for spotting a firmware that cleared our alarm. */
data class LiveRecord(val sleptAt: Long, val wokeAt: Long, val updates: Int, val lastUpdate: Long, val missed: Boolean)

/**
 * Live sleep screen: the face is redrawn while the tablet sleeps, every few minutes (verified on NA6C FW 4.3,
 * 2026-09-27, on battery).
 *
 * Boox's sleep screen is Android's doze dream, `com.onyx/.common.dream.OnyxDaydreamService`, fixed by the firmware and
 * not replaceable. It loads our picture once at sleep and never reads it again, but it accepts an `onyx_dream_refresh`
 * broadcast from any app: it wakes the display for about 1.2 s, redraws and dozes again. Frames drawn while dozing
 * are held until then. So an alarm wakes us, the face is rendered, the refresh is sent, and once Onyx reports the
 * display on (`onyx.action.DISPLAY_CHANGED_STATE`, state 2) the new face is shown in an accessibility overlay: the
 * only kind of window the lock screen doesn't hide. The overlay also covers the charging bar Boox always draws.
 *
 * Three things must be allowed, all on the tablet itself (T0):
 * - [LiveSleepService] as an accessibility service. It reads nothing on screen; it only owns the overlay.
 * - Background use. Boox's power manager (`android.onyx.pm.OnyxAlarmHelper`) clears every wake-up alarm at sleep
 *   unless the app is on its full-access list, which an app joins when its background usage is switched to allowed.
 * - Exact alarms, granted with the app (`USE_EXACT_ALARM`).
 */
object LiveSleep {
    const val ACTION_TICK = "app.booxultimatum.action.LIVE_SLEEP_TICK"
    private const val STATE = "sleep_live_state"
    private const val A11Y_JOURNAL = "sleep.live.a11y"

    fun component(context: Context) = ComponentName(context, LiveSleepService::class.java)

    // ---------- Prerequisites ----------

    fun accessibilityOn(context: Context): Boolean {
        val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = component(context)
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Android's own per-app switch (App battery usage › Allow background usage), which Boox's freeze also flips. */
    fun backgroundAllowed(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == false

    /** Exact alarms need no grant before Android 12. */
    fun exactAlarms(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    fun ready(context: Context) = accessibilityOn(context) && backgroundAllowed(context)

    fun openAccessibility(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    @android.annotation.SuppressLint("InlinedApi") // guarded: returns before API 31
    fun openExactAlarms(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * Turns our accessibility service on directly, when the app holds WRITE_SECURE_SETTINGS (T1). The list as it was is
     * journalled, and [disableAccessibility] puts exactly that back.
     */
    fun enableAccessibility(context: Context): Boolean = runCatching {
        val cr = context.contentResolver
        val before = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        if (Journal.original(context, A11Y_JOURNAL) == null) {
            Journal.rememberOriginal(context, A11Y_JOURNAL, JSONObject().put("services", before).put("enabled", Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)))
        }
        val me = component(context).flattenToString()
        val next = (before.split(':').filter { it.isNotBlank() } + me).distinct().joinToString(":")
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        true
    }.getOrDefault(false)

    /** Takes our service back out of the list, leaving every other service as it is now. */
    fun disableAccessibility(context: Context): Boolean = runCatching {
        val cr = context.contentResolver
        val me = component(context)
        val now = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val rest = now.split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != me }
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, rest.joinToString(":"))
        val o = Journal.original(context, A11Y_JOURNAL)
        if (rest.isEmpty() && o != null && o.optInt("enabled", 1) == 0) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        Journal.forget(context, A11Y_JOURNAL)
        true
    }.getOrDefault(false)

    /**
     * Puts the app on Boox's power full-access list with the shell (T2): the list is only updated when the background
     * setting changes, so it's switched to restricted and straight back to allowed.
     */
    suspend fun registerWithBoox(context: Context): Boolean {
        if (!Privileged.ready()) return false
        val pkg = context.packageName
        val r = Privileged.sh("cmd appops set $pkg RUN_ANY_IN_BACKGROUND ignore; sleep 1; cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
        if (r.ok) state(context).edit { putBoolean("missed", false) }
        return r.ok
    }

    // ---------- Schedule ----------

    private fun tickIntent(context: Context, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context, 41, Intent(ACTION_TICK).setClass(context, LiveSleepReceiver::class.java),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The next wall-clock multiple of the step, so updates land on round minutes like the time the face shows. */
    internal fun nextTick(now: Long, stepMin: Int): Long {
        val step = stepMin * 60_000L
        val next = (now / step + 1) * step
        // Never fire an alarm a few seconds away; the 1-minute step keeps a shorter guard so it still lands every minute.
        val minGap = minOf(30_000L, step / 4)
        return if (next - now < minGap) next + step else next
    }

    fun schedule(context: Context, prefs: LivePrefs = LivePrefs.load(context)) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = tickIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val at = nextTick(System.currentTimeMillis(), prefs.stepMin)
        if (exactAlarms(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        state(context).edit { putLong("next", at) }
    }

    fun cancel(context: Context) {
        tickIntent(context, PendingIntent.FLAG_NO_CREATE)?.let { context.getSystemService(AlarmManager::class.java)?.cancel(it); it.cancel() }
        state(context).edit { remove("next") }
    }

    /** The wall time the alarm this tick answers was set for (0 when unknown). Read before re-arming overwrites it. */
    internal fun nextDue(c: Context): Long = state(c).getLong("next", 0)

    /** Whether this moment is one the owner wants updates at: charging only, and outside quiet hours. */
    fun wantedNow(context: Context, prefs: LivePrefs, now: Long = System.currentTimeMillis()): Boolean {
        if (prefs.onlyCharging) {
            val charging = context.getSystemService(BatteryManager::class.java)?.isCharging == true
            if (!charging) return false
        }
        if (prefs.quiet && prefs.quietFrom != prefs.quietTo) {
            val h = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
            val inQuiet = if (prefs.quietFrom < prefs.quietTo) h in prefs.quietFrom until prefs.quietTo else h >= prefs.quietFrom || h < prefs.quietTo
            if (inQuiet) return false
        }
        return true
    }

    fun active(context: Context): Boolean {
        val spec = SleepStore.load(context)
        return LivePrefs.load(context).enabled && spec.active && spec.mode == SleepMode.Image
    }

    fun interactive(context: Context) = context.getSystemService(PowerManager::class.java)?.isInteractive != false

    // ---------- Record ----------

    private fun state(c: Context) = c.getSharedPreferences(STATE, Context.MODE_PRIVATE)

    internal fun onSlept(c: Context) {
        val level = runCatching { c.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrDefault(-1)
        state(c).edit { putLong("slept", System.currentTimeMillis()).putLong("woke", 0).putInt("updates", 0).putInt("level", level) }
    }

    /** The battery level when the tablet went to sleep, for the "used asleep" reading. */
    fun levelAtSleep(c: Context): Int? = state(c).getInt("level", -1).takeIf { it in 0..100 }

    internal fun onUpdated(c: Context) = state(c).edit {
        putInt("updates", state(c).getInt("updates", 0) + 1).putLong("last", System.currentTimeMillis())
    }

    /**
     * On wake: a sleep long enough for two updates that got none means the alarm never fired, which is what Boox's
     * power manager does to apps outside its full-access list. The Sleep page then says how to fix it.
     */
    internal fun onWoke(c: Context, prefs: LivePrefs) {
        val s = state(c)
        val slept = s.getLong("slept", 0)
        if (slept == 0L) return
        val now = System.currentTimeMillis()
        val missed = s.getInt("updates", 0) == 0 && now - slept > 2 * prefs.stepMin * 60_000L + 60_000L && wantedNow(c, prefs, slept + prefs.stepMin * 60_000L)
        s.edit { putLong("woke", now).putBoolean("missed", missed) }
    }

    fun record(c: Context): LiveRecord? {
        val s = state(c)
        val slept = s.getLong("slept", 0).takeIf { it > 0 } ?: return null
        return LiveRecord(slept, s.getLong("woke", 0), s.getInt("updates", 0), s.getLong("last", 0), s.getBoolean("missed", false))
    }
}

/** The alarm lands here and is handed to the running accessibility service; without it there's nothing to draw into. */
class LiveSleepReceiver : BroadcastReceiver() {
    private val log = Logbook.logger("sleep.live")

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LiveSleep.ACTION_TICK) return
        // Skew first, while the state still holds the alarm this tick answers: a late tick is the firmware
        // holding the alarm, not the app missing it. Logging only; the schedule below is untouched.
        val due = LiveSleep.nextDue(context.applicationContext)
        val skewMs = if (due > 0) System.currentTimeMillis() - due else 0
        if (due > 0 && skewMs > 90_000) log.w("tick late", "skew_s" to skewMs / 1_000)
        else log.i("tick", "skew_s" to skewMs / 1_000)
        // Re-arm first: a tick that finds no bound service must not end the chain.
        runCatching { LiveSleep.schedule(context.applicationContext) }
        LiveSleepService.tick(context.applicationContext)
    }
}
