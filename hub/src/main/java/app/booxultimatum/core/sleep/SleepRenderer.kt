package app.booxultimatum.core.sleep

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.withTranslation
import kotlin.math.max
import kotlin.math.min

/**
 * One sheet being typeset: the canvas, its inks and the typographic primitives the faces share. Sizes are fractions
 * of the short side [s] (1860 px on the panel), so the same face renders at full size for the tablet and at a
 * fraction for previews and thumbnails. Text is placed by cap height, not by font box, so every face aligns on
 * the letters themselves.
 *
 * With [dry] set nothing is drawn; faces use it to measure a plate before painting it.
 */
internal class SleepPage(
    val canvas: Canvas,
    val bitmap: Bitmap,
    val spec: SleepFaceSpec,
    val data: SleepData,
    fonts: SleepTypefaces,
) {
    val w = bitmap.width.toFloat()
    val h = bitmap.height.toFloat()
    /** The panel's short side: 1860 px on the tablet. */
    val short = min(w, h)
    /**
     * The type unit: every size and most gaps are fractions of it. It is 0.72 of the short side, which sets a refined
     * poster for a 10.3" panel read at arm's length rather than a billboard.
     */
    val s = short * 0.72f
    val landscape = w > h
    val inverted = spec.ink == SleepInk.Inverted
    val ink = if (inverted) Color.WHITE else Color.BLACK
    val paper = if (inverted) Color.BLACK else Color.WHITE
    val accent = if (spec.accent == SleepAccent.Ink) ink else spec.accent.argb.toInt()
    /** Text colour on an accent fill: whichever of black or white reads better on it. */
    val onAccent = if (spec.accent == SleepAccent.Ink) paper else if (luminance(accent) > 0.45f) Color.BLACK else Color.WHITE
    /** Minor ticks only, never text. */
    val minor = blend(ink, paper, 0.5f)

    val display: Typeface = fonts.at(spec.displayWeight)
    val body: Typeface = fonts.at(spec.bodyWeight)
    val strong: Typeface = fonts.at(min(900, spec.bodyWeight + 200))

    /** No ink comes closer than this to any edge of the panel (177 px on the tablet). */
    val margin = short * 0.095f
    /**
     * How far a [plate]'s soft edge reaches past its rectangle: the grown edge (`0.014 s`) plus about three sigma of
     * its blur ([BlurMaskFilter] turns a radius of `0.022 s` into a Gaussian of `0.0127 s`, and three sigma of that is
     * where the last pixel fades out). Measured on the panel-size renders in `SleepOverlayTest`.
     */
    val plateHalo = s * 0.055f
    /** Boox draws its clock top centre, over roughly the top 22 % of the image, when its Clock style isn't None. */
    val clockZoneBottom = h * 0.22f

    var dry = false

    /**
     * The area a face may fill: inside the margins, below the Boox clock when asked. The studio asks owners to turn
     * the Boox status bar off, and the margins are wider than that bar anyway. Text is set on cap height, so the top
     * and bottom keep a little extra for ascenders, accents and descenders.
     */
    fun frame(): RectF = RectF(
        margin,
        (if (spec.leavesClockRoom) clockZoneBottom + short * 0.035f else margin) + s * 0.014f,
        w - margin,
        h - margin - s * 0.014f,
    )

    /**
     * The area a sticker plate may fill: the centred square that survives Boox's centre-crop in either rotation
     * ([SleepCrop]), less the reach of the plate's soft edge. It sits inside [frame], so a plate placed here also
     * keeps the panel's margins, and its top edge lands just below Boox's clock zone.
     */
    fun safe(): RectF {
        val side = SleepCrop.safeSide(short)
        val left = (w - side) / 2f
        val top = (h - side) / 2f
        return RectF(left, top, left + side, top + side).also { it.inset(plateHalo, plateHalo) }
    }

    // ---------- Paints ----------

    fun paint(tf: Typeface, size: Float, color: Int = ink, tracking: Float = 0f, figures: Boolean = false) =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = tf
            textSize = size
            this.color = color
            letterSpacing = tracking
            fontFeatureSettings = if (figures) "tnum, lnum" else "lnum"
        }

    fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = width }

    // ---------- Single lines ----------

    private val bounds = Rect()

    /** Cap height of [p], measured on its own "H" so every face lines up on real letters. */
    fun cap(p: Paint): Float {
        p.getTextBounds("H", 0, 1, bounds)
        return -bounds.top.toFloat()
    }

    fun width(p: Paint, t: String) = p.measureText(t)

    /**
     * Draws [t] with its caps starting at [capTop]; returns the baseline. A last guard keeps every line inside the side
     * margins whatever the string: its measured ink is fitted to the room its anchor leaves, first by setting it up to
     * a fifth smaller, then by cutting it with an ellipsis. Faces size their text to fit already; this is the net.
     */
    fun text(t: String, x: Float, capTop: Float, p: Paint, align: Paint.Align = Paint.Align.LEFT): Float {
        val base = capTop + cap(p)
        if (t.isEmpty()) return base
        val room = when (align) {
            Paint.Align.LEFT -> w - margin - x
            Paint.Align.RIGHT -> x - margin
            else -> 2f * min(x - margin, w - margin - x)
        }
        var q: Paint = p
        var str = t
        if (inkWidth(str, q) > room + 0.5f) {
            val k = (room / inkWidth(str, q)).coerceAtLeast(0.8f) * 0.995f
            val smaller = TextPaint(p).apply { textSize = p.textSize * k }
            q = smaller
            if (inkWidth(str, smaller) > room) str = clip(str, smaller, room - smaller.textSize * 0.1f)
        }
        if (!dry) {
            q.textAlign = align
            canvas.drawText(str, x, base, q)
            q.textAlign = Paint.Align.LEFT
        }
        return base
    }

    /** From the pen's start to the far edge of the ink, including side bearings that overhang the advance. */
    private fun inkWidth(t: String, p: Paint): Float {
        p.getTextBounds(t, 0, t.length, bounds)
        return max(p.measureText(t), bounds.right.toFloat()) - min(0f, bounds.left.toFloat())
    }

    /** The size, at most [maxSize], at which [t] fits [maxW] on one line. */
    fun fit(t: String, tf: Typeface, maxW: Float, maxSize: Float, minSize: Float = maxSize * 0.25f, tracking: Float = 0f): Float {
        val m = paint(tf, maxSize, tracking = tracking).measureText(t)
        return if (m <= maxW || m == 0f) maxSize else (maxSize * maxW / m).coerceAtLeast(minSize)
    }

    /** One line, however the user typed it: line breaks become separators, and the end is cut with an ellipsis if needed. */
    fun clip(t: String, p: TextPaint, maxW: Float): String {
        val line = oneLine(t)
        if (p.measureText(line) <= maxW) return line
        var lo = 0
        var hi = line.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (p.measureText(line.substring(0, mid).trimEnd() + "…") <= maxW) lo = mid else hi = mid - 1
        }
        return if (lo == 0) "" else line.substring(0, lo).trimEnd() + "…"
    }

    fun oneLine(t: String): String = t.trim().replace(Regex("\\s*\\n+\\s*"), "  ·  ")

    // ---------- Blocks ----------

    fun layout(
        t: CharSequence,
        p: TextPaint,
        width: Float,
        maxLines: Int = Int.MAX_VALUE,
        align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
        spacing: Float = 1.12f,
    ): StaticLayout = StaticLayout.Builder.obtain(t, 0, t.length, p, max(1, width.toInt()))
        .setAlignment(align)
        .setLineSpacing(0f, spacing)
        .setIncludePad(false)
        .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .setMaxLines(maxLines)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()

    /** From the first line's cap top to the last baseline: the block's optical height. */
    fun span(l: StaticLayout): Float = l.getLineBaseline(l.lineCount - 1) - (l.getLineBaseline(0) - cap(l.paint))

    /** Draws [l] with its first caps at [capTop]; returns the last baseline. */
    fun draw(l: StaticLayout, x: Float, capTop: Float): Float {
        val shift = capTop - (l.getLineBaseline(0) - cap(l.paint))
        if (!dry) {
            canvas.withTranslation(x, shift) { l.draw(this) }
        }
        return shift + l.getLineBaseline(l.lineCount - 1)
    }

    /**
     * The largest setting of [t] between [minSize] and [maxSize] that fits [width] x [height] without breaking a word.
     * Nine halvings are finer than a pixel at poster sizes.
     */
    fun fitLayout(
        t: String,
        tf: Typeface,
        width: Float,
        height: Float,
        maxSize: Float,
        minSize: Float,
        spacing: Float = 1.12f,
        align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
        color: Int = ink,
        tracking: Float = 0f,
    ): StaticLayout {
        val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var lo = minSize
        var hi = maxSize
        var best: StaticLayout? = null
        repeat(9) {
            val mid = (lo + hi) / 2f
            val p = paint(tf, mid, color, tracking)
            val l = layout(t, p, width, align = align, spacing = spacing)
            val longest = words.maxOfOrNull { p.measureText(it) } ?: 0f
            if (span(l) + p.descent() <= height && longest <= width) { best = l; lo = mid } else hi = mid
        }
        best?.let { return it }
        val p = paint(tf, minSize, color, tracking)
        val lines = max(1, ((height + p.textSize) / (p.textSize * spacing)).toInt())
        return layout(t, p, width, maxLines = lines, align = align, spacing = spacing)
    }

    // ---------- Marks ----------

    fun rule(x1: Float, x2: Float, y: Float, thickness: Float, color: Int = ink) {
        if (!dry) canvas.drawRect(x1, y - thickness / 2f, x2, y + thickness / 2f, fill(color))
    }

    /** The instrument's lamp: an accent disc in an ink rim when lit, the rim alone when not. */
    fun lamp(cx: Float, cy: Float, r: Float, on: Boolean = true) {
        if (dry) return
        if (on) canvas.drawCircle(cx, cy, r, fill(accent))
        canvas.drawCircle(cx, cy, r - r * 0.11f, stroke(ink, r * 0.22f))
    }

    /** A paper plate with a soft paper edge and an ink rim, legible over any screenshot underneath. */
    fun plate(r: RectF, radius: Float) {
        if (dry) return
        val halo = fill(paper).apply { maskFilter = BlurMaskFilter(s * 0.022f, BlurMaskFilter.Blur.NORMAL); alpha = 230 }
        val grow = s * 0.014f
        canvas.drawRoundRect(RectF(r.left - grow, r.top - grow, r.right + grow, r.bottom + grow), radius + grow, radius + grow, halo)
        canvas.drawRoundRect(r, radius, radius, fill(paper))
        val t = s * 0.0035f
        canvas.drawRoundRect(RectF(r.left + t / 2, r.top + t / 2, r.right - t / 2, r.bottom - t / 2), radius, radius, stroke(ink, t))
    }

    /** A legend and its reading on one ruled line; returns where the next row's caps start. */
    fun row(legend: String, value: String, left: Float, right: Float, capTop: Float, size: Float, ruleBelow: Boolean = true): Float {
        val lp = paint(body, size)
        val vp = paint(strong, size, figures = true)
        val legendW = min(width(lp, legend), (right - left) * 0.4f)
        text(clip(legend, lp, legendW), left, capTop, lp)
        val base = text(clip(value, vp, right - left - legendW - size), right, capTop, vp, Paint.Align.RIGHT)
        val next = base + size * 0.95f
        if (ruleBelow) rule(left, right, next, max(1f, s * 0.0014f))
        return next + size * 0.95f
    }

    companion object {
        fun luminance(c: Int): Float = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f

        fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
            (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
            (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
        )

        /** A rectangle with only its top corners rounded, for a strip that caps a rounded card. */
        fun topRounded(r: RectF, radius: Float): Path = Path().apply {
            addRoundRect(r, floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f), Path.Direction.CW)
        }
    }
}

