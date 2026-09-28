package app.booxultimatum.core.sleep

import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import app.booxultimatum.core.sleep.SleepFaces.stackedRow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pieces the newer faces share, on top of the studio grammar in [SleepPage] and [SleepFaces]. */

internal fun upperL(t: String) = t.uppercase(Locale.getDefault())

/** The time a face shows, split for faces that set hours and minutes apart. [h24] follows the face's option. */
internal data class Hm(val time: String, val amPm: String, val hours: String, val minutes: String, val h24: Boolean)

/** A face's hours option: null follows the tablet, true is 24-hour, false 12-hour. */
internal fun SleepPage.hours(o: FaceOption?): Boolean? = when (o?.let { spec.option(it) }) {
    "12" -> false
    "24" -> true
    else -> null
}

/** The live time in the face's hours; [pad] sets 24-hour hours on two digits, as a display panel would. */
internal fun SleepPage.hm(o: FaceOption?, pad: Boolean = false, force24: Boolean? = null): Hm {
    val l = data.live
    val h24 = force24 ?: hours(o) ?: l.amPm.isEmpty()
    val hh = if (h24) l.hour else (l.hour % 12).let { if (it == 0) 12 else it }
    val hs = if (h24 && pad) String.format(Locale.getDefault(), "%02d", hh) else String.format(Locale.getDefault(), "%d", hh)
    val ms = String.format(Locale.getDefault(), "%02d", l.minute)
    val ap = if (h24) "" else if (l.hour < 12) data.words.am else data.words.pm
    return Hm("$hs:$ms", ap, hs, ms, h24)
}

/** The weekday on the left and the date on the right in tracked capitals, a rule under both. Returns the rule's y. */
internal fun SleepPage.masthead(f: RectF, date: String? = if (spec.shows(SleepElement.Date)) data.dateShort else null, size: Float = s * 0.03f): Float {
    val hp = paint(strong, size, tracking = 0.14f)
    val base = text(upperL(data.weekday), f.left, f.top, hp)
    if (date != null) text(upperL(date), f.right, f.top, hp, Paint.Align.RIGHT)
    val y = base + s * 0.035f
    rule(f.left, f.right, y, s * 0.004f)
    return y
}

/**
 * An ink texture for shading, since e-ink shows patterns far better than soft greys: "dots" (a staggered halftone)
 * or "lines" (45° hatching). [pitch] is the repeat and [weight] the dot radius or line width as a fraction of it.
 * The tile is drawn at this page's scale, so previews and thumbnails shade like the panel.
 */
internal fun SleepPage.pattern(kind: String, color: Int = ink, pitch: Float = s * 0.0105f, weight: Float = 0.26f): Paint {
    val n = max(2, pitch.roundToInt())
    val tile = createBitmap(n, n)
    val c = Canvas(tile)
    val nf = n.toFloat()
    if (kind == "lines") {
        val st = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = max(1f, nf * weight) }
        for (k in 0..2) c.drawLine(-nf, k * nf + nf, 2 * nf, k * nf - 2 * nf, st)
    } else {
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val r = max(0.6f, nf * weight)
        listOf(0f to 0f, nf to 0f, 0f to nf, nf to nf, nf / 2f to nf / 2f).forEach { (x, y) -> c.drawCircle(x, y, r, dot) }
    }
    return Paint().apply { shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
}

/**
 * The moon as it looks tonight: the lit part light and the rest dark in either ink, with an ink rim. Lit on the right
 * while waxing, as seen from the northern hemisphere, unless [south].
 */
internal fun SleepPage.moonDisc(cx: Float, cy: Float, r: Float, lit: Float, waxing: Boolean, south: Boolean = false) {
    if (dry) return
    canvas.drawCircle(cx, cy, r, fill(Color.WHITE))
    val oval = RectF(cx - r, cy - r, cx + r, cy + r)
    val litRight = waxing != south
    val half = Path().apply { if (litRight) addArc(oval, 90f, 180f) else addArc(oval, -90f, 180f); close() }
    val e = r * abs(2f * lit - 1f)
    val ell = Path().apply { addOval(RectF(cx - e, cy - r, cx + e, cy + r), Path.Direction.CW) }
    val dark = Path().apply { op(half, ell, if (lit < 0.5f) Path.Op.UNION else Path.Op.DIFFERENCE) }
    canvas.drawPath(dark, fill(Color.BLACK))
    val rim = max(1f, s * 0.004f)
    canvas.drawCircle(cx, cy, r - rim / 2f, stroke(ink, rim))
}

/** A flat gauge: an ink rim and the filled part in the accent. */
internal fun SleepPage.gauge(left: Float, right: Float, top: Float, height: Float, fraction: Float) {
    if (dry) return
    val rim = max(1f, s * 0.003f)
    val r = RectF(left, top, right, top + height)
    val k = fraction.coerceIn(0f, 1f)
    if (k > 0f) canvas.drawRect(RectF(left, top, left + (right - left) * k, top + height), fill(accent))
    canvas.drawRect(RectF(r.left + rim / 2, r.top + rim / 2, r.right - rim / 2, r.bottom - rim / 2), stroke(ink, rim))
}

