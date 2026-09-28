package app.booxultimatum.nib.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.booxultimatum.kit.ui.Lamp
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import app.booxultimatum.nib.R
import app.booxultimatum.nib.brush.Palette
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.input.InputSample
import app.booxultimatum.nib.engine.input.StrokeBuilder
import app.booxultimatum.nib.engine.render.StrokeRenderer
import app.booxultimatum.nib.render.CanvasSink
import kotlin.math.PI
import kotlin.math.sin

/** A round toolbar key with a glyph; [selected] fills it black, as the kit does for the one primary key. */
@Composable
fun ToolKey(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true) {
    val fill = if (selected && enabled) Ink.Black else Ink.Paper
    val ink = when {
        !enabled -> Ink.Rule
        selected -> Ink.Paper
        else -> Ink.Black
    }
    Box(
        modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(fill)
            .border(Lines.rim, if (enabled) Ink.Black else Ink.Rule, CircleShape)
            .clickable(remember { MutableInteractionSource() }, LocalIndication.current, enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics {
                contentDescription = label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(26.dp))
    }
}

/** A compact pill for a choice among many (a width, a pressure preset); the chosen one is filled black. */
@Composable
fun ChipKey(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier
            .defaultMinSize(minWidth = 56.dp, minHeight = 48.dp)
            .clip(shape)
            .background(if (selected && enabled) Ink.Black else Ink.Paper)
            .border(Lines.rim, if (enabled) Ink.Black else Ink.Rule, shape)
            .clickable(remember { MutableInteractionSource() }, LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> Ink.Legend
                selected -> Ink.Paper
                else -> Ink.Black
            },
            maxLines = 1,
        )
    }
}

/** A switch as the instrument draws it: a lamp and its label, the whole row one target. */
@Composable
fun ToggleRow(title: String, detail: String?, on: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val state = stringResource(if (on) R.string.toggle_on else R.string.toggle_off)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Switch) { onToggle(!on) }
            .semantics(mergeDescendants = true) { stateDescription = state }
            .padding(vertical = Space.s),
        verticalAlignment = Alignment.Top,
    ) {
        Lamp(on, Modifier.padding(top = 5.dp))
        Spacer(Modifier.size(Space.m))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
        }
        Text(state, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = Space.m))
    }
}

/**
 * A panel laid over the canvas: paper with a black rim, taking every touch inside it. The area around it closes it
 * ([onDismiss]); nothing animates.
 */
@Composable
fun OverlayPanel(onDismiss: () -> Unit, alignment: Alignment = Alignment.TopCenter, maxWidth: Dp = 760.dp, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = alignment,
    ) {
        Column(
            Modifier
                .padding(Space.s)
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .background(Ink.Paper, RoundedCornerShape(10.dp))
                .border(Lines.engraved, Ink.Black, RoundedCornerShape(10.dp))
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(Space.l),
            content = content,
        )
    }
}

/** A colour swatch key; the chosen one wears a thick black ring, pale ones a hairline so they show on paper. */
@Composable
fun SwatchKey(argb: Int, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(52.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics {
                contentDescription = label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(52.dp)) {
            val r = size.minDimension / 2f
            drawCircle(Color(argb), radius = r - 7.dp.toPx())
            if (Palette.isPale(argb)) drawCircle(Ink.Rule, radius = r - 7.dp.toPx(), style = Stroke(Lines.hairline.toPx()))
            if (selected) drawCircle(Ink.Black, radius = r - 2.dp.toPx(), style = Stroke(3.dp.toPx()))
        }
    }
}

/** A sample of what [brush] draws in [color]: an S-curve with pressure rising and falling, rendered by the engine. */
@Composable
fun BrushSample(brush: BrushSpec, color: Int, modifier: Modifier = Modifier) {
    val stroke = remember(brush, color) {
        val b = StrokeBuilder(brush, color, 7L)
        val n = 48
        for (i in 0..n) {
            val t = i / n.toFloat()
            b.add(InputSample(20f + t * 280f, 40f + 22f * sin(t * 2f * PI.toFloat()), 0.15f + 0.85f * sin(t * PI.toFloat()), timeNanos = i * 8_000_000L))
        }
        b.finish()
    }
    val sink = remember { CanvasSink() }
    Canvas(modifier) {
        val scale = minOf(size.width / 320f, size.height / 80f)
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.translate((size.width - 320f * scale) / 2f, (size.height - 80f * scale) / 2f)
            nc.scale(scale, scale)
            StrokeRenderer.render(stroke, sink.on(nc), 0.25f / scale)
            nc.restore()
        }
    }
}
