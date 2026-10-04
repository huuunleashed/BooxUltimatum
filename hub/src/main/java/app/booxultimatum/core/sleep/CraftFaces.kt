package app.booxultimatum.core.sleep

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave
import app.booxultimatum.core.sleep.SleepFaces.legend
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Faces built as objects: a cube clock in perspective, a split-flap clock, a Braun-style LCD and a word clock. Each
 * keeps time while the tablet sleeps and says honestly when it was last drawn (the foot line), like the other live
 * faces. Shading is done with ink patterns, never soft greys, because e-ink shows patterns far better.
 */
internal object CraftFaces {

    // ---------- Cube ----------

    private class P3(val x: Double, val y: Double, val z: Double)

    /**
     * A cube desk clock in true perspective: the time on the side facing you, the date on the side in shade (solid
     * ink), a round accent button on the top, and a cast shadow in stipple or hatching. The readings stand beside it.
     */
    fun cube(p: SleepPage) {
        with(p) {
            val f = frame()
            val t = hm(FaceOptions.CUBE_HOURS)
            val shade = spec.option(FaceOptions.CUBE_SHADE)
            val readings = LiveFaces.readings(p)
            val box: RectF
            if (!landscape) {
                val foot = LiveFaces.footLine(p, f)
                val top = footRows(readings, f.left, f.right, foot, s * 0.036f)
                box = RectF(f.left, f.top, f.right, top - s * 0.06f)
            } else {
                val colL = f.left + f.width() * 0.68f
                val foot = LiveFaces.footLine(p, RectF(colL, f.top, f.right, f.bottom))
                box = RectF(f.left, f.top, colL - f.width() * 0.04f, f.bottom)
                val lp = paint(strong, s * 0.03f, tracking = 0.14f)
                val lb = text(upperL(data.weekday), colL, f.top, lp)
                val dateBase = text(data.dateYear, colL, lb + s * 0.035f, paint(strong, fit(data.dateYear, strong, f.right - colL, s * 0.04f)))
                rule(colL, f.right, dateBase + s * 0.05f, s * 0.004f)
                columnRows(readings, colL, f.right, dateBase + s * 0.1f, foot - s * 0.05f, s * 0.042f)
            }
            cubeObject(box, t, shade)
        }
    }

