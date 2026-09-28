package app.booxultimatum.nib.editor

import android.app.ActivityManager
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
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
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.withSave
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
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.doc.Layer
import app.booxultimatum.nib.engine.doc.Stroke
import app.booxultimatum.nib.engine.geom.Affine
import app.booxultimatum.nib.engine.geom.Box
import app.booxultimatum.nib.engine.geom.Vec
import app.booxultimatum.nib.engine.geom.Viewport
import app.booxultimatum.nib.engine.history.AddStroke
import app.booxultimatum.nib.engine.history.HistoryAction
import app.booxultimatum.nib.engine.history.HistoryEvent
import app.booxultimatum.nib.engine.history.MoveLayer
import app.booxultimatum.nib.engine.history.MoveStrokes
import app.booxultimatum.nib.engine.history.ReplaceStrokes
import app.booxultimatum.nib.engine.history.SetLayerProps
import app.booxultimatum.nib.engine.history.TransformStrokes
import app.booxultimatum.nib.engine.input.InputSample
import app.booxultimatum.nib.engine.input.StrokeBuilder
import app.booxultimatum.nib.engine.input.Tool
import app.booxultimatum.nib.engine.record.PenAction
import app.booxultimatum.nib.engine.render.DocumentRenderer
import app.booxultimatum.nib.engine.render.StrokeRenderer
import app.booxultimatum.nib.engine.render.TileGrid
import app.booxultimatum.nib.pen.NibPen
import app.booxultimatum.nib.pen.PenRecorderStore
import app.booxultimatum.nib.pen.PenRouter
import app.booxultimatum.nib.pen.PenShields
import app.booxultimatum.nib.pen.PressureNormalizer
import app.booxultimatum.nib.render.CanvasSink
import app.booxultimatum.nib.render.GuidesPainter
import app.booxultimatum.nib.render.TileCache
import app.booxultimatum.nib.render.toBlendMode
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The drawing surface: the desk, the page on it, pen and finger input, and the tiles that show the ink.
 *
 * - **Pen.** Stylus events become [StrokeBuilder] samples (historical ones included, unbuffered), mapped through the
 *   view's zoom, pan and turn. With the display's pen session ([NibPen]) the firmware previews the stroke while its
 *   frames are held; after the lift the stroke is committed, drawn into the tiles, and the controller swaps once that
 *   frame is on its way. Without a session the stroke is drawn live, every move. A stroke whose pen rests at its end
 *   becomes a straight line ([StraightLine]).
 * - **Tools.** The eraser end, the side button or the eraser tool erase; the lasso picks strokes and then moves, scales
 *   and turns them; the eyedropper takes a colour from the page; the hand turns the page. None of them preview on the
 *   display, which stays paused.
 * - **Fingers.** Ignored while the pen is near. Two fingers pinch, pan and twist (snapping to quarter turns), two- and
 *   three-finger taps undo and redo, one finger pans or draws depending on the settings.
 * - **Tiles.** Rendered upright in document space; a turned page is drawn by turning the canvas once.
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

    /** Called when the view settles after a zoom, a pan or a turn, for the view chip. */
    var onViewportChanged: ((Viewport) -> Unit)? = null

    /** Called when the lasso picks strokes, after they're moved, and with null when the selection ends. */
    var onSelectionChanged: ((Selection?) -> Unit)? = null

    /** Called with the colour the eyedropper took from the page. */
    var onColourPicked: ((Int) -> Unit)? = null

    /**
     * Called when the pen ([stylus]) or a finger first touches the canvas. Returning true swallows the pen's touch:
     * an open panel closes instead of the pen drawing.
     */
    var onCanvasTouch: ((stylus: Boolean) -> Boolean)? = null

    /** Where the floating controls lie over the canvas: the pen over them isn't over the canvas. */
    var shields: PenShields? = null

    /** Room kept clear of the floating controls when the page is fitted, in view pixels. */
    val fitInsets = RectF()

    var viewport = Viewport(minScale = MIN_SCALE, maxScale = MAX_SCALE)
        private set

    /** The strokes the lasso picked, or null. */
    var selection: Selection? = null
        private set

    var session: EditorSession? = null
        set(value) {
            if (field === value) return
            field?.removeEditListener(onEdit)
            field?.removePaperListener(onPaper)
            field = value
            tiles.clear()
            fitted = false
            setSelection(null)
            value?.addEditListener(onEdit)
            value?.addPaperListener(onPaper)
            if (value != null && width > 0) restoreOrFit()
            updateBlocks()
            updateSessionOpen()
            invalidate()
        }

    private var fitted = false

    // The view is the page fitted, untouched since: rotating the tablet fits it again rather than keeping its centre.
    private var followFit = false
    private var resumed = false
    private var frameCallbackPosted = false

    /** What a stroke under way is for. */
    private enum class Purpose { Ink, Erase, Select }

    // The stroke under way.
    private class Live(
        val builder: StrokeBuilder,
        val kind: BrushKind,
        val purpose: Purpose,
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
        var endX = 0f
        var endY = 0f
        var viewLength = 0f
        var lastVX = Float.NaN
        var lastVY = 0f
        var straight = false
        val path = FloatList()
        val hits = LinkedHashSet<Long>()
        var erasePreview: List<Stroke> = emptyList()
    }

    private var live: Live? = null
    private val pendingLogs = ArrayList<Array<Pair<String, Any?>>>()
    private val hold = HoldDetector(StraightLine.SLOP_DP * density, StraightLine.HOLD_MS)
    private val holdCheck = Runnable { checkHold() }

    // A one-pointer gesture that isn't a stroke: turning the page, picking a colour, moving a selection.
    private sealed class Grab(val pointerId: Int, val stylus: Boolean) {
        class Hand(id: Int, stylus: Boolean, val startAngle: Float, val startRotation: Float) : Grab(id, stylus)
        class Pick(id: Int, stylus: Boolean) : Grab(id, stylus) {
            var colour: Int? = null
            var x = 0f
            var y = 0f
        }
        class Transform(id: Int, stylus: Boolean, val handle: SelectionHandle, val start: Vec, val corners: FloatArray, val centre: Vec) : Grab(id, stylus) {
            var affine: Affine = Affine.IDENTITY
        }
    }

    private var grab: Grab? = null

    // A pen touch that closed a panel instead of drawing.
    private var swallowed = -1

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
    private var twistLast = Float.NaN
    private var twistRaw = 0f
    private val taps = MultiTapDetector(slopPx = 12f * resources.displayMetrics.density)
    private val scaler = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (fingerMode != FingerMode.Pinch) return false
                followFit = false
                viewport = viewport.zoomAround(d.focusX, d.focusY, d.scaleFactor)
                invalidate()
                return true
            }
        },
    ).apply { isQuickScaleEnabled = false }

    // Drawing.
    private val viewSink = CanvasSink()
    private val docMatrix = Matrix()
    private val turnMatrix = Matrix()
    private val flatPage = RectF()
    private val pagePath = Path()
    private val pageXY = FloatArray(8)
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLACK }
    private val layerPaint = Paint()
    private val deskGrid = Paint().apply {
        color = DESK_GRID
        strokeWidth = 1f
    }
    private val deskMajor = Paint().apply {
        color = DESK_GRID_MAJOR
        strokeWidth = 1f
    }
    private var deskLines: FloatArray? = null
    private var deskMajorLines: FloatArray? = null
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.MITER
        color = BLACK
    }
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, 1.5f * density)
        color = BLACK
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = BLACK
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 5f * density), 0f)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE }
    private val handleInk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = BLACK
    }
    private val solidInk = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLACK }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density)
        color = 0xFF4A4A4A.toInt()
    }
    private val loupePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lassoPath = Path()
    private val framePath = Path()
    private var pickBitmap: Bitmap? = null
    private val pickSink = CanvasSink()

    // Edits the selection makes re-render their tiles at once, so the strokes never flash back to where they were.
    private var renderNextEditNow = false

    private val previewUpdate = Runnable { if (live == null) controller.setPreview(currentPreview()) }

    private val onEdit: (HistoryEvent) -> Unit = { e -> applyEdit(e) }
    private val onPaper: () -> Unit = { invalidate() }

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                resumed = true
                updateSessionOpen()
            }
            Lifecycle.Event.ON_PAUSE -> {
                resumed = false
                cancelLive()
                cancelGrab()
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

    /** Fits the whole page, upright, in the room the floating controls leave. */
    fun fit() {
        val doc = session?.document ?: return
        if (width == 0 || height == 0) return
        val roomW = width - fitInsets.left - fitInsets.right
        val roomH = height - fitInsets.top - fitInsets.bottom
        val w = roomW.coerceAtLeast(width * 0.4f)
        val h = roomH.coerceAtLeast(height * 0.4f)
        val dx = if (w == roomW) fitInsets.left else (width - w) / 2f
        val dy = if (h == roomH) fitInsets.top else (height - h) / 2f
        viewport = viewport.fit(doc.width.toFloat(), doc.height.toFloat(), w, h, MARGIN_DP * density).pan(dx, dy)
        fitted = true
        followFit = true
        viewportSettled()
    }

    /** Reset view: the whole page, upright. */
    fun resetView() {
        fit()
        uiLog.i("view reset", "zoom" to round2(viewport.scale))
    }

    /** One document pixel to one screen pixel, around the centre of the view. */
    fun actualSize() {
        followFit = false
        viewport = viewport.zoomAround(width / 2f, height / 2f, 1f / viewport.scale)
        viewportSettled()
    }

    /** Zooms by [factor] around the centre of the view. */
    fun zoomBy(factor: Float) {
        followFit = false
        viewport = viewport.zoomAround(width / 2f, height / 2f, factor)
        viewportSettled()
    }

    /** Turns the page to [radians] about the centre of the view. */
    fun rotateTo(radians: Float) {
        followFit = false
        viewport = viewport.rotatedTo(width / 2f, height / 2f, radians)
        viewportSettled()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        deskLines = null
        deskMajorLines = null
        if (session == null) return
        if (!fitted || oldw == 0 || oldh == 0) {
            restoreOrFit()
            return
        }
        if (followFit) {
            fit()
            return
        }
        // Rotation of the tablet: the document point at the centre stays at the centre.
        viewport = viewport.pan((w - oldw) / 2f, (h - oldh) / 2f)
        viewportSettled()
    }

    private fun restoreOrFit() {
        val saved = session?.lastViewport
        if (saved == null) {
            fit()
        } else {
            viewport = saved
            fitted = true
            followFit = session?.lastViewportFitted == true
            viewportSettled()
        }
    }

    private fun viewportSettled() {
        session?.lastViewport = viewport
        session?.lastViewportFitted = followFit
        invalidate()
        onViewportChanged?.invoke(viewport)
        refreshPreview()
    }

    // ---- The pen session ----

    /** Tools, brush, colour or settings changed: update the preview stroke and what blocks it. */
    fun onToolsChanged() {
        if (tools.mode != ToolMode.Lasso && selection != null) setSelection(null)
        updateBlocks()
        refreshPreview()
    }

    private fun refreshPreview() {
        removeCallbacks(previewUpdate)
        postDelayed(previewUpdate, PREVIEW_DEBOUNCE_MS)
    }

    private fun currentPreview(): PreviewStroke {
        val p = tools.current
        return PreviewPolicy.preview(p.spec(), p.color, viewport.scale, settings.tryUnverifiedStyles, settings.previewMatch, settings.markerPreview)
    }

    /**
     * Something over the canvas that a touch on the canvas closes (an unpinned panel, the menu, a text entry) opened
     * or closed. While one is open the display doesn't preview: the pen's next touch closes it instead of drawing.
     */
    fun setPanelOpen(open: Boolean) {
        if (open) controller.block(BLOCK_PANEL) else controller.unblock(BLOCK_PANEL)
    }

    private fun updateBlocks() {
        if (tools.mode.quiet) controller.block(BLOCK_TOOL) else controller.unblock(BLOCK_TOOL)
        if (selection != null) controller.block(BLOCK_SELECTION) else controller.unblock(BLOCK_SELECTION)
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

    /** Clears any stuck preview and held frames (menu › Recover screen). Returns whether a session exists. */
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
        cancelGrab()
        removeCallbacks(previewUpdate)
        removeCallbacks(holdCheck)
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
        // Back over the canvas where the excluded control was (it closed or moved): let the preview draw there again.
        if (inside) controller.exclusion?.let { r -> if (event.rawX >= r[0] && event.rawX < r[2] && event.rawY >= r[1] && event.rawY < r[3]) controller.clearExclusion() }
        controller.hover(inside, NibPen.isErasing(event, 0) || tools.mode.quiet, if (inside) null else controlAt(event.rawX, event.rawY))
    }

    override fun onStylusTouchOutside(x: Float, y: Float) {
        controller.touchedOutside(controlAt(x, y))
    }

    override fun onStylusTouchOutsideEnded() {
        controller.touchOutsideEnded()
    }

    /** The control under a screen point, as a screen rectangle: a floating card, or the strip beyond the canvas's edge. */
    private fun controlAt(x: Float, y: Float): IntArray? {
        val s = shields
        if (s != null) {
            rootView.getLocationOnScreen(rootLocation)
            s.at(x - rootLocation[0], y - rootLocation[1])?.let { r ->
                return intArrayOf(
                    (r.left + rootLocation[0]).toInt(), (r.top + rootLocation[1]).toInt(),
                    kotlin.math.ceil(r.right + rootLocation[0]).toInt(), kotlin.math.ceil(r.bottom + rootLocation[1]).toInt(),
                )
            }
        }
        return PenRouter.stripOutside(this, x, y)
    }

    private val location = IntArray(2)
    private val rootLocation = IntArray(2)

    /** On the canvas and not on one of the floating controls over it. */
    override fun containsScreenPoint(x: Float, y: Float): Boolean {
        if (!isShown) return false
        getLocationOnScreen(location)
        if (x < location[0] || y < location[1] || x >= location[0] + width || y >= location[1] + height) return false
        val s = shields ?: return true
        rootView.getLocationOnScreen(rootLocation)
        return !s.covers(x - rootLocation[0], y - rootLocation[1])
    }

    private fun penNear(): Boolean = hovering || live?.tool == Tool.Pen || live?.tool == Tool.Eraser || grab?.stylus == true ||
        SystemClock.uptimeMillis() - lastPenMs < PALM_WINDOW_MS

    // ---- Input ----

    // A drawing surface: strokes and gestures, nothing that clicks.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (session == null) return false
        val action = event.actionMasked
        val i = event.actionIndex
        val l = live
        val g = grab
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (NibPen.isStylus(event.getToolType(i))) {
                    if (swallowed >= 0) return true
                    if (onCanvasTouch?.invoke(true) == true) {
                        swallowed = event.getPointerId(i)
                        cancelFingerGesture()
                        lastPenMs = SystemClock.uptimeMillis()
                        return true
                    }
                    penDown(event, i)
                    return true
                }
                if (l != null && l.tool != Tool.Finger) return true
                if (g != null && g.stylus) return true
                if (action == MotionEvent.ACTION_DOWN) onCanvasTouch?.invoke(false)
                fingerDown(event, i)
            }
            MotionEvent.ACTION_MOVE -> {
                if (swallowed >= 0 && event.pointerCount == 1) return true
                if (l != null) {
                    val index = event.findPointerIndex(l.pointerId)
                    if (index >= 0) moveStroke(event, index)
                    if (l.tool != Tool.Finger) return true
                }
                if (g != null) {
                    val index = event.findPointerIndex(g.pointerId)
                    if (index >= 0) grabMove(event.getX(index), event.getY(index))
                    if (g.stylus) return true
                }
                fingerMove(event)
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (swallowed >= 0 && event.getPointerId(i) == swallowed) {
                    swallowed = -1
                    lastPenMs = SystemClock.uptimeMillis()
                    return true
                }
                if (l != null && event.getPointerId(i) == l.pointerId) {
                    endStroke(event, i)
                    if (action == MotionEvent.ACTION_UP) fingerEnd(event)
                    return true
                }
                if (g != null && event.getPointerId(i) == g.pointerId) {
                    grabEnd(event.getX(i), event.getY(i))
                    if (action == MotionEvent.ACTION_UP) fingerEnd(event)
                    return true
                }
                if (l != null && l.tool != Tool.Finger) return true
                if (g != null && g.stylus) return true
                fingerUp(event, i)
            }
            MotionEvent.ACTION_CANCEL -> {
                swallowed = -1
                cancelLive()
                cancelGrab()
                fingerEnd(event, cancelled = true)
            }
        }
        return true
    }

    private fun penDown(event: MotionEvent, i: Int) {
        requestUnbufferedDispatch(event)
        lastPenMs = SystemClock.uptimeMillis()
        cancelFingerGesture()
        if (NibPen.isErasing(event, i)) {
            startStroke(event, i, stylus = true, Purpose.Erase)
            return
        }
        when (tools.mode) {
            ToolMode.Pen -> startStroke(event, i, stylus = true, Purpose.Ink)
            ToolMode.Eraser -> startStroke(event, i, stylus = true, Purpose.Erase)
            else -> toolDown(event, i, stylus = true)
        }
    }

    /** The lasso, the eyedropper and the hand, with the pen or one finger. */
    private fun toolDown(event: MotionEvent, i: Int, stylus: Boolean) {
        live?.let { cancelLive() }
        cancelGrab()
        val x = event.getX(i)
        val y = event.getY(i)
        val id = event.getPointerId(i)
        when (tools.mode) {
            ToolMode.Hand -> {
                if (stylus) controller.down()
                val cx = width / 2f
                val cy = height / 2f
                grab = Grab.Hand(id, stylus, atan2(y - cy, x - cx), viewport.rotation)
            }
            ToolMode.Eyedropper -> {
                if (stylus) controller.down()
                grab = Grab.Pick(id, stylus).also {
                    it.x = x
                    it.y = y
                    it.colour = colourAt(x, y)
                }
                invalidate()
            }
            ToolMode.Lasso -> {
                val sel = selection
                if (sel != null) {
                    val view = cornersInView(sel)
                    val handle = SelectionMath.hit(view, SelectionMath.rotateHandle(view, ROTATE_LIFT_DP * density), x, y, HANDLE_TOUCH_DP * density)
                    if (handle != SelectionHandle.Outside) {
                        if (stylus) controller.down()
                        grab = Grab.Transform(id, stylus, handle, viewport.toDoc(Vec(x, y)), sel.corners(), sel.centre)
                        return
                    }
                    setSelection(null)
                }
                startStroke(event, i, stylus, Purpose.Select)
            }
            else -> Unit
        }
    }

    private fun grabMove(x: Float, y: Float) {
        when (val g = grab ?: return) {
            is Grab.Hand -> {
                val cx = width / 2f
                val cy = height / 2f
                if (hypot(x - cx, y - cy) < HAND_MIN_RADIUS_DP * density) return
                val target = Viewport.snapToQuarter(g.startRotation + Viewport.normalize(atan2(y - cy, x - cx) - g.startAngle), SNAP_RADIANS)
                if (target != viewport.rotation) {
                    followFit = false
                    viewport = viewport.rotatedTo(cx, cy, target)
                    onViewportChanged?.invoke(viewport)
                    invalidate()
                }
            }
            is Grab.Pick -> {
                g.x = x
                g.y = y
                g.colour = colourAt(x, y)
                invalidate()
            }
            is Grab.Transform -> {
                val p = viewport.toDoc(Vec(x, y))
                g.affine = when (val h = g.handle) {
                    SelectionHandle.Inside -> SelectionMath.move(p.x - g.start.x, p.y - g.start.y)
                    is SelectionHandle.Corner -> SelectionMath.scaleFromCorner(g.corners, h.index, g.start, p)
                    SelectionHandle.Rotate -> SelectionMath.rotate(g.centre, g.start, p)
                    SelectionHandle.Outside -> Affine.IDENTITY
                }
                invalidate()
            }
        }
    }

    private fun grabEnd(x: Float, y: Float) {
        val g = grab ?: return
        grabMove(x, y)
        grab = null
        if (g.stylus) {
            lastPenMs = SystemClock.uptimeMillis()
            controller.up()
        }
        when (g) {
            is Grab.Hand -> {
                uiLog.i("page turned", "degrees" to viewport.rotationDegrees)
                viewportSettled()
            }
            is Grab.Pick -> {
                g.colour?.let { onColourPicked?.invoke(it) }
                invalidate()
            }
            is Grab.Transform -> {
                val sel = selection
                if (sel != null && !g.affine.isIdentity) commitTransform(sel, g.affine)
                invalidate()
            }
        }
    }

    private fun cancelGrab() {
        val g = grab ?: return
        grab = null
        if (g.stylus) controller.up()
        invalidate()
    }

    private fun startStroke(event: MotionEvent, index: Int, stylus: Boolean, purpose: Purpose) {
        val s = session ?: return
        if (stylus) {
            requestUnbufferedDispatch(event)
            lastPenMs = SystemClock.uptimeMillis()
            cancelFingerGesture()
        }
        live?.let { cancelLive() }
        val layer = s.activeLayer
        val merged = stylus && controller.swapPending
        val previewed = stylus && controller.down()
        if (layer.locked || !layer.visible) {
            onMessage?.invoke(if (layer.locked) R.string.message_layer_locked else R.string.message_layer_hidden)
        }
        val preset = tools.current
        var brush = when (purpose) {
            Purpose.Erase -> tools.eraserSpec()
            Purpose.Select -> BrushSpec.defaults(BrushKind.LassoEraser)
            Purpose.Ink -> preset.spec()
        }
        if (purpose == Purpose.Ink && layer.alphaLock && brush.blend == Blend.Normal) brush = brush.copy(blend = Blend.Atop)
        val tool = when {
            !stylus -> Tool.Finger
            purpose == Purpose.Erase -> Tool.Eraser
            else -> Tool.Pen
        }
        val l = Live(
            StrokeBuilder(brush, if (purpose == Purpose.Ink) preset.color else BLACK, s.newId()),
            brush.kind, purpose, tool, event.getPointerId(index), layer.id, previewed, merged,
            SystemClock.uptimeMillis(), NibPen.normalizer(event),
        )
        live = l
        hold.reset()
        PenRecorderStore.begin("canvas", eventNanos(event))
        PenRecorderStore.marker("viewport", "${viewport.scale},${viewport.offsetX},${viewport.offsetY},${viewport.rotation}", eventNanos(event))
        PenRecorderStore.marker("previewed", previewed.toString(), eventNanos(event))
        addSample(l, event, index, -1, PenAction.Down)
        if (!previewed || purpose != Purpose.Ink) invalidate()
    }

    private fun moveStroke(event: MotionEvent, index: Int) {
        val l = live ?: return
        if (l.tool != Tool.Finger) lastPenMs = SystemClock.uptimeMillis()
        for (h in 0 until event.historySize) addSample(l, event, index, h, PenAction.Move)
        addSample(l, event, index, -1, PenAction.Move)
        if (!l.previewed || l.purpose != Purpose.Ink) invalidate()
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
        // The pen's orientation turns with the page, so a flat nib keeps its angle to the paper.
        val sample = InputSample(viewport.toDocX(vx, vy), viewport.toDocY(vx, vy), pressure, tilt, orientation - viewport.rotation, ms * 1_000_000L)
        l.builder.add(sample)
        l.samples++
        l.lastMs = ms
        l.endX = sample.x
        l.endY = sample.y
        if (!l.lastVX.isNaN()) l.viewLength += hypot(vx - l.lastVX, vy - l.lastVY)
        l.lastVX = vx
        l.lastVY = vy
        if (pressure < l.pMin) l.pMin = pressure
        if (pressure > l.pMax) l.pMax = pressure
        PenRecorderStore.sample(action, l.tool, sample)
        if (l.purpose == Purpose.Select || l.kind == BrushKind.StrokeEraser || l.kind == BrushKind.LassoEraser) {
            l.path.add(sample.x, sample.y)
            if (l.kind == BrushKind.StrokeEraser) hitTest(l)
        }
        // A digitiser keeps reporting a resting pen, so the wait restarts only when the pen moves on.
        if (l.purpose == Purpose.Ink && settings.straightLineHold && action != PenAction.Up && !l.straight && hold.move(vx, vy, ms)) {
            removeCallbacks(holdCheck)
            postDelayed(holdCheck, StraightLine.HOLD_MS + HOLD_GRACE_MS)
        }
    }

    private fun checkHold() {
        val l = live ?: return
        if (l.purpose != Purpose.Ink || l.straight) return
        val wait = hold.remaining(SystemClock.uptimeMillis())
        if (wait > 0L) {
            postDelayed(holdCheck, wait + HOLD_GRACE_MS)
            return
        }
        if (l.viewLength < StraightLine.MIN_LENGTH_DP * density) return
        l.straight = true
        uiLog.d("stroke straightened", "points" to l.samples)
        if (!l.previewed) invalidate()
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
        removeCallbacks(holdCheck)
        var stroke = l.builder.finish()
        if (l.straight) stroke = StraightLine.straighten(stroke, l.endX, l.endY)
        val layer = s.document.layer(l.layerId)
        var committed = false
        var removed = 0
        if (l.purpose == Purpose.Select) {
            val ids = if (layer != null && l.path.points >= 3) s.history.index.insideLasso(l.layerId, l.path.data, l.path.points).map { it.id } else emptyList()
            if (layer != null && ids.isNotEmpty()) {
                var box = Box.EMPTY
                for (id in ids) layer.stroke(id)?.let { box = box.union(it.bounds) }
                setSelection(Selection(l.layerId, ids, box))
                uiLog.i("strokes selected", "count" to ids.size)
            }
        } else if (layer != null && !layer.locked && layer.visible) {
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
        if (l.tool != Tool.Finger) controller.up()
        PenRecorderStore.marker("committed", committed.toString(), eventNanos(event))
        PenRecorderStore.end()
        invalidate()
        if (l.purpose != Purpose.Select) summarize(l, stroke, committed, removed)
    }

    private fun summarize(l: Live, stroke: Stroke, committed: Boolean, removed: Int) {
        val ms = l.lastMs - l.startMs
        val hz = if (ms > 0) (l.samples - 1) * 1000f / ms else 0f
        val preview = controller.preview
        var sum = 0f
        for (i in 0 until stroke.size) sum += stroke.points.pressure(i)
        val meanP = if (stroke.size > 0) sum / stroke.size else Float.NaN
        val match = settings.previewMatch
        // The preview's width follows the owner's usual pressure; resend it when that has moved.
        if (l.tool == Tool.Pen && committed && match.observe(meanP, l.samples)) {
            removeCallbacks(previewUpdate)
            postDelayed(previewUpdate, PREVIEW_DEBOUNCE_MS)
        }
        val fields = arrayOf<Pair<String, Any?>>(
            "points" to stroke.size, "samples" to l.samples, "ms" to ms, "hz" to hz.roundToInt(),
            "p min" to round2(l.pMin), "p max" to round2(l.pMax), "tool" to l.tool.name.lowercase(),
            "brush" to l.kind.id, "width" to l.builder.brush.width, "committed" to committed, "removed" to removed,
            "previewed" to l.previewed, "merged" to l.merged, "straight" to l.straight,
            "style" to preview?.style, "preview width" to preview?.widthPx?.let { round2(it) },
            "commit ms" to round2(lastCommitMs), "zoom" to round2(viewport.scale), "turn" to viewport.rotationDegrees,
            "p mean" to round2(meanP), "typical p" to round2(match.typicalPressure), "size factor" to preview?.style?.let { round2(match.factor(it)) },
            "since open ms" to controller.msSinceOpen,
        )
        lastCommitMs = 0.0
        if (controller.holding) pendingLogs.add(fields) else log.d("stroke", *fields, "held" to false, "swap ms" to -1)
    }

    private var lastCommitMs = 0.0

    private fun cancelLive() {
        val l = live ?: return
        live = null
        removeCallbacks(holdCheck)
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
                tools.mode == ToolMode.Lasso || tools.mode == ToolMode.Eyedropper || tools.mode == ToolMode.Hand ->
                    FingerMode.Draw.also { toolDown(event, index, stylus = false) }
                settings.fingerDrawing -> FingerMode.Draw.also {
                    startStroke(event, index, stylus = false, if (tools.mode == ToolMode.Eraser) Purpose.Erase else Purpose.Ink)
                }
                settings.fingerPan -> FingerMode.Pan
                else -> FingerMode.None
            }
            focus(event)
            scaler.onTouchEvent(event)
            return
        }
        if (fingerMode == FingerMode.Ignored) return
        taps.pointerDown(event.getPointerId(index), event.getX(index), event.getY(index), count)
        if (fingerMode == FingerMode.Draw) {
            live?.let { if (it.tool == Tool.Finger) discardLive() }
            grab?.let { if (!it.stylus) cancelGrab() }
        }
        if (fingerMode != FingerMode.Pinch) {
            fingerMode = FingerMode.Pinch
            pinching = true
            frozenLevel = TileGrid.scaleBucket(viewport.scale)
            twistRaw = viewport.rotation
            controller.block(BLOCK_GESTURE)
        }
        twistLast = Float.NaN
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
                twist(event)
                panToFocus(event)
            }
            FingerMode.Pan -> panToFocus(event)
            else -> Unit
        }
    }

    /** Two fingers turning: the page turns with them, snapping to a quarter turn within 5°. */
    private fun twist(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val a = atan2(event.getY(1) - event.getY(0), event.getX(1) - event.getX(0))
        if (twistLast.isNaN()) {
            twistLast = a
            return
        }
        twistRaw = Viewport.normalize(twistRaw + Viewport.normalize(a - twistLast))
        twistLast = a
        val target = Viewport.snapToQuarter(twistRaw, SNAP_RADIANS)
        if (target != viewport.rotation) {
            val fx = (event.getX(0) + event.getX(1)) / 2f
            val fy = (event.getY(0) + event.getY(1)) / 2f
            followFit = false
            viewport = viewport.rotatedTo(fx, fy, target)
            invalidate()
        }
    }

    private fun fingerUp(event: MotionEvent, index: Int) {
        if (fingerMode == FingerMode.Ignored) {
            if (event.actionMasked == MotionEvent.ACTION_UP) fingerMode = FingerMode.None
            return
        }
        if (fingerMode == FingerMode.Pinch) {
            scaler.onTouchEvent(event)
            twistLast = Float.NaN
        }
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
        if (grab?.stylus == false) cancelGrab()
        if (fingerMode == FingerMode.Pinch) {
            pinching = false
            controller.unblock(BLOCK_GESTURE)
            viewportSettled()
        }
        fingerMode = FingerMode.Ignored
    }

    private fun discardLive() {
        live = null
        removeCallbacks(holdCheck)
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
            followFit = false
            viewport = viewport.pan(dx, dy)
            invalidate()
        }
    }

    // ---- The selection ----

    private fun setSelection(s: Selection?) {
        if (s == selection) return
        selection = s
        updateBlocks()
        invalidate()
        onSelectionChanged?.invoke(s)
    }

    /** Ends the selection, leaving its strokes where they are. */
    fun clearSelection() = setSelection(null)

    private fun commitTransform(sel: Selection, affine: Affine) {
        val s = session ?: return
        renderNextEditNow = true
        val ok = s.transformStrokes(sel.layerId, sel.ids, affine)
        renderNextEditNow = false
        if (ok) {
            setSelection(sel.transformed(affine))
            uiLog.i("selection transformed", "count" to sel.ids.size)
        } else {
            onMessage?.invoke(R.string.message_layer_locked)
        }
    }

    /** Copies of the selection, a little down and right, which become the selection. */
    fun duplicateSelection(): Boolean {
        val s = session ?: return false
        val sel = selection ?: return false
        val shift = DUPLICATE_SHIFT_DP * density / viewport.scale
        val move = Affine.translate(shift, shift)
        renderNextEditNow = true
        val ids = s.duplicateStrokes(sel.layerId, sel.ids, move)
        renderNextEditNow = false
        if (ids == null) {
            onMessage?.invoke(R.string.message_layer_locked)
            return false
        }
        setSelection(Selection(sel.layerId, ids, sel.box, sel.frame.then(move)))
        return true
    }

    fun deleteSelection(): Boolean {
        val s = session ?: return false
        val sel = selection ?: return false
        renderNextEditNow = true
        val ok = s.deleteStrokes(sel.layerId, sel.ids)
        renderNextEditNow = false
        if (ok) setSelection(null) else onMessage?.invoke(R.string.message_layer_locked)
        return ok
    }

    fun recolourSelection(argb: Int): Boolean {
        val s = session ?: return false
        val sel = selection ?: return false
        renderNextEditNow = true
        val ok = s.recolourStrokes(sel.layerId, sel.ids, argb)
        renderNextEditNow = false
        if (!ok) onMessage?.invoke(R.string.message_layer_locked)
        return ok
    }

    fun moveSelectionTo(layerId: Long): Boolean {
        val s = session ?: return false
        val sel = selection ?: return false
        renderNextEditNow = true
        val ok = s.moveStrokes(sel.layerId, layerId, sel.ids)
        renderNextEditNow = false
        if (ok) setSelection(sel.copy(layerId = layerId)) else onMessage?.invoke(R.string.message_layer_locked)
        return ok
    }

    private fun cornersInView(sel: Selection): FloatArray {
        val c = sel.corners()
        val g = grab
        if (g is Grab.Transform) g.affine.mapPoints(c, 4)
        for (i in 0 until 4) {
            val x = c[2 * i]
            val y = c[2 * i + 1]
            c[2 * i] = viewport.toViewX(x, y)
            c[2 * i + 1] = viewport.toViewY(x, y)
        }
        return c
    }

    /** The selection's corners on screen, for placing its action bar. */
    fun selectionCornersInView(): FloatArray? = selection?.let { cornersInView(it) }

    // ---- The eyedropper ----

    /** The colour of the page at a view point, as drawn (paper, guides and every visible layer), or null off the page. */
    fun colourAt(viewX: Float, viewY: Float): Int? {
        val s = session ?: return null
        val doc = s.document
        val d = viewport.toDoc(Vec(viewX, viewY))
        if (!doc.bounds.contains(d.x, d.y)) return null
        val bmp = pickBitmap ?: createBitmap(1, 1).also { pickBitmap = it }
        val c = Canvas(bmp)
        c.drawColor(s.paper.colour or BLACK)
        c.scale(viewport.scale, viewport.scale)
        c.translate(-d.x, -d.y)
        val px = 1f / viewport.scale
        val clip = Box(d.x - px, d.y - px, d.x + 2f * px, d.y + 2f * px)
        GuidesPainter.draw(c, s.paper, doc.width, doc.height, clip, 0f)
        DocumentRenderer.render(doc, pickSink.on(c), clip = clip, tolerance = 0.25f / viewport.scale, paintBackground = false)
        return bmp[0, 0] or BLACK
    }

    // ---- Edits ----

    private fun applyEdit(e: HistoryEvent) {
        val doc = e.document
        val s = session
        tiles.retainLayers(doc.layers.mapTo(HashSet()) { it.id })
        val c = e.effective
        when {
            c is AddStroke -> {
                val stored = doc.layer(c.layerId)?.strokes?.lastOrNull()
                if (stored != null && stored.id == c.stroke.id) lastCommitMs = tiles.appendStroke(c.layerId, stored) else tiles.invalidate(c.layerId, e.change.bounds)
            }
            // Tiles hold only a layer's strokes; its opacity, blend, visibility and place apply when compositing.
            c is SetLayerProps || c is MoveLayer -> Unit
            renderNextEditNow || c is TransformStrokes || c is ReplaceStrokes || c is MoveStrokes -> for (id in e.change.layers) {
                val layer = doc.layer(id) ?: continue
                tiles.renderNow(layer, e.change.bounds) { box ->
                    s?.history?.index?.query(id, box).orEmpty().sortedBy { layer.indexOf(it.id) }
                }
            }
            else -> for (id in e.change.layers) if (doc.layer(id) != null) tiles.invalidate(id, e.change.bounds)
        }
        // Undo and redo, or anything that took the picked strokes away, end the selection.
        val sel = selection
        if (sel != null && !renderNextEditNow) {
            val layer = doc.layer(sel.layerId)
            if (e.action != HistoryAction.Do || layer == null || sel.ids.any { !layer.contains(it) }) setSelection(null)
        }
        updateBlocks()
        invalidate()
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        // Clipped first: inside Compose the canvas isn't clipped to this view.
        canvas.clipRect(0, 0, width, height)
        drawDesk(canvas)
        val s = session ?: return
        val doc = s.document
        val paper = s.paper
        val flat = viewport.unrotated()
        turnMatrix.setValues(viewport.turn().toMatrixValues())

        // The page and its shadow, turned with the view, in screen pixels.
        val dw = doc.width.toFloat()
        val dh = doc.height.toFloat()
        mapCorner(0, 0f, 0f)
        mapCorner(1, dw, 0f)
        mapCorner(2, dw, dh)
        mapCorner(3, 0f, dh)
        pagePath.rewind()
        pagePath.moveTo(pageXY[0], pageXY[1])
        for (k in 1 until 4) pagePath.lineTo(pageXY[2 * k], pageXY[2 * k + 1])
        pagePath.close()
        val shadow = PAGE_SHADOW_DP * density
        canvas.save()
        canvas.translate(shadow, shadow)
        canvas.drawPath(pagePath, shadowPaint)
        canvas.restore()
        paperPaint.color = paper.colour or BLACK
        canvas.drawPath(pagePath, paperPaint)

        flatPage.set(flat.toViewX(0f, 0f), flat.toViewY(0f, 0f), flat.toViewX(dw, 0f), flat.toViewY(0f, dh))
        val level = if (pinching) frozenLevel else TileGrid.scaleBucket(viewport.scale)
        tiles.beginFrame(level)
        val visible = viewport.visibleDocRect(width.toFloat(), height.toFloat()).intersect(doc.bounds)
        val l = live
        val g = grab
        val sel = selection
        val moving = if (g is Grab.Transform && sel != null && !g.affine.isIdentity) g.affine else null
        if (!visible.isEmpty) {
            val range = grid.range(visible, level)
            canvas.save()
            canvas.concat(turnMatrix)
            canvas.clipRect(flatPage)
            docMatrix.setValues(flat.docToView().toMatrixValues())
            canvas.save()
            canvas.concat(docMatrix)
            GuidesPainter.draw(canvas, paper, doc.width, doc.height, visible, 0f)
            canvas.restore()
            for (layer in doc.layers) {
                if (!layer.visible || layer.opacity <= 0f) continue
                val liveHere = l != null && layer.id == l.layerId && showsLive(l)
                val movingHere = moving != null && sel != null && layer.id == sel.layerId
                val isolate = layer.opacity < 1f || layer.blend != Blend.Normal || liveHere || movingHere
                if (isolate) {
                    layerPaint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255f).roundToInt()
                    layerPaint.blendMode = layer.blend.toBlendMode()
                    canvas.saveLayer(flatPage, layerPaint)
                }
                tiles.drawLayer(canvas, layer, flat, range) { box -> s.history.index.query(layer.id, box).isNotEmpty() }
                if (liveHere) drawLive(canvas, l!!, flat)
                if (movingHere) drawMoving(canvas, layer, sel!!, moving!!, flat)
                if (isolate) canvas.restore()
            }
            canvas.restore()
        }
        canvas.drawPath(pagePath, rimPaint)
        if (l != null) drawMarks(canvas, l)
        if (sel != null) drawSelection(canvas, sel)
        if (g is Grab.Pick) drawLoupe(canvas, g)
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

    private fun mapCorner(k: Int, x: Float, y: Float) {
        pageXY[2 * k] = viewport.toViewX(x, y)
        pageXY[2 * k + 1] = viewport.toViewY(x, y)
    }

    /** The desk: flat grey with a faint cutting-mat grid, fixed to the screen while the page moves on it. */
    private fun drawDesk(canvas: Canvas) {
        canvas.drawColor(DESK)
        val cell = DESK_CELL_DP * density
        val minor = deskLines ?: gridLines(cell, major = false).also { deskLines = it }
        val major = deskMajorLines ?: gridLines(cell, major = true).also { deskMajorLines = it }
        canvas.drawLines(minor, deskGrid)
        canvas.drawLines(major, deskMajor)
    }

    private fun gridLines(cell: Float, major: Boolean): FloatArray {
        val out = ArrayList<Float>()
        val cols = (width / cell).toInt() + 1
        val rows = (height / cell).toInt() + 1
        for (i in 1..cols) if ((i % DESK_MAJOR_EVERY == 0) == major) {
            val x = (i * cell).roundToInt() + 0.5f
            out += listOf(x, 0f, x, height.toFloat())
        }
        for (j in 1..rows) if ((j % DESK_MAJOR_EVERY == 0) == major) {
            val y = (j * cell).roundToInt() + 0.5f
            out += listOf(0f, y, width.toFloat(), y)
        }
        return out.toFloatArray()
    }

    private fun showsLive(l: Live): Boolean = when {
        l.purpose == Purpose.Select || l.kind == BrushKind.LassoEraser -> false
        l.kind == BrushKind.StrokeEraser -> l.erasePreview.isNotEmpty()
        else -> !l.previewed || l.tool == Tool.Eraser
    }

    private fun drawLive(canvas: Canvas, l: Live, flat: Viewport) = canvas.withSave {
        docMatrix.setValues(flat.docToView().toMatrixValues())
        concat(docMatrix)
        val sink = viewSink.on(this)
        val tolerance = 0.25f / viewport.scale
        if (l.kind == BrushKind.StrokeEraser) {
            for (st in l.erasePreview) StrokeRenderer.render(st, sink, tolerance)
        } else if (!l.builder.isEmpty) {
            val current = l.builder.current()
            StrokeRenderer.render(if (l.straight) StraightLine.straighten(current, l.endX, l.endY) else current, sink, tolerance)
        }
    }

    /** A selection being dragged: its strokes lifted out of the layer and drawn where they're going. */
    private fun drawMoving(canvas: Canvas, layer: Layer, sel: Selection, affine: Affine, flat: Viewport) = canvas.withSave {
        docMatrix.setValues(flat.docToView().toMatrixValues())
        concat(docMatrix)
        val sink = viewSink.on(this)
        val tolerance = 0.25f / viewport.scale
        val strokes = sel.ids.mapNotNull { layer.stroke(it) }.sortedBy { layer.indexOf(it.id) }
        for (st in strokes) StrokeRenderer.render(st.copy(brush = st.brush.copy(blend = Blend.Erase, opacity = 1f)), sink, tolerance)
        for (st in strokes) StrokeRenderer.render(st.transformed(affine), sink, tolerance)
    }

    private fun drawMarks(canvas: Canvas, l: Live) {
        val n = l.path.points
        if (n == 0) return
        val xy = l.path.data
        if (l.purpose == Purpose.Select || l.kind == BrushKind.LassoEraser) {
            lassoPath.rewind()
            lassoPath.moveTo(viewport.toViewX(xy[0], xy[1]), viewport.toViewY(xy[0], xy[1]))
            for (i in 1 until n) lassoPath.lineTo(viewport.toViewX(xy[2 * i], xy[2 * i + 1]), viewport.toViewY(xy[2 * i], xy[2 * i + 1]))
            if (n >= 3) lassoPath.close()
            canvas.drawPath(lassoPath, lassoPaint)
        } else if (l.kind == BrushKind.StrokeEraser) {
            val r = max(2f * density, l.builder.brush.width / 2f * viewport.scale)
            val x = xy[2 * n - 2]
            val y = xy[2 * n - 1]
            canvas.drawCircle(viewport.toViewX(x, y), viewport.toViewY(x, y), r, cursorPaint)
        }
    }

    /** The selection's frame, dashed, with square corner handles and a round turn handle above it. */
    private fun drawSelection(canvas: Canvas, sel: Selection) {
        val c = cornersInView(sel)
        framePath.rewind()
        framePath.moveTo(c[0], c[1])
        for (k in 1 until 4) framePath.lineTo(c[2 * k], c[2 * k + 1])
        framePath.close()
        canvas.drawPath(framePath, framePaint)
        val rot = SelectionMath.rotateHandle(c, ROTATE_LIFT_DP * density)
        val mx = (c[0] + c[2]) / 2f
        val my = (c[1] + c[3]) / 2f
        canvas.drawLine(mx, my, rot.x, rot.y, handleInk)
        val h = HANDLE_DP * density
        val shadow = 3f * density
        for (k in 0 until 4) {
            val x = c[2 * k]
            val y = c[2 * k + 1]
            canvas.drawRect(x - h + shadow, y - h + shadow, x + h + shadow, y + h + shadow, solidInk)
            canvas.drawRect(x - h, y - h, x + h, y + h, handleFill)
            canvas.drawRect(x - h, y - h, x + h, y + h, handleInk)
        }
        val r = h * 1.15f
        canvas.drawCircle(rot.x + shadow, rot.y + shadow, r, solidInk)
        canvas.drawCircle(rot.x, rot.y, r, handleFill)
        canvas.drawCircle(rot.x, rot.y, r, handleInk)
        canvas.drawCircle(rot.x, rot.y, r * 0.35f, solidInk)
    }

    /** The eyedropper's loupe: the colour under the pen in a ringed disc, clear of the pen tip. */
    private fun drawLoupe(canvas: Canvas, g: Grab.Pick) {
        val colour = g.colour ?: return
        val r = LOUPE_DP * density
        val x = min(width - r - 4f * density, max(r + 4f * density, g.x - r * 1.4f))
        val y = max(r + 4f * density, g.y - r * 1.6f)
        canvas.drawCircle(x + 4f * density, y + 4f * density, r, solidInk)
        loupePaint.color = colour
        canvas.drawCircle(x, y, r, loupePaint)
        canvas.drawCircle(x, y, r, handleInk)
        canvas.drawCircle(g.x, g.y, 5f * density, handleInk)
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
        private const val HOLD_GRACE_MS = 20L
        private const val BLACK = -0x1000000
        private const val WHITE = -0x1

        /** The desk: a flat grey from the panel's own sixteen, so it dithers to nothing. */
        const val DESK = 0xFFDDDDDD.toInt()
        private const val DESK_GRID = 0xFFD0D0D0.toInt()
        private const val DESK_GRID_MAJOR = 0xFFBBBBBB.toInt()
        private const val DESK_CELL_DP = 24f
        private const val DESK_MAJOR_EVERY = 5
        private const val PAGE_SHADOW_DP = 6f
        private const val HANDLE_DP = 9f
        private const val HANDLE_TOUCH_DP = 28f
        private const val ROTATE_LIFT_DP = 44f
        private const val DUPLICATE_SHIFT_DP = 24f
        private const val HAND_MIN_RADIUS_DP = 40f
        private const val LOUPE_DP = 30f
        private val SNAP_RADIANS = Math.toRadians(5.0).toFloat()
        private const val BLOCK_PANEL = "panel"
        private const val BLOCK_TOOL = "tool"
        private const val BLOCK_SELECTION = "selection"
        private const val BLOCK_LAYER = "layer"
        private const val BLOCK_GESTURE = "gesture"
        private const val BLOCK_FOCUS = "focus"
    }
}
