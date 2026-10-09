package app.booxultimatum.core.battery

import android.content.Context

/** OWNER: B2. Reads the three-hourly `deep-*.jsonl` snapshots. */
object DeepLog {
    /**
     * Wake sources between [fromMs] and [toMs]: wake lock acquisitions and CPU time summed from the snapshots' deltas, and
     * alarm wakeups as the difference between consecutive snapshots of one boot (cumulative since boot; a drop means a reboot).
     * System tags are grouped under pkg "android". Largest first. Empty when the folder holds no snapshots in the window.
     */
    fun wakeSources(context: Context, fromMs: Long, toMs: Long): List<WakeSource> = TODO("B2")
}