package app.booxultimatum.nib.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.booxultimatum.nib.R
import app.booxultimatum.nib.brush.BrushGroup
import app.booxultimatum.nib.brush.BrushPreset
import app.booxultimatum.nib.brush.BrushTune
import app.booxultimatum.nib.brush.PressurePreset
import app.booxultimatum.nib.brush.PreviewPolicy
import app.booxultimatum.nib.brush.TuneRange
import app.booxultimatum.nib.editor.ToolMode
import app.booxultimatum.nib.editor.ToolState
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.input.PressureCurve
import app.booxultimatum.nib.ui.studio.ButtonKind
import app.booxultimatum.nib.ui.studio.CardBody
import app.booxultimatum.nib.ui.studio.ChoiceTile
import app.booxultimatum.nib.ui.studio.EnginePicture
import app.booxultimatum.nib.ui.studio.FloatingPanel
import app.booxultimatum.nib.ui.studio.Samples
import app.booxultimatum.nib.ui.studio.SectionLabel
import app.booxultimatum.nib.ui.studio.Segmented
import app.booxultimatum.nib.ui.studio.SlabButton
import app.booxultimatum.nib.ui.studio.Studio
import app.booxultimatum.nib.ui.studio.StudioGlyphs
import app.booxultimatum.nib.ui.studio.StudioKey
import app.booxultimatum.nib.ui.studio.StudioSlider
import app.booxultimatum.nib.ui.studio.StudioType
import app.booxultimatum.nib.ui.studio.Toggle
import app.booxultimatum.nib.ui.studio.Track
import app.booxultimatum.nib.ui.studio.ValueScale
import app.booxultimatum.nib.ui.studio.slab
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** A floating panel with Nib's words for its pin and close keys. */
@Composable
internal fun StudioPanel(
    id: String,
    title: String,
    ed: Editor,
    landscape: Boolean,
    width: Dp,
    pinnable: Boolean = true,
    draggable: Boolean = true,
    headerActions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    FloatingPanel(
        id, title, ed.panels, ed.shields, landscape, width,
        pinLabel = stringResource(R.string.panel_pin), unpinLabel = stringResource(R.string.panel_unpin), closeLabel = stringResource(R.string.action_close),
        modifier = Modifier.fillMaxSize(), pinnable = pinnable, draggable = draggable, headerActions = headerActions, content = content,
    )
}

/** Brushes close enough that the width carries over when switching between them. */
private fun BrushKind.isSimilar(other: BrushKind): Boolean {
    val thin = setOf(BrushKind.Fineliner, BrushKind.Fountain, BrushKind.Ballpoint, BrushKind.Pencil, BrushKind.Graphite, BrushKind.Dash, BrushKind.SquarePen)
    return (this in thin) == (other in thin)
}

/** The pen [preset] would become with [kind] chosen: the width kept when the brushes are alike, the tuning reset. */
internal fun BrushPreset.switchedTo(kind: BrushKind): BrushPreset {
    if (kind == this.kind) return this
    val w = if (this.kind.isSimilar(kind)) width.coerceIn(BrushSpec.widthRange(kind)) else BrushSpec.defaults(kind).width
    return copy(kind = kind, width = w, tune = BrushTune.EMPTY)
}

internal fun groupGlyph(g: BrushGroup): ImageVector = when (g) {
    BrushGroup.Pens -> StudioGlyphs.PenNib
    BrushGroup.Pencils -> StudioGlyphs.Pencil
    BrushGroup.Markers -> StudioGlyphs.Marker
    BrushGroup.Brushes -> StudioGlyphs.Brushes
    BrushGroup.Textured -> StudioGlyphs.Charcoal
}

/** The badge a brush wears: the display style it previews in, marked ≈ when a verified style stands in for its own. */
@Composable
private fun previewBadge(spec: BrushSpec, tryUnverified: Boolean): String {
    val standIn = PreviewPolicy.standIn(spec, tryUnverified)
    val name = stringResource(Names.style(standIn ?: spec.preview.style)).uppercase()
    return if (standIn != null) "≈ $name" else name
}

