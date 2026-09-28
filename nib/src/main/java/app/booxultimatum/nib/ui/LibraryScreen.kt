package app.booxultimatum.nib.ui

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.kit.ui.StatusStrip
import app.booxultimatum.nib.R
import app.booxultimatum.nib.engine.brush.BrushKind
import app.booxultimatum.nib.engine.brush.BrushSpec
import app.booxultimatum.nib.engine.input.InputSample
import app.booxultimatum.nib.engine.input.StrokeBuilder
import app.booxultimatum.nib.engine.render.StrokeRenderer
import app.booxultimatum.nib.export.Exporter
import app.booxultimatum.nib.pen.NibPen
import app.booxultimatum.nib.render.CanvasSink
import app.booxultimatum.nib.store.DrawingInfo
import app.booxultimatum.nib.store.DrawingStore
import app.booxultimatum.nib.ui.studio.ButtonKind
import app.booxultimatum.nib.ui.studio.CardBody
import app.booxultimatum.nib.ui.studio.ConfirmButton
import app.booxultimatum.nib.ui.studio.EnginePicture
import app.booxultimatum.nib.ui.studio.EntryBar
import app.booxultimatum.nib.ui.studio.EntryRequest
import app.booxultimatum.nib.ui.studio.FloatingPanel
import app.booxultimatum.nib.ui.studio.Lamp
import app.booxultimatum.nib.ui.studio.PanelMemory
import app.booxultimatum.nib.ui.studio.PanelState
import app.booxultimatum.nib.ui.studio.Pill
import app.booxultimatum.nib.ui.studio.SectionLabel
import app.booxultimatum.nib.ui.studio.Segmented
import app.booxultimatum.nib.ui.studio.SlabButton
import app.booxultimatum.nib.ui.studio.SlabIconKey
import app.booxultimatum.nib.ui.studio.Studio
import app.booxultimatum.nib.ui.studio.StudioGlyphs
import app.booxultimatum.nib.ui.studio.StudioKey
import app.booxultimatum.nib.ui.studio.StudioPager
import app.booxultimatum.nib.ui.studio.StudioType
import app.booxultimatum.nib.ui.studio.Toast
import app.booxultimatum.nib.ui.studio.ValueChip
import app.booxultimatum.nib.ui.studio.ValueScale
import app.booxultimatum.nib.ui.studio.panelAnchor
import app.booxultimatum.nib.ui.studio.panelOrigin
import app.booxultimatum.nib.ui.studio.slab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** How many cards fit: pure, so the paging is tested on the JVM. */
object LibraryLayout {
    fun columns(widthDp: Float): Int = (widthDp / 250f).toInt().coerceIn(2, 6)
    fun rows(heightDp: Float): Int = (heightDp / 300f).toInt().coerceIn(1, 4)
    fun pages(count: Int, perPage: Int): Int = if (count <= 0) 1 else (count + perPage - 1) / perPage
}

/** The shelf's order. */
enum class LibrarySort(val key: String) {
    Recent("recent"), Name("name"), Size("size");

    /** [list] in this order, keeping only the drawings whose name holds [query] (any case). */
    fun apply(list: List<DrawingInfo>, query: String, untitled: String): List<DrawingInfo> {
        val q = query.trim()
        val kept = if (q.isEmpty()) list else list.filter { it.name.ifBlank { untitled }.contains(q, ignoreCase = true) }
        return when (this) {
            Recent -> kept.sortedByDescending { it.modified }
            Name -> kept.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { untitled } })
            Size -> kept.sortedByDescending { it.bytes }
        }
    }

    companion object {
        fun of(key: String?): LibrarySort = entries.firstOrNull { it.key == key } ?: Recent
    }
}

/** A page size for a new drawing. */
data class PagePreset(val key: String, val width: Int, val height: Int)

/** The New card's page sizes, and custom sizes in pixels or millimetres at the panel's 300 ppi. */
object PagePresets {
    const val PPI = 300f
    const val MIN_PX = 64
    const val MAX_PX = 8192

