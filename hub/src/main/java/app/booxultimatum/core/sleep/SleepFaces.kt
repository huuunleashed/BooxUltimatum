package app.booxultimatum.core.sleep

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The eight full-screen faces. Each is set as a poster for a 300 dpi panel read at arm's length: generous margins,
 * one dominant element, nothing below body weight, and a real landscape composition instead of a squeezed portrait.
 * The Boox status bar always keeps its strip at the bottom; the top 22 % is kept free when the user wants the live
 * Boox clock.
 */
internal object SleepFaces {
    private fun upper(t: String) = t.uppercase(Locale.getDefault())

    // ---------- Shared pieces ----------

    /** Weekday initials over the month, today in an accent disc, days with events dotted. Returns the grid's bottom. */
    private fun SleepPage.monthGrid(left: Float, right: Float, top: Float, cellH: Float): Float {
        val cw = (right - left) / 7f
        val headP = paint(strong, cellH * 0.28f, tracking = 0.08f)
        data.weekdayInitials.forEachIndexed { i, t -> text(upper(t), left + cw * (i + 0.5f), top, headP, Paint.Align.CENTER) }
        val y0 = top + cap(headP) + cellH * 0.35f
        val first = data.today.withDayOfMonth(1)
        val offset = (first.dayOfWeek.value - data.firstDayOfWeek.value + 7) % 7
        val days = data.today.lengthOfMonth()
        val dayP = paint(body, cellH * 0.38f, figures = true)
        val todayP = paint(strong, cellH * 0.38f, onAccent, figures = true)
        for (day in 1..days) {
            val idx = offset + day - 1
            val cx = left + cw * (idx % 7 + 0.5f)
            val cy = y0 + (idx / 7) * cellH + cellH / 2f
            val isToday = day == data.today.dayOfMonth
            if (isToday && !dry) canvas.drawCircle(cx, cy, min(cw, cellH) * 0.43f, fill(accent))
            val p = if (isToday) todayP else dayP
            text(day.toString(), cx, cy - cap(p) / 2f, p, Paint.Align.CENTER)
            if (day in data.eventDays && !dry) canvas.drawCircle(cx, cy + cellH * 0.35f, cellH * 0.038f, fill(if (isToday) onAccent else ink))
        }
        return y0 + ((offset + days + 6) / 7) * cellH
    }

    /** Up to [maxRows] events as when/what pairs, stopping above [bottom]. Returns where the next caps may start. */
    internal fun SleepPage.agenda(left: Float, right: Float, capTop: Float, bottom: Float, size: Float, maxRows: Int = 5): Float {
        val wp = paint(strong, size, figures = true)
        val tp = paint(body, size)
        if (data.events.isEmpty()) return text(data.labels.nothingPlanned, left, capTop, tp) + size * 1.2f
        val widest = data.events.maxOf { width(wp, it.whenLabel) }
        var y = capTop
        if (widest > (right - left) * 0.36f) {
            // A narrow column: when above what, so neither is cut short.
            for (e in data.events.take(maxRows)) {
                if (y + cap(tp) * 2 + size * 0.6f > bottom) break
                val b = text(clip(e.whenLabel, wp, right - left), left, y, wp)
                y = text(clip(e.title, tp, right - left), left, b + size * 0.5f, tp) + size * 1.1f
            }
            return y
        }
        val whenW = widest + size
        val step = size * 2.05f
        for (e in data.events.take(maxRows)) {
            if (y + cap(tp) > bottom) break
            text(clip(e.whenLabel, wp, whenW - size), left, y, wp)
            text(clip(e.title, tp, right - left - whenW), left + whenW, y, tp)
            y += step
        }
        return y
    }

    /** A reading for a narrow column: the legend in small capitals above, the value below, a hairline under both. */
    internal fun SleepPage.stackedRow(legend: String, value: String, left: Float, right: Float, capTop: Float, size: Float, ruleBelow: Boolean = true): Float {
        val lb = legend(clip(legend, paint(strong, s * 0.02f, tracking = 0.14f), right - left), left, capTop, s * 0.02f)
        val vp = paint(strong, size, figures = true)
        val base = text(clip(value, vp, right - left), left, lb + size * 0.55f, vp)
        val next = base + size * 0.8f
        if (ruleBelow) rule(left, right, next, max(1f, s * 0.0014f))
        return next + size * 0.8f
    }

    /** A legend in small tracked capitals. Returns its baseline. */
    internal fun SleepPage.legend(t: String, x: Float, capTop: Float, size: Float = s * 0.022f, align: Paint.Align = Paint.Align.LEFT, color: Int = ink): Float =
        text(upper(t), x, capTop, paint(strong, size, color, tracking = 0.14f), align)

    /** A lamp and the battery reading; the lamp is lit while charging. It steps down to fit [maxW]. Returns the baseline. */
    internal fun SleepPage.batteryLine(x: Float, capTop: Float, size0: Float, alignRight: Boolean = false, maxW: Float = Float.MAX_VALUE): Float {
        val label = data.batteryLine
        val whole = width(paint(strong, size0, figures = true), label) + size0 * (0.72f + 0.5f)
        val size = if (whole > maxW) size0 * maxW / whole else size0
        val p = paint(strong, size, figures = true)
        val r = size * 0.36f
        val tw = width(p, label)
        val lampX = if (alignRight) x - tw - size * 0.5f - r else x + r
        lamp(lampX, capTop + cap(p) / 2f, r, data.charging)
        return if (alignRight) text(label, x, capTop, p, Paint.Align.RIGHT) else text(label, x + 2 * r + size * 0.5f, capTop, p)
    }

