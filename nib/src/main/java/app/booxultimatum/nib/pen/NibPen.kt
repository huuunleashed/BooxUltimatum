package app.booxultimatum.nib.pen

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import app.booxultimatum.kit.ink.PenSession
import app.booxultimatum.kit.ink.SurfaceInk

/** The one display pen session this process has: the firmware keeps a single session, so the canvas and the probes share it. */
object NibPen {
    val session: PenSession by lazy { PenSession() }

    /** Main-thread [PenScheduler]. */
    val mainScheduler: PenScheduler by lazy {
        val handler = Handler(Looper.getMainLooper())
        PenScheduler { delayMs, block ->
            val r = Runnable { block() }
            handler.postDelayed(r, delayMs)
            ({ handler.removeCallbacks(r) })
        }
    }

    fun controller(): PenController = PenController(session, mainScheduler)

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

    fun normalizer(event: MotionEvent?): PressureNormalizer = PressureNormalizer(pressureRangeMax(event), SurfaceInk.maxTouchPressure)

    fun isStylus(toolType: Int): Boolean = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER

    /** Whether this pointer is erasing: the pen's eraser end, or its side button held. */
    fun isErasing(event: MotionEvent, index: Int): Boolean =
        event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER || (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
}

/**
 * Where window-level pen events go. The activity hands every stylus hover and touch to the surface showing now, so
 * hover over the toolbar and panels (which the surface itself never sees) still pauses the session.
 */
object PenRouter {
    /** A view that draws with the pen session. */
    interface Target {
        /** A stylus hover anywhere in the window, in screen coordinates. */
        fun onWindowHover(event: MotionEvent)

        /** A stylus touch that began outside this surface. */
        fun onStylusTouchOutside()

        /** Whether the screen point lies on this surface. */
        fun containsScreenPoint(x: Float, y: Float): Boolean
    }

    private var target: Target? = null

    fun attach(t: Target) {
        target = t
    }

    fun detach(t: Target) {
        if (target === t) target = null
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
        val action = event.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_POINTER_DOWN) return
        val i = event.actionIndex
        if (!NibPen.isStylus(event.getToolType(i))) return
        if (!t.containsScreenPoint(event.getRawX(i), event.getRawY(i))) t.onStylusTouchOutside()
    }
}
