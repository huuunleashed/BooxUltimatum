package app.booxultimatum.nib.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.booxultimatum.nib.pen.PenShields
import kotlinx.coroutines.delay

/**
 * A cut card: white fill, a hard black outline and a solid black shadow offset down and right, never blurred. The
 * shadow is drawn outside the bounds, so leave [shadow] of room below and to the right of it in flows.
 */
fun Modifier.slab(
    radius: Dp = Studio.RadiusM,
    shadow: Dp = Studio.ShadowCard,
    fill: Color = Studio.Card,
    outline: Color = Studio.Ink,
    outlineWidth: Dp = Studio.Outline,
): Modifier = this
    .drawBehind {
        val r = CornerRadius(radius.toPx())
        if (shadow > 0.dp) drawRoundRect(Studio.Ink, topLeft = Offset(shadow.toPx(), shadow.toPx()), size = size, cornerRadius = r)
        drawRoundRect(fill, size = size, cornerRadius = r)
    }
    .clip(RoundedCornerShape(radius))
    .drawWithContent {
        drawContent()
        val w = outlineWidth.toPx()
        if (w > 0f) {
            drawRoundRect(
                outline, topLeft = Offset(w / 2f, w / 2f), size = Size(size.width - w, size.height - w),
                cornerRadius = CornerRadius((radius.toPx() - w / 2f).coerceAtLeast(0f)), style = Stroke(w),
            )
        }
    }

/**
 * Marks this control's area as not the canvas: the pen hovering or touching it pauses the display's session. The
 * shadow's reach is included.
 */
fun Modifier.penShield(shields: PenShields?, key: Any, reach: Dp = Studio.ShadowCard): Modifier = if (shields == null) this else composed {
    DisposableEffect(key) { onDispose { shields.remove(key) } }
    val extra = with(LocalDensity.current) { reach.toPx() }
    onGloballyPositioned { c ->
        val b = c.boundsInWindow()
        shields.set(key, b.left, b.top, b.right + extra, b.bottom + extra)
    }
}

/** The green lamp: on, or an empty ring. */
@Composable
fun Lamp(on: Boolean, modifier: Modifier = Modifier, size: Dp = 12.dp, ring: Color = Studio.Ink) {
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        drawCircle(if (on) Studio.Lamp else Studio.Card, radius = r)
        drawCircle(ring, radius = r - 1.dp.toPx() / 2f, style = Stroke(1.5.dp.toPx()))
    }
}

/**
 * A key with a glyph, flat inside a pill or rail. Selected, it fills black with a white glyph and lights the lamp at
 * its corner; pressed, it washes grey for that instant. Nothing animates.
 */
@Composable
fun StudioKey(
    icon: ImageVector?,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    lamp: Boolean = selected,
    size: Dp = Studio.Key,
    content: (@Composable BoxScope.(ink: Color) -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val ink = when {
        !enabled -> Studio.Faint
        selected -> Studio.Card
        else -> Studio.Ink
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(Studio.RadiusM))
            .background(if (pressed && !selected) Studio.Wash else Color.Transparent)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics {
                contentDescription = label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(Modifier.matchParentSize().padding(4.dp).background(Studio.Ink, RoundedCornerShape(Studio.RadiusS)))
        }
        if (content != null) content(ink) else if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(26.dp))
        if (lamp && enabled) Lamp(true, Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 5.dp), size = 11.dp, ring = if (selected) Studio.Card else Studio.Ink)
    }
}


