package app.booxultimatum.core.sleep

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import androidx.core.graphics.withClip
import app.booxultimatum.core.sleep.SleepFaces.agenda
import app.booxultimatum.core.sleep.SleepFaces.legend
import app.booxultimatum.core.sleep.SleepFaces.stackedRow
import java.time.LocalDate
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Faces about the day as a whole: a dashboard of instrument tiles, the day on a 24-hour ring, a timeline, the year,
 * the sky and a broadsheet front page. They read only what the tablet knows for sure: the calendar when it's allowed,
 * the home screen's cached weather with its age, the sun for the weather city and the moon worked out locally.
 */
internal object DayFaces {
    private fun fmt(t: String, vararg a: Any) = String.format(Locale.getDefault(), t, *a)

    private fun SleepPage.pct(k: Float) = fmt("%d %%", (k * 100).toInt().coerceIn(0, 100))

    /** The readings most day faces add: time asleep, the sun, the next event and the battery. */
    private fun SleepPage.dayReadings(sun: Boolean): List<Pair<String, String>> {
        val live = data.live
        val w = data.words
        return buildList {
            if (spec.shows(SleepElement.Asleep)) {
                if (live.live) add(data.labels.asleep to listOfNotNull(live.asleepFor, fmt(w.since, live.sleptAt)).joinToString("  ·  "))
                else add(data.putDownLead to data.putDownTime)
            }
            val sky = data.sky
            if (sun && sky != null) when {
                sky.sunrise != null && sky.sunset != null -> add(w.daylight to "${sky.sunrise} – ${sky.sunset}")
                sky.polar == 1 -> add(w.daylight to w.polarDay)
                sky.polar == -1 -> add(w.daylight to w.polarNight)
            }
            if (spec.shows(SleepElement.Agenda) && data.calendarAllowed) {
                add(data.labels.next to (live.countdown ?: data.events.firstOrNull()?.let { "${it.title}  ·  ${it.whenLabel}" } ?: data.labels.nothingPlanned))
            }
            if (spec.shows(SleepElement.Battery)) add(data.labels.battery to (live.used?.takeIf { !data.charging }?.let { "${data.batteryLine}  ·  $it" } ?: data.batteryLine))
        }
    }

    // ---------- Dashboard ----------

    private class Tile(val span: Int, val weight: Float, val draw: (RectF) -> Unit)