    fun mmToPx(mm: Float): Int = (mm / 25.4f * PPI).roundToInt()

    fun pxToMm(px: Int): Float = px / PPI * 25.4f

    /** The presets for a panel of [portraitW] by [portraitH]: its page both ways, A4, A5 and a square. */
    fun all(portraitW: Int, portraitH: Int): List<PagePreset> = listOf(
        PagePreset("panel_portrait", portraitW, portraitH),
        PagePreset("panel_landscape", portraitH, portraitW),
        PagePreset("a4", mmToPx(210f), mmToPx(297f)),
        PagePreset("a5", mmToPx(148f), mmToPx(210f)),
        PagePreset("square", portraitH, portraitH),
    )

    /** A typed size in pixels, or null outside [MIN_PX]..[MAX_PX]. */
    fun custom(value: Float, mm: Boolean): Int? {
        if (!value.isFinite() || value <= 0f) return null
        val px = if (mm) mmToPx(value) else value.roundToInt()
        return px.takeIf { it in MIN_PX..MAX_PX }
    }
}

/**
 * The library, a studio shelf: the wordmark and count, a search and an order, the New card with its page sizes, and
 * the drawings as cards in pages. Each card has its menu; Select picks several to export or delete together.
 */
@Composable
fun LibraryScreen(onOpen: (String) -> Unit, onDiagnostics: () -> Unit, onAbout: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val store = remember { DrawingStore.get(context) }
    val scope = rememberCoroutineScope()
    val log = remember { Logbook.logger("nib.ui") }
    var refresh by remember { mutableIntStateOf(0) }
    // Saving continues for a moment after the editor closes; the list reads itself again as each save lands.
    val saved by store.changes.collectAsState()
    val drawings by produceState<List<DrawingInfo>?>(null, refresh, saved) { value = withContext(Dispatchers.IO) { store.list() } }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortKey by rememberSaveable { mutableStateOf(LibrarySort.Recent.key) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<String>() }
    var menuFor by rememberSaveable { mutableStateOf<String?>(null) }
    var entry by remember { mutableStateOf<EntryRequest?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val panels = remember { PanelState(PanelMemory(context.getSharedPreferences("nib.panels", android.content.Context.MODE_PRIVATE))) }
    val untitled = stringResource(R.string.untitled)
    val sort = LibrarySort.of(sortKey)
    LaunchedEffect(message) {
        if (message != null) {
            delay(2600)
            message = null
        }
    }

    fun create(w: Int, h: Int) {
        panels.close("new")
        scope.launch {
            val name = context.getString(R.string.new_name, (drawings?.size ?: 0) + 1)
            val info = withContext(Dispatchers.IO) { store.create(name, w, h, context.getString(R.string.layer_name, 1)) }
            log.i("new drawing", "width" to w, "height" to h)
            onOpen(info.id)
        }
    }

    fun exportAll(ids: List<String>) {
        message = context.getString(R.string.message_exporting)
        scope.launch {
            var done = 0
            for (id in ids) {
                val ok = withContext(Dispatchers.IO) {
                    val opened = store.open(id) ?: return@withContext false
                    opened.journal.close()
                    Exporter.saveToGallery(context, opened.document, opened.info.name.ifBlank { untitled }, opened.info.paper) != null
                }
                if (ok) done++
            }
            message = resources.getQuantityString(R.plurals.message_exported_count, done, done)
        }
    }

    Column(Modifier.fillMaxSize().background(Studio.Desk).deskGrid()) {
        StatusStrip()
        BoxWithConstraints(Modifier.fillMaxSize().panelOrigin(panels)) {
            val list = drawings
            val shown = list?.let { sort.apply(it, query, untitled) }
            val wide = maxWidth > 900.dp
            Column(Modifier.fillMaxSize().padding(horizontal = Studio.S5, vertical = Studio.S4)) {
                // The shelf's header: the wordmark with the count, and the keys for everything that isn't a drawing.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_name), style = StudioType.Wordmark)
                        Text(
                            if (list == null) stringResource(R.string.library_reading) else pluralStringResource(R.plurals.library_count, list.size, list.size),
                            style = StudioType.Label, color = Studio.Legend,
                        )
                    }
                    Pill {
                        StudioKey(StudioGlyphs.Settings, stringResource(R.string.menu_settings), onClick = onSettings)
                        StudioKey(StudioGlyphs.Diagnostics, stringResource(R.string.title_diagnostics), onClick = onDiagnostics)
                        StudioKey(StudioGlyphs.About, stringResource(R.string.title_about), onClick = onAbout)
                    }
                }
                Spacer(Modifier.height(Studio.S4))
                if (selecting) {
                    SelectBar(
                        picked.size,
                        onExport = { exportAll(picked.toList()) },
                        onDelete = {
                            val ids = picked.toList()
                            scope.launch {
                                withContext(Dispatchers.IO) { ids.forEach { store.delete(it) } }
                                picked.clear()
                                selecting = false
                                refresh++
                            }
                        },
                        onDone = {
                            picked.clear()
                            selecting = false
                        },
                    )
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Studio.S3)) {
                        SearchField(query, { query = it; page = 0 }, Modifier.weight(1f))
                        Segmented(
                            LibrarySort.entries.map { stringResource(sortName(it)) }, LibrarySort.entries.indexOf(sort),
                            onSelect = { sortKey = LibrarySort.entries[it].key; page = 0 },
                            modifier = Modifier.width(if (wide) 330.dp else 280.dp),
                            descriptions = LibrarySort.entries.map { stringResource(R.string.sort_by, stringResource(sortName(it))) },
                        )
                        SlabIconKey(StudioGlyphs.Select, stringResource(R.string.library_select), onClick = { selecting = true }, enabled = !list.isNullOrEmpty())
                    }
                }
                Spacer(Modifier.height(Studio.S5))
                when {
                    shown == null -> Unit
                    list.isEmpty() -> EmptyShelf(onNew = { panels.show("new") }, Modifier.weight(1f).fillMaxWidth())
                    else -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val cols = LibraryLayout.columns(maxWidth.value)
                        val rows = LibraryLayout.rows(maxHeight.value - 76f)
                        val perPage = cols * rows
                        // The New card takes the first place on the first page.
                        val cells: List<DrawingInfo?> = if (selecting || query.isNotBlank()) shown else listOf<DrawingInfo?>(null) + shown
                        val pages = LibraryLayout.pages(cells.size, perPage)
                        LaunchedEffect(pages) { if (page >= pages) page = pages - 1 }
                        val p = page.coerceIn(0, pages - 1)
                        val onPage = cells.drop(p * perPage).take(perPage)
                        Column(Modifier.fillMaxSize()) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Studio.S5)) {
                                for (r in 0 until rows) {
                                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Studio.S5)) {
                                        for (c in 0 until cols) {
                                            val i = r * cols + c
                                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                                if (i < onPage.size) {
                                                    val d = onPage[i]
                                                    if (d == null) {
                                                        NewCard(onClick = { panels.show("new") }, modifier = Modifier.panelAnchor(panels, "new"))
                                                    } else {
                                                        DrawingCard(
                                                            d, store, selecting, d.id in picked,
                                                            onOpen = {
                                                                if (selecting) {
                                                                    if (d.id in picked) picked.remove(d.id) else picked.add(d.id)
                                                                } else {
                                                                    onOpen(d.id)
                                                                }
                                                            },
                                                            onMenu = {
                                                                menuFor = d.id
                                                                panels.show("card")
                                                            },
                                                            modifier = if (menuFor == d.id) Modifier.panelAnchor(panels, "card") else Modifier,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (pages > 1) {
                                StudioPager(
                                    p, pages, onPage = { page = it },
                                    previousLabel = stringResource(R.string.pager_previous), nextLabel = stringResource(R.string.pager_next),
                                    pageText = stringResource(R.string.pager_page, p + 1, pages),
                                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = Studio.S4),
                                )
                            }
                        }
                    }
                }
            }
            val landscape = maxWidth > maxHeight
            if (panels.isOpen("new")) NewDrawingCard(landscape, panels, onCreate = { w, h -> create(w, h) }, type = { entry = it })
            val target = list?.firstOrNull { it.id == menuFor }
            if (target != null && panels.isOpen("card")) {
                CardMenu(
                    target, panels, landscape,
                    onOpen = {
                        panels.close("card")
                        onOpen(target.id)
                    },
                    onRename = {
                        panels.close("card")
                        entry = EntryRequest(context.getString(R.string.action_rename), target.name, numeric = false) { text ->
                            val t = text.trim()
                            if (t.isEmpty()) return@EntryRequest context.getString(R.string.entry_name_empty)
                            scope.launch {
                                withContext(Dispatchers.IO) { store.rename(target.id, t) }
                                refresh++
                            }
                            null
                        }
                    },
                    onDuplicate = {
                        panels.close("card")
                        scope.launch {
                            withContext(Dispatchers.IO) { store.duplicate(target.id, context.getString(R.string.copy_name, target.name.ifBlank { untitled })) }
                            refresh++
                        }
                    },
                    onExport = {
                        panels.close("card")
                        exportAll(listOf(target.id))
                    },
                    onDelete = {
                        panels.close("card")
                        scope.launch {
                            withContext(Dispatchers.IO) { store.delete(target.id) }
                            refresh++
                        }
                    },
                )
            }
            message?.let { Toast(it, Modifier.align(Alignment.TopCenter).padding(top = 120.dp).widthIn(max = 560.dp)) }
            entry?.let { r ->
                EntryBar(r, stringResource(R.string.action_set), stringResource(R.string.action_cancel), onDone = { entry = null }, shields = null, modifier = Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

private fun sortName(s: LibrarySort): Int = when (s) {
    LibrarySort.Recent -> R.string.sort_recent
    LibrarySort.Name -> R.string.sort_name
    LibrarySort.Size -> R.string.sort_size
}

/** The search field: a slab at the top of the shelf, so the keyboard never covers it. */
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.library_search)
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .padding(end = Studio.ShadowKey, bottom = Studio.ShadowKey)
            .heightIn(min = Studio.Target)
            .slab(radius = Studio.RadiusM, shadow = if (focused) 0.dp else Studio.ShadowKey, outlineWidth = if (focused) 3.dp else Studio.Outline)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(StudioGlyphs.Search, contentDescription = null, tint = Color.Black, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(label, style = StudioType.Body, color = Studio.Legend)
            BasicTextField(
                value = value,
                onValueChange = { onChange(it.take(60)) },
                singleLine = true,
                textStyle = StudioType.Body.copy(color = Color.Black),
                cursorBrush = SolidColor(Color.Black),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.semantics { contentDescription = label },
            )
        }
        if (value.isNotEmpty()) StudioKey(StudioGlyphs.Close, stringResource(R.string.library_search_clear), onClick = { onChange("") }, size = Studio.Target)
    }
}

