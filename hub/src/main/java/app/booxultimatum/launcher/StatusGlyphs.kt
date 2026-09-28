package app.booxultimatum.launcher

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.booxultimatum.R
import app.booxultimatum.kit.ui.theme.Ink
import kotlin.math.cos
import kotlin.math.sin

/** Wi-Fi symbol designs. Each reads the same three facts: radio on, connected, signal 0-3. */
enum class WifiGlyph(@StringRes val label: Int) {
    Arcs(R.string.gw_arcs), Bars(R.string.gw_bars), Fan(R.string.gw_fan), Ripples(R.string.gw_ripples),
    Meter(R.string.gw_meter), Dots(R.string.gw_dots), Mast(R.string.gw_mast), Steps(R.string.gw_steps),
}

/** Battery symbol designs. Each reads the level 0-100 and whether it is charging. */
enum class BatteryGlyph(@StringRes val label: Int) {
    Cell(R.string.gb_cell), Upright(R.string.gb_upright), Ring(R.string.gb_ring), Segments(R.string.gb_segments),
    Scale(R.string.gb_scale), Gauge(R.string.gb_gauge), Capsule(R.string.gb_capsule), Line(R.string.gb_line),
}

/**
 * Status symbols in the instrument's own line grammar: even strokes, round caps, fills only where the fill *is*
 * the reading. Inactive parts are drawn in rule grey rather than dropped, so every state keeps its shape on e-ink.
 */
object StatusGlyphs {
    private fun DrawScope.w(k: Float = 0.09f) = size.minDimension * k
    private fun ink(active: Boolean) = if (active) Ink.Black else Ink.Rule

    private fun DrawScope.offSlash() = drawLine(Ink.Black, Offset(size.width * 0.14f, size.height * 0.14f), Offset(size.width * 0.86f, size.height * 0.86f), w(), StrokeCap.Round)

