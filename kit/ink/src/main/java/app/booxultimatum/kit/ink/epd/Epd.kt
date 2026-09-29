package app.booxultimatum.kit.ink.epd

import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import app.booxultimatum.kit.log.Logbook

/**
 * A way to reach SurfaceFlinger through a privileged helper, for firmware that refuses this process. The hub supplies
 * one backed by Shizuku; apps without a helper pass none.
 */
fun interface ElevatedRoute {
    /** SurfaceFlinger's binder as seen through the helper, and the pid SurfaceFlinger sees as the caller; null when unavailable. */
    fun surfaceFlinger(): Pair<IBinder, Int>?
}

/**
 * The BOOX e-ink display, reached through SurfaceFlinger's private transactions: the pen session whose preview the
 * display draws itself, the regions it draws in, the stroke it draws, the hold on the app's frames, refreshes in any
 * update mode, the handwriting layer, and the display's own geometry.
 *
 * Codes and parcel layouts are those FW 4.3's `android.onyx.ViewUpdateHelper` sends (read from the decompiled
 * framework; see `docs/09-ink.md`, which also says which calls are verified on the tablet). Every call writes the
 * interface token first and is synchronous, so a later call can never overtake an earlier one. The codes are plain
 * numbers a firmware could change, so [connect] only accepts a route whose answers make sense.
 *
 * An ordinary app may make every call (verified from an app's and from the shell's pid on NA6C FW 4.3); the firmware's
 * helper class itself is a hidden API for a targetSdk-36 app, so it isn't used.
 */
object Epd {
    private val log = Logbook.logger("ink.display")
    private const val TOKEN = "android.ui.ISurfaceComposer"

    /** The transaction codes, grouped as `docs/09-ink.md` lists them. */
    object Code {
        const val SET_PEN_STATE = 16711693
        const val GET_PEN_STATE = 1048643
        const val IS_VALID_PEN_STATE = 1048641
        const val SET_REGION_LIMIT = 16711694
        const val SET_REGION_EXCLUDE = 16711714
        const val SET_REGION_MODE = 1048620
        const val SET_STROKE_COLOR = 16711686
        const val SET_STROKE_WIDTH = 16711687
        const val SET_STROKE_STYLE = 16711688
        const val GET_STROKE_PARAMETERS = 1049088
        const val SET_STROKE_PARAMETERS = 1049089
        const val SET_ERASER_RAW_DRAWING = 1048833
        const val SET_BRUSH_RAW_DRAWING = 1048834
        const val ENABLE_POST = 16711692
        const val SET_AUTO_SYNC = 1048722
        const val HANDWRITING_REPAINT = 1048647
        const val REPAINT_EVERYTHING = 16711700
        const val REPAINT_EVERYTHING_MODE = 16711715
        const val REFRESH_SCREEN = 16711681
        const val APPLY_GC_ONCE = 16711718
        const val WAIT_FOR_UPDATE = 16711703
        const val APPLY_TRANSIENT_UPDATE = 16711782
        const val CLEAR_TRANSIENT_UPDATE = 16711783
        const val GET_FAST_MODE_INDEX = 1048656
        const val DRAW_HANDWRITING_BITMAP = 1049092
        const val MAP_TO_VIEW = 16711723
        const val MAP_TO_EPD = 16711724
        const val GET_EPD_WIDTH = 16711727
        const val GET_EPD_HEIGHT = 16711712
        const val GET_TOUCH_WIDTH = 16711706
        const val GET_TOUCH_HEIGHT = 16711707
        const val GET_EPD_TO_VIEW_MATRIX = 1049344
        const val GET_MAX_TOUCH_PRESSURE = 1048618
        const val ENABLE_REGAL = 16711722
        const val SET_REGION_PEN_CONFIG = 1049090
        const val CLEAR_REGION_PEN_CONFIG = 1049091
        const val SET_PEN_SIDE_BUTTON = 1048832
        const val START_STROKE = 16711697
        const val ADD_STROKE_POINT = 16711698
        const val FINISH_STROKE = 16711699
    }

    /** The pen's parts a region's pen configuration can address. */
    object PenButton {
        const val TIP = 0