@Composable
private fun SelectBar(count: Int, onExport: () -> Unit, onDelete: () -> Unit, onDone: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(end = Studio.ShadowSlab, bottom = Studio.ShadowSlab).slab(radius = Studio.RadiusL, shadow = Studio.ShadowSlab).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Studio.S2),
    ) {
        Lamp(count > 0, Modifier.padding(start = 8.dp))
        Text(pluralStringResource(R.plurals.library_selected, count, count), style = StudioType.Title, modifier = Modifier.weight(1f).padding(start = 4.dp))
        SlabButton(stringResource(R.string.library_export), onClick = onExport, enabled = count > 0, icon = StudioGlyphs.Export)
        ConfirmButton(stringResource(R.string.action_delete), stringResource(R.string.library_delete_confirm), onConfirm = onDelete, enabled = count > 0, icon = StudioGlyphs.Trash)
        SlabButton(stringResource(R.string.selection_done), onClick = onDone, kind = ButtonKind.Primary)
    }
}

/** The New card: first on the shelf, black, with a blank page and a plus on it. */
@Composable
private fun NewCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shadow = Studio.ShadowCard
    Column(
        modifier
            .fillMaxSize()
            .padding(end = shadow, bottom = shadow)
            .offset { if (pressed) IntOffset(shadow.roundToPx(), shadow.roundToPx()) else IntOffset.Zero }
            .slab(radius = Studio.RadiusL, shadow = if (pressed) 0.dp else shadow, fill = Color.Black)
            .clickable(source, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.new_title), onClick = onClick)
            .padding(16.dp),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxHeight(0.8f).aspectRatio(0.75f).slab(radius = 2.dp, shadow = 0.dp, outline = Color.White), contentAlignment = Alignment.Center) {
                Icon(StudioGlyphs.Plus, contentDescription = null, tint = Color.Black, modifier = Modifier.size(56.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.new_title), style = StudioType.Title, color = Color.White)
        Text(stringResource(R.string.new_detail), style = StudioType.Small, color = Studio.Desk)
    }
}

