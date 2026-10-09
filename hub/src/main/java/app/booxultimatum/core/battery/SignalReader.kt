package app.booxultimatum.core.battery

import android.content.Context

/** OWNER: B2. Reads the Android state findings need. */
object SignalReader {
    /** Blocking: call off the main thread. [Signals.heldLocks] stays empty here; it comes from [DeepLog]. */
    fun read(context: Context): Signals = TODO("B2")
}