        /** The eraser end. */
        const val CAP = 1
        const val SIDE = 2
    }

    /**
     * What the display draws for one part of the pen inside a region: the native app gives the tip the pen's stroke,
     * the eraser end an eraser track (style 8, `[width, 0.5, 0.1]`) and the side button a marker or the lasso.
     */
    class ButtonStroke(
        val button: Int,
        val style: Int,
        val widthPx: Float,
        val argb: Int,
        val params: FloatArray? = null,
        val eraserPreview: Boolean = false,
        val eraserPainter: Int = 0,
    )

    /** SurfaceFlinger's pen session states. */
    object PenState {
        const val STOP = 0
        const val START = 1
        const val DRAW = 2
        const val PAUSE = 3

        /** What [penState] reads back for a paused session on NA6C FW 4.3. */
        const val PAUSED = 4

        fun isLive(state: Int?) = state == START || state == DRAW
    }

    /** How the display treats several limit or exclude rectangles. */
    enum class RegionMode(val code: Int) {
        /** Every rectangle counts (verified with two excluded areas, NA6C FW 4.3). */
        Multi(0),

        /** Only the last rectangle counts; what the native note app sets and what the display starts with. */
        Single(1),
    }

    enum class Route { Direct, Elevated }

    /** The largest bitmap one handwriting-layer call takes, as the firmware's own helper enforces it. */
    const val MAX_BITMAP_BYTES = 2 * 1024 * 1024

    @Volatile private var binder: IBinder? = null
    @Volatile var route: Route? = null
        private set

    /** The pen's pressure range, a device constant (4096 on NA6C FW 4.3), read when the route was found. */
    @Volatile var maxTouchPressure: Float? = null
        private set

    /** The pid SurfaceFlinger sees as the caller: ours, or the elevated helper's when calls go through it. */
    @Volatile var ownerPid: Int = Process.myPid()
        private set

    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    val available: Boolean get() = binder?.isBinderAlive == true && route != null

