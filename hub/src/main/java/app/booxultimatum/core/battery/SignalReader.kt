package app.booxultimatum.core.battery

import android.app.AppOpsManager
import android.content.Context
import android.os.DropBoxManager
import android.os.Process
import android.os.SystemClock
import app.booxultimatum.core.BatteryLog
import app.booxultimatum.kit.log.Logbook
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

/** The Android state that findings need. */
object SignalReader {
    private val RESTART_TAGS = listOf("system_server_crash", "SYSTEM_RESTART")
    private val dropboxFailureLogged = AtomicBoolean(false)

    /**
     * Says once per process that the dropbox can't be read. For its first nine days this was silent: a build that targets
     * API 35 or later needs READ_DROPBOX_DATA on Android 16, READ_LOGS no longer opens it, and the denial became a null.
     */
    internal fun noteDropboxFailure(error: Throwable) {
        if (dropboxFailureLogged.compareAndSet(false, true)) {
            Logbook.logger("battery").w("the dropbox can't be read, so a restart of Android goes unnoticed", error = error)
        }
    }

    /** A restart this long before the last boot still counts: a crash can be what rebooted the tablet. */
    private const val BEFORE_BOOT_MS = 12 * 3_600_000L

    /** Blocking: call off the main thread. [Signals.heldLocks] stays empty here; it comes from [DeepLog]. */
    fun read(context: Context): Signals = Signals(
        hubBackgroundAllowed = hubBackgroundAllowed(context),
        recordingEnabled = BatteryLog.enabled(context),
        shizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false),
        restartAtMs = restartAtMs(context),
        bootAtMs = System.currentTimeMillis() - SystemClock.elapsedRealtime(),
    )

    private fun hubBackgroundAllowed(context: Context): Boolean? = runCatching {
        val mode = context.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow("android:run_any_in_background", Process.myUid(), context.packageName)
        mode != AppOpsManager.MODE_IGNORED
    }.getOrNull()

    // The dropbox service wants READ_DROPBOX_DATA from an app that targets API 35 or later on Android 16 and READ_LOGS before
    // that; the owner grants both over adb (T1). Lint only knows READ_DROPBOX_DATA, a system-only permission.
    @android.annotation.SuppressLint("MissingPermission")
    internal fun restartAtMs(context: Context): Long? = runCatching {
        val dropBox = context.getSystemService(DropBoxManager::class.java)
        val bootMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        newestRestart(RESTART_TAGS, bootMs - BEFORE_BOOT_MS) { tag, afterMs ->
            dropBox.getNextEntry(tag, afterMs)?.let { entry -> try { entry.timeMillis } finally { entry.close() } }
        }
    }.onFailure { noteDropboxFailure(it) }.getOrNull()
    /** The dropbox entry of a restart at exactly [atMs] as (tag, its first 6 000 characters), or null. Blocking. */
    @android.annotation.SuppressLint("MissingPermission")
    internal fun restartEntry(context: Context, atMs: Long): Pair<String, String>? = runCatching {
        val dropBox = context.getSystemService(DropBoxManager::class.java)
        for (tag in RESTART_TAGS) {
            val entry = dropBox.getNextEntry(tag, atMs - 1) ?: continue
            try {
                if (entry.timeMillis == atMs) return@runCatching tag to (entry.getText(6_000) ?: "")
            } finally {
                entry.close()
            }
        }
        null
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
