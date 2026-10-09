package app.booxultimatum.ui.battery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.booxultimatum.R
import app.booxultimatum.core.battery.Episode
import app.booxultimatum.core.battery.EpisodeKind
import app.booxultimatum.core.battery.Timeline
import app.booxultimatum.kit.ui.IconKey
import app.booxultimatum.kit.ui.drawHatch
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.max

private val LABEL_WIDTH = 64.dp
private val LEVEL_HEIGHT = 72.dp
private val SCREEN_HEIGHT = 28.dp
private val POWER_HEIGHT = 20.dp
private val BAR_HEIGHT = 16.dp
private val MIN_SPAN = 6.dp
private val TICK_LENGTH = 5.dp
private val LEVEL_LINE = 2.5.dp
private val ASLEEP_LINE = 2.5.dp
private val OUTLINE = 2.dp
private val SELECTED_OUTLINE = 3.dp
private val SCALE_LEVELS = listOf(0, 50, 100)
private const val HOUR_MS = 3_600_000L
private const val DAILY_TICKS_OVER_HOURS = 36L

/**
 * The day as a strip: the level line over the Screen and Power lanes and a time axis, drawn in one canvas. The keys step
 * through the episodes that overlap the window; the chart takes no gestures.
 */
@Composable
fun DayStrip(
    timeline: Timeline,
    fromMs: Long,
    toMs: Long,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val episodes = remember(timeline.episodes, fromMs, toMs) { episodesOverlapping(timeline.episodes, fromMs, toMs) }
    val zone = remember { ZoneId.systemDefault() }
    val locale = Locale.getDefault()
    val daily = isDailyTicks(fromMs, toMs)
    val ticks = remember(fromMs, toMs, zone) { tickTimes(fromMs, toMs, zone) }
    val tickTexts = remember(ticks, daily, zone, locale) { ticks.map { tickText(it, zone, daily, locale) } }
    val description = remember(timeline, fromMs, toMs) { BatteryText.summarySentence(context, timeline, fromMs, toMs) }
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = Ink.Legend)
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = Ink.Legend)
    val rows = stripRows(
        density,
        measurer.measure("100", axisStyle).size.height.toFloat(),
        measurer.measure("00", axisStyle).size.height.toFloat(),
    )
    val current = selected?.takeIf { it in episodes.indices }
    val picked = current?.let { episodes[it] }
    val previous = stepTo(current, episodes.size, forward = false)
    val next = stepTo(current, episodes.size, forward = true)
    val frame = StripFrame(
        measurer = measurer,
        rows = rows,
        fromMs = fromMs,
        toMs = toMs,
        episodes = episodes,
        picked = picked,
        ticks = ticks,
        tickTexts = tickTexts,
        names = listOf(stringResource(R.string.ds_level), stringResource(R.string.ds_screen), stringResource(R.string.ds_power)),
        labelStyle = labelStyle,
        axisStyle = axisStyle,
    )

    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(with(density) { rows.bottom.toDp() })
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Image
                },
        ) {
            drawStrip(frame)
        }
        Row(Modifier.fillMaxWidth().padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
            IconKey(
                icon = PreviousGlyph,
                label = stringResource(R.string.ds_previous),
                onClick = { previous?.let(onSelect) },
                enabled = previous != null,
            )
            Column(Modifier.weight(1f).padding(horizontal = Space.s), horizontalAlignment = Alignment.CenterHorizontally) {
                val style = MaterialTheme.typography.bodyMedium
                if (picked == null) {
                    Text(stringResource(if (episodes.isEmpty()) R.string.ds_none else R.string.ds_step_through), style = style, textAlign = TextAlign.Center)
                } else {
                    val amounts = listOfNotNull(picked.mah?.let { BatteryText.mah(context, it) }, picked.avgMa?.let { BatteryText.ma(context, it) })
                    Text(BatteryText.kindLabel(context, picked.kind), style = style, textAlign = TextAlign.Center)
                    Text(BatteryText.timeRange(context, picked.startMs, picked.endMs), style = style, textAlign = TextAlign.Center)
                    if (amounts.isNotEmpty()) Text(amounts.joinToString(" · "), style = style, textAlign = TextAlign.Center)
                }
            }
            IconKey(
                icon = NextGlyph,
                label = stringResource(R.string.ds_next),
                onClick = { next?.let(onSelect) },
                enabled = next != null,
            )
        }
    }
}