/** A drawing on the shelf: its page, its name, when it last changed and how many layers it has. */
@Composable
private fun DrawingCard(
    d: DrawingInfo,
    store: DrawingStore,
    selecting: Boolean,
    picked: Boolean,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(null, d.id, d.modified) { value = withContext(Dispatchers.IO) { store.thumbnail(d.id) } }
    val name = d.name.ifBlank { stringResource(R.string.untitled) }
    val shadow = Studio.ShadowCard
    val date = DateUtils.formatDateTime(context, d.modified, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME)
    val detail = if (d.layers > 0) pluralStringResource(R.plurals.card_detail, d.layers, date, d.layers) else date
    Column(
        modifier
            .fillMaxSize()
            .padding(end = shadow, bottom = shadow)
            .slab(radius = Studio.RadiusL, shadow = if (picked) 0.dp else shadow, outlineWidth = if (picked) 3.dp else Studio.Outline),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Studio.Wash)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = name, onClick = onOpen)
                .semantics {
                    contentDescription = name
                    if (selecting) selected = picked
                }
                .padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            val t = thumb
            val aspect = if (d.width > 0 && d.height > 0) d.width / d.height.toFloat() else 0.75f
            Box(Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = true).slab(radius = 0.dp, shadow = 4.dp, fill = Color(d.paper.colour), outlineWidth = 1.5.dp)) {
                if (t != null) {
                    Image(t.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium, modifier = Modifier.fillMaxSize())
                }
            }
            if (selecting) {
                Box(Modifier.align(Alignment.TopStart).size(36.dp).slab(radius = Studio.RadiusS, shadow = 0.dp, fill = if (picked) Color.Black else Color.White), contentAlignment = Alignment.Center) {
                    if (picked) Icon(StudioGlyphs.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(Studio.Outline).background(Color.Black))
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onOpen)) {
                Text(name, style = StudioType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = StudioType.Small, color = Studio.Legend, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!selecting) StudioKey(StudioGlyphs.Menu, stringResource(R.string.action_drawing_actions, name), onClick = onMenu, size = Studio.Target)
        }
    }
}

