package app.booxultimatum.nib.diag

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.TypedValue
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import app.booxultimatum.kit.ink.PenSession
import app.booxultimatum.kit.ink.PreviewStroke
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.input.InputSample
import app.booxultimatum.nib.engine.input.StrokeBuilder
import app.booxultimatum.nib.engine.input.Tool
import app.booxultimatum.nib.engine.record.PenAction
import app.booxultimatum.nib.engine.render.StrokeRenderer
import app.booxultimatum.nib.pen.NibPen
import app.booxultimatum.nib.pen.PenRecorderStore
import app.booxultimatum.nib.pen.PenRouter
import app.booxultimatum.nib.pen.PressureNormalizer
import app.booxultimatum.nib.render.CanvasSink
import kotlin.math.roundToInt

/**
 * A Diagnostics probe: horizontal bands the owner draws in with the pen. Entering a band sets its preview stroke on
 * the same display pen session the canvas uses ([NibPen]); after each lift the stroke is drawn in the band and the
 * frame swapped, after the band's extra delay. Pen only; fingers are ignored.
 */
class ProbeView(context: Context) : View(context), PenRouter.Target {
    private val log = Logbook.logger("nib.probe")
    private val controller = NibPen.controller()
    private val sink = CanvasSink()
    private val density = resources.displayMetrics.density
    private val rule = Paint().apply {
        color = 0xFF9A9A9A.toInt()
        strokeWidth = density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4A4A4A.toInt()
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
    }

    var probe: Probe = Probe.Styles
        set(value) {
            if (field == value) return
            field = value
            strokes.clear()
            band = -1
            invalidate()
        }

    /** What the display is asked to preview in a band; the band's own stroke when null (Match preview sizes it). */
    var previewFor: ((Band) -> PreviewStroke)? = null

    /** Sends the current band's preview again (its size was changed). */
    fun refreshPreview() {
        val b = probe.bands.getOrNull(band) ?: return
        controller.setPreview(previewFor?.invoke(b) ?: b.preview)
    }

    /** Band labels, set by the screen from its strings. */
    var labels: List<String> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val strokes = ArrayList<Pair<Int, Stroke>>()
    private var band = -1
    private var builder: StrokeBuilder? = null
    private var builderBand = -1
    private var pointerId = -1
    private var previewed = false
    private var normalizer = PressureNormalizer(null, null)
    private var nextId = 1L
    private var startMs = 0L
    private var samples = 0
    private var resumed = false
    private var frameCallbackPosted = false

    val strokeCount: Int get() = strokes.size