    /**
     * The tuning scale: 0 to 100 engraved like a Braun receiver dial, the battery figure riding the needle, the needle
     * itself in the accent. Returns the bottom of the legend under it.
     */
    internal fun SleepPage.tuningScale(left: Float, right: Float, top: Float, figSize: Float): Float {
        val fig = paint(display, figSize, figures = true, tracking = -0.02f)
        val pct = paint(display, figSize * 0.4f)
        val figCap = cap(fig)
        val tickP = paint(body, s * 0.024f, figures = true)
        val major = s * 0.05f
        val minorLen = s * 0.024f
        val base = top + figCap + s * 0.06f + major
        val inset = width(tickP, "100") / 2f
        val l = left + inset
        val r = right - inset
        val sp = r - l
        for (i in 0..100 step 2) {
            val x = l + sp * i / 100f
            val maj = i % 10 == 0
            val t = if (maj) s * 0.0034f else s * 0.0018f
            if (!dry) canvas.drawRect(x - t / 2f, base - if (maj) major else minorLen, x + t / 2f, base, fill(if (maj) ink else minor))
            if (i % 20 == 0) text(i.toString(), x, base + s * 0.026f, tickP, Paint.Align.CENTER)
        }
        rule(l, r, base, s * 0.005f)
        val level = data.battery.coerceIn(0, 100)
        val nx = l + sp * level / 100f
        val figText = level.toString()
        val gap = s * 0.01f
        val fw = width(fig, figText) + gap + width(pct, "%")
        val fx = (nx - fw / 2f).coerceIn(left, right - fw)
        val figBase = text(figText, fx, top, fig)
        text("%", fx + width(fig, figText) + gap, figBase - cap(pct), pct)
        if (!dry) {
            val nt = s * 0.01f
            val needle = RectF(nx - nt / 2f, figBase + s * 0.022f, nx + nt / 2f, base + s * 0.02f)
            canvas.drawRect(needle, fill(accent))
            canvas.drawRect(needle, stroke(ink, s * 0.0016f))
        }
        val labelsBase = base + s * 0.026f + cap(tickP)
        return legend(data.labels.battery, left, labelsBase + s * 0.05f)
    }

    /** A ring gauge for the battery, the arc in the accent, the figure in its centre. */
    private fun SleepPage.batteryRing(cx: Float, cy: Float, radius: Float) {
        val track = s * 0.004f
        val arcW = radius * 0.2f
        if (!dry) {
            canvas.drawCircle(cx, cy, radius - track / 2f, stroke(ink, track))
            val inner = radius - track - arcW / 2f - s * 0.006f
            val arc = RectF(cx - inner, cy - inner, cx + inner, cy + inner)
            canvas.drawArc(arc, -90f, 360f * data.battery.coerceIn(0, 100) / 100f, false, stroke(accent, arcW).apply { strokeCap = Paint.Cap.BUTT })
        }
        val fig = paint(display, radius * 0.52f, figures = true, tracking = -0.02f)
        val pct = paint(display, radius * 0.22f)
        val t = data.battery.toString()
        val gap = radius * 0.03f
        val gw = width(fig, t) + gap + width(pct, "%")
        val x0 = cx - gw / 2f
        val base = text(t, x0, cy - cap(fig) / 2f, fig)
        text("%", x0 + width(fig, t) + gap, base - cap(pct), pct)
        if (data.charging) legend(data.labels.charging, cx, cy + radius + s * 0.035f, align = Paint.Align.CENTER)
    }

    /** The owner line at the foot of a face: name strong, contact after it. */
    private fun SleepPage.ownerLine(left: Float, right: Float, capTop: Float, size: Float): Float {
        val np = paint(strong, size)
        val cp = paint(body, size)
        val name = spec.ownerName.trim()
        val nw = min(width(np, name), (right - left) * 0.5f)
        text(clip(name, np, nw), left, capTop, np)
        return text(clip(spec.ownerContact.trim(), cp, right - left - nw - size), left + nw + if (name.isEmpty()) 0f else size, capTop, cp)
    }

    private fun SleepPage.hasOwner() = spec.ownerName.isNotBlank() || spec.ownerContact.isNotBlank()

    // ---------- Almanac ----------

    fun almanac(p: SleepPage) {
        with(p) {
            val f = frame()
            val tight = if (spec.leavesClockRoom) 0.76f else 1f
            val headP = paint(strong, s * 0.032f, tracking = 0.14f)
            val showAgenda = spec.shows(SleepElement.Agenda) && data.calendarAllowed
            if (!landscape) {
                text(upper(data.weekday), f.left, f.top, headP)
                val r1 = text(data.year, f.right, f.top, headP, Paint.Align.RIGHT) + s * 0.035f
                rule(f.left, f.right, r1, s * 0.004f)
                val numSize = s * 0.40f * tight
                val numP = paint(display, numSize, figures = true, tracking = -0.03f)
                val numTop = r1 + s * 0.065f
                val numBase = text(data.dayNumber, f.left, numTop, numP)
                almanacSide(f.left + width(numP, data.dayNumber) + s * 0.04f, f.right, numTop, numBase, tight)
                val r2 = numBase + s * 0.06f
                rule(f.left, f.right, r2, s * 0.004f)
                val gridBottom = monthGrid(f.left, f.right, r2 + s * 0.055f, s * 0.09f * tight)
                if (showAgenda && gridBottom + s * 0.1f < f.bottom) {
                    rule(f.left, f.right, gridBottom + s * 0.015f, max(1f, s * 0.0015f))
                    agenda(f.left, f.right, gridBottom + s * 0.06f, f.bottom, s * 0.031f)
                }
            } else {
                val leftW = f.width() * 0.4f
                val lx2 = f.left + leftW
                val rx1 = lx2 + f.width() * 0.07f
                text(upper(data.weekday), f.left, f.top, headP)
                val r1 = text(data.year, f.right, f.top, headP, Paint.Align.RIGHT) + s * 0.035f
                rule(f.left, f.right, r1, s * 0.004f)
                val numSize = min(s * 0.40f * tight, fit(data.dayNumber, display, leftW, s * 0.46f))
                val numP = paint(display, numSize, figures = true, tracking = -0.03f)
                val numBase = text(data.dayNumber, f.left, r1 + s * 0.06f, numP)
                val monthP = paint(display, fit(data.month, display, leftW, s * 0.085f * tight))
                var y = text(data.month, f.left, numBase + s * 0.065f, monthP) + s * 0.06f
                if (spec.shows(SleepElement.PutDown)) {
                    val lp = paint(body, s * 0.028f)
                    val lead = text(data.putDownLead, f.left, y, lp)
                    y = text(data.putDownTime, f.left, lead + s * 0.025f, paint(display, s * 0.06f * tight, figures = true)) + s * 0.05f
                }
                if (spec.shows(SleepElement.Battery)) batteryLine(f.left, y + s * 0.01f, s * 0.036f, maxW = leftW)
                if (!dry) canvas.drawRect(lx2 + f.width() * 0.035f - s * 0.0015f, r1 + s * 0.06f, lx2 + f.width() * 0.035f + s * 0.0015f, f.bottom, fill(ink))
                val gridBottom = monthGrid(rx1, f.right, r1 + s * 0.06f, s * 0.084f * tight)
                if (showAgenda && gridBottom + s * 0.1f < f.bottom) {
                    rule(rx1, f.right, gridBottom + s * 0.015f, max(1f, s * 0.0015f))
                    agenda(rx1, f.right, gridBottom + s * 0.06f, f.bottom, s * 0.03f, 3)
                }
            }
        }
    }

