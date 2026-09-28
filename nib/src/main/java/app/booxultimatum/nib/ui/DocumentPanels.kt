package app.booxultimatum.nib.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.booxultimatum.nib.R
import app.booxultimatum.nib.engine.geom.Viewport
import app.booxultimatum.nib.export.ExportKind
import app.booxultimatum.nib.export.Exporter
import app.booxultimatum.nib.store.Guides
import app.booxultimatum.nib.store.Paper
import app.booxultimatum.nib.store.PaperColour
import app.booxultimatum.nib.ui.studio.ButtonKind
import app.booxultimatum.nib.ui.studio.CardBody
import app.booxultimatum.nib.ui.studio.ChoiceTile
import app.booxultimatum.nib.ui.studio.SectionLabel
import app.booxultimatum.nib.ui.studio.Segmented
import app.booxultimatum.nib.ui.studio.SlabButton
import app.booxultimatum.nib.ui.studio.Studio
import app.booxultimatum.nib.ui.studio.StudioGlyphs
import app.booxultimatum.nib.ui.studio.StudioSlider
import app.booxultimatum.nib.ui.studio.StudioType
import app.booxultimatum.nib.ui.studio.Toggle
import app.booxultimatum.nib.ui.studio.ValueChip
import app.booxultimatum.nib.ui.studio.ValueScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI

/** The menu: a card of rows below its key. Not pinned or moved; it closes when anything is chosen. */
@Composable
internal fun MenuPanel(
    ed: Editor,
    landscape: Boolean,
    onBack: () -> Unit,
    onDiagnostics: () -> Unit,
    onAbout: () -> Unit,
    onSettings: () -> Unit,
    onFullscreen: () -> Unit,
) {
    val p = ed.panels
    StudioPanel(PanelId.MENU, stringResource(R.string.menu_title), ed, landscape, width = 380.dp, pinnable = false, draggable = false) {
        Column(Modifier.padding(vertical = 6.dp)) {
            MenuRow(StudioGlyphs.Export, stringResource(R.string.menu_export)) { p.show(PanelId.EXPORT) }
            MenuRow(StudioGlyphs.Share, stringResource(R.string.menu_share)) {
                p.close(PanelId.MENU)
                share(ed, ExportKind.WithPaper)
            }
            MenuRow(StudioGlyphs.Paper, stringResource(R.string.menu_paper)) { p.show(PanelId.PAPER) }
            MenuRow(StudioGlyphs.Fullscreen, stringResource(R.string.action_fullscreen)) { onFullscreen() }
            MenuRow(StudioGlyphs.Recover, stringResource(R.string.menu_recover)) {
                p.close(PanelId.MENU)
                val had = ed.view.value?.recoverScreen() == true
                ed.say(ed.string(if (had) R.string.message_recovered else R.string.message_no_display_path))
            }
            Rule()
            MenuRow(StudioGlyphs.Settings, stringResource(R.string.menu_settings)) {
                p.close(PanelId.MENU)
                onSettings()
            }
            MenuRow(StudioGlyphs.Diagnostics, stringResource(R.string.title_diagnostics)) {
                p.close(PanelId.MENU)
                onDiagnostics()
            }
            MenuRow(StudioGlyphs.About, stringResource(R.string.title_about)) {
                p.close(PanelId.MENU)
                onAbout()
            }
            Rule()
            MenuRow(StudioGlyphs.Library, stringResource(R.string.action_library)) {
                p.close(PanelId.MENU)
                onBack()
            }
        }
    }
}

@Composable
private fun Rule() {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(Studio.Hairline).background(Studio.Faint))
}

@Composable
private fun MenuRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.Black, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = StudioType.Title)
    }
}

private fun kindName(k: ExportKind): Int = when (k) {
    ExportKind.WithPaper -> R.string.export_paper
    ExportKind.Transparent -> R.string.export_transparent
    ExportKind.Layers -> R.string.export_layers
}

private fun layerFile(ed: Editor): (Int, String) -> String = { i, n -> "${i + 1} ${n.ifBlank { ed.string(R.string.layer_unnamed) }}" }

private fun share(ed: Editor, kind: ExportKind) {
    val doc = ed.session.document
    val paper = ed.session.paper
    val name = ed.session.info.name.ifBlank { ed.string(R.string.untitled) }
    ed.say(ed.string(R.string.message_exporting))
    ed.scope.launch {
        val intent = runCatching { withContext(Dispatchers.IO) { Exporter.shareIntent(ed.context, doc, name, paper, kind, layerFile(ed)) } }.getOrNull()
        if (intent == null) {
            ed.say(ed.string(R.string.message_export_failed))
        } else {
            try {
                ed.context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                ed.say(ed.string(R.string.message_no_share))
            }
        }
    }
}

