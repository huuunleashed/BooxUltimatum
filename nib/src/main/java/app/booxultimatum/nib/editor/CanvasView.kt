package app.booxultimatum.nib.editor

import android.app.ActivityManager
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import app.booxultimatum.kit.ink.PenSession
import app.booxultimatum.kit.ink.PreviewStroke
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.nib.NibSettings
import app.booxultimatum.nib.R
import app.booxultimatum.nib.brush.PreviewPolicy
import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.geom.Viewport
import app.booxultimatum.nib.engine.history.AddStroke
import app.booxultimatum.nib.engine.history.HistoryEvent
import app.booxultimatum.nib.engine.history.MoveLayer
import app.booxultimatum.nib.engine.history.SetLayerProps
import app.booxultimatum.nib.engine.input.InputSample
import app.booxultimatum.nib.engine.input.StrokeBuilder
import app.booxultimatum.nib.engine.input.Tool
import app.booxultimatum.nib.engine.record.PenAction
import app.booxultimatum.nib.engine.render.StrokeRenderer
import app.booxultimatum.nib.engine.render.TileGrid
import app.booxultimatum.nib.pen.NibPen
import app.booxultimatum.nib.pen.PenRecorderStore
import app.booxultimatum.nib.pen.PenRouter
import app.booxultimatum.nib.pen.PressureNormalizer
import app.booxultimatum.nib.render.CanvasSink
import app.booxultimatum.nib.render.TileCache
import app.booxultimatum.nib.render.toBlendMode
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The drawing surface: pen and finger input, the view of the page, and the tiles that show it.
 *
 * - **Pen.** Stylus events become [StrokeBuilder] samples (historical ones included, unbuffered). With the display's
 *   pen session ([NibPen]) the firmware previews the stroke while its frames are held; after the lift the stroke is
 *   committed, drawn into the tiles, and the controller swaps once that frame is on its way. Without a session the
 *   stroke is drawn live, every move.
 * - **Erasers.** The eraser end, the side button or the eraser tool erase with live software rendering: pixel
 *   erasing previews exactly, stroke erasing hides the strokes it has hit, the lasso shows its outline.
 * - **Fingers.** Ignored while the pen is near. Two fingers pinch and pan, two- and three-finger taps undo and redo,
 *   one finger pans or draws depending on the settings.
 */
class CanvasView(context: Context) : View(context), PenRouter.Target {
    private val log = Logbook.logger("nib.pen")
    private val uiLog = Logbook.logger("nib.ui")
    private val settings = NibSettings.get(context)
    private val tools = ToolState.get(context)
    private val grid = TileGrid(256)
    private val tiles = TileCache(grid, TileCache.budgetFor(context.getSystemService(ActivityManager::class.java).memoryClass)) { invalidate() }
    private val density = resources.displayMetrics.density

    /** The display's pen session policy, shared with the probes through [NibPen]. */
    val controller = NibPen.controller()

    /** Called with a string resource to show briefly (a refused edit, say). */
    var onMessage: ((Int) -> Unit)? = null

    /** Called when the zoom settles, for the toolbar's readout. */
    var onViewportChanged: ((Viewport) -> Unit)? = null

    var viewport = Viewport(minScale = MIN_SCALE, maxScale = MAX_SCALE)
        private set

    var session: EditorSession? = null
        set(value) {
            if (field === value) return
            field?.removeEditListener(onEdit)
            field = value
            tiles.clear()
            fitted = false
            value?.addEditListener(onEdit)
            if (value != null && width > 0) restoreOrFit()
            updateBlocks()
            updateSessionOpen()
            invalidate()
        }

    private var fitted = false
    private var resumed = false
    private var frameCallbackPosted = false

    // The stroke under way.
    private class Live(
        val builder: StrokeBuilder,
        val kind: BrushKind,
        val tool: Tool,
        val pointerId: Int,
        val layerId: Long,
        val previewed: Boolean,
        val merged: Boolean,
        val startMs: Long,
        val normalizer: PressureNormalizer,
    ) {
        var samples = 0
        var pMin = 1f
        var pMax = 0f
        var lastMs = startMs
        var lastPressure = 1f
        val path = FloatList()
        val hits = LinkedHashSet<Long>()
        var erasePreview: List<Stroke> = emptyList()
    }