    /** Month, put-down time and battery beside the big day number, scaled with it. */
    private fun SleepPage.almanacSide(x1: Float, x2: Float, top: Float, base: Float, k: Float) {
        val colW = x2 - x1
        val monthP = paint(display, fit(data.month, display, colW, s * 0.085f * k, s * 0.03f))
        var y = text(data.month, x1, top, monthP) + s * 0.055f * k
        if (spec.shows(SleepElement.PutDown)) {
            val lp = paint(body, s * 0.028f * k)
            val lead = text(clip(data.putDownLead, lp, colW), x1, y, lp)
            text(data.putDownTime, x1, lead + s * 0.022f * k, paint(display, fit(data.putDownTime, display, colW, s * 0.07f * k), figures = true))
        }
        if (spec.shows(SleepElement.Battery)) {
            val bp = paint(strong, s * 0.04f * k, figures = true)
            batteryLine(x1, base - cap(bp), s * 0.04f * k, maxW = colW)
        }
    }

    // ---------- Instrument ----------

    fun instrument(p: SleepPage) {
        with(p) {
            val f = frame()
            val rows = buildList {
                if (spec.shows(SleepElement.PutDown)) add(data.putDownLead to data.putDownTime)
                if (spec.shows(SleepElement.Agenda) && data.calendarAllowed) {
                    val e = data.events.firstOrNull()
                    add(data.labels.next to (e?.let { "${it.title}  ·  ${it.whenLabel}" } ?: data.labels.nothingPlanned))
                }
                if (spec.shows(SleepElement.Owner) && hasOwner()) add(spec.ownerName.trim() to spec.ownerContact.trim())
            }
            val rowSize = s * 0.032f
            val rowStep = if (landscape) s * 0.0144f + rowSize * 2.87f else rowSize * 0.72f + rowSize * 1.9f
            val colRight = if (landscape) f.left + f.width() * 0.4f else f.right
            val wkMax = if (landscape) s * 0.1f else s * 0.11f
            val wkP = paint(display, fit(data.weekday, display, (colRight - f.left) * (if (landscape) 1f else 0.78f), wkMax, tracking = -0.02f), tracking = -0.02f)
            val wkBase = text(data.weekday, f.left, f.top, wkP)
            val dateBase = text(data.dateYear, f.left, wkBase + s * 0.04f, paint(strong, s * 0.04f))
            val lr = s * 0.022f
            val lampY = f.top + cap(wkP) / 2f
            lamp(f.right - lr, lampY, lr, data.charging)
            legend(if (data.charging) data.labels.charging else data.labels.onBattery, f.right - lr * 2 - s * 0.025f, lampY - cap(paint(strong, s * 0.022f)) / 2f, align = Paint.Align.RIGHT)
            // Rows end on the frame's bottom line: the last baseline sits where the frame ends.
            val lastBase = if (landscape) s * 0.0144f + rowSize * 1.27f else rowSize * 0.72f
            val rowsTop = f.bottom - (rows.size - 1) * rowStep - lastBase
            if (rows.isNotEmpty()) {
                rule(f.left, colRight, rowsTop - rowSize * 1.2f, s * 0.004f)
                var y = rowsTop
                rows.forEachIndexed { i, (l, v) ->
                    y = if (landscape) stackedRow(l, v, f.left, colRight, y, rowSize, ruleBelow = i < rows.size - 1)
                    else row(l, v, f.left, colRight, y, rowSize, ruleBelow = i < rows.size - 1)
                }
            }
            val scaleLeft = if (landscape) colRight + f.width() * 0.08f else f.left
            val figSize = if (landscape) s * 0.19f else s * 0.21f
            dry = true
            val scaleH = tuningScale(scaleLeft, f.right, 0f, figSize)
            dry = false
            val midTop = if (landscape) f.top + cap(wkP) + s * 0.08f else dateBase + s * 0.05f
            val midBottom = if (landscape || rows.isEmpty()) f.bottom else rowsTop - rowSize * 1.2f
            tuningScale(scaleLeft, f.right, midTop + max(0f, (midBottom - midTop - scaleH) / 2f), figSize)
        }
    }

    // ---------- Poster ----------

