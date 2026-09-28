package app.booxultimatum.nib.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.kit.ui.StatusStrip
import app.booxultimatum.nib.NibSettings
import app.booxultimatum.nib.R
import app.booxultimatum.nib.brush.BrushPreset
import app.booxultimatum.nib.brush.Palette
import app.booxultimatum.nib.editor.CanvasView
import app.booxultimatum.nib.editor.EditorSession
import app.booxultimatum.nib.editor.OpenSessions
import app.booxultimatum.nib.editor.Selection
import app.booxultimatum.nib.editor.ToolMode
import app.booxultimatum.nib.editor.ToolState
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.geom.Viewport
import app.booxultimatum.nib.pen.PenShields
import app.booxultimatum.nib.ui.studio.EntryBar
import app.booxultimatum.nib.ui.studio.EntryRequest
import app.booxultimatum.nib.ui.studio.EnginePicture
import app.booxultimatum.nib.ui.studio.ConfirmStudioKey
import app.booxultimatum.nib.ui.studio.PanelMemory
import app.booxultimatum.nib.ui.studio.PanelState
import app.booxultimatum.nib.ui.studio.Pill
import app.booxultimatum.nib.ui.studio.PillRule
import app.booxultimatum.nib.ui.studio.QuickSlider
import app.booxultimatum.nib.ui.studio.Samples
import app.booxultimatum.nib.ui.studio.SlabButton
import app.booxultimatum.nib.ui.studio.SlabIconKey
import app.booxultimatum.nib.ui.studio.Studio
import app.booxultimatum.nib.ui.studio.StudioGlyphs
import app.booxultimatum.nib.ui.studio.StudioKey
import app.booxultimatum.nib.ui.studio.StudioType
import app.booxultimatum.nib.ui.studio.Toast
import app.booxultimatum.nib.ui.studio.Track
import app.booxultimatum.nib.ui.studio.ValueScale
import app.booxultimatum.nib.ui.studio.panelAnchor
import app.booxultimatum.nib.ui.studio.panelOrigin
import app.booxultimatum.nib.ui.studio.penShield
import app.booxultimatum.nib.ui.studio.slab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlin.math.ln
import kotlin.math.min

/** The floating panels' ids: also their keys for position and pin in the preferences. */
internal object PanelId {
    const val BRUSHES = "brushes"
    const val BRUSH = "brush"
    const val COLOUR = "colour"
    const val LAYERS = "layers"
    const val ERASER = "eraser"
    const val MENU = "menu"
    const val EXPORT = "export"
    const val PAPER = "paper"
    const val VIEW = "view"
    const val MOVE = "move"
}

/**
 * Everything the editor's floating controls share: the drawing, the tools, the panels and where they are, the canvas,
 * and the two ways to talk to the owner (a message, or the entry bar at the top).
 */
@Stable
internal class Editor(
    val context: Context,
    val session: EditorSession,
    val tools: ToolState,
    val settings: NibSettings,
    val panels: PanelState,
    val shields: PenShields,
    val view: State<CanvasView?>,
    val say: (String) -> Unit,
    val type: (EntryRequest) -> Unit,
    val scope: CoroutineScope,
) {
    val log = Logbook.logger("nib.ui")

    fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    /** Asks for an exact value in the entry bar, checks and clamps it, and says so when it was clamped. */
    fun typeValue(title: String, scale: ValueScale, value: Float, unit: String, onValue: (Float) -> Unit) {
        val hint = string(R.string.entry_range, scale.format(scale.min), scale.format(scale.max), unit).trim()
        type(
            EntryRequest(title, scale.format(value), numeric = true, hint = hint, suffix = unit.ifBlank { null }) { text ->
                when (val r = scale.parse(text)) {
                    ValueScale.Parsed.Invalid -> string(R.string.entry_not_a_number)
                    is ValueScale.Parsed.Ok -> {
                        onValue(r.value)
                        if (r.clamped) say(string(R.string.entry_clamped, scale.format(r.value), unit).trim())
                        null
                    }
                }
            },
        )
    }
}

