package app.booxultimatum.nib.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** What a slider's track shows under its thumb. */
sealed interface Track {
    /** Black up to the value. */
    data object Fill : Track

    /** A checkerboard fading into [argb]: opacity. */
    data class Checker(val argb: Int) : Track

    /** Colours laid along the track: a hue strip, a lightness ramp. */
    data class Colours(val colours: List<Color>) : Track
}

/**
 * A continuous property: its name and a value chip you tap to type an exact value, then − and + keys at either end of
 * a track you drag. The value snaps to the [scale]'s steps. While dragging, [onChange] gets each working value (and
 * null once the drag ends) so a sample can follow it; [onCommit] gets the value to keep, once.
 */
@Composable
fun StudioSlider(
    label: String,
    value: Float,
    scale: ValueScale,
    unit: String,
    onCommit: (Float) -> Unit,
    onType: () -> Unit,
    modifier: Modifier = Modifier,
    onChange: (Float?) -> Unit = {},
    enabled: Boolean = true,
    track: Track = Track.Fill,
    ticks: List<Float> = emptyList(),
    minusLabel: String,
    plusLabel: String,
    typeLabel: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    var working by remember { mutableStateOf<Float?>(null) }
    val shown = working ?: value
    val text = scale.format(shown) + if (unit.isEmpty()) "" else " $unit"
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = StudioType.Label, color = if (enabled) Studio.Ink else Studio.Faint, modifier = Modifier.weight(1f))
            if (trailing != null) {
                trailing()
                Spacer(Modifier.width(8.dp))
            }
            ValueChip(text, onClick = onType, description = typeLabel, enabled = enabled)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StudioKey(StudioGlyphs.Minus, minusLabel, onClick = { onCommit(scale.stepped(value, -1)) }, enabled = enabled && value > scale.min, size = Studio.Target)
            SliderTrack(
                label, text, shown, scale, enabled, track, ticks,
                onDrag = { v ->
                    working = v
                    onChange(v)
                },
                onRelease = { v ->
                    working = null
                    onChange(null)
                    if (v != null) onCommit(v)
                },
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            )
            StudioKey(StudioGlyphs.Plus, plusLabel, onClick = { onCommit(scale.stepped(value, 1)) }, enabled = enabled && value < scale.max, size = Studio.Target)
        }
    }
}

@Composable
private fun SliderTrack(
    label: String,
    stateText: String,
    value: Float,
    scale: ValueScale,
    enabled: Boolean,
    track: Track,
    ticks: List<Float>,
    onDrag: (Float) -> Unit,
    onRelease: (Float?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val drag by rememberUpdatedState(onDrag)
    val release by rememberUpdatedState(onRelease)
    val thumbHalf = 9.dp
    Canvas(
        modifier
            .height(Studio.Target)
            .semantics {
                contentDescription = label
                stateDescription = stateText
                progressBarRangeInfo = ProgressBarRangeInfo(scale.fraction(value), 0f..1f)
                if (enabled) {
                    setProgress { f ->
                        release(scale.value(f))
                        true
                    }
                }
            }
            .pointerInput(scale, enabled) {
                if (!enabled) return@pointerInput
                val pad = thumbHalf.toPx()
                fun at(x: Float) = scale.value((x - pad) / (size.width - 2 * pad).coerceAtLeast(1f))
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var v = at(down.position.x)
                    drag(v)
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        val next = at(c.position.x)
                        if (next != v) {
                            v = next
                            drag(v)
                        }
                    }
                    release(v)
                }
            },
    ) {
        val pad = thumbHalf.toPx()
        val barH = 14.dp.toPx()
        val top = (size.height - barH) / 2f
        val left = pad
        val w = size.width - 2 * pad
        val f = scale.fraction(value)
        val x = left + f * w
        val r = CornerRadius(3.dp.toPx())
        val ink = if (enabled) Studio.Ink else Studio.Faint
        drawRoundRect(Studio.Card, topLeft = Offset(left, top), size = Size(w, barH), cornerRadius = r)
        when (track) {
            Track.Fill -> drawRoundRect(ink, topLeft = Offset(left, top), size = Size(x - left, barH), cornerRadius = r)
            is Track.Checker -> {
                clipRect(left, top, left + w, top + barH) {
                    checker(left, top, w, barH, 7.dp.toPx())
                    val c = Color(track.argb or -0x1000000)
                    drawRect(Brush.horizontalGradient(listOf(c.copy(alpha = 0f), c), startX = left, endX = left + w), topLeft = Offset(left, top), size = Size(w, barH))
                }
            }
            is Track.Colours -> drawRect(Brush.horizontalGradient(track.colours, startX = left, endX = left + w), topLeft = Offset(left, top), size = Size(w, barH))
        }
        drawRoundRect(ink, topLeft = Offset(left, top), size = Size(w, barH), cornerRadius = r, style = Stroke(Studio.Outline.toPx()))
        for (t in ticks) {
            val tx = left + scale.fraction(t) * w
            drawLine(Studio.Legend, Offset(tx, top + barH + 3.dp.toPx()), Offset(tx, top + barH + 8.dp.toPx()), strokeWidth = 1.5.dp.toPx())
        }
        // The thumb: a card upright on the track, with its own hard shadow.
        val th = 36.dp.toPx()
        val tw = thumbHalf.toPx() * 2
        val ty = (size.height - th) / 2f
        val sh = Studio.ShadowKey.toPx()
        drawRoundRect(Studio.Ink, topLeft = Offset(x - tw / 2 + sh, ty + sh), size = Size(tw, th), cornerRadius = CornerRadius(3.dp.toPx()))
        drawRoundRect(Studio.Card, topLeft = Offset(x - tw / 2, ty), size = Size(tw, th), cornerRadius = CornerRadius(3.dp.toPx()))
        drawRoundRect(ink, topLeft = Offset(x - tw / 2 + 1.dp.toPx(), ty + 1.dp.toPx()), size = Size(tw - 2.dp.toPx(), th - 2.dp.toPx()), cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(Studio.Outline.toPx()))
        drawLine(ink, Offset(x, ty + 9.dp.toPx()), Offset(x, ty + th - 9.dp.toPx()), strokeWidth = Studio.Outline.toPx())
    }
}

