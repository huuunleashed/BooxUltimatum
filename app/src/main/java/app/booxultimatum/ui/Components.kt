package app.booxultimatum.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.booxultimatum.R
import app.booxultimatum.ui.theme.CodeStyle
import app.booxultimatum.ui.theme.Ink
import app.booxultimatum.ui.theme.Lines
import app.booxultimatum.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads data off the main thread when [key] changes, keeping the previous value on screen meanwhile so the
 * e-ink panel is not repainted with an empty state between readings.
 */
@Composable
fun <T> rememberReading(key: Any, read: suspend () -> T): T? {
    var value by remember { mutableStateOf<T?>(null) }
    LaunchedEffect(key) { value = withContext(Dispatchers.IO) { read() } }
    return value
}

/** The page frame: one scrolling column with a comfortable measure, centred in wide windows so landscape has no dead margin. */
@Composable
fun InstrumentPage(compact: Boolean, content: LazyListScope.() -> Unit) {
    val side = if (compact) Space.l else Space.xl
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 1200.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, top = Space.xl, bottom = Space.xxl),
            content = content,
        )
    }
}

/**
 * Ink behind the status bar. NA6C FW 4.3 keeps status-bar icons white over apps that EinkWise tunes (it strips
 * `LIGHT_STATUS_BARS` from their windows), so a paper background there would hide the clock and battery.
 */
@Composable
fun StatusStrip(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().windowInsetsTopHeight(androidx.compose.foundation.layout.WindowInsets.statusBars).background(Ink.Black))
}

@Composable
fun ScreenHeader(title: String, subtitle: String?, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = Space.l), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(end = Space.m)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            if (subtitle != null) {
                Spacer(Modifier.height(Space.xs))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), content = actions)
    }
}

/**
 * The tuning scale: 0-100 engraved like a Braun receiver dial, the needle at the measured level and the
 * figure riding on it. Every mark is real; nothing on the scale is decorative.
 */
@Composable
fun TuningScale(level: Int, modifier: Modifier = Modifier, figureStyle: TextStyle = MaterialTheme.typography.displayLarge) {
    val measurer = rememberTextMeasurer()
    val tickStyle = MaterialTheme.typography.labelMedium.copy(color = Ink.Legend)
    val percentStyle = SpanStyle(fontSize = figureStyle.fontSize * 0.42f, color = Ink.Black)
    val figureText: AnnotatedString = buildAnnotatedString {
        append(level.toString())
        withStyle(percentStyle) { append(" %") }
    }
    val figure = measurer.measure(figureText, figureStyle)
    val tickLabel = measurer.measure("100", tickStyle)
    val density = LocalDensity.current
    val height = with(density) { (figure.size.height + tickLabel.size.height).toDp() } + 56.dp
    val description = stringResource(R.string.scale_description, level)

    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val inset = tickLabel.size.width / 2f + 2.dp.toPx()
        val left = inset
        val right = size.width - inset
        val span = right - left
        val baseY = figure.size.height + 40.dp.toPx()
        val major = 22.dp.toPx()
        val minor = 11.dp.toPx()

        for (i in 0..100 step 2) {
            val x = left + span * i / 100f
            val isMajor = i % 10 == 0
            drawLine(
                color = if (isMajor) Ink.Black else Ink.Rule,
                start = Offset(x, baseY - if (isMajor) major else minor),
                end = Offset(x, baseY),
                strokeWidth = if (isMajor) Lines.engraved.toPx() else Lines.hairline.toPx(),
            )
            if (i % 20 == 0) {
                val label = measurer.measure(i.toString(), tickStyle)
                drawText(label, topLeft = Offset(x - label.size.width / 2f, baseY + 10.dp.toPx()))
            }
        }
        drawLine(Ink.Black, Offset(left, baseY), Offset(right, baseY), strokeWidth = Lines.rim.toPx())

        val needleX = left + span * level.coerceIn(0, 100) / 100f
        val figureX = (needleX - figure.size.width / 2f).coerceIn(0f, size.width - figure.size.width)
        drawText(figure, topLeft = Offset(figureX, 0f))
        drawLine(
            color = Ink.Black,
            start = Offset(needleX, figure.size.height + 8.dp.toPx()),
            end = Offset(needleX, baseY + 6.dp.toPx()),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Square,
        )
    }
}

/** A panel lamp. On = signal green disc inside a black rim; off = an empty rim. The label always states it too. */
@Composable
fun Lamp(on: Boolean, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        if (on) drawCircle(Ink.Signal, radius = r)
        drawCircle(Ink.Black, radius = r - Lines.rim.toPx() / 2f, style = Stroke(Lines.rim.toPx()))
    }
}

@Composable
fun LampRow(on: Boolean, title: String, detail: String?, modifier: Modifier = Modifier) {
    val state = stringResource(if (on) R.string.lamp_on else R.string.lamp_off)
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = Space.s).semantics(mergeDescendants = true) { stateDescription = state },
        verticalAlignment = Alignment.Top,
    ) {
        Lamp(on, Modifier.padding(top = 5.dp))
        Spacer(Modifier.size(Space.m))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
        }
    }
}