/** The editor: the canvas full-bleed on the desk, and Nib's tools floating over it. */
@Composable
fun EditorScreen(
    drawingId: String,
    sessions: OpenSessions,
    onBack: () -> Unit,
    onDiagnostics: (matchPreview: Boolean) -> Unit,
    onAbout: () -> Unit,
    onSettings: () -> Unit,
) {
    var failed by remember { mutableStateOf(false) }
    val session by produceState(sessions[drawingId], drawingId) {
        val s = sessions.load(drawingId)
        if (s == null) failed = true
        value = s
    }
    Column(Modifier.fillMaxSize().background(Studio.Desk)) {
        StatusStrip()
        val s = session
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(Studio.Edge)) {
                Pill { StudioKey(StudioGlyphs.Library, stringResource(R.string.action_library), onClick = onBack) }
                Toast(stringResource(if (failed) R.string.editor_unreadable else R.string.editor_opening), Modifier.align(Alignment.Center).widthIn(max = 560.dp))
            }
        } else {
            EditorBody(s, onBack, onDiagnostics, onAbout, onSettings)
        }
    }
}

@Composable
private fun EditorBody(
    session: EditorSession,
    onBack: () -> Unit,
    onDiagnostics: (Boolean) -> Unit,
    onAbout: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val tools = remember { ToolState.get(context) }
    val settings = remember { NibSettings.get(context) }
    val panels = remember { PanelState(PanelMemory(context.getSharedPreferences("nib.panels", Context.MODE_PRIVATE))) }
    val shields = remember { PenShields() }
    val view = remember { mutableStateOf<CanvasView?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var viewport by remember { mutableStateOf(session.lastViewport) }
    var selection by remember { mutableStateOf<Selection?>(null) }
    var entry by remember { mutableStateOf<EntryRequest?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val ed = remember(session) { Editor(context, session, tools, settings, panels, shields, view, say = { message = it }, type = { entry = it }, scope = scope) }

    DisposableEffect(lifecycle, session) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) session.saveNow("stop") }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(2600)
            message = null
        }
    }
    // An unpinned panel, the menu or the entry bar is closed by the pen's next touch, so the display doesn't preview.
    val blocking = panels.anyUnpinnedOpen || entry != null
    LaunchedEffect(blocking, view.value) { view.value?.setPanelOpen(blocking) }
    CanvasSync(ed)

    BoxWithConstraints(Modifier.fillMaxSize().panelOrigin(panels)) {
        val landscape = maxWidth > maxHeight
        val stacked = maxHeight - CHROME_DP.dp * 2 >= RAIL_STACKED_DP.dp
        val sideInset = if (stacked) CHROME_DP else CHROME_DP + 72f
        // The canvas stays the first child in every orientation, so rotating never recreates it.
        AndroidView(
            factory = { c -> CanvasView(c).apply { id = R.id.nib_canvas } },
            update = { v ->
                view.value = v
                v.session = session
                v.shields = shields
                with(density) { v.fitInsets.set(sideInset.dp.toPx(), CHROME_DP.dp.toPx(), sideInset.dp.toPx(), CHROME_DP.dp.toPx()) }
                v.onMessage = { res -> message = context.getString(res) }
                v.onViewportChanged = { vp -> viewport = vp }
                v.onSelectionChanged = { s -> selection = s }
                v.onColourPicked = { c ->
                    tools.useColour(c)
                    tools.mode = ToolMode.Pen
                    message = context.getString(R.string.message_colour_picked, Palette.toHex(c))
                }
                v.onCanvasTouch = { stylus ->
                    val hadEntry = entry != null
                    entry = null
                    val closed = panels.closeUnpinned()
                    (closed || hadEntry) && stylus
                }
            },
            modifier = Modifier.fillMaxSize().clipToBounds(),
        )
        if (!fullscreen) {
            Pill(Modifier.align(Alignment.TopStart).padding(Studio.Edge).penShield(shields, "left pill")) {
                StudioKey(StudioGlyphs.Library, stringResource(R.string.action_library), onClick = onBack)
                PillRule(vertical = true)
                UndoRedo(ed)
            }
            TopRightPill(ed, Modifier.align(Alignment.TopEnd).padding(Studio.Edge).penShield(shields, "right pill"))
            Box(Modifier.fillMaxSize().padding(top = CHROME_DP.dp, bottom = CHROME_DP.dp)) {
                ToolRail(ed, stacked, Modifier.align(if (settings.leftHanded) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = Studio.Edge))
            }
            Column(Modifier.align(Alignment.BottomCenter).padding(Studio.Edge), horizontalAlignment = Alignment.CenterHorizontally) {
                selection?.let {
                    SelectionBar(ed, it)
                    Spacer(Modifier.height(Studio.S3))
                }
                ViewChip(ed, viewport, onFullscreen = {
                    fullscreen = true
                    panels.closeUnpinned()
                })
            }
        } else {
            SlabIconKey(
                StudioGlyphs.ExitFullscreen, stringResource(R.string.action_exit_fullscreen), onClick = { fullscreen = false },
                modifier = Modifier.align(Alignment.TopEnd).padding(Studio.Edge).penShield(shields, "puck"),
            )
        }
        for (id in panels.openIds) key(id) {
            PanelFor(id, ed, landscape, onBack, onDiagnostics, onAbout, onSettings, onFullscreen = {
                fullscreen = true
                panels.closeUnpinned()
            })
        }
        message?.let { Toast(it, Modifier.align(Alignment.TopCenter).padding(top = CHROME_DP.dp).widthIn(max = 560.dp).penShield(shields, "toast")) }
        entry?.let { r ->
            EntryBar(
                r, stringResource(R.string.action_set), stringResource(R.string.action_cancel), onDone = { entry = null }, shields = shields,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

// The floating controls keep this much of each edge; the rail and its sliders stack when there's room for both.
private const val CHROME_DP = 84f
private const val RAIL_STACKED_DP = 1000f

/** Keeps the canvas's preview stroke and blocks in step with the tools; recomposes alone, not the whole editor. */
@Composable
private fun CanvasSync(ed: Editor) {
    val t = ed.tools
    val s = ed.settings
    LaunchedEffect(t.current, t.mode, t.eraser, s.tryUnverifiedStyles, s.markerPreview, s.swapDelayMs, ed.session.activeLayerId, ed.session.revision, ed.view.value) {
        ed.view.value?.onToolsChanged()
    }
}

@Composable
private fun UndoRedo(ed: Editor) {
    val s = ed.session
    s.revision
    StudioKey(StudioGlyphs.Undo, stringResource(R.string.action_undo), onClick = { if (!s.undo()) ed.say(ed.string(R.string.message_nothing_to_undo)) }, enabled = s.history.canUndo)
    StudioKey(StudioGlyphs.Redo, stringResource(R.string.action_redo), onClick = { if (!s.redo()) ed.say(ed.string(R.string.message_nothing_to_redo)) }, enabled = s.history.canRedo)
}

@Composable
private fun TopRightPill(ed: Editor, modifier: Modifier) {
    val p = ed.panels
    Pill(modifier) {
        StudioKey(StudioGlyphs.Brushes, stringResource(R.string.brushes_title), onClick = { p.toggle(PanelId.BRUSHES) }, selected = p.isOpen(PanelId.BRUSHES), modifier = Modifier.panelAnchor(p, PanelId.BRUSHES))
        ColourWell(ed.tools.current.color, selected = p.isOpen(PanelId.COLOUR), onClick = { p.toggle(PanelId.COLOUR) }, modifier = Modifier.panelAnchor(p, PanelId.COLOUR))
        StudioKey(StudioGlyphs.Layers, stringResource(R.string.layers_title), onClick = { p.toggle(PanelId.LAYERS) }, selected = p.isOpen(PanelId.LAYERS), modifier = Modifier.panelAnchor(p, PanelId.LAYERS))
        PillRule(vertical = true)
        StudioKey(
            StudioGlyphs.Menu, stringResource(R.string.action_more), onClick = { p.toggle(PanelId.MENU) }, selected = p.isOpen(PanelId.MENU),
            modifier = Modifier.panelAnchor(p, PanelId.MENU).panelAnchor(p, PanelId.EXPORT).panelAnchor(p, PanelId.PAPER),
        )
    }
}

/** The colour well: the chosen pen's ink in a ringed disc. */
@Composable
fun ColourWell(argb: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.colour_title_with, Palette.toHex(argb))
    StudioKey(null, label, onClick = onClick, selected = selected, modifier = modifier) { ink ->
        Canvas(Modifier.size(30.dp)) {
            val r = size.minDimension / 2f
            drawCircle(Color(argb or Palette.OPAQUE), radius = r - 2.dp.toPx())
            drawCircle(ink, radius = r - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
            drawCircle(if (selected) Color.Black else Color.White, radius = r - 3.5.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        }
    }
}

@Composable
private fun ToolRail(ed: Editor, stacked: Boolean, modifier: Modifier) {
    val right = ed.settings.leftHanded
    if (stacked) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Studio.S3)) {
            RailTools(ed)
            RailSliders(ed)
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Studio.S3)) {
            if (right) RailSliders(ed)
            RailTools(ed)
            if (!right) RailSliders(ed)
        }
    }
}

