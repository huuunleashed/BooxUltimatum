package app.booxultimatum.core.sleep

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import app.booxultimatum.core.AppWork
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the published face current while the tablet is awake, and only then: nothing here wakes it.
 *
 * - An inexact `ELAPSED_REALTIME` repeating alarm at the chosen step (1, 5, 15 or 30 minutes). Non-wakeup alarms don't
 *   fire while the tablet sleeps, so the studio costs nothing in standby; a missed one is delivered on wake.
 * - Broadcasts the system sends anyway: battery level or plug changes, rotation, time, date and time-zone changes and
 *   screen on, plus a calendar observer once READ_CALENDAR is granted. Triggers are coalesced for two seconds.
 * - Each render is fingerprinted, so a trigger that changes nothing on the face costs one data read and no encode.
 *
 * Registered from [app.booxultimatum.BooxUltimatumApplication] in the main process, like the battery log's watch.
 */
object SleepScheduler {
    const val ACTION_RENDER = "app.booxultimatum.action.SLEEP_RENDER"
    private val watching = AtomicBoolean(false)
    private val calendarWatching = AtomicBoolean(false)
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var appContext: Context? = null
    @Volatile private var pendingReason = "event"
    @Volatile private var lastLevel = -1
    @Volatile private var lastPlugged = -1
    @Volatile private var lastOrientation = -1
    @Volatile private var lastBatteryRender = 0L
    private const val BATTERY_GAP = 60_000L

    private val run = Runnable {
        val c = appContext ?: return@Runnable
        if (!interactive(c)) return@Runnable
        val reason = pendingReason
        AppWork.scope.launch { SleepStudio.refresh(c, reason) }
    }

    private fun interactive(c: Context) = c.getSystemService(PowerManager::class.java)?.isInteractive != false

    /** Coalesces triggers: a rotation that also changes the battery reading renders once. */
    fun request(c: Context, reason: String, delayMs: Long = 2_000) {
        appContext = c.applicationContext
        pendingReason = reason
        main.removeCallbacks(run)
        main.postDelayed(run, delayMs)
    }

    /** Registers the triggers once per process and arms the alarm if it is missing. A no-op while the studio is off. */
    fun watch(app: Context) {
        val c = app.applicationContext
        appContext = c
        if (!SleepStore.load(c).active) return
        schedule(c)
        watchCalendar(c)
        if (!watching.compareAndSet(false, true)) return
        lastOrientation = c.resources.configuration.orientation
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(c, object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                if (!SleepStore.load(ctx).active) return
                when (i.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                        val first = lastLevel < 0
                        if (level == lastLevel && plugged == lastPlugged) return
                        lastLevel = level
                        lastPlugged = plugged
                        if (first) return
                        // Charging moves the level often; one render a minute at most, and the fingerprint skips the rest.
                        val wait = (lastBatteryRender + BATTERY_GAP - SystemClock.elapsedRealtime()).coerceAtLeast(2_000)
                        lastBatteryRender = SystemClock.elapsedRealtime() + wait
                        request(ctx, "battery", wait)
                    }
                    Intent.ACTION_CONFIGURATION_CHANGED -> {
                        val o = ctx.resources.configuration.orientation
                        if (o == lastOrientation || o == Configuration.ORIENTATION_UNDEFINED) return
                        lastOrientation = o
                        request(ctx, "rotation", 1_500)
                    }
                    Intent.ACTION_SCREEN_ON -> request(ctx, "wake", 3_000)
                    else -> request(ctx, "clock")
                }
            }
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Calendar edits refresh the agenda; registered only once the permission exists, as the provider requires it. */
    fun watchCalendar(app: Context) {
        val c = app.applicationContext
        if (c.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return
        if (!calendarWatching.compareAndSet(false, true)) return
        runCatching {
            c.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    if (SleepStore.load(c).active) request(c, "calendar", 5_000)
                }
            })
        }.onFailure { calendarWatching.set(false) }
    }

    private fun pending(c: Context, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        c, 1, Intent(c, SleepReceiver::class.java).setAction(ACTION_RENDER), PendingIntent.FLAG_IMMUTABLE or flags,
    )

    /**
     * Arms the refresh alarm, leaving an armed one alone so opening the app never pushes the next refresh away.
     * A changed step re-arms it.
     */
    fun schedule(c: Context) {
        val spec = SleepStore.load(c)
        if (!spec.active) { cancel(c); return }
        val interval = spec.intervalMin * 60_000L
        val existing = pending(c, PendingIntent.FLAG_NO_CREATE)
        if (existing != null && SleepStore.get(c, "alarm_interval") == interval.toString()) return
        val am = c.getSystemService(AlarmManager::class.java)
        existing?.let { am.cancel(it) }
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + interval, interval, pending(c, PendingIntent.FLAG_UPDATE_CURRENT)!!)
        SleepStore.put(c, "alarm_interval", interval.toString())
    }

    fun cancel(c: Context) {
        pending(c, PendingIntent.FLAG_NO_CREATE)?.let {
            c.getSystemService(AlarmManager::class.java).cancel(it)
            it.cancel()
        }
        SleepStore.put(c, "alarm_interval", null)
        main.removeCallbacks(run)
    }
}

/** The refresh alarm. Manifest-registered and not exported; the alarm is non-wakeup, so this only runs while awake. */
class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SleepScheduler.ACTION_RENDER) return
        val app = context.applicationContext
        val power = app.getSystemService(PowerManager::class.java)
        // A missed non-wakeup alarm can land in a brief background wake; rendering then would change nothing on screen.
        if (power?.isInteractive == false) return
        val result = goAsync()
        AppWork.scope.launch {
            try { SleepStudio.refresh(app, "tick") } finally { result.finish() }
        }
    }
}
