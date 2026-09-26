package app.booxultimatum.core.ink

import android.os.IBinder
import android.os.Parcel
import android.os.Process
import app.booxultimatum.core.exec.Privileged
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * A clean-room client for the Onyx handwriting transactions on SurfaceFlinger (firmware 4.3, see
 * `knowledge/experiments.md`). SurfaceFlinger reads the pen itself and paints the stroke straight onto the panel;
 * this only arms it, shapes the stroke, and asks for the repaint that swaps the preview for the app's own pixels.
 *
 * Every call leaves from one process, and SurfaceFlinger accepts them from an ordinary app: the session's owner pid
 * can be BooxUltimatum's own (verified 2026-09-26 against Sketchbook's pid; both worked).
 */
object SurfaceInk {
    private const val TOKEN = "android.ui.ISurfaceComposer"
    private const val SET_PEN_STATE = 16711693
    private const val SET_REGION_LIMIT = 16711694
    private const val SET_REGION_EXCLUDE = 16711714
    private const val SET_STROKE_COLOR = 16711686
    private const val SET_STROKE_WIDTH = 16711687
    private const val SET_STROKE_STYLE = 16711688
    private const val HANDWRITING_REPAINT = 1048647
    private const val REPAINT_EVERYTHING = 16711700
    private const val SET_AUTO_SYNC = 1048722
    private const val GET_PEN_STATE = 1048643
    private const val ENABLE_POST = 16711692

    const val STOP = 0
    const val START = 1
    const val DRAW = 2
    const val PAUSE = 3

    enum class Route { Direct, Shizuku }

    @Volatile private var binder: IBinder? = null
    @Volatile var route: Route? = null
        private set

    /** The pid SurfaceFlinger sees as the caller: ours, or Shizuku's server when calls go through it. */
    @Volatile private var ownerPid = Process.myPid()

    /** Finds SurfaceFlinger, from this process when the platform allows it, otherwise through Shizuku. */
    // ServiceManager is hidden API; it works for SurfaceFlinger on FW 4.3, and Shizuku's wrapper covers firmware that refuses it.
    @android.annotation.SuppressLint("PrivateApi")
    @Synchronized
    fun connect(serverPid: () -> Int?): Route? {
        if (binder?.isBinderAlive == true && route != null) return route
        val direct = runCatching {
            Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "SurfaceFlinger") as IBinder?
        }.getOrNull()
        if (direct != null && probe(direct) != null) {
            binder = direct; route = Route.Direct; ownerPid = Process.myPid()
            return route
        }
        if (Privileged.ready()) {
            val wrapped = runCatching { ShizukuBinderWrapper(SystemServiceHelper.getSystemService("SurfaceFlinger")) }.getOrNull()
            val pid = serverPid()
            if (wrapped != null && pid != null && probe(wrapped) != null) {
                binder = wrapped; route = Route.Shizuku; ownerPid = pid
                return route
            }
        }
        binder = null; route = null
        return null
    }

    private fun probe(b: IBinder): Int? = runCatching {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(TOKEN)
            if (!b.transact(GET_PEN_STATE, data, reply, 0)) null else reply.readInt()
        } finally { data.recycle(); reply.recycle() }
    }.getOrNull()

    private fun call(code: Int, write: Parcel.() -> Unit = {}): Boolean {
        val b = binder ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(TOKEN)
            data.write()
            // Synchronous like the firmware's own calls, so a repaint can never overtake the pen state it follows.
            b.transact(code, data, reply, 0)
        } catch (_: Exception) {
            false
        } finally { data.recycle(); reply.recycle() }
    }

    /** SurfaceFlinger's own record of the pen session, or null when it can't be asked. */
    fun penState(): Int? = binder?.let { probe(it) }

    fun setPenState(state: Int) = call(SET_PEN_STATE) { writeInt(state); writeInt(ownerPid) }

    /** Rectangles as left, top, right, bottom in screen pixels of the current rotation. */
    fun setRegion(rect: IntArray) = call(SET_REGION_LIMIT) { writeInt(0); writeIntArray(rect) }

    fun setExclude(rects: IntArray) = call(SET_REGION_EXCLUDE) { writeInt(0); writeIntArray(rects) }

    fun setStroke(widthPx: Float, argb: Int, style: Int = 0) {
        call(SET_STROKE_WIDTH) { writeFloat(widthPx) }
        call(SET_STROKE_COLOR) { writeInt(argb) }
        call(SET_STROKE_STYLE) { writeInt(style) }
    }

    /** Off holds the app's own drawing back from the panel until [repaint], so the preview isn't doubled. */
    fun setAutoSync(on: Boolean) = call(SET_AUTO_SYNC) { writeInt(if (on) 1 else 0) }

    /**
     * Whether app frames reach the panel. Held off (false) while the pen draws, so the app's own stroke can't cut into
     * the preview; let through again (true) after the lift, which is also what swaps the preview for the app's real
     * pixels while the session stays open. Neither [repaint] nor [repaintEverything] does that swap (tested on FW 4.3).
     */
    fun enablePost(on: Boolean) = call(ENABLE_POST) { writeInt(-1); writeInt(if (on) 1 else 0); writeInt(ownerPid) }

    fun repaint(rect: IntArray) = call(HANDWRITING_REPAINT) { writeInt(0); writeIntArray(rect) }

    fun repaintEverything() = call(REPAINT_EVERYTHING)

    /** Ends any session and hands the panel back to normal drawing. Safe to call at any time. */
    fun release() {
        enablePost(true)
        setPenState(STOP)
        setAutoSync(true)
        repaintEverything()
    }
}
