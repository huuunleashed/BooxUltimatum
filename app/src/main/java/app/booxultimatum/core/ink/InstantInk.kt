package app.booxultimatum.core.ink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.KeyguardManager
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
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.view.Display
import androidx.core.content.edit
import app.booxultimatum.MainActivity
import app.booxultimatum.R
import app.booxultimatum.core.Journal
import app.booxultimatum.core.exec.Privileged
import kotlinx.coroutines.runBlocking

/** The preview brushes SurfaceFlinger offers. Pencil is textured and reads as broken at small widths. */
enum class InkStyle(val code: Int) { Fountain(1), Pencil(0), Marker(2) }

/**
 * What the owner chose on the Instant ink page. Stored in the "ink" preferences. A "hold" key from 0.5.0's
 * "While drawing" choice may still be there; it is ignored, since the firmware holds the app's drawing while the pen
 * touches anyway, and letting it through mid-stroke ended the preview (tested on NA6C FW 4.3).
 */
data class InkPrefs(
    val enabled: Boolean = false,
    val apps: Set<String> = emptySet(),
    val widthPx: Int = 4,
    val style: InkStyle = InkStyle.Fountain,
    val latencyMs: Int = 500,
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
            )
        }

        fun save(context: Context, v: InkPrefs) {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
                putBoolean("enabled", v.enabled).putStringSet("apps", v.apps).putInt("width", v.widthPx).putString("style", v.style.name)
                    .putInt("latency", v.latencyMs).remove("hold")
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
    enum class Status { Off, NoPen, NoRoute, NoUsageAccess, Ready, Armed }

    @Volatile var status: Status = Status.Off
        private set
    @Volatile var armedFor: String? = null
        private set

    /**
     * Instant ink arms only over the apps the owner picked, and Android tells an app which app is in front only with
     * usage access. Without it the usage history reads as empty and no error is raised, so this is checked up front.
     * The owner can grant it on the tablet (Settings › Apps › Special app access › Usage access); no computer needed.
     */
    fun usageAccess(context: Context): Boolean {
        val ops = context.getSystemService(android.app.AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION") // the replacement needs API 36; minSdk is 30
        return ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) ==
            android.app.AppOpsManager.MODE_ALLOWED
    }

    /** Opens Android's usage access page, at this app's own entry where the firmware supports it. */
    fun openUsageAccess(context: Context) {
        val own = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
        val list = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
        listOf(own, list).firstOrNull { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
    }

    fun apply(context: Context, prefs: InkPrefs) {
        InkPrefs.save(context, prefs)
        val intent = Intent(context, InkService::class.java)
        if (prefs.enabled && prefs.apps.isNotEmpty()) runCatching { context.startForegroundService(intent) }
        else context.stopService(intent)
        refreshTile(context)
    }

    /** Whether the service is running in this process; set by the service itself. */
    @Volatile internal var running = false

    /** Asks Quick Settings to redraw the Instant ink tile, when the owner has added it. */
    fun refreshTile(context: Context) {
        runCatching { android.service.quicksettings.TileService.requestListeningState(context, android.content.ComponentName(context, InkTileService::class.java)) }
    }

    /** Called when the app starts: brings the service back if the owner left Instant ink on. */
    fun resume(context: Context) {
        val p = InkPrefs.load(context)
        if (p.enabled && p.apps.isNotEmpty()) runCatching { context.startForegroundService(Intent(context, InkService::class.java)) }
    }

    /**
     * Clears any preview ink and returns the panel to normal drawing, whatever state it was left in. While the service
     * runs, it does this itself, so its idea of the session stays true and the next approach opens a new one.
     */
    fun recoverScreen(context: Context) {
        if (running && runCatching { context.startService(Intent(context, InkService::class.java).setAction(InkService.ACTION_RECOVER)) }.isSuccess) return
        if (SurfaceInk.connect { serverPid() } != null) SurfaceInk.release()
        Journal.log(context, "undo", context.getString(R.string.ink_title), context.getString(R.string.ink_recovered), true)
    }

    internal fun setStatus(s: Status, pkg: String? = null) { status = s; armedFor = pkg }

    /** Only needed when the platform refuses the direct route and calls go through Shizuku's server. */
    internal fun serverPid(): Int? = if (!Privileged.ready()) null else runBlocking {
        Privileged.sh("pidof shizuku_server").takeIf { it.ok }?.out?.trim()?.split(' ')?.firstOrNull()?.toIntOrNull()
    }
}

/**
 * The service behind Instant ink. It keeps one display pen session of its own, opened ahead of time and paused
 * whenever the pen is away or no chosen app is in front, because on NA6C FW 4.3 a session opened only as the pen
 * arrives misses the stroke that follows at once, while a paused session resumes instantly, even at the touch. Pen
 * hover on a quick stroke lasts about 45 ms, and a quick first touch arrives together with the hover. A paused session
 * stays quiet in other apps: no preview and no held frames (all tested with the owner drawing in Sketchbook and Boox
 * Notes).
 */
class InkService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private var pen: PenInput? = null
    private var penRestarts = 0
    private var prefs = InkPrefs()
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }

    /** Our pen session is open (started by this service and not ended since). */
    private var open = false
    /** Open but paused: screen off, the lock screen, the eraser, or no chosen app in front. */
    private var paused = false
    /**
     * A Boox app came to the front while our session was paused. Boox's own apps start, stop and pause sessions of
     * their own (Notes leaves its session paused when you switch away), so ours may have been replaced: the next
     * chosen app opens a new one instead of resuming.
     */
    private var stale = false
    /** App frames are held back from the panel (the pen is near, a stroke is being drawn or waiting to be swapped). */
    private var holding = false
    /** Preview ink may be on the panel that the app's own frames haven't replaced yet. */
    private var inked = false
    private var touching = false
    private var near = false
    private var downAt = 0L
    private var lastSwap = 0L
    private var lastCheck = 0L
    private var lastForeground: String? = null
    private var lastQuery = 0L

    private val drawing get() = open && !paused

    private fun hold() {
        if (!holding) { SurfaceInk.enablePost(false); holding = true }
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    /**
     * The swap: SurfaceFlinger replaces the preview with the app's own pixels when app frames go from held to let
     * through, while the session stays open. The firmware itself holds app frames from each touch (its
     * `HandlePenTrigger`) until they're let through, so a stroke that began unheld still needs a brief hold and
     * release; without it the preview stayed on the panel for good (tested on NA6C FW 4.3). Letting frames through
     * while the pen still draws ends the preview for the rest of that stroke, which is why this waits for the lift.
     */
    private fun swap() {
        handler.removeCallbacks(watchdog)
        when {
            holding -> { SurfaceInk.enablePost(true); holding = false }
            inked -> { SurfaceInk.enablePost(false); SurfaceInk.enablePost(true) }
        }
        inked = false
        lastSwap = SystemClock.uptimeMillis()
    }

    private val swapAfterLift = Runnable {
        swap()
        // With the pen still hovering, hold again shortly, so the next stroke's first points aren't raced by the app's frames.
        if (near && drawing) { handler.removeCallbacks(rehold); handler.postDelayed(rehold, REHOLD_MS) }
    }

    private val rehold = Runnable { if (near && !touching && drawing) { hold(); handler.postDelayed(hoverIdle, HOVER_IDLE_MS) } }

    /** A pen resting in range without drawing doesn't keep the app's screen frozen or the preview on it. */
    private val hoverIdle = Runnable { if (!touching && (holding || inked)) swap() }

    /** Never leave app frames held back for long, whatever the pen reports. */
    private val watchdog = Runnable { if (holding) swap() }

    /**
     * A tap with the pen often opens another app while the pen stays in range, so no new approach would say so. The
     * app in front is read again a few times after a tap: a chosen app is ready before the pen lands, and any other
     * app has the preview paused before it could draw there.
     */
    private val recheck = Runnable { if (near && !touching) target() }

    private fun recheckSoon() {
        handler.removeCallbacks(recheck)
        RECHECK_AFTER_TAP_MS.forEach { handler.postDelayed(recheck, it) }
    }

    private fun cancelSwaps() { handler.removeCallbacks(swapAfterLift); handler.removeCallbacks(rehold); handler.removeCallbacks(hoverIdle) }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (i.action == Intent.ACTION_SCREEN_OFF) handler.post { pauseForScreen() } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("instant-ink").apply { start() }
        handler = Handler(worker.looper)
        startInForeground()
        registerReceiver(screen, IntentFilter(Intent.ACTION_SCREEN_OFF))
        InstantInk.running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OFF -> handler.post {
                InkPrefs.save(this, InkPrefs.load(this).copy(enabled = false))
                disarm()
                InstantInk.setStatus(InstantInk.Status.Off)
                InstantInk.refreshTile(this)
                stopSelf()
            }
            ACTION_RECOVER -> handler.post {
                disarm()
                SurfaceInk.release()
                Journal.log(this, "undo", getString(R.string.ink_title), getString(R.string.ink_recovered), true)
                if (prefs.enabled && SurfaceInk.penState() == SurfaceInk.STOP) standby()
            }
            else -> handler.post { reload() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        InstantInk.running = false
        runCatching { unregisterReceiver(screen) }
        pen?.stop(); pen = null
        handler.post { disarm(); InstantInk.setStatus(InstantInk.Status.Off) }
        worker.quitSafely()
        super.onDestroy()
    }

    private fun reload() {
        prefs = InkPrefs.load(this)
        disarm()
        if (!prefs.enabled || prefs.apps.isEmpty()) { InstantInk.setStatus(InstantInk.Status.Off); stopSelf(); return }
        if (!InstantInk.usageAccess(this)) { pen?.stop(); pen = null; InstantInk.setStatus(InstantInk.Status.NoUsageAccess); return }
        if (SurfaceInk.connect { InstantInk.serverPid() } == null) { InstantInk.setStatus(InstantInk.Status.NoRoute); return }
        var state = SurfaceInk.penState()
        android.util.Log.i("InstantInk", "route ${SurfaceInk.route}, pen state $state")
        val fg = foreground()
        // A session left open by an earlier run that didn't end cleanly would keep the app's frames held. It's only
        // cleared over this app or a chosen one, never over Boox's own apps, whose sessions are theirs.
        if (state != null && state != SurfaceInk.STOP && (fg == packageName || fg in prefs.apps)) { SurfaceInk.release(); state = SurfaceInk.STOP }
        if (pen == null && !startPen()) { InstantInk.setStatus(InstantInk.Status.NoPen); return }
        // Opened now and paused, so the first stroke in a chosen app already finds a session to resume.
        if (state == SurfaceInk.STOP) standby()
        InstantInk.setStatus(InstantInk.Status.Ready)
        if (fg != null && fg in prefs.apps) target()
    }

    private fun startPen(): Boolean {
        val p = PenInput(this, onLost = { handler.post { penLost() } }) { e -> handler.post { onPen(e) } }
        if (!p.start()) return false
        pen = p
        return true
    }

    /** The pen's reader ended by itself: clear any preview and start a new reader, a few times at most. */
    private fun penLost() {
        pen = null
        near = false; touching = false
        if (open) { cancelSwaps(); swap() }
        if (penRestarts++ >= MAX_PEN_RESTARTS) { InstantInk.setStatus(InstantInk.Status.NoPen); return }
        handler.postDelayed({
            if (pen != null || !prefs.enabled) return@postDelayed
            if (!startPen()) InstantInk.setStatus(InstantInk.Status.NoPen)
        }, 2_000)
    }

    private fun onPen(e: PenInput.Event) {
        when (e) {
            // The pen comes into range before it touches. Resuming the session and holding the app's frames now, as
            // Onyx's SDK does at hover, means the stroke's first point already lands on a held screen.
            PenInput.Event.Near -> {
                near = true
                if (touching) return
                if (!target()) return
                hold()
                handler.removeCallbacks(hoverIdle); handler.postDelayed(hoverIdle, HOVER_IDLE_MS)
            }
            PenInput.Event.Down -> {
                touching = true; near = true; downAt = SystemClock.uptimeMillis()
                // A swap still pending from the last stroke is dropped: letting frames through now would cover this stroke's start.
                cancelSwaps()
                // A quick touch arrives with the hover, and the app in front may have changed without the pen leaving.
                if (!drawing || stale || downAt - lastCheck > CHECK_AGAIN_MS) { if (!target()) return }
                if (!holding) android.util.Log.d("InstantInk", "stroke began unheld, ${downAt - lastSwap} ms after the last swap")
                hold()
                inked = true
            }
            PenInput.Event.Up -> {
                touching = false
                if (drawing) { handler.removeCallbacks(swapAfterLift); handler.postDelayed(swapAfterLift, prefs.latencyMs.toLong()) }
                if (SystemClock.uptimeMillis() - downAt < TAP_MS) recheckSoon()
            }
            // Out of range means the owner is done for now: swap at once, and pause, so the session is quiet whenever
            // the pen is away (an app switch then finds nothing of ours drawing).
            PenInput.Event.Away -> {
                near = false
                handler.removeCallbacks(recheck)
                if (open && !touching) { cancelSwaps(); swap(); pause() }
            }
            // The eraser end, or a side button apps map to erasing, is the app's own tool: the preview would draw black
            // under it, so the session pauses and the app shows. The tip's next approach resumes it.
            PenInput.Event.EraserNear -> if (drawing) { cancelSwaps(); swap(); pause() }
            PenInput.Event.EraserAway -> if (near) target()
        }
    }

    /** Makes sure the session is drawing when a chosen app is in front, and paused or ended otherwise. */
    private fun target(): Boolean {
        lastCheck = SystemClock.uptimeMillis()
        // The lock screen sits over the app without replacing it in the usage history, so it's checked first.
        if (keyguard?.isKeyguardLocked == true) { if (drawing) { cancelSwaps(); swap(); pause() }; return false }
        val fg = foreground()
        if (fg == null || fg !in prefs.apps) { leave(fg); return false }
        when {
            !open -> arm()
            // Boox's app may have replaced our session. Its paused session is taken over without a new start (a new
            // one misses the stroke under way); a stopped or running one gets a session of our own.
            stale -> if (SurfaceInk.penState().let { it == SurfaceInk.PAUSE || it == SurfaceInk.PAUSED }) takeOver() else arm()
            paused -> resume()
        }
        if (drawing) InstantInk.setStatus(InstantInk.Status.Armed, fg)
        return drawing
    }

    /**
     * No chosen app in front: pause. Nothing is ever ended over Boox's own apps: they run sessions of their own, and
     * by the time the pen shows up there, the one on the display may already be theirs.
     */
    private fun leave(fg: String?) {
        if (!open) return
        if (InstantInk.status == InstantInk.Status.Armed) InstantInk.setStatus(InstantInk.Status.Ready)
        if (drawing) { cancelSwaps(); swap(); pause() }
        if (fg != null && fg.startsWith(BOOX_PREFIX)) stale = true
    }

    /** Opens a session. The region and stroke go with it, so any session found or replaced gets this app's settings. */
    private fun arm() {
        val (w, h) = screenSize() ?: return
        // SurfaceFlinger reads the region in the panel's own landscape frame, whatever the rotation: a portrait-shaped
        // rectangle lost the bottom quarter. A square as large as the long side covers the panel in every orientation,
        // so turning the tablet needs no new session.
        val side = maxOf(w, h)
        SurfaceInk.setRegion(intArrayOf(0, 0, side, side))
        // The stroke goes after START, as in Onyx's demo: arming can reset the stroke to firmware defaults.
        SurfaceInk.setPenState(SurfaceInk.START)
        SurfaceInk.setStroke(prefs.widthPx.toFloat(), 0xFF000000.toInt(), prefs.style.code)
        SurfaceInk.setPenState(SurfaceInk.DRAW)
        open = true; paused = false; stale = false
    }

    /** A session opened ahead of time and paused at once, ready to resume the moment a chosen app sees the pen. */
    private fun standby() {
        arm()
        if (open) { SurfaceInk.setPenState(SurfaceInk.PAUSE); paused = true }
    }

    /** Resumes a paused session that may be another app's, with this app's region and stroke. */
    private fun takeOver() {
        val (w, h) = screenSize() ?: return
        val side = maxOf(w, h)
        SurfaceInk.setRegion(intArrayOf(0, 0, side, side))
        SurfaceInk.setStroke(prefs.widthPx.toFloat(), 0xFF000000.toInt(), prefs.style.code)
        SurfaceInk.setPenState(SurfaceInk.DRAW)
        open = true; paused = false; stale = false
    }

    private fun pause() {
        if (!drawing) return
        SurfaceInk.setPenState(SurfaceInk.PAUSE)
        paused = true
    }

    private fun resume() {
        // If the firmware or another app closed the session meanwhile, open a new one.
        if (SurfaceInk.penState() == SurfaceInk.STOP) { arm(); return }
        SurfaceInk.setPenState(SurfaceInk.DRAW)
        paused = false
    }

    /** Screen off: clear the preview and pause, keeping the session open for the stroke right after unlocking. */
    private fun pauseForScreen() {
        cancelSwaps(); handler.removeCallbacks(recheck)
        near = false; touching = false
        if (!drawing) return
        swap()
        pause()
        if (InstantInk.status == InstantInk.Status.Armed) InstantInk.setStatus(InstantInk.Status.Ready)
    }

    private fun disarm() {
        cancelSwaps(); handler.removeCallbacks(watchdog); handler.removeCallbacks(recheck)
        if (open || holding) SurfaceInk.release()
        open = false; paused = false; stale = false; holding = false; touching = false; inked = false
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
        fun action(label: Int, act: String, code: Int) = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_tile_ink), getString(label),
            PendingIntent.getService(this, code, Intent(this, InkService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE),
        ).build()
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_ink)
            .setContentTitle(getString(R.string.ink_title))
            .setContentText(getString(R.string.ink_notification))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(action(R.string.ink_turn_off, ACTION_OFF, 1))
            .addAction(action(R.string.ink_recover, ACTION_RECOVER, 2))
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, n)
    }

    companion object {
        const val ACTION_OFF = "app.booxultimatum.ink.OFF"
        const val ACTION_RECOVER = "app.booxultimatum.ink.RECOVER"
        private const val CHANNEL = "instant_ink"
        private const val NOTIFICATION_ID = 42
        /** After a swap with the pen still in range, frames flow this long before the hold resumes: enough for one e-ink refresh. */
        private const val REHOLD_MS = 350L
        /** A hovering pen that doesn't touch down lets the app's frames through after this long. */
        private const val HOVER_IDLE_MS = 2_500L
        /** App frames are never held longer than this, whatever the pen reports. */
        private const val WATCHDOG_MS = 30_000L
        /** A touch shorter than this is a tap, which may open another app. */
        private const val TAP_MS = 400L
        /** When the app in front is read again after a tap: app launches on e-ink take a moment. */
        private val RECHECK_AFTER_TAP_MS = longArrayOf(350L, 1_000L, 2_000L)
        /** At a touch, the app in front is read again if the last look is older than this. */
        private const val CHECK_AGAIN_MS = 1_500L
        /** Packages that run their own pen sessions: Boox's apps. */
        private const val BOOX_PREFIX = "com.onyx"
        private const val MAX_PEN_RESTARTS = 5
    }
}