/**
 * The Brushes panel: the families down its side, each drawn as its own tool, and the chosen family's brushes as
 * tiles, every one showing a sample stroke the engine draws at the pen's width and colour.
 */
@Composable
internal fun BrushesPanel(ed: Editor, landscape: Boolean) {
    val tools = ed.tools
    val preset = tools.current
    var groupKey by rememberSaveable { mutableStateOf(BrushGroup.of(preset.kind)?.key ?: BrushGroup.Pens.key) }
    val group = BrushGroup.entries.firstOrNull { it.key == groupKey } ?: BrushGroup.Pens
    StudioPanel(PanelId.BRUSHES, stringResource(R.string.brushes_title_slot, tools.selected + 1), ed, landscape, width = 580.dp) {
        Row(Modifier.padding(start = 12.dp, top = 12.dp, end = 16.dp, bottom = 12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (g in BrushGroup.entries) {
                    StudioKey(groupGlyph(g), stringResource(Names.group(g)), onClick = { groupKey = g.key }, selected = g == group, lamp = preset.kind in g.kinds)
                }
            }
            Box(Modifier.padding(horizontal = 12.dp).width(Studio.Outline).height(56.dp * 5).background(Studio.Ink))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Names.group(group)), style = StudioType.Title)
                for (row in group.kinds.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (kind in row) {
                            val candidate = preset.switchedTo(kind)
                            val spec = candidate.spec()
                            val name = stringResource(Names.brush(kind))
                            ChoiceTile(
                                name, selected = kind == preset.kind,
                                onClick = {
                                    tools.mode = ToolMode.Pen
                                    tools.setSlot(tools.selected, candidate)
                                },
                                badge = previewBadge(spec, ed.settings.tryUnverifiedStyles),
                                description = stringResource(R.string.brush_tile, name, ValueScale.formatNumber(candidate.width)),
                                pictureHeight = 68.dp,
                                modifier = Modifier.weight(1f),
                            ) {
                                EnginePicture(Triple("tile", spec, candidate.color), Modifier.fillMaxSize().padding(4.dp)) { c, sink, w, h ->
                                    Samples.draw(Samples.sCurve(spec, candidate.color, w.toFloat(), h.toFloat()), c, sink)
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(Studio.Outline).background(Studio.Ink))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(R.string.brush_summary, stringResource(Names.brush(preset.kind)), ValueScale.formatNumber(preset.width)), style = StudioType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.brushes_badge_note), style = StudioType.Small, color = Studio.Legend)
            }
            SlabButton(stringResource(R.string.brush_settings), onClick = { ed.panels.show(PanelId.BRUSH) }, icon = StudioGlyphs.Settings)
        }
    }
}

/**
 * Brush settings: a live sample on top, then every property of the pen as a slider with − and +, snapped steps and a
 * value to type, in three pages (size and ink, pressure, feel). Moving a control redraws only the sample; the pen
 * takes the value when the control is let go.
 */