    @Composable
    fun Wifi(on: Boolean, connected: Boolean, level: Int, style: WifiGlyph = WifiGlyph.Arcs, size: Dp = 22.dp) {
        val lv = if (connected) level.coerceIn(0, 3) else -1
        Canvas(Modifier.size(size)) {
            val W = this.size.width; val H = this.size.height
            val stroke = Stroke(w(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (style) {
                WifiGlyph.Arcs -> {
                    val cx = W / 2; val cy = H * 0.86f
                    for (i in 1..3) {
                        val r = W * (0.16f + i * 0.14f)
                        drawArc(ink(lv >= i), 225f, 90f, false, Offset(cx - r, cy - r), Size(r * 2, r * 2), style = stroke)
                    }
                    drawCircle(ink(connected), W * 0.07f, Offset(cx, cy))
                }
                WifiGlyph.Bars -> for (i in 0..3) {
                    val bw = W * 0.15f; val gap = W * 0.08f
                    val x = W * 0.1f + i * (bw + gap)
                    val h = H * (0.25f + i * 0.2f)
                    drawRoundRect(ink(lv >= i && connected), Offset(x, H * 0.9f - h), Size(bw, h), CornerRadius(bw * 0.3f))
                }
                WifiGlyph.Fan -> {
                    // A solid wedge cut into three bands: the bands fill outward with the signal.
                    val cx = W / 2; val cy = H * 0.9f
                    for (i in 3 downTo 1) {
                        val r = W * (0.18f + i * 0.16f)
                        drawArc(ink(lv >= i), 225f, 90f, true, Offset(cx - r, cy - r), Size(r * 2, r * 2))
                        val inner = r - W * 0.12f
                        drawArc(Ink.Paper, 225f, 90f, true, Offset(cx - inner, cy - inner), Size(inner * 2, inner * 2))
                    }
                    drawArc(ink(connected), 225f, 90f, true, Offset(cx - W * 0.16f, cy - W * 0.16f), Size(W * 0.32f, W * 0.32f))
                }
                WifiGlyph.Ripples -> {
                    val c = Offset(W / 2, H / 2)
                    for (i in 1..3) drawCircle(ink(lv >= i), W * (0.12f + i * 0.12f), c, style = Stroke(w(0.07f)))
                    drawCircle(ink(connected), W * 0.09f, c)
                }
                WifiGlyph.Meter -> {
                    // A Braun panel meter: four ticks on a half dial, the needle at the signal.
                    val c = Offset(W / 2, H * 0.84f); val r = W * 0.42f
                    drawArc(Ink.Black, 180f, 180f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(w(0.06f)))
                    for (i in 0..3) {
                        val a = Math.toRadians(180.0 + i * 60.0)
                        drawLine(Ink.Black, Offset(c.x + (cos(a) * r * 0.78f).toFloat(), c.y + (sin(a) * r * 0.78f).toFloat()), Offset(c.x + (cos(a) * r).toFloat(), c.y + (sin(a) * r).toFloat()), w(0.06f))
                    }
                    val a = Math.toRadians(180.0 + (lv.coerceAtLeast(0)) * 60.0)
                    drawLine(if (connected) Ink.Black else Ink.Rule, c, Offset(c.x + (cos(a) * r * 0.9f).toFloat(), c.y + (sin(a) * r * 0.9f).toFloat()), w(0.1f), StrokeCap.Round)
                    drawCircle(if (connected) Ink.Signal else Ink.Rule, W * 0.08f, c)
                }
                WifiGlyph.Dots -> for (i in 0..2) {
                    val r = W * (0.07f + i * 0.04f)
                    drawCircle(ink(lv >= i + 1 || (i == 0 && connected)), r, Offset(W * (0.2f + i * 0.3f), H / 2))
                }
                WifiGlyph.Mast -> {
                    // An antenna mast with waves either side.
                    drawLine(ink(connected), Offset(W / 2, H * 0.3f), Offset(W / 2, H * 0.92f), w(), StrokeCap.Round)
                    drawCircle(ink(connected), W * 0.07f, Offset(W / 2, H * 0.3f))
                    for (i in 1..2) {
                        val r = W * (0.14f + i * 0.13f)
                        val col = ink(lv >= i + 1 || (i == 1 && lv >= 1))
                        drawArc(col, 140f, 80f, false, Offset(W / 2 - r, H * 0.3f - r), Size(r * 2, r * 2), style = stroke)
                        drawArc(col, -40f, 80f, false, Offset(W / 2 - r, H * 0.3f - r), Size(r * 2, r * 2), style = stroke)
                    }
                }
                WifiGlyph.Steps -> for (i in 0..2) {
                    val y = H * (0.75f - i * 0.25f)
                    drawLine(ink(lv >= i + 1 || (i == 0 && connected)), Offset(W * 0.15f, y), Offset(W * (0.45f + i * 0.2f), y), w(0.11f), StrokeCap.Round)
                }
            }
            if (!on) offSlash()
        }
    }

    @Composable
    fun Battery(level: Int, charging: Boolean, style: BatteryGlyph = BatteryGlyph.Cell, size: Dp = 22.dp) {
        val lv = level.coerceIn(0, 100)
        val low = lv <= 15 && !charging
        val fill = if (low) Ink.Alert else Ink.Black
        val wide = style in setOf(BatteryGlyph.Cell, BatteryGlyph.Segments, BatteryGlyph.Scale, BatteryGlyph.Capsule, BatteryGlyph.Line)
        Canvas(Modifier.size(width = if (wide) size * 1.5f else size, height = size)) {
            val W = this.size.width; val H = this.size.height
            val s = w()
            when (style) {
                BatteryGlyph.Cell -> {
                    val body = Size(W * 0.86f, H * 0.62f); val top = (H - body.height) / 2
                    drawRoundRect(Ink.Black, Offset(s / 2, top), body, CornerRadius(H * 0.12f), style = Stroke(s))
                    drawRoundRect(Ink.Black, Offset(W * 0.9f, H * 0.38f), Size(W * 0.08f, H * 0.24f), CornerRadius(H * 0.04f))
                    val inner = s * 1.6f
                    drawRoundRect(fill, Offset(s / 2 + inner, top + inner), Size((body.width - inner * 2) * lv / 100f, body.height - inner * 2), CornerRadius(H * 0.05f))
                    if (charging) bolt(W * 0.47f, H / 2, H * 0.78f)
                }
                BatteryGlyph.Upright -> {
                    val body = Size(W * 0.5f, H * 0.8f); val left = (W - body.width) / 2
                    drawRoundRect(Ink.Black, Offset(W * 0.4f, 0f), Size(W * 0.2f, H * 0.08f), CornerRadius(H * 0.03f))
                    drawRoundRect(Ink.Black, Offset(left, H * 0.12f), body, CornerRadius(W * 0.08f), style = Stroke(s))
                    val inner = s * 1.5f; val full = body.height - inner * 2; val h = full * lv / 100f
                    drawRoundRect(fill, Offset(left + inner, H * 0.12f + inner + (full - h)), Size(body.width - inner * 2, h), CornerRadius(W * 0.04f))
                    if (charging) bolt(W / 2, H * 0.56f, H * 0.6f)
                }
                BatteryGlyph.Ring -> {
                    val r = W * 0.4f
                    drawCircle(Ink.Rule, r, style = Stroke(s * 0.9f))
                    drawArc(fill, -90f, 360f * lv / 100f, false, Offset(W / 2 - r, H / 2 - r), Size(r * 2, r * 2), style = Stroke(s * 1.6f, cap = StrokeCap.Round))
                    if (charging) bolt(W / 2, H / 2, H * 0.5f) else drawCircle(fill, W * 0.07f)
                }
                BatteryGlyph.Segments -> {
                    val frame = Size(W * 0.88f, H * 0.62f); val top = (H - frame.height) / 2
                    drawRoundRect(Ink.Black, Offset(s / 2, top), frame, CornerRadius(H * 0.1f), style = Stroke(s))
                    drawRoundRect(Ink.Black, Offset(W * 0.91f, H * 0.38f), Size(W * 0.07f, H * 0.24f), CornerRadius(H * 0.04f))
                    val n = 5; val pad = s * 1.6f; val gap = s * 0.9f
                    val segW = (frame.width - pad * 2 - gap * (n - 1)) / n
                    val lit = ((lv + 10) / 20).coerceIn(0, n)
                    for (i in 0 until n) drawRoundRect(if (i < lit) fill else Ink.Paper, Offset(s / 2 + pad + i * (segW + gap), top + pad), Size(segW, frame.height - pad * 2), CornerRadius(H * 0.03f))
                    if (charging) bolt(W * 0.47f, H / 2, H * 0.78f)
                }
                BatteryGlyph.Scale -> {
                    // The app's signature tuning scale, in miniature: ticks every 20 %, the needle at the level.
                    val base = H * 0.72f
                    drawLine(Ink.Black, Offset(0f, base), Offset(W, base), s * 0.7f)
                    for (i in 0..10) {
                        val x = W * i / 10f; val major = i % 5 == 0
                        drawLine(if (major) Ink.Black else Ink.Rule, Offset(x, base), Offset(x, base - H * if (major) 0.32f else 0.18f), s * if (major) 0.7f else 0.45f)
                    }
                    val nx = W * lv / 100f
                    drawLine(fill, Offset(nx, H * 0.08f), Offset(nx, H * 0.92f), s * 1.2f, StrokeCap.Round)
                    drawCircle(if (charging) Ink.Signal else fill, s * 1.1f, Offset(nx, H * 0.1f))
                }
                BatteryGlyph.Gauge -> {
                    // A fuel gauge: E to F on a half dial.
                    val c = Offset(W / 2, H * 0.8f); val r = W * 0.44f
                    drawArc(Ink.Black, 180f, 180f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(s * 0.7f))
                    drawArc(fill, 180f, 180f * lv / 100f, false, Offset(c.x - r * 0.78f, c.y - r * 0.78f), Size(r * 1.56f, r * 1.56f), style = Stroke(s * 1.3f))
                    val a = Math.toRadians(180.0 + 180.0 * lv / 100.0)
                    drawLine(Ink.Black, c, Offset(c.x + (cos(a) * r * 0.9f).toFloat(), c.y + (sin(a) * r * 0.9f).toFloat()), s, StrokeCap.Round)
                    drawCircle(if (charging) Ink.Signal else Ink.Black, W * 0.07f, c)
                }
                BatteryGlyph.Capsule -> {
                    val body = Size(W * 0.9f, H * 0.56f); val top = (H - body.height) / 2
                    drawRoundRect(Ink.Black, Offset(W * 0.05f, top), body, CornerRadius(body.height / 2), style = Stroke(s))
                    val inner = s * 1.7f
                    val fw = ((body.width - inner * 2) * lv / 100f).coerceAtLeast(body.height - inner * 2)
                    drawRoundRect(fill, Offset(W * 0.05f + inner, top + inner), Size(fw, body.height - inner * 2), CornerRadius((body.height - inner * 2) / 2))
                    if (charging) bolt(W / 2, H / 2, H * 0.72f)
                }
                BatteryGlyph.Line -> {
                    // Quietest: a hairline track with the charge as a heavy stroke and a cap at the end.
                    val y = H / 2
                    drawLine(Ink.Rule, Offset(W * 0.05f, y), Offset(W * 0.95f, y), s * 0.6f, StrokeCap.Round)
                    val end = W * 0.05f + W * 0.9f * lv / 100f
                    drawLine(fill, Offset(W * 0.05f, y), Offset(end, y), s * 1.8f, StrokeCap.Round)
                    drawLine(Ink.Black, Offset(W * 0.95f, y - H * 0.2f), Offset(W * 0.95f, y + H * 0.2f), s * 0.8f, StrokeCap.Round)
                    if (charging) bolt(end, y - H * 0.28f, H * 0.5f)
                }
            }
        }
    }

    /** A lightning stroke with a paper halo, so it reads over any fill. */
    private fun DrawScope.bolt(cx: Float, cy: Float, h: Float) {
        val w = h * 0.55f
        val p = Path().apply {
            moveTo(cx + w * 0.1f, cy - h / 2); lineTo(cx - w * 0.35f, cy + h * 0.05f); lineTo(cx, cy + h * 0.05f)
            lineTo(cx - w * 0.1f, cy + h / 2); lineTo(cx + w * 0.35f, cy - h * 0.05f); lineTo(cx, cy - h * 0.05f); close()
        }
        drawPath(p, Ink.Paper)
        drawPath(p, Ink.Black, style = Stroke(h * 0.08f, join = StrokeJoin.Round))
    }

    /** The Bluetooth rune. Off = grey. */
    @Composable
    fun Bluetooth(on: Boolean, size: Dp = 22.dp) {
        Canvas(Modifier.size(size)) {
            val W = this.size.width; val H = this.size.height
            val p = Path().apply {
                moveTo(W * 0.28f, H * 0.30f); lineTo(W * 0.72f, H * 0.68f); lineTo(W * 0.50f, H * 0.88f); lineTo(W * 0.50f, H * 0.12f)
                lineTo(W * 0.72f, H * 0.32f); lineTo(W * 0.28f, H * 0.70f)
            }
            drawPath(p, ink(on), style = Stroke(w(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    @Composable
    fun Airplane(size: Dp = 22.dp) {
        Canvas(Modifier.size(size)) {
            val W = this.size.width; val H = this.size.height; val s = w()
            drawLine(Ink.Black, Offset(W * 0.5f, H * 0.10f), Offset(W * 0.5f, H * 0.86f), s, StrokeCap.Round)
            drawLine(Ink.Black, Offset(W * 0.12f, H * 0.56f), Offset(W * 0.5f, H * 0.38f), s, StrokeCap.Round)
            drawLine(Ink.Black, Offset(W * 0.88f, H * 0.56f), Offset(W * 0.5f, H * 0.38f), s, StrokeCap.Round)
            drawLine(Ink.Black, Offset(W * 0.34f, H * 0.90f), Offset(W * 0.66f, H * 0.90f), s, StrokeCap.Round)
        }
    }

    /** Do not disturb: a disc with a bar through it. */
    @Composable
    fun Dnd(size: Dp = 22.dp) {
        Canvas(Modifier.size(size)) {
            val s = w()
            drawCircle(Ink.Black, this.size.minDimension * 0.42f, style = Stroke(s))
            drawLine(Ink.Black, Offset(this.size.width * 0.3f, this.size.height / 2), Offset(this.size.width * 0.7f, this.size.height / 2), s, StrokeCap.Round)
        }
    }
}
