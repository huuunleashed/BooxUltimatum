package app.booxultimatum.nib.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.booxultimatum.nib.R
import app.booxultimatum.nib.brush.Palette
import app.booxultimatum.nib.editor.ToolMode
import app.booxultimatum.nib.ui.studio.CardBody
import app.booxultimatum.nib.ui.studio.Lamp
import app.booxultimatum.nib.ui.studio.SectionLabel
import app.booxultimatum.nib.ui.studio.SlabButton
import app.booxultimatum.nib.ui.studio.Studio
import app.booxultimatum.nib.ui.studio.StudioGlyphs
import app.booxultimatum.nib.ui.studio.StudioType
import app.booxultimatum.nib.ui.studio.ValueChip
import app.booxultimatum.nib.ui.studio.EntryRequest
import app.booxultimatum.nib.ui.studio.slab

/**
 * The Colour panel: a hue strip and a saturation and value square for any colour, the palette tuned for Kaleido, the
 * eight colours used lately, the hex to type, and the eyedropper to take one from the page. The pen takes a colour
 * when the finger or pen lifts, so a drag doesn't rewrite it at every step.
 */
@Composable
internal fun ColourPanel(ed: Editor, landscape: Boolean) {
    val tools = ed.tools
    val colour = tools.current.color or Palette.OPAQUE
    val start = remember(colour) { Palette.toHsv(colour) }
    // A grey has no hue of its own: the strip keeps the last one.
    val lastHue = remember { floatArrayOf(start.first) }
    var hue by remember(colour) { mutableFloatStateOf(if (start.second > 0.02f) start.first.also { lastHue[0] = it } else lastHue[0]) }
    var sat by remember(colour) { mutableFloatStateOf(start.second) }
    var value by remember(colour) { mutableFloatStateOf(start.third) }
    var dragging by remember { mutableStateOf(false) }
    val working = if (dragging) Palette.hsv(hue, sat, value) else colour
    fun commit() {
        dragging = false
        tools.useColour(Palette.hsv(hue, sat, value))
    }
    StudioPanel(PanelId.COLOUR, stringResource(R.string.colour_title), ed, landscape, width = 480.dp) {
        CardBody {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(end = Studio.ShadowKey, bottom = Studio.ShadowKey).size(56.dp).slab(radius = Studio.RadiusS, shadow = Studio.ShadowKey, fill = Color(working)))
                Spacer(Modifier.width(12.dp))
                ValueChip(
                    Palette.toHex(working), onClick = {
                        ed.type(
                            EntryRequest(ed.string(R.string.colour_hex), Palette.toHex(colour), numeric = false, hint = ed.string(R.string.colour_hex_hint), maxLength = 7) { text ->
                                val c = Palette.parseHex(text) ?: return@EntryRequest ed.string(R.string.colour_hex_invalid)
                                tools.useColour(c)
                                null
                            },
                        )
                    },
                    description = stringResource(R.string.colour_hex_type, Palette.toHex(working)),
                )
                Spacer(Modifier.weight(1f))
                SlabButton(stringResource(R.string.tool_eyedropper), onClick = {
                    tools.mode = ToolMode.Eyedropper
                    if (!ed.panels.pinned(PanelId.COLOUR)) ed.panels.close(PanelId.COLOUR)
                }, icon = StudioGlyphs.Eyedropper)
            }
            SaturationValueSquare(
                hue, sat, value,
                onMove = { s, v ->
                    dragging = true
                    sat = s
                    value = v
                },
                onUp = { commit() },
                label = stringResource(R.string.picker_square),
                modifier = Modifier.fillMaxWidth().height(if (landscape) 132.dp else 176.dp),
            )
            HueStrip(
                hue, onMove = {
                    dragging = true
                    hue = it
                    lastHue[0] = it
                    if (sat < 0.05f) sat = 0.85f
                    if (value < 0.1f) value = 0.8f
                },
                onUp = { commit() }, label = stringResource(R.string.picker_hue),
                modifier = Modifier.fillMaxWidth().height(Studio.Target),
            )
            SectionLabel(stringResource(R.string.colour_palette))
            SwatchGrid(Palette.SWATCHES.map { it.argb }, colour, columns = 6, label = { c -> Palette.swatchOf(c)?.let { ed.string(Names.swatch(it)) } ?: Palette.toHex(c) }) { tools.useColour(it) }
            SectionLabel(stringResource(R.string.colour_recent))
            if (tools.recentColours.isEmpty()) {
                Text(stringResource(R.string.colour_recent_none), style = StudioType.Small, color = Studio.Legend)
            } else {
                SwatchGrid(tools.recentColours, colour, columns = 8, label = { Palette.toHex(it) }) { tools.useColour(it) }
            }
        }
    }
}

