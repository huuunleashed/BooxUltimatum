package app.booxultimatum.core.ink

import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.view.View
import app.booxultimatum.core.exec.Privileged
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

/**
 * The Onyx handwriting path on SurfaceFlinger: SurfaceFlinger reads the pen itself and paints the stroke straight onto
 * the panel; this only arms it, shapes the stroke, and lets the app's own frames through again to swap the preview.
 *
 * It prefers the firmware's own helper, `android.onyx.ViewUpdateHelper`. That is the class Onyx's public pen SDK
 * (`onyxsdk-pen`, documented in onyx-intl/OnyxAndroidDemo) calls on current devices, so each firmware supplies its own
 * transaction codes. The raw transactions below, read from FW 4.3, are the fallback when that class can't be reached.
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
    private const val GET_MAX_TOUCH_PRESSURE = 1048618

    const val STOP = 0
    const val START = 1
    const val DRAW = 2
    const val PAUSE = 3

    enum class Route { Firmware, Direct, Shizuku }

    @Volatile private var binder: IBinder? = null
    @Volatile private var fw: Firmware? = null
    @Volatile var route: Route? = null
        private set

    /** The pid SurfaceFlinger sees as the caller: ours, or Shizuku's server when calls go through it. */
    @Volatile private var ownerPid = Process.myPid()

    /** The firmware helper's static methods, looked up once. Null when the class or any method is missing. */
    private class Firmware(c: Class<*>) {
        val getPenState: Method = c.getMethod("getPenState")
        val setPenState: Method = c.getMethod("setScreenHandWritingPenState", Int::class.javaPrimitiveType)
        val setRegion: Method = c.getMethod("setScreenHandWritingRegionLimit", View::class.java, IntArray::class.java)
        val setExclude: Method = c.getMethod("setScreenHandWritingRegionExclude", View::class.java, IntArray::class.java)
        val setWidth: Method = c.getMethod("setStrokeWidth", Float::class.javaPrimitiveType)
        val setColor: Method = c.getMethod("setStrokeColor", Int::class.javaPrimitiveType)
        val setStyle: Method = c.getMethod("setStrokeStyle", Int::class.javaPrimitiveType)
        val enablePost: Method = c.getMethod("enablePost", Int::class.javaPrimitiveType)
        val setAutoSync: Method = c.getMethod("setAutoSyncBufEnable", Boolean::class.javaPrimitiveType)
        val repaint: Method = c.getMethod(
            "handwritingRepaint", View::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        val repaintEverything: Method = c.getMethod("repaintEverything")
    }

    private inline fun fwCall(block: Firmware.() -> Any?): Boolean? = fw?.let { f -> runCatching { f.block() }.isSuccess }

    /**
     * Lifts Android's hidden-API check for this process, the way Onyx's own SDK does before it reaches
     * `android.onyx.*` (`com.onyx.android.sdk.utils.ReflectUtil`): `VMRuntime.setHiddenApiExemptions(["L"])` reached
     * through `Class.getDeclaredMethod` itself, so the lookup comes from the framework rather than the app.
     * Returns whether it took; without it the firmware route is simply skipped.
     */
    @android.annotation.SuppressLint("DiscouragedPrivateApi")
    private fun exemptHiddenApi(): Boolean = runCatching {
        val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
        val getDeclared = Class::class.java.getDeclaredMethod("getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java)
        val vmRuntime = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
        val getRuntime = getDeclared.invoke(vmRuntime, "getRuntime", null) as Method
        val setExemptions = getDeclared.invoke(vmRuntime, "setHiddenApiExemptions", arrayOf<Class<*>>(Array<String>::class.java)) as Method
        setExemptions.invoke(getRuntime.invoke(null), arrayOf("L"))
        true
    }.getOrDefault(false)

    @Volatile private var exempted: Boolean? = null

    /**
     * Finds the handwriting path: the firmware helper first, then SurfaceFlinger from this process, then through
     * Shizuku. The firmware route is only taken when it answers a harmless read.
     */
    // ViewUpdateHelper and ServiceManager are reached by reflection, as Onyx's own SDK does; Shizuku covers firmware that refuses it.
    @android.annotation.SuppressLint("PrivateApi")
    @Synchronized
    fun connect(serverPid: () -> Int?): Route? {
        if (route == Route.Firmware && fw != null) return route
        if (binder?.isBinderAlive == true && route != null) return route
        if (exempted == null) exempted = exemptHiddenApi().also { android.util.Log.i("InstantInk", "hidden-API exemption: $it") }
        val firmware = runCatching { Firmware(Class.forName("android.onyx.ViewUpdateHelper")) }.getOrNull()
        if (firmware != null && runCatching { firmware.getPenState.invoke(null) as Int }.isSuccess) {
            fw = firmware; binder = null; route = Route.Firmware; ownerPid = Process.myPid()
            return route
        }
        fw = null
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
    }.getOrNull()?.takeIf { maxPressure(b) > 0f }

    /**
     * The pen's pressure range, a device constant (4096.0 on NA6C FW 4.3, 4095 on a 2022 Tab Ultra C per CalliPlus's
     * notes). A plausible answer shows the codes mean what we think on this firmware, not merely that they transact.
     */
    private fun maxPressure(b: IBinder): Float = runCatching {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(TOKEN)
            if (!b.transact(GET_MAX_TOUCH_PRESSURE, data, reply, 0)) 0f else reply.readFloat()
        } finally { data.recycle(); reply.recycle() }
    }.getOrDefault(0f).takeIf { it in 1f..100_000f } ?: 0f

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
    fun penState(): Int? {
        fw?.let { f -> return runCatching { f.getPenState.invoke(null) as Int }.getOrNull() }
        return binder?.let { probe(it) }
    }

    fun setPenState(state: Int) = fwCall { setPenState.invoke(null, state) }
        ?: call(SET_PEN_STATE) { writeInt(state); writeInt(ownerPid) }

    /** Rectangles as left, top, right, bottom in screen pixels. */
    fun setRegion(rect: IntArray) = fwCall { setRegion.invoke(null, null, rect) }
        ?: call(SET_REGION_LIMIT) { writeInt(0); writeIntArray(rect) }

    fun setExclude(rects: IntArray) = fwCall { setExclude.invoke(null, null, rects) }
        ?: call(SET_REGION_EXCLUDE) { writeInt(0); writeIntArray(rects) }

    /**
     * Style first: choosing a style loads that style's default width, so a width sent before it is lost (CalliPlus's
     * notes, and the order in Onyx's own demo). Send after [START], as the demo does.
     */
    fun setStroke(widthPx: Float, argb: Int, style: Int = 0) {
        fwCall { setStyle.invoke(null, style); setWidth.invoke(null, widthPx); setColor.invoke(null, argb) }?.let { return }
        call(SET_STROKE_STYLE) { writeInt(style) }
        call(SET_STROKE_WIDTH) { writeFloat(widthPx) }
        call(SET_STROKE_COLOR) { writeInt(argb) }
    }

    /** Off holds the app's own drawing back from the panel until [repaint], so the preview isn't doubled. */
    fun setAutoSync(on: Boolean) = fwCall { setAutoSync.invoke(null, on) }
        ?: call(SET_AUTO_SYNC) { writeInt(if (on) 1 else 0) }

    /**
     * Whether app frames reach the panel. Held off (false) while the pen draws, so the app's own stroke can't cut into
     * the preview; let through again (true) after the lift, which is also what swaps the preview for the app's real
     * pixels while the session stays open. Neither [repaint] nor [repaintEverything] does that swap (tested on FW 4.3).
     */
    fun enablePost(on: Boolean) = fwCall { enablePost.invoke(null, if (on) 1 else 0) }
        ?: call(ENABLE_POST) { writeInt(-1); writeInt(if (on) 1 else 0); writeInt(ownerPid) }

    fun repaint(rect: IntArray) = fwCall { repaint.invoke(null, null, rect[0], rect[1], rect[2], rect[3]) }
        ?: call(HANDWRITING_REPAINT) { writeInt(0); writeIntArray(rect) }

    fun repaintEverything() = fwCall { repaintEverything.invoke(null) } ?: call(REPAINT_EVERYTHING)

    /** Ends any session and hands the panel back to normal drawing. Safe to call at any time. */
    fun release() {
        enablePost(true)
        setPenState(STOP)
        setAutoSync(true)
        repaintEverything()
    }
}
