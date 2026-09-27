package app.booxultimatum.core.sleep

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import app.booxultimatum.core.ink.SurfaceInk

/**
 * Owns the one window that can sit over Boox's sleep screen while the tablet is locked (see [LiveSleep]). It asks for
 * no accessibility events it uses and can't read window content (`res/xml/live_sleep_service.xml`); it only draws the
 * face while the screen is off and removes it the moment the screen turns on, so the lock screen and the PIN pad are
 * never covered.
 */
class LiveSleepService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: HandlerThread
    private lateinit var work: Handler
    private var view: FaceView? = null
    private var face: Bitmap? = null
    private var pending: Bitmap? = null
    private var waitingForDisplay = false
    private var shown = 0
    private var wake: PowerManager.WakeLock? = null

    /** Screen on and off, from the system; registered unexported, which system broadcasts still reach. */
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> onScreenOn()
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
            }
        }
    }

    /** Onyx's dream says when it has turned the display on for a refresh; it's sent to every app, so this is exported. */
    private val display = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getIntExtra("state", -1) == DISPLAY_ON && waitingForDisplay) main.post { showPending() }
        }
    }

    override fun onServiceConnected() {
        instance = this
        worker = HandlerThread("live-sleep").apply { start() }
        work = Handler(worker.looper)
        ContextCompat.registerReceiver(this, screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, display, IntentFilter(ONYX_DISPLAY_STATE), ContextCompat.RECEIVER_EXPORTED)
        Log.i(TAG, "connected")
        // Connected while asleep (an update installed, or the service restarted): carry on as if the tablet just slept.
        if (!LiveSleep.interactive(this)) onScreenOff()
    }

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(screen) }
        runCatching { unregisterReceiver(display) }
        removeFace()
        LiveSleep.cancel(this)
        if (::worker.isInitialized) worker.quitSafely()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun onScreenOff() {
        if (!LiveSleep.active(this)) return
        LiveSleep.onSlept(this)
        shown = 0
        LiveSleep.schedule(this)
        // The first live face goes up as soon as Onyx has drawn its own and dozed, not only at the first alarm: the
        // charging bar is covered from the start and the face reads the same from the first minute of sleep.
        main.postDelayed({ update(first = true) }, FIRST_DELAY_MS)
    }

    private fun onScreenOn() {
        removeFace()
        LiveSleep.cancel(this)
        if (LivePrefs.load(this).enabled) LiveSleep.onWoke(this, LivePrefs.load(this))
    }

    /** An update: render off the main thread, then ask Onyx to wake the display and show the face once it has. */
    private fun update(first: Boolean = false) {
        val prefs = LivePrefs.load(this)
        if (!LiveSleep.active(this) || LiveSleep.interactive(this)) return
        if (!first) LiveSleep.schedule(this, prefs)
        if (!LiveSleep.wantedNow(this, prefs)) return
        hold(8_000)
        work.post {
            val slept = LiveSleep.record(this)?.sleptAt ?: System.currentTimeMillis()
            val r = runCatching { SleepStudio.renderLive(this, slept, LiveSleep.levelAtSleep(this), null) }
                .onFailure { Log.w(TAG, "render failed", it) }.getOrNull() ?: return@post
            main.post {
                if (LiveSleep.interactive(this)) return@post
                pending = r.bitmap
                waitingForDisplay = true
                sendBroadcast(Intent(ONYX_REFRESH))
                // If Onyx never answers (another firmware), don't hold the frame forever: show it and let the next wake push it.
                main.postDelayed({ if (waitingForDisplay) showPending() }, 3_000)
            }
        }
    }

    private fun showPending() {
        val next = pending ?: return
        waitingForDisplay = false
        pending = null
        if (LiveSleep.interactive(this)) return
        val v = view ?: addFace() ?: return
        face = next
        v.bitmap = next
        v.invalidate()
        shown++
        LiveSleep.onUpdated(this)
        Log.i(TAG, "face shown, update $shown of this sleep")
        // Partial e-ink updates leave traces; a full repaint every few updates clears them, as Onyx's dream does.
        if (shown % FULL_REFRESH_EVERY == 0) main.postDelayed({ runCatching { SurfaceInk.repaintEverything() } }, 300)
    }

    private fun addFace(): FaceView? {
        val v = FaceView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "BooxUltimatum live sleep screen"
        }
        return runCatching { getSystemService(WindowManager::class.java).addView(v, lp); v }
            .onFailure { Log.w(TAG, "overlay refused", it) }.getOrNull()?.also { view = it }
    }

    private fun removeFace() {
        main.removeCallbacksAndMessages(null)
        waitingForDisplay = false
        pending = null
        view?.let { v -> runCatching { getSystemService(WindowManager::class.java).removeView(v) } }
        view = null
        face = null
        wake?.takeIf { it.isHeld }?.release()
    }

    private fun hold(ms: Long) {
        val w = wake ?: getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BooxUltimatum:live-sleep").also { wake = it }
        runCatching { w.acquire(ms) }
    }

    private class FaceView(context: Context) : View(context) {
        var bitmap: Bitmap? = null
        override fun onDraw(canvas: Canvas) {
            val b = bitmap
            if (b == null) { canvas.drawColor(Color.WHITE); return }
            // The face was rendered for this rotation at the panel's size; drawn 1:1 so no line is resampled.
            canvas.drawBitmap(b, 0f, 0f, null)
        }
    }

    companion object {
        private const val TAG = "LiveSleep"
        private const val ONYX_REFRESH = "onyx_dream_refresh"
        private const val ONYX_DISPLAY_STATE = "onyx.action.DISPLAY_CHANGED_STATE"
        private const val DISPLAY_ON = 2
        private const val FULL_REFRESH_EVERY = 6
        private const val FIRST_DELAY_MS = 4_000L

        @Volatile private var instance: LiveSleepService? = null

        val running: Boolean get() = instance != null

        fun tick(context: Context) {
            val s = instance
            if (s == null) { Log.i(TAG, "tick without the service; nothing to draw into"); return }
            s.main.post { s.update() }
        }
    }
}