    /** A grid of instrument tiles, chosen on the Sleep page; each tile says one thing well, and none is invented. */
    fun dashboard(p: SleepPage) {
        with(p) {
            val f = frame()
            val live = data.live
            val w = data.words
            val t = hm(FaceOptions.DASH_HOURS)
            val on = spec.optionSet(FaceOptions.DASH_TILES)
            val pd = s * 0.032f
            val cols = if (landscape) 4 else 3
            val tiles = mutableListOf<Tile>()
            fun frameOf(r: RectF, title: String): Float {
                if (!dry) {
                    val rim = max(1f, s * 0.0028f)
                    canvas.drawRoundRect(RectF(r.left + rim / 2, r.top + rim / 2, r.right - rim / 2, r.bottom - rim / 2), s * 0.022f, s * 0.022f, stroke(ink, rim))
                }
                return legend(clip(upperL(title), paint(strong, s * 0.021f, tracking = 0.14f), r.width() - 2 * pd), r.left + pd, r.top + pd, s * 0.021f)
            }
            val detailSize = s * 0.027f
            val detailRoom = cap(paint(body, detailSize)) + s * 0.03f
            val gaugeRoom = s * 0.05f
            /**
             * One value size for every small tile, from the tile's height, so values line up across a row whatever
             * each tile holds: room is kept for a gauge and a detail line even where a tile has neither.
             */
            fun valueSize(r: RectF, lb: Float) = ((r.bottom - pd - detailRoom - gaugeRoom - lb - s * 0.035f) / 0.72f).coerceIn(s * 0.03f, s * 0.11f)
            /** A small line set to fit: up to a fifth smaller, then cut. */
            fun line(t2: String, x: Float, capTop: Float, maxW: Float, size: Float, tf: android.graphics.Typeface = body) {
                if (t2.isEmpty()) return
                val probe = paint(tf, size)
                val sz = max(size * 0.8f, min(size, size * maxW / max(1f, width(probe, t2))))
                val lp = paint(tf, sz, figures = true)
                text(clip(t2, lp, maxW), x, capTop, lp)
            }
            fun detail(r: RectF, t2: String) = line(t2, r.left + pd, r.bottom - pd - cap(paint(body, detailSize)), r.width() - 2 * pd, detailSize)
            fun value(r: RectF, lb: Float, v: String, figures: Boolean = true): Float {
                val size = min(if (figures) fitFigures(v, display, r.width() - 2 * pd, s * 0.11f) else fit(v, display, r.width() - 2 * pd, s * 0.11f), valueSize(r, lb))
                return text(v, r.left + pd, lb + s * 0.035f, paint(display, size, figures = figures))
            }
            tiles += Tile(2, if (landscape) 1.35f else 1.15f) { r ->
                val lb = frameOf(r, data.weekday)
                val amP = paint(strong, s * 0.034f, tracking = 0.1f)
                val amW = if (t.amPm.isEmpty()) 0f else width(amP, upperL(t.amPm)) + s * 0.03f
                val size = min(fitFigures(t.time, display, r.width() - 2 * pd - amW, s * 0.3f, -0.03f), (r.bottom - pd - lb - s * 0.035f) / 0.72f)
                val tp = paint(display, size, figures = true, tracking = -0.03f)
                val base = text(t.time, r.left + pd, lb + s * 0.035f, tp)
                if (t.amPm.isNotEmpty()) text(upperL(t.amPm), r.left + pd + width(tp, t.time) + s * 0.03f, base - cap(amP), amP)
            }
            tiles += Tile(1, 1f) { r ->
                val lb = frameOf(r, w.date)
                val base = value(r, lb, data.dateShort, figures = false)
                line(fmt(w.week, data.week), r.left + pd, base + s * 0.03f, r.width() - 2 * pd, s * 0.028f, strong)
                detail(r, fmt(w.dayOfYear, data.dayOfYear, data.daysInYear))
            }
            if ("battery" in on) tiles += Tile(1, 1f) { r ->
                val lb = frameOf(r, data.labels.battery)
                val base = value(r, lb, if (data.batteryKnown) fmt("%d %%", data.battery) else data.batteryText)
                if (data.batteryKnown) gauge(r.left + pd, r.right - pd, base + s * 0.028f, s * 0.018f, data.battery / 100f)
                detail(r, if (data.charging) data.labels.charging else live.used ?: "")
            }
            if ("asleep" in on) tiles += Tile(1, 1f) { r ->
                val lb = frameOf(r, if (live.live) data.labels.asleep else data.putDownLead)
                value(r, lb, if (live.live) live.asleepFor ?: data.labels.justPutDown else data.putDownTime, figures = false)
                if (live.live) detail(r, fmt(w.since, live.sleptAt))
            }
            if ("alarm" in on) tiles += Tile(1, 1f) { r ->
                val lb = frameOf(r, data.labels.alarm)
                val v = live.nextAlarm
                if (v != null) value(r, lb, v) else line(w.alarmNone, r.left + pd, lb + s * 0.04f, r.width() - 2 * pd, s * 0.036f, strong)
            }
            if ("agenda" in on && data.calendarAllowed) tiles += Tile(2, 1f) { r ->
                val lb = frameOf(r, data.labels.next)
                agenda(r.left + pd, r.right - pd, lb + s * 0.04f, r.bottom - pd, s * 0.032f, 3)
            }
            if ("day" in on) tiles += Tile(1, 1f) { r ->
                val lb = frameOf(r, w.day)
                val k = dayMinute() / 1440f
                val base = value(r, lb, pct(k))
                gauge(r.left + pd, r.right - pd, base + s * 0.028f, s * 0.018f, k)
                detail(r, fmt(w.leftToday, w.duration(1440 - dayMinute())))
            }
            val wx = data.weather
            if ("weather" in on && wx != null) tiles += Tile(1, 1f) { r ->
                // The reading's age is always on the tile, so the figure is never taken for a fresh one.
                val lb = frameOf(r, wx.place)
                val v = wx.now ?: wx.range ?: wx.sky
                val base = value(r, lb, v, figures = false)
                line(listOfNotNull(wx.sky.takeIf { v != it }, wx.range.takeIf { wx.now != null }).joinToString("  ·  "), r.left + pd, base + s * 0.03f, r.width() - 2 * pd, s * 0.028f, strong)
                detail(r, wx.asOf)
            }
            val sky = data.sky
            if ("moon" in on && sky != null) tiles += Tile(1, 1f) { r ->
                val lb = frameOf(r, w.moon)
                val lines = listOfNotNull(sky.moonPhase, fmt(w.lit, (sky.moonLit * 100).roundToInt()), sky.moonNext)
                val lh = s * 0.05f
                val south = (sky.lat ?: 1.0) < 0
                if (r.width() < s * 0.5f) {
                    // A narrow tile: the disc up by the heading, the words under it at full width.
                    val mr = min(s * 0.055f, r.width() * 0.14f)
                    moonDisc(r.right - pd - mr, r.top + pd + mr, mr, sky.moonLit, sky.waxing, south)
                    var y = max(lb + s * 0.04f, r.top + pd + 2 * mr + s * 0.03f)
                    lines.forEachIndexed { i, l2 ->
                        line(l2, r.left + pd, y, r.width() - 2 * pd, if (i == 0) s * 0.034f else s * 0.027f, if (i == 0) strong else body)
                        y += lh
                    }
                    return@Tile
                }
                val room = r.bottom - pd - (lb + s * 0.03f)
                val mr = min(room / 2f, min(r.width() * 0.15f, s * 0.09f))
                val cy = lb + s * 0.03f + room / 2f
                moonDisc(r.left + pd + mr, cy, mr, sky.moonLit, sky.waxing, south)
                val tx = r.left + pd + 2 * mr + s * 0.035f
                val tw = r.right - pd - tx
                var y = cy - ((lines.size - 1) * lh + cap(paint(strong, s * 0.034f))) / 2f
                lines.forEachIndexed { i, l2 ->
                    line(l2, tx, y, tw, if (i == 0) s * 0.034f else s * 0.027f, if (i == 0) strong else body)
                    y += lh
                }
            }
            if ("year" in on) tiles += Tile(1, 1f) { r ->
                val lb = frameOf(r, data.year)
                val base = value(r, lb, pct(yearFraction()))
                gauge(r.left + pd, r.right - pd, base + s * 0.028f, s * 0.018f, yearFraction())
                detail(r, data.words.daysLeft)
            }
            // Rows of tiles; a row left short is closed by widening its last tile, so the grid has no holes.
            val rows = mutableListOf<MutableList<Pair<Tile, Int>>>()
            fun close() {
                val row = rows.lastOrNull() ?: return
                val rem = cols - row.sumOf { it.second }
                if (rem > 0) row[row.size - 1] = row.last().first to row.last().second + rem
            }
            var used = cols
            for (tile in tiles) {
                val span = min(tile.span, cols)
                if (used + span > cols) { close(); rows.add(mutableListOf()); used = 0 }
                rows.last().add(tile to span)
                used += span
            }
            close()
            val foot = LiveFaces.footLine(p, f)
            val gap = s * 0.026f
            val bottom = foot - s * 0.055f
            val weights = rows.map { r -> r.maxOf { it.first.weight } }
            val unit = min((bottom - f.top - gap * (rows.size - 1)) / weights.sum(), s * 0.42f)
            val colW = (f.width() - gap * (cols - 1)) / cols
            // A grid of few tiles sits in the middle of the page rather than hanging from the top.
            val gridH = unit * weights.sum() + gap * (rows.size - 1)
            var y = f.top + max(0f, (bottom - f.top - gridH) / 2f)
            rows.forEachIndexed { i, row ->
                val hgt = unit * weights[i]
                var x = f.left
                row.forEach { (tile, span) ->
                    val tw = colW * span + gap * (span - 1)
                    tile.draw(RectF(x, y, x + tw, y + hgt))
                    x += tw + gap
                }
                y += hgt + gap
            }
        }
    }

    // ---------- Day ring ----------

    /** The whole day on a 24-hour ring: night in stipple, today's events as arcs, the sleep so far in the accent, now as a hand. */
    fun dayRing(p: SleepPage) {
        with(p) {
            val f = frame()
            val t = hm(null)
            val rows = dayReadings(sun = true)
            val r1 = masthead(f)
            if (!landscape) {
                val foot = LiveFaces.footLine(p, f)
                val above = footRows(rows, f.left, f.right, foot, s * 0.034f)
                val d = min(f.width(), above - s * 0.05f - (r1 + s * 0.06f))
                ring(w / 2f, r1 + s * 0.06f + d / 2f, d / 2f, t)
            } else {
                val d = min(f.bottom - r1 - s * 0.06f, f.width() * 0.52f)
                ring(f.left + d / 2f, r1 + s * 0.06f + d / 2f, d / 2f, t)
                val colL = f.left + d + f.width() * 0.07f
                val foot = LiveFaces.footLine(p, RectF(colL, f.top, f.right, f.bottom))
                val y = columnRows(rows, colL, f.right, r1 + s * 0.07f, foot - s * 0.05f, s * 0.038f)
                if (spec.shows(SleepElement.Agenda) && data.calendarAllowed) todayList(colL, f.right, y + s * 0.02f, foot - s * 0.06f, s * 0.03f)
            }
        }
    }