@Composable
private fun RailTools(ed: Editor) {
    val tools = ed.tools
    val p = ed.panels
    Pill(Modifier.penShield(ed.shields, "rail"), vertical = true) {
        tools.slots.forEachIndexed { i, preset ->
            val chosen = tools.mode == ToolMode.Pen && tools.selected == i
            PenSlotKey(
                preset, i, chosen,
                onClick = {
                    if (chosen) {
                        p.toggle(PanelId.BRUSH)
                    } else {
                        tools.mode = ToolMode.Pen
                        tools.selected = i
                    }
                },
                modifier = if (tools.selected == i) Modifier.panelAnchor(p, PanelId.BRUSH) else Modifier,
            )
        }
        PillRule(vertical = false)
        StudioKey(
            StudioGlyphs.Eraser, stringResource(R.string.tool_eraser_with, stringResource(Names.brush(tools.eraser))),
            onClick = { if (tools.mode == ToolMode.Eraser) p.toggle(PanelId.ERASER) else tools.mode = ToolMode.Eraser },
            selected = tools.mode == ToolMode.Eraser, modifier = Modifier.panelAnchor(p, PanelId.ERASER),
        )
        StudioKey(StudioGlyphs.Lasso, stringResource(R.string.tool_lasso), onClick = { tools.mode = if (tools.mode == ToolMode.Lasso) ToolMode.Pen else ToolMode.Lasso }, selected = tools.mode == ToolMode.Lasso)
        StudioKey(StudioGlyphs.Eyedropper, stringResource(R.string.tool_eyedropper), onClick = { tools.mode = if (tools.mode == ToolMode.Eyedropper) ToolMode.Pen else ToolMode.Eyedropper }, selected = tools.mode == ToolMode.Eyedropper)
        StudioKey(StudioGlyphs.Hand, stringResource(R.string.tool_hand), onClick = { tools.mode = if (tools.mode == ToolMode.Hand) ToolMode.Pen else ToolMode.Hand }, selected = tools.mode == ToolMode.Hand)
    }
}

