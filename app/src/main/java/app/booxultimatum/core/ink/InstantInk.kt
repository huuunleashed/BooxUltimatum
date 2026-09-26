package app.booxultimatum.core.ink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Display
import androidx.core.content.edit
import app.booxultimatum.MainActivity
import app.booxultimatum.R
import app.booxultimatum.core.Journal
import app.booxultimatum.core.exec.Privileged
import kotlinx.coroutines.runBlocking

/** The preview brushes SurfaceFlinger offers. Pencil is textured and reads as broken at small widths. */
enum class InkStyle(val code: Int) { Fountain(1), Pencil(0), Marker(2) }

/** What the owner chose on the Instant ink page. Stored in the "ink" preferences. */
data class InkPrefs(
    val enabled: Boolean = false,
    val apps: Set<String> = emptySet(),
    val widthPx: Int = 4,
    val style: InkStyle = InkStyle.Fountain,
    val latencyMs: Int = 500,
    /** Hold the app's own drawing back while the pen draws, so it can't cut into the preview or show it twice. */
    val holdAppInk: Boolean = true,
) {
    companion object {
        private const val FILE = "ink"

        fun load(context: Context): InkPrefs {
            val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return InkPrefs(
                enabled = p.getBoolean("enabled", false),
                apps = p.getStringSet("apps", emptySet()).orEmpty().toSet(),
                widthPx = p.getInt("width", 4),
                style = runCatching { InkStyle.valueOf(p.getString("style", null) ?: "") }.getOrDefault(InkStyle.Fountain),
                latencyMs = p.getInt("latency", 500),
                holdAppInk = p.getBoolean("hold", true),
            )
        }

        fun save(context: Context, v: InkPrefs) {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
                putBoolean("enabled", v.enabled).putStringSet("apps", v.apps).putInt("width", v.widthPx).putString("style", v.style.name)
                    .putInt("latency", v.latencyMs).putBoolean("hold", v.holdAppInk)
            }
        }
    }
}

/**
 * Instant ink for any app, verified on NA6C FW 4.3 with Sketchbook (2026-09-26). While a chosen app is in front,
 * SurfaceFlinger draws the stylus stroke straight onto the panel the moment the pen touches, as it does for Boox
 * Notes. The app's own frames are held back while the pen draws and let through again shortly after the lift, which
 * swaps the preview for the app's real stroke. The session stays open between strokes, so no stroke loses its start.
 * The app itself is untouched and still gets every pen event.
 */
object InstantInk {
    enum class Status { Off, NoPen, NoRoute, Ready, Armed }

    @Volatile var status: Status = Status.Off
        private set
    @Volatile var armedFor: String? = null
        private set

    fun apply(context: Context, prefs: InkPrefs) {
        InkPrefs.save(context, prefs)
        val intent = Intent(context, InkService::class.java)
        if (prefs.enabled && prefs.apps.isNotEmpty()) runCatching { context.startForegroundService(intent) }
        else context.stopService(intent)
    }

    /** Called when the app starts: brings the service back if the owner left Instant ink on. */
    fun resume(context: Context) {
        val p = InkPrefs.load(context)
        if (p.enabled && p.apps.isNotEmpty()) runCatching { context.startForegroundService(Intent(context, InkService::class.java)) }
    }

    /** Clears any preview ink and returns the panel to normal drawing, whatever state it was left in. */
    fun recoverScreen(context: Context) {
        if (SurfaceInk.connect { serverPid() } != null) SurfaceInk.release()
        Journal.log(context, "undo", context.getString(R.string.ink_title), context.getString(R.string.ink_recovered), true)
    }

    internal fun setStatus(s: Status, pkg: String? = null) { status = s; armedFor = pkg }

    /** Only needed when the platform refuses the direct route and calls go through Shizuku's server. */
    internal fun serverPid(): Int? = if (!Privileged.ready()) null else runBlocking {
        Privileged.sh("pidof shizuku_server").takeIf { it.ok }?.out?.trim()?.split(' ')?.firstOrNull()?.toIntOrNull()
    }
}