private class StripRows(val levelTop: Float, val screenTop: Float, val powerTop: Float, val axisTop: Float, val bottom: Float)

private class StripFrame(
    val measurer: TextMeasurer,
    val rows: StripRows,
    val fromMs: Long,
    val toMs: Long,
    val episodes: List<Episode>,
    val picked: Episode?,
    val ticks: List<Long>,
    val tickTexts: List<String>,
    val names: List<String>,
    val labelStyle: TextStyle,
    val axisStyle: TextStyle,
)

private fun stripRows(density: Density, scaleHeight: Float, axisHeight: Float): StripRows = with(density) {
    val levelTop = scaleHeight + 2.dp.toPx()
    val screenTop = levelTop + LEVEL_HEIGHT.toPx() + 8.dp.toPx()
    val powerTop = screenTop + SCREEN_HEIGHT.toPx() + 6.dp.toPx()
    val axisTop = powerTop + POWER_HEIGHT.toPx() + 4.dp.toPx()
    StripRows(levelTop, screenTop, powerTop, axisTop, axisTop + TICK_LENGTH.toPx() + 2.dp.toPx() + axisHeight)
}

private fun DrawScope.drawStrip(frame: StripFrame) {
    val left = LABEL_WIDTH.toPx()
    val right = size.width
    val rows = frame.rows
    val levelHeight = LEVEL_HEIGHT.toPx()
    val x = { ms: Long -> timeToX(ms, frame.fromMs, frame.toMs, left, right - left) }
    val y = { level: Int -> rows.levelTop + (100 - level) / 100f * levelHeight }
    val screenMid = rows.screenTop + SCREEN_HEIGHT.toPx() / 2f
    val powerMid = rows.powerTop + POWER_HEIGHT.toPx() / 2f
    clipRect(left, 0f, right, size.height) {
        drawScale(frame, left, right, y)
        drawLevelLine(frame.episodes, x, y)
        drawScreenLane(frame, x, screenMid, left, right)
        drawPowerLane(frame.episodes, x, powerMid, left)
        drawTickMarks(frame.ticks, x, rows.axisTop)
    }
    drawLaneNames(frame, listOf(rows.levelTop + levelHeight / 2f, screenMid, powerMid), left)
    drawTickLabels(frame, x)
}

private fun DrawScope.drawScale(frame: StripFrame, left: Float, right: Float, y: (Int) -> Float) {
    val rule = Lines.hairline.toPx()
    for (level in SCALE_LEVELS) {
        drawLine(Ink.Rule, Offset(left, y(level)), Offset(right, y(level)), strokeWidth = rule)
        val label = frame.measurer.measure(level.toString(), frame.axisStyle)
        drawText(label, topLeft = Offset(left + 4.dp.toPx(), y(level) - label.size.height - 2.dp.toPx()))
    }
}

