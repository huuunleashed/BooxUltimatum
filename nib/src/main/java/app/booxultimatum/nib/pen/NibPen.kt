package app.booxultimatum.nib.pen

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import app.booxultimatum.kit.core.Tablet
import app.booxultimatum.kit.ink.canvas.InkCanvasController
import app.booxultimatum.kit.ink.canvas.InkScheduler
import app.booxultimatum.kit.ink.epd.Epd
import app.booxultimatum.kit.ink.session.EpdInkDisplay
import app.booxultimatum.kit.ink.session.InkGuard
import app.booxultimatum.kit.ink.session.InkSession
import app.booxultimatum.kit.log.Logbook
import java.io.File

/**
 * The one display pen session this process has: the firmware keeps a single session, so the canvas and the probes
 * share it, each through its own [InkCanvasController].
 */
object NibPen {
    private val log = Logbook.logger("nib.pen")

    val session: InkSession by lazy { InkSession(EpdInkDisplay(), InkGuard.process) }

    /**
     * At process start: [InkGuard] undoes whatever an ended Nib process left on the display (a live session, fast
     * mode, finger touch switched off, replaced style parameters) and keeps its record from now on. Nib 0.2's lease
     * file is honoured once, so an update from it mid-session still ends the session it left.
     */
    fun start(context: Context) {
        runCatching { InkGuard.process.attach(File(context.noBackupFilesDir, GUARD_FILE)) }
            .onFailure { log.w("display guard not attached", error = it) }
        val old = File(context.noBackupFilesDir, OLD_LEASE)
        if (old.exists()) runCatching {
            old.delete()
            val ended = InkGuard.Undo.Display.endLiveSession()
            log.w("session lease left by Nib 0.2", "ended" to ended)
        }
    }

    /** Main-thread [InkScheduler]. */
    val mainScheduler: InkScheduler by lazy {
        val handler = Handler(Looper.getMainLooper())
        InkScheduler { delayMs, block ->
            val r = Runnable { block() }
            handler.postDelayed(r, delayMs)
            ({ handler.removeCallbacks(r) })
        }
    }

    fun controller(): InkCanvasController = InkCanvasController(session, mainScheduler)

    /** How long after a pause the pen may preview again: longer on colour panels, which finish their update later. */
    fun rearmMs(context: Context): Long = PanelKind.rearmMs(Tablet.current(context))

    /** The panel's long side in pixels, which sizes the session's square region. */
    fun panelLongSide(context: Context): Int {
        val b = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return maxOf(b.width(), b.height())
    }

    /** The panel's size in its portrait form: the default page. */
    fun panelPortrait(context: Context): Pair<Int, Int> {
        val b = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return minOf(b.width(), b.height()) to maxOf(b.width(), b.height())
    }

    /** The stylus's pressure maximum as Android reports it, when a stylus is listed. */
    fun pressureRangeMax(event: MotionEvent?): Float? {
        val device = event?.device ?: stylusDevice() ?: return null
        return device.getMotionRange(MotionEvent.AXIS_PRESSURE)?.max?.takeIf { it > 0f }
    }

    fun stylusDevice(): InputDevice? = InputDevice.getDeviceIds().asSequence()
        .mapNotNull { InputDevice.getDevice(it) }
        .firstOrNull { it.supportsSource(InputDevice.SOURCE_STYLUS) }

    fun normalizer(event: MotionEvent?): PressureNormalizer = PressureNormalizer(pressureRangeMax(event), Epd.maxTouchPressure)

    fun isStylus(toolType: Int): Boolean = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER

    /** Whether this pointer is erasing: the pen's eraser end, or its side button held. */
    fun isErasing(event: MotionEvent, index: Int): Boolean = isEraserEnd(event, index) || isSideButton(event)

    /** The pen's eraser end (Android reports it as its own tool). */
    fun isEraserEnd(event: MotionEvent, index: Int): Boolean = event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER

    /** The pen's side button held. */
    fun isSideButton(event: MotionEvent): Boolean = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

    private const val GUARD_FILE = "ink-guard"
    private const val OLD_LEASE = "pen-session.lease"
}

/**
 * Where window-level touches and pen hovers go. The activity hands every stylus hover and every touch to the surface
 * showing now, before Compose sees them, so the surface knows when the pen is over a control (which it never sees
 * itself) and when the pen or a finger presses one: that's a break, and the held frames are let through so the
 * control can show it responded.
 */
object PenRouter {
    /** A view that draws with the pen session. */
    interface Target {
        /** A stylus hover anywhere in the window, in screen coordinates. */
        fun onWindowHover(event: MotionEvent)

        /** The pen or a finger touched the app's controls, anywhere off this surface, at this screen point. */
        fun onControlsTouched(x: Float, y: Float, stylus: Boolean)

        /** Every pointer that touched the controls has lifted. */
        fun onControlsTouchEnded()

        /** Whether the screen point lies on this surface (and not on a control over it). */
        fun containsScreenPoint(x: Float, y: Float): Boolean
    }

    private var target: Target? = null
    private val outside = HashSet<Int>()

    fun attach(t: Target) {
        if (target !== t) outside.clear()
        target = t
    }

    fun detach(t: Target) {
        if (target === t) {
            target = null
            outside.clear()
        }
    }

    fun onGenericMotion(event: MotionEvent) {
        val t = target ?: return
        if (!event.isFromSource(InputDevice.SOURCE_STYLUS) && !NibPen.isStylus(event.getToolType(0))) return
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT -> t.onWindowHover(event)
        }
    }

    fun onTouch(event: MotionEvent) {
        val t = target ?: return
        val i = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) outside.clear()
                val x = event.getRawX(i)
                val y = event.getRawY(i)
                if (t.containsScreenPoint(x, y)) return
                val first = outside.isEmpty()
                outside += event.getPointerId(i)
                if (first) t.onControlsTouched(x, y, NibPen.isStylus(event.getToolType(i)))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                if (outside.remove(event.getPointerId(i)) && outside.isEmpty()) t.onControlsTouchEnded()
            MotionEvent.ACTION_CANCEL -> if (outside.isNotEmpty()) {
                outside.clear()
                t.onControlsTouchEnded()
            }
        }
    }
}
