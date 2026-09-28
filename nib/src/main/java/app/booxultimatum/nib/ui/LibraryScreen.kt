package app.booxultimatum.nib.ui

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.kit.ui.ConfirmKey
import app.booxultimatum.kit.ui.Glyphs
import app.booxultimatum.kit.ui.IconKey
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.Pager
import app.booxultimatum.kit.ui.Paragraph
import app.booxultimatum.kit.ui.Plate
import app.booxultimatum.kit.ui.ScreenHeader
import app.booxultimatum.kit.ui.StatusStrip
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import app.booxultimatum.nib.R
import app.booxultimatum.nib.pen.NibPen
import app.booxultimatum.nib.store.DrawingInfo
import app.booxultimatum.nib.store.DrawingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A page size for a new drawing. */
private data class PagePreset(val label: Int, val width: Int, val height: Int)

/** How many cards fit: pure, so the paging is tested on the JVM. */
object LibraryLayout {
    fun columns(widthDp: Float): Int = (widthDp / 250f).toInt().coerceIn(2, 6)
    fun rows(heightDp: Float): Int = (heightDp / 340f).toInt().coerceIn(1, 4)
    fun pages(count: Int, perPage: Int): Int = if (count <= 0) 1 else (count + perPage - 1) / perPage
}

/** The library: a paged grid of drawings, with New and each drawing's rename, duplicate and delete. */
@Composable
fun LibraryScreen(onOpen: (String) -> Unit, onDiagnostics: () -> Unit, onAbout: () -> Unit) {
    val context = LocalContext.current
    val store = remember { DrawingStore.get(context) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    // Saving continues for a moment after the editor closes; the list reads itself again as each save lands.
    val saved by store.changes.collectAsState()
    val drawings by produceState<List<DrawingInfo>?>(null, refresh, saved) { value = withContext(Dispatchers.IO) { store.list() } }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val log = remember { Logbook.logger("nib.ui") }
    val portrait = remember { NibPen.panelPortrait(context) }
    val presets = remember(portrait) {
        listOf(
            PagePreset(R.string.page_tablet, portrait.first, portrait.second),
            PagePreset(R.string.page_a4, 2480, 3508),
            PagePreset(R.string.page_square, portrait.second, portrait.second),
        )
    }

    Column(Modifier.fillMaxSize()) {
        StatusStrip()
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.l).padding(top = Space.l)) {
            val list = drawings
            ScreenHeader(
                stringResource(R.string.app_name),
                if (list == null) null else pluralStringResource(R.plurals.library_count, list.size, list.size),
            ) {
                Key(stringResource(R.string.action_new), onClick = { creating = !creating; editing = null }, primary = true)
                IconKey(Glyphs.Pen, stringResource(R.string.title_diagnostics), onClick = onDiagnostics)
                IconKey(Glyphs.Nib, stringResource(R.string.title_about), onClick = onAbout)
            }
            if (creating) {
                Plate(stringResource(R.string.new_title)) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        for (p in presets) {
                            Key(stringResource(p.label, p.width, p.height), onClick = {
                                creating = false
                                scope.launch {
                                    val name = context.getString(R.string.new_name, (drawings?.size ?: 0) + 1)
                                    val info = withContext(Dispatchers.IO) { store.create(name, p.width, p.height, context.getString(R.string.layer_name, 1)) }
                                    log.i("new drawing", "width" to p.width, "height" to p.height)
                                    onOpen(info.id)
                                }
                            })
                        }
                        Key(stringResource(R.string.action_cancel), onClick = { creating = false })
                    }
                }
                Spacer(Modifier.height(Space.l))
            }
            val target = list?.firstOrNull { it.id == editing }
            if (target != null) {
                DrawingActions(
                    target,
                    onRename = { name ->
                        scope.launch {
                            withContext(Dispatchers.IO) { store.rename(target.id, name) }
                            editing = null
                            refresh++
                        }
                    },
                    onDuplicate = {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.duplicate(target.id, context.getString(R.string.copy_name, target.name.ifBlank { context.getString(R.string.untitled) })) }
                            editing = null
                            refresh++
                        }
                    },
                    onDelete = {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.delete(target.id) }
                            editing = null
                            refresh++
                        }
                    },
                    onClose = { editing = null },
                )
                Spacer(Modifier.height(Space.l))
            }
            when {
                list == null -> Paragraph(stringResource(R.string.library_reading), color = Ink.Legend)
                list.isEmpty() -> Paragraph(stringResource(R.string.library_empty), color = Ink.Legend)
                else -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val cols = LibraryLayout.columns(maxWidth.value)
                    val rows = LibraryLayout.rows(maxHeight.value - 96f)
                    val perPage = cols * rows
                    val pages = LibraryLayout.pages(list.size, perPage)
                    LaunchedEffect(pages) { if (page >= pages) page = pages - 1 }
                    val shown = list.drop(page.coerceIn(0, pages - 1) * perPage).take(perPage)
                    Column(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                            for (r in 0 until rows) {
                                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                                    for (c in 0 until cols) {
                                        val d = shown.getOrNull(r * cols + c)
                                        Box(Modifier.weight(1f).fillMaxHeight()) {
                                            if (d != null) DrawingCard(d, store, onOpen = { onOpen(d.id) }, onMore = { editing = d.id; creating = false })
                                        }
                                    }
                                }
                            }
                        }
                        if (pages > 1) Pager(page, pages, onPage = { page = it })
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawingCard(d: DrawingInfo, store: DrawingStore, onOpen: () -> Unit, onMore: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(null, d.id, d.modified) { value = withContext(Dispatchers.IO) { store.thumbnail(d.id) } }
    val name = d.name.ifBlank { stringResource(R.string.untitled) }
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(Lines.hairline, Ink.Black, shape)
                .clickable(role = Role.Button, onClickLabel = name, onClick = onOpen)
                .semantics { contentDescription = name }
                .padding(Space.s),
            contentAlignment = Alignment.Center,
        ) {
            val t = thumb
            if (t != null) {
                Image(
                    t.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (d.width > 0 && d.height > 0) {
                Box(Modifier.fillMaxHeight().aspectRatio(d.width / d.height.toFloat()).border(Lines.hairline, Ink.Rule))
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    DateUtils.formatDateTime(context, d.modified, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH),
                    style = MaterialTheme.typography.bodySmall, color = Ink.Legend, maxLines = 1,
                )
            }
            ToolKey(NibGlyphs.More, stringResource(R.string.action_drawing_actions, name), onClick = onMore)
        }
    }
}