@Composable
internal fun BrushSettingsPanel(ed: Editor, landscape: Boolean, onMatchPreview: () -> Unit) {
    val tools = ed.tools
    val preset = tools.current
    var working by remember(tools.selected, preset) { mutableStateOf<BrushPreset?>(null) }
    val shown = working ?: preset
    val spec = shown.spec()
    var page by rememberSaveable { mutableIntStateOf(0) }
    fun commit(p: BrushPreset) {
        working = null
        tools.setSlot(tools.selected, p)
    }
    fun live(p: BrushPreset?) {
        working = p
    }
    val title = stringResource(R.string.pen_title, tools.selected + 1, stringResource(Names.brush(preset.kind)))
    StudioPanel(
        PanelId.BRUSH, title, ed, landscape, width = 540.dp,
        headerActions = {
            StudioKey(StudioGlyphs.Recover, stringResource(R.string.brush_reset), onClick = { commit(BrushPreset(preset.kind, BrushSpec.defaults(preset.kind).width, PressurePreset.Medium, preset.color)) }, size = Studio.Target)
        },
    ) {
        Box(Modifier.fillMaxWidth().height(if (landscape) 84.dp else 104.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
            EnginePicture(Triple("live", spec, shown.color), Modifier.fillMaxSize()) { c, sink, w, h ->
                Samples.draw(Samples.sCurve(spec, shown.color, w.toFloat(), h.toFloat(), maxWidth = h * 0.45f), c, sink)
            }
        }
        Segmented(
            listOf(stringResource(R.string.brush_page_size), stringResource(R.string.brush_page_pressure), stringResource(R.string.brush_page_feel)),
            page, onSelect = { page = it }, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        )
        CardBody {
            when (page) {
                0 -> SizePage(ed, shown, spec, ::live, ::commit, onMatchPreview)
                1 -> PressurePage(ed, shown, spec, ::live, ::commit)
                else -> FeelPage(ed, shown, spec, ::live, ::commit)
            }
        }
    }
}

/** A slider bound to one property of the pen, with Nib's key labels. */
@Composable
private fun PropertySlider(
    ed: Editor,
    label: String,
    value: Float,
    scale: ValueScale,
    unit: String,
    set: (Float) -> BrushPreset,
    live: (BrushPreset?) -> Unit,
    commit: (BrushPreset) -> Unit,
    enabled: Boolean = true,
    track: Track = Track.Fill,
    ticks: List<Float> = emptyList(),
    trailing: (@Composable () -> Unit)? = null,
) {
    StudioSlider(
        label, value, scale, unit,
        onCommit = { commit(set(it)) },
        onType = { ed.typeValue(label, scale, value, unit) { commit(set(it)) } },
        onChange = { v -> live(v?.let(set)) },
        enabled = enabled, track = track, ticks = ticks,
        minusLabel = stringResource(R.string.slider_less, label), plusLabel = stringResource(R.string.slider_more, label),
        typeLabel = stringResource(R.string.slider_type_value, label, scale.format(value) + if (unit.isEmpty()) "" else " $unit"),
        trailing = trailing,
    )
}

private val WIDTH_TICKS = listOf(0.5f, 1f, 2f, 5f, 10f, 20f, 50f, 100f, 200f, 400f)

@Composable
private fun SizePage(ed: Editor, shown: BrushPreset, spec: BrushSpec, live: (BrushPreset?) -> Unit, commit: (BrushPreset) -> Unit, onMatchPreview: () -> Unit) {
    val range = BrushSpec.widthRange(shown.kind)
    val widthScale = remember(shown.kind) { ValueScale.width(range) }
    val zoom = ed.view.value?.viewport?.scale ?: 1f
    val px = ed.string(R.string.unit_px)
    PropertySlider(
        ed, stringResource(R.string.prop_width), shown.width, widthScale, px, set = { shown.copy(width = it) }, live = live, commit = commit,
        ticks = WIDTH_TICKS.filter { it in range },
        trailing = { NibDot(spec.inkAt(zoom).width * zoom) },
    )
    Text(stringResource(R.string.prop_width_dot, zoomLabel(zoom)), style = StudioType.Small, color = Studio.Legend)
    PropertySlider(
        ed, stringResource(R.string.prop_opacity), spec.opacity, remember { ValueScale.percent(TuneRange.OPACITY.min, 1f) }, "%",
        set = { shown.copy(tune = shown.tune.copy(opacity = it)) }, live = live, commit = commit, track = Track.Checker(shown.color),
    )
    Box(Modifier.fillMaxWidth().height(Studio.Hairline).background(Studio.Faint))
    val settings = ed.settings
    val style = PreviewPolicy.standIn(spec, settings.tryUnverifiedStyles) ?: spec.preview.style
    val sent = PreviewPolicy.preview(spec, shown.color, zoom, settings.tryUnverifiedStyles, settings.previewMatch, settings.markerPreview)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(R.string.preview_while_drawing, stringResource(Names.style(style))), style = StudioType.Label)
            Text(
                stringResource(
                    R.string.preview_size_readout, ValueScale.formatNumber(sent.widthPx), ValueScale.formatNumber(settings.previewMatch.factor(sent.style)),
                    ValueScale.formatNumber(settings.previewMatch.typicalPressure),
                ),
                style = StudioType.Small, color = Studio.Legend,
            )
        }
        SlabButton(stringResource(R.string.preview_match_link), onClick = onMatchPreview, icon = StudioGlyphs.Diagnostics)
    }
}

