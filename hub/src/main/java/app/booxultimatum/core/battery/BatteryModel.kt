package app.booxultimatum.core.battery

import android.content.Context
import app.booxultimatum.core.BatteryLog
import app.booxultimatum.core.BatterySnapshot
import app.booxultimatum.core.Journal

/**
 * Everything one page of the Battery section needs, read once: the log as rows, the timeline of the window and of the
 * last 30 days, what is normal here, the verdict, and the time left. Pages call [BatteryModel.load] inside
 * `rememberReading` and share the result between tabs through the short cache.
 */
data class BatteryView(
    val nowMs: Long,
    val windowMs: Long,
    val rows: List<BatteryRow>,
    /** The window's own episodes, clipped to it. */
    val timeline: Timeline,
    /** Up to 30 days, for baselines, trends and before-and-after. */
    val full: Timeline,
    val baseline: Baseline,
    val signals: Signals,
    val verdict: Verdict,
    val estimate: Estimate?,
    val foreground: List<ForegroundSpan>,
    val marks: List<Mark>,
    val storedMah: Double?,
)

object BatteryModel {
    const val HISTORY_DAYS = 30
    private const val DAY_MS = 86_400_000L
    private const val CACHE_MS = 20_000L

    private data class Key(val windowMs: Long, val logStamp: Long)

    @Volatile private var cached: Triple<Key, Long, BatteryView>? = null

    /** Blocking: call off the main thread. [fresh] skips the 20-second cache (the Read again key). */
    fun load(context: Context, windowMs: Long, fresh: Boolean = false, nowMs: Long = System.currentTimeMillis()): BatteryView {
        val ctx = context.applicationContext
        val stamp = BatteryLog.dir(ctx).listFiles().orEmpty().sumOf { f -> f.lastModified() + f.length() }
        val key = Key(windowMs, stamp)
        cached?.let { (k, at, view) -> if (!fresh && k == key && nowMs - at < CACHE_MS) return view }
        val since = nowMs - maxOf(HISTORY_DAYS * DAY_MS, windowMs)
        val rows = BatteryRows.read(ctx, since)
        val foreground = runCatching { ForegroundReader.read(ctx, nowMs - 7 * DAY_MS, nowMs) }.getOrDefault(emptyList())
        val full = Timelines.build(rows, nowMs, foreground)
        val baseline = Baselines.of(full, nowMs)
        val signals = runCatching { SignalReader.read(ctx) }.getOrDefault(Signals(null, true, false, null))
        val window = full.window(nowMs - windowMs, nowMs)
        val verdict = Findings.evaluate(window, baseline, signals, nowMs)
        val snapshot = runCatching { BatterySnapshot.read(ctx) }.getOrNull()
        val stored = snapshot?.chargeCounterMah ?: snapshot?.let { full.capacityMah * it.levelPct / 100.0 }
        val estimate = stored?.let { Estimates.timeLeft(full, baseline, it, nowMs) }
        val marks = (Marks.fromRows(rows) + runCatching { Marks.fromJournal(Journal.entries(ctx)) }.getOrDefault(emptyList())).sortedBy { it.epoch }
        return BatteryView(nowMs, windowMs, rows, window, full, baseline, signals, verdict, estimate, foreground, marks, stored)
            .also { cached = Triple(key, nowMs, it) }
    }
}

/** The part of the timeline inside [fromMs, toMs]: episodes clipped with their charge and levels in proportion, segments that overlap it. */
fun Timeline.window(fromMs: Long, toMs: Long = this.toMs): Timeline {
    fun clip(e: Episode): Episode? {
        val start = maxOf(e.startMs, fromMs)
        val end = minOf(e.endMs, toMs)
        if (end <= start) return null
        if (start == e.startMs && end == e.endMs) return e
        val span = (e.endMs - e.startMs).toDouble().coerceAtLeast(1.0)
        fun levelAt(t: Long) = Math.round(e.level0 + (e.level1 - e.level0) * ((t - e.startMs) / span)).toInt()
        return e.copy(startMs = start, endMs = end, level0 = levelAt(start), level1 = levelAt(end), mah = e.mah?.let { it * (end - start) / span })
    }
    return copy(
        episodes = episodes.mapNotNull(::clip),
        segments = segments.filter { it.endMs > fromMs && it.startMs < toMs },
        fromMs = fromMs,
        toMs = toMs,
    )
}
