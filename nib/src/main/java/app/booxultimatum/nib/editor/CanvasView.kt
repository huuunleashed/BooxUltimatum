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
import android.graphics.Rect
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
import app.booxultimatum.kit.ink.canvas.InkCanvasController
import app.booxultimatum.kit.ink.eink.Eink
import app.booxultimatum.kit.ink.epd.HandwritingLayer
import app.booxultimatum.kit.ink.epd.UpdateMode
import app.booxultimatum.kit.ink.input.PalmGuard
import app.booxultimatum.kit.ink.session.InkSession
import app.booxultimatum.kit.ink.session.InkStroke
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.nib.NibSettings
import app.booxultimatum.nib.R
import app.booxultimatum.nib.brush.PreviewPolicy
import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.brush.PenButtons
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
import app.booxultimatum.nib.pen.PenPart
import app.booxultimatum.nib.pen.PenParts
import app.booxultimatum.nib.pen.PenRecorderStore
import app.booxultimatum.nib.pen.PenRouter
import app.booxultimatum.nib.pen.PenShields
import app.booxultimatum.nib.pen.PressureNormalizer
import app.booxultimatum.nib.pen.RevealPolicy
import app.booxultimatum.nib.pen.ScreenAreas
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
 *   view's zoom, pan and turn. With the display's pen session ([NibPen]) the firmware previews each stroke itself and
 *   holds this app's frames for the whole writing session, as BOOX's own note app does ([InkCanvasController]): every
 *   stroke is committed and drawn into the tiles behind the hold, and Nib's own pixels replace the preview at a break
 *   (a control pressed, a panel, a gesture, undo, a tool change), or after a pause when the pen's ink doesn't preview
 *   faithfully, by pushing the exact ink into the display's layer ([RevealPolicy]). Without a session the stroke is
 *   drawn live, every move. A stroke whose pen rests at its end becomes a straight line ([StraightLine]).
 * - **Controls.** Every floating control's area ([PenShields]) and the screen around the canvas are the preview's
 *   exclusions, all at once. While frames are held, controls whose content changed are pushed into the display's
 *   layer.
 * - **Tools.** The eraser end, the side button or the eraser tool erase; the lasso picks strokes, its path previewed
 *   by the display in the dashed style, and then moves, scales and turns them; the eyedropper takes a colour from the
 *   page; the hand turns the page. The eraser tool, the eyedropper and the hand pause the preview.
 * - **Fingers.** Ignored while the pen is near, and with Palm guard the firmware ignores them too. Two fingers pinch,
 *   pan and twist (snapping to quarter turns) in the display's fast mode, two- and three-finger taps undo and redo, one
 *   finger pans or draws depending on the settings.
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

    /** The display's writing choreography, on the session shared with the probes through [NibPen]. */
    val controller = NibPen.controller()

    /** Finger touch off over the canvas while the pen is near (Settings › Fingers › Palm guard). */
    private val palm = PalmGuard(NibPen.mainScheduler)

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
        set(value) {
            if (field === value) return
            field?.let { it.onAreasChanged = null; it.onContentChanged = null }
            field = value
            value?.onAreasChanged = { controlsMoved() }
            value?.onContentChanged = { scheduleControlsPush() }
            controlsMoved()
        }

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
            controller.releaseNow("drawing")
            field = value
            tiles.clear()
            fitted = false
            setSelection(null)
            value?.addEditListener(onEdit)
            value?.addPaperListener(onPaper)
            if (value != null && width > 0) restoreOrFit()
            lastLayerId = NO_LAYER
            updateBlocks()
            updateSessionOpen()
            if (value != null) refreshAfterDraw = "drawing opened"
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
        val hold: Int,
        val startMs: Long,
        val normalizer: PressureNormalizer,
        val previewWidthPx: Float,
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
        var minVX = Float.POSITIVE_INFINITY
        var minVY = Float.POSITIVE_INFINITY
        var maxVX = Float.NEGATIVE_INFINITY
        var maxVY = Float.NEGATIVE_INFINITY
        var straight = false
        val path = FloatList()
        val hits = LinkedHashSet<Long>()
        var erasePreview: List<Stroke> = emptyList()
    }

    private var live: Live? = null

    // Each finished stroke's summary waits for the end of its hold (or for its ink to be pushed), so the log says how
    // it was revealed.
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

    // How far the fingers have moved the page in this gesture, and whether the display was told it's a gesture yet.
    private var gestureTravel = 0f
    private var gestureOn = false
    private val taps = MultiTapDetector(slopPx = 12f * resources.displayMetrics.density)
    private val scaler = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (fingerMode != FingerMode.Pinch) return false
                followFit = false
                viewport = viewport.zoomAround(d.focusX, d.focusY, d.scaleFactor)
                gestureMoved(kotlin.math.abs(d.currentSpan - d.previousSpan))
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

    private val previewUpdate = Runnable { applyStroke() }

    private val onEdit: (HistoryEvent) -> Unit = { e -> applyEdit(e) }
    private val onPaper: () -> Unit = {
        controller.releaseNow("paper")
        invalidate()
    }

    // The session's hold, as this canvas sees it: which one, since when, and what went on in it.
    private var holdIndex = 0
    private var holdStartMs = 0L
    private var holdPushes = 0

    // Lifts whose frame the controller waits for, and the last one reported (see onDraw).
    private var upSeq = 0
    private var reportedSeq = 0

    // Drawing this view into a bitmap for the display's layer, not onto the screen.
    private var capturing = false

    // What the display was last told, so it's told again only when something changed.
    private var sentParts: List<PenPart>? = null
    private var sentPartsRect: IntArray? = null
    private var partsPending = false
    private var controlsCache: List<IntArray>? = null
    private var controlsPending = false
    private var controlsPosted = false
    private var controlsPushPosted = false
    private var toolSignature: Any? = null
    private var lastLayerId = NO_LAYER
    private var panelOpen = false
    private var lastPolicy: String? = null

    // A Regal refresh of the canvas once a drawing opens or a panel closes, as the native apps do for page changes.
    private var refreshAfterDraw: String? = null
    private var pageRefreshReason: String? = null
    private var lastPageRefreshMs = Long.MIN_VALUE / 2
    private val pageRefresh = Runnable { refreshPage() }

    private val controlsApply = Runnable {
        controlsPosted = false
        applyControls()
    }

    private val controlsPush = Runnable {
        controlsPushPosted = false
        val s = shields ?: return@Runnable
        when {
            !s.hasChanged -> Unit
            // Frames reach the panel, so the controls show themselves.
            !controller.holding -> s.takeChanged()
            // After the lift: the frame report asks again.
            controller.touching -> Unit
            else -> controller.controlsChanged()
        }
    }

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
        controller.host = object : InkCanvasController.Host {
            override fun pushInk(dirty: IntArray): Boolean = pushInkArea(dirty)
            override fun pushControls(): Boolean = pushChangedControls()
            override fun fastMode(on: Boolean) {
                if (!controller.available) return
                val ok = Eink.fastMode(on)
                log.i("fast mode", "on" to on, "ok" to ok)
            }
        }
        controller.onReleased = { reason, strokes -> holdEnded(reason, strokes) }
        controller.rearmMs = NibPen.rearmMs(context)
        palm.onChanged = { on -> log.i("palm guard", "on" to on) }
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

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // The canvas may move on screen without changing size: the screen around it is out of the preview too.
        if (changed) controlsMoved()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        deskLines = null
        deskMajorLines = null
        // The tablet turned: held frames would hide the page as it's laid out again.
        controller.releaseNow("layout")
        controlsMoved()
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

    /** Tools, brush, colour or settings changed: update the preview stroke, the reveal and what blocks the preview. */
    fun onToolsChanged() {
        if (tools.mode != ToolMode.Lasso && selection != null) setSelection(null)
        val signature = listOf(tools.mode, tools.selected, tools.current, tools.eraser, tools.eraserWidth(tools.eraser))
        if (signature != toolSignature) {
            // A pen, colour, size or tool chosen from a panel is a break: Nib's own ink goes on screen.
            if (toolSignature != null) controller.releaseNow("tools")
            toolSignature = signature
        }
        updateBlocks()
        refreshPreview()
    }

    private fun refreshPreview() {
        removeCallbacks(previewUpdate)
        postDelayed(previewUpdate, PREVIEW_DEBOUNCE_MS)
    }

    /** What the display previews for the tool in hand: the pen's own stroke, or the lasso's dashed path. */
    private fun currentStroke(): InkStroke {
        if (tools.mode == ToolMode.Lasso) return InkStroke.lasso(LASSO_PREVIEW_PX)
        val p = tools.current
        return PreviewPolicy.preview(
            p.spec(), p.color, viewport.scale, settings.tryUnverifiedStyles, settings.previewMatch, settings.markerPreview, viewport.rotation,
        )
    }

    /** Sends the stroke and the pen's parts; the controller keeps a stroke under way as it is until the lift. */
    private fun applyStroke() {
        val s = currentStroke()
        controller.setStroke(s)
        sendPenParts(s)
    }

    /** The tip previews the pen in hand; the eraser end an eraser track, when Diagnostics lets the display preview it. */
    private fun wantedParts(stroke: InkStroke): List<PenPart> =
        if (settings.eraserEndPreview) PenParts.both(stroke, PenButtons.eraserEnd(tools.eraserSpec(), viewport.scale)) else emptyList()

    private fun sendPenParts(stroke: InkStroke) {
        if (!controller.available || width == 0) return
        val want = wantedParts(stroke)
        val rect = screenRect()
        if (want == sentParts && rect.contentEquals(sentPartsRect)) return
        if (controller.touching) {
            partsPending = true
            return
        }
        partsPending = false
        controller.session.setPenButtons(rect, want.map { it.toButton() })
        if (want.isNotEmpty() || !sentParts.isNullOrEmpty()) log.i("pen parts", "parts" to want.joinToString("; ").ifEmpty { "none" }, "rect" to rect.joinToString(","))
        sentParts = want
        sentPartsRect = rect
    }

    /**
     * Something over the canvas that a touch on the canvas closes (an unpinned panel, the menu, a text entry) opened
     * or closed. While one is open the display doesn't preview: the pen's next touch closes it instead of drawing.
     * Once it's gone, the canvas gets one Regal refresh to clear its ghost.
     */
    fun setPanelOpen(open: Boolean) {
        if (open) controller.block(BLOCK_PANEL) else controller.unblock(BLOCK_PANEL)
        if (panelOpen && !open) schedulePageRefresh("panel closed")
        panelOpen = open
    }

    /** A break the editor decides (an export, a share, leaving for the library): Nib's own ink goes on screen. */
    fun release(reason: String) = controller.releaseNow(reason)

    private fun updateBlocks() {
        if (tools.mode.quiet) controller.block(BLOCK_TOOL) else controller.unblock(BLOCK_TOOL)
        if (selection != null) controller.block(BLOCK_SELECTION) else controller.unblock(BLOCK_SELECTION)
        val layer = session?.activeLayer
        if (layer != null && (layer.locked || !layer.visible)) controller.block(BLOCK_LAYER) else controller.unblock(BLOCK_LAYER)
        if (layer != null && layer.id != lastLayerId) {
            if (lastLayerId != NO_LAYER) controller.releaseNow("layer")
            lastLayerId = layer.id
        }
        applyPolicy()
    }

    /** The reveal for the pen in hand (Settings › Display preview), the fast mode switch and Palm guard. */
    private fun applyPolicy() {
        val p = tools.current
        val spec = p.spec()
        val layer = session?.activeLayer
        val look = RevealPolicy.LayerLook(layer?.opacity ?: 1f, layer?.blend ?: Blend.Normal, layer?.alphaLock == true)
        val reveal = if (tools.mode == ToolMode.Lasso) {
            InkCanvasController.Reveal.AtBreaks
        } else {
            RevealPolicy.effective(settings.reveal, spec, p.color, look, !settings.tryUnverifiedStyles, settings.markerPreview, viewport.scale)
        }
        controller.reveal = reveal
        controller.pauseMs = settings.revealPauseMs.toLong()
        controller.fastGestures = settings.fastGestures
        // Finger touch control is BOOX's own; it's only tried where the display's pen path is there too.
        val guard = settings.palmGuard && controller.available
        if (palm.enabled != guard) {
            palm.enabled = guard
            log.i("palm guard setting", "on" to guard, "asked" to settings.palmGuard)
        }
        val policy = "${settings.reveal.id}/${reveal.name}/${if (tools.mode == ToolMode.Lasso) "lasso" else spec.kind.id}/${controller.pauseMs}"
        if (policy != lastPolicy) {
            lastPolicy = policy
            log.i(
                "preview policy", "setting" to settings.reveal.id, "reveal" to reveal.name, "pause ms" to controller.pauseMs,
                "brush" to if (tools.mode == ToolMode.Lasso) "lasso" else spec.kind.id, "fast gestures" to settings.fastGestures,
            )
        }
    }

    private fun updateSessionOpen() {
        val want = isAttachedToWindow && resumed && hasWindowFocus() && session != null
        if (want) {
            if (controller.session.state == InkSession.State.Closed) {
                val stroke = currentStroke()
                // The session is shared with the probes: what it re-arms with must be this canvas's.
                sentParts = null
                sentPartsRect = null
                if (width > 0) {
                    val parts = wantedParts(stroke)
                    val rect = screenRect()
                    controller.session.setPenButtons(rect, parts.map { it.toButton() })
                    sentParts = parts
                    sentPartsRect = rect
                } else {
                    controller.session.setPenButtons(IntArray(4), emptyList())
                }
                controller.rearmMs = NibPen.rearmMs(context)
                val ok = controller.open(stroke, NibPen.panelLongSide(context))
                // Clears a configuration an ended process may have left, when this canvas sends none.
                if (sentParts?.isEmpty() == true && controller.available) sentPartsRect?.let { controller.session.setPenButtons(it, emptyList()) }
                controlsCache = null
                applyControls()
                updateBlocks()
                log.i("canvas session", "opened" to ok, "state" to controller.session.state.name, "controls" to controlAreas().size, "parts" to sentParts?.size)
            }
            PenRouter.attach(this)
        } else if (!resumed || !isAttachedToWindow) {
            closeSession()
            PenRouter.detach(this)
        }
    }

    private fun closeSession() {
        palm.stop()
        if (controller.session.state != InkSession.State.Closed) {
            val holding = controller.holding
            val strokes = controller.session.strokesInHold
            controller.close()
            if (holding) holdEnded("close", strokes)
        }
        flushStrokeLogs("close")
    }

    /** Clears any stuck preview and held frames (menu › Recover screen). Returns whether a session exists. */
    fun recoverScreen(): Boolean {
        cancelLive()
        controller.recover()
        sentParts = null
        applyStroke()
        invalidate()
        uiLog.i("recover screen", "state" to controller.session.state.name)
        return controller.available
    }

    /** One full clean of the panel (menu › Refresh screen), as the native apps' "refresh page". Returns whether it ran. */
    fun cleanScreen(): Boolean {
        controller.releaseNow("refresh screen")
        val ok = Eink.cleanScreen()
        uiLog.i("refresh screen", "ok" to ok)
        return ok
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val owner = findViewTreeLifecycleOwner()
        resumed = owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true
        owner?.lifecycle?.addObserver(lifecycleObserver)
        updateSessionOpen()
        // The session is shared: a screen leaving in the same pass (Diagnostics, say) closes it after this attach.
        post { updateSessionOpen() }
    }

    override fun onDetachedFromWindow() {
        findViewTreeLifecycleOwner()?.lifecycle?.removeObserver(lifecycleObserver)
        cancelLive()
        cancelGrab()
        removeCallbacks(previewUpdate)
        removeCallbacks(holdCheck)
        removeCallbacks(pageRefresh)
        removeCallbacks(controlsApply)
        closeSession()
        PenRouter.detach(this)
        tiles.stop()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            controller.unblock(BLOCK_FOCUS)
        } else {
            controller.block(BLOCK_FOCUS)
            palm.stop()
        }
        updateSessionOpen()
    }

    override fun onWindowHover(event: MotionEvent) {
        lastPenMs = SystemClock.uptimeMillis()
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            hovering = false
            palm.penAway()
            return
        }
        val x = event.rawX
        val y = event.rawY
        val inside = containsScreenPoint(x, y)
        hovering = inside
        palmNear()
        val target = when {
            inside -> InkCanvasController.Target.Canvas
            onView(x, y) -> InkCanvasController.Target.Controls
            else -> InkCanvasController.Target.Elsewhere
        }
        // A block, not the hover's pause: a pen touch resumes a session that's only paused.
        if (pausesForEraser(event)) controller.block(BLOCK_ERASER) else controller.unblock(BLOCK_ERASER)
        controller.hover(target)
    }

    /** The side button always stops the preview; the eraser end only when the display isn't set to preview it. */
    private fun pausesForEraser(event: MotionEvent, index: Int = 0): Boolean =
        NibPen.isSideButton(event) || (NibPen.isEraserEnd(event, index) && !settings.eraserEndPreview)

    override fun onControlsTouched(x: Float, y: Float, stylus: Boolean) {
        controller.controlsTouched()
    }

    override fun onControlsTouchEnded() {
        controller.controlsTouchEnded()
    }

    private val location = IntArray(2)
    private val rootLocation = IntArray(2)

    /** This view's rectangle on screen: left, top, right, bottom. */
    private fun screenRect(): IntArray {
        getLocationOnScreen(location)
        return intArrayOf(location[0], location[1], location[0] + width, location[1] + height)
    }

    private fun onView(x: Float, y: Float): Boolean {
        getLocationOnScreen(location)
        return x >= location[0] && y >= location[1] && x < location[0] + width && y < location[1] + height
    }

    /** On the canvas and not on one of the floating controls over it. */
    override fun containsScreenPoint(x: Float, y: Float): Boolean {
        if (!isShown || !onView(x, y)) return false
        val s = shields ?: return true
        rootView.getLocationOnScreen(rootLocation)
        return !s.covers(x - rootLocation[0], y - rootLocation[1])
    }

    /** Every control's area on screen, and the screen around the canvas: all of it stays out of the preview. */
    private fun controlAreas(): List<IntArray> {
        controlsCache?.let { return it }
        rootView.getLocationOnScreen(rootLocation)
        val out = ArrayList<IntArray>()
        shields?.let { out += it.areas(rootLocation[0], rootLocation[1]) }
        if (width > 0) {
            val root = intArrayOf(rootLocation[0], rootLocation[1], rootLocation[0] + rootView.width, rootLocation[1] + rootView.height)
            out += ScreenAreas.around(screenRect(), root)
        }
        controlsCache = out
        return out
    }

    /** A control appeared, moved or went, or the canvas did: the display is told once the layout settles. */
    private fun controlsMoved() {
        controlsCache = null
        if (controlsPosted) return
        controlsPosted = true
        post(controlsApply)
    }

    private fun applyControls() {
        if (!controller.available) return
        if (controller.touching) {
            controlsPending = true
            return
        }
        controlsPending = false
        controller.setControls(controlAreas())
        if (palm.active) palmNear()
    }

    private fun palmNear() {
        if (!palm.enabled || width == 0) return
        palm.penNear(screenRect(), controlAreas())
    }

    /** A control's content changed; once its new look is drawn, it's pushed to the display if frames are held. */
    private fun scheduleControlsPush() {
        if (controlsPushPosted) return
        controlsPushPosted = true
        Choreographer.getInstance().postFrameCallback { post(controlsPush) }
    }

    /** Pushes the controls that changed, from the window's own drawing, into the display's layer. */
    private fun pushChangedControls(): Boolean {
        val s = shields ?: return true
        val areas = s.takeChanged()
        if (areas.isEmpty()) return true
        val root = rootView
        val t0 = SystemClock.uptimeMillis()
        var ok = true
        var px = 0L
        capturing = true
        try {
            for (a in areas) {
                px += ScreenAreas.pixels(a)
                ok = HandwritingLayer.pushArea(root, Rect(a[0], a[1], a[2], a[3])) && ok
            }
        } finally {
            capturing = false
        }
        log.d("controls pushed", "count" to areas.size, "px" to px, "ms" to SystemClock.uptimeMillis() - t0, "ok" to ok, "hold" to holdIndex)
        return ok
    }

    /**
     * Pushes the page inside [dirty] (screen coordinates) into the display's layer, drawn from the window so any control
     * over it stays on top. Refused above [MAX_PUSH_BYTES], which the controller answers by letting the frames through.
     */
    private fun pushInkArea(dirty: IntArray): Boolean {
        val root = rootView
        rootView.getLocationOnScreen(rootLocation)
        val r = Rect(dirty[0] - rootLocation[0], dirty[1] - rootLocation[1], dirty[2] - rootLocation[0], dirty[3] - rootLocation[1])
        if (!r.intersect(0, 0, root.width, root.height)) return false
        val bytes = r.width().toLong() * r.height() * 4L
        if (bytes > MAX_PUSH_BYTES) {
            log.d("ink push refused", "bytes" to bytes, "hold" to holdIndex)
            return false
        }
        val t0 = SystemClock.uptimeMillis()
        capturing = true
        val ok = try { HandwritingLayer.pushArea(root, r) } finally { capturing = false }
        log.d("ink pushed", "w" to r.width(), "h" to r.height(), "ms" to SystemClock.uptimeMillis() - t0, "ok" to ok, "hold" to holdIndex)
        if (ok) {
            holdPushes++
            flushStrokeLogs("push")
        }
        return ok
    }

    /** A hold ended: [strokes] of the owner's were shown by Nib's own frames. */
    private fun holdEnded(reason: String, strokes: Int) {
        log.i("hold", "index" to holdIndex, "strokes" to strokes, "ms" to SystemClock.uptimeMillis() - holdStartMs, "reason" to reason, "pushes" to holdPushes, "reveal" to controller.reveal.name)
        flushStrokeLogs(reason)
        if (shields?.hasChanged == true) shields?.takeChanged()
    }

    private fun flushStrokeLogs(revealed: String) {
        for (f in pendingLogs) log.d("stroke", *f, "revealed" to revealed)
        pendingLogs.clear()
    }

    /** One Regal refresh of the canvas, soon, unless one ran lately or frames are held. */
    private fun schedulePageRefresh(reason: String) {
        pageRefreshReason = reason
        removeCallbacks(pageRefresh)
        postDelayed(pageRefresh, PAGE_REFRESH_DELAY_MS)
    }

    private fun refreshPage() {
        val reason = pageRefreshReason ?: return
        pageRefreshReason = null
        val now = SystemClock.uptimeMillis()
        if (!controller.available || controller.holding || controller.touching || live != null || now - lastPageRefreshMs < PAGE_REFRESH_GAP_MS) return
        lastPageRefreshMs = now
        val ok = Eink.refresh(this, UpdateMode.Regal)
        log.d("page refreshed", "reason" to reason, "ok" to ok)
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
        // The pen lifted: finger touch comes back shortly unless it hovers on (Palm guard).
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && NibPen.isStylus(event.getToolType(i))) palm.penAway()
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
        // Android sends a hover exit before every touch; the pen is as near as it gets.
        palmNear()
        // The eraser end or side button may touch without a hover first.
        if (pausesForEraser(event, i)) controller.block(BLOCK_ERASER)
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
            penLifted(null)
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
        if (g.stylus) penLifted(null)
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
        val wasHolding = controller.holding
        val previewed = stylus && controller.down()
        if (previewed && !wasHolding && controller.holding) {
            holdIndex++
            holdStartMs = SystemClock.uptimeMillis()
            holdPushes = 0
        }
        // A finger's stroke isn't previewed by the display, so held frames would hide it.
        if (!stylus) controller.releaseNow("finger")
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
            StrokeBuilder(brush, if (purpose == Purpose.Ink) preset.color else BLACK, s.newId(), viewScale = viewport.scale),
            brush.kind, purpose, tool, event.getPointerId(index), layer.id, previewed, if (previewed && controller.holding) holdIndex else 0,
            SystemClock.uptimeMillis(), NibPen.normalizer(event), controller.stroke?.widthPx ?: 0f,
        )
        live = l
        hold.reset()
        PenRecorderStore.begin("canvas", eventNanos(event))
        PenRecorderStore.marker("viewport", "${viewport.scale},${viewport.offsetX},${viewport.offsetY},${viewport.rotation}", eventNanos(event))
        PenRecorderStore.marker("previewed", previewed.toString(), eventNanos(event))
        addSample(l, event, index, -1, PenAction.Down)
        // While the display previews, frames are held: nothing drawn now would reach the panel.
        if (!previewed) invalidate()
    }

    private fun moveStroke(event: MotionEvent, index: Int) {
        val l = live ?: return
        if (l.tool != Tool.Finger) lastPenMs = SystemClock.uptimeMillis()
        for (h in 0 until event.historySize) addSample(l, event, index, h, PenAction.Move)
        addSample(l, event, index, -1, PenAction.Move)
        if (!l.previewed) invalidate()
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
        if (vx < l.minVX) l.minVX = vx
        if (vx > l.maxVX) l.maxVX = vx
        if (vy < l.minVY) l.minVY = vy
        if (vy > l.maxVY) l.maxVY = vy
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
        // The lift is reported before the commit, so any release the commit leads to waits for the frame that shows it.
        if (l.tool != Tool.Finger) penLifted(if (l.previewed) strokeScreenBounds(l, stroke) else null)
        if (l.previewed) {
            // The display drew the lasso's path or the eraser end's track; neither is ink, so they go once the frame
            // with the result is drawn.
            if (l.purpose == Purpose.Select) controller.releaseNow("lasso") else if (l.tool == Tool.Eraser) controller.releaseNow("eraser end")
        }
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
        PenRecorderStore.marker("committed", committed.toString(), eventNanos(event))
        PenRecorderStore.end()
        invalidate()
        if (l.purpose != Purpose.Select) summarize(l, stroke, committed, removed)
    }

    /** The pen lifted from a stroke; [bounds] is where its preview and its ink lie on screen, if it was previewed. */
    private fun penLifted(bounds: IntArray?) {
        val held = controller.holding
        controller.up(bounds)
        // The controller now waits for the frame with this stroke (see onDraw).
        if (held) upSeq++
        if (controlsPending) applyControls()
        if (partsPending) sendPenParts(currentStroke())
        if (shields?.hasChanged == true) scheduleControlsPush()
    }

    /** Where a stroke's preview (the path as drawn, at the preview's width) and its final ink lie, on screen. */
    private fun strokeScreenBounds(l: Live, stroke: Stroke): IntArray? {
        if (l.minVX > l.maxVX) return null
        val pad = l.previewWidthPx / 2f + BOUNDS_PAD_PX
        var left = l.minVX - pad
        var top = l.minVY - pad
        var right = l.maxVX + pad
        var bottom = l.maxVY + pad
        if (l.kind.isRendered && stroke.size > 0) {
            val b = stroke.bounds
            for (k in 0 until 4) {
                val x = if (k == 0 || k == 3) b.left else b.right
                val y = if (k < 2) b.top else b.bottom
                val vx = viewport.toViewX(x, y)
                val vy = viewport.toViewY(x, y)
                left = min(left, vx); right = max(right, vx); top = min(top, vy); bottom = max(bottom, vy)
            }
        }
        getLocationOnScreen(location)
        return intArrayOf(
            kotlin.math.floor(left).toInt() + location[0], kotlin.math.floor(top).toInt() + location[1],
            kotlin.math.ceil(right).toInt() + location[0], kotlin.math.ceil(bottom).toInt() + location[1],
        )
    }

    private fun summarize(l: Live, stroke: Stroke, committed: Boolean, removed: Int) {
        val ms = l.lastMs - l.startMs
        val hz = if (ms > 0) (l.samples - 1) * 1000f / ms else 0f
        val preview = controller.stroke
        var sum = 0f
        for (i in 0 until stroke.size) sum += stroke.points.pressure(i)
        val meanP = if (stroke.size > 0) sum / stroke.size else Float.NaN
        val match = settings.previewMatch
        // Constant-width previews follow the owner's usual pressure; resend when that has moved.
        if (l.tool == Tool.Pen && committed && match.observe(meanP, l.samples)) {
            removeCallbacks(previewUpdate)
            postDelayed(previewUpdate, PREVIEW_DEBOUNCE_MS)
        }
        val fields = arrayOf<Pair<String, Any?>>(
            "points" to stroke.size, "samples" to l.samples, "ms" to ms, "hz" to hz.roundToInt(),
            "p min" to round2(l.pMin), "p max" to round2(l.pMax), "tool" to l.tool.name.lowercase(),
            "brush" to l.kind.id, "width" to l.builder.brush.width, "committed" to committed, "removed" to removed,
            "previewed" to l.previewed, "hold" to l.hold, "in hold" to if (l.hold > 0) controller.session.strokesInHold else 0,
            "reveal" to controller.reveal.name, "straight" to l.straight,
            "style" to preview?.style, "preview width" to preview?.widthPx?.let { round2(it) }, "params" to preview?.params?.joinToString(","),
            "commit ms" to round2(lastCommitMs), "zoom" to round2(viewport.scale), "turn" to viewport.rotationDegrees,
            "p mean" to round2(meanP), "typical p" to round2(match.typicalPressure), "size factor" to preview?.style?.let { round2(match.factor(it)) },
            "since open ms" to controller.msSinceOpen, "swap delay ms" to settings.swapDelayMs,
        )
        lastCommitMs = 0.0
        if (controller.holding) {
            pendingLogs.add(fields)
            if (pendingLogs.size >= MAX_PENDING_LOGS) flushStrokeLogs("still held")
        } else {
            log.d("stroke", *fields, "revealed" to if (l.previewed) "at once" else "not previewed")
        }
    }

    private var lastCommitMs = 0.0

    private fun cancelLive() {
        val l = live ?: return
        live = null
        removeCallbacks(holdCheck)
        if (l.tool != Tool.Finger) penLifted(null)
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
            gestureTravel = 0f
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
            // Two fingers down is a break already (a pinch, or a tap to undo); fast mode waits for them to move.
            controller.block(BLOCK_FINGERS)
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
            gestureMoved(Float.MAX_VALUE)
            invalidate()
        }
    }

    /** The fingers moved the page by [px]: past a few pixels it's a gesture, which the display follows in fast mode. */
    private fun gestureMoved(px: Float) {
        gestureTravel += px
        if (gestureOn || gestureTravel < GESTURE_SLOP_DP * density) return
        gestureOn = true
        controller.gestureStarted()
    }

    /** The fingers let go of the page: the display leaves fast mode shortly after, unless another gesture follows. */
    private fun gestureDone() {
        if (gestureOn) {
            gestureOn = false
            controller.gestureEnded()
        }
        controller.unblock(BLOCK_FINGERS)
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
        if (fingerMode == FingerMode.Pinch) pinching = false
        gestureDone()
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
            viewportSettled()
        }
        gestureDone()
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
            gestureMoved(hypot(dx, dy))
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
        // A new stroke is drawn behind the hold; any other edit (undo, redo, a layer's change, a merge) is a break.
        when {
            e.action == HistoryAction.Undo -> controller.releaseNow("undo")
            e.action == HistoryAction.Redo -> controller.releaseNow("redo")
            c !is AddStroke -> controller.releaseNow("edit")
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
        // A capture for the display's layer draws the frame as it is; the tiles' frame bookkeeping is the screen's.
        if (!capturing) tiles.beginFrame(level)
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
        if (capturing) return
        tiles.endFrame()
        // The frame drawn now, with every stroke lifted so far, reaches the panel by the next vsync: report it then,
        // unless another stroke ended in between, whose own frame is still to come.
        if (upSeq != reportedSeq && !frameCallbackPosted) {
            frameCallbackPosted = true
            val seq = upSeq
            Choreographer.getInstance().postFrameCallback {
                frameCallbackPosted = false
                if (seq != upSeq) {
                    invalidate()
                    return@postFrameCallback
                }
                val delay = settings.swapDelayMs.toLong()
                if (delay > 0) postDelayed({ reportFrame(seq) }, delay) else reportFrame(seq)
            }
        }
        refreshAfterDraw?.let { reason ->
            refreshAfterDraw = null
            schedulePageRefresh(reason)
        }
    }

    private fun reportFrame(seq: Int) {
        if (seq != upSeq) {
            invalidate()
            return
        }
        reportedSeq = seq
        controller.frameShown()
        if (shields?.hasChanged == true) scheduleControlsPush()
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

    /** Whether Palm guard has finger touch switched off right now. */
    val palmGuardActive: Boolean get() = palm.active

    /** The screen areas the display's preview leaves alone: every control, and the screen around the canvas. */
    val previewExclusions: List<IntArray> get() = controlAreas().map { it.copyOf() }

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

        /** The lasso's dashed preview, in screen pixels. */
        private const val LASSO_PREVIEW_PX = 3f

        /** Room around a stroke's path for its preview's own edge. */
        private const val BOUNDS_PAD_PX = 3f

        /** The most a pushed area of ink may take; larger ones are shown by letting the frames through instead. */
        const val MAX_PUSH_BYTES = 4L * 1024 * 1024

        /** How far the fingers must move the page before the display switches to its fast mode (the native apps: 5 px). */
        private const val GESTURE_SLOP_DP = 4f

        /** A page refresh waits for the frame that shows the change, and happens at most this often. */
        private const val PAGE_REFRESH_DELAY_MS = 150L
        private const val PAGE_REFRESH_GAP_MS = 1500L

        /** Stroke summaries kept for a long hold before they're logged anyway. */
        private const val MAX_PENDING_LOGS = 200
        private const val NO_LAYER = Long.MIN_VALUE

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
        private const val BLOCK_FINGERS = "fingers"
        private const val BLOCK_ERASER = "eraser"
        private const val BLOCK_FOCUS = "focus"
    }
}