/**
 * Where a sticker plate may sit, worked out from what Boox does with the file.
 *
 * The Transparent style's sticker is decoded at the panel's size in the rotation the tablet sleeps in, with
 * `ImageView.ScaleType.CENTER_CROP` (`PrepareTransparentDreamAction` and `DreamSettingBean`, read from the decompiled
 * `com.onyx`): a 1860 × 2480 sheet shown at 2480 × 1860 is scaled to 2480 × 3307 and cut back to the middle 1860
 * rows, and the other way round. What survives either rotation is therefore the centred square of 0.75 of the short
 * side — 1395 px of the panel's 1860 — so one sticker file serves both orientations if every plate stays inside it.
 */
internal object SleepCrop {
    /** The share of the short side that survives a centre-crop into the other orientation: 1395 of 1860. */
    const val SAFE_FRACTION = 0.75f

    fun safeSide(short: Float): Float = short * SAFE_FRACTION

    /** The first pixel of [long] that survives, 542 px of the panel's 2480. Pure arithmetic, so it tests on the JVM. */
    fun keptStart(long: Float, short: Float): Float = (long - safeSide(short)) / 2f
}

/** Entry point: pure drawing from a spec and its data into a bitmap of the target size. No I/O, no Context. */
object SleepRenderer {
    fun render(target: Bitmap, spec: SleepFaceSpec, data: SleepData, fonts: SleepTypefaces, photo: Bitmap?) {
        val overlay = spec.mode == SleepMode.Overlay
        val paper = if (spec.ink == SleepInk.Inverted) Color.BLACK else Color.WHITE
        target.eraseColor(if (overlay) Color.TRANSPARENT else paper)
        val page = SleepPage(Canvas(target), target, spec, data, fonts)
        if (overlay) SleepOverlays.draw(page) else when (spec.face) {
            SleepFace.Dial -> LiveFaces.dial(page)
            SleepFace.Clock -> LiveFaces.clock(page)
            SleepFace.Monitor -> LiveFaces.monitor(page)
            SleepFace.Cube -> CraftFaces.cube(page)
            SleepFace.Flip -> CraftFaces.flip(page)
            SleepFace.Dashboard -> DayFaces.dashboard(page)
            SleepFace.WordClock -> CraftFaces.wordClock(page)
            SleepFace.DayRing -> DayFaces.dayRing(page)
            SleepFace.Timeline -> DayFaces.timeline(page)
            SleepFace.Lcd -> CraftFaces.lcd(page)
            SleepFace.Sky -> DayFaces.sky(page)
            SleepFace.Broadsheet -> DayFaces.broadsheet(page)
            SleepFace.Year -> DayFaces.year(page)
            SleepFace.Almanac -> SleepFaces.almanac(page)
            SleepFace.Instrument -> SleepFaces.instrument(page)
            SleepFace.Poster -> SleepFaces.poster(page)
            SleepFace.UnderClock -> SleepFaces.underClock(page)
            SleepFace.Photo -> SleepFaces.photo(page, photo)
            SleepFace.Note -> SleepFaces.note(page)
            SleepFace.ReturnCard -> SleepFaces.returnCard(page)
            SleepFace.Minimal -> SleepFaces.minimal(page)
        }
        // Onyx wants a plain 24-bit picture for the image style; only the sticker keeps its alpha.
        target.setHasAlpha(overlay)
    }
}