@Composable
private fun RailSliders(ed: Editor) {
    val tools = ed.tools
    val preset = tools.current
    val erasing = tools.mode == ToolMode.Eraser && tools.eraser != BrushKind.LassoEraser
    val kind = if (erasing) tools.eraser else preset.kind
    val width = if (erasing) tools.eraserWidth(tools.eraser) else preset.width
    val widthScale = remember(kind) { ValueScale.width(BrushSpec.widthRange(kind)) }
    val opacityScale = remember { ValueScale.percent(0.05f, 1f) }
    val sizeTitle = stringResource(if (erasing) R.string.slider_eraser_size else R.string.slider_size)
    fun setWidth(w: Float) = if (erasing) tools.setEraserWidth(tools.eraser, w) else tools.updateCurrent { it.copy(width = w) }
    Pill(Modifier.penShield(ed.shields, "sliders"), vertical = true) {
        QuickSlider(
            sizeTitle, width, widthScale, text = { ValueScale.formatNumber(it) },
            onCommit = { setWidth(it) },
            onType = { ed.typeValue(sizeTitle, widthScale, width, ed.string(R.string.unit_px)) { setWidth(it) } },
            typeLabel = stringResource(R.string.slider_type, sizeTitle),
        )
        PillRule(vertical = false)
        val opacity = preset.spec().opacity
        val opacityTitle = stringResource(R.string.slider_opacity)
        QuickSlider(
            opacityTitle, opacity, opacityScale, text = { "${opacityScale.format(it)}%" },
            onCommit = { v -> tools.updateCurrent { it.copy(tune = it.tune.copy(opacity = v)) } },
            onType = { ed.typeValue(opacityTitle, opacityScale, opacity, "%") { v -> tools.updateCurrent { it.copy(tune = it.tune.copy(opacity = v)) } } },
            typeLabel = stringResource(R.string.slider_type, opacityTitle),
            track = Track.Checker(preset.color),
        )
    }
}

/**
 * A favourite pen on the rail: its stroke in its colour, drawn by the engine, with a dot as wide as it draws. Chosen,
 * the key fills black around a paper chip that keeps the ink visible.
 */
@Composable
private fun PenSlotKey(preset: BrushPreset, index: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.pen_slot, index + 1, stringResource(Names.brush(preset.kind)), ValueScale.formatNumber(preset.width))
    val spec = preset.spec()
    StudioKey(null, label, onClick = onClick, selected = selected, modifier = modifier) { _ ->
        Box(
            Modifier
                .size(if (selected) 34.dp else 40.dp)
                .background(Studio.Card, RoundedCornerShape(Studio.RadiusS)),
            contentAlignment = Alignment.Center,
        ) {
            EnginePicture(Triple("slot", spec, preset.color), Modifier.size(if (selected) 32.dp else 38.dp)) { c, sink, w, h ->
                Samples.draw(Samples.sCurve(spec, preset.color, w.toFloat(), h.toFloat(), maxWidth = h * 0.3f), c, sink)
            }
            Canvas(Modifier.align(Alignment.BottomEnd).padding(2.dp).size(10.dp)) {
                val f = (ln(preset.width.coerceIn(0.5f, 80f) / 0.5f) / ln(160f)).coerceIn(0f, 1f)
                drawCircle(Color.Black, radius = (1.5f + f * 3.5f).dp.toPx())
            }
        }
    }
}