/** The accent used as a fill on an ink ground, where Ink as the accent would vanish. */
internal val SleepPage.accentOnInk: Int get() = if (spec.accent == SleepAccent.Ink) paper else accent

/** The share of the year already over: the days before today. */
internal fun SleepPage.yearFraction(): Float = (data.dayOfYear - 1) / data.daysInYear.toFloat()

internal fun SleepPage.dayMinute(): Int = data.live.hour * 60 + data.live.minute

/** Readings as ruled rows standing on [foot] between [left] and [right]. Returns the y the face above must stay over. */
internal fun SleepPage.footRows(rows: List<Pair<String, String>>, left: Float, right: Float, foot: Float, rs: Float): Float {
    if (rows.isEmpty()) return foot - s * 0.05f
    val rowsTop = foot - s * 0.06f - rows.size * rs * 2.62f
    rule(left, right, rowsTop - rs * 1.2f, s * 0.004f)
    var y = rowsTop
    rows.forEachIndexed { i, (l, v) -> y = row(l, v, left, right, y, rs, ruleBelow = i < rows.size - 1) }
    return rowsTop - rs * 1.2f
}

/**
 * Readings side by side along [foot], like a receiver's lower panel; each column is as wide as its reading needs, the
 * spare width shared out. Returns the y the face above must stay over.
 */
internal fun SleepPage.footColumns(rows: List<Pair<String, String>>, left: Float, right: Float, foot: Float, rs: Float): Float {
    if (rows.isEmpty()) return foot - s * 0.05f
    val gap = s * 0.05f
    val room = right - left - gap * (rows.size - 1)
    val vp = paint(strong, rs, figures = true)
    val need = rows.map { (l, v) -> max(width(vp, v), width(paint(strong, s * 0.02f, tracking = 0.14f), upperL(l))) + s * 0.01f }
    val spare = room - need.sum()
    val widths = if (spare >= 0) need.map { it + spare / rows.size } else need.map { it * room / need.sum() }
    val stackH = cap(paint(strong, s * 0.02f)) + rs * 0.55f + cap(vp)
    val top = foot - s * 0.06f - stackH
    rule(left, right, top - s * 0.05f, s * 0.004f)
    var x = left
    rows.forEachIndexed { i, (l, v) ->
        stackedRow(l, v, x, x + widths[i], top, rs, ruleBelow = false)
        x += widths[i] + gap
    }
    return top - s * 0.05f
}

/** Readings stacked in a column from [top], stopping above [bottom]. Returns where the next caps may start. */
internal fun SleepPage.columnRows(rows0: List<Pair<String, String>>, left: Float, right: Float, top: Float, bottom: Float, size: Float): Float {
    // A reading too long for the column, like the battery with what it has used, goes on two rows.
    val vp = paint(strong, size, figures = true)
    val rows = rows0.flatMap { (l, v) ->
        if (width(vp, v) > right - left && v.contains("  ·  ")) listOf(l to v.substringBefore("  ·  "), if (l == data.labels.battery) data.labels.usedAsleep to (data.live.usedShort ?: v.substringAfter("  ·  ")) else "" to v.substringAfter("  ·  "))
        else listOf(l to v)
    }
    var y = top
    rows.forEachIndexed { i, (l, v) ->
        // A value a little too long is set smaller, to about three quarters, before it is cut.
        val sz = max(size * 0.72f, min(size, size * (right - left) / max(1f, width(vp, v))))
        if (y + s * 0.02f + sz * 1.4f < bottom) y = stackedRow(l, v, left, right, y, sz, ruleBelow = i < rows.size - 1)
    }
    return y
}

/**
 * Text drawn in a transformed space (a cube's side, a flap), where [SleepPage.text]'s margin guard would measure the
 * wrong room. The caller fits the text to its shape. Returns the baseline.
 */
internal fun SleepPage.rawText(t: String, x: Float, capTop: Float, p: Paint, align: Paint.Align = Paint.Align.LEFT): Float {
    val base = capTop + cap(p)
    if (!dry && t.isNotEmpty()) {
        p.textAlign = align
        canvas.drawText(t, x, base, p)
        p.textAlign = Paint.Align.LEFT
    }
    return base
}

/** Like [SleepPage.fit], but measured with tabular figures, which run wider than the proportional ones. */
internal fun SleepPage.fitFigures(t: String, tf: android.graphics.Typeface, maxW: Float, maxSize: Float, tracking: Float = 0f): Float {
    val m = width(paint(tf, maxSize, tracking = tracking, figures = true), t)
    return if (m <= maxW || m == 0f) maxSize else maxSize * maxW / m
}
