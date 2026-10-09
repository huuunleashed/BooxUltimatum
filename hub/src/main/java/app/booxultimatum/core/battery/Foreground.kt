package app.booxultimatum.core.battery

import android.content.Context

/** OWNER: B2. The foreground app over time, from UsageStats events (needs usage access; empty without it). */
object ForegroundReader {
    /** Spans where one package was resumed and not yet paused, clipped to [fromMs, toMs]. Blocking: call off the main thread. */
    fun read(context: Context, fromMs: Long, toMs: Long): List<ForegroundSpan> = TODO("B2")
}