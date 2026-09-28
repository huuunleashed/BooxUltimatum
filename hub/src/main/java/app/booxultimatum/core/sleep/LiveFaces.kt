package app.booxultimatum.core.sleep

import android.graphics.Paint
import android.graphics.RectF
import app.booxultimatum.core.sleep.SleepFaces.agenda
import app.booxultimatum.core.sleep.SleepFaces.batteryLine
import app.booxultimatum.core.sleep.SleepFaces.legend
import app.booxultimatum.core.sleep.SleepFaces.stackedRow
import app.booxultimatum.core.sleep.SleepFaces.tuningScale
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Faces made for updates while asleep. Each reads what a sleeping tablet can truthfully say every few minutes: the
 * time now, how long it has slept, what the battery has done since, and what comes next. They share the studio's
 * grammar (cap-height alignment, one dominant element, the accent only on shapes) and have their own portrait and
 * landscape compositions.
 *
 * Without live updates ([SleepLive.live] false) the time is the moment the tablet was put down, and the faces say so
 * instead of pretending to keep time.
 */
internal object LiveFaces {
    private fun upper(t: String) = t.uppercase(Locale.getDefault())

    // ---------- Dial ----------

    fun dial(p: SleepPage) {
        with(p) {
            val f = frame()
            val live = data.live
            val style = spec.option(FaceOptions.DIAL_STYLE)
            // The 24-hour dial reads 24-hour time beside it too; the others follow the tablet.
            val t = if (style == "h24") hm(null, force24 = true) else hm(null)
            val headP = paint(strong, s * 0.03f, tracking = 0.14f)
            text(upper(data.weekday), f.left, f.top, headP)
            val headBase = text(if (spec.shows(SleepElement.Date)) upper(data.dateShort) else "", f.right, f.top, headP, Paint.Align.RIGHT)
            val r1 = headBase + s * 0.035f
            rule(f.left, f.right, r1, s * 0.004f)
            val rs = s * 0.036f
            val readings = readings(p)
            if (!landscape) {
                val rowsH = readings.size * rs * 3.3f
                val foot = footLine(p, f)
                val room = foot - s * 0.05f - rowsH - (r1 + s * 0.07f)
                val d = min(f.width(), room)
                val cx = w / 2f
                val cy = r1 + s * 0.07f + d / 2f
                clock(cx, cy, d / 2f, live.hour, live.minute, style)
                var y = cy + d / 2f + s * 0.08f
                readings.forEachIndexed { i, (l, v) -> y = row(l, v, f.left, f.right, y, rs, ruleBelow = i < readings.size - 1) }
            } else {
                val d = min(f.height() - (r1 - f.top) - s * 0.06f, f.width() * 0.5f)
                val cx = f.left + d / 2f
                val cy = r1 + s * 0.06f + d / 2f
                clock(cx, cy, d / 2f, live.hour, live.minute, style)
                val colL = f.left + d + f.width() * 0.07f
                val timeP = paint(display, fitFigures(t.time, display, f.right - colL, s * 0.2f, tracking = -0.02f), figures = true, tracking = -0.02f)
                val tb = text(t.time, colL, r1 + s * 0.07f, timeP)
                if (t.amPm.isNotEmpty()) legend(t.amPm, colL + width(timeP, t.time) + s * 0.02f, tb - cap(paint(strong, s * 0.026f)), s * 0.026f)
                val foot = footLine(p, RectF(colL, f.top, f.right, f.bottom))
                val big = s * 0.042f
                var y = tb + s * 0.11f
                readings.forEachIndexed { i, (l, v) -> if (y < foot - s * 0.1f) y = stackedRow(l, v, colL, f.right, y, big, ruleBelow = i < readings.size - 1) }
            }
        }
    }

