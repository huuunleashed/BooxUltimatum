package app.booxultimatum.nib.diag

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.TypedValue
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.graphics.withTranslation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import app.booxultimatum.kit.ink.canvas.InkCanvasController
import app.booxultimatum.kit.ink.eink.Eink
import app.booxultimatum.kit.ink.epd.Epd
import app.booxultimatum.kit.ink.epd.HandwritingLayer
import app.booxultimatum.kit.ink.input.PalmGuard
import app.booxultimatum.kit.ink.session.InkSession
import app.booxultimatum.kit.ink.session.InkStroke
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.brush.PenButtons
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.input.InputSample
import app.booxultimatum.nib.engine.input.StrokeBuilder
import app.booxultimatum.nib.engine.input.Tool
import app.booxultimatum.nib.engine.record.PenAction
import app.booxultimatum.nib.engine.render.StrokeRenderer
import app.booxultimatum.nib.pen.NibPen
import app.booxultimatum.nib.pen.PenParts
import app.booxultimatum.nib.pen.PenRecorderStore
import app.booxultimatum.nib.pen.PenRouter
import app.booxultimatum.nib.pen.PressureNormalizer
import app.booxultimatum.nib.pen.ScreenAreas
import app.booxultimatum.nib.render.CanvasSink
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A Diagnostics probe: horizontal bands the owner draws in with the pen, on the same display pen session the canvas
 * uses ([NibPen]) and the same choreography ([InkCanvasController]). Entering a band sets its preview stroke; each
 * stroke is drawn in the band, and Nib's frame replaces the preview as the probe's [Probe.reveal] says. The screen
 * around the surface is left out of the preview. Diagnostics › Lab's probes add what they test: the ink or the
 * controls pushed into the display's layer, the eraser end and the lasso previewed, fast mode while a finger moves the
 * surface, Palm guard, a preview limited to one band, and a stroke fed to the display's own width model.
 */