@Composable
private fun SwatchGrid(colours: List<Int>, chosen: Int, columns: Int, label: (Int) -> String, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in colours.chunked(columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in row) Swatch(c, (c or Palette.OPAQUE) == chosen, label(c)) { onPick(c) }
            }
        }
    }
}

/** A colour to pick: a small slab of ink; the chosen one sits lower, with the lamp. */
@Composable
private fun Swatch(argb: Int, selected: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(end = Studio.ShadowKey, bottom = Studio.ShadowKey)
            .size(Studio.Target)
            .slab(radius = Studio.RadiusS, shadow = if (selected) 0.dp else Studio.ShadowKey, fill = Color(argb or Palette.OPAQUE), outlineWidth = if (selected) 3.dp else Studio.Outline)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics {
                contentDescription = label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Lamp(true, size = 14.dp, ring = if (Palette.isPale(argb)) Color.Black else Color.White)
    }
}

/** Saturation across, value up: the square for the strip's hue, with a ringed mark where the colour sits. */
@Composable
private fun SaturationValueSquare(hue: Float, sat: Float, value: Float, onMove: (Float, Float) -> Unit, onUp: () -> Unit, label: String, modifier: Modifier) {
    val move by rememberUpdatedState(onMove)
    val up by rememberUpdatedState(onUp)
    Canvas(
        modifier
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                fun at(o: Offset) = move((o.x / size.width).coerceIn(0f, 1f), (1f - o.y / size.height).coerceIn(0f, 1f))
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    at(down.position)
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        at(c.position)
                    }
                    up()
                }
            },
    ) {
        val r = CornerRadius(Studio.RadiusS.toPx())
        drawRoundRect(Color(Palette.hsv(hue, 1f, 1f)), cornerRadius = r)
        drawRoundRect(Brush.horizontalGradient(listOf(Color.White, Color.White.copy(alpha = 0f))), cornerRadius = r)
        drawRoundRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0f), Color.Black)), cornerRadius = r)
        drawRoundRect(Color.Black, cornerRadius = r, style = Stroke(Studio.Outline.toPx()))
        val x = sat * size.width
        val y = (1f - value) * size.height
        drawCircle(Color.Black, radius = 11.dp.toPx(), center = Offset(x, y), style = Stroke(4.dp.toPx()))
        drawCircle(Color.White, radius = 11.dp.toPx(), center = Offset(x, y), style = Stroke(2.dp.toPx()))
    }
}

/** Every hue in a strip, tapped or dragged, with a card thumb where the current one sits. */
@Composable
private fun HueStrip(hue: Float, onMove: (Float) -> Unit, onUp: () -> Unit, label: String, modifier: Modifier) {
    val move by rememberUpdatedState(onMove)
    val up by rememberUpdatedState(onUp)
    Canvas(
        modifier
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    move((down.position.x / size.width).coerceIn(0f, 1f) * 360f)
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        move((c.position.x / size.width).coerceIn(0f, 1f) * 360f)
                    }
                    up()
                }
            },
    ) {
        val barH = 20.dp.toPx()
        val top = (size.height - barH) / 2f
        val hues = (0..12).map { Color(Palette.hsv(it * 30f, 1f, 1f)) }
        drawRoundRect(Brush.horizontalGradient(hues), topLeft = Offset(0f, top), size = Size(size.width, barH), cornerRadius = CornerRadius(3.dp.toPx()))
        drawRoundRect(Color.Black, topLeft = Offset(0f, top), size = Size(size.width, barH), cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(Studio.Outline.toPx()))
        val x = hue / 360f * size.width
        val tw = 16.dp.toPx()
        val th = 36.dp.toPx()
        val ty = (size.height - th) / 2f
        val sh = Studio.ShadowKey.toPx()
        val tx = (x - tw / 2).coerceIn(0f, size.width - tw)
        drawRoundRect(Color.Black, topLeft = Offset(tx + sh, ty + sh), size = Size(tw, th), cornerRadius = CornerRadius(3.dp.toPx()))
        drawRoundRect(Color(Palette.hsv(hue, 1f, 1f)), topLeft = Offset(tx, ty), size = Size(tw, th), cornerRadius = CornerRadius(3.dp.toPx()))
        drawRoundRect(Color.Black, topLeft = Offset(tx + 1.dp.toPx(), ty + 1.dp.toPx()), size = Size(tw - 2.dp.toPx(), th - 2.dp.toPx()), cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(Studio.Outline.toPx()))
    }
}