/** Export: the page with its paper, the ink alone on transparency, or every layer as its own PNG. */
@Composable
internal fun ExportPanel(ed: Editor, landscape: Boolean) {
    var kindKey by rememberSaveable { mutableStateOf(ExportKind.WithPaper.key) }
    val kind = ExportKind.entries.firstOrNull { it.key == kindKey } ?: ExportKind.WithPaper
    val paper = ed.session.paper
    StudioPanel(PanelId.EXPORT, stringResource(R.string.export_title), ed, landscape, width = 500.dp) {
        CardBody {
            Segmented(ExportKind.entries.map { stringResource(kindName(it)) }, ExportKind.entries.indexOf(kind), onSelect = { kindKey = ExportKind.entries[it].key }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(
                    when (kind) {
                        ExportKind.WithPaper -> R.string.export_paper_detail
                        ExportKind.Transparent -> R.string.export_transparent_detail
                        ExportKind.Layers -> R.string.export_layers_detail
                    },
                ),
                style = StudioType.Body,
            )
            if (kind == ExportKind.WithPaper && paper.guides != Guides.None) {
                Toggle(
                    stringResource(R.string.paper_guides_export), null, paper.guidesInExport,
                    onToggle = { ed.session.setPaper(paper.copy(guidesInExport = it)) },
                    onText = stringResource(R.string.toggle_on), offText = stringResource(R.string.toggle_off),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SlabButton(
                    stringResource(if (kind == ExportKind.Layers) R.string.export_save_downloads else R.string.export_save_pictures),
                    kind = ButtonKind.Primary, icon = null,
                    onClick = {
                        val doc = ed.session.document
                        val name = ed.session.info.name.ifBlank { ed.string(R.string.untitled) }
                        ed.say(ed.string(R.string.message_exporting))
                        ed.panels.close(PanelId.EXPORT)
                        ed.scope.launch {
                            val uri = withContext(Dispatchers.IO) { Exporter.saveToGallery(ed.context, doc, name, paper, kind, layerFile(ed)) }
                            ed.say(ed.string(if (uri == null) R.string.message_export_failed else if (kind == ExportKind.Layers) R.string.message_exported_zip else R.string.message_exported))
                        }
                    },
                )
                SlabButton(stringResource(R.string.export_share), icon = StudioGlyphs.Share, onClick = {
                    ed.panels.close(PanelId.EXPORT)
                    share(ed, kind)
                })
            }
        }
    }
}

private fun paperName(c: PaperColour): Int = when (c) {
    PaperColour.White -> R.string.paper_white
    PaperColour.Warm -> R.string.paper_warm
    PaperColour.Grey -> R.string.paper_grey
    PaperColour.Black -> R.string.paper_black
}

private fun guidesName(g: Guides): Int = when (g) {
    Guides.None -> R.string.guides_none
    Guides.Dots -> R.string.guides_dots
    Guides.Grid -> R.string.guides_grid
    Guides.Lines -> R.string.guides_lines
}

/** Paper: its colour, its guides and their spacing, and whether exports show them. Guides lie under every layer. */
@Composable
internal fun PaperPanel(ed: Editor, landscape: Boolean) {
    val session = ed.session
    val paper = session.paper
    StudioPanel(PanelId.PAPER, stringResource(R.string.paper_title), ed, landscape, width = 520.dp) {
        CardBody {
            SectionLabel(stringResource(R.string.paper_colour))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in PaperColour.entries) {
                    ChoiceTile(
                        stringResource(paperName(c)), selected = (paper.colour or -0x1000000) == c.argb,
                        onClick = { session.setPaper(paper.copy(colour = c.argb)) }, modifier = Modifier.weight(1f), pictureHeight = 56.dp,
                    ) {
                        PaperSwatch(c.argb, Guides.None, Modifier.fillMaxSize().padding(8.dp))
                    }
                }
            }
            SectionLabel(stringResource(R.string.paper_guides))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (g in Guides.entries) {
                    ChoiceTile(
                        stringResource(guidesName(g)), selected = paper.guides == g,
                        onClick = { session.setPaper(paper.copy(guides = g)) }, modifier = Modifier.weight(1f), pictureHeight = 56.dp,
                    ) {
                        PaperSwatch(paper.colour, g, Modifier.fillMaxSize().padding(8.dp))
                    }
                }
            }
            val scale = remember { ValueScale.linear(Paper.SPACING_RANGE.start, Paper.SPACING_RANGE.endInclusive, 5f, decimals = 0) }
            val label = stringResource(R.string.paper_spacing)
            val px = ed.string(R.string.unit_px)
            StudioSlider(
                label, paper.spacing, scale, px,
                onCommit = { session.setPaper(paper.copy(spacing = it)) },
                onType = { ed.typeValue(label, scale, paper.spacing, px) { session.setPaper(paper.copy(spacing = it)) } },
                enabled = paper.guides != Guides.None,
                minusLabel = stringResource(R.string.slider_less, label), plusLabel = stringResource(R.string.slider_more, label),
                typeLabel = stringResource(R.string.slider_type_value, label, scale.format(paper.spacing) + " " + px),
            )
            Text(stringResource(R.string.paper_spacing_mm, ValueScale.formatNumber(paper.spacing / 300f * 25.4f, 1)), style = StudioType.Small, color = Studio.Legend)
            Toggle(
                stringResource(R.string.paper_guides_export), stringResource(R.string.paper_guides_export_detail), paper.guidesInExport,
                onToggle = { session.setPaper(paper.copy(guidesInExport = it)) },
                onText = stringResource(R.string.toggle_on), offText = stringResource(R.string.toggle_off),
            )
        }
    }
}

