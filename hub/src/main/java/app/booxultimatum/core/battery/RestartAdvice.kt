package app.booxultimatum.core.battery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.edit
import app.booxultimatum.MainActivity
import app.booxultimatum.R
import app.booxultimatum.kit.log.Logbook
import java.util.concurrent.Executors

/**
 * Android restarted by itself, and the tablet has not been restarted since. On this tablet that is the state both
 * stuck-display nights began in: the system process crashed and came back (2026-10-08 at 16:32, 2026-10-11 at 20:42,
 * both seen in the dropbox as `system_server_crash` then `SYSTEM_RESTART`), the first long sleep after it ended with
 * the display forced on 90 minutes in, and a real restart on 2026-10-09 was followed by three clean nights. Two cases
 * show a pattern, not a cause *[verify]*, but a restart costs a minute and a night's battery costs more, so the owner
 * is told with the reason while there is time to act. The first note was quiet and went unseen through the night of
 * 2026-10-13, so it now stays in the shade until the tablet is restarted, and comes back every 12 hours if swiped away.
 */
object RestartAdvice {
    private const val PREFS = "battery_alerts"
    private const val ADVISED_AT = "restart_advised_at"
    private const val REMINDED_AT = "restart_reminded_at"
    private const val CHANNEL = "battery_restart"
    private const val NOTIFICATION_ID = 63
    private const val OPEN_REQUEST = 64
    private const val REMIND_EVERY_MS = 12 * 3600_000L
    private const val CHECK_EVERY_MS = 10 * 60_000L
    @Volatile private var lastCheckMs = 0L
    private val log = Logbook.logger("battery")
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "restart-advice").apply { isDaemon = true } }

    /** Whether a restart at [restartAtMs] is one the owner should hear about: after the last boot, and not advised before. */
    internal fun shouldAdvise(restartAtMs: Long?, bootAtMs: Long, advisedAtMs: Long): Boolean =
        restartAtMs != null && restartAtMs >= bootAtMs && restartAtMs > advisedAtMs

    internal enum class Advice { None, First, Reminder }

    /** What to do now: say it the first time a restart is seen, remind every [REMIND_EVERY_MS] while it is still not cleared. */
    internal fun decide(restartAtMs: Long?, bootAtMs: Long, advisedAtMs: Long, remindedAtMs: Long, nowMs: Long): Advice = when {
        shouldAdvise(restartAtMs, bootAtMs, advisedAtMs) -> Advice.First
        restartAtMs != null && restartAtMs >= bootAtMs && nowMs - remindedAtMs >= REMIND_EVERY_MS -> Advice.Reminder
        else -> Advice.None
    }

    /** Looks at the dropbox, off the main thread, at most every 10 minutes, and posts the note when it is due. Cheap to call often. */
    fun check(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        if (now - lastCheckMs < CHECK_EVERY_MS) return
        lastCheckMs = now
        worker.execute { runCatching { run(app) }.onFailure { log.w("restart advice failed", error = it) } }
    }

    private fun run(app: Context) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val restartAt = SignalReader.restartAtMs(app)
        val now = System.currentTimeMillis()
        val bootAt = now - SystemClock.elapsedRealtime()
        val advice = decide(restartAt, bootAt, prefs.getLong(ADVISED_AT, 0L), prefs.getLong(REMINDED_AT, 0L), now)
        if (advice == Advice.None) return
        prefs.edit { putLong(ADVISED_AT, restartAt ?: 0L).putLong(REMINDED_AT, now) }
        log.w(if (advice == Advice.First) "android restarted by itself, tablet not restarted since" else "restart still pending, reminding", "at" to restartAt)
        if (!MorningReport.notificationsAllowed(app)) return
        val nm = app.getSystemService(NotificationManager::class.java)
        // A channel of its own: the report's is low importance and silent, and a channel's importance can't be raised once made.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.ba_restart_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        val open = PendingIntent.getActivity(
            app,
            OPEN_REQUEST,
            Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_DESTINATION, "Battery").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = app.getString(R.string.ba_restart_text)
        nm.notify(
            NOTIFICATION_ID,
            Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile_ink)
                .setContentTitle(app.getString(R.string.ba_restart_title))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }
}
