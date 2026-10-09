package app.booxultimatum.core.battery

import android.app.AppOpsManager
import android.content.Context
import android.os.DropBoxManager
import android.os.Process
import android.os.SystemClock
import app.booxultimatum.core.BatteryLog
import rikka.shizuku.Shizuku

/** The Android state that findings need. */
object SignalReader {
    private val RESTART_TAGS = listOf("system_server_crash", "SYSTEM_RESTART")

    /** A restart this long before the last boot still counts: a crash can be what rebooted the tablet. */
    private const val BEFORE_BOOT_MS = 12 * 3_600_000L

    /** Blocking: call off the main thread. [Signals.heldLocks] stays empty here; it comes from [DeepLog]. */
    fun read(context: Context): Signals = Signals(
        hubBackgroundAllowed = hubBackgroundAllowed(context),
        recordingEnabled = BatteryLog.enabled(context),
        shizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false),
        restartAtMs = restartAtMs(context),
    )

    private fun hubBackgroundAllowed(context: Context): Boolean? = runCatching {
        val mode = context.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow("android:run_any_in_background", Process.myUid(), context.packageName)
        mode != AppOpsManager.MODE_IGNORED
    }.getOrNull()

    // The dropbox service accepts READ_LOGS, which the owner grants over adb (T1); lint only knows READ_DROPBOX_DATA, a system-only permission.
    @android.annotation.SuppressLint("MissingPermission")
    private fun restartAtMs(context: Context): Long? = runCatching {
        val dropBox = context.getSystemService(DropBoxManager::class.java)
        val bootMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        newestRestart(RESTART_TAGS, bootMs - BEFORE_BOOT_MS) { tag, afterMs ->
            dropBox.getNextEntry(tag, afterMs)?.let { entry -> try { entry.timeMillis } finally { entry.close() } }
        }
    }.getOrNull()
}

/**
 * The newest time of an entry with one of [tags] after [afterMs], or null. [nextAfter] gives the time of the first entry
 * of a tag after a time, or null when there is none.
 */
internal fun newestRestart(tags: List<String>, afterMs: Long, nextAfter: (tag: String, afterMs: Long) -> Long?): Long? {
    var newest: Long? = null
    for (tag in tags) {
        var after = afterMs
        while (true) {
            val at = nextAfter(tag, after) ?: break
            if (at <= after) break
            newest = maxOf(newest ?: at, at)
            after = at
        }
    }
    return newest
}