@Composable
private fun CardMenu(
    d: DrawingInfo,
    panels: PanelState,
    landscape: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    FloatingPanel(
        "card", d.name.ifBlank { stringResource(R.string.untitled) }, panels, null, landscape, 340.dp,
        pinLabel = "", unpinLabel = "", closeLabel = stringResource(R.string.action_close), modifier = Modifier.fillMaxSize(),
        pinnable = false, draggable = false,
    ) {
        CardBody {
            SlabButton(stringResource(R.string.action_open), onClick = onOpen, kind = ButtonKind.Primary, modifier = Modifier.fillMaxWidth())
            SlabButton(stringResource(R.string.action_rename), onClick = onRename, modifier = Modifier.fillMaxWidth())
            SlabButton(stringResource(R.string.action_duplicate), onClick = onDuplicate, icon = StudioGlyphs.Duplicate, modifier = Modifier.fillMaxWidth())
            SlabButton(stringResource(R.string.library_export_one), onClick = onExport, icon = StudioGlyphs.Export, modifier = Modifier.fillMaxWidth())
            ConfirmButton(stringResource(R.string.action_delete), stringResource(R.string.action_delete_confirm), onConfirm = onDelete, icon = StudioGlyphs.Trash, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun presetName(key: String): Int = when (key) {
    "panel_portrait" -> R.string.page_panel_portrait
    "panel_landscape" -> R.string.page_panel_landscape
    "a4" -> R.string.page_a4
    "a5" -> R.string.page_a5
    else -> R.string.page_square
}

/** The New card's panel: page sizes drawn at their shape, and a custom size in pixels or millimetres. */
@Composable
private fun NewDrawingCard(landscape: Boolean, panels: PanelState, onCreate: (Int, Int) -> Unit, type: (EntryRequest) -> Unit) {
    val context = LocalContext.current
    val portrait = remember { NibPen.panelPortrait(context) }
    val presets = remember(portrait) { PagePresets.all(portrait.first, portrait.second) }
    var mm by rememberSaveable { mutableStateOf(false) }
    var customW by rememberSaveable { mutableIntStateOf(portrait.first) }
    var customH by rememberSaveable { mutableIntStateOf(portrait.second) }

    FloatingPanel(
        "new", stringResource(R.string.new_title), panels, null, landscape, 680.dp,
        pinLabel = "", unpinLabel = "", closeLabel = stringResource(R.string.action_close), modifier = Modifier.fillMaxSize(),
        pinnable = false, draggable = false,
    ) {
        CardBody {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (p in presets) {
                    PageTile(stringResource(presetName(p.key)), p.width, p.height, Modifier.weight(1f)) { onCreate(p.width, p.height) }
                }
            }
            Box(Modifier.fillMaxWidth().height(Studio.Hairline).background(Studio.Faint))
            SectionLabel(stringResource(R.string.new_custom))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val unit = stringResource(if (mm) R.string.unit_mm else R.string.unit_px)
                fun ask(which: Int) {
                    val current = if (which == 0) customW else customH
                    val shownValue = if (mm) ValueScale.formatNumber(PagePresets.pxToMm(current), 1) else current.toString()
                    type(
                        EntryRequest(
                            context.getString(if (which == 0) R.string.new_width else R.string.new_height), shownValue, numeric = true,
                            hint = context.getString(R.string.new_custom_hint, PagePresets.MIN_PX, PagePresets.MAX_PX), suffix = unit,
                        ) { text ->
                            val v = text.trim().replace(',', '.').toFloatOrNull() ?: return@EntryRequest context.getString(R.string.entry_not_a_number)
                            val px = PagePresets.custom(v, mm) ?: return@EntryRequest context.getString(R.string.new_custom_out_of_range, PagePresets.MIN_PX, PagePresets.MAX_PX)
                            if (which == 0) customW = px else customH = px
                            null
                        },
                    )
                }
                val wText = if (mm) ValueScale.formatNumber(PagePresets.pxToMm(customW), 1) else customW.toString()
                val hText = if (mm) ValueScale.formatNumber(PagePresets.pxToMm(customH), 1) else customH.toString()
                ValueChip("$wText $unit", onClick = { ask(0) }, description = stringResource(R.string.new_width_type, "$wText $unit"))
                Text("×", style = StudioType.Title)
                ValueChip("$hText $unit", onClick = { ask(1) }, description = stringResource(R.string.new_height_type, "$hText $unit"))
                Segmented(listOf(stringResource(R.string.unit_px), stringResource(R.string.unit_mm)), if (mm) 1 else 0, onSelect = { mm = it == 1 }, modifier = Modifier.width(140.dp))
                Spacer(Modifier.weight(1f))
                SlabButton(stringResource(R.string.new_create), onClick = { onCreate(customW, customH) }, kind = ButtonKind.Primary)
            }
        }
    }
}

/** A page size drawn at its own shape, with its name and size. */
@Composable
private fun PageTile(name: String, w: Int, h: Int, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shadow = Studio.ShadowKey
    Column(
        modifier
            .padding(end = shadow, bottom = shadow)
            .offset { if (pressed) IntOffset(shadow.roundToPx(), shadow.roundToPx()) else IntOffset.Zero }
            .slab(radius = Studio.RadiusM, shadow = if (pressed) 0.dp else shadow)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$name, $w × $h" }
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.fillMaxWidth().height(84.dp)) {
            val s = minOf(size.width / w, size.height / h) * 0.86f
            val pw = w * s
            val ph = h * s
            val left = (size.width - pw) / 2f
            val top = (size.height - ph) / 2f
            val sh = 3.dp.toPx()
            drawRect(Color.Black, topLeft = Offset(left + sh, top + sh), size = Size(pw, ph))
            drawRect(Color.White, topLeft = Offset(left, top), size = Size(pw, ph))
            drawRect(Color.Black, topLeft = Offset(left, top), size = Size(pw, ph), style = Stroke(Studio.Outline.toPx()))
        }
        Spacer(Modifier.height(6.dp))
        Text(name, style = StudioType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$w × $h", style = StudioType.Badge, color = Studio.Legend, maxLines = 1)
    }
}

/**
 * The empty shelf: a page with strokes the engine itself draws (a highlighter sweep, pencil hatching and a fountain
 * loop), and the way to start.
 */
@Composable
private fun EmptyShelf(onNew: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(end = Studio.ShadowCard, bottom = Studio.ShadowCard).slab(radius = Studio.RadiusL, shadow = Studio.ShadowCard),
        ) {
            Box(Modifier.fillMaxWidth().height(260.dp).background(Color.White)) {
                EnginePicture("empty shelf", Modifier.fillMaxSize()) { c, sink, w, h -> drawEmptyShelf(c, sink, w.toFloat(), h.toFloat()) }
            }
            Box(Modifier.fillMaxWidth().height(Studio.Outline).background(Color.Black))
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.library_empty_title), style = StudioType.Heading)
                Text(stringResource(R.string.library_empty), style = StudioType.Body, color = Studio.Legend)
                Spacer(Modifier.height(4.dp))
                Row {
                    SlabButton(stringResource(R.string.new_title), onClick = onNew, kind = ButtonKind.Primary, icon = StudioGlyphs.Plus)
                }
            }
        }
    }
}