/** A round-ended key with a black rim. [primary] fills it black; use it for at most one key per screen. */
@Composable
fun Key(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true) {
    val shape = RoundedCornerShape(percent = 50)
    val fill = if (primary && enabled) Ink.Black else Ink.Paper
    val ink = when {
        !enabled -> Ink.Legend
        primary -> Ink.Paper
        else -> Ink.Black
    }
    Box(
        modifier
            .defaultMinSize(minWidth = 88.dp, minHeight = 48.dp)
            .clip(shape)
            .background(fill)
            .border(Lines.rim, if (enabled) Ink.Black else Ink.Rule, shape)
            .clickable(remember { MutableInteractionSource() }, LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
    }
}

/**
 * A round key that carries a glyph instead of a word, for controls whose meaning is universal (settings, edit,
 * page turns). [label] is read by accessibility services and shown nowhere else.
 */
@Composable
fun IconKey(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true) {
    val fill = if (primary && enabled) Ink.Black else Ink.Paper
    val ink = when {
        !enabled -> Ink.Rule
        primary -> Ink.Paper
        else -> Ink.Black
    }
    Box(
        modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(fill)
            .border(Lines.rim, if (enabled) Ink.Black else Ink.Rule, CircleShape)
            .clickable(remember { MutableInteractionSource() }, LocalIndication.current, enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(26.dp))
    }
}

/** An engraved section: title, a 2 dp rule, then its readings. Sections are separated by space, not boxes. */
@Composable
fun Plate(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(bottom = Space.s + Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
            action?.invoke()
        }
        HorizontalDivider(thickness = Lines.engraved, color = Ink.Black)
        Spacer(Modifier.height(Space.s))
        content()
    }
}

/** A datasheet row: engraved legend left, reading right, hairline below. */
@Composable
fun SpecRow(label: String, value: String, modifier: Modifier = Modifier, code: Boolean = false, note: String? = null) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Ink.Legend, modifier = Modifier.weight(0.4f).padding(end = Space.m))
            Column(Modifier.weight(0.6f)) {
                Text(value, style = if (code) CodeStyle else MaterialTheme.typography.bodyLarge)
                if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
            }
        }
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}

/** A small engraved mark for package states. Text carries the meaning; the rim only groups it. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    val shape = RoundedCornerShape(percent = 50)
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (strong) Ink.Paper else Ink.Black,
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .background(if (strong) Ink.Black else Ink.Paper)
            .border(Lines.hairline, Ink.Black, shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Previous / page n of m / Next. E-ink pages instead of scrolling long lists. */
@Composable
fun Pager(page: Int, pages: Int, onPage: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = Space.l), verticalAlignment = Alignment.CenterVertically) {
        Key(stringResource(R.string.action_previous), onClick = { onPage(page - 1) }, enabled = page > 0)
        Text(
            stringResource(R.string.pager_position, page + 1, pages),
            style = MaterialTheme.typography.labelLarge,
            color = Ink.Legend,
            modifier = Modifier.weight(1f).padding(horizontal = Space.m),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Key(stringResource(R.string.action_next), onClick = { onPage(page + 1) }, enabled = page < pages - 1)
    }
}

/** Shown before the first reading lands. Plain words, no spinner: the panel should not flicker. */
@Composable
fun Reading(modifier: Modifier = Modifier) {
    Text(stringResource(R.string.state_reading), style = MaterialTheme.typography.bodyLarge, color = Ink.Legend, modifier = modifier.padding(vertical = Space.l))
}

@Composable
fun Paragraph(text: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = Ink.Black) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = color, modifier = modifier.widthIn(max = 680.dp))
}

@Composable
fun CodeBlock(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = CodeStyle,
        overflow = TextOverflow.Visible,
        modifier = modifier
            .fillMaxWidth()
            .border(Lines.hairline, Ink.Black, RoundedCornerShape(6.dp))
            .padding(Space.m),
    )
}

@Composable
fun Dot(modifier: Modifier = Modifier) {
    Box(modifier.size(6.dp).clip(CircleShape).background(Ink.Black))
}

/** A pill search field in the instrument's rim. Filters as you type; the keyboard's search key just closes it. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.Black),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Ink.Black),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).border(Lines.rim, Ink.Black, shape).padding(start = 20.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(Glyphs.Search, contentDescription = null, tint = Ink.Black)
                Spacer(Modifier.size(12.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Ink.Legend, maxLines = 1)
                    inner()
                }
                if (value.isNotEmpty()) {
                    Box(
                        Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).clip(shape)
                            .clickable(role = Role.Button) { onValueChange("") }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(R.string.action_clear), style = MaterialTheme.typography.labelLarge) }
                }
            }
        },
    )
}

/**
 * A key for consequential actions: the first tap arms it and says so, the second tap acts. Inline instead
 * of a dialog, so the panel does not repaint a modal for a single decision.
 */
@Composable
fun ConfirmKey(text: String, confirmText: String, onConfirm: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var armed by remember { mutableStateOf(false) }
    Key(
        if (armed) confirmText else text,
        onClick = { if (armed) { armed = false; onConfirm() } else armed = true },
        modifier = modifier,
        primary = armed,
        enabled = enabled,
    )
}

@Composable
fun ErrorLine(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Ink.Alert, modifier = modifier.padding(top = Space.s))
}