    fun poster(p: SleepPage) {
        with(p) {
            val f = frame()
            val wkP = paint(display, fit(data.weekday, display, f.width(), if (landscape) s * 0.24f else s * 0.28f, tracking = -0.035f), tracking = -0.035f)
            val wkBase = text(data.weekday, f.left, f.top, wkP)
            val footer = spec.shows(SleepElement.PutDown) || spec.shows(SleepElement.Battery)
            val fs = s * 0.03f
            if (!landscape) {
                val dateBase = text(data.dateYear, f.left, wkBase + s * 0.05f, paint(strong, s * 0.052f))
                val barTop = dateBase + s * 0.06f
                if (!dry) canvas.drawRect(f.left, barTop, f.right, barTop + s * 0.024f, fill(accent))
                val footTop = if (footer) f.bottom - cap(paint(body, fs)) else f.bottom
                if (footer) {
                    rule(f.left, f.right, footTop - s * 0.04f, max(1f, s * 0.0015f))
                    if (spec.shows(SleepElement.PutDown)) text(data.putDownLine, f.left, footTop, paint(body, fs))
                    if (spec.shows(SleepElement.Battery)) text(data.batteryLine, f.right, footTop, paint(body, fs, figures = true), Paint.Align.RIGHT)
                }
                posterBlock(f.left, f.right, barTop + s * 0.024f + s * 0.09f, (if (footer) footTop - s * 0.04f else f.bottom) - s * 0.07f)
            } else {
                val y0 = wkBase + s * 0.075f
                val leftW = f.width() * 0.32f
                val dateP = paint(strong, fit(data.dateYear, strong, leftW, s * 0.045f))
                val dateBase = text(data.dateYear, f.left, y0, dateP)
                if (!dry) canvas.drawRect(f.left, dateBase + s * 0.05f, f.left + leftW, dateBase + s * 0.05f + s * 0.022f, fill(accent))
                var y = f.bottom
                if (spec.shows(SleepElement.Battery)) y = text(data.batteryLine, f.left, f.bottom - cap(paint(body, fs)), paint(body, fs, figures = true)) - cap(paint(body, fs)) - fs * 1.1f
                if (spec.shows(SleepElement.PutDown)) text(data.putDownLine, f.left, y - cap(paint(body, fs)), paint(body, fs))
                posterBlock(f.left + leftW + f.width() * 0.06f, f.right, y0, f.bottom)
            }
        }
    }

    /** The lower half of the poster: the quote set as large as it will go, or the day number filling the space. */
    private fun SleepPage.posterBlock(left: Float, right: Float, top: Float, bottom: Float) {
        val height = bottom - top
        if (height <= s * 0.05f) return
        if (spec.shows(SleepElement.Quote) && data.quote.isNotBlank()) {
            val ap = paint(strong, s * 0.028f, tracking = 0.12f)
            val authorH = if (data.quoteAuthor.isNotBlank()) cap(ap) + s * 0.06f else 0f
            val l = fitLayout("“${data.quote}”", display, right - left, height - authorH, if (landscape) s * 0.14f else s * 0.16f, s * 0.035f, spacing = 1.06f)
            val qb = draw(l, left, top)
            if (data.quoteAuthor.isNotBlank()) text("— " + upper(data.quoteAuthor), left, qb + s * 0.06f, ap)
        } else {
            val np = paint(display, min(height / 0.72f, s * 0.62f), figures = true, tracking = -0.04f)
            val size = fit(data.dayNumber, display, right - left, np.textSize)
            val fp = paint(display, size, figures = true, tracking = -0.04f)
            text(data.dayNumber, right, bottom - cap(fp), fp, Paint.Align.RIGHT)
            legend(data.month, left, top, s * 0.03f)
        }
    }

    // ---------- Under the clock ----------

    fun underClock(p: SleepPage) {
        with(p) {
            val f = frame()
            // A rule and a lamp under the Boox clock, so the live time reads as part of the face.
            rule(margin, w - margin, clockZoneBottom, s * 0.003f)
            lamp(w / 2f, clockZoneBottom, s * 0.013f)
            val top = f.top + s * 0.03f
            val ringR = s * 0.115f
            val c1Right = if (landscape) f.left + f.width() * 0.4f else f.right - ringR * 2 - s * 0.06f
            val wkP = paint(display, fit(data.weekday, display, c1Right - f.left, s * 0.1f, tracking = -0.02f), tracking = -0.02f)
            val wkBase = text(data.weekday, f.left, top, wkP)
            val dateBase = text(data.dateYear, f.left, wkBase + s * 0.035f, paint(strong, fit(data.dateYear, strong, c1Right - f.left, s * 0.04f)))
            val showRing = spec.shows(SleepElement.Battery)
            val rs = s * 0.03f
            if (!landscape) {
                if (showRing) batteryRing(f.right - ringR, top + ringR, ringR)
                val blockBottom = max(dateBase, if (showRing) top + ringR * 2 + s * 0.03f else dateBase) + s * 0.07f
                rule(f.left, f.right, blockBottom, s * 0.004f)
                var y = blockBottom + s * 0.06f
                val ownerTop = f.bottom - cap(paint(strong, rs))
                val hasOwnerLine = spec.shows(SleepElement.Owner) && hasOwner()
                val limit = if (hasOwnerLine) ownerTop - s * 0.07f else f.bottom
                if (spec.shows(SleepElement.PutDown)) y = row(data.putDownLead, data.putDownTime, f.left, f.right, y, rs)
                if (spec.shows(SleepElement.Agenda) && data.calendarAllowed) {
                    val lb = legend(data.labels.next, f.left, y + s * 0.01f)
                    y = agenda(f.left, f.right, lb + s * 0.04f, limit, rs, 4) + s * 0.02f
                }
                if (spec.shows(SleepElement.Note) && spec.note.isNotBlank() && limit - y > s * 0.06f) {
                    draw(fitLayout(spec.note.trim(), strong, f.width(), limit - y - s * 0.02f, s * 0.06f, s * 0.03f), f.left, y + s * 0.01f)
                }
                if (hasOwnerLine) {
                    rule(f.left, f.right, ownerTop - s * 0.04f, max(1f, s * 0.0015f))
                    ownerLine(f.left, f.right, ownerTop, rs)
                }
            } else {
                val ringCx = f.left + f.width() * 0.52f
                if (showRing) batteryRing(ringCx, top + ringR, ringR)
                val c3Left = f.left + f.width() * 0.66f
                var y = dateBase + s * 0.08f
                val hasOwnerLine = spec.shows(SleepElement.Owner) && hasOwner()
                val ownerTop = f.bottom - cap(paint(strong, rs))
                if (spec.shows(SleepElement.PutDown)) y = stackedRow(data.putDownLead, data.putDownTime, f.left, c1Right, y, rs)
                val limit = if (hasOwnerLine) ownerTop - s * 0.07f else f.bottom
                if (spec.shows(SleepElement.Note) && spec.note.isNotBlank() && limit - y > s * 0.06f) {
                    draw(fitLayout(spec.note.trim(), strong, c1Right - f.left, limit - y - s * 0.02f, s * 0.055f, s * 0.028f), f.left, y + s * 0.01f)
                }
                if (hasOwnerLine) ownerLine(f.left, c1Right, ownerTop, rs)
                if (spec.shows(SleepElement.Agenda) && data.calendarAllowed) {
                    val lb = legend(data.labels.next, c3Left, top)
                    agenda(c3Left, f.right, lb + s * 0.05f, f.bottom, rs, 6)
                }
            }
        }
    }