/** A dot as wide as the pen draws on screen at this zoom, capped to its box. */
@Composable
private fun NibDot(screenPx: Float) {
    Canvas(Modifier.size(Studio.Target)) {
        val r = min(screenPx, size.minDimension - 8.dp.toPx()) / 2f
        drawRect(Studio.Card)
        drawRect(Studio.Faint, style = Stroke(Studio.Hairline.toPx()))
        drawCircle(Color.Black, radius = r.coerceAtLeast(0.5f))
    }
}

@Composable
private fun PressurePage(ed: Editor, shown: BrushPreset, spec: BrushSpec, live: (BrushPreset?) -> Unit, commit: (BrushPreset) -> Unit) {
    val curve = spec.curve
    if (curve.isConstant) {
        Text(stringResource(R.string.pen_pressure_none), style = StudioType.Body, color = Studio.Legend)
        SlabButton(stringResource(R.string.pressure_enable), onClick = { commit(shown.copy(tune = shown.tune.copy(floor = 0.3f, ceiling = curve.ceiling.coerceAtLeast(0.31f)))) })
        return
    }
    SectionLabel(stringResource(R.string.pen_pressure))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in PressurePreset.entries) {
            val candidate = shown.withPressure(p)
            val cspec = candidate.spec()
            ChoiceTile(
                stringResource(Names.pressure(p)), selected = shown.pressure == p && shown.tune.exponent == null,
                onClick = { commit(candidate) }, pictureHeight = 72.dp, modifier = Modifier.weight(1f),
                description = stringResource(R.string.pressure_tile, stringResource(Names.pressure(p))),
            ) {
                Row(Modifier.fillMaxSize().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CurvePlot(cspec.curve, Modifier.size(56.dp))
                    Spacer(Modifier.width(4.dp))
                    EnginePicture(Triple("pressure", cspec, candidate.color), Modifier.weight(1f).height(56.dp)) { c, sink, w, h ->
                        Samples.draw(Samples.sCurve(cspec, candidate.color, w.toFloat(), h.toFloat(), maxWidth = h * 0.4f), c, sink)
                    }
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CurvePlot(curve, Modifier.size(104.dp), marks = true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            PropertySlider(
                ed, stringResource(R.string.prop_sensitivity), curve.exponent, remember { ValueScale.factor(TuneRange.EXPONENT.min, TuneRange.EXPONENT.max, 0.05f) }, "",
                set = { shown.copy(tune = shown.tune.copy(exponent = it)) }, live = live, commit = commit, ticks = listOf(1f),
            )
        }
    }
    Text(stringResource(R.string.prop_sensitivity_detail), style = StudioType.Small, color = Studio.Legend)
    PropertySlider(
        ed, stringResource(R.string.prop_floor), curve.floor, remember { ValueScale.percent(0f, 1f) }, "%",
        set = { v -> shown.copy(tune = shown.tune.copy(floor = min(v, curve.ceiling))) }, live = live, commit = commit,
    )
    PropertySlider(
        ed, stringResource(R.string.prop_ceiling), curve.ceiling, remember { ValueScale.percent(TuneRange.CEILING.min, TuneRange.CEILING.max) }, "%",
        set = { v -> shown.copy(tune = shown.tune.copy(ceiling = v.coerceAtLeast(curve.floor))) }, live = live, commit = commit, ticks = listOf(1f),
    )
}

/** The pressure curve: width against pressure, from none to full; [marks] adds the floor and ceiling as dashes. */
@Composable
private fun CurvePlot(curve: PressureCurve, modifier: Modifier = Modifier, marks: Boolean = false) {
    Canvas(modifier.semantics { contentDescription = "" }) {
        val pad = 4.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        val top = maxOf(1f, curve.maxFactor)
        drawRect(Studio.Wash, topLeft = Offset(pad, pad), size = Size(w, h))
        drawLine(Studio.Faint, Offset(pad, pad + h), Offset(pad + w, pad + h - h / top), strokeWidth = 1.dp.toPx())
        if (marks) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
            for (f in listOf(curve.floor, curve.ceiling)) {
                val y = pad + h - h * f / top
                drawLine(Studio.Legend, Offset(pad, y), Offset(pad + w, y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            }
        }
        val path = Path()
        val n = 32
        for (i in 0..n) {
            val p = i / n.toFloat()
            val x = pad + p * w
            val y = pad + h - h * curve.factor(p) / top
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Color.Black, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        drawRect(Color.Black, topLeft = Offset(pad, pad), size = Size(w, h), style = Stroke(Studio.Outline.toPx()))
    }
}

@Composable
private fun FeelPage(ed: Editor, shown: BrushPreset, spec: BrushSpec, live: (BrushPreset?) -> Unit, commit: (BrushPreset) -> Unit) {
    val percent = remember { ValueScale.percent(0f, 1f) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            PropertySlider(
                ed, stringResource(R.string.prop_smoothing), spec.smoothing, percent, "%",
                set = { shown.copy(tune = shown.tune.copy(smoothing = it)) }, live = live, commit = commit,
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(width = 132.dp, height = 72.dp).slab(radius = Studio.RadiusS, shadow = 0.dp, outlineWidth = Studio.Hairline)) {
            val raw = BrushSpec.defaults(BrushKind.Fineliner).copy(width = 1.2f, smoothing = 0f)
            EnginePicture(Triple("wobble", spec.smoothing, shown.color), Modifier.fillMaxSize().padding(4.dp)) { c, sink, w, h ->
                val input = Samples.wobble(w.toFloat(), h.toFloat())
                Samples.draw(Samples.through(raw, 0xFF9A9A9A.toInt(), input), c, sink)
                Samples.draw(Samples.through(BrushSpec.defaults(BrushKind.Fineliner).copy(width = 3f, smoothing = spec.smoothing), 0xFF000000.toInt(), input), c, sink)
            }
        }
    }
    Text(stringResource(R.string.prop_smoothing_detail), style = StudioType.Small, color = Studio.Legend)
    if (shown.kind.rendersAsDabs) {
        SectionLabel(stringResource(R.string.prop_texture))
        PropertySlider(ed, stringResource(R.string.prop_grain), spec.grain, percent, "%", set = { shown.copy(tune = shown.tune.copy(grain = it)) }, live = live, commit = commit)
        PropertySlider(
            ed, stringResource(R.string.prop_spacing), spec.spacing, remember { ValueScale.percent(TuneRange.SPACING.min, TuneRange.SPACING.max) }, "%",
            set = { shown.copy(tune = shown.tune.copy(spacing = it)) }, live = live, commit = commit,
        )
        PropertySlider(
            ed, stringResource(R.string.prop_flow), spec.flow, remember { ValueScale.percent(TuneRange.FLOW.min, TuneRange.FLOW.max) }, "%",
            set = { shown.copy(tune = shown.tune.copy(flow = it)) }, live = live, commit = commit,
        )
        PropertySlider(ed, stringResource(R.string.prop_scatter), spec.jitter, percent, "%", set = { shown.copy(tune = shown.tune.copy(jitter = it)) }, live = live, commit = commit)
    }
    if (shown.kind.usesNib) {
        SectionLabel(stringResource(R.string.prop_nib))
        val degrees = Math.toDegrees(spec.nibAngle.toDouble()).toFloat().let { ((it % 180f) + 180f) % 180f }
        val angleScale = remember { ValueScale.linear(0f, 180f, 1f, decimals = 0) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            NibDial(degrees, enabled = !spec.nibFromOrientation, onCommit = { d -> commit(shown.copy(tune = shown.tune.copy(nibAngle = Math.toRadians(d.toDouble()).toFloat()))) })
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                PropertySlider(
                    ed, stringResource(R.string.prop_nib_angle), degrees, angleScale, "°",
                    set = { shown.copy(tune = shown.tune.copy(nibAngle = Math.toRadians(it.toDouble()).toFloat())) }, live = live, commit = commit,
                    enabled = !spec.nibFromOrientation, ticks = listOf(45f, 90f, 135f),
                )
            }
        }
        Toggle(
            stringResource(R.string.prop_nib_follow), stringResource(R.string.prop_nib_follow_detail), spec.nibFromOrientation,
            onToggle = { commit(shown.copy(tune = shown.tune.copy(nibFromOrientation = it))) },
            onText = stringResource(R.string.toggle_on), offText = stringResource(R.string.toggle_off),
        )
    }
    if (BrushSpec.defaults(shown.kind).speedInfluence > 0f) {
        SectionLabel(stringResource(R.string.prop_speed_section))
        PropertySlider(ed, stringResource(R.string.prop_speed), spec.speedInfluence, percent, "%", set = { shown.copy(tune = shown.tune.copy(speedInfluence = it)) }, live = live, commit = commit)
        PropertySlider(
            ed, stringResource(R.string.prop_taper), spec.taper, remember { ValueScale.linear(TuneRange.TAPER.min, TuneRange.TAPER.max, 0.1f, decimals = 1) }, "×",
            set = { shown.copy(tune = shown.tune.copy(taper = it)) }, live = live, commit = commit,
        )
    }
}

/**
 * The nib's angle as a dial: a flat nib drawn through the centre at the angle it writes with, which the pen or a
 * finger turns. Takes the angle when let go.
 */
@Composable
private fun NibDial(degrees: Float, enabled: Boolean, onCommit: (Float) -> Unit) {
    var working by remember { mutableStateOf<Float?>(null) }
    val shown = working ?: degrees
    val commit by rememberUpdatedState(onCommit)
    val label = stringResource(R.string.prop_nib_angle)
    Canvas(
        Modifier
            .size(96.dp)
            .semantics {
                contentDescription = label
                stateDescription = "${shown.roundToInt()}°"
                progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..180f)
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                fun at(o: Offset): Float {
                    val a = Math.toDegrees(atan2(-(o.y - size.height / 2f), o.x - size.width / 2f).toDouble()).toFloat()
                    return (((a % 180f) + 180f) % 180f).roundToInt().toFloat()
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var v = at(down.position)
                    working = v
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        v = at(c.position)
                        working = v
                    }
                    working = null
                    commit(v)
                }
            },
    ) {
        val r = size.minDimension / 2f - 6.dp.toPx()
        val c = Offset(size.width / 2f, size.height / 2f)
        val ink = if (enabled) Color.Black else Studio.Faint
        drawCircle(Studio.Card, radius = r, center = c)
        for (t in 0 until 12) {
            val a = t * PI.toFloat() / 6f
            val inner = if (t % 3 == 0) r - 9.dp.toPx() else r - 5.dp.toPx()
            drawLine(Studio.Legend, Offset(c.x + cos(a) * inner, c.y - sin(a) * inner), Offset(c.x + cos(a) * r, c.y - sin(a) * r), strokeWidth = 1.5.dp.toPx())
        }
        drawCircle(ink, radius = r, center = c, style = Stroke(Studio.Outline.toPx()))
        rotate(-shown, c) {
            val len = r * 0.78f
            val thick = 9.dp.toPx()
            val sh = 3.dp.toPx()
            drawRect(Color.Black, topLeft = Offset(c.x - len + sh, c.y - thick / 2 + sh), size = Size(2 * len, thick))
            drawRect(Studio.Card, topLeft = Offset(c.x - len, c.y - thick / 2), size = Size(2 * len, thick))
            drawRect(ink, topLeft = Offset(c.x - len, c.y - thick / 2), size = Size(2 * len, thick), style = Stroke(Studio.Outline.toPx()))
            drawRect(ink, topLeft = Offset(c.x + len - thick, c.y - thick / 2), size = Size(thick, thick))
        }
        drawCircle(ink, radius = 3.dp.toPx(), center = c)
    }
}

