package app.booxultimatum.nib.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import app.booxultimatum.nib.R
import app.booxultimatum.nib.engine.brush.Blend
import app.booxultimatum.nib.engine.doc.Layer
import app.booxultimatum.nib.engine.history.MergeDown
import app.booxultimatum.nib.engine.render.DocumentRenderer
import app.booxultimatum.nib.engine.render.StrokeRenderer
import app.booxultimatum.nib.render.DocumentPainter
import app.booxultimatum.nib.ui.studio.ConfirmButton
import app.booxultimatum.nib.ui.studio.EnginePicture
import app.booxultimatum.nib.ui.studio.EntryRequest
import app.booxultimatum.nib.ui.studio.Lamp
import app.booxultimatum.nib.ui.studio.SectionLabel
import app.booxultimatum.nib.ui.studio.Segmented
import app.booxultimatum.nib.ui.studio.SlabButton
import app.booxultimatum.nib.ui.studio.SlabIconKey
import app.booxultimatum.nib.ui.studio.Studio
import app.booxultimatum.nib.ui.studio.StudioGlyphs
import app.booxultimatum.nib.ui.studio.StudioKey
import app.booxultimatum.nib.ui.studio.StudioPager
import app.booxultimatum.nib.ui.studio.StudioSlider
import app.booxultimatum.nib.ui.studio.StudioType
import app.booxultimatum.nib.ui.studio.Track
import app.booxultimatum.nib.ui.studio.ValueScale
import app.booxultimatum.nib.ui.studio.checker
import app.booxultimatum.nib.ui.studio.slab
import kotlin.math.roundToInt

/**
 * The Layers card: pages of layers, top first, each with a thumbnail the engine draws, its name, whether it shows and
 * whether it's locked; below, the active layer's opacity, blend, transparency lock, order and actions. Every change
 * goes through the drawing's history, so all of it undoes.
 */
@Composable
internal fun LayersPanel(ed: Editor, landscape: Boolean) {
    val session = ed.session
    session.revision
    val context = LocalContext.current
    val doc = session.document
    val top = doc.layers.asReversed()
    val activeId = session.activeLayerId
    val perPage = if (landscape) 4 else 6
    val pages = ((top.size + perPage - 1) / perPage).coerceAtLeast(1)
    var page by rememberSaveable { mutableIntStateOf(0) }
    val activeIndex = top.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
    LaunchedEffect(activeId, perPage) { page = activeIndex / perPage }
    val p = page.coerceIn(0, pages - 1)
    StudioPanel(
        PanelId.LAYERS, stringResource(R.string.layers_title), ed, landscape, width = 500.dp,
        headerActions = {
            StudioKey(StudioGlyphs.Plus, stringResource(R.string.layers_add), onClick = {
                session.addLayer(nextLayerName(doc.layers) { n -> context.getString(R.string.layer_name, n) })
            }, size = Studio.Target)
        },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            for (layer in top.drop(p * perPage).take(perPage)) {
                LayerRow(ed, layer, layer.id == activeId)
            }
            if (pages > 1) {
                StudioPager(
                    p, pages, onPage = { page = it },
                    previousLabel = stringResource(R.string.pager_previous), nextLabel = stringResource(R.string.pager_next),
                    pageText = stringResource(R.string.pager_page, p + 1, pages), modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(Studio.Outline).background(Studio.Ink))
        ActiveLayer(ed, session.activeLayer)
    }
}

@Composable
private fun LayerRow(ed: Editor, layer: Layer, active: Boolean) {
    val session = ed.session
    val name = layer.name.ifBlank { stringResource(R.string.layer_unnamed) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(if (active) Studio.Wash else Studio.Card)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = name) { session.selectLayer(layer.id) }
            .semantics { selected = active }
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LayerThumb(ed, layer)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    Lamp(true, size = 11.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    name, style = StudioType.Title.copy(fontWeight = if (active) FontWeight(800) else FontWeight(600)),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                pluralStringResource(R.plurals.layer_detail, layer.strokes.size, layer.strokes.size, (layer.opacity * 100f).roundToInt(), stringResource(blendName(layer.blend))),
                style = StudioType.Small, color = Studio.Legend, maxLines = 1,
            )
        }
        StudioKey(
            if (layer.visible) StudioGlyphs.Eye else StudioGlyphs.EyeClosed,
            stringResource(if (layer.visible) R.string.layers_hide else R.string.layers_show, name),
            onClick = { session.setVisible(layer.id, !layer.visible) }, selected = !layer.visible, lamp = false, size = Studio.Target,
        )
        StudioKey(
            if (layer.locked) StudioGlyphs.Lock else StudioGlyphs.LockOpen,
            stringResource(if (layer.locked) R.string.layers_unlock else R.string.layers_lock, name),
            onClick = { session.setLocked(layer.id, !layer.locked) }, selected = layer.locked, lamp = false, size = Studio.Target,
        )
    }
}