/** A drawing's actions, in a panel at the top where the keyboard can't cover the name field. */
@Composable
private fun DrawingActions(d: DrawingInfo, onRename: (String) -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    var name by remember(d.id) { mutableStateOf(d.name) }
    val focus = remember { FocusRequester() }
    Plate(d.name.ifBlank { stringResource(R.string.untitled) }, action = { ToolKey(Glyphs.Close, stringResource(R.string.action_close), onClick = onClose) }) {
        NameField(name, { name = it }, stringResource(R.string.field_name), Modifier.focusRequester(focus), onDone = { if (name.isNotBlank()) onRename(name.trim()) })
        Spacer(Modifier.height(Space.m))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Key(stringResource(R.string.action_rename), onClick = { onRename(name.trim()) }, enabled = name.isNotBlank() && name.trim() != d.name)
            Key(stringResource(R.string.action_duplicate), onClick = onDuplicate)
            ConfirmKey(stringResource(R.string.action_delete), stringResource(R.string.action_delete_confirm), onConfirm = onDelete)
        }
    }
}

/** A single-line text field in the instrument's rim. */
@Composable
fun NameField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    val shape = RoundedCornerShape(percent = 50)
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.take(80)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.Black),
        cursorBrush = SolidColor(Ink.Black),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).border(Lines.rim, Ink.Black, shape).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(label, style = MaterialTheme.typography.bodyLarge, color = Ink.Legend)
                inner()
            }
        },
    )
}