private fun drawEmptyShelf(c: android.graphics.Canvas, sink: CanvasSink, w: Float, h: Float) {
    val s = sink.on(c)
    var id = 1L
    fun stroke(kind: BrushKind, width: Float, color: Int, points: List<Triple<Float, Float, Float>>) {
        val b = StrokeBuilder(BrushSpec.defaults(kind).copy(width = width), color, id++)
        points.forEachIndexed { i, (x, y, p) -> b.add(InputSample(x, y, p, timeNanos = i * 8_000_000L)) }
        StrokeRenderer.render(b.finish(), s, StrokeRenderer.DEFAULT_TOLERANCE)
    }
    // A highlighter sweep under everything.
    stroke(BrushKind.Highlighter, h * 0.16f, 0xFFF2C300.toInt(), (0..30).map { i -> Triple(w * (0.12f + 0.62f * i / 30f), h * (0.62f - 0.05f * sin(i / 30f * PI.toFloat())), 1f) })
    // Pencil hatching at the right.
    for (k in 0 until 7) {
        val x0 = w * (0.62f + 0.035f * k)
        stroke(BrushKind.Pencil, 3f, 0xFF404040.toInt(), (0..12).map { i -> Triple(x0 + i * w * 0.004f, h * (0.28f + 0.045f * i), 0.4f + 0.5f * sin(i / 12f * PI.toFloat())) })
    }
    // A fountain-pen loop across the page, pressure rising and falling.
    stroke(BrushKind.Fountain, 7f, 0xFF000000.toInt(), (0..90).map { i ->
        val t = i / 90f
        val a = t * 2.4f * PI.toFloat()
        Triple(w * (0.14f + 0.52f * t) - w * 0.1f * cos(a), h * 0.46f + h * 0.2f * sin(a), 0.15f + 0.85f * sin(t * PI.toFloat()))
    })
}