    private fun SleepPage.cubeObject(box: RectF, t: Hm, shade: String) {
        if (dry) return
        val yaw = Math.toRadians(30.0)
        val pitch = Math.toRadians(23.0)
        val dist = 6.2
        fun view(v: P3): P3 {
            val x1 = v.x * cos(yaw) + v.z * sin(yaw)
            val z1 = -v.x * sin(yaw) + v.z * cos(yaw)
            val y2 = v.y * cos(pitch) + z1 * sin(pitch)
            val z2 = -v.y * sin(pitch) + z1 * cos(pitch)
            return P3(x1, y2, z2 + dist)
        }
        fun flat(v: P3): Pair<Double, Double> { val q = view(v); return q.x / q.z to -q.y / q.z }
        // Light from the upper left, in front: the right side is in shade and the shadow falls behind it.
        val light = P3(0.5, -1.0, 0.62)
        val corners = listOf(-1.0, 1.0).flatMap { x -> listOf(-1.0, 1.0).flatMap { y -> listOf(-1.0, 1.0).map { z -> P3(x, y, z) } } }
        val onFloor = corners.map { c -> val k = (-1.0 - c.y) / light.y; c.x + light.x * k to c.z + light.z * k }
        val hull = hull(onFloor).map { (x, z) -> P3(x, -1.0, z) }
        val all = (corners + hull).map { flat(it) }
        val cube = corners.map { flat(it) }
        val minX = all.minOf { it.first }; val maxX = all.maxOf { it.first }
        val minY = all.minOf { it.second }; val maxY = all.maxOf { it.second }
        val k = min(box.width() / (maxX - minX), box.height() / (maxY - minY)) * 0.96
        // The cube itself sits in the middle of its room; the shadow may push it aside, never out.
        val cubeX = (cube.minOf { it.first } + cube.maxOf { it.first }) / 2
        val cubeY = (cube.minOf { it.second } + cube.maxOf { it.second }) / 2
        var ox = box.centerX() - cubeX * k
        var oy = box.centerY() - cubeY * k
        ox -= max(0.0, ox + maxX * k - box.right) - max(0.0, box.left - (ox + minX * k))
        oy -= max(0.0, oy + maxY * k - box.bottom) - max(0.0, box.top - (oy + minY * k))
        fun pt(v: P3): FloatArray { val (x, y) = flat(v); return floatArrayOf((ox + x * k).toFloat(), (oy + y * k).toFloat()) }
        fun quad(vararg v: P3): FloatArray = v.flatMap { pt(it).toList() }.toFloatArray()
        fun path(q: FloatArray) = Path().apply { moveTo(q[0], q[1]); for (i in 1 until q.size / 2) lineTo(q[2 * i], q[2 * i + 1]); close() }

        val shadow = path(hull.flatMap { pt(it).toList() }.toFloatArray())
        canvas.drawPath(shadow, pattern(shade, pitch = s * 0.0115f, weight = if (shade == "lines") 0.28f else 0.3f))

        val front = quad(P3(-1.0, 1.0, -1.0), P3(1.0, 1.0, -1.0), P3(1.0, -1.0, -1.0), P3(-1.0, -1.0, -1.0))
        val side = quad(P3(1.0, 1.0, -1.0), P3(1.0, 1.0, 1.0), P3(1.0, -1.0, 1.0), P3(1.0, -1.0, -1.0))
        val top = quad(P3(-1.0, 1.0, 1.0), P3(1.0, 1.0, 1.0), P3(1.0, 1.0, -1.0), P3(-1.0, 1.0, -1.0))
        val u = 1000f
        fun onFace(q: FloatArray, block: () -> Unit) {
            val m = Matrix()
            m.setPolyToPoly(floatArrayOf(0f, 0f, u, 0f, u, u, 0f, u), 0, q, 0, 4)
            canvas.withSave {
                concat(m)
                block()
            }
        }
        canvas.drawPath(path(front), fill(paper))
        canvas.drawPath(path(side), fill(ink))
        canvas.drawPath(path(top), fill(paper))
        canvas.drawPath(path(top), pattern(shade, pitch = s * 0.0105f, weight = if (shade == "lines") 0.14f else 0.16f))

        onFace(front) {
            val probe = paint(display, 100f, figures = true, tracking = -0.03f)
            val size = min(100f * 840f / max(1f, width(probe, t.time)), 420f / 0.72f)
            val tp = paint(display, size, figures = true, tracking = -0.03f)
            val amP = paint(strong, 70f, tracking = 0.16f)
            val blockH = cap(tp) + if (t.amPm.isNotEmpty()) 70f + cap(amP) else 0f
            val capTop = (u - blockH) / 2f
            val base = rawText(t.time, u / 2f, capTop, tp, Paint.Align.CENTER)
            if (t.amPm.isNotEmpty()) rawText(upperL(t.amPm), u / 2f, base + 70f, amP, Paint.Align.CENTER)
        }
        onFace(side) {
            val wp = paint(strong, 92f, paper, tracking = 0.12f)
            val wk = upperL(data.weekdayShort)
            rawText(wk, u / 2f, 120f, wp, Paint.Align.CENTER)
            val np = paint(display, min(100f * 760f / max(1f, width(paint(display, 100f, figures = true), data.dayNumber)), 460f / 0.72f), paper, figures = true, tracking = -0.03f)
            rawText(data.dayNumber, u / 2f, (u - cap(np)) / 2f + 30f, np, Paint.Align.CENTER)
            rawText(upperL(data.monthShort), u / 2f, u - 120f - cap(wp), wp, Paint.Align.CENTER)
        }
        onFace(top) {
            canvas.drawCircle(u / 2f, u / 2f, 250f, fill(paper))
            canvas.drawCircle(u / 2f, u / 2f, 250f, stroke(ink, 14f))
            canvas.drawCircle(u / 2f, u / 2f, 190f, fill(accent))
            canvas.drawCircle(u / 2f, u / 2f, 190f, stroke(ink, 10f))
        }
        val edge = stroke(ink, s * 0.006f).apply { strokeJoin = Paint.Join.ROUND }
        listOf(front, side, top).forEach { canvas.drawPath(path(it), edge) }
    }