private fun DrawScope.drawLevelLine(episodes: List<Episode>, x: (Long) -> Float, y: (Int) -> Float) {
    if (episodes.isEmpty()) return
    val path = Path()
    episodes.forEachIndexed { i, e ->
        val startX = x(e.startMs)
        val startY = y(e.level0)
        if (i == 0 || episodes[i - 1].endMs != e.startMs) path.moveTo(startX, startY) else path.lineTo(startX, startY)
        path.lineTo(x(e.endMs), y(e.level1))
    }
    drawPath(path, Ink.Black, style = Stroke(width = LEVEL_LINE.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawScreenLane(frame: StripFrame, x: (Long) -> Float, mid: Float, left: Float, right: Float) {
    val bar = BAR_HEIGHT.toPx() / 2f
    val minSpan = MIN_SPAN.toPx()
    for (e in frame.episodes) {
        val x0 = x(e.startMs)
        val x1 = max(x(e.endMs), x0 + minSpan)
        when (e.kind) {
            EpisodeKind.InUse -> drawRect(Ink.Black, Offset(x0, mid - bar), Size(x1 - x0, 2 * bar))
            EpisodeKind.Asleep -> drawLine(Ink.Black, Offset(x0, mid), Offset(x1, mid), strokeWidth = ASLEEP_LINE.toPx())
            EpisodeKind.DisplayOn -> {
                drawDisplayOn(x0, x1, mid, bar)
                if (x0 >= left) drawCross(Offset(x0, mid))
                if (x1 <= right) drawCross(Offset(x1, mid))
            }
            EpisodeKind.Off -> drawLine(
                Ink.Black,
                Offset(x0, mid),
                Offset(x1, mid),
                strokeWidth = OUTLINE.toPx(),
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, 5.dp.toPx())),
            )
            EpisodeKind.Charging -> Unit
        }
    }
    frame.picked?.let { p ->
        val w = SELECTED_OUTLINE.toPx()
        val x0 = x(p.startMs)
        val x1 = max(x(p.endMs), x0 + minSpan)
        drawRect(
            Ink.Black,
            Offset(x0 + w / 2f, frame.rows.screenTop + w / 2f),
            Size(x1 - x0 - w, SCREEN_HEIGHT.toPx() - w),
            style = Stroke(w),
        )
    }
}

private fun DrawScope.drawDisplayOn(x0: Float, x1: Float, mid: Float, bar: Float) {
    val line = OUTLINE.toPx()
    val top = mid - bar
    drawHatch(Offset(x0 + line, top + line), Size(x1 - x0 - 2 * line, 2 * bar - 2 * line))
    drawRect(Ink.Black, Offset(x0 + line / 2f, top + line / 2f), Size(x1 - x0 - line, 2 * bar - line), style = Stroke(line))
}

private fun DrawScope.drawCross(center: Offset) {
    val half = 4.dp.toPx()
    for ((color, width) in listOf(Ink.Paper to 4.dp.toPx(), Ink.Black to 2.dp.toPx())) {
        drawLine(color, center + Offset(-half, -half), center + Offset(half, half), strokeWidth = width)
        drawLine(color, center + Offset(half, -half), center + Offset(-half, half), strokeWidth = width)
    }
}

private fun DrawScope.drawPowerLane(episodes: List<Episode>, x: (Long) -> Float, mid: Float, left: Float) {
    val rule = 2.dp.toPx()
    val half = 2.5.dp.toPx()
    val minSpan = MIN_SPAN.toPx()
    for (e in episodes) {
        if (e.kind != EpisodeKind.Charging) continue
        val x0 = x(e.startMs)
        val x1 = max(x(e.endMs), x0 + minSpan)
        var start = x0
        if (x0 >= left) {
            drawBolt(Offset(x0, mid - 7.dp.toPx()))
            start = x0 + 10.dp.toPx()
        }
        if (start < x1) {
            drawLine(Ink.Black, Offset(start, mid - half), Offset(x1, mid - half), strokeWidth = rule)
            drawLine(Ink.Black, Offset(start, mid + half), Offset(x1, mid + half), strokeWidth = rule)
        }
    }
}

private fun DrawScope.drawBolt(topLeft: Offset) {
    val u = 1.dp.toPx()
    val path = Path().apply {
        moveTo(topLeft.x + 5 * u, topLeft.y)
        lineTo(topLeft.x, topLeft.y + 8 * u)
        lineTo(topLeft.x + 4 * u, topLeft.y + 8 * u)
        lineTo(topLeft.x + 3 * u, topLeft.y + 14 * u)
        lineTo(topLeft.x + 8 * u, topLeft.y + 6 * u)
        lineTo(topLeft.x + 4 * u, topLeft.y + 6 * u)
        close()
    }
    drawPath(path, Ink.Black)
}

private fun DrawScope.drawTickMarks(ticks: List<Long>, x: (Long) -> Float, axisTop: Float) {
    val rule = Lines.hairline.toPx()
    for (t in ticks) {
        drawLine(Ink.Rule, Offset(x(t), axisTop), Offset(x(t), axisTop + TICK_LENGTH.toPx()), strokeWidth = rule)
    }
}

private fun DrawScope.drawLaneNames(frame: StripFrame, mids: List<Float>, columnWidth: Float) {
    val maxWidth = (columnWidth - 4.dp.toPx()).toInt().coerceAtLeast(0)
    frame.names.forEachIndexed { i, name ->
        val label = frame.measurer.measure(
            text = name,
            style = frame.labelStyle,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(maxWidth = maxWidth),
        )
        drawText(label, topLeft = Offset(0f, mids[i] - label.size.height / 2f))
    }
}

private fun DrawScope.drawTickLabels(frame: StripFrame, x: (Long) -> Float) {
    val top = frame.rows.axisTop + TICK_LENGTH.toPx() + 2.dp.toPx()
    val gap = 6.dp.toPx()
    var end = Float.NEGATIVE_INFINITY
    frame.ticks.forEachIndexed { i, t ->
        val label = frame.measurer.measure(frame.tickTexts[i], frame.axisStyle)
        val at = (x(t) - label.size.width / 2f).coerceAtMost(size.width - label.size.width).coerceAtLeast(0f)
        if (at >= end + gap) {
            drawText(label, topLeft = Offset(at, top))
            end = at + label.size.width
        }
    }
}

private val PreviousGlyph: ImageVector = chevron("EpisodePrevious", outer = 15f, point = 8f)
private val NextGlyph: ImageVector = chevron("EpisodeNext", outer = 9f, point = 16f)

private fun chevron(name: String, outer: Float, point: Float): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(
            stroke = SolidColor(Ink.Black),
            strokeLineWidth = 2.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(outer, 5f)
            lineTo(point, 12f)
            lineTo(outer, 19f)
        }
        .build()

/** The episodes that overlap the window, oldest first: the order `selected` indexes. */
internal fun episodesOverlapping(episodes: List<Episode>, fromMs: Long, toMs: Long): List<Episode> =
    episodes.filter { it.endMs > fromMs && it.startMs < toMs }.sortedBy { it.startMs }

/** The x of [ms] on a chart that starts at [left] and is [width] wide. Times outside the window fall outside the chart. */
internal fun timeToX(ms: Long, fromMs: Long, toMs: Long, left: Float, width: Float): Float {
    if (toMs <= fromMs) return left
    return left + width * ((ms - fromMs).toDouble() / (toMs - fromMs)).toFloat()
}

/** Windows over 36 hours tick every local midnight, and shorter ones every third local hour. */
internal fun isDailyTicks(fromMs: Long, toMs: Long): Boolean = toMs - fromMs > DAILY_TICKS_OVER_HOURS * HOUR_MS

internal fun tickTimes(fromMs: Long, toMs: Long, zone: ZoneId): List<Long> {
    if (toMs <= fromMs) return emptyList()
    val first = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDateTime()
    val out = ArrayList<Long>()
    if (isDailyTicks(fromMs, toMs)) {
        var day = first.toLocalDate().minusDays(1)
        while (true) {
            val ms = day.atStartOfDay(zone).toInstant().toEpochMilli()
            if (ms > toMs) break
            if (ms >= fromMs) out += ms
            day = day.plusDays(1)
        }
    } else {
        var at = first.toLocalDate().atTime(first.hour / 3 * 3, 0).minusHours(3)
        while (true) {
            val ms = at.atZone(zone).toInstant().toEpochMilli()
            if (ms > toMs) break
            if (ms >= fromMs) out += ms
            at = at.plusHours(3)
        }
    }
    return out
}

internal fun tickText(ms: Long, zone: ZoneId, daily: Boolean, locale: Locale): String {
    val at = Instant.ofEpochMilli(ms).atZone(zone)
    return if (daily) at.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale)
    else String.format(locale, "%02d", at.hour)
}

/** The episode a key moves to, or null when it can't move. From no selection, forward is the first and back the last. */
internal fun stepTo(current: Int?, count: Int, forward: Boolean): Int? {
    if (count <= 0) return null
    val at = current?.takeIf { it in 0 until count }
    return when {
        at == null -> if (forward) 0 else count - 1
        forward -> (at + 1).takeIf { it < count }
        else -> (at - 1).takeIf { it >= 0 }
    }
}