    fun clear() {
        strokes.clear()
        invalidate()
    }

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                resumed = true
                updateOpen()
            }
            Lifecycle.Event.ON_PAUSE -> {
                resumed = false
                updateOpen()
            }
            else -> Unit
        }
    }

    init {
        controller.onSwapped = { delayMs, watchdog ->
            log.d("probe swap", "probe" to probe.id, "band" to bandKey(builderBand), "swap ms" to delayMs, "watchdog" to watchdog)
        }
    }

    private fun bandKey(i: Int): String? = probe.bands.getOrNull(i)?.key

    private fun updateOpen() {
        val want = isAttachedToWindow && resumed
        if (want) {
            if (controller.state == PenSession.State.Closed) {
                val first = probe.bands.first()
                val ok = controller.open(NibPen.panelLongSide(context), previewFor?.invoke(first) ?: first.preview)
                log.i("probe session", "probe" to probe.id, "opened" to ok, "state" to controller.state.name)
            }
            PenRouter.attach(this)
        } else {
            if (controller.state != PenSession.State.Closed) controller.close()
            PenRouter.detach(this)
        }
    }

    /** The display's session state, for the status line. */
    val sessionState: PenSession.State get() = controller.state

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val owner = findViewTreeLifecycleOwner()
        resumed = owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true
        owner?.lifecycle?.addObserver(lifecycleObserver)
        updateOpen()
    }

    override fun onDetachedFromWindow() {
        findViewTreeLifecycleOwner()?.lifecycle?.removeObserver(lifecycleObserver)
        if (controller.state != PenSession.State.Closed) controller.close()
        PenRouter.detach(this)
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) controller.unblock("focus") else controller.block("focus")
    }

    private val location = IntArray(2)

    override fun containsScreenPoint(x: Float, y: Float): Boolean {
        if (!isShown) return false
        getLocationOnScreen(location)
        return x >= location[0] && y >= location[1] && x < location[0] + width && y < location[1] + height
    }

    override fun onWindowHover(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            controller.hoverExit()
            return
        }
        val inside = containsScreenPoint(event.rawX, event.rawY)
        if (inside) {
            getLocationOnScreen(location)
            enterBand(bandAt(event.rawY - location[1]))
        }
        controller.hover(inside, NibPen.isErasing(event, 0), if (inside) null else PenRouter.stripOutside(this, event.rawX, event.rawY))
    }

    override fun onStylusTouchOutside(x: Float, y: Float) {
        controller.touchedOutside(PenRouter.stripOutside(this, x, y))
    }

    override fun onStylusTouchOutsideEnded() {
        controller.touchOutsideEnded()
    }

    private fun bandAt(y: Float): Int {
        val n = probe.bands.size
        if (height <= 0) return 0
        return (y / height * n).toInt().coerceIn(0, n - 1)
    }

    private fun enterBand(i: Int) {
        if (i == band) return
        band = i
        val b = probe.bands[i]
        controller.setPreview(previewFor?.invoke(b) ?: b.preview)
        controller.extraSwapDelayMs = b.swapDelayMs
    }

    // A drawing surface: strokes and gestures, nothing that clicks.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val i = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (!NibPen.isStylus(event.getToolType(i)) || builder != null) return true
                requestUnbufferedDispatch(event)
                val bi = bandAt(event.getY(i))
                enterBand(bi)
                val b = probe.bands[bi]
                previewed = controller.down()
                builder = StrokeBuilder(b.brush, b.color, nextId++)
                builderBand = bi
                pointerId = event.getPointerId(i)
                normalizer = NibPen.normalizer(event)
                startMs = SystemClock.uptimeMillis()
                samples = 0
                PenRecorderStore.begin("probe:${probe.id}:${b.key}", event.eventTime * 1_000_000L)
                add(event, i, -1, PenAction.Down)
                if (!previewed) invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (builder == null || index < 0) return true
                for (h in 0 until event.historySize) add(event, index, h, PenAction.Move)
                add(event, index, -1, PenAction.Move)
                if (!previewed) invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (builder == null || event.getPointerId(i) != pointerId) return true
                add(event, i, -1, PenAction.Up)
                val stroke = builder!!.finish()
                builder = null
                strokes.add(builderBand to stroke)
                controller.up()
                PenRecorderStore.end()
                invalidate()
                val ms = SystemClock.uptimeMillis() - startMs
                val b = probe.bands[builderBand]
                log.d(
                    "probe stroke",
                    "probe" to probe.id, "band" to b.key, "style" to (controller.preview ?: b.preview).style, "width" to (controller.preview ?: b.preview).widthPx,
                    "argb" to Integer.toHexString(b.preview.argb), "points" to stroke.size, "ms" to ms,
                    "hz" to if (ms > 0) ((samples - 1) * 1000f / ms).roundToInt() else 0,
                    "previewed" to previewed, "held" to controller.holding, "delay ms" to b.swapDelayMs,
                )
            }
            MotionEvent.ACTION_CANCEL -> {
                builder = null
                controller.up()
                PenRecorderStore.end()
                invalidate()
            }
        }
        return true
    }

    private fun add(event: MotionEvent, index: Int, h: Int, action: PenAction) {
        val b = builder ?: return
        val x = if (h < 0) event.getX(index) else event.getHistoricalX(index, h)
        val y = if (h < 0) event.getY(index) else event.getHistoricalY(index, h)
        val p = if (h < 0) event.getPressure(index) else event.getHistoricalPressure(index, h)
        val t = if (h < 0) event.eventTime else event.getHistoricalEventTime(h)
        val s = InputSample(x, y, normalizer.normalize(p), timeNanos = t * 1_000_000L)
        b.add(s)
        samples++
        PenRecorderStore.sample(action, Tool.Pen, s)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(-0x1)
        val n = probe.bands.size
        val bh = height / n.toFloat()
        for (i in 0 until n) {
            val top = i * bh
            if (i > 0) canvas.drawLine(0f, top, width.toFloat(), top, rule)
            labels.getOrNull(i)?.let { canvas.drawText(it, 12f * density, top + labelPaint.textSize + 6f * density, labelPaint) }
        }
        val s = sink.on(canvas)
        for ((_, st) in strokes) StrokeRenderer.render(st, s)
        builder?.takeIf { !previewed && !it.isEmpty }?.let { StrokeRenderer.render(it.current(), s) }
        if (controller.awaitingFrame && !frameCallbackPosted) {
            frameCallbackPosted = true
            Choreographer.getInstance().postFrameCallback {
                frameCallbackPosted = false
                controller.frameShown()
            }
        }
    }
}