/** A layer alone, drawn by the engine over a checkerboard, at the page's shape. */
@Composable
private fun LayerThumb(ed: Editor, layer: Layer) {
    val doc = ed.session.document
    val aspect = doc.width / doc.height.toFloat()
    val w = if (aspect >= 1f) 60.dp else 60.dp * aspect
    val h = if (aspect >= 1f) 60.dp / aspect else 60.dp
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(w, h).slab(radius = 2.dp, shadow = 0.dp, outlineWidth = Studio.Hairline)) {
            Canvas(Modifier.fillMaxSize()) { checker(0f, 0f, size.width, size.height, 5.dp.toPx()) }
            val key = Triple("layer", layer.id, System.identityHashCode(layer.strokes) * 31 + (layer.opacity * 100).roundToInt())
            EnginePicture(Triple(key, doc.width, doc.height), Modifier.fillMaxSize()) { c, sink, pw, ph ->
                val s = pw / doc.width.toFloat()
                c.scale(s, ph / doc.height.toFloat())
                val shown = DocumentPainter.legibleAt(doc.copy(layers = listOf(layer.copy(visible = true))), s).layers.first()
                DocumentRenderer.renderLayer(shown, sink.on(c), tolerance = StrokeRenderer.DEFAULT_TOLERANCE / s)
            }
        }
    }
}

internal fun blendName(b: Blend): Int = when (b) {
    Blend.Normal -> R.string.blend_normal
    Blend.Multiply -> R.string.blend_multiply
    Blend.Atop -> R.string.blend_clip
    Blend.Erase -> R.string.blend_normal
}

private val BLENDS = listOf(Blend.Normal, Blend.Multiply, Blend.Atop)

@Composable
private fun ActiveLayer(ed: Editor, active: Layer) {
    val session = ed.session
    val context = LocalContext.current
    val doc = session.document
    val name = active.name.ifBlank { stringResource(R.string.layer_unnamed) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.layers_active, name), Modifier.weight(1f))
            SlabButton(stringResource(R.string.layers_rename), onClick = {
                ed.type(EntryRequest(ed.string(R.string.layers_rename), active.name, numeric = false) { text ->
                    val t = text.trim()
                    if (t.isEmpty()) return@EntryRequest ed.string(R.string.entry_name_empty)
                    if (t != active.name) session.renameLayer(active.id, t)
                    null
                })
            })
        }
        val scale = remember { ValueScale.percent(0f, 1f) }
        val label = stringResource(R.string.prop_opacity)
        StudioSlider(
            label, active.opacity, scale, "%",
            onCommit = { session.setOpacity(active.id, it) },
            onType = { ed.typeValue(label, scale, active.opacity, "%") { session.setOpacity(active.id, it) } },
            minusLabel = stringResource(R.string.slider_less, label), plusLabel = stringResource(R.string.slider_more, label),
            typeLabel = stringResource(R.string.slider_type_value, label, scale.format(active.opacity) + " %"),
            track = Track.Checker(0xFF000000.toInt()),
        )
        Segmented(
            BLENDS.map { stringResource(blendName(it)) }, BLENDS.indexOf(active.blend).coerceAtLeast(0),
            onSelect = { session.setBlend(active.id, BLENDS[it]) }, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            SlabIconKey(
                StudioGlyphs.AlphaLock, stringResource(if (active.alphaLock) R.string.layers_alpha_unlock else R.string.layers_alpha_lock),
                onClick = { session.setAlphaLock(active.id, !active.alphaLock) }, selected = active.alphaLock,
            )
            val index = doc.layerIndex(active.id)
            SlabIconKey(StudioGlyphs.Up, stringResource(R.string.layers_up), onClick = { session.moveLayer(active.id, up = true) }, enabled = index < doc.layers.lastIndex)
            SlabIconKey(StudioGlyphs.Down, stringResource(R.string.layers_down), onClick = { session.moveLayer(active.id, up = false) }, enabled = index > 0)
            SlabIconKey(StudioGlyphs.Duplicate, stringResource(R.string.layers_duplicate), onClick = {
                session.duplicateLayer(active.id, context.getString(R.string.layer_copy_name, name))
            })
            Spacer(Modifier.weight(1f))
            val canMerge = index > 0 && !active.locked && doc.layers.getOrNull(index - 1)?.locked == false
            // A translucent or blended layer, or one with pixel erasers, can't merge without changing how it looks.
            if (MergeDown.isExact(active)) {
                SlabIconKey(StudioGlyphs.MergeDown, stringResource(R.string.layers_merge_down), onClick = { session.mergeDown(active.id) }, enabled = canMerge)
            } else {
                ConfirmButton(stringResource(R.string.layers_merge_short), stringResource(R.string.layers_merge_approximate), onConfirm = { session.mergeDown(active.id) }, enabled = canMerge, icon = StudioGlyphs.MergeDown)
            }
            ConfirmButton(stringResource(R.string.action_delete), stringResource(R.string.layers_delete_confirm), onConfirm = { session.deleteLayer(active.id) }, enabled = doc.layers.size > 1 && !active.locked, icon = StudioGlyphs.Trash)
        }
        if (active.alphaLock) Text(stringResource(R.string.layers_alpha_lock_detail), style = StudioType.Small, color = Studio.Legend)
    }
}

/** "Layer N" with the first N from the layer count up that isn't taken. */
fun nextLayerName(layers: List<Layer>, name: (Int) -> String): String {
    val used = layers.map { it.name }.toSet()
    var n = layers.size + 1
    while (name(n) in used) n++
    return name(n)
}