    private var live: Live? = null
    private val pendingLogs = ArrayList<Array<Pair<String, Any?>>>()

    // Pen proximity, for palm rejection.
    private var hovering = false
    private var lastPenMs = 0L

    // Fingers.
    private enum class FingerMode { None, Pan, Pinch, Draw, Ignored }
    private var fingerMode = FingerMode.None
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var pinching = false
    private var frozenLevel = 0
    private val taps = MultiTapDetector(slopPx = 12f * resources.displayMetrics.density)
    private val scaler = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (fingerMode != FingerMode.Pinch) return false
                viewport = viewport.zoomAround(d.focusX, d.focusY, d.scaleFactor)
                invalidate()
                return true
            }
        },
    ).apply { isQuickScaleEnabled = false }

    // Drawing.
    private val viewSink = CanvasSink()
    private val docMatrix = Matrix()
    private val pageRect = RectF()
    private val paperPaint = Paint()
    private val layerPaint = Paint()
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density)
        color = 0xFF000000.toInt()
    }
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, 1.5f * density)
        color = 0xFF000000.toInt()
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density)
        color = 0xFF4A4A4A.toInt()
    }
    private val lassoPath = Path()

    private val previewUpdate = Runnable { if (live == null) controller.setPreview(currentPreview()) }

    private val onEdit: (HistoryEvent) -> Unit = { e -> applyEdit(e) }

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                resumed = true
                updateSessionOpen()
            }
            Lifecycle.Event.ON_PAUSE -> {
                resumed = false
                cancelLive()
                updateSessionOpen()
            }
            else -> Unit
        }
    }

    init {
        isFocusable = true
        controller.onSwapped = { delayMs, watchdog ->
            for (f in pendingLogs) log.d("stroke", *f, "held" to true, "swap ms" to delayMs, "watchdog" to watchdog)
            pendingLogs.clear()
        }
    }

    // ---- The view of the page ----

    /** Fits the whole page in the view. */
    fun fit() {
        val doc = session?.document ?: return
        if (width == 0 || height == 0) return
        viewport = viewport.fit(doc.width.toFloat(), doc.height.toFloat(), width.toFloat(), height.toFloat(), MARGIN_DP * density)
        fitted = true
        viewportSettled()
    }

    /** One document pixel to one screen pixel, around the centre of the view. */
    fun actualSize() {
        viewport = viewport.zoomAround(width / 2f, height / 2f, 1f / viewport.scale)
        viewportSettled()
    }

    /** Zooms by [factor] around the centre of the view. */
    fun zoomBy(factor: Float) {
        viewport = viewport.zoomAround(width / 2f, height / 2f, factor)
        viewportSettled()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (session == null) return
        if (!fitted || oldw == 0 || oldh == 0) {
            restoreOrFit()
            return
        }
        // Rotation, or a docked panel opening: the document point at the centre stays at the centre.
        val cx = viewport.toDocX(oldw / 2f)
        val cy = viewport.toDocY(oldh / 2f)
        viewport = viewport.copy(offsetX = w / 2f - cx * viewport.scale, offsetY = h / 2f - cy * viewport.scale)
        viewportSettled()
    }

    private fun restoreOrFit() {
        val saved = session?.lastViewport
        if (saved == null) {
            fit()
        } else {
            viewport = saved
            fitted = true
            viewportSettled()
        }
    }

    private fun viewportSettled() {
        session?.lastViewport = viewport
        invalidate()
        onViewportChanged?.invoke(viewport)
        refreshPreview()
    }

    // ---- The pen session ----

    /** Tools, brush, colour or settings changed: update the preview stroke and what blocks it. */
    fun onToolsChanged() {
        updateBlocks()
        refreshPreview()
    }

    private fun refreshPreview() {
        removeCallbacks(previewUpdate)
        postDelayed(previewUpdate, PREVIEW_DEBOUNCE_MS)
    }

    private fun currentPreview(): PreviewStroke {
        val p = tools.current
        return PreviewPolicy.preview(p.spec(), p.color, viewport.scale, settings.tryUnverifiedStyles)
    }

    /** A panel or menu over the canvas opened or closed. */
    fun setPanelOpen(open: Boolean) {
        if (open) controller.block(BLOCK_PANEL) else controller.unblock(BLOCK_PANEL)
    }

    private fun updateBlocks() {
        if (tools.mode == ToolMode.Eraser) controller.block(BLOCK_ERASER) else controller.unblock(BLOCK_ERASER)
        val layer = session?.activeLayer
        if (layer != null && (layer.locked || !layer.visible)) controller.block(BLOCK_LAYER) else controller.unblock(BLOCK_LAYER)
        controller.extraSwapDelayMs = settings.swapDelayMs.toLong()
    }

    private fun updateSessionOpen() {
        val want = isAttachedToWindow && resumed && hasWindowFocus() && session != null
        if (want) {
            if (controller.state == PenSession.State.Closed) {
                controller.open(NibPen.panelLongSide(context), currentPreview())
                updateBlocks()
            }
            PenRouter.attach(this)
        } else if (!resumed || !isAttachedToWindow) {
            if (controller.state != PenSession.State.Closed) controller.close()
            PenRouter.detach(this)
        }
    }

    /** Clears any stuck preview and held frames (overflow › Recover screen). Returns whether a session exists. */
    fun recoverScreen(): Boolean {
        cancelLive()
        controller.recover()
        invalidate()
        uiLog.i("recover screen", "state" to controller.state.name)
        return controller.available
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val owner = findViewTreeLifecycleOwner()
        resumed = owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true
        owner?.lifecycle?.addObserver(lifecycleObserver)
        updateSessionOpen()
    }

    override fun onDetachedFromWindow() {
        findViewTreeLifecycleOwner()?.lifecycle?.removeObserver(lifecycleObserver)
        cancelLive()
        removeCallbacks(previewUpdate)
        if (controller.state != PenSession.State.Closed) controller.close()
        PenRouter.detach(this)
        tiles.stop()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) controller.unblock(BLOCK_FOCUS) else controller.block(BLOCK_FOCUS)
        updateSessionOpen()
    }

    override fun onWindowHover(event: MotionEvent) {
        lastPenMs = SystemClock.uptimeMillis()
        val inside = containsScreenPoint(event.rawX, event.rawY)
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            hovering = false
            controller.hoverExit()
            return
        }
        hovering = inside
        controller.hover(inside, NibPen.isErasing(event, 0) || tools.mode == ToolMode.Eraser)
    }

    override fun onStylusTouchOutside() {
        controller.touchedOutside()
    }

    private val location = IntArray(2)

    override fun containsScreenPoint(x: Float, y: Float): Boolean {
        if (!isShown) return false
        getLocationOnScreen(location)
        return x >= location[0] && y >= location[1] && x < location[0] + width && y < location[1] + height
    }

    private fun penNear(): Boolean = hovering || live?.tool == Tool.Pen || live?.tool == Tool.Eraser ||
        SystemClock.uptimeMillis() - lastPenMs < PALM_WINDOW_MS

    // ---- Input ----

    // A drawing surface: strokes and gestures, nothing that clicks.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (session == null) return false
        val action = event.actionMasked
        val i = event.actionIndex
        val l = live
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (NibPen.isStylus(event.getToolType(i))) {
                    startStroke(event, i, stylus = true)
                    return true
                }
                if (l != null && l.tool != Tool.Finger) return true
                fingerDown(event, i)
            }
            MotionEvent.ACTION_MOVE -> {
                if (l != null) {
                    val index = event.findPointerIndex(l.pointerId)
                    if (index >= 0) moveStroke(event, index)
                    if (l.tool != Tool.Finger) return true
                }
                fingerMove(event)
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (l != null && event.getPointerId(i) == l.pointerId) {
                    endStroke(event, i)
                    if (action == MotionEvent.ACTION_UP) fingerEnd(event)
                    return true
                }
                if (l != null && l.tool != Tool.Finger) return true
                fingerUp(event, i)
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelLive()
                fingerEnd(event, cancelled = true)
            }
        }
        return true
    }

    private fun startStroke(event: MotionEvent, index: Int, stylus: Boolean) {
        val s = session ?: return
        if (stylus) {
            requestUnbufferedDispatch(event)
            lastPenMs = SystemClock.uptimeMillis()
            cancelFingerGesture()
        }
        live?.let { cancelLive() }
        val erasing = stylus && (NibPen.isErasing(event, index) || tools.mode == ToolMode.Eraser)
        val layer = s.activeLayer
        val merged = stylus && controller.swapPending
        val previewed = stylus && controller.down()
        if (layer.locked || !layer.visible) {
            onMessage?.invoke(if (layer.locked) R.string.message_layer_locked else R.string.message_layer_hidden)
        }
        val preset = tools.current
        var brush = if (erasing) tools.eraserSpec() else preset.spec()
        if (!erasing && layer.alphaLock && brush.blend == Blend.Normal) brush = brush.copy(blend = Blend.Atop)
        val tool = when {
            !stylus -> Tool.Finger
            erasing -> Tool.Eraser
            else -> Tool.Pen
        }
        val l = Live(
            StrokeBuilder(brush, if (erasing) BLACK else preset.color, s.newId()),
            brush.kind, tool, event.getPointerId(index), layer.id, previewed, merged,
            SystemClock.uptimeMillis(), NibPen.normalizer(event),
        )
        live = l
        PenRecorderStore.begin("canvas", eventNanos(event))
        PenRecorderStore.marker("viewport", "${viewport.scale},${viewport.offsetX},${viewport.offsetY}", eventNanos(event))
        PenRecorderStore.marker("previewed", previewed.toString(), eventNanos(event))
        addSample(l, event, index, -1, PenAction.Down)
        if (!previewed || erasing) invalidate()
    }

    private fun moveStroke(event: MotionEvent, index: Int) {
        val l = live ?: return
        if (l.tool != Tool.Finger) lastPenMs = SystemClock.uptimeMillis()
        for (h in 0 until event.historySize) addSample(l, event, index, h, PenAction.Move)
        addSample(l, event, index, -1, PenAction.Move)
        if (!l.previewed || l.tool == Tool.Eraser) invalidate()
    }

    private fun addSample(l: Live, event: MotionEvent, index: Int, h: Int, action: PenAction) {
        val vx = if (h < 0) event.getX(index) else event.getHistoricalX(index, h)
        val vy = if (h < 0) event.getY(index) else event.getHistoricalY(index, h)
        val raw = if (h < 0) event.getPressure(index) else event.getHistoricalPressure(index, h)
        val tilt = if (h < 0) event.getAxisValue(MotionEvent.AXIS_TILT, index) else event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, h)
        val orientation = if (h < 0) event.getAxisValue(MotionEvent.AXIS_ORIENTATION, index) else event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, index, h)
        val ms = if (h < 0) event.eventTime else event.getHistoricalEventTime(h)
        // Many digitisers report no pressure on the lift itself; that last point keeps the pressure before it.
        val pressure = when {
            l.tool == Tool.Finger -> 1f
            action == PenAction.Up && raw <= 0f && l.samples > 0 -> l.lastPressure
            else -> l.normalizer.normalize(raw)
        }
        l.lastPressure = pressure
        val sample = InputSample(viewport.toDocX(vx), viewport.toDocY(vy), pressure, tilt, orientation, ms * 1_000_000L)
        l.builder.add(sample)
        l.samples++
        l.lastMs = ms
        if (pressure < l.pMin) l.pMin = pressure
        if (pressure > l.pMax) l.pMax = pressure
        PenRecorderStore.sample(action, l.tool, sample)
        if (l.kind == BrushKind.StrokeEraser || l.kind == BrushKind.LassoEraser) {
            l.path.add(sample.x, sample.y)
            if (l.kind == BrushKind.StrokeEraser) hitTest(l)
        }
    }

    private fun hitTest(l: Live) {
        val s = session ?: return
        val n = l.path.points
        val from = max(0, n - 2)
        val seg = l.path.data.copyOfRange(from * 2, n * 2)
        val radius = l.builder.brush.width / 2f
        val found = s.history.index.hitByEraser(l.layerId, seg, seg.size / 2, radius)
        var added = false
        for (st in found) if (l.hits.add(st.id)) added = true
        if (added) {
            val layer = s.document.layer(l.layerId) ?: return
            l.erasePreview = l.hits.mapNotNull { id -> layer.stroke(id) }.map { it.copy(brush = it.brush.copy(blend = Blend.Erase, opacity = 1f)) }
        }
    }

    private fun endStroke(event: MotionEvent, index: Int) {
        val l = live ?: return
        val s = session ?: return
        addSample(l, event, index, -1, PenAction.Up)
        live = null
        val stroke = l.builder.finish()
        val layer = s.document.layer(l.layerId)
        var committed = false
        var removed = 0
        if (layer != null && !layer.locked && layer.visible) {
            when (l.kind) {
                BrushKind.StrokeEraser -> {
                    removed = l.hits.size
                    committed = l.hits.isNotEmpty() && s.removeStrokes(l.hits)
                }
                BrushKind.LassoEraser -> {
                    val ids = if (l.path.points >= 3) s.history.index.insideLasso(l.layerId, l.path.data, l.path.points).map { it.id } else emptyList()
                    removed = ids.size
                    committed = ids.isNotEmpty() && s.removeStrokes(ids)
                }
                else -> committed = s.addStroke(stroke)
            }
        }
        controller.up()
        PenRecorderStore.marker("committed", committed.toString(), eventNanos(event))
        PenRecorderStore.end()
        invalidate()
        summarize(l, stroke, committed, removed)
    }

    private fun summarize(l: Live, stroke: Stroke, committed: Boolean, removed: Int) {
        val ms = l.lastMs - l.startMs
        val hz = if (ms > 0) (l.samples - 1) * 1000f / ms else 0f
        val preview = controller.preview
        val fields = arrayOf<Pair<String, Any?>>(
            "points" to stroke.size, "samples" to l.samples, "ms" to ms, "hz" to hz.roundToInt(),
            "p min" to round2(l.pMin), "p max" to round2(l.pMax), "tool" to l.tool.name.lowercase(),
            "brush" to l.kind.id, "width" to l.builder.brush.width, "committed" to committed, "removed" to removed,
            "previewed" to l.previewed, "merged" to l.merged,
            "style" to preview?.style, "preview width" to preview?.widthPx?.let { round2(it) },
            "commit ms" to round2(lastCommitMs), "zoom" to round2(viewport.scale),
        )
        lastCommitMs = 0.0
        if (controller.holding) pendingLogs.add(fields) else log.d("stroke", *fields, "held" to false, "swap ms" to -1)
    }

    private var lastCommitMs = 0.0

    private fun cancelLive() {
        val l = live ?: return
        live = null
        if (l.tool != Tool.Finger) controller.up()
        PenRecorderStore.marker("cancelled", "true", SystemClock.uptimeMillis() * 1_000_000L)
        PenRecorderStore.end()
        invalidate()
    }

    private fun eventNanos(event: MotionEvent): Long =
        if (Build.VERSION.SDK_INT >= 34) event.eventTimeNanos else event.eventTime * 1_000_000L

    // ---- Fingers ----

    private fun fingerDown(event: MotionEvent, index: Int) {
        val count = event.pointerCount
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (penNear()) {
                fingerMode = FingerMode.Ignored
                return
            }
            taps.start(event.eventTime, event.getPointerId(index), event.getX(index), event.getY(index))
            fingerMode = when {
                settings.fingerDrawing -> FingerMode.Draw.also { startStroke(event, index, stylus = false) }
                settings.fingerPan -> FingerMode.Pan
                else -> FingerMode.None
            }
            focus(event)
            scaler.onTouchEvent(event)
            return
        }
        if (fingerMode == FingerMode.Ignored) return
        taps.pointerDown(event.getPointerId(index), event.getX(index), event.getY(index), count)
        if (fingerMode == FingerMode.Draw) live?.let { if (it.tool == Tool.Finger) discardLive() }
        if (fingerMode != FingerMode.Pinch) {
            fingerMode = FingerMode.Pinch
            pinching = true
            frozenLevel = TileGrid.scaleBucket(viewport.scale)
            controller.block(BLOCK_GESTURE)
        }
        focus(event)
        scaler.onTouchEvent(event)
    }

    private fun fingerMove(event: MotionEvent) {
        if (fingerMode == FingerMode.Ignored || fingerMode == FingerMode.None && event.pointerCount < 2) return
        for (p in 0 until event.pointerCount) taps.move(event.getPointerId(p), event.getX(p), event.getY(p))
        if (penNear() && fingerMode != FingerMode.Pinch) {
            taps.cancel()
            return
        }
        when (fingerMode) {
            FingerMode.Pinch -> {
                scaler.onTouchEvent(event)
                panToFocus(event)
            }
            FingerMode.Pan -> panToFocus(event)
            else -> Unit
        }
    }

    private fun fingerUp(event: MotionEvent, index: Int) {
        if (fingerMode == FingerMode.Ignored) {
            if (event.actionMasked == MotionEvent.ACTION_UP) fingerMode = FingerMode.None
            return
        }
        if (fingerMode == FingerMode.Pinch) scaler.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            fingerEnd(event)
        } else {
            // One finger lifted: the focus jumps, so start measuring from the remaining fingers.
            focus(event, excluding = index)
        }
    }

    private fun fingerEnd(event: MotionEvent, cancelled: Boolean = false) {
        val result = if (cancelled) MultiTapDetector.Result.None else taps.end(event.eventTime)
        val wasGesture = fingerMode == FingerMode.Pinch || fingerMode == FingerMode.Pan
        if (fingerMode == FingerMode.Pinch) {
            pinching = false
            controller.unblock(BLOCK_GESTURE)
        }
        fingerMode = FingerMode.None
        if (wasGesture) viewportSettled()
        when (result) {
            MultiTapDetector.Result.Undo -> if (session?.undo() == true) onMessage?.invoke(R.string.message_undone)
            MultiTapDetector.Result.Redo -> if (session?.redo() == true) onMessage?.invoke(R.string.message_redone)
            MultiTapDetector.Result.None -> Unit
        }
    }

    private fun cancelFingerGesture() {
        taps.cancel()
        if (live?.tool == Tool.Finger) discardLive()
        if (fingerMode == FingerMode.Pinch) {
            pinching = false
            controller.unblock(BLOCK_GESTURE)
            viewportSettled()
        }
        fingerMode = FingerMode.Ignored
    }

    private fun discardLive() {
        live = null
        PenRecorderStore.end()
        invalidate()
    }

    private fun focus(event: MotionEvent, excluding: Int = -1) {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (p in 0 until event.pointerCount) {
            if (p == excluding) continue
            sx += event.getX(p)
            sy += event.getY(p)
            n++
        }
        if (n == 0) return
        lastFocusX = sx / n
        lastFocusY = sy / n
    }

    private fun panToFocus(event: MotionEvent) {
        val fx = lastFocusX
        val fy = lastFocusY
        focus(event)
        val dx = lastFocusX - fx
        val dy = lastFocusY - fy
        if (dx != 0f || dy != 0f) {
            viewport = viewport.pan(dx, dy)
            invalidate()
        }
    }

    // ---- Edits ----

    private fun applyEdit(e: HistoryEvent) {
        val doc = e.document
        tiles.retainLayers(doc.layers.mapTo(HashSet()) { it.id })
        when (val c = e.effective) {
            is AddStroke -> {
                val stored = doc.layer(c.layerId)?.strokes?.lastOrNull()
                if (stored != null && stored.id == c.stroke.id) lastCommitMs = tiles.appendStroke(c.layerId, stored) else tiles.invalidate(c.layerId, e.change.bounds)
            }
            // Tiles hold only a layer's strokes; its opacity, blend, visibility and place apply when compositing.
            is SetLayerProps, is MoveLayer -> Unit
            else -> for (id in e.change.layers) if (doc.layer(id) != null) tiles.invalidate(id, e.change.bounds)
        }
        updateBlocks()
        invalidate()
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        // Not drawColor: inside Compose the canvas isn't clipped to this view.
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(DESK)
        val s = session ?: return
        val doc = s.document
        pageRect.set(viewport.toViewX(0f), viewport.toViewY(0f), viewport.toViewX(doc.width.toFloat()), viewport.toViewY(doc.height.toFloat()))
        paperPaint.color = doc.background or BLACK
        canvas.drawRect(pageRect, paperPaint)
        val level = if (pinching) frozenLevel else TileGrid.scaleBucket(viewport.scale)
        tiles.beginFrame(level)
        val visible = viewport.visibleDocRect(width.toFloat(), height.toFloat()).intersect(doc.bounds)
        val l = live
        if (!visible.isEmpty) {
            val range = grid.range(visible, level)
            canvas.save()
            canvas.clipRect(pageRect)
            for (layer in doc.layers) {
                if (!layer.visible || layer.opacity <= 0f) continue
                val liveHere = l != null && layer.id == l.layerId && showsLive(l)
                val isolate = layer.opacity < 1f || layer.blend != Blend.Normal || liveHere
                if (isolate) {
                    layerPaint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255f).roundToInt()
                    layerPaint.blendMode = layer.blend.toBlendMode()
                    canvas.saveLayer(pageRect, layerPaint)
                }
                tiles.drawLayer(canvas, layer, viewport, range) { box -> s.history.index.query(layer.id, box).isNotEmpty() }
                if (liveHere) drawLive(canvas, l!!)
                if (isolate) canvas.restore()
            }
            canvas.restore()
        }
        canvas.drawRect(pageRect, rimPaint)
        if (l != null) drawEraserMarks(canvas, l)
        tiles.endFrame()
        if (controller.awaitingFrame && !frameCallbackPosted) {
            frameCallbackPosted = true
            // The frame drawn now reaches the display by the next vsync; swap then.
            Choreographer.getInstance().postFrameCallback {
                frameCallbackPosted = false
                controller.frameShown()
            }
        }
    }

    private fun showsLive(l: Live): Boolean = when (l.kind) {
        BrushKind.LassoEraser -> false
        BrushKind.StrokeEraser -> l.erasePreview.isNotEmpty()
        else -> !l.previewed || l.tool == Tool.Eraser
    }

    private fun drawLive(canvas: Canvas, l: Live) {
        canvas.save()
        docMatrix.setValues(viewport.docToView().toMatrixValues())
        canvas.concat(docMatrix)
        val sink = viewSink.on(canvas)
        val tolerance = 0.25f / viewport.scale
        if (l.kind == BrushKind.StrokeEraser) {
            for (st in l.erasePreview) StrokeRenderer.render(st, sink, tolerance)
        } else if (!l.builder.isEmpty) {
            StrokeRenderer.render(l.builder.current(), sink, tolerance)
        }
        canvas.restore()
    }

    private fun drawEraserMarks(canvas: Canvas, l: Live) {
        val n = l.path.points
        if (n == 0) return
        val xy = l.path.data
        if (l.kind == BrushKind.LassoEraser) {
            lassoPath.rewind()
            lassoPath.moveTo(viewport.toViewX(xy[0]), viewport.toViewY(xy[1]))
            for (i in 1 until n) lassoPath.lineTo(viewport.toViewX(xy[2 * i]), viewport.toViewY(xy[2 * i + 1]))
            if (n >= 3) lassoPath.close()
            canvas.drawPath(lassoPath, lassoPaint)
        } else if (l.kind == BrushKind.StrokeEraser) {
            val r = max(2f * density, l.builder.brush.width / 2f * viewport.scale)
            canvas.drawCircle(viewport.toViewX(xy[2 * n - 2]), viewport.toViewY(xy[2 * n - 1]), r, cursorPaint)
        }
    }

    // ---- For tests and diagnostics ----

    /** Whether the pen hovers over the canvas, as the window reports it. */
    val penHovering: Boolean get() = hovering

    val tileCount: Int get() = tiles.tileCount
    val tileBytes: Long get() = tiles.usedBytes
    val pendingTiles: Int get() = tiles.pendingJobs

    private fun round2(v: Float): Float = Math.round(v * 100f) / 100f
    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    companion object {
        const val MIN_SCALE = 0.25f
        const val MAX_SCALE = 16f
        private const val MARGIN_DP = 24f
        private const val PALM_WINDOW_MS = 600L
        private const val PREVIEW_DEBOUNCE_MS = 120L
        private const val BLACK = -0x1000000
        private const val DESK = -0x1
        private const val BLOCK_PANEL = "panel"
        private const val BLOCK_ERASER = "eraser"
        private const val BLOCK_LAYER = "layer"
        private const val BLOCK_GESTURE = "gesture"
        private const val BLOCK_FOCUS = "focus"
    }
}
