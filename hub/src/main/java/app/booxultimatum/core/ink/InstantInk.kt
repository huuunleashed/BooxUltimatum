package app.booxultimatum.core.ink

import android.app.KeyguardManager
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
import app.booxultimatum.kit.core.Suite
import app.booxultimatum.kit.ink.canvas.InkScheduler
import app.booxultimatum.kit.ink.eink.Eink
import app.booxultimatum.kit.ink.epd.ElevatedRoute
import app.booxultimatum.kit.ink.epd.Epd
import app.booxultimatum.kit.ink.input.PenInput
import app.booxultimatum.kit.ink.input.TouchPanel
import app.booxultimatum.kit.ink.session.InkGuard
import app.booxultimatum.kit.log.Logbook
import kotlinx.coroutines.runBlocking
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/** The preview brushes SurfaceFlinger offers. Pencil is textured and reads as broken at small widths. */
enum class InkStyle(val code: Int) { Fountain(1), Pencil(0), Marker(2) }

/**
 * What the owner chose on the Instant ink page. Stored in the "ink" preferences. A "hold" key from 0.5.0's
 * "While drawing" choice may still be there; it is ignored, since the firmware holds the app's drawing while the pen
 * touches anyway, and letting it through mid-stroke ended the preview (tested on NA6C FW 4.3).
 *
 * [latencyMs] is how long the pen rests after a lift before the app's strokes replace the preview (see [HoldPolicy]).
 * A saved value is kept, brought into the 400 ms to 2 s range BOOX's own screen notes offer.
 */