class InkService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private var pen: PenInput? = null
    private var prefs = InkPrefs()
    private var armed: String? = null
    private var armedRotation = -1
    private var lastForeground: String? = null
    private var lastQuery = 0L
    /** App frames are held back from the panel (the pen is near, a stroke is being drawn or waiting to be swapped). */
    private var holding = false
    private var touching = false
    private var near = false
    private var lastSwap = 0L

    private fun hold() {
        if (prefs.holdAppInk && !holding) { SurfaceInk.enablePost(false); holding = true }
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 30_000)
    }

    private fun letThrough() {
        handler.removeCallbacks(watchdog)
        if (holding) { SurfaceInk.enablePost(true); holding = false }
        lastSwap = android.os.SystemClock.uptimeMillis()
    }

    /**
     * The swap: the app's frames reach the panel again and replace the preview, while the session stays open. If the
     * pen is still hovering, the hold resumes shortly afterwards, so the next stroke's first points are never raced
     * by the app's own frames (the likely cause of the preview sometimes missing a stroke's start).
     */
    private val swap = Runnable {
        letThrough()
        if (near && armed != null) { handler.removeCallbacks(rehold); handler.postDelayed(rehold, REHOLD_MS) }
    }

    private val rehold = Runnable { if (near && !touching && armed != null) { hold(); handler.postDelayed(hoverIdle, HOVER_IDLE_MS) } }

    /** A pen resting in range without drawing doesn't keep the app's screen frozen: its frames go through after a while. */
    private val hoverIdle = Runnable { if (!touching && holding) letThrough() }

    /** Never leave app frames held back for long, whatever the pen reports. */
    private val watchdog = Runnable { if (holding) { SurfaceInk.enablePost(true); holding = false } }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { handler.post { disarm() } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("instant-ink").apply { start() }
        handler = Handler(worker.looper)
        startInForeground()
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.post { reload() }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOff) }
        pen?.stop(); pen = null
        handler.post { disarm(); InstantInk.setStatus(InstantInk.Status.Off) }
        worker.quitSafely()
        super.onDestroy()
    }

    private fun reload() {
        prefs = InkPrefs.load(this)
        disarm()
        if (!prefs.enabled || prefs.apps.isEmpty()) { InstantInk.setStatus(InstantInk.Status.Off); stopSelf(); return }
        if (SurfaceInk.connect { InstantInk.serverPid() } == null) { InstantInk.setStatus(InstantInk.Status.NoRoute); return }
        android.util.Log.i("InstantInk", "route ${SurfaceInk.route}, pen state ${SurfaceInk.penState()}")
        if (pen == null) {
            val p = PenInput { e -> handler.post { onPen(e) } }
            if (!p.start()) { InstantInk.setStatus(InstantInk.Status.NoPen); return }
            pen = p
        }
        InstantInk.setStatus(InstantInk.Status.Ready)
    }

    private fun onPen(e: PenInput.Event) {
        when (e) {
            // The pen comes into range about half a second before it touches. Arming and holding the app's frames now,
            // as Onyx's SDK does at hover, means the first point of the stroke already lands on a held screen.
            PenInput.Event.Near -> {
                near = true
                checkTarget()
                if (armed != null) {
                    hold()
                    handler.removeCallbacks(hoverIdle); handler.postDelayed(hoverIdle, HOVER_IDLE_MS)
                }
            }
            PenInput.Event.Down -> {
                touching = true; near = true
                if (armed == null) checkTarget()
                if (armed == null) return
                // A swap still pending from the last stroke is dropped: letting frames through now would cover this stroke's start.
                handler.removeCallbacks(swap); handler.removeCallbacks(rehold); handler.removeCallbacks(hoverIdle)
                if (!holding) android.util.Log.d("InstantInk", "stroke began unheld, ${android.os.SystemClock.uptimeMillis() - lastSwap} ms after the last swap")
                hold()
            }
            PenInput.Event.Up -> {
                touching = false
                if (armed != null) { handler.removeCallbacks(swap); handler.postDelayed(swap, prefs.latencyMs.toLong()) }
            }
            // Out of range means the owner is done for now: swap at once and stop holding.
            PenInput.Event.Away -> {
                near = false
                if (armed != null && !touching) {
                    handler.removeCallbacks(swap); handler.removeCallbacks(rehold); handler.removeCallbacks(hoverIdle)
                    letThrough()
                }
            }
            // The eraser end, or a side button that apps map to erasing, is the app's own tool; the preview would draw
            // black under it, so the preview pauses and the app shows.
            PenInput.Event.EraserNear -> if (armed != null) {
                handler.removeCallbacks(swap); handler.removeCallbacks(rehold); handler.removeCallbacks(hoverIdle)
                letThrough(); SurfaceInk.setPenState(SurfaceInk.PAUSE)
            }
            PenInput.Event.EraserAway -> if (armed != null) SurfaceInk.setPenState(SurfaceInk.DRAW)
        }
    }

    private fun checkTarget() {
        val fg = foreground()
        val rotation = display()?.rotation ?: 0
        if (fg != null && fg in prefs.apps) {
            if (armed != fg || armedRotation != rotation) arm(fg, rotation)
        } else if (armed != null) disarm()
    }

    private fun arm(pkg: String, rotation: Int) {
        val (w, h) = screenSize() ?: return
        // SurfaceFlinger reads the region in the panel's own landscape frame, whatever the rotation: a portrait-shaped
        // rectangle lost the bottom quarter. A square as large as the long side covers the panel in every orientation.
        val side = maxOf(w, h)
        SurfaceInk.setRegion(intArrayOf(0, 0, side, side))
        // The stroke goes after START, as in Onyx's demo: arming can reset the stroke to firmware defaults.
        SurfaceInk.setPenState(SurfaceInk.START)
        SurfaceInk.setStroke(prefs.widthPx.toFloat(), 0xFF000000.toInt(), prefs.style.code)
        SurfaceInk.setPenState(SurfaceInk.DRAW)
        armed = pkg; armedRotation = rotation
        InstantInk.setStatus(InstantInk.Status.Armed, pkg)
    }

    private fun disarm() {
        handler.removeCallbacks(swap); handler.removeCallbacks(watchdog); handler.removeCallbacks(rehold); handler.removeCallbacks(hoverIdle)
        if (armed != null || holding) SurfaceInk.release()
        armed = null; holding = false; touching = false
        if (InstantInk.status == InstantInk.Status.Armed) InstantInk.setStatus(InstantInk.Status.Ready)
    }

    private fun display(): Display? = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int>? = display()?.let { d -> Point().also { d.getRealSize(it) }.let { it.x to it.y } }

    /** The app in front, from usage events since the last look: read once per pen approach, never polled. */
    private fun foreground(): String? {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return lastForeground
        val now = System.currentTimeMillis()
        val from = if (lastQuery == 0L) now - 6 * 3600_000L else lastQuery - 2000
        runCatching {
            val events = usm.queryEvents(from, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) lastForeground = e.packageName
            }
        }
        lastQuery = now
        return lastForeground
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.ink_channel), NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_DESTINATION, "Ink").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mark)
            .setContentTitle(getString(R.string.ink_title))
            .setContentText(getString(R.string.ink_notification))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, n)
    }

    companion object {
        private const val CHANNEL = "instant_ink"
        private const val NOTIFICATION_ID = 42
        /** After a swap with the pen still in range, frames flow this long before the hold resumes: enough for one e-ink refresh. */
        private const val REHOLD_MS = 350L
        /** A hovering pen that doesn't touch down releases the app's frames after this long. */
        private const val HOVER_IDLE_MS = 2_500L
    }
}