/** Floyd–Steinberg to a few levels per channel, applied to the photo only so type stays crisp. */
object SleepDither {
    fun floydSteinberg(bmp: Bitmap, r: Rect, levels: Int = 16) {
        val left = r.left.coerceAtLeast(0)
        val top = r.top.coerceAtLeast(0)
        val w = min(r.right, bmp.width) - left
        val h = min(r.bottom, bmp.height) - top
        if (w <= 0 || h <= 0) return
        val step = 255f / (levels - 1)
        val row = IntArray(w)
        val out = IntArray(3)
        var cur = FloatArray((w + 2) * 3)
        var next = FloatArray((w + 2) * 3)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, left, top + y, w, 1)
            for (x in 0 until w) {
                val px = row[x]
                for (c in 0 until 3) {
                    val shift = 16 - 8 * c
                    val v = ((px shr shift) and 0xFF) + cur[(x + 1) * 3 + c]
                    val q = (Math.round(v / step) * step).coerceIn(0f, 255f)
                    val e = v - q
                    out[c] = q.toInt()
                    cur[(x + 2) * 3 + c] += e * 7f / 16f
                    next[x * 3 + c] += e * 3f / 16f
                    next[(x + 1) * 3 + c] += e * 5f / 16f
                    next[(x + 2) * 3 + c] += e / 16f
                }
                row[x] = (px and 0xFF000000.toInt()) or (out[0] shl 16) or (out[1] shl 8) or out[2]
            }
            bmp.setPixels(row, 0, w, left, top + y, w, 1)
            val t = cur; cur = next; next = t
            next.fill(0f)
        }
    }
}
