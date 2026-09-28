package app.booxultimatum.nib.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.kit.ui.Glyphs
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.Paragraph
import app.booxultimatum.kit.ui.StatusStrip
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import app.booxultimatum.nib.NibSettings
import app.booxultimatum.nib.R
import app.booxultimatum.nib.brush.BrushPreset
import app.booxultimatum.nib.brush.Palette
import app.booxultimatum.nib.editor.CanvasView
import app.booxultimatum.nib.editor.EditorSession
import app.booxultimatum.nib.editor.OpenSessions
import app.booxultimatum.nib.editor.ToolMode
import app.booxultimatum.nib.editor.ToolState
import app.booxultimatum.nib.export.Exporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** What is laid over the canvas. The layers panel is docked beside it instead, so the pen can still draw. */
private enum class Panel { None, Pen, Eraser, Zoom, More, Settings, RenameLayer }

/** The editor: toolbar, canvas, and its panels. */
@Composable
fun EditorScreen(drawingId: String, sessions: OpenSessions, onBack: () -> Unit, onDiagnostics: () -> Unit, onAbout: () -> Unit) {
    var failed by remember { mutableStateOf(false) }
    val session by produceState(sessions[drawingId], drawingId) {
        val s = sessions.load(drawingId)
        if (s == null) failed = true
        value = s
    }
    Column(Modifier.fillMaxSize()) {
        StatusStrip()
        val s = session
        if (s == null) {
            Column(Modifier.padding(Space.l)) {
                ToolKey(Glyphs.ChevronLeft, stringResource(R.string.action_library), onClick = onBack)
                Spacer(Modifier.height(Space.l))
                Paragraph(stringResource(if (failed) R.string.editor_unreadable else R.string.editor_opening), color = Ink.Legend)
            }
        } else {
            Editor(s, onBack, onDiagnostics, onAbout)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Editor(session: EditorSession, onBack: () -> Unit, onDiagnostics: () -> Unit, onAbout: () -> Unit) {
    val context = LocalContext.current
    val tools = remember { ToolState.get(context) }
    val settings = remember { NibSettings.get(context) }
    val log = remember { Logbook.logger("nib.ui") }
    val scope = rememberCoroutineScope()
    var panel by remember { mutableStateOf(Panel.None) }
    var layersOpen by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var renaming by remember { mutableStateOf<Long?>(null) }
    val view = remember { mutableStateOf<CanvasView?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    fun say(text: String) {
        message = text
    }

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
    LaunchedEffect(panel, view.value) { view.value?.setPanelOpen(panel != Panel.None) }
    LaunchedEffect(tools.current, tools.mode, tools.eraser, settings.tryUnverifiedStyles, settings.swapDelayMs, session.activeLayerId, session.revision, view.value) {
        view.value?.onToolsChanged()
    }
    session.revision

    Toolbar(
        tools = tools,
        canUndo = session.history.canUndo,
        canRedo = session.history.canRedo,
        layersOpen = layersOpen,
        zoom = zoom,
        onBack = onBack,
        onSlot = { i ->
            if (tools.mode == ToolMode.Pen && tools.selected == i) {
                panel = if (panel == Panel.Pen) Panel.None else Panel.Pen
            } else {
                tools.mode = ToolMode.Pen
                tools.selected = i
                panel = Panel.None
            }
        },
        onEraser = {
            if (tools.mode == ToolMode.Eraser) {
                panel = if (panel == Panel.Eraser) Panel.None else Panel.Eraser
            } else {
                tools.mode = ToolMode.Eraser
                panel = Panel.None
            }
        },
        onUndo = { if (!session.undo()) say(context.getString(R.string.message_nothing_to_undo)) },
        onRedo = { if (!session.redo()) say(context.getString(R.string.message_nothing_to_redo)) },
        onLayers = { layersOpen = !layersOpen },
        onZoom = { panel = if (panel == Panel.Zoom) Panel.None else Panel.Zoom },
        onMore = { panel = if (panel == Panel.More) Panel.None else Panel.More },
    )
    HorizontalDivider(thickness = Lines.engraved, color = Ink.Black)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val side = 400.dp
        val bottom = maxHeight * 0.42f
        // The canvas stays the first child in every orientation, so rotating never recreates it.
        AndroidView(
            factory = { c -> CanvasView(c).apply { id = R.id.nib_canvas } },
            update = { v ->
                view.value = v
                v.session = session
                v.onMessage = { res -> say(context.getString(res)) }
                v.onViewportChanged = { vp -> zoom = vp.scale }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(end = if (landscape && layersOpen) side else 0.dp, bottom = if (!landscape && layersOpen) bottom else 0.dp)
                .clipToBounds(),
        )
        if (layersOpen) {
            if (landscape) {
                Row(Modifier.align(Alignment.CenterEnd).width(side).fillMaxHeight()) {
                    VerticalDivider(thickness = Lines.engraved, color = Ink.Black)
                    LayersPanel(session, onRename = { renaming = it; panel = Panel.RenameLayer }, onClose = { layersOpen = false }, modifier = Modifier.fillMaxSize())
                }
            } else {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottom)) {
                    HorizontalDivider(thickness = Lines.engraved, color = Ink.Black)
                    LayersPanel(session, onRename = { renaming = it; panel = Panel.RenameLayer }, onClose = { layersOpen = false }, modifier = Modifier.fillMaxSize())
                }
            }
        }
        message?.let { m ->
            Text(
                m,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(Space.m)
                    .background(Ink.Paper, RoundedCornerShape(8.dp))
                    .border(Lines.rim, Ink.Black, RoundedCornerShape(8.dp))
                    .padding(horizontal = Space.m, vertical = Space.s),
            )
        }
        when (panel) {
            Panel.None -> Unit
            Panel.Pen -> PenPanel(tools, settings, onClose = { panel = Panel.None })
            Panel.Eraser -> EraserPanel(tools, onClose = { panel = Panel.None })
            Panel.Zoom -> OverlayPanel(onDismiss = { panel = Panel.None }, alignment = Alignment.TopEnd, maxWidth = 420.dp) {
                Text(stringResource(R.string.zoom_title, zoomLabel(zoom)), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(Space.m))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Key(stringResource(R.string.zoom_fit), onClick = { view.value?.fit(); panel = Panel.None })
                    Key(stringResource(R.string.zoom_actual), onClick = { view.value?.actualSize(); panel = Panel.None })
                    Key(stringResource(R.string.zoom_out), onClick = { view.value?.zoomBy(1f / sqrt(2f)) })
                    Key(stringResource(R.string.zoom_in), onClick = { view.value?.zoomBy(sqrt(2f)) })
                }
                Text(stringResource(R.string.zoom_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(top = Space.m))
            }
            Panel.More -> OverlayPanel(onDismiss = { panel = Panel.None }, alignment = Alignment.TopEnd, maxWidth = 420.dp) {
                MenuKey(stringResource(R.string.menu_export)) {
                    panel = Panel.None
                    say(context.getString(R.string.message_exporting))
                    val doc = session.document
                    scope.launch {
                        val uri = withContext(Dispatchers.IO) { Exporter.saveToGallery(context, doc, session.info.name.ifBlank { context.getString(R.string.untitled) }) }
                        say(context.getString(if (uri != null) R.string.message_exported else R.string.message_export_failed))
                    }
                }
                MenuKey(stringResource(R.string.menu_share)) {
                    panel = Panel.None
                    val doc = session.document
                    scope.launch {
                        val intent = runCatching { withContext(Dispatchers.IO) { Exporter.shareIntent(context, doc, session.info.name.ifBlank { context.getString(R.string.untitled) }) } }.getOrNull()
                        if (intent == null) {
                            say(context.getString(R.string.message_export_failed))
                        } else {
                            try {
                                context.startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                say(context.getString(R.string.message_no_share))
                            }
                        }
                    }
                }
                MenuKey(stringResource(R.string.menu_recover)) {
                    panel = Panel.None
                    val had = view.value?.recoverScreen() == true
                    say(context.getString(if (had) R.string.message_recovered else R.string.message_no_display_path))
                }
                MenuKey(stringResource(R.string.menu_settings)) { panel = Panel.Settings }
                MenuKey(stringResource(R.string.title_diagnostics)) {
                    panel = Panel.None
                    onDiagnostics()
                }
                MenuKey(stringResource(R.string.title_about)) {
                    panel = Panel.None
                    onAbout()
                }
            }
            Panel.Settings -> OverlayPanel(onDismiss = { panel = Panel.None }, alignment = Alignment.TopEnd, maxWidth = 560.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.menu_settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    ToolKey(Glyphs.Close, stringResource(R.string.action_close), onClick = { panel = Panel.None })
                }
                ToggleRow(stringResource(R.string.setting_finger_drawing), stringResource(R.string.setting_finger_drawing_detail), settings.fingerDrawing, onToggle = {
                    settings.fingerDrawing = it
                    log.i("setting", "finger drawing" to it)
                })
                ToggleRow(stringResource(R.string.setting_finger_pan), stringResource(R.string.setting_finger_pan_detail), settings.fingerPan, onToggle = {
                    settings.fingerPan = it
                    log.i("setting", "finger pan" to it)
                })
                Text(stringResource(R.string.setting_gestures), style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(top = Space.s))
            }
            Panel.RenameLayer -> {
                val layer = renaming?.let { session.document.layer(it) }
                if (layer == null) {
                    LaunchedEffect(Unit) { panel = Panel.None }
                } else {
                    var name by remember(layer.id) { mutableStateOf(layer.name) }
                    OverlayPanel(onDismiss = { panel = Panel.None }) {
                        Text(stringResource(R.string.layers_rename), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(Space.m))
                        val done = {
                            if (name.isNotBlank() && name.trim() != layer.name) session.renameLayer(layer.id, name.trim())
                            panel = Panel.None
                        }
                        NameField(name, { name = it }, stringResource(R.string.field_name), onDone = done)
                        Spacer(Modifier.height(Space.m))
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            Key(stringResource(R.string.action_rename), onClick = done, primary = true, enabled = name.isNotBlank())
                            Key(stringResource(R.string.action_cancel), onClick = { panel = Panel.None })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuKey(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s, vertical = Space.m),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
    HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
}

@Composable
private fun Toolbar(
    tools: ToolState,
    canUndo: Boolean,
    canRedo: Boolean,
    layersOpen: Boolean,
    zoom: Float,
    onBack: () -> Unit,
    onSlot: (Int) -> Unit,
    onEraser: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onLayers: () -> Unit,
    onZoom: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).padding(horizontal = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ToolKey(Glyphs.ChevronLeft, stringResource(R.string.action_library), onClick = onBack)
        Spacer(Modifier.width(Space.s))
        tools.slots.forEachIndexed { i, preset ->
            PenSlotKey(preset, i, selected = tools.mode == ToolMode.Pen && tools.selected == i, onClick = { onSlot(i) })
        }
        ToolKey(NibGlyphs.Eraser, stringResource(R.string.tool_eraser), onClick = onEraser, selected = tools.mode == ToolMode.Eraser)
        Spacer(Modifier.weight(1f))
        ToolKey(Glyphs.Undo, stringResource(R.string.action_undo), onClick = onUndo, enabled = canUndo)
        ToolKey(Glyphs.Redo, stringResource(R.string.action_redo), onClick = onRedo, enabled = canRedo)
        ToolKey(NibGlyphs.Layers, stringResource(R.string.layers_title), onClick = onLayers, selected = layersOpen)
        ZoomKey(zoom, onZoom)
        ToolKey(NibGlyphs.More, stringResource(R.string.action_more), onClick = onMore)
    }
}

/** A favourite pen: a short stroke in its colour, about as thick as it draws. The chosen one wears a heavy ring. */
@Composable
private fun PenSlotKey(preset: BrushPreset, index: Int, selected: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.pen_slot, index + 1, stringResource(Names.brush(preset.kind)))
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .clickable(remember { MutableInteractionSource() }, LocalIndication.current, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics {
                contentDescription = label
                this.selected = selected
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val w = (preset.width * 0.9f).coerceIn(1.5f, 12f).dp.toPx()
            val ink = if (Palette.isPale(preset.color)) Ink.Legend else Color(preset.color or Palette.OPAQUE)
            drawLine(ink, Offset(r * 0.55f, r * 1.4f), Offset(r * 1.45f, r * 0.6f), strokeWidth = w, cap = StrokeCap.Round)
            if (selected) {
                drawCircle(Ink.Black, radius = r - 2.dp.toPx(), style = Stroke(4.dp.toPx()))
            } else {
                drawCircle(Ink.Black, radius = r - Lines.rim.toPx() / 2f, style = Stroke(Lines.rim.toPx()))
            }
        }
    }
}

@Composable
private fun ZoomKey(zoom: Float, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    val label = stringResource(R.string.zoom_title, zoomLabel(zoom))
    Box(
        Modifier
            .defaultMinSize(minWidth = 84.dp, minHeight = 52.dp)
            .clip(shape)
            .border(Lines.rim, Ink.Black, shape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(zoomLabel(zoom), style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