    // ---------- Photo ----------

    fun photo(p: SleepPage, photo: Bitmap?) {
        with(p) {
            if (photo == null) {
                lamp(w / 2f, h * 0.42f, s * 0.025f, false)
                text(data.labels.photoEmpty, w / 2f, h * 0.42f + s * 0.07f, paint(body, s * 0.034f), Paint.Align.CENTER)
                return
            }
            val dst: RectF
            val src: Rect
            val pr = photo.width.toFloat() / photo.height
            val cr = w / h
            if (spec.photoFit == PhotoFit.Fill) {
                dst = RectF(0f, 0f, w, h)
                src = if (pr > cr) {
                    val sw = (photo.height * cr).toInt()
                    Rect((photo.width - sw) / 2, 0, (photo.width + sw) / 2, photo.height)
                } else {
                    val sh = (photo.width / cr).toInt()
                    Rect(0, (photo.height - sh) / 2, photo.width, (photo.height + sh) / 2)
                }
            } else {
                src = Rect(0, 0, photo.width, photo.height)
                dst = if (pr > cr) RectF(0f, (h - w / pr) / 2f, w, (h + w / pr) / 2f) else RectF((w - h * pr) / 2f, 0f, (w + h * pr) / 2f, h)
            }
            if (!dry) {
                canvas.drawBitmap(photo, src, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                if (spec.dither) SleepDither.floydSteinberg(bitmap, Rect(dst.left.toInt(), dst.top.toInt(), dst.right.toInt(), dst.bottom.toInt()))
            }
            val caption = spec.caption.trim()
            val showDate = spec.shows(SleepElement.Date)
            val right = buildList {
                if (spec.shows(SleepElement.PutDown)) add(data.putDownLine)
                if (spec.shows(SleepElement.Battery)) add(data.batteryLine)
            }
            if (caption.isEmpty() && !showDate && right.isEmpty()) return
            val f = frame()
            val fp = paint(strong, s * 0.042f)
            val sp = paint(body, s * 0.028f)
            val rp = paint(body, s * 0.028f, figures = true)
            val first = caption.ifEmpty { if (showDate) data.dateShort else "" }
            val second = if (showDate && caption.isNotEmpty()) data.dateLong else ""
            val lineGap = s * 0.03f
            val leftH = cap(fp) + if (second.isNotEmpty()) lineGap + cap(sp) else 0f
            val rightH = if (right.isEmpty()) 0f else right.size * cap(rp) + (right.size - 1) * lineGap
            val top = f.bottom - max(leftH, rightH)
            // A paper band from the caption to the bottom edge, so the Boox status bar sits inside it rather than over the picture.
            if (!dry) canvas.drawRect(0f, top - s * 0.07f, w, h, fill(paper))
            val leftW = f.width() * 0.58f
            val fb = text(clip(first, fp, leftW), f.left, f.bottom - leftH, fp)
            if (second.isNotEmpty()) text(clip(second, sp, leftW), f.left, fb + lineGap, sp)
            var ry = f.bottom - rightH
            val rightW = f.width() - leftW - s * 0.04f
            right.forEach { t -> ry = text(clip(t, rp, rightW), f.right, ry, rp, Paint.Align.RIGHT) + lineGap }
        }
    }

    // ---------- Note ----------

    fun note(p: SleepPage) {
        with(p) {
            val f = frame()
            val note = spec.note.trim().ifEmpty { data.labels.noteEmpty }
            val tf = if (note.length <= 80) display else strong
            val fs = s * 0.03f
            if (!landscape) {
                var y = f.top
                if (spec.shows(SleepElement.Date)) {
                    val hp = paint(strong, s * 0.03f, tracking = 0.1f)
                    val r = s * 0.013f
                    lamp(f.left + r, y + cap(hp) / 2f, r)
                    y = text(upper(data.weekday) + "   " + data.dateShort, f.left + 2 * r + s * 0.025f, y, hp) + s * 0.045f
                    rule(f.left, f.right, y, s * 0.004f)
                    y += s * 0.09f
                }
                val footer = spec.shows(SleepElement.PutDown) || spec.shows(SleepElement.Battery)
                val footTop = f.bottom - cap(paint(body, fs))
                if (footer) {
                    rule(f.left, f.right, footTop - s * 0.045f, max(1f, s * 0.0015f))
                    if (spec.shows(SleepElement.PutDown)) text(data.putDownLine, f.left, footTop, paint(body, fs))
                    if (spec.shows(SleepElement.Battery)) text(data.batteryLine, f.right, footTop, paint(body, fs, figures = true), Paint.Align.RIGHT)
                }
                val bottom = if (footer) footTop - s * 0.11f else f.bottom
                draw(fitLayout(note, tf, f.width(), bottom - y, s * 0.13f, s * 0.034f, spacing = 1.14f), f.left, y)
            } else {
                val leftW = f.width() * 0.26f
                val divX = f.left + leftW + f.width() * 0.035f
                if (!dry) canvas.drawRect(divX - s * 0.0015f, f.top, divX + s * 0.0015f, f.bottom, fill(ink))
                val r = s * 0.016f
                lamp(f.left + r, f.top + r, r)
                var y = f.top + 2 * r + s * 0.06f
                if (spec.shows(SleepElement.Date)) {
                    y = text(data.weekday, f.left, y, paint(display, fit(data.weekday, display, leftW, s * 0.075f))) + s * 0.035f
                    y = text(data.dateShort, f.left, y, paint(strong, fit(data.dateShort, strong, leftW, s * 0.038f))) + s * 0.06f
                }
                var by = f.bottom
                if (spec.shows(SleepElement.Battery)) {
                    val bp = paint(strong, s * 0.03f, figures = true)
                    batteryLine(f.left, by - cap(bp), s * 0.03f)
                    by -= cap(bp) + s * 0.06f
                }
                if (spec.shows(SleepElement.PutDown)) {
                    val tp = paint(display, s * 0.055f, figures = true)
                    val tb = text(data.putDownTime, f.left, by - cap(tp), tp)
                    text(clip(data.putDownLead, paint(body, s * 0.026f), leftW), f.left, tb - cap(tp) - s * 0.025f - cap(paint(body, s * 0.026f)), paint(body, s * 0.026f))
                }
                val x1 = divX + f.width() * 0.035f
                draw(fitLayout(note, tf, f.right - x1, f.height(), s * 0.13f, s * 0.034f, spacing = 1.14f), x1, f.top)
            }
        }
    }

    // ---------- Return card ----------

    fun returnCard(p: SleepPage) {
        with(p) {
            val f = frame()
            val cardH = if (landscape) min(f.height() * 0.84f, short * 0.72f) else min(f.height() * 0.62f, short * 0.82f)
            val top = f.top + (f.height() - cardH) * (if (landscape) 0.5f else 0.42f)
            val card = RectF(f.left, top, f.right, top + cardH)
            val radius = s * 0.04f
            val rim = s * 0.006f
            val stripH = s * 0.11f
            if (!dry) {
                canvas.drawPath(SleepPage.topRounded(RectF(card.left, card.top, card.right, card.top + stripH), radius), fill(accent))
                canvas.drawRoundRect(RectF(card.left + rim / 2, card.top + rim / 2, card.right - rim / 2, card.bottom - rim / 2), radius, radius, stroke(ink, rim))
                canvas.drawRect(card.left, card.top + stripH - rim / 2, card.right, card.top + stripH + rim / 2, fill(ink))
            }
            val pad = s * 0.065f
            val hp = paint(strong, s * 0.03f, onAccent, tracking = 0.12f)
            text(upper(data.labels.returnHeading), card.left + pad, card.top + (stripH - cap(hp)) / 2f, hp)
            val x = card.left + pad
            val innerRight = if (landscape) card.left + card.width() * 0.58f else card.right - pad
            val name = spec.ownerName.trim().ifEmpty { data.labels.ownerEmpty }
            val np = paint(display, fit(name, display, innerRight - x, s * 0.13f, s * 0.04f, tracking = -0.02f), tracking = -0.02f)
            val nameBase = text(clip(name, np, innerRight - x), x, card.top + stripH + s * 0.09f, np)
            if (spec.ownerContact.isNotBlank()) {
                val cl = layout(spec.ownerContact.trim(), paint(strong, s * 0.048f), innerRight - x, maxLines = 3)
                draw(cl, x, nameBase + s * 0.07f)
            }
            val extras = buildList {
                if (spec.shows(SleepElement.Date)) add(if (landscape) data.dateYear else data.dateLong)
                if (spec.shows(SleepElement.PutDown)) add(data.putDownLine)
            }
            val ep = paint(body, s * 0.03f)
            val lp = paint(body, s * 0.026f)
            if (!landscape) {
                if (spec.reward.isNotBlank()) {
                    val rl = layout(spec.reward.trim(), paint(strong, s * 0.04f), card.width() - 2 * pad, maxLines = 2)
                    val rTop = card.bottom - pad - span(rl)
                    val lgTop = rTop - s * 0.05f - cap(paint(strong, s * 0.022f))
                    rule(card.left + pad, card.right - pad, lgTop - s * 0.045f, max(1f, s * 0.0015f))
                    legend(data.labels.reward, x, lgTop)
                    draw(rl, x, rTop)
                }
                var y = card.bottom + s * 0.08f
                extras.forEach { t -> y = text(clip(t, ep, f.width()), w / 2f, y, ep, Paint.Align.CENTER) + s * 0.03f }
            } else {
                val divX = card.left + card.width() * 0.62f
                if (!dry) canvas.drawRect(divX - s * 0.001f, card.top + stripH + pad, divX + s * 0.001f, card.bottom - pad, fill(ink))
                val rx = divX + pad * 0.8f
                val rw = card.right - pad - rx
                var y = card.top + stripH + s * 0.09f
                if (spec.reward.isNotBlank()) {
                    val lb = legend(data.labels.reward, rx, y)
                    y = draw(layout(spec.reward.trim(), paint(strong, s * 0.04f), rw, maxLines = 4), rx, lb + s * 0.04f) + s * 0.08f
                }
                var ey = card.bottom - pad - cap(ep)
                extras.reversed().forEach { t -> text(clip(t, lp, rw), rx, ey, lp); ey -= cap(lp) + s * 0.03f }
            }
        }
    }

    // ---------- Minimal ----------

    fun minimal(p: SleepPage) {
        with(p) {
            val f = frame()
            val extras = buildList {
                if (spec.shows(SleepElement.PutDown)) add(data.putDownLine)
                if (spec.shows(SleepElement.Battery)) add(data.batteryLine)
            }.joinToString("   ·   ")
            val wkP = paint(strong, s * 0.032f, tracking = 0.2f)
            val ep = paint(body, s * 0.03f, figures = true)
            val r = s * 0.03f
            if (!landscape) {
                val cy = f.top + f.height() * 0.44f
                lamp(w / 2f, cy - s * 0.22f, r)
                val wkBase = text(upper(data.weekday), w / 2f, cy - s * 0.13f, wkP, Paint.Align.CENTER)
                val dp = paint(display, fit(data.dateShort, display, f.width(), s * 0.12f), tracking = -0.02f)
                val db = text(data.dateShort, w / 2f, wkBase + s * 0.05f, dp, Paint.Align.CENTER)
                if (extras.isNotEmpty()) text(clip(extras, ep, f.width()), w / 2f, db + s * 0.08f, ep, Paint.Align.CENTER)
            } else {
                val dp = paint(display, fit(data.dateShort, display, f.width() * 0.7f, s * 0.13f), tracking = -0.02f)
                val textW = max(width(dp, data.dateShort), max(width(wkP, upper(data.weekday)), if (extras.isEmpty()) 0f else width(ep, extras)))
                val gap = s * 0.06f
                val x0 = (w - (2 * r + gap + textW)) / 2f
                val tx = x0 + 2 * r + gap
                val groupH = cap(wkP) + s * 0.05f + cap(dp) + if (extras.isEmpty()) 0f else s * 0.08f + cap(ep)
                val top = f.top + (f.height() - groupH) * 0.46f
                val wkBase = text(upper(data.weekday), tx, top, wkP)
                val db = text(data.dateShort, tx, wkBase + s * 0.05f, dp)
                lamp(x0 + r, wkBase + s * 0.05f + cap(dp) / 2f, r)
                if (extras.isNotEmpty()) text(extras, tx, db + s * 0.08f, ep)
            }
        }
    }
}

/** Sticker plates for the Transparent style: measured dry, then painted, so each plate fits what it holds. */
internal object SleepOverlays {
    private fun upper(t: String) = t.uppercase(Locale.getDefault())