/** The eraser panel: pixel, stroke or lasso, each drawn as what it does, and the width for the first two. */
@Composable
internal fun EraserPanel(ed: Editor, landscape: Boolean) {
    val tools = ed.tools
    StudioPanel(PanelId.ERASER, stringResource(R.string.eraser_title), ed, landscape, width = 480.dp) {
        CardBody {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in ToolState.ERASERS) {
                    val glyph = when (k) {
                        BrushKind.PixelEraser -> StudioGlyphs.Eraser
                        BrushKind.StrokeEraser -> StudioGlyphs.StrokeErase
                        else -> StudioGlyphs.Lasso
                    }
                    ChoiceTile(
                        stringResource(Names.brush(k)), selected = tools.eraser == k,
                        onClick = {
                            tools.chooseEraser(k)
                            tools.mode = ToolMode.Eraser
                        },
                        modifier = Modifier.weight(1f), pictureHeight = 64.dp,
                    ) {
                        Icon(glyph, contentDescription = null, tint = Color.Black, modifier = Modifier.size(36.dp))
                    }
                }
            }
            Text(
                stringResource(
                    when (tools.eraser) {
                        BrushKind.PixelEraser -> R.string.eraser_pixel_detail
                        BrushKind.StrokeEraser -> R.string.eraser_stroke_detail
                        else -> R.string.eraser_lasso_detail
                    },
                ),
                style = StudioType.Body,
            )
            if (tools.eraser != BrushKind.LassoEraser) {
                val k = tools.eraser
                val scale = remember(k) { ValueScale.width(BrushSpec.widthRange(k)) }
                val label = stringResource(R.string.prop_width)
                val w = tools.eraserWidth(k)
                val px = ed.string(R.string.unit_px)
                StudioSlider(
                    label, w, scale, px,
                    onCommit = { tools.setEraserWidth(k, it) },
                    onType = { ed.typeValue(label, scale, w, px) { tools.setEraserWidth(k, it) } },
                    minusLabel = stringResource(R.string.slider_less, label), plusLabel = stringResource(R.string.slider_more, label),
                    typeLabel = stringResource(R.string.slider_type_value, label, scale.format(w) + " " + px),
                    ticks = WIDTH_TICKS.filter { it in BrushSpec.widthRange(k) },
                )
            }
            Text(stringResource(R.string.eraser_pen_note), style = StudioType.Small, color = Studio.Legend)
        }
    }
}

/** A plain slab button that fills its row, for lists in panels. */
@Composable
internal fun WideButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, kind: ButtonKind = ButtonKind.Plain, enabled: Boolean = true) {
    SlabButton(text, onClick = onClick, modifier = modifier.fillMaxWidth(), icon = icon, kind = kind, enabled = enabled)
}