class ProbeView(context: Context) : View(context), PenRouter.Target {
    private val log = Logbook.logger("nib.probe")
    private val controller = NibPen.controller()
    private val palm = PalmGuard(NibPen.mainScheduler)
    private val sink = CanvasSink()
    private val density = resources.displayMetrics.density
    private val rule = Paint().apply {
        color = 0xFF9A9A9A.toInt()
        strokeWidth = density
    }
    private val gridPaint = Paint().apply {
        color = 0xFFCCCCCC.toInt()
        strokeWidth = density
    }
    private val limitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = BLACK
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 5f * density), 0f)
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
            controller.reveal = value.reveal
            applyLayout()
            invalidate()
        }

    /** What the display is asked to preview in a band; the band's own stroke when null (Match preview sizes it). */
    var previewFor: ((Band) -> InkStroke)? = null

    /** Sends the current band's preview again (its size or parameters changed). */
    fun refreshPreview() {
        val b = probe.bands.getOrNull(band) ?: return
        controller.setStroke(strokeFor(b))
    }

    /** Band labels, set by the screen from its strings. */
    var labels: List<String> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** Called after each stroke drawn into a band (Lab › Push controls counts them). */
    var onStrokeAdded: ((Band) -> Unit)? = null

    /** Pushes the Lab's changed controls into the display's layer; true if shown. */
    var pushControls: (() -> Boolean)? = null

    /** Called for every finger that touches the surface: whether the pen was near, and whether Palm guard was on. */
    var onFingerTouch: ((penNear: Boolean, guardOn: Boolean) -> Unit)? = null

    /** Lab › Fast mode: a finger moving the surface uses the display's fast mode. */
    var fastGestures: Boolean
        get() = controller.fastGestures
        set(v) {
            controller.fastGestures = v
        }

    /** How long the pen rests before Nib pushes its ink (Lab › Push ink), as the owner set it for the canvas. */
    var pauseMs: Long
        get() = controller.pauseMs
        set(v) {
            controller.pauseMs = v
        }

    /** Lab › Palm guard: finger touch off over the surface while the pen is near, where the display's pen path is there. */
    var palmGuard: Boolean = false
        set(v) {
            field = v
            palm.enabled = v && controller.available
        }

    private val strokes = ArrayList<Pair<Int, Stroke>>()
    private var band = -1
    private var builder: StrokeBuilder? = null
    private var builderBand = -1
    private var pointerId = -1
    private var previewed = false
    private var erasing = false
    private val eraserPath = ArrayList<Float>()
    private var normalizer = PressureNormalizer(null, null)
    private var nextId = 1L
    private var startMs = 0L
    private var samples = 0
    private var minX = 0f
    private var minY = 0f
    private var maxX = 0f
    private var maxY = 0f
    private var resumed = false
    private var frameCallbackPosted = false
    private var upSeq = 0
    private var reportedSeq = 0
    private var frameDelayMs = 0L
    private var capturing = false
    private var hovering = false
    private var lastPenMs = 0L
    private var controlsPushPosted = false

    // Lab › Fast mode: a finger moves what's drawn.
    private var offsetX = 0f
    private var offsetY = 0f
    private var panId = -1
    private var panX = 0f
    private var panY = 0f
    private var panTravel = 0f
    private var panOn = false

    val strokeCount: Int get() = strokes.size

    /** The display's session state, for the status line. */
    val sessionState: InkSession.State get() = controller.session.state

    /** Whether the display holds this app's frames now, and how many strokes it has held. */
    val holding: Boolean get() = controller.holding
    val holdStrokes: Int get() = controller.session.strokesInHold

    fun clear() {
        strokes.clear()
        offsetX = 0f
        offsetY = 0f
        controller.releaseNow("clear")
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

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { applyLayout() }

    private val controlsPush = Runnable {
        controlsPushPosted = false
        controller.controlsChanged()
    }

    init {
        controller.host = object : InkCanvasController.Host {
            override fun pushInk(dirty: IntArray): Boolean = pushInkArea(dirty)
            override fun pushControls(): Boolean {
                val push = pushControls ?: return true
                val t0 = SystemClock.uptimeMillis()
                capturing = true
                val ok = try { push() } finally { capturing = false }
                log.i("probe controls pushed", "probe" to probe.id, "ok" to ok, "ms" to SystemClock.uptimeMillis() - t0, "hold strokes" to holdStrokes)
                return ok
            }
            override fun fastMode(on: Boolean) {
                if (!controller.available) return
                val ok = Eink.fastMode(on)
                log.i("probe fast mode", "probe" to probe.id, "on" to on, "ok" to ok)
            }
        }
        controller.onReleased = { reason, n -> log.d("probe release", "probe" to probe.id, "reason" to reason, "strokes" to n) }
        controller.rearmMs = NibPen.rearmMs(context)
        controller.reveal = probe.reveal
        palm.onChanged = { on -> log.i("probe palm guard", "on" to on) }
    }

    private fun strokeFor(b: Band): InkStroke = previewFor?.invoke(b) ?: b.preview

    /** The eraser end previews an eraser track in Lab › Eraser end; everywhere else the probes leave the pen's parts alone. */
    private fun penParts(): List<Epd.ButtonStroke> =
        if (probe != Probe.LabEraserEnd) emptyList()
        else PenParts.both(strokeFor(probe.bands.first()), PenButtons.eraserEnd(ERASER_PX)).map { it.toButton() }

    private fun updateOpen() {
        val want = isAttachedToWindow && resumed
        if (want) {
            if (controller.session.state == InkSession.State.Closed) {
                val first = probe.bands.first()
                // The session is shared with the canvas: what it re-arms with must be this probe's.
                partsRect = if (width > 0) screenRect() else null
                controller.session.setPenButtons(partsRect ?: IntArray(4), penParts())
                val ok = controller.open(strokeFor(first), NibPen.panelLongSide(context), limitRect())
                if (ok && probe == Probe.LabPenState4) {
                    // The SDK names state 4 erasing but never sends it: ask the display to hold it and read back what it kept.
                    Epd.setPenState(4)
                    log.i("probe pen state", "probe" to probe.id, "sent" to 4, "read back" to Epd.penState(), "route" to Epd.route?.name)
                }
                band = -1
                applyLayout()
                log.i("probe session", "probe" to probe.id, "opened" to ok, "state" to controller.session.state.name, "reveal" to probe.reveal.name, "limit" to limitRect()?.joinToString(","))
            }
            PenRouter.attach(this)
        } else {
            close()
            PenRouter.detach(this)
        }
    }

    private fun close() {
        palm.stop()
        if (controller.session.state == InkSession.State.Closed) return
        controller.close()
        if (probe == Probe.LabParams) {
            log.i("style parameters after leaving", "fountain" to Epd.strokeParameters(1)?.joinToString(","), "route" to Epd.route?.name)
        }
    }

    /** The screen around the surface stays out of the preview; Lab › Limit keeps the preview inside its middle band. */
    private fun applyLayout() {
        palm.enabled = palmGuard && controller.available
        if (!controller.available || width == 0 || !isAttachedToWindow) return
        if (controller.touching) return
        val rect = screenRect()
        controller.setControls(ScreenAreas.around(rect, rootRect()))
        controller.setLimit(limitRect())
        if (probe == Probe.LabEraserEnd && !rect.contentEquals(partsRect)) {
            partsRect = rect
            controller.session.setPenButtons(rect, penParts())
            log.i("probe pen parts", "probe" to probe.id, "rect" to rect.joinToString(","))
        }
    }

    private var partsRect: IntArray? = null

    private val location = IntArray(2)
    private val rootLocation = IntArray(2)

    private fun screenRect(): IntArray {
        getLocationOnScreen(location)
        return intArrayOf(location[0], location[1], location[0] + width, location[1] + height)
    }

    private fun rootRect(): IntArray {
        rootView.getLocationOnScreen(rootLocation)
        return intArrayOf(rootLocation[0], rootLocation[1], rootLocation[0] + rootView.width, rootLocation[1] + rootView.height)
    }

    private fun bandTop(i: Int): Float = height * i / probe.bands.size.toFloat()

    /** The limit band's rectangle on screen, or null for no limit. */
    private fun limitRect(): IntArray? {
        val i = probe.limitBand
        if (i < 0 || height == 0) return null
        val r = screenRect()
        return intArrayOf(r[0], r[1] + bandTop(i).roundToInt(), r[2], r[1] + bandTop(i + 1).roundToInt())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val owner = findViewTreeLifecycleOwner()
        resumed = owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true
        owner?.lifecycle?.addObserver(lifecycleObserver)
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        updateOpen()
        // The session is shared: a screen leaving in the same pass (the editor, say) closes it after this attach.
        post { updateOpen() }
    }

    override fun onDetachedFromWindow() {
        findViewTreeLifecycleOwner()?.lifecycle?.removeObserver(lifecycleObserver)
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        close()
        PenRouter.detach(this)
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            controller.unblock("focus")
        } else {
            controller.block("focus")
            palm.stop()
        }
    }

    override fun containsScreenPoint(x: Float, y: Float): Boolean {
        if (!isShown) return false
        getLocationOnScreen(location)
        return x >= location[0] && y >= location[1] && x < location[0] + width && y < location[1] + height
    }

    override fun onWindowHover(event: MotionEvent) {
        lastPenMs = SystemClock.uptimeMillis()
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            hovering = false
            palm.penAway()
            return
        }
        val inside = containsScreenPoint(event.rawX, event.rawY)
        hovering = inside
        if (palm.enabled && width > 0) palm.penNear(screenRect(), ScreenAreas.around(screenRect(), rootRect()))
        if (inside) {
            getLocationOnScreen(location)
            enterBand(bandAt(event.rawY - location[1]))
        }
        val pause = NibPen.isSideButton(event) || (NibPen.isEraserEnd(event, 0) && !eraserDraws())
        // A block, not the hover's pause: a pen touch resumes a session that's only paused.
        if (pause) controller.block(ERASER) else controller.unblock(ERASER)
        controller.hover(if (inside) InkCanvasController.Target.Canvas else InkCanvasController.Target.Controls)
    }

    override fun onControlsTouched(x: Float, y: Float, stylus: Boolean) {
        controller.controlsTouched()
    }

    override fun onControlsTouchEnded() {
        controller.controlsTouchEnded()
    }

    /** The probes where the owner draws with the eraser end and the display is left to answer. */
    private fun eraserDraws() = probe == Probe.LabEraserEnd || probe == Probe.LabEraserPainters

    private fun bandAt(y: Float): Int {
        val n = probe.bands.size
        if (height <= 0) return 0
        return (y / height * n).toInt().coerceIn(0, n - 1)
    }

    private fun enterBand(i: Int) {
        if (i == band) return
        band = i
        controller.setStroke(strokeFor(probe.bands[i]))
        if (probe == Probe.LabEraserPainters) {
            // Bands are painters 0 to 8 top to bottom; the owner rubs with the eraser end in each and answers per band.
            Epd.setEraserRawDrawing(true, i)
            log.i("probe eraser painter", "probe" to probe.id, "band" to probe.bands[i].key, "painter" to i, "route" to Epd.route?.name)
        }
    }

    /** A control beside the surface changed (Lab › Push controls); once drawn, it's pushed if frames are held. */
    fun controlsChangedSoon() {
        if (controlsPushPosted) return
        controlsPushPosted = true
        Choreographer.getInstance().postFrameCallback { post(controlsPush) }
    }

    /**
     * Lab › Fed stroke: with the preview paused, feeds a line of points across the surface's middle to the display's
     * own stroke-width model (start, add, finish, in screen coordinates) and returns the width it answered for each,
     * null where the call failed. Whether the display draws them is the owner's question.
     */
    fun feedStroke(): List<Float?> {
        val r = screenRect()
        controller.block(FED)
        val y = (r[1] + r[3]) / 2f
        val x0 = r[0] + (r[2] - r[0]) * 0.15f
        val x1 = r[0] + (r[2] - r[0]) * 0.85f
        val widths = ArrayList<Float?>(FED_POINTS + 1)
        for (k in 0..FED_POINTS) {
            val t = k / FED_POINTS.toFloat()
            val kind = when (k) {
                0 -> 0
                FED_POINTS -> 2
                else -> 1
            }
            val pressure = 0.25f + 0.6f * sin(PI.toFloat() * t)
            widths += Epd.strokeWidthAt(kind, FED_BASE_WIDTH, x0 + (x1 - x0) * t, y, pressure, 1f, k * FED_STEP_MS)
        }
        controller.unblock(FED)
        log.i(
            "fed stroke", "widths" to widths.joinToString(",") { it?.let { v -> "%.2f".format(v) } ?: "none" },
            "base width" to FED_BASE_WIDTH, "points" to widths.size, "coords" to "screen", "route" to Epd.route?.name,
            "session" to controller.session.state.name,
        )
        return widths
    }

    // A drawing surface: strokes and gestures, nothing that clicks.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val i = event.actionIndex
        if (!NibPen.isStylus(event.getToolType(i)) && pointerId < 0) return finger(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (!NibPen.isStylus(event.getToolType(i)) || builder != null) return true
                requestUnbufferedDispatch(event)
                lastPenMs = SystemClock.uptimeMillis()
                if (palm.enabled && width > 0) palm.penNear(screenRect(), ScreenAreas.around(screenRect(), rootRect()))
                val bi = bandAt(event.getY(i))
                enterBand(bi)
                val b = probe.bands[bi]
                erasing = NibPen.isErasing(event, i)
                if (NibPen.isSideButton(event) || (NibPen.isEraserEnd(event, i) && !eraserDraws())) controller.block(ERASER)
                previewed = controller.down()
                val brush = if (erasing) BrushSpec.defaults(BrushKind.StrokeEraser).withWidth(ERASER_PX) else b.brush
                builder = StrokeBuilder(brush, b.color, nextId++)
                builderBand = bi
                pointerId = event.getPointerId(i)
                normalizer = NibPen.normalizer(event)
                startMs = SystemClock.uptimeMillis()
                samples = 0
                eraserPath.clear()
                minX = Float.POSITIVE_INFINITY; minY = Float.POSITIVE_INFINITY
                maxX = Float.NEGATIVE_INFINITY; maxY = Float.NEGATIVE_INFINITY
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
                finishStroke()
                palm.penAway()
            }
            MotionEvent.ACTION_CANCEL -> {
                builder = null
                pointerId = -1
                lifted(null)
                PenRecorderStore.end()
                invalidate()
            }
        }
        return true
    }

    private fun finishStroke() {
        val stroke = builder!!.finish()
        builder = null
        pointerId = -1
        val b = probe.bands[builderBand]
        frameDelayMs = b.swapDelayMs
        lifted(if (previewed) strokeScreenBounds() else null)
        var removed = 0
        when {
            erasing -> {
                val r = ERASER_PX / 2f
                val before = strokes.size
                strokes.removeAll { (_, st) -> hitsPath(st, r) }
                removed = before - strokes.size
            }
            b.kind == BandKind.Lasso -> Unit
            else -> strokes.add(builderBand to stroke)
        }
        PenRecorderStore.end()
        invalidate()
        if (previewed) {
            if (erasing) controller.releaseNow("eraser end") else if (b.kind == BandKind.Lasso) controller.releaseNow("lasso")
        }
        val ms = SystemClock.uptimeMillis() - startMs
        val sent = controller.stroke ?: strokeFor(b)
        log.d(
            "probe stroke",
            "probe" to probe.id, "band" to b.key, "style" to sent.style, "width" to sent.widthPx, "argb" to Integer.toHexString(sent.argb),
            "params" to sent.params?.joinToString(","), "points" to stroke.size, "ms" to ms,
            "hz" to if (ms > 0) ((samples - 1) * 1000f / ms).roundToInt() else 0,
            "previewed" to previewed, "held" to controller.holding, "hold strokes" to holdStrokes, "reveal" to controller.reveal.name,
            "erasing" to erasing, "removed" to removed, "delay ms" to b.swapDelayMs,
        )
        if (!erasing && b.kind == BandKind.Ink) onStrokeAdded?.invoke(b)
    }

    private fun lifted(bounds: IntArray?) {
        val held = controller.holding
        controller.up(bounds)
        if (held) upSeq++
    }

    /** Whether the eraser's path, [r] wide, passes over any point of [st]. */
    private fun hitsPath(st: Stroke, r: Float): Boolean {
        val box = st.bounds
        for (k in 0 until eraserPath.size / 2) {
            val x = eraserPath[2 * k] - offsetX
            val y = eraserPath[2 * k + 1] - offsetY
            if (x < box.left - r || x > box.right + r || y < box.top - r || y > box.bottom + r) continue
            for (p in 0 until st.size) if (hypot(st.points.x(p) - x, st.points.y(p) - y) <= r + st.brush.width / 2f) return true
        }
        return false
    }

    private fun strokeScreenBounds(): IntArray? {
        if (minX > maxX) return null
        val pad = (controller.stroke?.widthPx ?: 4f) + BOUNDS_PAD_PX
        getLocationOnScreen(location)
        return intArrayOf(
            (minX - pad).toInt() + location[0], (minY - pad).toInt() + location[1],
            (maxX + pad).toInt() + 1 + location[0], (maxY + pad).toInt() + 1 + location[1],
        )
    }

    /** Pushes the surface's pixels inside [dirty] (screen coordinates) into the display's layer (Lab › Push ink). */
    private fun pushInkArea(dirty: IntArray): Boolean {
        val root = rootView
        root.getLocationOnScreen(rootLocation)
        val r = Rect(dirty[0] - rootLocation[0], dirty[1] - rootLocation[1], dirty[2] - rootLocation[0], dirty[3] - rootLocation[1])
        if (!r.intersect(0, 0, root.width, root.height)) return false
        val bytes = r.width().toLong() * r.height() * 4L
        if (bytes > MAX_PUSH_BYTES) {
            log.i("probe ink push refused", "probe" to probe.id, "bytes" to bytes)
            return false
        }
        val t0 = SystemClock.uptimeMillis()
        capturing = true
        val ok = try { HandwritingLayer.pushArea(root, r) } finally { capturing = false }
        log.i("probe ink pushed", "probe" to probe.id, "w" to r.width(), "h" to r.height(), "ms" to SystemClock.uptimeMillis() - t0, "ok" to ok)
        return ok
    }

    /** A finger on the surface: counted for Lab › Palm guard, and it moves what's drawn in Lab › Fast mode. */
    private fun finger(event: MotionEvent): Boolean {
        val i = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val near = hovering || SystemClock.uptimeMillis() - lastPenMs < PALM_WINDOW_MS
                onFingerTouch?.invoke(near, palm.active)
                if (probe == Probe.LabPalmGuard) log.i("probe finger touch", "pen near" to near, "guard on" to palm.active)
                if (probe == Probe.LabFastMode && panId < 0) {
                    panId = event.getPointerId(i)
                    panX = event.getX(i)
                    panY = event.getY(i)
                    panTravel = 0f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(panId)
                if (index < 0) return true
                val dx = event.getX(index) - panX
                val dy = event.getY(index) - panY
                panX = event.getX(index)
                panY = event.getY(index)
                offsetX += dx
                offsetY += dy
                panTravel += hypot(dx, dy)
                if (!panOn && panTravel >= PAN_SLOP_DP * density) {
                    panOn = true
                    controller.gestureStarted()
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(i) != panId) return true
                if (panOn) {
                    panOn = false
                    controller.gestureEnded()
                }
                panId = -1
            }
        }
        return true
    }

    private fun add(event: MotionEvent, index: Int, h: Int, action: PenAction) {
        val b = builder ?: return
        val x = if (h < 0) event.getX(index) else event.getHistoricalX(index, h)
        val y = if (h < 0) event.getY(index) else event.getHistoricalY(index, h)
        val p = if (h < 0) event.getPressure(index) else event.getHistoricalPressure(index, h)
        val tilt = if (h < 0) event.getAxisValue(MotionEvent.AXIS_TILT, index) else event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, h)
        val orientation = if (h < 0) event.getAxisValue(MotionEvent.AXIS_ORIENTATION, index) else event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, index, h)
        val t = if (h < 0) event.eventTime else event.getHistoricalEventTime(h)
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
        if (erasing) {
            eraserPath += x
            eraserPath += y
        }
        val s = InputSample(x - offsetX, y - offsetY, normalizer.normalize(p), tilt, orientation, t * 1_000_000L)
        b.add(s)
        samples++
        PenRecorderStore.sample(action, if (erasing) Tool.Eraser else Tool.Pen, s)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(WHITE)
        if (probe == Probe.LabFastMode) drawGrid(canvas)
        val n = probe.bands.size
        for (i in 0 until n) {
            val top = bandTop(i)
            if (i > 0) canvas.drawLine(0f, top, width.toFloat(), top, rule)
            labels.getOrNull(i)?.let { canvas.drawText(it, 12f * density, top + labelPaint.textSize + 6f * density, labelPaint) }
        }
        probe.limitBand.takeIf { it >= 0 }?.let { i ->
            val inset = limitPaint.strokeWidth
            canvas.drawRect(inset, bandTop(i) + inset, width - inset, bandTop(i + 1) - inset, limitPaint)
        }
        val s = sink.on(canvas)
        canvas.withTranslation(offsetX, offsetY) {
            for ((_, st) in strokes) StrokeRenderer.render(st, s)
            val live = builder
            if (live != null && !previewed && !erasing && probe.bands.getOrNull(builderBand)?.kind == BandKind.Ink && !live.isEmpty) StrokeRenderer.render(live.current(), s)
        }
        if (capturing) return
        if (upSeq != reportedSeq && !frameCallbackPosted) {
            frameCallbackPosted = true
            val seq = upSeq
            Choreographer.getInstance().postFrameCallback {
                frameCallbackPosted = false
                if (seq != upSeq) {
                    invalidate()
                    return@postFrameCallback
                }
                if (frameDelayMs > 0) postDelayed({ reportFrame(seq) }, frameDelayMs) else reportFrame(seq)
            }
        }
    }

    private fun reportFrame(seq: Int) {
        if (seq != upSeq) {
            invalidate()
            return
        }
        reportedSeq = seq
        controller.frameShown()
    }

    /** A grid that moves with what's drawn, so a moving surface shows even before anything is drawn on it. */
    private fun drawGrid(canvas: Canvas) {
        val cell = GRID_DP * density
        var x = ((offsetX % cell) + cell) % cell
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += cell
        }
        var y = ((offsetY % cell) + cell) % cell
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y += cell
        }
    }

    companion object {
        private const val BLACK = -0x1000000
        private const val WHITE = -0x1
        private const val ERASER_PX = 24f
        private const val BOUNDS_PAD_PX = 3f
        private const val MAX_PUSH_BYTES = 4L * 1024 * 1024
        private const val PALM_WINDOW_MS = 600L
        private const val PAN_SLOP_DP = 4f
        private const val GRID_DP = 48f
        private const val FED = "fed stroke"
        private const val ERASER = "eraser"
        private const val FED_POINTS = 24
        private const val FED_BASE_WIDTH = 4f
        private const val FED_STEP_MS = 8f
    }
}