/** A little page in [argb] with [guides] on it and a stroke of ink, for the paper tiles. */
@Composable
private fun PaperSwatch(argb: Int, guides: Guides, modifier: Modifier) {
    val paper = Paper(colour = argb, guides = guides)
    Canvas(modifier) {
        drawRect(Color.Black, topLeft = Offset(3.dp.toPx(), 3.dp.toPx()), size = size)
        drawRect(Color(argb or -0x1000000))
        val g = Color(paper.guideColour)
        val step = size.height / 4f
        when (guides) {
            Guides.Lines -> for (i in 1..3) drawLine(g, Offset(0f, i * step), Offset(size.width, i * step), strokeWidth = 1.dp.toPx())
            Guides.Grid -> {
                for (i in 1..3) drawLine(g, Offset(0f, i * step), Offset(size.width, i * step), strokeWidth = 1.dp.toPx())
                var x = step
                while (x < size.width) {
                    drawLine(g, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                    x += step
                }
            }
            Guides.Dots -> {
                var x = step
                while (x < size.width) {
                    for (i in 1..3) drawCircle(g, radius = 1.6.dp.toPx(), center = Offset(x, i * step))
                    x += step
                }
            }
            Guides.None -> Unit
        }
        if (guides == Guides.None) {
            val ink = if (paper.dark) Color(0xFFEEEEEE) else Color.Black
            val path = Path().apply {
                moveTo(size.width * 0.15f, size.height * 0.62f)
                cubicTo(size.width * 0.35f, size.height * 0.2f, size.width * 0.55f, size.height * 0.95f, size.width * 0.85f, size.height * 0.4f)
            }
            drawPath(path, ink, style = Stroke(2.5.dp.toPx()))
        }
        drawRect(Color.Black, style = Stroke(Studio.Outline.toPx()))
    }
}

/** The view: fit, 100 % and steps of zoom; the page's turn in quarter turns or to the degree. */
@Composable
internal fun ViewPanel(ed: Editor, landscape: Boolean) {
    val v = ed.view.value
    StudioPanel(PanelId.VIEW, stringResource(R.string.view_title), ed, landscape, width = 460.dp) {
        CardBody {
            SectionLabel(stringResource(R.string.view_zoom))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SlabButton(stringResource(R.string.zoom_fit), onClick = { v?.fit() })
                SlabButton(stringResource(R.string.zoom_actual), onClick = { v?.actualSize() })
                SlabButton(stringResource(R.string.zoom_out), onClick = { v?.zoomBy(1f / kotlin.math.sqrt(2f)) })
                SlabButton(stringResource(R.string.zoom_in), onClick = { v?.zoomBy(kotlin.math.sqrt(2f)) })
            }
            SectionLabel(stringResource(R.string.view_turn))
            val degrees = v?.viewport?.rotationDegrees ?: 0
            val quarters = listOf(0, 90, 180, 270)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Segmented(quarters.map { "$it°" }, quarters.indexOf(degrees), onSelect = { i -> v?.rotateTo((quarters[i] * PI / 180.0).toFloat()) }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                ValueChip("$degrees°", onClick = {
                    val scale = ValueScale.linear(0f, 359f, 1f, decimals = 0)
                    ed.typeValue(ed.string(R.string.view_turn), scale, degrees.toFloat(), "°") { d -> v?.rotateTo(Viewport.normalize((d * PI / 180.0).toFloat())) }
                }, description = stringResource(R.string.view_turn_type, degrees))
            }
            Text(stringResource(R.string.zoom_hint), style = StudioType.Small, color = Studio.Legend)
        }
    }
}

/** Moving the lasso's strokes to another layer: the other layers, top first. */
@Composable
internal fun MovePanel(ed: Editor, landscape: Boolean) {
    val session = ed.session
    session.revision
    val from = ed.view.value?.selection?.layerId ?: session.activeLayerId
    val others = session.document.layers.asReversed().filter { it.id != from }
    StudioPanel(PanelId.MOVE, stringResource(R.string.selection_move), ed, landscape, width = 400.dp) {
        CardBody {
            if (others.isEmpty()) Text(stringResource(R.string.selection_move_none), style = StudioType.Body, color = Studio.Legend)
            for (l in others) {
                WideButton(
                    l.name.ifBlank { stringResource(R.string.layer_unnamed) },
                    onClick = {
                        ed.view.value?.moveSelectionTo(l.id)
                        ed.panels.close(PanelId.MOVE)
                    },
                    icon = if (l.locked) StudioGlyphs.Lock else StudioGlyphs.Layers, enabled = !l.locked,
                )
            }
        }
    }
}

