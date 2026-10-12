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
 * is told once per such restart, with the reason, while there is time to act.
 */
object RestartAdvice {
    private const val PREFS = "battery_alerts"
    private const val ADVISED_AT = "restart_advised_at"
    private const val CHANNEL = "battery_report"
    private const val NOTIFICATION_ID = 63
    private const val OPEN_REQUEST = 64
    private val log = Logbook.logger("battery")
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "restart-advice").apply { isDaemon = true } }

    /** Whether a restart at [restartAtMs] is one the owner should hear about: after the last boot, and not advised before. */
    internal fun shouldAdvise(restartAtMs: Long?, bootAtMs: Long, advisedAtMs: Long): Boolean =
        restartAtMs != null && restartAtMs >= bootAtMs && restartAtMs > advisedAtMs

    /** Looks at the dropbox once, off the main thread, and posts the note when a restart is new. Cheap to call often. */
    fun check(context: Context) {
        val app = context.applicationContext
        worker.execute { runCatching { run(app) }.onFailure { log.w("restart advice failed", error = it) } }
    }

    private fun run(app: Context) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val restartAt = SignalReader.restartAtMs(app)
        val bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        if (!shouldAdvise(restartAt, bootAt, prefs.getLong(ADVISED_AT, 0L))) return
        prefs.edit { putLong(ADVISED_AT, restartAt ?: 0L) }
        log.w("android restarted by itself, tablet not restarted since", "at" to restartAt)
        if (!MorningReport.notificationsAllowed(app)) return
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.ba_channel), NotificationManager.IMPORTANCE_LOW).apply {
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
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }
}
