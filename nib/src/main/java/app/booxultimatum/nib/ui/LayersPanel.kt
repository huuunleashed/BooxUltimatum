package app.booxultimatum.nib.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.booxultimatum.kit.ui.ConfirmKey
import app.booxultimatum.kit.ui.Glyphs
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.Lamp
import app.booxultimatum.kit.ui.Pager
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import app.booxultimatum.nib.R
import app.booxultimatum.nib.editor.EditorSession
import app.booxultimatum.nib.engine.doc.Layer
import app.booxultimatum.nib.engine.history.MergeDown
import kotlin.math.roundToInt

/** Opacity steps the layers panel offers. */
val LAYER_OPACITIES = listOf(1f, 0.75f, 0.5f, 0.25f)

/**
 * The layers, top first, in pages: which is active, shown or hidden, locked, and how opaque; add, rename, reorder,
 * duplicate, merge down and delete. Every change goes through the session's history, so it all undoes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LayersPanel(session: EditorSession, onRename: (Long) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    session.revision
    val doc = session.document
    val top = doc.layers.asReversed()
    val activeId = session.activeLayerId
    var page by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    Column(modifier.background(Ink.Paper).padding(horizontal = Space.m, vertical = Space.s)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.layers_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            ToolKey(NibGlyphs.Plus, stringResource(R.string.layers_add), onClick = {
                session.addLayer(nextLayerName(doc.layers) { n -> context.getString(R.string.layer_name, n) })
            })
            Spacer(Modifier.size(Space.s))
            ToolKey(Glyphs.Close, stringResource(R.string.action_close), onClick = onClose)
        }
        HorizontalDivider(thickness = Lines.engraved, color = Ink.Black)
        // The rows fill what the header and action keys leave; the active row is taller, and a pager costs a row.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val room = maxHeight.value - ACTIVE_EXTRA_DP
            val all = (room / ROW_DP).toInt()
            val perPage = if (top.size <= all) top.size.coerceAtLeast(1) else ((room - PAGER_DP) / ROW_DP).toInt().coerceAtLeast(1)
            val pages = ((top.size + perPage - 1) / perPage).coerceAtLeast(1)
            val activeIndex = top.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
            LaunchedEffect(activeId, perPage) { page = activeIndex / perPage }
            val p = page.coerceIn(0, pages - 1)
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f)) {
                    for (layer in top.drop(p * perPage).take(perPage)) {
                        LayerRow(session, layer, layer.id == activeId, isTop = layer === top.first(), isBottom = layer === top.last())
                        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
                    }
                }
                if (pages > 1) Pager(p, pages, onPage = { page = it })
            }
        }
        val active = session.activeLayer
        FlowRow(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Key(stringResource(R.string.layers_rename), onClick = { onRename(active.id) })
            Key(stringResource(R.string.layers_duplicate), onClick = {
                session.duplicateLayer(active.id, context.getString(R.string.layer_copy_name, active.name.ifBlank { context.getString(R.string.layer_unnamed) }))
            })
            val canMerge = doc.layerIndex(active.id) > 0 && !active.locked && doc.layers.getOrNull(doc.layerIndex(active.id) - 1)?.locked == false
            // A translucent or blended layer, or one with pixel erasers, can't merge without changing how it looks.
            if (MergeDown.isExact(active)) {
                Key(stringResource(R.string.layers_merge_down), onClick = { session.mergeDown(active.id) }, enabled = canMerge)
            } else {
                ConfirmKey(stringResource(R.string.layers_merge_down), stringResource(R.string.layers_merge_approximate), onConfirm = { session.mergeDown(active.id) }, enabled = canMerge)
            }
            ConfirmKey(stringResource(R.string.layers_delete), stringResource(R.string.layers_delete_confirm), onConfirm = { session.deleteLayer(active.id) }, enabled = doc.layers.size > 1 && !active.locked)
        }
    }
}

// A row is 64 dp and a rule; the active one adds its opacity line; the pager takes about a row.
private const val ROW_DP = 66f
private const val ACTIVE_EXTRA_DP = 58f
private const val PAGER_DP = 76f

@Composable
private fun LayerRow(session: EditorSession, layer: Layer, active: Boolean, isTop: Boolean, isBottom: Boolean) {
    val name = layer.name.ifBlank { stringResource(R.string.layer_unnamed) }
    Column(Modifier.fillMaxWidth().semantics { selected = active }) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClickLabel = name) { session.selectLayer(layer.id) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Lamp(active)
            Spacer(Modifier.size(Space.m))
            Column(Modifier.weight(1f)) {
                Text(
                    name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.Medium),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    pluralStringResource(R.plurals.layer_detail, layer.strokes.size, layer.strokes.size, (layer.opacity * 100f).roundToInt()),
                    style = MaterialTheme.typography.bodySmall, color = Ink.Legend, maxLines = 1,
                )
            }
            ToolKey(
                if (layer.visible) NibGlyphs.Eye else NibGlyphs.EyeClosed,
                stringResource(if (layer.visible) R.string.layers_hide else R.string.layers_show, name),
                onClick = { session.setVisible(layer.id, !layer.visible) },
                selected = !layer.visible,
            )
            Spacer(Modifier.size(Space.xs))
            ToolKey(
                if (layer.locked) NibGlyphs.Lock else NibGlyphs.LockOpen,
                stringResource(if (layer.locked) R.string.layers_unlock else R.string.layers_lock, name),
                onClick = { session.setLocked(layer.id, !layer.locked) },
                selected = layer.locked,
            )
        }
        if (active) {
            Row(Modifier.fillMaxWidth().padding(bottom = Space.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                for (o in LAYER_OPACITIES) ChipKey("${(o * 100).roundToInt()}", layer.opacity == o, onClick = { session.setOpacity(layer.id, o) })
                Spacer(Modifier.weight(1f))
                ToolKey(NibGlyphs.Up, stringResource(R.string.layers_up), onClick = { session.moveLayer(layer.id, up = true) }, enabled = !isTop)
                ToolKey(NibGlyphs.Down, stringResource(R.string.layers_down), onClick = { session.moveLayer(layer.id, up = false) }, enabled = !isBottom)
            }
        }
    }
}

/** "Layer N" with the first N from the layer count up that isn't taken. */
fun nextLayerName(layers: List<Layer>, name: (Int) -> String): String {
    val used = layers.map { it.name }.toSet()
    var n = layers.size + 1
    while (name(n) in used) n++
    return name(n)
}
