package app.booxultimatum.nib

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.booxultimatum.nib.brush.BrushPreset
import app.booxultimatum.nib.editor.CanvasView
import app.booxultimatum.nib.editor.EditorSession
import app.booxultimatum.nib.editor.ToolMode
import app.booxultimatum.nib.editor.ToolState
import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.geom.Vec
import app.booxultimatum.nib.engine.io.Journal
import app.booxultimatum.nib.engine.io.NibFile
import app.booxultimatum.nib.engine.record.PenAction
import app.booxultimatum.nib.engine.record.PenRecording
import app.booxultimatum.nib.engine.record.PenRecordingCodec
import app.booxultimatum.nib.export.Exporter
import app.booxultimatum.nib.pen.PenRecorderStore
import app.booxultimatum.nib.store.DrawingStore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The editor end to end on the emulator, which has no Boox display path: Nib draws in software there, which is the
 * mode these tests cover. Pen strokes are MotionEvents with a stylus tool and pressure, as the tablet sends them.
 */
@RunWith(AndroidJUnit4::class)
class EditorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store = DrawingStore.get(context)
    private lateinit var id: String
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() {
        instrumentation.runOnMainSync {
            val tools = ToolState.get(context)
            tools.setSlot(0, BrushPreset.DEFAULTS[0])
            tools.setSlot(1, BrushPreset.DEFAULTS[1])
            tools.selected = 0
            tools.mode = ToolMode.Pen
            NibSettings.get(context).fingerDrawing = false
            NibSettings.get(context).penRecorder = false
        }
        id = store.create("Test drawing", 1860, 2480, "Layer 1").id
    }

    @After fun tearDown() {
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
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

    private fun session(v: CanvasView): EditorSession = v.session!!

    private fun event(down: Long, t: Long, action: Int, xs: FloatArray, ys: FloatArray, tool: Int, pressure: Float = 0.8f): MotionEvent {
        val n = xs.size
        val props = Array(n) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = tool } }
        val coords = Array(n) { i ->
            MotionEvent.PointerCoords().apply {
                x = xs[i]
                y = ys[i]
                this.pressure = pressure
                size = 0.1f
            }
        }
        val source = if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_TOUCHSCREEN
        return MotionEvent.obtain(down, t, action, n, props, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
    }

    /** A stylus stroke from (x0, y0) to (x1, y1) in view pixels, dispatched to the canvas as the window would. */
    private fun stroke(v: CanvasView, x0: Float, y0: Float, x1: Float, y1: Float, steps: Int = 24) {
        val down = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync {
            v.dispatchTouchEvent(event(down, down, MotionEvent.ACTION_DOWN, floatArrayOf(x0), floatArrayOf(y0), MotionEvent.TOOL_TYPE_STYLUS, 0.3f))
            for (i in 1..steps) {
                val f = i / steps.toFloat()
                val p = 0.3f + 0.6f * f
                v.dispatchTouchEvent(event(down, down + i * 4L, MotionEvent.ACTION_MOVE, floatArrayOf(x0 + (x1 - x0) * f), floatArrayOf(y0 + (y1 - y0) * f), MotionEvent.TOOL_TYPE_STYLUS, p))
            }
            v.dispatchTouchEvent(event(down, down + steps * 4L + 4, MotionEvent.ACTION_UP, floatArrayOf(x1), floatArrayOf(y1), MotionEvent.TOOL_TYPE_STYLUS, 0f))
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun aStylusStrokeBecomesOneStroke() {
        val v = launch()
        stroke(v, 200f, 300f, 900f, 700f)
        val doc = session(v).document
        assertEquals(1, doc.strokeCount)
        val s = doc.layers.single().strokes.single()
        assertEquals(BrushPreset.DEFAULTS[0].kind, s.brush.kind)
        assertTrue(s.points.size > 10)
        assertTrue((0 until s.points.size).map { s.points.pressure(it) }.let { p -> p.max() > p.min() }, "pressure reaches the stroke")
        assertTrue(s.points.pressure(s.points.size - 1) > 0.5f, "the lift's zero pressure doesn't thin the end")
        // The stroke is in document pixels: the canvas starts fitted, so it maps back onto the view.
        val vp = v.viewport
        assertTrue(abs(vp.toView(Vec(s.points.x(0), s.points.y(0))).x - 200f) < 1f)
    }

    @Test fun aStrokeThroughTheWindowReachesTheCanvas() {
        val v = launch()
        val loc = IntArray(2)
        instrumentation.runOnMainSync { v.getLocationOnScreen(loc) }
        val down = SystemClock.uptimeMillis()
        val x0 = loc[0] + 300f
        val y0 = loc[1] + 400f
        instrumentation.uiAutomation.injectInputEvent(event(down, down, MotionEvent.ACTION_DOWN, floatArrayOf(x0), floatArrayOf(y0), MotionEvent.TOOL_TYPE_STYLUS), true)
        for (i in 1..20) {
            instrumentation.uiAutomation.injectInputEvent(event(down, down + i * 5L, MotionEvent.ACTION_MOVE, floatArrayOf(x0 + i * 20f), floatArrayOf(y0 + i * 10f), MotionEvent.TOOL_TYPE_STYLUS), true)
        }
        instrumentation.uiAutomation.injectInputEvent(event(down, down + 110, MotionEvent.ACTION_UP, floatArrayOf(x0 + 400f), floatArrayOf(y0 + 200f), MotionEvent.TOOL_TYPE_STYLUS), true)
        waitFor("the stroke") { session(v).document.strokeCount.takeIf { it == 1 } }
    }

    @Test fun undoAndRedo() {
        val v = launch()
        stroke(v, 200f, 300f, 900f, 700f)
        stroke(v, 200f, 800f, 900f, 900f)
        val s = session(v)
        assertEquals(2, s.document.strokeCount)
        instrumentation.runOnMainSync { assertTrue(s.undo()) }
        assertEquals(1, s.document.strokeCount)
        instrumentation.runOnMainSync { assertTrue(s.redo()) }
        assertEquals(2, s.document.strokeCount)

        // Two fingers tapped together undo, once the pen has been away long enough for palm rejection to let them in.
        SystemClock.sleep(800)
        val t = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync {
            val xs = floatArrayOf(500f, 700f)
            val ys = floatArrayOf(1200f, 1200f)
            val finger = MotionEvent.TOOL_TYPE_FINGER
            v.dispatchTouchEvent(event(t, t, MotionEvent.ACTION_DOWN, xs.copyOf(1), ys.copyOf(1), finger))
            v.dispatchTouchEvent(event(t, t + 10, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), xs, ys, finger))
            v.dispatchTouchEvent(event(t, t + 80, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), xs, ys, finger))
            v.dispatchTouchEvent(event(t, t + 90, MotionEvent.ACTION_UP, xs.copyOf(1), ys.copyOf(1), finger))
        }
        assertEquals(1, s.document.strokeCount)
    }

    @Test fun layersAddReorderAndUndo() {
        val v = launch()
        val s = session(v)
        stroke(v, 200f, 300f, 900f, 700f)
        val first = s.document.layers.single().id
        instrumentation.runOnMainSync { assertTrue(s.addLayer("Layer 2")) }
        val second = s.activeLayerId
        stroke(v, 200f, 900f, 900f, 1100f)
        assertEquals(listOf(first, second), s.document.layers.map { it.id })
        assertEquals(1, s.document.layer(second)!!.strokes.size, "new strokes go to the active layer")
        instrumentation.runOnMainSync { assertTrue(s.moveLayer(second, up = false)) }
        assertEquals(listOf(second, first), s.document.layers.map { it.id })
        instrumentation.runOnMainSync { assertTrue(s.setOpacity(second, 0.5f)) }
        instrumentation.runOnMainSync { assertTrue(s.undo()) }
        assertEquals(1f, s.document.layer(second)!!.opacity)
        instrumentation.runOnMainSync { assertTrue(s.undo()) }
        assertEquals(listOf(first, second), s.document.layers.map { it.id })
        instrumentation.runOnMainSync { assertTrue(s.duplicateLayer(second, "Copy")) }
        assertEquals(3, s.document.layers.size)
        assertEquals(1, s.document.layers.last().strokes.size)
        instrumentation.runOnMainSync { assertTrue(s.mergeDown(s.activeLayerId)) }
        assertEquals(2, s.document.layer(second)!!.strokes.size)
    }

    @Test fun savedDrawingsReopenTheSame() {
        val v = launch()
        val s = session(v)
        stroke(v, 200f, 300f, 900f, 700f)
        instrumentation.runOnMainSync { s.addLayer("Layer 2") }
        stroke(v, 300f, 1000f, 800f, 1500f)
        assertTrue(s.flush())
        val live = s.document

        // From the journal: snapshot plus replayed edits.
        val fromJournal = store.open(id)!!
        fromJournal.journal.close()
        assertEquals(live, fromJournal.document)
        assertEquals(3, fromJournal.replayed)

        // After compaction the snapshot alone holds it, with a thumbnail, and the journal starts empty.
        instrumentation.runOnMainSync { s.saveNow("test") }
        assertTrue(s.flush())
        val snapshot = NibFile.read(store.nibFile(id))
        assertEquals(live, snapshot.document)
        assertNotNull(snapshot.thumbnailPng)
        assertEquals(Journal.HEADER_SIZE.toLong(), store.journalFile(id).length())
        assertNotNull(store.thumbnail(id))
    }

    @Test fun rotationKeepsTheCanvasAndTheDocument() {
        val v = launch()
        stroke(v, 200f, 300f, 900f, 700f)
        val s = session(v)
        val doc = s.document
        val centre = v.viewport.toDoc(Vec(v.width / 2f, v.height / 2f))
        // Android 16 ignores apps' orientation requests on large screens, so the test turns the display itself.
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90)
        waitFor("landscape") { canvas()?.takeIf { it.width > it.height } }
        val after = canvas()!!
        assertSame(v, after, "the canvas isn't recreated")
        assertSame(s, after.session)
        assertSame(doc, after.session!!.document)
        val now = after.viewport.toDoc(Vec(after.width / 2f, after.height / 2f))
        assertTrue(abs(now.x - centre.x) < 1f)
        assertTrue(abs(now.y - centre.y) < 1f)
        stroke(after, 300f, 300f, 900f, 500f)
        assertEquals(2, s.document.strokeCount)
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        waitFor("portrait") { canvas()?.takeIf { it.height > it.width } }
    }

    @Test fun erasersRemoveStrokesAndRubOutInk() {
        val v = launch()
        val s = session(v)
        stroke(v, 200f, 300f, 1200f, 300f)
        stroke(v, 200f, 700f, 1200f, 700f)
        stroke(v, 200f, 1100f, 1200f, 1100f)
        assertEquals(3, s.document.strokeCount)
        val tools = ToolState.get(context)
        try {
            instrumentation.runOnMainSync {
                tools.mode = ToolMode.Eraser
                tools.chooseEraser(BrushKind.StrokeEraser)
            }
            stroke(v, 700f, 200f, 700f, 400f)
            assertEquals(2, s.document.strokeCount, "the stroke eraser removes what it crosses, in one step")

            // A lasso round the middle line removes it and leaves the bottom one.
            instrumentation.runOnMainSync { tools.chooseEraser(BrushKind.LassoEraser) }
            val loop = listOf(100f to 600f, 1300f to 600f, 1300f to 800f, 100f to 800f, 100f to 600f)
            val down = SystemClock.uptimeMillis()
            instrumentation.runOnMainSync {
                loop.forEachIndexed { i, (x, y) ->
                    val action = when (i) {
                        0 -> MotionEvent.ACTION_DOWN
                        loop.lastIndex -> MotionEvent.ACTION_UP
                        else -> MotionEvent.ACTION_MOVE
                    }
                    v.dispatchTouchEvent(event(down, down + i * 20L, action, floatArrayOf(x), floatArrayOf(y), MotionEvent.TOOL_TYPE_STYLUS))
                }
            }
            assertEquals(1, s.document.strokeCount)

            // The pixel eraser is itself a stroke that erases the ink under it, so it undoes like one.
            instrumentation.runOnMainSync { tools.chooseEraser(BrushKind.PixelEraser) }
            stroke(v, 700f, 1000f, 700f, 1200f)
            val strokes = s.document.layers.single().strokes
            assertEquals(2, strokes.size)
            assertEquals(Blend.Erase, strokes.last().brush.blend)
            instrumentation.runOnMainSync { s.undo() }
            assertEquals(1, s.document.strokeCount)
        } finally {
            instrumentation.runOnMainSync {
                tools.chooseEraser(BrushKind.StrokeEraser)
                tools.mode = ToolMode.Pen
            }
        }
    }

    @Test fun theTilesShowTheInkAndLoseItOnUndo() {
        val v = launch()
        val s = session(v)
        stroke(v, 200f, 500f, 1400f, 500f)
        fun inkAt(x: Int, y: Int): Boolean {
            var dark = false
            instrumentation.runOnMainSync {
                val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                v.draw(Canvas(bmp))
                dark = (y - 3..y + 3).any { yy -> (bmp.getPixel(x, yy) and 0xFF) < 128 }
                bmp.recycle()
            }
            return dark
        }
        assertTrue(inkAt(800, 500), "the committed stroke is in the tiles at once")
        assertTrue(v.tileCount > 0)
        instrumentation.runOnMainSync { s.undo() }
        waitFor("the tiles to re-render") { v.pendingTiles.takeIf { it == 0 }?.let { true } }
        SystemClock.sleep(200)
        instrumentation.waitForIdleSync()
        assertTrue(!inkAt(800, 500), "undo re-renders the tiles from the vectors")
    }

    @Test fun twoFingersPinchToZoomAndThePenIgnoresPalms() {
        val v = launch()
        val before = v.viewport.scale
        SystemClock.sleep(800)
        val t = SystemClock.uptimeMillis()
        val finger = MotionEvent.TOOL_TYPE_FINGER
        instrumentation.runOnMainSync {
            val cy = 1000f
            v.dispatchTouchEvent(event(t, t, MotionEvent.ACTION_DOWN, floatArrayOf(700f), floatArrayOf(cy), finger))
            v.dispatchTouchEvent(event(t, t + 10, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), floatArrayOf(700f, 900f), floatArrayOf(cy, cy), finger))
            for (i in 1..20) {
                val spread = 100f + i * 20f
                v.dispatchTouchEvent(event(t, t + 10 + i * 16L, MotionEvent.ACTION_MOVE, floatArrayOf(800f - spread, 800f + spread), floatArrayOf(cy, cy), finger))
            }
            v.dispatchTouchEvent(event(t, t + 400, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), floatArrayOf(300f, 1300f), floatArrayOf(cy, cy), finger))
            v.dispatchTouchEvent(event(t, t + 410, MotionEvent.ACTION_UP, floatArrayOf(300f), floatArrayOf(cy), finger))
        }
        assertTrue(v.viewport.scale > before * 1.5f, "pinching apart zooms in (${v.viewport.scale} from $before)")
        assertEquals(0, session(v).document.strokeCount, "fingers never draw by default")

        // Right after the pen, a resting palm is ignored: it neither pans nor draws.
        stroke(v, 200f, 300f, 600f, 300f)
        val vp = v.viewport
        val t2 = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync {
            v.dispatchTouchEvent(event(t2, t2, MotionEvent.ACTION_DOWN, floatArrayOf(900f), floatArrayOf(1500f), finger))
            v.dispatchTouchEvent(event(t2, t2 + 50, MotionEvent.ACTION_MOVE, floatArrayOf(1100f), floatArrayOf(1700f), finger))
            v.dispatchTouchEvent(event(t2, t2 + 100, MotionEvent.ACTION_UP, floatArrayOf(1100f), floatArrayOf(1700f), finger))
        }
        assertEquals(vp, v.viewport)
        assertEquals(1, session(v).document.strokeCount)
    }

    @Test fun penHoverIsSeenOverTheCanvasAndNotOverTheToolbar() {
        val v = launch()
        val loc = IntArray(2)
        instrumentation.runOnMainSync { v.getLocationOnScreen(loc) }
        val t = SystemClock.uptimeMillis()
        fun hover(action: Int, x: Float, y: Float, dt: Long) {
            val e = event(t, t + dt, action, floatArrayOf(x), floatArrayOf(y), MotionEvent.TOOL_TYPE_STYLUS, 0f)
            instrumentation.uiAutomation.injectInputEvent(e, true)
        }
        hover(MotionEvent.ACTION_HOVER_ENTER, loc[0] + 400f, loc[1] + 400f, 0)
        hover(MotionEvent.ACTION_HOVER_MOVE, loc[0] + 420f, loc[1] + 420f, 10)
        waitFor("hover over the canvas") { v.penHovering.takeIf { it } }
        hover(MotionEvent.ACTION_HOVER_MOVE, loc[0] + 420f, loc[1] - 60f, 20)
        waitFor("hover over the toolbar") { (!v.penHovering).takeIf { it } }
        hover(MotionEvent.ACTION_HOVER_EXIT, loc[0] + 420f, loc[1] - 60f, 30)
    }

    @Test fun thePenRecorderKeepsStrokesOnlyWhenOn() {
        val v = launch()
        val settings = NibSettings.get(context)
        try {
            stroke(v, 200f, 300f, 900f, 700f)
            assertEquals(0, PenRecorderStore.strokeCount, "off by default: nothing kept")
            instrumentation.runOnMainSync { settings.penRecorder = true }
            stroke(v, 200f, 800f, 900f, 900f)
            stroke(v, 200f, 1000f, 900f, 1100f)
            assertEquals(2, PenRecorderStore.strokeCount)
            var rec: PenRecording? = null
            instrumentation.runOnMainSync { rec = PenRecorderStore.recording(1860, 2480, 1f) }
            val file = PenRecorderStore.export(java.io.File(context.cacheDir, "recordings/test.penrec"), rec!!)
            val back = file.inputStream().use { PenRecordingCodec.read(it) }
            assertEquals(2, back.samples.count { it.action == PenAction.Down })
            assertEquals(rec!!.events.size, back.events.size)
            file.delete()
        } finally {
            instrumentation.runOnMainSync { settings.penRecorder = false }
        }
        assertEquals(0, PenRecorderStore.strokeCount, "turning it off forgets what it kept")
    }

    @Test fun exportMakesAPng() {
        val v = launch()
        stroke(v, 200f, 300f, 900f, 700f)
        val doc = session(v).document
        val bytes = ByteArrayOutputStream().also { Exporter.writePng(doc, it) }.toByteArray()
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(doc.width, bmp.width)
        assertEquals(doc.height, bmp.height)
        assertEquals(-1, bmp.getPixel(5, 5), "paper")
        val s = doc.layers.single().strokes.single()
        val mid = s.points.size / 2
        val px = bmp.getPixel(s.points.x(mid).toInt(), s.points.y(mid).toInt())
        assertTrue((px and 0xFF) < 128, "ink where the stroke runs")

        val uri = Exporter.saveToGallery(context, doc, "Nib test export")
        assertNotNull(uri)
        context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.IS_PENDING), null, null, null)!!.use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(Exporter.RELATIVE_DIR, c.getString(0))
            assertEquals(0, c.getInt(1))
        }
        context.contentResolver.delete(uri, null, null)
    }

}
