package app.booxultimatum.nib

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.booxultimatum.nib.brush.BrushPreset
import app.booxultimatum.nib.brush.PressurePreset
import app.booxultimatum.nib.editor.CanvasView
import app.booxultimatum.nib.editor.ToolMode
import app.booxultimatum.nib.editor.ToolState
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.geom.Vec
import app.booxultimatum.nib.store.DrawingStore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The redesigned editor's tools end to end on the emulator: the turned page, Reset view, the lasso, typed values, the
 * eyedropper and the floating controls as far as the pen is concerned. Controls are found and pressed through the
 * accessibility tree, as a screen reader would.
 */
@RunWith(AndroidJUnit4::class)
class StudioEditorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store = DrawingStore.get(context)
    private lateinit var id: String
    private var scenario: ActivityScenario<MainActivity>? = null
    private val tools get() = ToolState.get(context)

    @Before fun setUp() {
        instrumentation.runOnMainSync {
            tools.setSlot(0, BrushPreset.DEFAULTS[0])
            tools.setSlot(1, BrushPreset.DEFAULTS[1])
            tools.selected = 0
            tools.mode = ToolMode.Pen
            NibSettings.get(context).fingerDrawing = false
            NibSettings.get(context).straightLineHold = true
        }
        context.getSharedPreferences("nib.panels", Context.MODE_PRIVATE).edit().clear().commit()
        id = store.create("Studio test", 1860, 2480, "Layer 1").id
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync {
            tools.mode = ToolMode.Pen
            tools.setSlot(0, BrushPreset.DEFAULTS[0])
            tools.setSlot(1, BrushPreset.DEFAULTS[1])
            tools.selected = 0
        }
        scenario?.close()
        store.delete(id)
    }

    private fun launch(): CanvasView {
        val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_DRAWING, id)
        scenario = ActivityScenario.launch(intent)
        return waitFor("the canvas and its drawing") { canvas()?.takeIf { it.session != null && it.width > 0 } }
    }

    private fun canvas(): CanvasView? {
        var v: CanvasView? = null
        scenario!!.onActivity { v = it.findViewById(R.id.nib_canvas) }
        return v
    }

    private fun <T : Any> waitFor(what: String, timeoutMs: Long = 10_000, probe: () -> T?): T {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            instrumentation.waitForIdleSync()
            probe()?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun event(down: Long, t: Long, action: Int, x: Float, y: Float, tool: Int = MotionEvent.TOOL_TYPE_STYLUS, pressure: Float = 0.8f): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { this.id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = pressure
            size = 0.1f
        })
        val source = if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_TOUCHSCREEN
        return MotionEvent.obtain(down, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
    }

    /** A stylus path through [points] in view pixels, dispatched to the canvas as the window would. */
    private fun path(v: CanvasView, points: List<Pair<Float, Float>>, stepMs: Long = 6L) {
        val down = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync {
            points.forEachIndexed { i, (x, y) ->
                val action = when (i) {
                    0 -> MotionEvent.ACTION_DOWN
                    points.lastIndex -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
                v.dispatchTouchEvent(event(down, down + i * stepMs, action, x, y, pressure = if (action == MotionEvent.ACTION_UP) 0f else 0.6f))
            }
        }
        instrumentation.waitForIdleSync()
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, steps: Int = 24) =
        (0..steps).map { i -> (x0 + (x1 - x0) * i / steps) to (y0 + (y1 - y0) * i / steps) }

    private fun find(n: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.contentDescription?.toString() == label || n.text?.toString() == label) return n
        for (i in 0 until n.childCount) find(n.getChild(i), label)?.let { return it }
        return null
    }

    private fun node(label: String): AccessibilityNodeInfo =
        waitFor("a control labelled \"$label\"") { find(instrumentation.uiAutomation.rootInActiveWindow, label) }

    private fun click(label: String) {
        // Compose may hang a label on a child of the clickable node; press the nearest clickable one.
        var n: AccessibilityNodeInfo? = node(label)
        while (n != null && !n.isClickable) n = n.parent
        assertTrue(n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true, "pressed $label")
        instrumentation.waitForIdleSync()
    }

    private fun editable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) editable(n.getChild(i))?.let { return it }
        return null
    }

    private fun type(field: String, text: String) {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        // The label may sit beside the editable node; the entry bar holds the only text field on screen.
        var n: AccessibilityNodeInfo? = node(field)
        while (n != null && !n.isEditable) n = n.parent
        val target = n ?: waitFor("the entry field") { editable(instrumentation.uiAutomation.rootInActiveWindow) }
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args), "typed into $field")
        instrumentation.waitForIdleSync()
    }

    @Test fun onATurnedPageTheStrokeLandsWhereThePenWas() {
        val v = launch()
        instrumentation.runOnMainSync { v.rotateTo((PI / 5).toFloat()) }
        instrumentation.waitForIdleSync()
        assertEquals(36, v.viewport.rotationDegrees)
        val scale = v.viewport.scale
        path(v, line(400f, 500f, 1000f, 900f))
        val s = v.session!!.document.layers.single().strokes.single()
        val vp = v.viewport
        val first = vp.toView(Vec(s.points.x(0), s.points.y(0)))
        val last = vp.toView(Vec(s.points.x(s.size - 1), s.points.y(s.size - 1)))
        assertTrue(abs(first.x - 400f) < 1.5f && abs(first.y - 500f) < 1.5f, "the start is under the pen: $first")
        assertTrue(abs(last.x - 1000f) < 1.5f && abs(last.y - 900f) < 1.5f, "so is the end: $last")
        assertEquals(scale, vp.scale, "turning never zooms")
        // The brush's width is the document's, however the page is turned.
        assertEquals(BrushPreset.DEFAULTS[0].width, s.brush.width)
    }

    @Test fun resetViewStandsThePageUprightAndFitsIt() {
        val v = launch()
        val fitted = v.viewport
        instrumentation.runOnMainSync {
            v.zoomBy(2.5f)
            v.rotateTo(1.1f)
        }
        assertTrue(v.viewport.rotation != 0f)
        click(context.getString(R.string.view_reset))
        waitFor("the page upright") { v.viewport.takeIf { it.rotation == 0f } }
        assertEquals(fitted.scale, v.viewport.scale, 1e-4f)
        assertEquals(fitted.offsetX, v.viewport.offsetX, 0.5f)
        assertEquals(fitted.offsetY, v.viewport.offsetY, 0.5f)
    }

    @Test fun theLassoMovesStrokesAndUndoPutsThemBack() {
        val v = launch()
        val s = v.session!!
        path(v, line(350f, 350f, 750f, 750f))
        path(v, line(300f, 1400f, 900f, 1400f))
        val before = s.document.layers.single().strokes
        instrumentation.runOnMainSync { tools.mode = ToolMode.Lasso }
        path(v, listOf(250f to 250f, 850f to 250f, 850f to 850f, 250f to 850f, 250f to 260f), stepMs = 20L)
        val sel = assertNotNull(v.selection, "the loop picked the stroke inside it")
        assertEquals(listOf(before[0].id), sel.ids)
        // Dragging from inside the frame moves the picked stroke, and only it, in one step.
        path(v, line(550f, 550f, 550f, 850f, steps = 12))
        val moved = s.document.layers.single().strokes
        val dy = 300f / v.viewport.scale
        assertEquals(before[0].points.y(0) + dy, moved[0].points.y(0), 0.1f)
        assertEquals(before[0].points.x(0), moved[0].points.x(0), 0.1f)
        assertEquals(before[1], moved[1], "the other stroke stays")
        assertNotNull(v.selection, "the selection follows its strokes")
        instrumentation.runOnMainSync { assertTrue(s.undo()) }
        assertEquals(before, s.document.layers.single().strokes)
        assertNull(v.selection, "undo ends the selection")
        // Duplicate and delete are single steps too.
        path(v, listOf(250f to 250f, 850f to 250f, 850f to 850f, 250f to 850f, 250f to 260f), stepMs = 20L)
        instrumentation.runOnMainSync { assertTrue(v.duplicateSelection()) }
        assertEquals(3, s.document.strokeCount)
        instrumentation.runOnMainSync { assertTrue(v.deleteSelection()) }
        assertEquals(2, s.document.strokeCount)
        instrumentation.runOnMainSync { assertTrue(s.undo()) }
        assertEquals(3, s.document.strokeCount)
    }

    @Test fun aTypedWidthIsTakenCheckedAndClamped() {
        launch()
        val size = context.getString(R.string.slider_size)
        val field = context.getString(R.string.entry_field, size)
        click(context.getString(R.string.slider_type, size))
        type(field, "0,6")
        click(context.getString(R.string.action_set))
        waitFor("the width to be taken") { tools.current.width.takeIf { abs(it - 0.6f) < 1e-4f } }
        click(context.getString(R.string.slider_type, size))
        type(field, "abc")
        click(context.getString(R.string.action_set))
        // Refused: the entry bar stays open with its field, and the width is as it was.
        waitFor("the entry bar to stay open") { editable(instrumentation.uiAutomation.rootInActiveWindow) }
        assertEquals(0.6f, tools.current.width, 1e-4f, "nothing taken from a bad entry")
        type(field, "999")
        click(context.getString(R.string.action_set))
        val max = BrushSpec.widthRange(tools.current.kind).endInclusive
        waitFor("the width to be clamped") { tools.current.width.takeIf { it == max } }
    }

    @Test fun theEyedropperTakesAColourFromThePage() {
        val v = launch()
        val red = 0xFFD2232A.toInt()
        instrumentation.runOnMainSync { tools.setSlot(0, BrushPreset(BrushKind.Fineliner, 24f, PressurePreset.Medium, red)) }
        path(v, line(300f, 1200f, 1300f, 1200f))
        instrumentation.runOnMainSync {
            tools.selected = 1
            tools.mode = ToolMode.Eyedropper
        }
        assertEquals(0xFF000000.toInt(), tools.current.color)
        path(v, listOf(800f to 1200f, 800f to 1200f))
        waitFor("the colour to be taken") { tools.current.color.takeIf { it == red } }
        assertEquals(ToolMode.Pen, tools.mode, "the eyedropper hands back to the pen")
        assertEquals(red, tools.recentColours.first())
    }

    @Test fun aHeldStrokeBecomesStraight() {
        val v = launch()
        val down = SystemClock.uptimeMillis()
        val pts = (0..30).map { i -> 300f + i * 20f to 1000f + 60f * kotlin.math.sin(i / 3f) }
        instrumentation.runOnMainSync {
            pts.forEachIndexed { i, (x, y) ->
                v.dispatchTouchEvent(event(down, down + i * 6L, if (i == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE, x, y))
            }
        }
        SystemClock.sleep(900)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val (x, y) = pts.last()
            v.dispatchTouchEvent(event(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, pressure = 0f))
        }
        val s = v.session!!.document.layers.single().strokes.single()
        val ys = (0 until s.size).map { s.points.y(it) }
        val xs = (0 until s.size).map { s.points.x(it) }
        // Collinear: every point on the segment from the first to the last.
        for (i in ys.indices) {
            val cross = (xs.last() - xs[0]) * (ys[i] - ys[0]) - (ys.last() - ys[0]) * (xs[i] - xs[0])
            assertTrue(abs(cross) / 600f < 0.5f, "point $i is off the straight line")
        }
    }

    @Test fun everyFloatingControlIsLeftOutOfThePreview() {
        val v = launch()
        val keys = listOf(R.string.action_undo, R.string.action_more, R.string.tool_lasso, R.string.view_reset).map { res ->
            android.graphics.Rect().also { node(context.getString(res)).getBoundsInScreen(it) }
        }
        val loc = IntArray(2)
        instrumentation.runOnMainSync { v.getLocationOnScreen(loc) }
        fun exclusions(): List<IntArray> {
            var out = emptyList<IntArray>()
            instrumentation.runOnMainSync { out = v.previewExclusions }
            return out
        }
        for (r in keys) {
            val areas = waitFor("an exclusion around $r") {
                exclusions().takeIf { list -> list.any { it[0] <= r.left && it[1] <= r.top && it[2] >= r.right && it[3] >= r.bottom } }
            }
            assertTrue(areas.size >= keys.size, "the pills, the rail and the view chip, all at once")
        }
        if (loc[1] > 0) {
            assertTrue(exclusions().any { it[1] <= 0 && it[3] >= loc[1] }, "the screen above the canvas too")
        }
    }

    @Test fun thePenOverAFloatingCardIsNotOverTheCanvas() {
        val v = launch()
        val undo = node(context.getString(R.string.action_library))
        val r = android.graphics.Rect()
        undo.getBoundsInScreen(r)
        val loc = IntArray(2)
        instrumentation.runOnMainSync { v.getLocationOnScreen(loc) }
        assertTrue(r.centerY() > loc[1], "the pill floats over the canvas")
        val t = SystemClock.uptimeMillis()
        fun hover(action: Int, x: Float, y: Float, dt: Long) {
            instrumentation.uiAutomation.injectInputEvent(event(t, t + dt, action, x, y, pressure = 0f), true)
        }
        hover(MotionEvent.ACTION_HOVER_ENTER, loc[0] + 700f, loc[1] + 900f, 0)
        hover(MotionEvent.ACTION_HOVER_MOVE, loc[0] + 720f, loc[1] + 920f, 10)
        waitFor("hover over the canvas") { v.penHovering.takeIf { it } }
        hover(MotionEvent.ACTION_HOVER_MOVE, r.exactCenterX(), r.exactCenterY(), 20)
        waitFor("hover over the pill") { (!v.penHovering).takeIf { it } }
        hover(MotionEvent.ACTION_HOVER_MOVE, loc[0] + 720f, loc[1] + 920f, 30)
        waitFor("hover back over the canvas") { v.penHovering.takeIf { it } }
        hover(MotionEvent.ACTION_HOVER_EXIT, loc[0] + 720f, loc[1] + 920f, 40)
    }

    @Test fun exportsWithPaperWithoutAndLayerByLayer() {
        val v = launch()
        val s = v.session!!
        path(v, line(300f, 600f, 1200f, 600f))
        instrumentation.runOnMainSync { assertTrue(s.addLayer("Sky")) }
        path(v, line(300f, 900f, 1200f, 900f))
        val doc = s.document
        val paper = app.booxultimatum.nib.store.Paper(colour = app.booxultimatum.nib.store.PaperColour.Warm.argb, guides = app.booxultimatum.nib.store.Guides.Grid, guidesInExport = true)
        fun png(kind: app.booxultimatum.nib.export.ExportKind): android.graphics.Bitmap {
            val bytes = java.io.ByteArrayOutputStream().also { app.booxultimatum.nib.export.Exporter.writePng(doc, it, paper, kind) }.toByteArray()
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        val withPaper = png(app.booxultimatum.nib.export.ExportKind.WithPaper)
        assertEquals(app.booxultimatum.nib.store.PaperColour.Warm.argb, withPaper.getPixel(5, 5), "the warm paper")
        val guide = (59..61).minOf { withPaper.getPixel(5, it) and 0xFF }
        assertTrue(guide < (withPaper.getPixel(5, 30) and 0xFF) - 15, "a grid line at the spacing, drawn under the ink")
        val ink = png(app.booxultimatum.nib.export.ExportKind.Transparent)
        assertEquals(0, ink.getPixel(5, 5) ushr 24, "no paper: transparent")
        val zip = java.io.ByteArrayOutputStream().also { app.booxultimatum.nib.export.Exporter.writeLayersZip(doc, it) { i, n -> "${i + 1} $n" } }.toByteArray()
        val names = java.util.zip.ZipInputStream(zip.inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertEquals(listOf("1 Layer 1.png", "2 Sky.png"), names)
    }

    @Test fun aDrawingSavedByNib01StillOpens() {
        // Nib 0.1 wrote only the name and size beside a drawing; everything new must read as plain paper.
        store.metaFile(id).writeText("name=Old one\nwidth=1860\nheight=2480\n")
        val info = assertNotNull(store.info(id))
        assertEquals("Old one", info.name)
        assertEquals(1, info.layers, "the layer count comes from the file itself")
        assertEquals(app.booxultimatum.nib.store.Paper.PLAIN, info.paper)
        val opened = assertNotNull(store.open(id))
        opened.journal.close()
        assertEquals(1860, opened.document.width)
        val v = launch()
        path(v, line(300f, 600f, 900f, 700f))
        assertEquals(1, v.session!!.document.strokeCount)
        // A 0.1 pen slot, written without any tuning, still reads.
        assertEquals(BrushPreset(BrushKind.Fountain, 3f, PressurePreset.Medium, -0x1000000), BrushPreset.decode("fountain:3.0:medium:ff000000"))
    }
}