/** A floating pill of keys: one card, keys flat inside it, [PillRule]s between groups. */
@Composable
fun Pill(modifier: Modifier = Modifier, vertical: Boolean = false, content: @Composable () -> Unit) {
    val inner = Modifier.slab(radius = Studio.RadiusL, shadow = Studio.ShadowSlab).padding(4.dp)
    if (vertical) {
        Column(modifier.then(inner), horizontalAlignment = Alignment.CenterHorizontally) { content() }
    } else {
        Row(modifier.then(inner), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

/** A rule between groups of keys in a pill: vertical in a row, horizontal in a column. */
@Composable
fun PillRule(vertical: Boolean) {
    if (vertical) {
        Box(Modifier.padding(horizontal = 4.dp).width(Studio.Outline).height(32.dp).background(Studio.Ink))
    } else {
        Box(Modifier.padding(vertical = 4.dp).height(Studio.Outline).width(32.dp).background(Studio.Ink))
    }
}

/** How a [SlabButton] reads. */
enum class ButtonKind { Plain, Primary, Danger }

/**
 * A button printed as a slab: pressed, it sinks into its own shadow. The primary one is solid black; a danger one
 * carries a red rule under its words.
 */
@Composable
fun SlabButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Plain,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    description: String? = null,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shadow = Studio.ShadowKey
    val fill = if (kind == ButtonKind.Primary && enabled) Studio.Ink else Studio.Card
    val ink = when {
        !enabled -> Studio.Faint
        kind == ButtonKind.Primary -> Studio.Card
        else -> Studio.Ink
    }
    Box(
        modifier
            .padding(end = shadow, bottom = shadow)
            .offset { if (pressed) IntOffset(shadow.roundToPx(), shadow.roundToPx()) else IntOffset.Zero }
            .defaultMinSize(minWidth = 64.dp, minHeight = Studio.Target)
            .slab(radius = Studio.RadiusM, shadow = if (pressed || !enabled) 0.dp else shadow, fill = fill, outline = if (enabled) Studio.Ink else Studio.Faint)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text, style = StudioType.Label, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (kind == ButtonKind.Danger && enabled) Box(Modifier.padding(top = 2.dp).height(2.dp).width(24.dp).background(Studio.Alert))
            }
        }
    }
}

/** A free-standing key with a glyph, printed as a small slab. */
@Composable
fun SlabIconKey(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shadow = Studio.ShadowKey
    Box(
        modifier
            .padding(end = shadow, bottom = shadow)
            .offset { if (pressed) IntOffset(shadow.roundToPx(), shadow.roundToPx()) else IntOffset.Zero }
            .size(Studio.Target)
            .slab(radius = Studio.RadiusM, shadow = if (pressed || !enabled) 0.dp else shadow, fill = if (selected) Studio.Ink else Studio.Card, outline = if (enabled) Studio.Ink else Studio.Faint)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics {
                contentDescription = label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (!enabled) Studio.Faint else if (selected) Studio.Card else Studio.Ink, modifier = Modifier.size(24.dp))
    }
}

/**
 * A destructive key that asks twice: the first tap arms it (it turns to [confirmText] with a red rule), the second
 * does it. It disarms by itself after a few seconds.
 */
@Composable
fun ConfirmButton(text: String, confirmText: String, onConfirm: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(4000)
            armed = false
        }
    }
    SlabButton(
        if (armed) confirmText else text,
        onClick = {
            if (armed) {
                armed = false
                onConfirm()
            } else {
                armed = true
            }
        },
        modifier = modifier,
        kind = if (armed) ButtonKind.Danger else ButtonKind.Plain,
        enabled = enabled,
        icon = icon,
    )
}