    /**
     * Finds SurfaceFlinger from this process, then through [elevated] when given. A route is only taken when it answers
     * the pen state and a plausible pressure range, which shows the codes mean what they say on this firmware.
     */
    @android.annotation.SuppressLint("PrivateApi")
    @Synchronized
    fun connect(elevated: ElevatedRoute? = null): Route? {
        if (available) return route
        val direct = runCatching {
            Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "SurfaceFlinger") as IBinder?
        }.getOrNull()
        if (direct != null && probe(direct)) {
            binder = direct; route = Route.Direct; ownerPid = Process.myPid()
            log.i("route", "route" to route, "max pressure" to maxTouchPressure)
            return route
        }
        elevated?.let { e -> runCatching { e.surfaceFlinger() }.getOrNull() }?.let { (wrapped, pid) ->
            if (probe(wrapped)) {
                binder = wrapped; route = Route.Elevated; ownerPid = pid
                log.i("route", "route" to route, "max pressure" to maxTouchPressure)
                return route
            }
        }
        binder = null; route = null
        return null
    }

    private fun probe(b: IBinder): Boolean {
        if (readInt(b, Code.GET_PEN_STATE) == null) return false
        val p = readFloat(b, Code.GET_MAX_TOUCH_PRESSURE) ?: return false
        if (p !in 1f..100_000f) return false
        maxTouchPressure = p
        return true
    }

    // ---- The pen session ----

    fun penState(): Int? = binder?.let { readInt(it, Code.GET_PEN_STATE) }

    fun setPenState(state: Int): Boolean = call(Code.SET_PEN_STATE) { writeInt(state); writeInt(ownerPid) }

    /**
     * Where the display may draw: left, top, right, bottom quadruples. With [screen] they're screen pixels, which the
     * display maps itself; without, they're in the panel's own landscape frame.
     */
    fun setRegionLimit(rects: IntArray, screen: Boolean): Boolean =
        call(Code.SET_REGION_LIMIT) { writeInt(if (screen) 1 else 0); writeIntArray(rects) }

    /** Where the display must not draw, as for [setRegionLimit]; an empty array clears every rectangle. */
    fun setRegionExclude(rects: IntArray, screen: Boolean = true): Boolean =
        call(Code.SET_REGION_EXCLUDE) { writeInt(if (screen) 1 else 0); writeIntArray(rects) }

    fun setRegionMode(mode: RegionMode): Boolean = call(Code.SET_REGION_MODE) { writeInt(mode.code) }

    /**
     * The stroke the display draws. Style first: choosing a style loads that style's default width, so a width sent
     * before it is lost. Send after [PenState.START].
     */
    fun setStroke(style: Int, widthPx: Float, argb: Int): Boolean {
        val a = call(Code.SET_STROKE_STYLE) { writeInt(style) }
        val b = call(Code.SET_STROKE_WIDTH) { writeFloat(widthPx) }
        val c = call(Code.SET_STROKE_COLOR) { writeInt(argb) }
        return a && b && c
    }

    /** A style's own parameters (fountain's are two numbers on FW 4.3, pencil has none); null when unavailable. */
    fun strokeParameters(style: Int): FloatArray? {
        val b = binder ?: return null
        return transact(b, Code.GET_STROKE_PARAMETERS, { writeInt(style) }) { r ->
            val n = r.readInt()
            if (n !in 0..64) null else FloatArray(n) { r.readFloat() }
        }
    }

    /** Replaces a style's parameters for every app until changed again; read them first so they can be put back. */
    fun setStrokeParameters(style: Int, params: FloatArray): Boolean = call(Code.SET_STROKE_PARAMETERS) {
        writeInt(style); writeInt(params.size); params.forEach { writeFloat(it) }
    }

    /**
     * Per-part pen configuration for a screen [rect] (left, top, right, bottom): what the tip, the eraser end and the
     * side button each draw there, as the native note app sets it up. The layout is the framework's
     * `HandwritingRegionConfig` parcelable (size-prefixed records: the rectangle, then typed `PenConfig`s of a button
     * and a typed `StrokeConfig`). *[verify]* on the tablet, including whether [rect] is in screen coordinates.
     */
    fun setRegionPenConfig(rect: IntArray, buttons: List<ButtonStroke>): Boolean = call(Code.SET_REGION_PEN_CONFIG) {
        writeInt(1); writeInt(1)
        sized {
            rect.take(4).forEach { writeInt(it) }
            writeInt(buttons.size)
            buttons.forEach { b ->
                writeInt(1)
                sized {
                    writeInt(b.button)
                    writeInt(1)
                    sized {
                        writeInt(b.style); writeFloat(b.widthPx); writeInt(b.argb)
                        writeFloatArray(b.params)
                        writeInt(if (b.eraserPreview) 1 else 0)
                        writeInt(b.eraserPainter)
                    }
                }
            }
        }
    }

    /** Clears the pen configuration of [rect], or of every region when null. */
    fun clearRegionPenConfig(rect: IntArray? = null): Boolean = call(Code.CLEAR_REGION_PEN_CONFIG) {
        if (rect == null) { writeInt(0); writeInt(0) } else { writeInt(1); writeInt(1); rect.take(4).forEach { writeInt(it) } }
    }

    /** Whether the pen's side button is on (it's reported to apps and the display when on). */
    fun setPenSideButton(on: Boolean): Boolean = call(Code.SET_PEN_SIDE_BUTTON) { writeInt(if (on) 1 else 0) }

    /**
     * The display's own stroke-width model, fed one point at a time: each call returns the width the display computes
     * for the point (base width, x, y, pressure 0..1, size, time). Probed from Diagnostics only: whether feeding
     * points also draws them is *[verify]*.
     */
    fun strokeWidthAt(kind: Int, baseWidth: Float, x: Float, y: Float, pressure: Float, size: Float, timeMs: Float): Float? {
        val b = binder ?: return null
        val code = when (kind) { 0 -> Code.START_STROKE; 1 -> Code.ADD_STROKE_POINT; else -> Code.FINISH_STROKE }
        return transact(b, code, { writeFloat(baseWidth); writeFloat(x); writeFloat(y); writeFloat(pressure); writeFloat(size); writeFloat(timeMs) }) { it.readFloat() }
    }

    /** Whether the display previews the eraser end's track, and in which style. */
    fun setEraserRawDrawing(on: Boolean, style: Int): Boolean = call(Code.SET_ERASER_RAW_DRAWING) { writeInt(if (on) 1 else 0); writeInt(style) }

    fun setBrushRawDrawing(on: Boolean): Boolean = call(Code.SET_BRUSH_RAW_DRAWING) { writeInt(if (on) 1 else 0) }

    /**
     * Whether the app's frames reach the panel. The display holds them from the first pen touch of a session (so the
     * app's drawing can't cut into the preview) until this lets them through, which also replaces the preview with the
     * app's pixels. The native note app never lets them through between strokes.
     */
    fun enablePost(on: Boolean): Boolean = call(Code.ENABLE_POST) { writeInt(-1); writeInt(if (on) 1 else 0); writeInt(ownerPid) }

    /** Off holds the app's drawing back from the panel until a repaint; the native apps leave it on. */
    fun setAutoSync(on: Boolean): Boolean = call(Code.SET_AUTO_SYNC) { writeInt(if (on) 1 else 0) }

    /** Repaints a screen rectangle (left, top, right, bottom) from the app's latest frame. */
    fun handwritingRepaint(rect: IntArray): Boolean = call(Code.HANDWRITING_REPAINT) { writeInt(0); writeIntArray(rect) }

    // ---- Refreshes ----

    /** Redraws everything, in [mode] when given (the firmware's choice otherwise). */
    fun repaintEverything(mode: UpdateMode? = null): Boolean =
        if (mode == null) call(Code.REPAINT_EVERYTHING) else call(Code.REPAINT_EVERYTHING_MODE) { writeInt(mode.code) }

    /** Refreshes a screen area in [mode]. Note the width and height: not right and bottom. */
    fun refresh(left: Int, top: Int, width: Int, height: Int, mode: UpdateMode): Boolean =
        call(Code.REFRESH_SCREEN) { writeInt(left); writeInt(top); writeInt(width); writeInt(height); writeInt(mode.code) }

    /** One full flash of the whole panel, which clears all ghosting. */
    fun applyGcOnce(): Boolean = call(Code.APPLY_GC_ONCE)

    fun waitForUpdateFinished(): Boolean = call(Code.WAIT_FOR_UPDATE)

    /**
     * A temporary update mode for everything on screen. The native apps' fast mode is [UpdateMode.AnimationQuality],
     * applied once a drag has moved 5 px and cleared, with a reset, 3 s after the last movement.
     */
    fun applyTransientUpdate(mode: UpdateMode = UpdateMode.AnimationQuality): Boolean = call(Code.APPLY_TRANSIENT_UPDATE) { writeInt(mode.code) }

    /** Ends [applyTransientUpdate]. With [reset], as the native apps' UI clears it, the screen is also cleaned up. */
    fun clearTransientUpdate(reset: Boolean = true): Boolean = call(Code.CLEAR_TRANSIENT_UPDATE) { writeInt(if (reset) 1 else 0) }

    /** Regal updates for the app's own frames (the native apps turn them on for page turns). */
    fun enableRegal(on: Boolean): Boolean = call(Code.ENABLE_REGAL) { writeInt(if (on) 1 else 0) }

    /** 0 normal, 1 the system's fast mode, 2 an app's fast mode; null when unavailable. */
    fun fastModeIndex(): Int? = binder?.let { readInt(it, Code.GET_FAST_MODE_INDEX) }

    // ---- The handwriting layer ----

    /**
     * Draws ARGB_8888 pixels ([pixels], as `Bitmap.copyPixelsToBuffer` lays them out) straight into the display's
     * handwriting layer at a screen position, while the app's frames stay held: how the native note app keeps its
     * toolbar current during a writing session. At most [MAX_BITMAP_BYTES] per call. Needs Android 13 for the blob.
     */
    fun drawHandwritingBitmap(x: Int, y: Int, width: Int, height: Int, pixels: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        if (pixels.size != width * height * 4 || pixels.size > MAX_BITMAP_BYTES) return false
        return call(Code.DRAW_HANDWRITING_BITMAP) {
            writeInt(x); writeInt(y); writeInt(width); writeInt(height); writeInt(pixels.size)
            writeBlob(pixels)
        }
    }

    // ---- Geometry ----

    /** The panel's size in its own (landscape) frame: 2480 × 1860 on NA6C. */
    fun panelSize(): Pair<Float, Float>? {
        val b = binder ?: return null
        val w = readFloat(b, Code.GET_EPD_WIDTH) ?: return null
        val h = readFloat(b, Code.GET_EPD_HEIGHT) ?: return null
        return w to h
    }

    /** The digitizer's range: 20832 × 15624 on NA6C. */
    fun digitizerSize(): Pair<Float, Float>? {
        val b = binder ?: return null
        val w = readFloat(b, Code.GET_TOUCH_WIDTH) ?: return null
        val h = readFloat(b, Code.GET_TOUCH_HEIGHT) ?: return null
        return w to h
    }

    /** A screen point in the panel's frame, as the display maps it for the current rotation. */
    fun mapToPanel(x: Float, y: Float): FloatArray? {
        val b = binder ?: return null
        return transact(b, Code.MAP_TO_EPD, { writeFloat(x); writeFloat(y) }) { r -> floatArrayOf(r.readFloat(), r.readFloat()) }
    }

    /** A digitizer point on screen, for the current rotation. */
    fun mapDigitizerToScreen(x: Float, y: Float): FloatArray? {
        val b = binder ?: return null
        return transact(b, Code.MAP_TO_VIEW, { writeFloat(x); writeFloat(y) }) { r -> floatArrayOf(r.readFloat(), r.readFloat()) }
    }

    /**
     * The panel-to-screen transform for the current rotation, as the display reports it: a row-major 3 × 3 matrix,
     * then the digitizer-to-panel scale for x and y (`[0, −1, 1860, 1, 0, 0, 0, 0, 1, 0.119, 0.119]` in portrait on NA6C).
     */
    fun panelToScreenMatrix(): FloatArray? {
        val b = binder ?: return null
        return transact(b, Code.GET_EPD_TO_VIEW_MATRIX, {}) { r ->
            val n = r.readInt()
            if (n !in 1..32) null else FloatArray(n) { r.readFloat() }
        }
    }

    // ---- Recovery ----

    /**
     * Ends any pen session and hands the display back to normal drawing, undoing everything a session may have
     * changed: exclusions, the region mode, the hold, autosync and any temporary update mode. Safe at any time.
     */
    fun release() {
        clearRegionPenConfig()
        setRegionExclude(IntArray(0))
        setRegionMode(RegionMode.Single)
        enablePost(true)
        setPenState(PenState.STOP)
        setAutoSync(true)
        clearTransientUpdate(reset = true)
        repaintEverything()
    }

    // ---- Transport ----

    /** Writes a size-prefixed record, as AIDL parcelables are: an int with the record's length, then its fields. */
    private inline fun Parcel.sized(body: Parcel.() -> Unit) {
        val start = dataPosition()
        writeInt(0)
        body()
        val end = dataPosition()
        setDataPosition(start)
        writeInt(end - start)
        setDataPosition(end)
    }

    private fun call(code: Int, write: Parcel.() -> Unit = {}): Boolean {
        val b = binder ?: return false
        return transact(b, code, write) { true } ?: false
    }

    private fun readInt(b: IBinder, code: Int): Int? = transact(b, code, {}) { it.readInt() }

    private fun readFloat(b: IBinder, code: Int): Float? = transact(b, code, {}) { it.readFloat() }

    private inline fun <T> transact(b: IBinder, code: Int, write: Parcel.() -> Unit, read: (Parcel) -> T?): T? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(TOKEN)
            data.write()
            if (!b.transact(code, data, reply, 0)) { noteFailure(code, null); null } else read(reply)
        } catch (e: Exception) {
            noteFailure(code, e)
            null
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    /** Logs the first failure of each code only, so a firmware without a call doesn't flood the logbook. */
    private fun noteFailure(code: Int, e: Exception?) {
        if (failed.add(code)) log.w("display call failed", "code" to code, "route" to route, error = e)
    }
}