    private fun SleepPage.ring(cx: Float, cy: Float, r: Float, t: Hm) {
        val noonTop = spec.option(FaceOptions.RING_TOP) == "noon"
        fun ang(min: Float) = (if (noonTop) min - 720f else min) / 1440f * 360f - 90f
        val oval = { rr: Float -> RectF(cx - rr, cy - rr, cx + rr, cy + rr) }
        val sky = data.sky
        if (!dry) {
            // Night on the outer band, when the weather city gives sunrise and sunset.
            if (spec.option(FaceOptions.RING_NIGHT) == "on" && sky?.place != null) {
                val band = pattern("dots", pitch = s * 0.011f, weight = 0.3f).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.09f }
                val night = when {
                    sky.polar == -1 -> listOf(0f to 1440f)
                    sky.polar == 1 -> emptyList()
                    sky.riseMin != null && sky.setMin != null -> listOf(0f to sky.riseMin.toFloat(), sky.setMin.toFloat() to 1440f)
                    else -> emptyList()
                }
                night.filter { it.second > it.first }.forEach { (a, b) -> canvas.drawArc(oval(r * 0.945f), ang(a), (b - a) / 1440f * 360f, false, band) }
            }
            canvas.drawCircle(cx, cy, r - s * 0.002f, stroke(ink, s * 0.004f))
            canvas.drawCircle(cx, cy, r * 0.9f, stroke(ink, s * 0.0025f))
            for (h in 0 until 24) {
                val major = h % 6 == 0
                val a = Math.toRadians(ang(h * 60f).toDouble())
                val r2 = r * (if (major) 0.83f else if (h % 3 == 0) 0.86f else 0.875f)
                canvas.drawLine(cx + (cos(a) * r * 0.9f).toFloat(), cy + (sin(a) * r * 0.9f).toFloat(), cx + (cos(a) * r2).toFloat(), cy + (sin(a) * r2).toFloat(),
                    stroke(ink, if (major) s * 0.008f else s * 0.0035f).apply { strokeCap = Paint.Cap.BUTT })
            }
            // Today's events as arcs, each ending a hair short so neighbours stay apart.
            if (spec.shows(SleepElement.Agenda)) {
                val ev = stroke(ink, r * 0.055f).apply { strokeCap = Paint.Cap.BUTT }
                data.day.filter { !it.allDay }.forEach { e ->
                    val a = ((e.begin - data.dayStart) / 60_000f).coerceIn(0f, 1440f)
                    val b = ((e.end - data.dayStart) / 60_000f).coerceIn(0f, 1440f)
                    val sweep = max(1.5f, (b - a) / 1440f * 360f - 0.8f)
                    canvas.drawArc(oval(r * 0.775f), ang(a), sweep, false, ev)
                }
            }
            // The sleep so far, in the accent: from when the tablet went down to now.
            val live = data.live
            if (live.live && live.asleepMin > 0) {
                val start = ((live.sleptAtMs - data.dayStart) / 60_000f)
                val sweep = min(360f, live.asleepMin / 1440f * 360f)
                canvas.drawArc(oval(r * 0.69f), ang(start), sweep, false, stroke(accent, r * 0.045f).apply { strokeCap = Paint.Cap.BUTT })
            }
        }
        val np = paint(strong, r * 0.062f, figures = true)
        listOf(0, 6, 12, 18).forEach { h ->
            val a = Math.toRadians(ang(h * 60f).toDouble())
            val nr = r * 0.59f
            text(h.toString(), cx + (cos(a) * nr).toFloat(), cy + (sin(a) * nr).toFloat() - cap(np) / 2f, np, Paint.Align.CENTER)
        }
        // Now, as a hand from the numerals out to the rim, with a paper halo so it reads across the arcs.
        if (!dry) {
            val a = Math.toRadians(ang(dayMinute().toFloat()).toDouble())
            val x1 = cx + (cos(a) * r * 0.66f).toFloat()
            val y1 = cy + (sin(a) * r * 0.66f).toFloat()
            val x2 = cx + (cos(a) * r * 1.0f).toFloat()
            val y2 = cy + (sin(a) * r * 1.0f).toFloat()
            canvas.drawLine(x1, y1, x2, y2, stroke(paper, s * 0.026f).apply { strokeCap = Paint.Cap.ROUND })
            canvas.drawLine(x1, y1, x2, y2, stroke(ink, s * 0.012f).apply { strokeCap = Paint.Cap.ROUND })
            canvas.drawCircle(x1, y1, s * 0.014f, fill(ink))
        }
        val amP = paint(strong, r * 0.06f, tracking = 0.14f)
        val size = min(fitFigures(t.time, display, r * 0.86f, r * 0.34f, -0.03f), r * 0.22f / 0.72f)
        val tp = paint(display, size, figures = true, tracking = -0.03f)
        val blockH = cap(tp) + if (t.amPm.isNotEmpty()) r * 0.07f + cap(amP) else 0f
        val base = text(t.time, cx, cy - blockH / 2f, tp, Paint.Align.CENTER)
        if (t.amPm.isNotEmpty()) text(upperL(t.amPm), cx, base + r * 0.07f, amP, Paint.Align.CENTER)
    }

    /** What's left of today, time and title, under a small heading. */
    private fun SleepPage.todayList(left: Float, right: Float, top: Float, bottom: Float, size: Float) {
        if (top + s * 0.1f > bottom) return
        val lb = legend(data.words.today, left, top)
        val now = data.live.nowMs
        val left0 = data.day.filter { it.end > now || it.allDay }
        if (left0.isEmpty()) {
            text(data.words.nothingToday, left, lb + s * 0.04f, paint(body, size))
            return
        }
        val tp = paint(strong, size, figures = true)
        val bp = paint(body, size)
        val whenW = left0.maxOf { width(tp, it.time.ifEmpty { data.words.today }) } + size
        var y = lb + s * 0.045f
        for (e in left0) {
            if (y + cap(bp) > bottom) break
            text(e.time.ifEmpty { "—" }, left, y, tp)
            text(clip(e.title, bp, right - left - whenW), left + whenW, y, bp)
            y += size * 2f
        }
    }

    // ---------- Timeline ----------

    /** Today along a line: events as blocks in lanes, the sleep in the accent, now as a marker. */
    fun timeline(p: SleepPage) {
        with(p) {
            val f = frame()
            val live = data.live
            val t = hm(null)
            val span = spec.option(FaceOptions.LINE_SPAN)
            val (from, to, step) = when (span) {
                "day" -> Triple(data.dayStart, data.dayStart + 24 * 3_600_000L, 3)
                "waking" -> Triple(data.dayStart + 6 * 3_600_000L, data.dayStart + 24 * 3_600_000L, 2)
                else -> {
                    val h0 = live.nowMs - live.nowMs % 3_600_000L - 2 * 3_600_000L
                    Triple(h0, h0 + 10 * 3_600_000L, 1)
                }
            }
            val rows = dayReadings(sun = false)
            val r1 = masthead(f)
            // The time, large, at the top left; the readings beside it.
            val timeMax = if (landscape) s * 0.2f else s * 0.19f
            val amP = paint(strong, s * 0.034f, tracking = 0.12f)
            val tp = paint(display, min(timeMax, fitFigures(t.time, display, f.width() * 0.42f, timeMax, -0.03f)), figures = true, tracking = -0.03f)
            val tTop = r1 + s * 0.07f
            val tb = text(t.time, f.left, tTop, tp)
            if (t.amPm.isNotEmpty()) text(upperL(t.amPm), f.left + width(tp, t.time) + s * 0.025f, tb - cap(amP), amP)
            val colL = f.left + max(width(tp, t.time) + (if (t.amPm.isEmpty()) 0f else width(amP, upperL(t.amPm)) + s * 0.03f), f.width() * 0.36f) + f.width() * 0.06f
            val foot = LiveFaces.footLine(p, f)
            val rowsBottom = columnRows(rows.take(if (landscape) 3 else 3), colL, f.right, tTop - s * 0.005f, if (landscape) f.top + f.height() * 0.5f else f.top + f.height() * 0.42f, s * 0.032f)
            val zoneTop = max(tb, rowsBottom - s * 0.04f) + s * 0.08f
            val zone = RectF(f.left, zoneTop, f.right, foot - s * 0.06f)
            val labelP = paint(strong, s * 0.024f, figures = true)
            fun hourLabel(ms: Long): String {
                val h = java.util.Calendar.getInstance().apply { timeInMillis = ms }.get(java.util.Calendar.HOUR_OF_DAY)
                return if (t.h24) h.toString() else "${(h % 12).let { if (it == 0) 12 else it }} ${if (h < 12) data.words.am else data.words.pm}"
            }
            // Today's events, and the coming ones too when the span runs past midnight.
            val pool = (data.day + data.events).filter { !it.allDay }.distinctBy { it.begin to it.title }
            val events = if (spec.shows(SleepElement.Agenda)) pool.filter { it.end > from && it.begin < to }.sortedBy { it.begin } else emptyList()
            // Lanes: each event takes the first lane free at its start; three at most.
            val lanes = mutableListOf<Long>()
            val placed = events.mapNotNull { e ->
                val i = lanes.indexOfFirst { it <= e.begin }.let { if (it >= 0) it else if (lanes.size < 3) { lanes += 0L; lanes.size - 1 } else -1 }
                if (i < 0) null else { lanes[i] = e.end; e to i }
            }
            val nLanes = max(1, lanes.size)
            // The night, from the weather city's sunset to sunrise, as a light stipple behind the events.
            val sky = data.sky
            val nights: List<Pair<Long, Long>> = when {
                sky?.place == null -> emptyList()
                sky.polar == -1 -> listOf(from to to)
                sky.riseMin == null || sky.setMin == null -> emptyList()
                else -> (-1..1).map { d -> data.dayStart + d * 86_400_000L + sky.setMin * 60_000L to data.dayStart + (d + 1) * 86_400_000L + sky.riseMin * 60_000L }
            }.map { (a, b) -> max(a, from) to min(b, to) }.filter { it.second > it.first }
            val nightPaint = pattern("dots", pitch = s * 0.012f, weight = 0.14f)
            if (landscape) {
                val labelTop = zone.bottom - cap(labelP)
                val axisY = labelTop - s * 0.075f
                val x = { ms: Long -> zone.left + (ms - from).toFloat() / (to - from) * zone.width() }
                rule(zone.left, zone.right, axisY, s * 0.005f)
                var h = from
                while (h <= to) {
                    val hx = x(h)
                    val major = ((h - data.dayStart) / 3_600_000L) % step == 0L
                    rule(hx - s * 0.0015f, hx + s * 0.0015f, axisY + s * 0.012f, s * 0.024f)
                    if (major) {
                        // The end labels align to the ends of the line rather than spilling past the margins.
                        val lbl = hourLabel(h)
                        val half = width(labelP, lbl) / 2f
                        when {
                            hx - half < zone.left -> text(lbl, zone.left, labelTop, labelP)
                            hx + half > zone.right -> text(lbl, zone.right, labelTop, labelP, Paint.Align.RIGHT)
                            else -> text(lbl, hx, labelTop, labelP, Paint.Align.CENTER)
                        }
                    }
                    h += 3_600_000L
                }
                val laneH = min(s * 0.11f, (axisY - s * 0.04f - zone.top) / nLanes)
                if (!dry) nights.forEach { (a, b) -> canvas.drawRect(x(a), zone.top, x(b), axisY, nightPaint) }
                // Now, drawn under the events so it never cuts their words.
                val nx = x(live.nowMs)
                val nowIn = nx in zone.left..zone.right
                if (!dry && nowIn) canvas.drawLine(nx, zone.top, nx, axisY + s * 0.045f, stroke(ink, s * 0.007f))
                // Each event: a bar on its lane with its time and title written above it, as far as the next one.
                val barH = min(s * 0.03f, laneH * 0.3f)
                val lp = paint(strong, min(s * 0.028f, (laneH - barH) * 0.5f / 0.72f), figures = true)
                val bp = paint(body, lp.textSize)
                placed.forEach { (e, lane) ->
                    val bottom = axisY - s * 0.03f - lane * laneH
                    val x1 = x(max(e.begin, from))
                    val x2 = max(x(min(e.end, to)), x1 + s * 0.012f)
                    if (!dry) canvas.drawRoundRect(RectF(x1, bottom - barH, x2, bottom), barH / 3f, barH / 3f, fill(ink))
                    val next = placed.filter { it.second == lane && it.first.begin > e.begin }.minOfOrNull { x(max(it.first.begin, from)) } ?: zone.right
                    val room = next - x1 - s * 0.02f
                    val capTop = bottom - barH - s * 0.018f - cap(lp)
                    val tw = width(lp, e.time) + lp.textSize * 0.5f
                    // Each label sits on a little paper, clear of the night's stipple and the line for now.
                    fun knock(wd: Float) { if (!dry) canvas.drawRect(x1 - s * 0.006f, capTop - s * 0.01f, x1 + wd + s * 0.006f, capTop + cap(lp) + s * 0.012f, fill(paper)) }
                    if (room > tw + s * 0.05f) {
                        val title = clip(e.title, bp, room - tw)
                        knock(tw + width(bp, title))
                        text(e.time, x1, capTop, lp)
                        text(title, x1 + tw, capTop, bp)
                    } else if (room > s * 0.05f) {
                        val tt = clip(e.time, lp, room)
                        knock(width(lp, tt))
                        text(tt, x1, capTop, lp)
                    }
                }
                if (live.live && live.asleepMin > 0 && !dry) {
                    val a = x(max(live.sleptAtMs, from)).coerceIn(zone.left, zone.right)
                    val b = x(min(live.nowMs, to)).coerceIn(zone.left, zone.right)
                    if (b > a) canvas.drawRect(a, axisY + s * 0.018f, b, axisY + s * 0.04f, fill(accent))
                }
                if (!dry && nowIn) {
                    val tri = Path().apply { moveTo(nx - s * 0.022f, zone.top - s * 0.03f); lineTo(nx + s * 0.022f, zone.top - s * 0.03f); lineTo(nx, zone.top); close() }
                    canvas.drawPath(tri, fill(ink))
                }
            } else {
                val labelW = listOf(from, to).maxOf { width(labelP, hourLabel(it)) } + s * 0.03f
                val axisX = zone.left + labelW + s * 0.07f
                val y = { ms: Long -> zone.top + (ms - from).toFloat() / (to - from) * zone.height() }
                if (!dry) canvas.drawRect(axisX - s * 0.0025f, zone.top, axisX + s * 0.0025f, zone.bottom, fill(ink))
                var h = from
                while (h <= to) {
                    val hy = y(h)
                    val major = ((h - data.dayStart) / 3_600_000L) % step == 0L
                    if (!dry) canvas.drawRect(axisX - s * 0.012f, hy - s * 0.0015f, axisX, hy + s * 0.0015f, fill(ink))
                    if (major) text(hourLabel(h), axisX - s * 0.07f, hy - cap(labelP) / 2f, labelP, Paint.Align.RIGHT)
                    h += 3_600_000L
                }
                val evLeft = axisX + s * 0.07f
                if (!dry) nights.forEach { (a, b) -> canvas.drawRect(axisX + s * 0.05f, y(a), zone.right, y(b), nightPaint) }
                val ny = y(live.nowMs)
                val nowIn = ny in zone.top..zone.bottom
                if (!dry && nowIn) canvas.drawLine(axisX - s * 0.02f, ny, zone.right, ny, stroke(ink, s * 0.007f))
                val laneW = (zone.right - evLeft) / nLanes
                placed.forEach { (e, lane) ->
                    val x1 = evLeft + lane * laneW
                    block(RectF(x1, y(max(e.begin, from)), x1 + laneW - s * 0.015f, y(min(e.end, to))), e)
                }
                if (live.live && live.asleepMin > 0 && !dry) {
                    val a = y(max(live.sleptAtMs, from)).coerceIn(zone.top, zone.bottom)
                    val b = y(min(live.nowMs, to)).coerceIn(zone.top, zone.bottom)
                    if (b > a) canvas.drawRect(axisX + s * 0.015f, a, axisX + s * 0.045f, b, fill(accent))
                }
                if (!dry && nowIn) {
                    val tri = Path().apply { moveTo(axisX - s * 0.05f, ny - s * 0.022f); lineTo(axisX - s * 0.05f, ny + s * 0.022f); lineTo(axisX - s * 0.02f, ny); close() }
                    canvas.drawPath(tri, fill(ink))
                }
            }
        }
    }

    /** An event block: solid ink, its time and title in paper when they fit. */
    private fun SleepPage.block(r: RectF, e: SleepEvent) {
        if (dry || r.width() <= 1f || r.height() <= 1f) return
        val rad = min(s * 0.01f, min(r.width(), r.height()) / 2f)
        canvas.drawRoundRect(r, rad, rad, fill(ink))
        val size = min(s * 0.03f, r.height() * 0.5f / 0.72f)
        if (size < s * 0.02f || r.width() < s * 0.08f) return
        val tp = paint(strong, size, paper, figures = true)
        val bp = paint(body, size, paper)
        val pad = s * 0.016f
        val cy = r.top + min(r.height() / 2f, pad + cap(bp) / 2f + s * 0.01f)
        val tw = width(tp, e.time) + size * 0.6f
        val room = r.width() - 2 * pad
        canvas.withClip(r) {
            if (tw < room * 0.5f) {
                rawText(e.time, r.left + pad, cy - cap(tp) / 2f, tp)
                rawText(clip(e.title, bp, room - tw), r.left + pad + tw, cy - cap(bp) / 2f, bp)
            } else rawText(clip(e.title, bp, room), r.left + pad, cy - cap(bp) / 2f, bp)
        }
    }

    // ---------- Year ----------

    /** The year at a glance: every day a dot, the past filled, today in the accent; how far through it is, in figures. */
    fun year(p: SleepPage) {
        with(p) {
            val f = frame()
            val w = data.words
            val layout = spec.option(FaceOptions.YEAR_LAYOUT)
            val facts = listOf(fmt(w.dayOfYear, data.dayOfYear, data.daysInYear), fmt(w.week, data.week), w.daysLeft)
            val footer = listOfNotNull(data.putDownLine.takeIf { spec.shows(SleepElement.PutDown) }, data.batteryLine.takeIf { spec.shows(SleepElement.Battery) })
            val fs = s * 0.03f
            val footTop = if (footer.isEmpty()) f.bottom + s * 0.04f else f.bottom - cap(paint(body, fs))
            if (footer.isNotEmpty()) {
                rule(f.left, f.right, footTop - s * 0.04f, max(1f, s * 0.0015f))
                text(footer.first(), f.left, footTop, paint(body, fs))
                if (footer.size > 1) text(footer[1], f.right, footTop, paint(body, fs, figures = true), Paint.Align.RIGHT)
            }
            val fieldBottom = footTop - s * 0.1f
            val pctText = pct(yearFraction())
            if (!landscape) {
                val yp = paint(display, s * 0.16f, figures = true, tracking = -0.03f)
                val yb = text(data.year, f.left, f.top, yp)
                val pp = paint(display, s * 0.16f * 0.62f, figures = true, tracking = -0.02f)
                text(pctText, f.right, yb - cap(pp), pp, Paint.Align.RIGHT)
                val fp = paint(strong, s * 0.032f, figures = true)
                val fb = text(clip(facts.joinToString("   ·   "), fp, f.width()), f.left, yb + s * 0.05f, fp)
                val r2 = fb + s * 0.045f
                rule(f.left, f.right, r2, s * 0.004f)
                yearField(RectF(f.left, r2 + s * 0.06f, f.right, fieldBottom), layout)
            } else {
                val colW = f.width() * 0.27f
                val yp = paint(display, fitFigures(data.year, display, colW, s * 0.2f, -0.03f), figures = true, tracking = -0.03f)
                val yb = text(data.year, f.left, f.top, yp)
                val pp = paint(display, s * 0.11f, figures = true, tracking = -0.02f)
                var y = text(pctText, f.left, yb + s * 0.07f, pp) + s * 0.08f
                facts.forEachIndexed { i, fact ->
                    val lp = paint(strong, s * 0.034f, figures = true)
                    y = text(clip(fact, lp, colW), f.left, y, lp) + (if (i < facts.size - 1) s * 0.045f else 0f)
                }
                val divX = f.left + colW + f.width() * 0.035f
                if (!dry) canvas.drawRect(divX - s * 0.0015f, f.top, divX + s * 0.0015f, fieldBottom + s * 0.04f, fill(ink))
                yearField(RectF(divX + f.width() * 0.035f, f.top, f.right, fieldBottom), layout)
            }
        }
    }

    /** One dot: the past filled, today larger in the accent, the rest as rims. */
    private fun SleepPage.dayDot(cx: Float, cy: Float, pitch: Float, d: LocalDate) {
        if (dry) return
        val today = data.today
        when {
            d == today -> {
                canvas.drawCircle(cx, cy, pitch * 0.46f, fill(accent))
                canvas.drawCircle(cx, cy, pitch * 0.46f, stroke(ink, max(1f, s * 0.003f)))
            }
            d.isBefore(today) -> canvas.drawCircle(cx, cy, pitch * 0.25f, fill(ink))
            else -> canvas.drawCircle(cx, cy, pitch * 0.24f, stroke(ink, max(1f, s * 0.002f)))
        }
    }

    private fun SleepPage.yearField(r: RectF, layout: String) {
        val y0 = LocalDate.of(data.today.year, 1, 1)
        val first = data.firstDayOfWeek
        val monthP = paint(strong, s * 0.022f, tracking = 0.14f)
        val names = (1..12).map { m -> upperL(y0.withMonth(m).month.getDisplayName(java.time.format.TextStyle.SHORT_STANDALONE, Locale.getDefault())) }
        when (layout) {
            "rows" -> {
                val labelW = names.maxOf { width(monthP, it) } + s * 0.03f
                val pitch = min((r.width() - labelW) / 31f, r.height() / 12f)
                // The days keep their pitch across; the months may space out down the page, like a ruler's lines.
                val rowPitch = min(r.height() / 12f, pitch * 2f)
                val top = r.top + (r.height() - rowPitch * 12f) / 2f
                for (m in 0 until 12) {
                    val cy = top + rowPitch * (m + 0.5f)
                    text(names[m], r.left, cy - cap(monthP) / 2f, monthP)
                    val month = y0.withMonth(m + 1)
                    for (d in 1..month.lengthOfMonth()) dayDot(r.left + labelW + pitch * (d - 0.5f), cy, pitch, month.withDayOfMonth(d))
                }
            }
            "weeks" -> {
                val start = y0.minusDays(((y0.dayOfWeek.value - first.value + 7) % 7).toLong())
                val weeks = ((java.time.temporal.ChronoUnit.DAYS.between(start, y0.withDayOfYear(y0.lengthOfYear())) / 7) + 1).toInt()
                val blocks = if (landscape) 1 else 2
                val perBlock = (weeks + blocks - 1) / blocks
                val labelH = cap(monthP) + s * 0.025f
                val pitch = min(r.width() / perBlock, (r.height() - blocks * labelH - (blocks - 1) * s * 0.06f) / (7f * blocks))
                val blockH = labelH + pitch * 7f
                val total = blocks * blockH + (blocks - 1) * s * 0.06f
                var top = r.top + (r.height() - total) / 2f
                var pending: Int? = null
                for (b in 0 until blocks) {
                    val w0 = b * perBlock
                    val left = r.left + (r.width() - pitch * perBlock) / 2f
                    val right = left + pitch * perBlock
                    // A month is named over the week holding its 1st; one that would run off the block is named
                    // at the start of the next.
                    pending?.let { m -> text(names[m - 1], left + pitch * 0.1f, top, monthP) }
                    pending = null
                    for (wk in w0 until min(weeks, w0 + perBlock)) {
                        for (dd in 0 until 7) {
                            val d = start.plusDays((wk * 7 + dd).toLong())
                            if (d.year != y0.year) continue
                            val cx = left + pitch * (wk - w0 + 0.5f)
                            if (d.dayOfMonth == 1) {
                                val lx = cx - pitch * 0.4f
                                if (lx + width(monthP, names[d.monthValue - 1]) <= right) text(names[d.monthValue - 1], lx, top, monthP) else pending = d.monthValue
                            }
                            dayDot(cx, top + labelH + pitch * (dd + 0.5f), pitch, d)
                        }
                    }
                    top += blockH + s * 0.06f
                }
            }
            else -> {
                val cols = if (landscape) 4 else 3
                val rowsN = 12 / cols
                val gapX = s * 0.05f
                val gapY = s * 0.04f
                val cw = (r.width() - gapX * (cols - 1)) / cols
                val labelH = cap(monthP) + s * 0.03f
                val pitch = min(cw / 7f, ((r.height() - gapY * (rowsN - 1)) / rowsN - labelH) / 6f)
                val mh = labelH + pitch * 6f
                val top0 = r.top + (r.height() - (mh * rowsN + gapY * (rowsN - 1))) / 2f
                for (m in 0 until 12) {
                    val x = r.left + (m % cols) * (cw + gapX)
                    val y = top0 + (m / cols) * (mh + gapY)
                    text(names[m], x, y, monthP)
                    val month = y0.withMonth(m + 1)
                    val offset = (month.dayOfWeek.value - first.value + 7) % 7
                    for (d in 1..month.lengthOfMonth()) {
                        val i = offset + d - 1
                        dayDot(x + pitch * (i % 7 + 0.5f), y + labelH + pitch * (i / 7 + 0.5f), pitch, month.withDayOfMonth(d))
                    }
                }
            }
        }
    }

    // ---------- Sky ----------

    /** The sun's path across today and where it is now, the moon as it looks tonight, and the day's light in figures. */
    fun sky(p: SleepPage) {
        with(p) {
            val f = frame()
            val sky = data.sky ?: return
            val w = data.words
            val r1 = masthead(f)
            val rows = buildList {
                when {
                    sky.sunrise != null && sky.sunset != null -> {
                        add(w.sunrise to sky.sunrise)
                        add(w.sunset to sky.sunset)
                        sky.dayLength?.let { add(w.daylight to it) }
                    }
                    sky.polar == 1 -> add(w.daylight to w.polarDay)
                    sky.polar == -1 -> add(w.daylight to w.polarNight)
                }
            }
            if (!landscape) {
                val foot = LiveFaces.footLine(p, f)
                val above = footRows(rows, f.left, f.right, foot, s * 0.036f)
                val moonH = s * 0.3f
                val moonTop = above - s * 0.06f - moonH
                sunPath(RectF(f.left, r1 + s * 0.08f, f.right, moonTop - s * 0.1f))
                moonBlock(f.left, f.right, moonTop, moonH)
            } else {
                val colL = f.left + f.width() * 0.66f
                val foot = LiveFaces.footLine(p, RectF(colL, f.top, f.right, f.bottom))
                sunPath(RectF(f.left, r1 + s * 0.08f, colL - f.width() * 0.05f, foot - s * 0.02f))
                val moonH = s * 0.25f
                moonBlock(colL, f.right, r1 + s * 0.08f, moonH)
                columnRows(rows, colL, f.right, r1 + s * 0.08f + moonH + s * 0.09f, foot - s * 0.05f, s * 0.038f)
            }
        }
    }

    private fun SleepPage.moonBlock(left: Float, right: Float, top: Float, height: Float) {
        val sky = data.sky ?: return
        val mr = min(height / 2f, (right - left) * 0.2f)
        moonDisc(left + mr, top + height / 2f, mr, sky.moonLit, sky.waxing, (sky.lat ?: 1.0) < 0)
        val tx = left + 2 * mr + s * 0.05f
        val tw = right - tx
        val np = paint(display, fit(sky.moonPhase, display, tw, s * 0.06f))
        fun sized(t: String, tf: android.graphics.Typeface, size: Float) = paint(tf, max(size * 0.78f, min(size, size * tw / max(1f, width(paint(tf, size), t)))), figures = true)
        val litText = fmt(data.words.lit, (sky.moonLit * 100).roundToInt())
        val sp = sized(litText, strong, s * 0.032f)
        val lb = legend(data.words.moon, tx, top + height / 2f - (cap(np) + s * 0.19f) / 2f)
        val nb = text(sky.moonPhase, tx, lb + s * 0.04f, np)
        val b2 = text(clip(litText, sp, tw), tx, nb + s * 0.045f, sp)
        sky.moonNext?.let { val bp = sized(it, body, s * 0.03f); text(clip(it, bp, tw), tx, b2 + s * 0.04f, bp) }
    }

    /**
     * Today's sun: its height through the day for the weather city, the daylight in stipple above the horizon, and the
     * sun where it is now. Without a city there's no horizon to draw, so the sun follows the clock and says so.
     */
    private fun SleepPage.sunPath(r: RectF) {
        val sky = data.sky ?: return
        val labelP = paint(strong, s * 0.024f, figures = true)
        val axisY = r.bottom - cap(labelP) - s * 0.04f
        val area = RectF(r.left, r.top + s * 0.02f, r.right, axisY - s * 0.03f)
        val x = { m: Float -> area.left + m / 1440f * area.width() }
        val lat = sky.lat
        val lon = sky.lon
        val real = lat != null && lon != null
        val samples = (0..144).map { i -> i * 10f }
        val horizon: Float
        val yOf: (Float) -> Float
        if (real) {
            val alts = samples.map { m -> Astro.altitude(data.dayStart + (m * 60_000L).toLong(), lat!!, lon!!).toFloat() }
            val peak = max(15f, alts.maxOrNull() ?: 15f)
            val low = max(15f, -(alts.minOrNull() ?: -15f))
            horizon = area.top + area.height() * 0.64f
            // Above the horizon the day's peak reaches near the top; below, the night is drawn shallower.
            val up = (horizon - area.top - s * 0.05f) / peak
            val down = (area.bottom - horizon - s * 0.02f) / low
            yOf = { m ->
                val a = Astro.altitude(data.dayStart + (m * 60_000L).toLong(), lat!!, lon!!).toFloat()
                horizon - a * (if (a >= 0f) up else down)
            }
        } else {
            horizon = area.centerY()
            val a = area.height() * 0.42f
            yOf = { m -> horizon - cos((m - 720f) / 1440f * 2 * PI).toFloat() * a }
        }
        if (!dry) {
            val curve = Path().apply { samples.forEachIndexed { i, m -> if (i == 0) moveTo(x(m), yOf(m)) else lineTo(x(m), yOf(m)) } }
            if (real) {
                // Daylight: the area between the path and the horizon, in stipple.
                canvas.withClip(area.left, area.top, area.right, horizon) {
                    val fillPath = Path(curve).apply { lineTo(x(1440f), horizon); lineTo(x(0f), horizon); close() }
                    drawPath(fillPath, pattern("dots", pitch = s * 0.012f, weight = 0.2f))
                    drawPath(curve, stroke(ink, s * 0.005f))
                }
                canvas.withClip(area.left, horizon, area.right, area.bottom + s * 0.01f) {
                    drawPath(curve, stroke(ink, s * 0.003f).apply { pathEffect = DashPathEffect(floatArrayOf(s * 0.012f, s * 0.012f), 0f) })
                }
                rule(area.left, area.right, horizon, s * 0.004f)
            } else canvas.drawPath(curve, stroke(ink, s * 0.004f).apply { pathEffect = DashPathEffect(floatArrayOf(s * 0.016f, s * 0.01f), 0f) })
            // The hour scale along the foot.
            rule(r.left, r.right, axisY, s * 0.003f)
            for (h in 0..24) {
                val hx = x(h * 60f)
                val major = h % 6 == 0
                canvas.drawRect(hx - s * 0.0015f, axisY - (if (major) s * 0.025f else s * 0.012f), hx + s * 0.0015f, axisY, fill(ink))
            }
        }
        for (h in 0..24 step 6) text(h.toString(), x(h * 60f).coerceIn(r.left + s * 0.02f, r.right - s * 0.02f), axisY + s * 0.025f, labelP, Paint.Align.CENTER)
        val m = dayMinute().toFloat()
        val sx = x(m)
        val sy = yOf(m)
        if (!dry) {
            val sr = s * 0.04f
            val up = !real || (sky.sunAltitude ?: 0.0) > -0.833
            canvas.drawCircle(sx, sy, sr + s * 0.012f, fill(paper))
            if (up) canvas.drawCircle(sx, sy, sr, fill(accent))
            canvas.drawCircle(sx, sy, sr - s * 0.002f, stroke(ink, s * 0.005f))
        }
        val tag = if (real) upperL(sky.place.orEmpty()) else upperL(data.words.sunByClock)
        legend(tag, r.right, r.top, align = Paint.Align.RIGHT)
    }

    // ---------- Broadsheet ----------

    /**
     * A front page: the masthead with the weather and battery in its ears, the dateline (the issue is the day of the
     * year), the next event as the headline, and columns for the sleep, what's ahead and the almanac.
     */
    fun broadsheet(p: SleepPage) {
        with(p) {
            val f = frame()
            val live = data.live
            val w = data.words
            val sky = data.sky
            val wx = data.weather
            val earW = f.width() * (if (landscape) 0.2f else 0.4f)
            val earP = paint(body, s * 0.024f)
            val earS = paint(strong, s * 0.026f, figures = true)
            // The ears: the weather on the left, the battery on the right. In landscape they flank the masthead;
            // in portrait they sit above it, so the title can take the whole width.
            var earBottom = f.top
            if (wx != null) {
                val lb = legend(w.weather, f.left, f.top)
                val v = listOfNotNull(wx.now ?: wx.range, wx.sky).joinToString(" · ")
                val b = text(clip(v, earS, earW), f.left, lb + s * 0.02f, earS)
                earBottom = max(earBottom, text(clip(wx.asOf, earP, earW), f.left, b + s * 0.018f, earP))
            }
            if (spec.shows(SleepElement.Battery)) {
                val lb = legend(data.labels.battery, f.right, f.top, align = Paint.Align.RIGHT)
                var b = text(clip(data.batteryLine, earS, earW), f.right, lb + s * 0.02f, earS, Paint.Align.RIGHT)
                if (!data.charging) live.used?.let { b = text(clip(it, earP, earW), f.right, b + s * 0.018f, earP, Paint.Align.RIGHT) }
                earBottom = max(earBottom, b)
            }
            val mastW = if (landscape) f.width() - 2 * earW - s * 0.06f else f.width()
            val mastTop = if (landscape || earBottom == f.top) f.top + s * 0.01f else earBottom + s * 0.05f
            val mp = paint(display, fit(w.masthead, display, mastW, if (landscape) s * 0.12f else s * 0.14f, tracking = -0.02f), tracking = -0.02f)
            val mb = text(w.masthead, w0(), mastTop, mp, Paint.Align.CENTER)
            var y = (if (landscape) max(mb, earBottom) else mb) + s * 0.045f
            rule(f.left, f.right, y, s * 0.008f)
            rule(f.left, f.right, y + s * 0.016f, s * 0.002f)
            val dl = paint(strong, s * 0.022f, tracking = 0.12f, figures = true)
            val dTop = y + s * 0.045f
            text(upperL(fmt(w.issue, data.dayOfYear)), f.left, dTop, dl)
            text(upperL(data.dateLong), w0(), dTop, dl, Paint.Align.CENTER)
            val db = text(upperL(fmt(w.edition, live.time + (if (live.amPm.isEmpty()) "" else " " + live.amPm))), f.right, dTop, dl, Paint.Align.RIGHT)
            y = db + s * 0.04f
            rule(f.left, f.right, y, s * 0.002f)
            val foot = LiveFaces.footLine(p, f)
            val bottom = foot - s * 0.06f
            // The lead: the next event, or the quote when asked or when there's no event to tell.
            val lead = data.events.firstOrNull()?.takeIf { spec.option(FaceOptions.PAPER_LEAD) == "agenda" && spec.shows(SleepElement.Agenda) && data.calendarAllowed }
            val quoteLead = lead == null
            val showQuote = spec.shows(SleepElement.Quote) && data.quote.isNotBlank()
            val leadW = if (landscape) f.width() * 0.64f else f.width()
            val leadTop = y + s * 0.06f
            val leadBottom: Float
            if (!quoteLead) {
                val hl = fitLayout(lead!!.title, display, leadW, if (landscape) f.height() * 0.34f else f.height() * 0.22f, s * 0.15f, s * 0.05f, spacing = 1.02f, tracking = -0.02f)
                val hb = draw(hl, f.left, leadTop)
                val deck = paint(strong, s * 0.036f, figures = true)
                leadBottom = text(clip(lead.whenLabel, deck, leadW), f.left, hb + s * 0.05f, deck)
            } else if (showQuote) {
                val ap = paint(strong, s * 0.026f, tracking = 0.12f)
                val hl = fitLayout("“${data.quote}”", display, leadW, (if (landscape) f.height() * 0.4f else f.height() * 0.3f) - s * 0.08f, s * 0.15f, s * 0.04f, spacing = 1.04f)
                val hb = draw(hl, f.left, leadTop)
                leadBottom = if (data.quoteAuthor.isNotBlank()) text("— " + upperL(data.quoteAuthor), f.left, hb + s * 0.05f, ap) else hb
            } else leadBottom = leadTop
            // The columns: the sleep, what's ahead and the almanac, each in a few short items.
            val sections = mutableListOf<Pair<String, List<Pair<String, String>>>>()
            sections += w.sleepReport to buildList {
                if (spec.shows(SleepElement.Asleep)) {
                    if (live.live) add(data.labels.asleepSince(live.sleptAt) to (live.asleepFor ?: data.labels.justPutDown))
                    else add(data.putDownLead to data.putDownTime)
                }
                if (spec.shows(SleepElement.Battery) && live.used != null && !data.charging) add(data.labels.usedAsleep to (live.usedShort ?: live.used))
                add(data.labels.alarm to (live.nextAlarm ?: w.alarmNone))
            }
            if (spec.shows(SleepElement.Agenda) && data.calendarAllowed) {
                val more = data.events.drop(if (quoteLead) 0 else 1).take(3)
                sections += w.ahead to (if (more.isEmpty()) listOf(data.labels.nothingPlanned to "") else more.map { it.whenLabel to it.title })
            }
            val almanac = w.almanac to buildList {
                if (sky != null) add(w.moon to "${sky.moonPhase}, ${fmt(w.lit, (sky.moonLit * 100).roundToInt())}")
                if (sky?.sunrise != null && sky.sunset != null) add(w.daylight to "${sky.sunrise} – ${sky.sunset}")
                add(fmt(w.week, data.week) to fmt(w.dayOfYear, data.dayOfYear, data.daysInYear))
            }
            val colTop = leadBottom + s * 0.07f
            val thoughtToo = !quoteLead && showQuote
            if (landscape) {
                // The lead fills two thirds, with the thought for the day and the almanac side by side under it;
                // the sleep and what's ahead take the last third.
                val cx = f.left + leadW + f.width() * 0.04f
                if (!dry) canvas.drawRect(cx - f.width() * 0.02f - s * 0.001f, y + s * 0.05f, cx - f.width() * 0.02f + s * 0.001f, bottom, fill(ink))
                val split = if (thoughtToo) f.left + leadW * 0.56f else f.left
                if (thoughtToo) thought(f.left, split - s * 0.04f, colTop, bottom)
                section(almanac.first, almanac.second, if (thoughtToo) split + s * 0.02f else f.left, f.left + leadW, colTop, bottom)
                var sy = y + s * 0.06f
                sections.forEach { (title, items) -> sy = section(title, items, cx, f.right, sy, bottom) + s * 0.03f }
            } else {
                // Three columns under the lead, the thought for the day across the foot when there's room.
                sections += almanac
                val gap = s * 0.05f
                val n = sections.size
                val cw = (f.width() - gap * (n - 1)) / n
                val qH = if (thoughtToo) min(s * 0.32f, (bottom - colTop) * 0.4f) else 0f
                val colBottom = bottom - qH
                var tallest = colTop
                sections.forEachIndexed { i, (title, items) ->
                    val x = f.left + i * (cw + gap)
                    tallest = max(tallest, section(title, items, x, x + cw, colTop, colBottom))
                }
                for (i in 1 until n) {
                    val x = f.left + i * (cw + gap) - gap / 2f
                    if (!dry) canvas.drawRect(x - s * 0.001f, colTop + s * 0.03f, x + s * 0.001f, tallest - s * 0.01f, fill(ink))
                }
                if (qH > 0f) thought(f.left, f.right, (tallest + s * 0.05f).coerceAtMost(bottom - s * 0.14f), bottom)
            }
        }
    }
    private fun SleepPage.w0() = w / 2f

    /** A section: a hairline, a small heading and its items as legend over value. Returns where the next may start. */
    private fun SleepPage.section(title: String, items: List<Pair<String, String>>, left: Float, right: Float, top: Float, bottom: Float): Float {
        // A heading needs room for at least one item under it.
        if (top + s * 0.2f > bottom) return top
        rule(left, right, top, max(1f, s * 0.0016f))
        var y = legend(title, left, top + s * 0.03f) + s * 0.045f
        val size = s * 0.03f
        items.forEach { (l, v) ->
            if (y + s * 0.02f + size * 1.3f > bottom) return y
            val sz = max(size * 0.78f, min(size, size * (right - left) / max(1f, width(paint(strong, size, figures = true), v))))
            y = if (v.isEmpty()) text(clip(l, paint(body, size), right - left), left, y, paint(body, size)) + size * 1.2f
            else stackedRow(l, v, left, right, y, sz, ruleBelow = false) - sz * 0.3f
        }
        return y
    }

    /** The thought for the day, boxed: the quote set as large as the room allows. */
    private fun SleepPage.thought(left: Float, right: Float, top: Float, bottom: Float) {
        if (bottom - top < s * 0.12f) return
        rule(left, right, top, max(1f, s * 0.0016f))
        val lb = legend(data.words.thought, left, top + s * 0.03f)
        val ap = paint(strong, s * 0.024f, tracking = 0.12f)
        val authorH = if (data.quoteAuthor.isNotBlank()) cap(ap) + s * 0.04f else 0f
        val l = fitLayout("“${data.quote}”", strong, right - left, bottom - lb - s * 0.04f - authorH, s * 0.06f, s * 0.028f, spacing = 1.1f, align = Layout.Alignment.ALIGN_NORMAL)
        val qb = draw(l, left, lb + s * 0.04f)
        if (data.quoteAuthor.isNotBlank()) text("— " + upperL(data.quoteAuthor), left, qb + s * 0.04f, ap)
    }
}
