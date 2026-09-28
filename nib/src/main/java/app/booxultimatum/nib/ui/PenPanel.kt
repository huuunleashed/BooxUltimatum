package app.booxultimatum.nib.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.booxultimatum.kit.ui.Glyphs
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import app.booxultimatum.nib.NibSettings
import app.booxultimatum.nib.R
import app.booxultimatum.nib.brush.BrushGroup
import app.booxultimatum.nib.brush.Palette
import app.booxultimatum.nib.brush.PressurePreset
import app.booxultimatum.nib.brush.PreviewPolicy
import app.booxultimatum.nib.brush.WidthSteps
import app.booxultimatum.nib.editor.ToolMode
import app.booxultimatum.nib.editor.ToolState
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import kotlin.math.roundToInt

/** The pen panel: every brush by group, its width, pressure and colour. Two pages, Brush and Colour, instead of a long scroll. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PenPanel(tools: ToolState, settings: NibSettings, onClose: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val preset = tools.current
    val spec = preset.spec()
    OverlayPanel(onDismiss = onClose) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.pen_title, tools.selected + 1, stringResource(Names.brush(preset.kind))),
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
            )
            ToolKey(Glyphs.Close, stringResource(R.string.action_close), onClick = onClose)
        }
        BrushSample(spec, preset.color, Modifier.fillMaxWidth().height(84.dp).padding(vertical = Space.s))
        PreviewNote(spec, settings)
        Row(Modifier.padding(vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            ChipKey(stringResource(R.string.pen_page_brush), page == 0, onClick = { page = 0 })
            ChipKey(stringResource(R.string.pen_page_colour), page == 1, onClick = { page = 1 })
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (page == 0) {
                for (group in BrushGroup.entries) {
                    Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.Top) {
                        Text(
                            stringResource(Names.group(group)), style = MaterialTheme.typography.labelLarge, color = Ink.Legend,
                            modifier = Modifier.width(104.dp).padding(top = 14.dp),
                        )
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            for (kind in group.kinds) {
                                ChipKey(stringResource(Names.brush(kind)), kind == preset.kind, onClick = {
                                    tools.updateCurrent { p ->
                                        p.copy(kind = kind, width = p.width.coerceIn(BrushSpec.widthRange(kind)).takeIf { p.kind.isSimilar(kind) } ?: BrushSpec.defaults(kind).width)
                                    }
                                })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Space.s))
                WidthChooser(BrushSpec.widthRange(preset.kind), preset.width) { w -> tools.updateCurrent { it.copy(width = w) } }
                Spacer(Modifier.height(Space.s))
                Text(stringResource(R.string.pen_pressure), style = MaterialTheme.typography.labelLarge, color = Ink.Legend)
                if (PressurePreset.applies(preset.kind)) {
                    Row(Modifier.padding(vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        for (p in PressurePreset.entries) ChipKey(stringResource(Names.pressure(p)), p == preset.pressure, onClick = { tools.updateCurrent { it.copy(pressure = p) } })
                    }
                } else {
                    Text(stringResource(R.string.pen_pressure_none), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    for (s in Palette.SWATCHES) {
                        SwatchKey(s.argb, stringResource(Names.swatch(s)), (preset.color or Palette.OPAQUE) == s.argb, onClick = { tools.updateCurrent { it.copy(color = s.argb) } })
                    }
                }
                Spacer(Modifier.height(Space.m))
                HueLightnessPicker(preset.color) { c -> tools.updateCurrent { it.copy(color = c) } }
            }
        }
    }
}

/** Brushes close enough that the width carries over when switching between them. */
private fun BrushKind.isSimilar(other: BrushKind): Boolean {
    val thin = setOf(BrushKind.Fineliner, BrushKind.Fountain, BrushKind.Ballpoint, BrushKind.Pencil, BrushKind.Graphite, BrushKind.Dash, BrushKind.SquarePen)
    return (this in thin) == (other in thin)
}

/** Says what the display previews while drawing, when it isn't the brush's own style. */
@Composable
private fun PreviewNote(spec: BrushSpec, settings: NibSettings) {
    val standIn = PreviewPolicy.standIn(spec, settings.tryUnverifiedStyles)
    val text = when {
        standIn != null -> stringResource(R.string.pen_preview_stand_in, stringResource(Names.style(standIn)), stringResource(Names.style(spec.preview.style)))
        !spec.preview.style.verified -> stringResource(R.string.pen_preview_unverified, stringResource(Names.style(spec.preview.style)))
        else -> null
    }
    if (text != null) Text(text, style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(bottom = Space.xs))
}