    fun draw(p: SleepPage) {
        with(p) {
            // The plate's rim sits on the margin line, so no ink comes closer to an edge than on a full-screen face.
            val f = frame()
            when (spec.overlay) {
                SleepOverlay.BottomBand, SleepOverlay.TopBand -> {
                    val x1 = f.left
                    val x2 = f.right
                    val pad = s * 0.06f
                    dry = true
                    val ch = band(x1 + pad, 0f, x2 - x1 - 2 * pad)
                    dry = false
                    val plateH = ch + 2 * pad
                    val top = if (spec.overlay == SleepOverlay.BottomBand) f.bottom - plateH else f.top
                    plate(RectF(x1, top, x2, top + plateH), s * 0.045f)
                    band(x1 + pad, top + pad, x2 - x1 - 2 * pad)
                }
                SleepOverlay.Corner -> {
                    val cw = min(short * 0.5f, f.width())
                    val x2 = f.right
                    val pad = s * 0.055f
                    val (skip, ch) = fitting(f.height() - 2 * pad) { stack(x2 - cw + pad, 0f, cw - 2 * pad, big = false, skip = it) }
                    plate(RectF(x2 - cw, f.bottom - ch - 2 * pad, x2, f.bottom), s * 0.045f)
                    stack(x2 - cw + pad, f.bottom - ch - pad, cw - 2 * pad, big = false, skip = skip)
                }
                SleepOverlay.Centre -> {
                    val cw = min(if (landscape) short * 0.8f else short * 0.66f, f.width())
                    val x1 = (w - cw) / 2f
                    val pad = s * 0.07f
                    val (skip, ch) = fitting(f.height() - 2 * pad) { stack(x1 + pad, 0f, cw - 2 * pad, big = true, skip = it) }
                    val top = f.top + max(0f, (f.height() - ch - 2 * pad) / 2f)
                    plate(RectF(x1, top, x1 + cw, top + ch + 2 * pad), s * 0.05f)
                    stack(x1 + pad, top + pad, cw - 2 * pad, big = true, skip = skip)
                }
            }
        }
    }