@Composable
private fun ViewChip(ed: Editor, viewport: Viewport?, onFullscreen: () -> Unit) {
    val p = ed.panels
    val label = viewport?.let { viewLabel(it) } ?: "…"
    Pill(Modifier.penShield(ed.shields, "view chip")) {
        Box(
            Modifier
                .heightIn(min = Studio.Key)
                .panelAnchor(p, PanelId.VIEW)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { p.toggle(PanelId.VIEW) }
                .semantics { contentDescription = ed.string(R.string.view_chip, label) }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = StudioType.Value)
        }
        PillRule(vertical = true)
        StudioKey(StudioGlyphs.ResetView, stringResource(R.string.view_reset), onClick = { ed.view.value?.resetView() })
        StudioKey(StudioGlyphs.Fullscreen, stringResource(R.string.action_fullscreen), onClick = onFullscreen)
    }
}

/** What the lasso picked, and what can be done with it. */
@Composable
private fun SelectionBar(ed: Editor, selection: Selection) {
    val p = ed.panels
    val count = selection.ids.size
    Pill(Modifier.penShield(ed.shields, "selection bar")) {
        Text(pluralStringResource(R.plurals.selection_count, count, count), style = StudioType.Label, modifier = Modifier.padding(horizontal = 12.dp))
        PillRule(vertical = true)
        StudioKey(StudioGlyphs.MoveToLayer, stringResource(R.string.selection_move), onClick = { p.toggle(PanelId.MOVE) }, enabled = ed.session.document.layers.size > 1, modifier = Modifier.panelAnchor(p, PanelId.MOVE))
        StudioKey(StudioGlyphs.Duplicate, stringResource(R.string.selection_duplicate), onClick = { ed.view.value?.duplicateSelection() })
        val colour = ed.tools.current.color
        StudioKey(null, stringResource(R.string.selection_recolour, Palette.toHex(colour)), onClick = { ed.view.value?.recolourSelection(colour) }) { ink ->
            androidx.compose.material3.Icon(StudioGlyphs.Recolour, contentDescription = null, tint = ink, modifier = Modifier.size(26.dp))
            Canvas(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(12.dp)) {
                drawCircle(Color(colour or Palette.OPAQUE))
                drawCircle(Color.Black, style = Stroke(1.5.dp.toPx()))
            }
        }
        ConfirmStudioKey(StudioGlyphs.Trash, stringResource(R.string.selection_delete), stringResource(R.string.selection_delete_confirm), onConfirm = { ed.view.value?.deleteSelection() })
        PillRule(vertical = true)
        StudioKey(StudioGlyphs.Check, stringResource(R.string.selection_done), onClick = { ed.view.value?.clearSelection() })
    }
}

@Composable
private fun PanelFor(
    id: String,
    ed: Editor,
    landscape: Boolean,
    onBack: () -> Unit,
    onDiagnostics: (Boolean) -> Unit,
    onAbout: () -> Unit,
    onSettings: () -> Unit,
    onFullscreen: () -> Unit,
) {
    when (id) {
        PanelId.BRUSHES -> BrushesPanel(ed, landscape)
        PanelId.BRUSH -> BrushSettingsPanel(ed, landscape, onMatchPreview = { onDiagnostics(true) })
        PanelId.COLOUR -> ColourPanel(ed, landscape)
        PanelId.LAYERS -> LayersPanel(ed, landscape)
        PanelId.ERASER -> EraserPanel(ed, landscape)
        PanelId.MENU -> MenuPanel(ed, landscape, onBack, onDiagnostics = { onDiagnostics(false) }, onAbout, onSettings, onFullscreen)
        PanelId.EXPORT -> ExportPanel(ed, landscape)
        PanelId.PAPER -> PaperPanel(ed, landscape)
        PanelId.VIEW -> ViewPanel(ed, landscape)
        PanelId.MOVE -> MovePanel(ed, landscape)
    }
}

/** "85 % · 12°". */
fun viewLabel(v: Viewport): String = "${zoomLabel(v.scale)} · ${v.rotationDegrees}°"

/** A readout like "250 %". */
fun zoomLabel(scale: Float): String = "${Math.round(scale * 100f)} %"


