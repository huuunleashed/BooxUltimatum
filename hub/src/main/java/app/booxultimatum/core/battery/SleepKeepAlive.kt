package app.booxultimatum.core.battery

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.edit
import app.booxultimatum.core.BatteryLog
import app.booxultimatum.kit.log.Logbook

/** The keep-alive's rules as pure functions, so they are tested without a device. */
object SleepKeepAlivePolicy {
    /** Half the 90 minutes after which the display stuck on, so a late alarm still has an hour of margin. */
    const val INTERVAL_MS = 30 * 60_000L

    /** An alarm that comes this much early still counts as due. */
    const val EARLY_SLACK_MS = 60_000L

    /** The next alarm is never nearer than this, so a refresh that just ran is not followed by another. */
    const val MIN_GAP_MS = 30_000L

    /** A sleep this long with no refresh at all means the alarm never fired. */
    const val MISSED_AFTER_MS = 2 * INTERVAL_MS

    /** Whether the sleep screen has gone long enough without a refresh that this alarm must send one. */
    fun due(lastRefreshMs: Long, nowMs: Long): Boolean = nowMs - lastRefreshMs >= INTERVAL_MS - EARLY_SLACK_MS

    /** When the next alarm is set for: a full interval after the last refresh, and never sooner than [MIN_GAP_MS] from now. */
    fun nextAtMs(lastRefreshMs: Long, nowMs: Long): Long = maxOf(lastRefreshMs + INTERVAL_MS, nowMs + MIN_GAP_MS)

    /** A sleep of two intervals or more in which not even one refresh was sent: Boox cleared the alarm, or the app was frozen. */
    fun missed(sleptMs: Long, wokeMs: Long, lastRefreshMs: Long): Boolean =
        sleptMs > 0 && wokeMs - sleptMs >= MISSED_AFTER_MS && lastRefreshMs <= sleptMs
}

/**
 * Keeps Boox's sleep screen from being left with the display on (docs §4.8). On this tablet the display went ON exactly
 * 90 minutes after the sleep screen last went to doze, with no wake and no way back, on 2026-10-08 (twice), 2026-10-12
 * and 2026-10-13, each time after Android's system process had restarted and before the tablet had been restarted. The
 * live sleep screen refreshes every few minutes in the day, which restarts that clock and which no such night had; the
 * stuck nights were the ones with no refresh in between. So while the tablet sleeps and nothing else has refreshed the
 * sleep screen for [SleepKeepAlivePolicy.INTERVAL_MS], this asks it to, which costs about 0.04 mAh a time.
 *
 * A pattern from four episodes, not a proven cause *[verify]*: whether it prevents the stuck display can only be seen the
 * next time Android restarts by itself, and the log says what it did (`keepalive` row at each wake, Logbook line at each refresh).
 *
 * It runs on its own alarm, armed at each screen-off by the battery log's receiver, so it works whether or not live
 * updates, quiet hours or the accessibility service are on.
 */
object SleepKeepAlive {
    const val ACTION = "app.booxultimatum.action.KEEP_ALIVE"
    private const val PREFS = "sleep_keepalive"
    private const val ON = "on"
    private const val SLEPT = "slept_at"
    private const val LAST = "last_refresh"
    private const val SENT = "sent"
    private const val REFRESH = "onyx_dream_refresh"
    private const val REQUEST = 42
    private const val HOLD_MS = 4_000L
    private val log = Logbook.logger("battery")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** On by default: it only acts while the tablet sleeps, and costs well under 1 mAh a night. */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(ON, true)

    fun setEnabled(context: Context, on: Boolean) {
        val app = context.applicationContext
        prefs(app).edit { putBoolean(ON, on) }
        if (!on) cancel(app) else if (!interactive(app)) onSleep(app)
    }

    private fun interactive(app: Context) = app.getSystemService(PowerManager::class.java)?.isInteractive != false

    /** The screen went off: the sleep screen has just gone to doze, which is when its clock starts. */
    fun onSleep(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        if (!enabled(app)) return
        prefs(app).edit { putLong(SLEPT, nowMs).putLong(LAST, nowMs).putInt(SENT, 0) }
        arm(app, SleepKeepAlivePolicy.nextAtMs(nowMs, nowMs))
    }

    /** The screen came on: ends the chain, and writes one `keepalive` row when it sent a refresh or never ran. */
    fun onWake(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        cancel(app)
        val p = prefs(app)
        val slept = p.getLong(SLEPT, 0)
        if (slept <= 0 || !enabled(app)) return
        val sent = p.getInt(SENT, 0)
        val minutes = (nowMs - slept) / 60_000L
        val missed = SleepKeepAlivePolicy.missed(slept, nowMs, p.getLong(LAST, 0))
        p.edit { remove(SLEPT).putInt(SENT, 0) }
        if (missed) {
            log.w("keep-alive never ran in a long sleep", "min" to minutes)
            BatteryLog.record(app, "keepalive", note = "missed min=$minutes")
        } else if (sent > 0) {
            BatteryLog.record(app, "keepalive", note = "sent=$sent min=$minutes")
        }
    }

    /** Process start: a sleep that began while no receiver was listening is treated as having just begun. */
    fun sync(context: Context) {
        val app = context.applicationContext
        if (enabled(app) && !interactive(app) && prefs(app).getLong(SLEPT, 0) == 0L) onSleep(app)
    }

    /** Something else refreshed the sleep screen (a live update, the guard), which restarts its clock too. */
    fun noteRefresh(context: Context, nowMs: Long = System.currentTimeMillis()) {
        prefs(context.applicationContext).edit { putLong(LAST, nowMs) }
    }

    /** The alarm: refreshes the sleep screen if nothing has for an interval, and sets the next one. [done] ends the broadcast. */
    internal fun onAlarm(context: Context, done: () -> Unit) {
        val app = context.applicationContext
        val p = prefs(app)
        if (!enabled(app) || interactive(app)) {
            p.edit { remove(SLEPT) }
            done()
            return
        }
        val now = System.currentTimeMillis()
        if (SleepKeepAlivePolicy.due(p.getLong(LAST, 0), now)) {
            refresh(app)
            p.edit { putLong(LAST, now).putInt(SENT, p.getInt(SENT, 0) + 1) }
            log.i("keep-alive refresh", "sent" to p.getInt(SENT, 0))
        }
        arm(app, SleepKeepAlivePolicy.nextAtMs(p.getLong(LAST, now), now))
        runCatching { DisplayWatch.recheck(app) }
        done()
    }

    private fun refresh(app: Context) {
        val lock = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BooxUltimatum:keepalive")
        runCatching { lock.acquire(HOLD_MS) }
        app.sendBroadcast(Intent(REFRESH))
    }

    private fun pending(app: Context, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        app, REQUEST, Intent(ACTION).setClass(app, KeepAliveReceiver::class.java), flags or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun arm(app: Context, atWallMs: Long) {
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(app, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val delay = (atWallMs - System.currentTimeMillis()).coerceAtLeast(SleepKeepAlivePolicy.MIN_GAP_MS)
        val at = SystemClock.elapsedRealtime() + delay
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
    }

    private fun cancel(app: Context) {
        pending(app, PendingIntent.FLAG_NO_CREATE)?.let { app.getSystemService(AlarmManager::class.java)?.cancel(it); it.cancel() }
    }
}

/** The keep-alive's alarm lands here, in a process Android starts for it if the hub's has gone. */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SleepKeepAlive.ACTION) return
        val pending = goAsync()
        SleepKeepAlive.onAlarm(context) { pending.finish() }
    }
}