    /** Drops the most optional elements, one at a time, until the card fits [room]. Measures dry; returns what to skip and the height. */
    private fun SleepPage.fitting(room: Float, measure: (Set<SleepElement>) -> Float): Pair<Set<SleepElement>, Float> {
        val order = listOf(SleepElement.Quote, SleepElement.Note, SleepElement.Owner, SleepElement.Agenda, SleepElement.PutDown)
        var skip = emptySet<SleepElement>()
        dry = true
        var h = measure(skip)
        for (e in order) {
            if (h <= room) break
            skip = skip + e
            h = measure(skip)
        }
        dry = false
        return skip to h
    }

    /** Date left, put-down time and battery right, then the next event and the owner on ruled lines. Returns height. */
    private fun SleepPage.band(x: Float, top: Float, width: Float): Float {
        val right = x + width
        var leftBottom = top
        if (spec.shows(SleepElement.Date)) {
            val wk = paint(display, fit(data.weekday, display, width * 0.55f, s * 0.065f))
            val b = text(data.weekday, x, top, wk)
            leftBottom = text(data.dateYear, x, b + s * 0.03f, paint(strong, s * 0.032f))
        }
        var rightBottom = top
        if (spec.shows(SleepElement.PutDown)) {
            val lp = paint(body, s * 0.024f)
            val lb = text(data.putDownLead, right, top, lp, Paint.Align.RIGHT)
            rightBottom = text(data.putDownTime, right, lb + s * 0.02f, paint(display, s * 0.05f, figures = true), Paint.Align.RIGHT)
        }
        if (spec.shows(SleepElement.Battery)) {
            val y = if (rightBottom > top) rightBottom + s * 0.03f else top
            rightBottom = batteryLineRight(right, y, s * 0.028f)
        }
        var y = max(leftBottom, rightBottom)
        if (spec.shows(SleepElement.Agenda) && data.calendarAllowed) {
            y = ruled(x, right, y)
            val e = data.events.firstOrNull()
            val lp = paint(strong, s * 0.022f, tracking = 0.14f)
            val lw = width(lp, upper(data.labels.next)) + s * 0.03f
            val tp = paint(body, s * 0.03f)
            text(upper(data.labels.next), x, y + (cap(tp) - cap(lp)) / 2f, lp)
            y = text(clip(e?.let { "${it.whenLabel}   ${it.title}" } ?: data.labels.nothingPlanned, tp, width - lw), x + lw, y, tp)
        }
        if (spec.shows(SleepElement.Owner) && (spec.ownerName.isNotBlank() || spec.ownerContact.isNotBlank())) {
            y = ruled(x, right, y)
            val tp = paint(body, s * 0.028f)
            y = text(clip(listOf(spec.ownerName.trim(), spec.ownerContact.trim()).filter { it.isNotEmpty() }.joinToString("   ·   "), tp, width), x, y, tp)
        }
        return y - top
    }