/** A value you can tap to type: a small slab of tabular figures. */
@Composable
fun ValueChip(text: String, onClick: () -> Unit, description: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shadow = Studio.ShadowKey
    Box(
        modifier
            .padding(end = shadow, bottom = shadow)
            .offset { if (pressed) IntOffset(shadow.roundToPx(), shadow.roundToPx()) else IntOffset.Zero }
            .defaultMinSize(minWidth = 72.dp, minHeight = Studio.Target)
            .slab(radius = Studio.RadiusS, shadow = if (pressed || !enabled) 0.dp else shadow, outline = if (enabled) Studio.Ink else Studio.Faint)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = StudioType.Value, color = if (enabled) Studio.Ink else Studio.Faint, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/**
 * A switch as Nib prints it: an outlined track whose square knob sits right and carries the lamp when on. The whole
 * row is one target.
 */
@Composable
fun Toggle(title: String, detail: String?, on: Boolean, onToggle: (Boolean) -> Unit, onText: String, offText: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Switch) { onToggle(!on) }
            .semantics(mergeDescendants = true) { stateDescription = if (on) onText else offText }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = StudioType.Title)
            if (detail != null) Text(detail, style = StudioType.Body, color = Studio.Legend)
        }
        Canvas(Modifier.size(width = 60.dp, height = 34.dp)) {
            val w = 2.dp.toPx()
            val r = CornerRadius(5.dp.toPx())
            drawRoundRect(if (on) Studio.Ink else Studio.Card, size = size, cornerRadius = r)
            drawRoundRect(Studio.Ink, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), cornerRadius = r, style = Stroke(w))
            val k = size.height - 8.dp.toPx()
            val x = if (on) size.width - k - 4.dp.toPx() else 4.dp.toPx()
            drawRoundRect(Studio.Card, topLeft = Offset(x, 4.dp.toPx()), size = Size(k, k), cornerRadius = CornerRadius(3.dp.toPx()))
            drawRoundRect(Studio.Ink, topLeft = Offset(x + w / 2, 4.dp.toPx() + w / 2), size = Size(k - w, k - w), cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(w))
            if (on) {
                drawCircle(Studio.Lamp, radius = k * 0.22f, center = Offset(x + k / 2, 4.dp.toPx() + k / 2))
                drawCircle(Studio.Ink, radius = k * 0.22f, center = Offset(x + k / 2, 4.dp.toPx() + k / 2), style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/** A row of choices joined in one slab; the chosen one is solid black. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, descriptions: List<String>? = null) {
    Row(
        modifier.padding(end = Studio.ShadowKey, bottom = Studio.ShadowKey).slab(radius = Studio.RadiusM, shadow = Studio.ShadowKey),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, text ->
            if (i > 0) Box(Modifier.width(Studio.Outline).height(Studio.Target).background(Studio.Ink))
            val chosen = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = Studio.Target)
                    .background(if (chosen) Studio.Ink else Studio.Card)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(i) }
                    .semantics {
                        this.selected = chosen
                        descriptions?.getOrNull(i)?.let { contentDescription = it }
                    }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text, style = StudioType.Label, color = if (chosen) Studio.Card else Studio.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A small heading inside a card. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = StudioType.Label, color = Studio.Legend, modifier = modifier)
}

/** Pages instead of a long scroll: back, "2 / 5", forward. */
@Composable
fun StudioPager(page: Int, pages: Int, onPage: (Int) -> Unit, previousLabel: String, nextLabel: String, pageText: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SlabIconKey(StudioGlyphs.ChevronLeft, previousLabel, onClick = { onPage(page - 1) }, enabled = page > 0)
        Text(pageText, style = StudioType.Value, modifier = Modifier.padding(horizontal = 8.dp))
        SlabIconKey(StudioGlyphs.ChevronRight, nextLabel, onClick = { onPage(page + 1) }, enabled = page < pages - 1)
    }
}

/** A tile in a grid of choices: a slab whose name strip turns black when chosen. */
@Composable
fun ChoiceTile(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    description: String = name,
    pictureHeight: Dp = 64.dp,
    picture: @Composable BoxScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shadow = Studio.ShadowKey
    Column(
        modifier
            .padding(end = shadow, bottom = shadow)
            .offset { if (pressed) IntOffset(shadow.roundToPx(), shadow.roundToPx()) else IntOffset.Zero }
            .slab(radius = Studio.RadiusM, shadow = if (pressed) 0.dp else shadow)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
            },
    ) {
        Box(Modifier.fillMaxWidth().height(pictureHeight), contentAlignment = Alignment.Center, content = picture)
        Box(Modifier.fillMaxWidth().height(Studio.Outline).background(Studio.Ink))
        Row(
            Modifier.fillMaxWidth().background(if (selected) Studio.Ink else Studio.Card).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = StudioType.Small, color = if (selected) Studio.Card else Studio.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (badge != null) {
                Spacer(Modifier.width(4.dp))
                Text(badge, style = StudioType.Badge, color = if (selected) Studio.Card else Studio.Legend, maxLines = 1)
            }
            if (selected) {
                Spacer(Modifier.width(6.dp))
                Lamp(true, size = 10.dp, ring = Studio.Card)
            }
        }
    }
}


/** Content laid out in a card body with the usual gaps. */
@Composable
fun CardBody(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** A row of slab buttons that wraps. */
@Composable
fun ButtonRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** A destructive key inside a pill: the first tap arms it (black, with a red mark), the second does it. */
@Composable
fun ConfirmStudioKey(icon: ImageVector, label: String, confirmLabel: String, onConfirm: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(4000)
            armed = false
        }
    }
    StudioKey(
        icon = null,
        label = if (armed) confirmLabel else label,
        onClick = {
            if (armed) {
                armed = false
                onConfirm()
            } else {
                armed = true
            }
        },
        modifier = modifier,
        selected = armed,
        lamp = false,
        enabled = enabled,
    ) { ink ->
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(26.dp))
        if (armed) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp).size(width = 18.dp, height = 3.dp).background(Studio.Alert))
    }
}

/** A short message on a card, shown for a moment below the top pills. */
@Composable
fun Toast(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(end = Studio.ShadowSlab, bottom = Studio.ShadowSlab)
            .slab(radius = Studio.RadiusM, shadow = Studio.ShadowSlab)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text, style = StudioType.Label)
    }
}