/** The width steps for a range, fine at the thin end, with the current width named. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WidthChooser(range: ClosedFloatingPointRange<Float>, width: Float, onWidth: (Float) -> Unit) {
    val steps = WidthSteps.forRange(range)
    val chosen = WidthSteps.nearestIndex(steps, width)
    Text(stringResource(R.string.pen_width, WidthSteps.label(width)), style = MaterialTheme.typography.labelLarge, color = Ink.Legend)
    FlowRow(Modifier.padding(vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        steps.forEachIndexed { i, w -> ChipKey(WidthSteps.label(w), i == chosen, onClick = { onWidth(w) }) }
    }
}

/**
 * Any colour by hue and lightness: two strips, tapped or dragged, with a mark where the current colour sits. The
 * colour is taken when the finger or pen lifts, so a drag doesn't rewrite the preset at every step.
 */
@Composable
fun HueLightnessPicker(color: Int, onColor: (Int) -> Unit) {
    val (h0, l0) = remember(color) { Palette.hueLightness(color) }
    var hue by remember(color) { mutableFloatStateOf(h0) }
    var light by remember(color) { mutableFloatStateOf(l0.coerceIn(0.1f, 0.9f)) }
    val hueLabel = stringResource(R.string.picker_hue)
    val lightLabel = stringResource(R.string.picker_lightness)
    Text(stringResource(R.string.picker_title), style = MaterialTheme.typography.labelLarge, color = Ink.Legend)
    Spacer(Modifier.height(Space.xs))
    Strip(hueLabel, hue / 360f, { f -> Palette.hsl(f * 360f, 0.45f) }, onMove = { hue = it * 360f }, onUp = { onColor(Palette.hsl(hue, light)) })
    Spacer(Modifier.height(Space.s))
    Strip(lightLabel, (light - 0.1f) / 0.8f, { f -> Palette.hsl(hue, 0.1f + 0.8f * f) }, onMove = { light = 0.1f + 0.8f * it }, onUp = { onColor(Palette.hsl(hue, light)) })
}

@Composable
private fun Strip(label: String, value: Float, colorAt: (Float) -> Int, onMove: (Float) -> Unit, onUp: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .border(Lines.rim, Ink.Black, shape)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onMove((down.position.x / size.width).coerceIn(0f, 1f))
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        onMove((c.position.x / size.width).coerceIn(0f, 1f))
                        c.consume()
                        if (!c.pressed) break
                    }
                    onUp()
                }
            },
    ) {
        val cells = 36
        val w = size.width / cells
        for (i in 0 until cells) {
            drawRect(Color(colorAt((i + 0.5f) / cells)), topLeft = Offset(i * w, 0f), size = androidx.compose.ui.geometry.Size(w + 1f, size.height))
        }
        val x = value.coerceIn(0f, 1f) * size.width
        drawLine(Ink.Paper, Offset(x, 0f), Offset(x, size.height), strokeWidth = 7.dp.toPx())
        drawLine(Ink.Black, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3.dp.toPx())
    }
}

/** The eraser panel: pixel, stroke or lasso, and the width for the first two. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EraserPanel(tools: ToolState, onClose: () -> Unit) {
    OverlayPanel(onDismiss = onClose) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.eraser_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            ToolKey(Glyphs.Close, stringResource(R.string.action_close), onClick = onClose)
        }
        Spacer(Modifier.height(Space.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            for (k in ToolState.ERASERS) ChipKey(stringResource(Names.brush(k)), tools.eraser == k, onClick = { tools.chooseEraser(k); tools.mode = ToolMode.Eraser })
        }
        Text(
            stringResource(
                when (tools.eraser) {
                    BrushKind.PixelEraser -> R.string.eraser_pixel_detail
                    BrushKind.StrokeEraser -> R.string.eraser_stroke_detail
                    else -> R.string.eraser_lasso_detail
                },
            ),
            style = MaterialTheme.typography.bodyMedium, color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s),
        )
        if (tools.eraser != BrushKind.LassoEraser) {
            WidthChooser(BrushSpec.widthRange(tools.eraser), tools.eraserWidth(tools.eraser)) { tools.setEraserWidth(tools.eraser, it) }
        }
        Text(stringResource(R.string.eraser_pen_note), style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(top = Space.s))
    }
}

/** A readout like "250 %". */
fun zoomLabel(scale: Float): String = "${(scale * 100f).roundToInt()} %"
