package app.booxultimatum.core.battery

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/** The foreground app over time, from UsageStats events (needs usage access; empty without it). */
object ForegroundReader {
    /** Events from five minutes before the window, so an app already in front when the window opens is seen. */
    private const val LEAD_MS = 5 * 60_000L

    /** Spans where one package was resumed and not yet paused, clipped to [fromMs, toMs]. Blocking: call off the main thread. */
    fun read(context: Context, fromMs: Long, toMs: Long): List<ForegroundSpan> =
        spans(events(context, fromMs - LEAD_MS, toMs), fromMs, toMs)

    private fun events(context: Context, fromMs: Long, toMs: Long): List<UsageEvent> = runCatching {
        val stream = context.getSystemService(UsageStatsManager::class.java).queryEvents(fromMs, toMs)
        val event = UsageEvents.Event()
        buildList {
            while (stream.hasNextEvent()) {
                stream.getNextEvent(event)
                if (event.eventType == RESUMED || event.eventType == PAUSED) add(UsageEvent(event.packageName, event.eventType, event.timeStamp))
            }
        }
    }.getOrDefault(emptyList())
}

/** A package's activity resumed or paused, as UsageStats reports it. */
internal data class UsageEvent(val pkg: String, val type: Int, val ms: Long)

/**
 * The spans in front, from resume and pause events. One app is in front at a time: a resume of another app ends the
 * previous span there, a resume of the app already in front changes nothing, and a pause of an app that isn't in front
 * is ignored. A span still open at [toMs] ends there. Spans are clipped to the window, and empty ones are dropped.
 */
internal fun spans(events: List<UsageEvent>, fromMs: Long, toMs: Long): List<ForegroundSpan> {
    val out = mutableListOf<ForegroundSpan>()
    var front: String? = null
    var since = 0L
    fun close(end: Long) {
        val pkg = front ?: return
        val start = maxOf(since, fromMs)
        val stop = minOf(end, toMs)
        if (stop > start) out += ForegroundSpan(pkg, start, stop)
    }
    for (e in events.sortedBy { it.ms }) {
        when (e.type) {
            RESUMED -> if (e.pkg != front) {
                close(e.ms)
                front = e.pkg
                since = e.ms
            }
            PAUSED -> if (e.pkg == front) {
                close(e.ms)
                front = null
            }
        }
    }
    close(toMs)
    return out.sortedBy { it.startMs }
}

private const val RESUMED = 1 // UsageEvents.Event.ACTIVITY_RESUMED
private const val PAUSED = 2 // UsageEvents.Event.ACTIVITY_PAUSED