    /**
     * A wall clock in one of four styles. Braun: a fine rim, sixty minute marks with heavier hours, the four quarter
     * numerals, two plain hands and the accent at the centre. Railway: a minute track and bold hour bars, no numerals,
     * broad stick hands. Numerals: all twelve hours. 24-hour: the hour hand goes round once a day, 24 at the top.
     * No second hand in any: nothing on e-ink should pretend to tick.
     */
    private fun SleepPage.clock(cx: Float, cy: Float, r: Float, hour: Int, minute: Int, style: String = "braun") {
        if (dry) return
        val railway = style == "railway"
        val day = style == "h24"
        canvas.drawCircle(cx, cy, r - s * 0.003f, stroke(ink, if (railway) s * 0.009f else s * 0.006f))
        val outer = r - s * 0.03f
        if (railway) canvas.drawCircle(cx, cy, outer - r * 0.05f, stroke(ink, s * 0.0022f))
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0 - 90)
            val major = i % 5 == 0
            val len = when {
                railway && major -> r * 0.2f
                railway -> r * 0.05f
                major -> r * 0.1f
                else -> r * 0.04f
            }
            val t = when {
                railway && major -> s * 0.02f
                railway -> s * 0.005f
                major -> s * 0.009f
                else -> s * 0.0032f
            }
            val p1x = cx + (cos(a) * outer).toFloat()
            val p1y = cy + (sin(a) * outer).toFloat()
            val p2x = cx + (cos(a) * (outer - len)).toFloat()
            val p2y = cy + (sin(a) * (outer - len)).toFloat()
            canvas.drawLine(p1x, p1y, p2x, p2y, stroke(if (major || railway) ink else minor, t).apply { strokeCap = Paint.Cap.BUTT })
        }
        if (!railway) {
            val labels = when (style) {
                "numerals" -> (1..12).map { it to it * 30.0 }
                "h24" -> (1..12).map { (it * 2) to it * 30.0 }
                else -> listOf(12, 3, 6, 9).map { it to it * 30.0 }
            }
            val np = paint(body, r * (if (style == "braun") 0.14f else 0.115f), figures = true)
            val nr = outer - r * 0.1f - r * (if (style == "braun") 0.13f else 0.11f)
            labels.forEach { (n, deg) ->
                val a = Math.toRadians(deg - 90)
                val x = cx + (cos(a) * nr).toFloat()
                val y = cy + (sin(a) * nr).toFloat()
                text(n.toString(), x, y - cap(np) / 2f, np, Paint.Align.CENTER)
            }
        }
        fun hand(deg: Double, len: Float, tail: Float, width: Float) {
            val a = Math.toRadians(deg - 90)
            val ex = cx + (cos(a) * len).toFloat()
            val ey = cy + (sin(a) * len).toFloat()
            val tx = cx - (cos(a) * tail).toFloat()
            val ty = cy - (sin(a) * tail).toFloat()
            canvas.drawLine(tx, ty, ex, ey, stroke(ink, width).apply { strokeCap = if (railway) Paint.Cap.BUTT else Paint.Cap.ROUND })
        }
        val h = if (day) hour + minute / 60f else (hour % 12) + minute / 60f
        if (railway) {
            hand(h * 30.0, r * 0.56f, r * 0.14f, s * 0.034f)
            hand(minute * 6.0, r * 0.84f, r * 0.16f, s * 0.024f)
        } else {
            hand(if (day) h * 15.0 else h * 30.0, r * 0.5f, r * 0.08f, s * 0.022f)
            hand(minute * 6.0, r * 0.78f, r * 0.1f, s * 0.013f)
        }
        canvas.drawCircle(cx, cy, s * 0.022f, fill(accent))
        canvas.drawCircle(cx, cy, s * 0.022f, stroke(ink, s * 0.003f))
    }

    // ---------- Clock ----------

    fun clock(p: SleepPage) {
        with(p) {
            val f = frame()
            val live = hm(FaceOptions.CLOCK_HOURS)
            val headP = paint(strong, s * 0.032f, tracking = 0.14f)
            text(upper(data.weekday), f.left, f.top, headP)
            val headBase = text(if (spec.shows(SleepElement.Date)) upper(data.dateYear) else "", f.right, f.top, headP, Paint.Align.RIGHT)
            val readings = readings(p)
            val rs = s * 0.038f
            val foot = footLine(p, f)
            // The readings sit in columns along the foot in landscape, like a receiver's lower panel, each as wide as
            // it needs; portrait hasn't the width for three, so they stand as ruled rows, as on the Dial.
            val readTop = (if (landscape) footColumns(readings, f.left, f.right, foot, rs) else footRows(readings, f.left, f.right, foot, s * 0.036f)) + s * 0.05f
            val top = headBase + s * 0.06f
            val bottom = (if (readings.isEmpty()) foot - s * 0.05f else readTop - s * 0.1f)
            // Landscape has width to spare beside the time for AM or PM; portrait sets it under the time instead.
            val amBeside = landscape && live.amPm.isNotEmpty()
            val amW = if (amBeside) width(paint(strong, s * 0.05f), upper(live.amPm)) + s * 0.06f else 0f
            val maxByHeight = (bottom - top) / 0.72f
            // Measured with tabular figures, as they are set: they run wider than the proportional ones.
            val probe = paint(display, 100f, figures = true, tracking = -0.04f)
            val byWidth = 100f * (f.width() - amW) / max(1f, width(probe, live.time))
            val size = minOf(byWidth, maxByHeight, if (landscape) s * 0.62f else s * 0.5f)
            val tp = paint(display, size, figures = true, tracking = -0.04f)
            val capH = cap(tp)
            val tTop = top + max(0f, (bottom - top - capH) / 2f)
            val tw = width(tp, live.time)
            val x0 = (w - tw - amW) / 2f
            val tb = text(live.time, x0, tTop, tp)
            if (amBeside) text(upper(live.amPm), x0 + tw + s * 0.03f, tb - cap(paint(strong, s * 0.05f)), paint(strong, s * 0.05f))
            else if (live.amPm.isNotEmpty()) text(upper(live.amPm), x0 + tw, tb + s * 0.04f, paint(strong, s * 0.05f), Paint.Align.RIGHT)
            if (!dry) {
                val barW = min(tw * 0.18f, s * 0.2f)
                canvas.drawRect(x0, tb + s * 0.04f, x0 + barW, tb + s * 0.04f + s * 0.016f, fill(accent))
            }
        }
    }

    // ---------- Monitor ----------

    fun monitor(p: SleepPage) {
        with(p) {
            val f = frame()
            val live = data.live
            val rs = if (landscape) s * 0.04f else s * 0.036f
            val colRight = if (landscape) f.left + f.width() * 0.44f else f.right
            // The heading is the sleep itself: since when, and for how long.
            val lead = if (live.live) data.labels.asleepSince(live.sleptAt) else data.labels.justPutDown
            val lb = legend(lead, f.left, f.top, s * 0.028f)
            val big = live.asleepFor ?: live.time
            val bigP = paint(display, fitFigures(big, display, colRight - f.left, if (landscape) s * 0.18f else s * 0.2f, tracking = -0.03f), figures = true, tracking = -0.03f)
            val bigBase = text(big, f.left, lb + s * 0.05f, bigP)
            val rows = buildList {
                if (live.live) add(data.labels.now to listOf(live.time, live.amPm).filter { it.isNotEmpty() }.joinToString(" "))
                add(data.labels.usedAsleep to (live.usedShort ?: live.used ?: data.labels.nothingUsed))
                if (data.calendarAllowed) add(data.labels.next to (live.countdown ?: data.events.firstOrNull()?.let { "${it.title}  ·  ${it.whenLabel}" } ?: data.labels.nothingPlanned))
                live.nextAlarm?.let { add(data.labels.alarm to it) }
                if (spec.shows(SleepElement.Owner) && (spec.ownerName.isNotBlank() || spec.ownerContact.isNotBlank())) add(spec.ownerName.trim() to spec.ownerContact.trim())
            }
            val foot = footLine(p, f)
            if (!landscape) {
                val rowsH = rows.size * rs * 2.62f
                val rowsTop = foot - s * 0.06f - rowsH
                var y = rowsTop
                rule(f.left, f.right, rowsTop - rs * 1.2f, s * 0.004f)
                rows.forEachIndexed { i, (l, v) -> y = row(l, v, f.left, f.right, y, rs, ruleBelow = i < rows.size - 1) }
                val fig = s * 0.3f
                dry = true
                val scaleH = tuningScale(f.left, f.right, 0f, fig)
                dry = false
                val midTop = bigBase + s * 0.1f
                val midBottom = rowsTop - rs * 1.2f
                tuningScale(f.left, f.right, midTop + max(0f, (midBottom - midTop - scaleH) / 2f), fig)
            } else {
                var y = bigBase + s * 0.09f
                rule(f.left, colRight, y - s * 0.035f, s * 0.004f)
                // The readings take the column's height, a little larger when there are few of them.
                val room = foot - s * 0.06f - y
                val size = if (rows.isEmpty()) rs else ((room / rows.size - s * 0.0144f) / 2.87f).coerceIn(rs, s * 0.05f)
                rows.forEachIndexed { i, (l, v) -> y = stackedRow(l, v, f.left, colRight, y, size, ruleBelow = i < rows.size - 1) }
                val scaleLeft = colRight + f.width() * 0.08f
                val fig = s * 0.25f
                dry = true
                val scaleH = tuningScale(scaleLeft, f.right, 0f, fig)
                dry = false
                tuningScale(scaleLeft, f.right, f.top + max(0f, (foot - s * 0.06f - f.top - scaleH) / 2f), fig)
            }
        }
    }

    // ---------- Shared ----------

    /** Time asleep, battery and the next event, as legend and value pairs, in the order a glance wants them. */
    internal fun readings(p: SleepPage): List<Pair<String, String>> = with(p) {
        val live = data.live
        buildList {
            if (spec.shows(SleepElement.Asleep)) {
                if (live.live) add(data.labels.asleep to (live.asleepFor ?: data.labels.justPutDown))
                else add(data.putDownLead to data.putDownTime)
            }
            if (spec.shows(SleepElement.Battery)) add(data.labels.battery to (live.used?.takeIf { !data.charging }?.let { "${data.batteryLine}  ·  $it" } ?: data.batteryLine))
            if (spec.shows(SleepElement.Agenda) && data.calendarAllowed) {
                add(data.labels.next to (live.countdown ?: data.events.firstOrNull()?.let { "${it.title}  ·  ${it.whenLabel}" } ?: data.labels.nothingPlanned))
            }
        }
    }

    /**
     * The honest foot: when this face was drawn and how often it updates, or when the tablet was put down. Set small
     * at the bottom of [f]; returns the top of its caps, the line everything else stays above.
     */
    internal fun footLine(p: SleepPage, f: RectF): Float = with(p) {
        val fp = paint(body, s * 0.024f)
        val top = f.bottom - cap(fp)
        val lr = s * 0.009f
        lamp(f.left + lr, top + cap(fp) / 2f, lr, data.live.live)
        text(data.live.updated, f.left + lr * 2 + s * 0.02f, top, fp)
        top
    }
}