    /** Everything stacked in one column, for the corner and centre cards. Returns height. */
    private fun SleepPage.stack(x: Float, top: Float, width: Float, big: Boolean, skip: Set<SleepElement> = emptySet()): Float {
        fun on(e: SleepElement) = spec.shows(e) && e !in skip
        val right = x + width
        val k = if (big) 1.2f else 1f
        var y = top
        var any = false
        if (on(SleepElement.Date)) {
            val wk = paint(display, fit(data.weekday, display, width, s * 0.06f * k))
            val b = text(data.weekday, x, y, wk)
            y = text(data.dateYear, x, b + s * 0.028f, paint(strong, fit(data.dateYear, strong, width, s * 0.03f * k)))
            any = true
        }
        val rs = s * 0.027f * k
        if (on(SleepElement.PutDown)) {
            if (any) y = ruled(x, right, y)
            y = text(clip(data.putDownLine, paint(body, rs), width), x, y, paint(body, rs))
            any = true
        }
        if (on(SleepElement.Battery)) {
            y = if (any) y + rs * 1.1f else y
            y = batteryLineLeft(x, y, rs)
            any = true
        }
        if (on(SleepElement.Agenda) && data.calendarAllowed) {
            if (any) y = ruled(x, right, y)
            val tp = paint(body, rs)
            val wp = paint(strong, rs, figures = true)
            val list = data.events.take(if (big) 3 else 2)
            if (list.isEmpty()) y = text(data.labels.nothingPlanned, x, y, tp)
            list.forEachIndexed { i, e ->
                if (i > 0) y += rs * 1.1f
                val ww = width(wp, e.whenLabel) + rs
                text(e.whenLabel, x, y, wp)
                y = text(clip(e.title, tp, width - ww), x + ww, y, tp)
            }
            any = true
        }
        if (on(SleepElement.Note) && spec.note.isNotBlank()) {
            if (any) y = ruled(x, right, y)
            y = draw(layout(spec.note.trim(), paint(strong, s * 0.036f), width, maxLines = 4), x, y)
            any = true
        }
        if (on(SleepElement.Quote) && data.quote.isNotBlank()) {
            if (any) y = ruled(x, right, y)
            y = draw(layout("“${data.quote}”", paint(display, s * 0.04f), width, maxLines = 4, spacing = 1.08f), x, y)
            if (data.quoteAuthor.isNotBlank()) y = text("— " + upper(data.quoteAuthor), x, y + s * 0.04f, paint(strong, s * 0.022f, tracking = 0.12f))
            any = true
        }
        if (on(SleepElement.Owner) && (spec.ownerName.isNotBlank() || spec.ownerContact.isNotBlank())) {
            if (any) y = ruled(x, right, y)
            val np = paint(strong, rs)
            val b = text(clip(spec.ownerName.trim(), np, width), x, y, np)
            y = if (spec.ownerContact.isNotBlank()) text(clip(spec.ownerContact.trim(), paint(body, rs), width), x, b + rs * 0.9f, paint(body, rs)) else b
        }
        return y - top
    }

    /** A hairline under [y]; returns where the next caps start. */
    private fun SleepPage.ruled(x1: Float, x2: Float, y: Float): Float {
        rule(x1, x2, y + s * 0.03f, max(1f, s * 0.0015f))
        return y + s * 0.065f
    }

    private fun SleepPage.batteryLineRight(right: Float, capTop: Float, size: Float): Float {
        val p = paint(strong, size, figures = true)
        val r = size * 0.36f
        lamp(right - width(p, data.batteryLine) - size * 0.5f - r, capTop + cap(p) / 2f, r, data.charging)
        return text(data.batteryLine, right, capTop, p, Paint.Align.RIGHT)
    }

    private fun SleepPage.batteryLineLeft(x: Float, capTop: Float, size: Float): Float {
        val p = paint(strong, size, figures = true)
        val r = size * 0.36f
        lamp(x + r, capTop + cap(p) / 2f, r, data.charging)
        return text(data.batteryLine, x + 2 * r + size * 0.5f, capTop, p)
    }
}
