package app.booxultimatum.kit.ink.input

import android.os.IBinder
import android.os.Parcel
import app.booxultimatum.kit.log.Logbook

/**
 * Finger touch on the capacitive panel, switched off in chosen screen areas while the pen stays usable: palm
 * rejection done by the firmware rather than by the app. BOOX adds these calls to Android's input manager
 * (`IInputManager` transactions 82 to 85 on FW 4.3, read from the decompiled framework).
 *
 * Whether an ordinary app may make them is *[verify]*: [available] is false until one call has gone through. The
 * suppression is global until reset, so every owner must call [reset] when it stops, and a lease should record it
 * (see [app.booxultimatum.kit.ink.session.InkLease]) so a later process can undo it after a crash.
 */
object TouchPanel {
    private val log = Logbook.logger("ink.touch")
    private const val TOKEN = "android.hardware.input.IInputManager"
    private const val RESET_CTP_REGION = 82
    private const val IS_CTP_REGION = 83
    private const val SET_CTP_DISABLE_REGION = 84
    private const val ENABLE_CTP_REGION = 85

    @Volatile private var binder: IBinder? = null
    @Volatile var available: Boolean? = null
        private set

    /** Whether finger touch is currently switched off somewhere; true while this process has set a region. */
    @Volatile var suppressing: Boolean = false
        private set

    @android.annotation.SuppressLint("PrivateApi")
    private fun input(): IBinder? = binder ?: runCatching {
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "input") as IBinder?
    }.getOrNull()?.also { binder = it }

    /**
     * Switches finger touch off inside [disable] except inside [keep], both as left, top, right, bottom quadruples in
     * screen pixels. Returns whether the firmware accepted it.
     */
    fun suppress(disable: IntArray, keep: IntArray = IntArray(0)): Boolean {
        val ok = aidl(SET_CTP_DISABLE_REGION) { writeIntArray(disable); writeIntArray(keep) } &&
            aidl(ENABLE_CTP_REGION) { writeInt(1) }
        if (available != ok) { available = ok; log.i("finger touch control", "available" to ok) }
        suppressing = ok
        return ok
    }

    /** Finger touch back everywhere. Safe to call at any time. */
    fun reset(): Boolean {
        val ok = aidl(RESET_CTP_REGION) {}
        if (ok) suppressing = false
        return ok
    }

    /** Whether any area has finger touch switched off, for anyone; null when it can't be asked. */
    fun isSuppressed(): Boolean? {
        val b = input() ?: return null
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(TOKEN)
            if (!b.transact(IS_CTP_REGION, data, reply, 0)) null else { reply.readException(); reply.readInt() != 0 }
        } catch (e: Exception) { null } finally { data.recycle(); reply.recycle() }
    }

    private fun aidl(code: Int, write: Parcel.() -> Unit): Boolean {
        val b = input() ?: return false
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(TOKEN)
            data.write()
            if (!b.transact(code, data, reply, 0)) false else { reply.readException(); true }
        } catch (e: Exception) {
            if (available != false) log.w("finger touch call refused", "code" to code, error = e)
            available = false
            false
        } finally { data.recycle(); reply.recycle() }
    }
}