data class InkPrefs(
    val enabled: Boolean = false,
    val apps: Set<String> = emptySet(),
    val widthPx: Int = 4,
    val style: InkStyle = InkStyle.Fountain,
    val latencyMs: Int = HoldPolicy.DEFAULT_LATENCY_MS,
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
                latencyMs = HoldPolicy.latencyFor(p.getInt("latency", HoldPolicy.DEFAULT_LATENCY_MS)),
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
 * Notes. The app's own frames are held back while the pen writes, across quick strokes, and let through once the pen
 * pauses, which swaps the preview for the app's real strokes ([HoldPolicy]). The session stays open between strokes,
 * so no stroke loses its start. The app itself is untouched and still gets every pen event.
 */
object InstantInk {
    enum class Status { Off, NoPen, NoRoute, NoUsageAccess, Ready, Armed }

    private val log = Logbook.logger("ink")

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
     * Call it off the main thread: the Shizuku route answers through a shell command.
     */
    fun recoverScreen(context: Context) {
        if (running && runCatching { context.startService(Intent(context, InkService::class.java).setAction(InkService.ACTION_RECOVER)) }.isSuccess) return
        releaseDisplay()
        Journal.log(context, "undo", context.getString(R.string.ink_title), context.getString(R.string.ink_recovered), true)
    }

    /**
     * Ends any pen session and undoes every display-wide change one can leave: exclusions, the region mode, held
     * frames, autosync, fast mode, and finger touch switched off in areas.
     */
    internal fun releaseDisplay() {
        if (Epd.connect(shizukuRoute) != null) Epd.release()
        TouchPanel.reset()
        InkGuard.process.sessionClosed()
        log.i("display released", "route" to Epd.route)
    }

    /**
     * One full clean of the whole panel, which clears the ghosting partial e-ink updates leave (the Ink page's key and
     * the Clean screen tile): a repaint of everything in the deep GC waveform, NeoReader's choice on colour panels.
     * Needs nothing: the display call is open to apps, and Shizuku is only tried when the direct route is refused.
     * Call it off the main thread. Returns whether the display took it.
     */
    fun cleanScreen(from: String): Boolean {
        val ok = runCatching { Eink.cleanScreen(shizukuRoute) }.getOrDefault(false)
        log.i("clean screen", "from" to from, "route" to Epd.route, "done" to ok)
        return ok
    }

    internal fun setStatus(s: Status, pkg: String? = null) { status = s; armedFor = pkg }

    /** Only used when the platform refuses the direct route: calls then go through Shizuku's server, under its pid. */
    internal val shizukuRoute = ElevatedRoute {
        if (!Privileged.ready()) return@ElevatedRoute null
        val pid = runBlocking {
            Privileged.sh("pidof shizuku_server").takeIf { it.ok }?.out?.trim()?.split(' ')?.firstOrNull()?.toIntOrNull()
        } ?: return@ElevatedRoute null
        runCatching { ShizukuBinderWrapper(SystemServiceHelper.getSystemService("SurfaceFlinger")) }.getOrNull()?.let { it to pid }
    }
}

/**
 * The service behind Instant ink. It keeps one display pen session of its own, opened ahead of time and paused
 * whenever the pen is away or no chosen app is in front, because on NA6C FW 4.3 a session opened only as the pen
 * arrives misses the stroke that follows at once, while a paused session resumes instantly, even at the touch. Pen
 * hover on a quick stroke lasts about 45 ms, and a quick first touch arrives together with the hover. A paused session
 * stays quiet in other apps: no preview and no held frames (all tested with the owner drawing in Sketchbook and Boox
 * Notes). When the app's frames are held and let through is [HoldPolicy]'s choice.
 */
class InkService : Service() {
    private val log = Logbook.logger("ink")
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private lateinit var holds: HoldPolicy
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
    /**
     * [InkGuard] records our session only while it draws: that's what a dead process would leave harmful (a preview
     * over every app, frames held). A paused session draws nothing, and the guard leaves paused ones alone anyway.
     */
    private var guarded = false
    private var downAt = 0L
    private var lastCheck = 0L
    private var lastForeground: String? = null
    private var lastQuery = 0L

    private val drawing get() = open && !paused

    private val frames = object : HoldPolicy.Frames {
        override fun hold() { Epd.enablePost(false) }
        override fun letThrough() { Epd.enablePost(true) }
    }

    /**
     * A tap with the pen often opens another app while the pen stays in range, so no new approach would say so. The
     * app in front is read again a few times after a tap: a chosen app is ready before the pen lands, and any other
     * app has the preview paused before it could draw there.
     */
    private val recheck = Runnable { if (holds.near && !holds.touching) target() }

    private fun recheckSoon() {
        handler.removeCallbacks(recheck)
        RECHECK_AFTER_TAP_MS.forEach { handler.postDelayed(recheck, it) }
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (i.action == Intent.ACTION_SCREEN_OFF) handler.post { pauseForScreen() } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("instant-ink").apply { start() }
        handler = Handler(worker.looper)
        val scheduler = InkScheduler { delayMs, block ->
            val r = Runnable(block)
            handler.postDelayed(r, delayMs)
            ({ handler.removeCallbacks(r) })
        }
        holds = HoldPolicy(frames, scheduler, SystemClock::uptimeMillis).apply {
            mayHold = { target() }
            onEnded = { h ->
                val fields = arrayOf<Pair<String, Any?>>("strokes" to h.strokes, "held ms" to h.heldMs, "ended" to h.end.name)
                // Holds the pen only hovered through say little in a report; the ones with strokes show the batching.
                if (h.strokes > 0) log.i("hold", *fields) else log.d("hold", *fields)
            }
            onUnheldStroke = { since -> log.d("stroke began unheld", "since swap ms" to since) }
        }
        startInForeground()
        registerReceiver(screen, IntentFilter(Intent.ACTION_SCREEN_OFF))
        InstantInk.running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OFF -> handler.post {
                InkPrefs.save(this, InkPrefs.load(this).copy(enabled = false))
                disarm(HoldPolicy.End.Off)
                InstantInk.setStatus(InstantInk.Status.Off)
                InstantInk.refreshTile(this)
                stopSelf()
            }
            ACTION_RECOVER -> handler.post {
                disarm(HoldPolicy.End.Recover)
                InstantInk.releaseDisplay()
                Journal.log(this, "undo", getString(R.string.ink_title), getString(R.string.ink_recovered), true)
                if (prefs.enabled && Epd.penState() == Epd.PenState.STOP) standby()
            }
            else -> handler.post { reload() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        InstantInk.running = false
        runCatching { unregisterReceiver(screen) }
        pen?.stop(); pen = null
        handler.post { disarm(HoldPolicy.End.Off); InstantInk.setStatus(InstantInk.Status.Off) }
        worker.quitSafely()
        super.onDestroy()
    }

    private fun reload() {
        prefs = InkPrefs.load(this)
        disarm(HoldPolicy.End.Settings)
        holds.latencyMs = prefs.latencyMs.toLong()
        if (!prefs.enabled || prefs.apps.isEmpty()) { InstantInk.setStatus(InstantInk.Status.Off); stopSelf(); return }
        if (!InstantInk.usageAccess(this)) { pen?.stop(); pen = null; InstantInk.setStatus(InstantInk.Status.NoUsageAccess); return }
        if (Epd.connect(InstantInk.shizukuRoute) == null) { InstantInk.setStatus(InstantInk.Status.NoRoute); return }
        var state = Epd.penState()
        log.i("session", "route" to Epd.route, "pen state" to state, "pause ms" to prefs.latencyMs)
        val fg = foreground()
        // A session left open by an earlier run that didn't end cleanly would keep the app's frames held. It's only
        // cleared over this app or a chosen one, never over Boox's own apps, whose sessions are theirs.
        if (state != null && state != Epd.PenState.STOP && (fg == packageName || fg in prefs.apps)) { Epd.release(); state = Epd.PenState.STOP }
        if (pen == null && !startPen()) { InstantInk.setStatus(InstantInk.Status.NoPen); return }
        // Opened now and paused, so the first stroke in a chosen app already finds a session to resume.
        if (state == Epd.PenState.STOP) standby()
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
        holds.near = false; holds.touching = false
        if (open) holds.end(HoldPolicy.End.PenLost)
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
                holds.near = true
                if (holds.touching) return
                if (!target()) return
                holds.hover()
            }
            PenInput.Event.Down -> {
                holds.touching = true; holds.near = true; downAt = SystemClock.uptimeMillis()
                // A swap still pending from the last stroke is dropped: letting frames through now would cover this stroke's start.
                holds.cancelPending()
                // A quick touch arrives with the hover, and the app in front may have changed without the pen leaving.
                if (!drawing || stale || downAt - lastCheck > CHECK_AGAIN_MS) { if (!target()) return }
                holds.down()
            }
            PenInput.Event.Up -> {
                holds.touching = false
                if (drawing) holds.up()
                if (SystemClock.uptimeMillis() - downAt < TAP_MS) recheckSoon()
            }
            // Out of range means the owner is done for now: swap at once, and pause, so the session is quiet whenever
            // the pen is away (an app switch then finds nothing of ours drawing).
            PenInput.Event.Away -> {
                holds.near = false
                handler.removeCallbacks(recheck)
                if (open && !holds.touching) { holds.end(HoldPolicy.End.Away); pause() }
            }
            // The eraser end, or a side button apps map to erasing, is the app's own tool: the preview would draw black
            // under it, so the session pauses and the app shows. The tip's next approach resumes it.
            PenInput.Event.EraserNear -> if (drawing) { holds.end(HoldPolicy.End.Eraser); pause() }
            PenInput.Event.EraserAway -> if (holds.near) target()
        }
    }

    /** Makes sure the session is drawing when a chosen app is in front, and paused or ended otherwise. */
    private fun target(): Boolean {
        lastCheck = SystemClock.uptimeMillis()
        // The lock screen sits over the app without replacing it in the usage history, so it's checked first.
        if (keyguard?.isKeyguardLocked == true) { if (drawing) { holds.end(HoldPolicy.End.Locked); pause() }; return false }
        val fg = foreground()
        if (fg == null || fg !in prefs.apps || Suite.isSuitePackage(fg)) { leave(fg); return false }
        when {
            !open -> arm()
            // Boox's app may have replaced our session. Its paused session is taken over without a new start (a new
            // one misses the stroke under way); a stopped or running one gets a session of our own.
            stale -> if (Epd.penState().let { it == Epd.PenState.PAUSE || it == Epd.PenState.PAUSED }) takeOver() else arm()
            paused -> resume()
        }
        if (drawing) InstantInk.setStatus(InstantInk.Status.Armed, fg)
        return drawing
    }

    /**
     * No chosen app in front: pause. Nothing is ever ended over Boox's own apps: they run sessions of their own, and
     * by the time the pen shows up there, the one on the display may already be theirs. Suite apps such as Nib run
     * their own too, so Instant ink steps aside for them the same way.
     */
    private fun leave(fg: String?) {
        if (!open) return
        if (InstantInk.status == InstantInk.Status.Armed) InstantInk.setStatus(InstantInk.Status.Ready)
        if (drawing) { holds.end(HoldPolicy.End.LeftApp); pause() }
        if (fg != null && (fg.startsWith(BOOX_PREFIX) || Suite.isSuitePackage(fg))) stale = true
    }

    /** Opens a session. The region and stroke go with it, so any session found or replaced gets this app's settings. */
    private fun arm() {
        val (w, h) = screenSize() ?: return
        // SurfaceFlinger reads the region in the panel's own landscape frame, whatever the rotation: a portrait-shaped
        // rectangle lost the bottom quarter. A square as large as the long side covers the panel in every orientation,
        // so turning the tablet needs no new session.
        val side = maxOf(w, h)
        Epd.setRegionLimit(intArrayOf(0, 0, side, side), screen = false)
        // The stroke goes after START, as in Onyx's demo: arming can reset the stroke to firmware defaults.
        Epd.setPenState(Epd.PenState.START)
        Epd.setStroke(prefs.style.code, prefs.widthPx.toFloat(), BLACK)
        Epd.setPenState(Epd.PenState.DRAW)
        open = true; paused = false; stale = false
        guardLive(true)
    }

    /** A session opened ahead of time and paused at once, ready to resume the moment a chosen app sees the pen. */
    private fun standby() {
        arm()
        if (open) { Epd.setPenState(Epd.PenState.PAUSE); paused = true; guardLive(false) }
    }

    /** Resumes a paused session that may be another app's, with this app's region and stroke. */
    private fun takeOver() {
        val (w, h) = screenSize() ?: return
        val side = maxOf(w, h)
        Epd.setRegionLimit(intArrayOf(0, 0, side, side), screen = false)
        Epd.setStroke(prefs.style.code, prefs.widthPx.toFloat(), BLACK)
        Epd.setPenState(Epd.PenState.DRAW)
        open = true; paused = false; stale = false
        guardLive(true)
    }

    private fun pause() {
        if (!drawing) return
        Epd.setPenState(Epd.PenState.PAUSE)
        paused = true
        guardLive(false)
    }

    private fun resume() {
        // If the firmware or another app closed the session meanwhile, open a new one.
        if (Epd.penState() == Epd.PenState.STOP) { arm(); return }
        Epd.setPenState(Epd.PenState.DRAW)
        paused = false
        guardLive(true)
    }

    private fun guardLive(live: Boolean) {
        if (live == guarded) return
        guarded = live
        if (live) InkGuard.process.sessionOpened() else InkGuard.process.sessionClosed()
    }

    /** Screen off: clear the preview and pause, keeping the session open for the stroke right after unlocking. */
    private fun pauseForScreen() {
        handler.removeCallbacks(recheck)
        holds.near = false; holds.touching = false
        holds.end(HoldPolicy.End.ScreenOff)
        if (!drawing) return
        pause()
        if (InstantInk.status == InstantInk.Status.Armed) InstantInk.setStatus(InstantInk.Status.Ready)
    }

    private fun disarm(reason: HoldPolicy.End) {
        handler.removeCallbacks(recheck)
        val held = holds.holding
        holds.forget(reason)
        holds.touching = false
        if (open || held) Epd.release()
        open = false; paused = false; stale = false
        guardLive(false)
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
        private const val BLACK = -0x1000000
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