    /** Convex hull of points on the floor, counter-clockwise (Andrew's monotone chain). */
    private fun hull(pts: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val s = pts.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        if (s.size < 3) return s
        fun cross(o: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>) =
            (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
        val lower = mutableListOf<Pair<Double, Double>>()
        for (q in s) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), q) <= 0) lower.removeAt(lower.size - 1); lower += q }
        val upper = mutableListOf<Pair<Double, Double>>()
        for (q in s.reversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), q) <= 0) upper.removeAt(upper.size - 1); upper += q }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    // ---------- Split-flap ----------

    /** Hours and minutes on split-flap cards, the upper flap leaning back on its hinge, with the date on small flaps. */
    fun flip(p: SleepPage) {
        with(p) {
            val f = frame()
            val t = hm(FaceOptions.FLIP_HOURS, pad = true)
            val readings = LiveFaces.readings(p)
            val small = buildList {
                if (spec.shows(SleepElement.Date)) { add(upperL(data.weekdayShort)); add(data.dayNumber); add(upperL(data.monthShort)) }
                if (t.amPm.isNotEmpty()) add(upperL(t.amPm))
            }
            val gap = s * 0.045f
            val sh = s * 0.12f
            val foot = LiveFaces.footLine(p, f)
            val above = if (landscape) footColumns(readings, f.left, f.right, foot, s * 0.038f) else footRows(readings, f.left, f.right, foot, s * 0.036f)
            val smallGap = s * 0.08f
            val room = above - s * 0.06f - f.top - (if (small.isEmpty()) 0f else sh + smallGap)
            var cardW = (f.width() - gap) / 2f
            val cardH = min(if (landscape) cardW * 0.95f else cardW * 1.28f, room)
            cardW = min(cardW, cardH / (if (landscape) 0.8f else 1.05f))
            val x0 = (w - 2 * cardW - gap) / 2f
            // Cards and date flaps as one group, centred in the room above the readings.
            val groupH = cardH + if (small.isEmpty()) 0f else smallGap + sh
            val top = f.top + max(0f, (above - s * 0.06f - f.top - groupH) / 2f)
            val smallTop = top + cardH + smallGap
            val probe = paint(display, 100f, paper, figures = true, tracking = -0.02f)
            val size = min(100f * cardW * 0.82f / max(1f, width(probe, "00")), cardH * 0.64f / 0.72f)
            val dp = paint(display, size, paper, figures = true, tracking = -0.02f)
            flap(RectF(x0, top, x0 + cardW, top + cardH), t.hours, dp)
            flap(RectF(x0 + cardW + gap, top, x0 + 2 * cardW + gap, top + cardH), t.minutes, dp)
            if (small.isNotEmpty()) {
                val sp = paint(display, sh * 0.5f / 0.72f * 0.8f, paper, figures = true, tracking = 0.04f)
                val pad = sh * 0.34f
                val widths = small.map { width(sp, it) + 2 * pad }
                val sgap = s * 0.025f
                var x = if (landscape) x0 else (w - widths.sum() - sgap * (small.size - 1)) / 2f
                small.forEachIndexed { i, label ->
                    flap(RectF(x, smallTop, x + widths[i], smallTop + sh), label, sp, stack = 1)
                    x += widths[i] + sgap
                }
            }
        }
    }

    /** One card: flaps stacked behind, the lower half flat, the upper half tilted back, the split and two accent pins. */
    private fun SleepPage.flap(r: RectF, label: String, tp: TextPaint, stack: Int = 2) {
        if (dry) return
        val rad = min(r.width(), r.height()) * 0.08f
        val sep = max(1f, s * 0.003f)
        for (k in stack downTo 1) {
            val d = s * 0.01f * k
            val back = RectF(r.left + d * 0.7f, r.top + d, r.right - d * 0.7f, r.bottom + d)
            canvas.drawRoundRect(back, rad, rad, fill(ink))
            canvas.drawRoundRect(back, rad, rad, stroke(paper, sep))
        }
        val mid = r.centerY()
        fun face() {
            canvas.drawRoundRect(r, rad, rad, fill(ink))
            rawText(label, r.centerX(), mid - cap(tp) / 2f, tp, Paint.Align.CENTER)
        }
        canvas.withClip(r.left - 2, mid, r.right + 2, r.bottom + 2) { face() }
        // The upper flap leans back a little on its hinge: its top edge draws in and drops, as in perspective.
        val lean = 0.018f
        val m = Matrix()
        m.setPolyToPoly(
            floatArrayOf(r.left, r.top, r.right, r.top, r.right, mid, r.left, mid), 0,
            floatArrayOf(r.left + r.width() * lean, r.top + (mid - r.top) * lean, r.right - r.width() * lean, r.top + (mid - r.top) * lean, r.right, mid, r.left, mid), 0, 4,
        )
        canvas.withSave {
            concat(m)
            clipRect(r.left - 2, r.top - 2, r.right + 2, mid)
            face()
        }
        // The split: a dark cut through the figures, and the lower flap's top edge catching the light.
        val cut = max(2f, r.height() * 0.012f)
        canvas.drawRect(r.left, mid - cut / 2f, r.right, mid + cut / 2f, fill(ink))
        canvas.drawRect(r.left + rad * 0.5f, mid + cut / 2f, r.right - rad * 0.5f, mid + cut / 2f + max(1f, cut * 0.35f), fill(paper))
        val split = cut
        val pinW = max(2f, r.height() * 0.022f)
        val pinH = split * 3.2f
        listOf(r.left - pinW * 0.55f, r.right - pinW * 0.45f).forEach { x ->
            val pin = RectF(x, mid - pinH / 2f, x + pinW, mid + pinH / 2f)
            canvas.drawRoundRect(pin, pinW * 0.3f, pinW * 0.3f, fill(accentOnInk))
            canvas.drawRoundRect(pin, pinW * 0.3f, pinW * 0.3f, stroke(ink, max(1f, s * 0.002f)))
        }
    }

    // ---------- LCD ----------

    /** Segments a to g as bits 0 to 6. */
    private val DIGITS = intArrayOf(0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F)

    /**
     * A Braun-style LCD clock: a black body, a paper window with drawn seven-segment figures (ghosts of the unlit
     * segments optional), small fields for the date, time asleep, battery and alarm, and a row of keys with one in
     * the accent.
     */
    fun lcd(p: SleepPage) {
        with(p) {
            val f = frame()
            val t = hm(FaceOptions.LCD_HOURS, pad = true)
            val slant = if (spec.option(FaceOptions.LCD_SLANT) == "slanted") 0.1f else 0f
            val ghost = spec.option(FaceOptions.LCD_GHOST) == "on"
            val live = data.live
            val dayMonth = if (android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "dM").trimStart().startsWith("M"))
                "%02d.%02d".format(data.today.monthValue, data.today.dayOfMonth) else "%02d.%02d".format(data.today.dayOfMonth, data.today.monthValue)
            val fields = buildList {
                if (spec.shows(SleepElement.Date)) add(upperL(data.weekdayShort) to dayMonth)
                if (spec.shows(SleepElement.Asleep) && live.live) add(data.labels.asleep to "%d:%02d".format(live.asleepMin / 60, live.asleepMin % 60))
                if (spec.shows(SleepElement.Battery)) {
                    val label = (if (data.charging) data.labels.charging else data.labels.battery) + if (data.batteryKnown) " %" else ""
                    add(label to data.batteryText)
                }
                live.alarmAt?.let { add(data.labels.alarm to hmOf(it, t.h24)) }
            }
            val rows = buildList {
                if (spec.shows(SleepElement.Agenda) && data.calendarAllowed) {
                    add(data.labels.next to (live.countdown ?: data.events.firstOrNull()?.let { "${it.title}  ·  ${it.whenLabel}" } ?: data.labels.nothingPlanned))
                }
                if (spec.shows(SleepElement.Asleep) && live.live) add(data.labels.asleep to String.format(data.words.since, live.sleptAt))
            }
            val foot = LiveFaces.footLine(p, f)
            val above = if (landscape) footColumns(rows, f.left, f.right, foot, s * 0.036f) else footRows(rows, f.left, f.right, foot, s * 0.036f)
            val room = RectF(f.left, f.top, f.right, above - s * 0.05f)
            // The body's proportions come from what it holds: main figures, one row of fields, the keys.
            val bodyW = room.width()
            val pad = s * 0.05f
            val winW = bodyW - 2 * pad
            val mainStr = t.hours.padStart(2, ' ') + ":" + t.minutes
            var dh = winW * 0.8f / (segWidth(mainStr, 1f) + slant)
            val fieldH = dh * 0.3f
            val legendP = paint(strong, s * 0.024f, tracking = 0.14f)
            fun winH(d: Float) = pad + d + s * 0.07f + (if (fields.isEmpty()) 0f else cap(legendP) + s * 0.025f + d * 0.3f + s * 0.02f) + pad
            val keysH = s * 0.1f
            fun bodyH(d: Float) = pad + winH(d) + pad * 0.9f + keysH + pad * 0.9f
            if (bodyH(dh) > room.height()) dh *= room.height() / bodyH(dh)
            val bh = bodyH(dh)
            val bTop = room.top + (room.height() - bh) * 0.42f
            val body = RectF(room.left, bTop, room.right, bTop + bh)
            if (!dry) {
                canvas.drawRoundRect(body, s * 0.06f, s * 0.06f, fill(ink))
                val win = RectF(body.left + pad, body.top + pad, body.right - pad, body.top + pad + winH(dh))
                canvas.drawRoundRect(win, s * 0.025f, s * 0.025f, fill(paper))
                val inset = s * 0.012f
                canvas.drawRoundRect(RectF(win.left + inset, win.top + inset, win.right - inset, win.bottom - inset), s * 0.018f, s * 0.018f, stroke(ink, max(1f, s * 0.0018f)))
                // The main figures, centred in the window, with AM or PM as an annunciator beside them.
                val th = dh * 0.13f
                val mainW = segWidth(mainStr, dh) + dh * slant
                val mx = win.centerX() - mainW / 2f
                val my = win.top + pad
                segString(mainStr, mx, my, dh, th, ghost, slant)
                if (t.amPm.isNotEmpty()) legend(t.amPm, win.right - pad * 0.7f, my, s * 0.03f, Paint.Align.RIGHT)
                if (fields.isNotEmpty()) {
                    val fy = my + dh + s * 0.07f
                    rule(win.left + pad * 0.7f, win.right - pad * 0.7f, fy - s * 0.035f, max(1f, s * 0.0016f))
                    val colW = (win.width() - pad * 1.4f) / fields.size
                    // The small figures share one height, the largest at which the widest fits its field. They have
                    // no ghosts: at this size the unlit segments would only blur the figures.
                    val fh = min(dh * 0.3f, fields.minOf { (_, v) -> colW * 0.86f / (segWidth(v, 1f) + slant) })
                    fields.forEachIndexed { i, (l, v) ->
                        val x = win.left + pad * 0.7f + i * colW + colW * 0.04f
                        val lb = text(clip(upperL(l), legendP, colW * 0.92f), x, fy, legendP)
                        segString(v, x, lb + s * 0.025f, fh, fh * 0.14f, false, slant)
                    }
                }
                // The keys: three plain, one in the accent, as on the calculators and clocks this face borrows from.
                val ky = win.bottom + pad * 0.9f + keysH / 2f
                val kr = keysH * 0.42f
                val kgap = kr * 1.1f
                val kx0 = body.right - pad - kr
                for (i in 0 until 4) {
                    val cx = kx0 - i * (2 * kr + kgap)
                    if (i == 0) canvas.drawCircle(cx, ky, kr, fill(accentOnInk))
                    canvas.drawCircle(cx, ky, kr - s * 0.002f, stroke(paper, max(1f, s * 0.004f)))
                }
                val lampR = s * 0.011f
                canvas.drawCircle(body.left + pad + lampR, ky, lampR, fill(if (data.charging) accentOnInk else ink))
                canvas.drawCircle(body.left + pad + lampR, ky, lampR, stroke(paper, max(1f, s * 0.003f)))
            }
        }
    }

    private fun SleepPage.hmOf(ms: Long, h24: Boolean): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        val h = c.get(java.util.Calendar.HOUR_OF_DAY)
        val hh = if (h24) h else (h % 12).let { if (it == 0) 12 else it }
        return "%d:%02d".format(hh, c.get(java.util.Calendar.MINUTE))
    }

    /** Width of a seven-segment string of height [dh]: digits and blanks, ':' and '.' narrower. */
    private fun segWidth(str: String, dh: Float): Float {
        val dw = dh * 0.52f
        var x = 0f
        str.forEachIndexed { i, ch ->
            x += when (ch) { ':' -> dh * 0.34f; '.' -> dh * 0.2f; else -> dw }
            if (i < str.length - 1 && ch != ':' && ch != '.' && str[i + 1] != ':' && str[i + 1] != '.') x += dw * 0.3f
        }
        return x
    }

    /** Draws [str] in seven segments with its top at [top]; a space is a digit left dark, shown only as a ghost. */
    private fun SleepPage.segString(str: String, x0: Float, top: Float, dh: Float, th: Float, ghost: Boolean, slant: Float) {
        val dw = dh * 0.52f
        canvas.withSave {
            translate(0f, top + dh)
            skew(-slant, 0f)
            translate(0f, -(top + dh))
            var x = x0
            str.forEachIndexed { i, ch ->
                when (ch) {
                    ':' -> {
                        val cw = dh * 0.34f
                        listOf(0.3f, 0.7f).forEach { k -> drawRect(x + cw / 2f - th / 2f, top + dh * k - th / 2f, x + cw / 2f + th / 2f, top + dh * k + th / 2f, fill(ink)) }
                        x += cw
                    }
                    '.' -> {
                        val cw = dh * 0.2f
                        drawRect(x + cw / 2f - th / 2f, top + dh - th, x + cw / 2f + th / 2f, top + dh, fill(ink))
                        x += cw
                    }
                    else -> {
                        val bits = if (ch.isDigit()) DIGITS[ch - '0'] else if (ch == '-') 0x40 else 0
                        segDigit(x, top, dw, dh, th, bits, ghost)
                        x += dw
                        if (i < str.length - 1 && str[i + 1] != ':' && str[i + 1] != '.') x += dw * 0.3f
                    }
                }
            }
        }
    }

    private fun SleepPage.segDigit(x: Float, top: Float, dw: Float, dh: Float, th: Float, bits: Int, ghost: Boolean) {
        val g = th * 0.16f
        val l = x + th / 2f
        val r = x + dw - th / 2f
        val t = top + th / 2f
        val m = top + dh / 2f
        val b = top + dh - th / 2f
        val ends = listOf(l to t to (r to t), r to t to (r to m), r to m to (r to b), l to b to (r to b), l to m to (l to b), l to t to (l to m), l to m to (r to m))
        val off = stroke(ink, max(1f, s * 0.0013f))
        ends.forEachIndexed { i, (a, e) ->
            val on = bits and (1 shl i) != 0
            if (!on && !ghost) return@forEachIndexed
            val path = segment(a.first, a.second, e.first, e.second, th, g)
            canvas.drawPath(path, if (on) fill(ink) else off)
        }
    }

    /** One bevelled segment from (x1, y1) to (x2, y2), [th] thick, shortened by [g] at both ends. */
    private fun segment(x1: Float, y1: Float, x2: Float, y2: Float, th: Float, g: Float): Path {
        val len = kotlin.math.hypot(x2 - x1, y2 - y1)
        val ux = (x2 - x1) / len
        val uy = (y2 - y1) / len
        val nx = -uy
        val ny = ux
        val ax = x1 + ux * g
        val ay = y1 + uy * g
        val bx = x2 - ux * g
        val by = y2 - uy * g
        val h = th / 2f
        return Path().apply {
            moveTo(ax, ay)
            lineTo(ax + ux * h + nx * h, ay + uy * h + ny * h)
            lineTo(bx - ux * h + nx * h, by - uy * h + ny * h)
            lineTo(bx, by)
            lineTo(bx - ux * h - nx * h, by - uy * h - ny * h)
            lineTo(ax + ux * h - nx * h, ay + uy * h - ny * h)
            close()
        }
    }

    // ---------- Word clock ----------

    /** The letters, our own layout: every word the clock says, in reading order, with filler between. */
    private val GRID = listOf(
        "ITRISUHALFK", "TENQUARTERY", "TWENTYFIVEM", "PASTJTOWONE", "TWOTHREELSX",
        "FOURFIVESIX", "SEVENEIGHTZ", "NINEDELEVEN", "TWELVESLEEP", "TENRUOCLOCK",
    )

    /** Word to row, first column and length. The minute words carry an m. */
    private val WORDS = mapOf(
        "IT" to Triple(0, 0, 2), "IS" to Triple(0, 3, 2), "HALF" to Triple(0, 6, 4), "TENm" to Triple(1, 0, 3),
        "QUARTER" to Triple(1, 3, 7), "TWENTY" to Triple(2, 0, 6), "FIVEm" to Triple(2, 6, 4), "PAST" to Triple(3, 0, 4),
        "TO" to Triple(3, 5, 2), "ONE" to Triple(3, 8, 3), "TWO" to Triple(4, 0, 3), "THREE" to Triple(4, 3, 5),
        "FOUR" to Triple(5, 0, 4), "FIVE" to Triple(5, 4, 4), "SIX" to Triple(5, 8, 3), "SEVEN" to Triple(6, 0, 5),
        "EIGHT" to Triple(6, 5, 5), "NINE" to Triple(7, 0, 4), "ELEVEN" to Triple(7, 5, 6), "TWELVE" to Triple(8, 0, 6),
        "TEN" to Triple(9, 0, 3), "OCLOCK" to Triple(9, 5, 6),
    )
    private val HOURS = listOf("TWELVE", "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT", "NINE", "TEN", "ELEVEN")

    /** "IT IS TWENTY FIVE PAST TEN": the time to the five minutes below it. */
    private fun phrase(hour: Int, minute: Int): List<String> {
        val m5 = minute / 5
        return buildList {
            add("IT"); add("IS")
            when (m5) {
                1, 11 -> add("FIVEm")
                2, 10 -> add("TENm")
                3, 9 -> add("QUARTER")
                4, 8 -> add("TWENTY")
                5, 7 -> { add("TWENTY"); add("FIVEm") }
                6 -> add("HALF")
            }
            if (m5 in 1..6) add("PAST") else if (m5 >= 7) add("TO")
            add(HOURS[(if (m5 >= 7) hour + 1 else hour) % 12])
            if (m5 == 0) add("OCLOCK")
        }
    }

    /**
     * The time in words, lit in a grid of letters. The unlit letters are drawn as fine outlines (or left out), never
     * in grey, and up to four accent dots count the minutes past the five.
     */
    fun wordClock(p: SleepPage) {
        with(p) {
            val f = frame()
            val live = data.live
            val lit = phrase(live.hour, live.minute).flatMap { wd -> WORDS.getValue(wd).let { (r, c, n) -> (c until c + n).map { r to it } } }.toSet()
            val alone = spec.option(FaceOptions.WORD_STYLE) == "words"
            val dots = spec.option(FaceOptions.WORD_DOTS) == "on"
            val readings = LiveFaces.readings(p).filter { it.first != data.labels.next }
            val r1 = masthead(f)
            val gTop = r1 + s * 0.07f
            val dotsH = if (dots) s * 0.07f else 0f
            val grid: RectF
            if (!landscape) {
                val foot = LiveFaces.footLine(p, f)
                val above = footRows(readings, f.left, f.right, foot, s * 0.036f)
                val cell = min(f.width() / 11f, (above - s * 0.05f - dotsH - gTop) / 10f)
                val gw = cell * 11f
                grid = RectF((w - gw) / 2f, gTop, (w + gw) / 2f, gTop + cell * 10f)
            } else {
                val cellH = (f.bottom - dotsH - gTop) / 10f
                val cellW = min(cellH, f.width() * 0.66f / 11f)
                grid = RectF(f.left, gTop, f.left + cellW * 11f, gTop + cellH * 10f)
                val colL = grid.right + f.width() * 0.06f
                val foot = LiveFaces.footLine(p, RectF(colL, f.top, f.right, f.bottom))
                columnRows(readings, colL, f.right, gTop, foot - s * 0.05f, s * 0.042f)
            }
            val cw = grid.width() / 11f
            val ch = grid.height() / 10f
            if (alone) wordsAlone(grid, live.hour, live.minute) else {
                val probe = paint(display, 100f)
                val size = min(100f * cw * 0.66f / max(1f, width(probe, "W")), ch * 0.5f / 0.72f)
                val on = paint(display, size)
                // Unlit letters as fine outlines: texture to the eye, never grey text.
                val off = paint(display, size).apply { style = Paint.Style.STROKE; strokeWidth = max(1f, s * 0.0017f) }
                GRID.forEachIndexed { r, row ->
                    row.forEachIndexed { c, letter ->
                        val isLit = (r to c) in lit
                        val cx = grid.left + cw * (c + 0.5f)
                        val cy = grid.top + ch * (r + 0.5f)
                        rawText(letter.toString(), cx, cy - cap(on) / 2f, if (isLit) on else off, Paint.Align.CENTER)
                    }
                }
            }
            if (dots && !dry) {
                val extra = live.minute % 5
                val dr = s * 0.012f
                val y = grid.bottom + dotsH * 0.55f
                val span = dr * 2 * 4 + dr * 3 * 3
                val x0 = grid.centerX() - span / 2f + dr
                for (i in 0 until 4) {
                    val cx = x0 + i * dr * 5
                    if (i < extra) canvas.drawCircle(cx, y, dr, fill(accent))
                    canvas.drawCircle(cx, y, dr - s * 0.0015f, stroke(ink, s * 0.003f))
                }
            }
        }
    }

    /** The word clock without its grid: the same words set as a poster, the numbers large and the joints small. */
    private fun SleepPage.wordsAlone(r: RectF, hour: Int, minute: Int) {
        val m5 = minute / 5
        val big = mutableListOf<Pair<String, Boolean>>()
        when (m5) {
            1, 11 -> "FIVE"
            2, 10 -> "TEN"
            3, 9 -> "QUARTER"
            4, 8 -> "TWENTY"
            5, 7 -> "TWENTY-FIVE"
            6 -> "HALF"
            else -> null
        }?.let { big += it to true }
        if (m5 in 1..6) big += "PAST" to false else if (m5 >= 7) big += "TO" to false
        big += HOURS[(if (m5 >= 7) hour + 1 else hour) % 12] to true
        if (m5 == 0) big += "O’CLOCK" to false
        val lead = paint(strong, s * 0.036f, tracking = 0.2f)
        val gap = s * 0.05f
        val bigCount = big.count { it.second }
        val smallCount = big.size - bigCount
        // The large lines share one size: the largest at which the longest fits and the whole stack fits the height.
        val byWidth = big.filter { it.second }.minOf { (t, _) -> fit(t, display, r.width(), s * 0.34f, tracking = -0.02f) }
        val byHeight = (r.height() - cap(lead) - gap * big.size) / ((bigCount + smallCount * 0.45f) * 0.72f)
        val size = min(byWidth, byHeight)
        val bp = paint(display, size, tracking = -0.02f)
        val sp = paint(strong, size * 0.45f, tracking = 0.08f)
        val total = cap(lead) + big.sumOf { (_, b) -> (cap(if (b) bp else sp) + gap).toDouble() }.toFloat()
        var y = r.top + max(0f, (r.height() - total) / 2f)
        y = text("IT IS", r.left, y, lead) + gap
        big.forEach { (t, b) -> y = text(t, r.left, y, if (b) bp else sp) + gap }
    }
}