private val CHECKER = Color(0xFFCCCCCC)

/** A grey and white checkerboard in a box, the usual sign for transparency. */
fun DrawScope.checker(left: Float, top: Float, w: Float, h: Float, cell: Float) {
    drawRect(Studio.Card, topLeft = Offset(left, top), size = Size(w, h))
    var y = top
    var row = 0
    while (y < top + h) {
        var x = left + if (row % 2 == 0) 0f else cell
        while (x < left + w) {
            drawRect(CHECKER, topLeft = Offset(x, y), size = Size(cell, cell))
            x += 2 * cell
        }
        y += cell
        row++
    }
}

/**
 * A short vertical slider for the rail, as Sketchbook's pucks: the value on top (tap to type), the track below it,
 * filled from the bottom. Commits on release; nothing redraws the canvas while it moves.
 */
@Composable
fun QuickSlider(
    label: String,
    value: Float,
    scale: ValueScale,
    text: (Float) -> String,
    onCommit: (Float) -> Unit,
    onType: () -> Unit,
    typeLabel: String,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    track: Track = Track.Fill,
) {
    var working by remember { mutableStateOf<Float?>(null) }
    val shown = working ?: value
    val commit by rememberUpdatedState(onCommit)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            Modifier
                .size(width = Studio.Key, height = Studio.Target)
                .semantics(mergeDescendants = true) { contentDescription = typeLabel }
                .clickable(role = Role.Button, onClickLabel = typeLabel, onClick = onType),
            contentAlignment = Alignment.Center,
        ) {
            Text(text(shown), style = StudioType.Value, maxLines = 1)
        }
        Canvas(
            Modifier
                .size(width = Studio.Key, height = height)
                .semantics {
                    contentDescription = label
                    stateDescription = text(shown)
                    progressBarRangeInfo = ProgressBarRangeInfo(scale.fraction(shown), 0f..1f)
                    setProgress { f ->
                        commit(scale.value(f))
                        true
                    }
                }
                .pointerInput(scale) {
                    val pad = 9.dp.toPx()
                    fun at(y: Float) = scale.value(1f - (y - pad) / (size.height - 2 * pad).coerceAtLeast(1f))
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        var v = at(down.position.y)
                        working = v
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                            v = at(c.position.y)
                            working = v
                        }
                        working = null
                        commit(v)
                    }
                },
        ) {
            val pad = 9.dp.toPx()
            val barW = 14.dp.toPx()
            val left = (size.width - barW) / 2f
            val h = size.height - 2 * pad
            val f = scale.fraction(shown)
            val y = pad + (1f - f) * h
            val r = CornerRadius(3.dp.toPx())
            drawRoundRect(Studio.Card, topLeft = Offset(left, pad), size = Size(barW, h), cornerRadius = r)
            when (track) {
                is Track.Checker -> clipRect(left, pad, left + barW, pad + h) {
                    checker(left, pad, barW, h, 7.dp.toPx())
                    val c = Color(track.argb or -0x1000000)
                    drawRect(Brush.verticalGradient(listOf(c, c.copy(alpha = 0f)), startY = pad, endY = pad + h), topLeft = Offset(left, pad), size = Size(barW, h))
                }
                else -> drawRoundRect(Studio.Ink, topLeft = Offset(left, y), size = Size(barW, pad + h - y), cornerRadius = r)
            }
            drawRoundRect(Studio.Ink, topLeft = Offset(left, pad), size = Size(barW, h), cornerRadius = r, style = Stroke(Studio.Outline.toPx()))
            val tw = 36.dp.toPx()
            val th = 18.dp.toPx()
            val tx = (size.width - tw) / 2f
            val sh = Studio.ShadowKey.toPx()
            drawRoundRect(Studio.Ink, topLeft = Offset(tx + sh, y - th / 2 + sh), size = Size(tw, th), cornerRadius = CornerRadius(3.dp.toPx()))
            drawRoundRect(Studio.Card, topLeft = Offset(tx, y - th / 2), size = Size(tw, th), cornerRadius = CornerRadius(3.dp.toPx()))
            drawRoundRect(Studio.Ink, topLeft = Offset(tx + 1.dp.toPx(), y - th / 2 + 1.dp.toPx()), size = Size(tw - 2.dp.toPx(), th - 2.dp.toPx()), cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(Studio.Outline.toPx()))
            drawLine(Studio.Ink, Offset(tx + 9.dp.toPx(), y), Offset(tx + tw - 9.dp.toPx(), y), strokeWidth = Studio.Outline.toPx())
        }
        Text(label, style = StudioType.Small, color = Studio.Legend, maxLines = 1)
    }
}
