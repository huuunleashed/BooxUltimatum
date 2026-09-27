package app.booxultimatum.launcher

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import app.booxultimatum.R
import app.booxultimatum.ui.Key
import app.booxultimatum.ui.IconKey
import app.booxultimatum.ui.Lamp
import app.booxultimatum.ui.Paragraph
import app.booxultimatum.ui.Plate
import app.booxultimatum.ui.SearchField
import app.booxultimatum.ui.StatusStrip
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.booxultimatum.ui.theme.CodeStyle
import app.booxultimatum.ui.theme.Ink
import app.booxultimatum.ui.theme.Lines
import app.booxultimatum.ui.theme.Space
import java.util.Locale
import kotlin.math.max

@Composable
fun LauncherScreen(
    model: LauncherModel,
    onRequestCalendar: () -> Unit,
    onAddSystemWidget: (android.appwidget.AppWidgetProviderInfo) -> Unit,
    onOpenApp: () -> Unit,
    onOpenAppearance: () -> Unit,
    onWindowChanged: () -> Unit,
) {
    val overlay by model.overlay
    val selected by model.selected
    val openFolder by model.openFolder
    val chooser by model.folderChooser
    val prefs by model.prefs
    val entry by model.entry
    fun closeEntry() { model.entry.value = null; model.visibleKey.intValue++ }
    // Back never leaves home: it only closes what is open, innermost first.
    BackHandler {
        when {
            entry != null -> closeEntry()
            chooser != null -> model.folderChooser.value = null
            selected != null -> model.selected.value = null
            openFolder != null -> model.openFolder.value = null
            overlay == Overlay.WidgetPicker -> model.overlay.value = Overlay.Edit
            overlay != Overlay.None -> model.overlay.value = Overlay.None
        }
    }
    val wall = model.wallpaper.value
    Box(Modifier.fillMaxSize().background(if (prefs.wallMode == WallMode.System) androidx.compose.ui.graphics.Color.Transparent else Ink.Paper)) {
        if (overlay == Overlay.None && prefs.wallMode != WallMode.Paper) {
            if (prefs.wallMode == WallMode.Image && wall != null) {
                Image(wall.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            // A paper veil keeps text and icons legible over any picture.
            Box(Modifier.fillMaxSize().background(Ink.Paper.copy(alpha = prefs.wallDim / 100f)))
        }
        // Home ignores the keyboard, so typing in a panel never reflows the page, drops a widget, or repaints the whole
        // screen; the panels that take text lift themselves above it instead. Full-screen overlays still make room for it.
        val insets = if (overlay == Overlay.None) WindowInsets.systemBars.union(WindowInsets.displayCutout) else WindowInsets.safeDrawing
        Box(Modifier.fillMaxSize().then(if (overlay == Overlay.None) Modifier else Modifier.background(Ink.Paper)).windowInsetsPadding(insets)) {
            when (overlay) {
                Overlay.None -> androidx.compose.runtime.CompositionLocalProvider(LocalLabelInk provides labelInkFor(prefs.wallMode, model.backdropLum.value)) {
                    Home(model, onRequestCalendar, onOpenApp)
                }
                Overlay.Drawer -> Drawer(model)
                Overlay.Edit -> EditHome(model, onOpenApp, onOpenAppearance, onWindowChanged)
                Overlay.WidgetPicker -> WidgetPicker(model, onAddSystemWidget)
            }
            if (openFolder != null || selected != null || chooser != null || entry != null) {
                // A paper veil quiets what is behind the panel; tapping it closes the panel, as on any sheet.
                Box(
                    Modifier.fillMaxSize().background(Ink.Paper.copy(alpha = 0.72f)).clickable(interactionSource = null, indication = null) {
                        when {
                            entry != null -> closeEntry()
                            chooser != null -> model.folderChooser.value = null
                            selected != null -> model.selected.value = null
                            else -> model.openFolder.value = null
                        }
                    },
                )
            }
            openFolder?.let { id -> prefs.folders[id]?.let { FolderPanel(model, it, Modifier.align(Alignment.BottomCenter)) } }
            selected?.let { AppActions(model, it, Modifier.align(Alignment.BottomCenter)) }
            chooser?.let { FolderChooser(model, it, Modifier.align(Alignment.BottomCenter)) }
            entry?.let { e ->
                TopPanel(Modifier.align(Alignment.TopCenter)) {
                    when (e) {
                        Entry.City -> CityPicker(model.weather) { closeEntry() }
                        Entry.Note -> NoteEditor(model.widgetStore) { closeEntry() }
                    }
                }
            }
        }
        StatusStrip(Modifier.align(Alignment.TopStart))
    }
}

/**
 * Ink for text set straight on the backdrop (tile labels, header, footer). On a dark picture it turns white with a
 * dark halo; on paper it stays black. A soft halo in the paper colour sits under dark ink too, so labels lift off
 * a busy wallpaper either way.
 */
data class LabelInk(val color: androidx.compose.ui.graphics.Color, val halo: androidx.compose.ui.graphics.Shadow?)

val LocalLabelInk = androidx.compose.runtime.staticCompositionLocalOf { LabelInk(Ink.Black, null) }

private fun labelInkFor(wall: WallMode, lum: Float): LabelInk = when {
    wall == WallMode.Paper -> LabelInk(Ink.Black, null)
    lum < 0.5f -> LabelInk(Ink.Paper, androidx.compose.ui.graphics.Shadow(Ink.Black.copy(alpha = 0.75f), androidx.compose.ui.geometry.Offset(0f, 2f), 10f))
    else -> LabelInk(Ink.Black, androidx.compose.ui.graphics.Shadow(Ink.Paper.copy(alpha = 0.9f), androidx.compose.ui.geometry.Offset(0f, 2f), 10f))
}

/** One item in the home grid. */
private sealed interface HomeItem {
    val key: String
    data class App(val t: LaunchTarget) : HomeItem { override val key get() = t.key }
    data class Group(val folder: Folder, val apps: List<LaunchTarget>) : HomeItem { override val key get() = FOLDER_PREFIX + folder.id }
}

@Composable
private fun Home(model: LauncherModel, onRequestCalendar: () -> Unit, onOpenApp: () -> Unit) {
    val prefs by model.prefs
    val apps by model.apps
    val byKey = remember(apps) { apps.associateBy { it.key } }
    val items = prefs.favourites.mapNotNull { k ->
        if (k.startsWith(FOLDER_PREFIX)) prefs.folders[k.removePrefix(FOLDER_PREFIX)]?.let { f ->
            HomeItem.Group(f, f.apps.mapNotNull { byKey[it] }.filter { it.key !in prefs.hidden })
        }
        else byKey[k]?.takeIf { it.key !in prefs.hidden }?.let { HomeItem.App(it) }
    }

    val widgets by model.widgets
    Column(Modifier.fillMaxSize().padding(horizontal = Space.xl, vertical = Space.l)) {
        Header(model, onOpenApp)
        Spacer(Modifier.height(Space.l))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val bodyH = maxHeight
            val bodyW = maxWidth
            Box(Modifier.fillMaxSize()) {
                // Landscape puts widgets and apps side by side: stacked, the widgets would leave the apps a single row.
                if (bodyW > bodyH && widgets.isNotEmpty()) {
                    Row(Modifier.fillMaxSize()) {
                        WidgetShelf(model, fitShelf(model, bodyH), onRequestCalendar, onOpenApp, Modifier.weight(1.1f))
                        Spacer(Modifier.width(Space.xl))
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            AppPages(model, items, Modifier.weight(1f).fillMaxWidth())
                            HomeFooter(model, items.size)
                        }
                    }
                } else {
                    val cols = gridColumns(prefs.columns, bodyW)
                    val cellH = cellHeight(iconSize(bodyW / cols), prefs.labels)
                    // Widgets take what is left after one row of apps and the footer, so neither is ever pushed off the screen.
                    val shelf = fitShelf(model, bodyH - cellH - FooterHeight - Space.l)
                    Column(Modifier.fillMaxSize()) {
                        if (shelf.isNotEmpty()) {
                            WidgetShelf(model, shelf, onRequestCalendar, onOpenApp, Modifier.fillMaxWidth())
                            Spacer(Modifier.height(Space.l))
                        }
                        AppPages(model, items, Modifier.weight(1f).fillMaxWidth())
                        HomeFooter(model, items.size)
                    }
                }
            }
        }
    }
}

private val FooterHeight = 72.dp

/** Columns as set, but never so many that a cell gets narrower than a comfortable touch target and label. */
private fun gridColumns(wanted: Int, width: Dp): Int = minOf(wanted, max(3, (width / 92.dp).toInt()))

private fun cellHeight(icon: Dp, labels: Boolean): Dp = icon + if (labels) 58.dp else 28.dp

@Composable
private fun AppPages(model: LauncherModel, items: List<HomeItem>, modifier: Modifier) {
    val prefs by model.prefs
    BoxWithConstraints(modifier) {
        val cols = gridColumns(prefs.columns, maxWidth)
        val icon = iconSize(maxWidth / cols)
        val cellH = cellHeight(icon, prefs.labels)
        val rows = max(1, minOf(prefs.rows, (maxHeight / cellH).toInt()))
        val perPage = cols * rows
        val pages = max(1, (items.size + perPage - 1) / perPage)
        if (model.pages.intValue != pages) model.pages.intValue = pages
        val page = model.page.intValue.coerceIn(0, pages - 1)
        if (model.page.intValue != page) model.page.intValue = page
        if (items.isEmpty()) Paragraph(stringResource(R.string.l_empty_home), color = Ink.Legend)
        else Box(Modifier.fillMaxSize().swipePages(page, pages) { model.page.intValue = it }) {
            Grid(model, items.drop(page * perPage).take(perPage), cols, icon, cellH)
        }
    }
}

/**
 * A horizontal swipe turns the page: leftwards for the next one, rightwards for the previous one. The page flips once
 * the finger lifts, in one repaint, rather than following it, which on e-ink would only smear.
 */
private fun Modifier.swipePages(page: Int, pages: Int, onPage: (Int) -> Unit): Modifier = if (pages <= 1) this else pointerInput(page, pages) {
    val threshold = 64.dp.toPx()
    var dx = 0f
    detectHorizontalDragGestures(
        onDragStart = { dx = 0f },
        onDragEnd = {
            when {
                dx <= -threshold && page < pages - 1 -> onPage(page + 1)
                dx >= threshold && page > 0 -> onPage(page - 1)
            }
        },
        onHorizontalDrag = { change, amount -> change.consume(); dx += amount },
    )
}

@Composable
private fun HomeFooter(model: LauncherModel, pinned: Int) {
    Row(Modifier.padding(top = Space.m).fillMaxWidth().onBackdrop(model), verticalAlignment = Alignment.CenterVertically) {
        val pages = model.pages.intValue
        IconKey(app.booxultimatum.ui.Glyphs.ChevronLeft, stringResource(R.string.action_previous), onClick = { model.page.intValue = (model.page.intValue - 1).coerceAtLeast(0) }, enabled = model.page.intValue > 0)
        val off = model.widgetsOffscreen.intValue
        Column(Modifier.weight(1f).padding(horizontal = Space.s), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.l_page_of, model.page.intValue + 1, pages) + "  ·  " + stringResource(R.string.l_pinned, pinned),
                style = MaterialTheme.typography.labelLarge, color = Ink.Legend, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (off > 0) Text(
                pluralStringResource(R.plurals.l_widgets_hidden_short, off, off),
                style = MaterialTheme.typography.labelSmall, color = Ink.Legend, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconKey(app.booxultimatum.ui.Glyphs.ChevronRight, stringResource(R.string.action_next), onClick = { model.page.intValue = (model.page.intValue + 1).coerceAtMost(pages - 1) }, enabled = model.page.intValue < pages - 1)
        Spacer(Modifier.size(Space.m))
        IconKey(app.booxultimatum.ui.Glyphs.AllApps, stringResource(R.string.l_all_apps), primary = true, onClick = { model.overlay.value = Overlay.Drawer })
    }
}

/** Date, connectivity and battery. Each indicator opens the matching system control. */
@Composable
private fun Header(model: LauncherModel, onOpenApp: () -> Unit) {
    val context = LocalContext.current
    val now by model.now
    val battery by model.battery
    val ind by model.indicators
    fun open(action: String) = runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    val p = model.prefs.value
    Row(Modifier.onBackdrop(model), verticalAlignment = Alignment.CenterVertically) {
        // Boox blanks the status bar's icons over non-Boox apps, so the header carries the time as well.
        if (p.showTime) {
            Text(android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(now)), style = MaterialTheme.typography.titleLarge)
            if (p.showDate) Text("  ·  ", style = MaterialTheme.typography.titleLarge, color = Ink.Legend)
        }
        Text(
            if (p.showDate) android.text.format.DateFormat.format(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), now).toString() else "",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val style = p.indicatorStyle
        if (p.showWifi) {
            val wifiLabel = when {
                ind.wifiConnected && p.showWifiName && ind.ssid != null -> ind.ssid!!
                ind.wifiConnected -> stringResource(R.string.ind_wifi_connected)
                ind.wifiOn -> stringResource(R.string.ind_wifi_on)
                else -> stringResource(R.string.ind_wifi_off)
            }
            // A network name is information, so it shows beside the symbol in every style.
            IndicatorItem(style, wifiLabel, ind.wifiConnected, glyph = { StatusGlyphs.Wifi(ind.wifiOn, ind.wifiConnected, ind.wifiLevel, p.wifiGlyph) }, alwaysText = p.showWifiName && ind.ssid != null && ind.wifiConnected) {
                open(Settings.Panel.ACTION_WIFI)
            }
        }
        if (p.showBluetooth) IndicatorItem(style, stringResource(if (ind.bluetooth) R.string.ind_bt_on else R.string.ind_bt_off), ind.bluetooth, glyph = { StatusGlyphs.Bluetooth(ind.bluetooth) }) {
            open(Settings.ACTION_BLUETOOTH_SETTINGS)
        }
        if (ind.airplane) IndicatorItem(style, stringResource(R.string.ind_airplane), true, glyph = { StatusGlyphs.Airplane() }) { open(Settings.ACTION_AIRPLANE_MODE_SETTINGS) }
        if (ind.dnd) IndicatorItem(style, stringResource(R.string.ind_dnd), true, glyph = { StatusGlyphs.Dnd() }) { open("android.settings.ZEN_MODE_SETTINGS") }
        battery?.let { b ->
            IndicatorItem(style, "${b.levelPct} %", b.charging, glyph = { StatusGlyphs.Battery(b.levelPct, b.charging, p.batteryGlyph) }, alwaysText = p.showBatteryPct) { open(Intent.ACTION_POWER_USAGE_SUMMARY) }
        }
        Spacer(Modifier.size(Space.s))
        IconKey(app.booxultimatum.ui.Glyphs.Gear, stringResource(R.string.l_settings_boox), onClick = { BooxIntents.openSettings(context) })
        Spacer(Modifier.size(Space.s))
        IconKey(app.booxultimatum.ui.Glyphs.Mark, stringResource(R.string.l_open_hub), onClick = onOpenApp)
        Spacer(Modifier.size(Space.s))
        IconKey(app.booxultimatum.ui.Glyphs.Pencil, stringResource(R.string.l_edit_home), onClick = { model.overlay.value = Overlay.Edit })
    }
}

/** One header indicator in the chosen style. Battery keeps its figure in every style: a gauge alone is too coarse. */
@Composable
private fun IndicatorItem(style: IndicatorStyle, label: String, on: Boolean, glyph: @Composable () -> Unit, alwaysText: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClickLabel = label, onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 8.dp)
            .androidxSemantics(label),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (style) {
            IndicatorStyle.Text -> { Lamp(on, size = 12.dp); Spacer(Modifier.size(6.dp)); Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
            IndicatorStyle.Glyphs -> { glyph(); if (alwaysText) { Spacer(Modifier.size(6.dp)); Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) } }
            IndicatorStyle.GlyphsAndText -> { glyph(); Spacer(Modifier.size(6.dp)); Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
        }
    }
}

private fun Modifier.androidxSemantics(label: String) = this.then(
    Modifier.semantics(mergeDescendants = true) { contentDescription = label },
)

/** Labels one step heavier than body text: thin system faces vanish at 14 sp on e-ink. */
private val TileLabel: androidx.compose.ui.text.TextStyle
    @Composable get() = LocalLabelInk.current.let { ink -> MaterialTheme.typography.bodySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = ink.color, shadow = ink.halo, textAlign = TextAlign.Center) }

/**
 * A tile's name. Names of several words wrap onto two lines; a single long word ("BooxUltimatum") would be cut in the
 * middle, so it stays on one line and steps its size down until it fits.
 */
@Composable
private fun TileName(name: String) {
    val style = TileLabel
    if (name.any { it.isWhitespace() }) Text(name, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
    else androidx.compose.foundation.text.BasicText(
        name, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
}

/** Over a wallpaper, the header and footer sit on a paper plate like the widgets, so their glyphs and keys stay legible. */
@Composable
private fun Modifier.onBackdrop(model: LauncherModel): Modifier =
    if (model.prefs.value.wallMode == WallMode.Paper) this
    else this.clip(RoundedCornerShape(22.dp)).background(Ink.Paper).padding(horizontal = Space.m, vertical = Space.xs)

private fun iconSize(cellW: Dp): Dp = (cellW * 0.52f).coerceIn(44.dp, 76.dp)

@Composable
private fun Grid(model: LauncherModel, items: List<HomeItem>, cols: Int, icon: Dp, cellH: Dp) {
    Column(Modifier.fillMaxWidth()) {
        items.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth().height(cellH)) {
                row.forEach { item ->
                    when (item) {
                        is HomeItem.App -> AppTile(model, item.t, icon, Modifier.weight(1f))
                        is HomeItem.Group -> FolderTile(model, item, icon, Modifier.weight(1f))
                    }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AppIcon(model: LauncherModel, t: LaunchTarget, size: Dp) {
    val prefs by model.prefs
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bmp = remember(t.key, px, prefs.iconStyle, prefs.iconShape, model.apps.value) {
        model.catalog.icon(t.key, px, prefs.iconStyle, prefs.iconShape)?.asImageBitmap()
    }
    if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.size(size))
    else Box(Modifier.size(size).border(Lines.rim, Ink.Black, RoundedCornerShape(14.dp)))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(model: LauncherModel, t: LaunchTarget, icon: Dp, modifier: Modifier) {
    val prefs by model.prefs
    Column(
        modifier.clip(RoundedCornerShape(16.dp))
            .combinedClickable(role = Role.Button, onClickLabel = t.label, onLongClick = { model.selected.value = t }, onClick = { if (!model.catalog.launch(t)) model.selected.value = t })
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(model, t, icon)
        if (prefs.labels) {
            Spacer(Modifier.height(6.dp))
            TileName(t.label)
        }
    }
}

/** A folder: up to four of its apps in a rimmed square, and its name. */
@Composable
private fun FolderTile(model: LauncherModel, g: HomeItem.Group, icon: Dp, modifier: Modifier) {
    val prefs by model.prefs
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { model.openFolder.value = g.folder.id }.padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(icon).clip(RoundedCornerShape(icon * 0.22f)).background(Ink.Paper).border(Lines.rim, Ink.Black, RoundedCornerShape(icon * 0.22f)).padding(icon * 0.1f), contentAlignment = Alignment.Center) {
            if (g.apps.size == 1) {
                AppIcon(model, g.apps[0], icon * 0.6f)
            } else {
                val mini = icon * 0.36f
                Column(verticalArrangement = Arrangement.spacedBy(icon * 0.06f)) {
                    g.apps.take(4).chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(icon * 0.06f)) { pair.forEach { AppIcon(model, it, mini) } }
                    }
                }
            }
        }
        if (prefs.labels) {
            Spacer(Modifier.height(6.dp))
            TileName(g.folder.name)
        }
    }
}

/** A sheet from the top edge, for typing: the keyboard rises from below and can never cover it. */
@Composable
private fun TopPanel(modifier: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
    Column(
        modifier.widthIn(max = 880.dp).fillMaxWidth().clip(shape).background(Ink.Paper).border(Lines.rim, Ink.Black, shape)
            .clickable(enabled = false) {}
            .padding(horizontal = Space.xl, vertical = Space.l),
    ) { content() }
}

@Composable
private fun BottomPanel(modifier: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Column(
        // Home leaves the keyboard alone, so a panel with a text field rises above it by itself.
        modifier.imePadding().widthIn(max = 880.dp).fillMaxWidth().clip(shape).background(Ink.Paper).border(Lines.rim, Ink.Black, shape)
            .clickable(enabled = false) {}
            .padding(horizontal = Space.xl, vertical = Space.l),
    ) { content() }
}

@Composable
private fun FolderPanel(model: LauncherModel, f: Folder, modifier: Modifier) {
    val apps by model.apps
    val byKey = remember(apps) { apps.associateBy { it.key } }
    val members = f.apps.mapNotNull { byKey[it] }
    var renaming by remember(f.id) { mutableStateOf(false) }
    var name by remember(f.id) { mutableStateOf(f.name) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Renaming should be one tap: focus the field and raise the keyboard straight away.
    LaunchedEffect(renaming) { if (renaming) runCatching { focus.requestFocus() } }
    BottomPanel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (renaming) {
                Box(Modifier.weight(1f).border(Lines.rim, Ink.Black, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    BasicTextField(
                        name, { name = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.headlineSmall.copy(color = Ink.Black), cursorBrush = SolidColor(Ink.Black),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { model.renameFolder(f.id, name); renaming = false }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                Spacer(Modifier.size(Space.s))
                Key(stringResource(R.string.l_save), primary = true, onClick = { model.renameFolder(f.id, name); renaming = false })
            } else {
                Text(f.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Key(stringResource(R.string.l_rename), onClick = { renaming = true })
                Spacer(Modifier.size(Space.s))
                Key(stringResource(R.string.l_close), onClick = { model.openFolder.value = null })
            }
        }
        Spacer(Modifier.height(Space.m))
        val cols = model.prefs.value.columns.coerceAtLeast(4)
        members.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { AppTile(model, it, 56.dp, Modifier.weight(1f)) }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(Space.s))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.l_folder_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.weight(1f))
            Key(stringResource(R.string.l_delete_folder), onClick = { model.deleteFolder(f.id); model.openFolder.value = null })
        }
    }
}

@Composable
private fun FolderChooser(model: LauncherModel, t: LaunchTarget, modifier: Modifier) {
    val prefs by model.prefs
    var newName by remember { mutableStateOf("") }
    BottomPanel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.l_add_to_folder_title, t.label), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Key(stringResource(R.string.l_close), onClick = { model.folderChooser.value = null })
        }
        Spacer(Modifier.height(Space.m))
        prefs.folders.values.sortedBy { it.name.lowercase() }.forEach { f ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(pluralStringResource(R.plurals.l_count, f.apps.size, f.apps.size), style = MaterialTheme.typography.labelMedium, color = Ink.Legend)
                Spacer(Modifier.size(Space.m))
                Key(stringResource(R.string.l_add), onClick = { model.addToFolder(t, f.id); model.folderChooser.value = null })
            }
            HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
        }
        Spacer(Modifier.height(Space.m))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).border(Lines.rim, Ink.Black, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (newName.isEmpty()) Text(stringResource(R.string.l_new_folder_hint), style = MaterialTheme.typography.bodyLarge, color = Ink.Legend)
                BasicTextField(newName, { newName = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.Black), cursorBrush = SolidColor(Ink.Black), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.size(Space.s))
            val fallback = stringResource(R.string.l_folder_default)
            Key(stringResource(R.string.l_new_folder), primary = true, onClick = { model.createFolderWith(t, newName.ifBlank { fallback }); model.folderChooser.value = null })
        }
    }
}

/** One row of the widget shelf and the height it takes. */
private class ShelfRow(val widgets: List<WidgetSpec>, val height: Dp)

/**
 * The widget rows that fit in [budget], top first. Rows that do not fit are skipped rather than ending the shelf, so
 * a smaller widget further down still shows; how many were left out is kept for Edit and the footer to mention.
 */
@Composable
private fun fitShelf(model: LauncherModel, budget: Dp): List<ShelfRow> {
    val widgets by model.widgets
    val rows = remember(widgets) { packRows(widgets) }
    val preferred = rows.map { row -> row.maxOf { heightOf(model, it) } }
    // Extra height is a wish, standard height a need: a row gives up its extra steps before it gives up its place.
    val least = rows.map { row -> row.maxOf { heightOf(model, it, allowTaller = false) } }
    var used = 0.dp
    val shown = mutableListOf<ShelfRow>()
    var off = 0
    rows.forEachIndexed { i, row ->
        val gap = if (used > 0.dp) Space.m else 0.dp
        val room = budget - used - gap
        val h = when {
            preferred[i] <= room -> preferred[i]
            least[i] <= room -> room
            else -> null
        }
        if (h == null) off += row.size else { shown += ShelfRow(row, h); used += gap + h }
    }
    if (model.widgetsOffscreen.intValue != off) model.widgetsOffscreen.intValue = off
    return shown
}

@Composable
private fun WidgetShelf(model: LauncherModel, rows: List<ShelfRow>, onRequestCalendar: () -> Unit, onOpenApp: () -> Unit, modifier: Modifier) {
    if (rows.isEmpty()) return
    BoxWithConstraints(modifier) {
        val full = maxWidth
        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
            rows.forEach { r ->
                val row = r.widgets
                val h = r.height
                Row(Modifier.fillMaxWidth().height(h), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    row.forEach { w ->
                        val width = if (w.span == 2) full else (full - Space.m) / 2
                        WidgetCell(model, w, Modifier.weight(w.span.toFloat()).height(h), width, h, onRequestCalendar, onOpenApp)
                    }
                    if (row.size == 1 && row[0].span == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private fun packRows(list: List<WidgetSpec>): List<List<WidgetSpec>> {
    val rows = mutableListOf<List<WidgetSpec>>()
    var pending: WidgetSpec? = null
    list.forEach { w ->
        if (w.span == 2) {
            pending?.let { rows += listOf(it); pending = null }
            rows += listOf(w)
        } else if (pending == null) pending = w else { rows += listOf(pending!!, w); pending = null }
    }
    pending?.let { rows += listOf(it) }
    return rows
}

@Composable
private fun heightOf(model: LauncherModel, w: WidgetSpec, allowTaller: Boolean = true): Dp {
    val base = if (w.kind != WidgetKind.System) widgetHeight(w.kind) else {
        val info = remember(w.appWidgetId) { model.widgetManager.getAppWidgetInfo(w.appWidgetId) }
        val density = LocalDensity.current
        val min = info?.let { with(density) { it.minHeight.toDp() } } ?: 0.dp
        // Providers state a bare minimum; many (Boox Library among them) crop their content at it.
        (min * 1.4f + 16.dp).coerceIn(140.dp, 520.dp)
    }
    val steps = if (allowTaller) w.extra else minOf(w.extra, 0)
    return (base + WidgetSpec.HEIGHT_STEP * steps).coerceIn(80.dp, 900.dp)
}

@Composable
private fun WidgetCell(model: LauncherModel, w: WidgetSpec, modifier: Modifier, width: Dp, height: Dp, onRequestCalendar: () -> Unit, onOpenApp: () -> Unit) {
    val now by model.now
    val wide = w.span == 2
    // Paper behind every widget, so each stays legible over a wallpaper.
    val m = modifier.clip(RoundedCornerShape(22.dp)).background(Ink.Paper)
    when (w.kind) {
        WidgetKind.Clock -> ClockWidget(now, wide, m)
        WidgetKind.Calendar -> CalendarWidget(now, m)
        WidgetKind.Agenda -> AgendaWidget(now, m, onRequestCalendar)
        WidgetKind.Weather -> WeatherWidget(model.weather, model.visibleKey.intValue, wide, m) { model.entry.value = Entry.City }
        WidgetKind.Battery -> BatteryWidget(model.battery.value, m, onOpenApp)
        WidgetKind.Alarm -> AlarmWidget(now, m)
        WidgetKind.Note -> NoteWidget(model.widgetStore, model.visibleKey.intValue, m) { model.entry.value = Entry.Note }
        WidgetKind.Boox -> BooxWidget(m)
        WidgetKind.System -> SystemWidget(model.host, w.appWidgetId, width.value.toInt(), height.value.toInt(), m)
    }
}

@Composable
private fun Drawer(model: LauncherModel) {
    val prefs by model.prefs
    val apps by model.apps
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val visible = apps.filter { it.key !in prefs.hidden }
    val q = query.trim()
    val matched = if (q.isEmpty()) visible else visible.filter { it.label.contains(q, true) || it.component.packageName.contains(q, true) }
    // Sort keys are read off the main thread: colour needs each icon, recency needs usage stats.
    val lastUsed by produceState<Map<String, Long>?>(null, prefs.drawerSort, apps) {
        value = if (prefs.drawerSort == DrawerSort.Recent) withContext(Dispatchers.IO) { recentUse(context) } else null
    }
    val colours by produceState<Map<String, Float>?>(null, prefs.drawerSort, apps) {
        value = if (prefs.drawerSort == DrawerSort.Colour) withContext(Dispatchers.Default) { apps.associate { it.key to model.catalog.colourKey(it.key) } } else null
    }
    fun usedAt(t: LaunchTarget) = lastUsed?.get(t.component.packageName) ?: 0L
    val list = when (prefs.drawerSort) {
        DrawerSort.Name -> matched
        DrawerSort.Recent -> lastUsed?.let { matched.sortedWith(compareByDescending<LaunchTarget> { usedAt(it) }.thenBy { it.label.lowercase() }) } ?: matched
        DrawerSort.Colour -> colours?.let { c -> matched.sortedWith(compareBy<LaunchTarget> { c[it.key] ?: 2f }.thenBy { it.label.lowercase() }) } ?: matched
    }
    Column(Modifier.fillMaxSize().padding(horizontal = Space.xl, vertical = Space.l)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.l_all_apps), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Text(pluralStringResource(R.plurals.l_count, list.size, list.size), style = MaterialTheme.typography.labelLarge, color = Ink.Legend)
            Spacer(Modifier.size(Space.m))
            IconKey(app.booxultimatum.ui.Glyphs.Close, stringResource(R.string.l_close), onClick = { model.overlay.value = Overlay.None })
        }
        Spacer(Modifier.height(Space.m))
        SearchField(query, { query = it; model.drawerPage.intValue = 0 }, stringResource(R.string.l_search))
        Spacer(Modifier.height(Space.m))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.l_sort), style = MaterialTheme.typography.labelLarge, color = Ink.Legend, modifier = Modifier.padding(end = Space.xs))
            listOf(DrawerSort.Name to R.string.l_sort_name, DrawerSort.Colour to R.string.l_sort_colour, DrawerSort.Recent to R.string.l_sort_recent).forEach { (s, label) ->
                Key(stringResource(label), primary = prefs.drawerSort == s, onClick = { model.update { it.copy(drawerSort = s) }; model.drawerPage.intValue = 0 })
            }
            Spacer(Modifier.width(Space.l))
            listOf(DrawerView.Grid to R.string.l_view_grid, DrawerView.List to R.string.l_view_list).forEach { (v, label) ->
                Key(stringResource(label), primary = prefs.drawerView == v, onClick = { model.update { it.copy(drawerView = v) }; model.drawerPage.intValue = 0 })
            }
        }
        Spacer(Modifier.height(Space.l))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val perPage: Int
            if (prefs.drawerView == DrawerView.List) {
                // Two columns of rows when the panel is wide, so landscape doesn't stretch a row across the screen.
                val cols = if (maxWidth > 900.dp) 2 else 1
                perPage = cols * max(1, (maxHeight / (ListRowHeight + Lines.hairline)).toInt())
            } else {
                val cols = gridColumns(prefs.columns, maxWidth)
                perPage = cols * max(1, (maxHeight / cellHeight(iconSize(maxWidth / cols), prefs.labels)).toInt())
            }
            val pages = max(1, (list.size + perPage - 1) / perPage)
            if (model.drawerPages.intValue != pages) model.drawerPages.intValue = pages
            val page = model.drawerPage.intValue.coerceIn(0, pages - 1)
            if (model.drawerPage.intValue != page) model.drawerPage.intValue = page
            val slice = list.drop(page * perPage).take(perPage)
            val swipe = Modifier.fillMaxSize().swipePages(page, pages) { model.drawerPage.intValue = it }
            val listCols = if (maxWidth > 900.dp) 2 else 1
            when {
                list.isEmpty() -> Paragraph(stringResource(R.string.apps_no_match, q), color = Ink.Legend)
                prefs.drawerView == DrawerView.List -> Box(swipe) { AppList(model, slice, listCols, if (prefs.drawerSort == DrawerSort.Recent) ::usedAt else null) }
                else -> {
                    val cols = gridColumns(prefs.columns, maxWidth)
                    val icon = iconSize(maxWidth / cols)
                    Box(swipe) { Grid(model, slice.map { HomeItem.App(it) }, cols, icon, cellHeight(icon, prefs.labels)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Space.m), verticalAlignment = Alignment.CenterVertically) {
            val pages = model.drawerPages.intValue
            IconKey(app.booxultimatum.ui.Glyphs.ChevronLeft, stringResource(R.string.action_previous), onClick = { model.drawerPage.intValue = (model.drawerPage.intValue - 1).coerceAtLeast(0) }, enabled = model.drawerPage.intValue > 0)
            Text(stringResource(R.string.l_page_of, model.drawerPage.intValue + 1, pages), style = MaterialTheme.typography.labelLarge, color = Ink.Legend, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            IconKey(app.booxultimatum.ui.Glyphs.ChevronRight, stringResource(R.string.action_next), onClick = { model.drawerPage.intValue = (model.drawerPage.intValue + 1).coerceAtMost(pages - 1) }, enabled = model.drawerPage.intValue < pages - 1)
        }
    }
}

private val ListRowHeight = 76.dp

/** The last time each package was in the foreground over the past 60 days, from Android's usage stats. */
private fun recentUse(context: android.content.Context): Map<String, Long> = runCatching {
    val usm = context.getSystemService(android.app.usage.UsageStatsManager::class.java)
    val now = System.currentTimeMillis()
    usm.queryAndAggregateUsageStats(now - 60L * 24 * 3600 * 1000, now).mapValues { it.value.lastTimeUsed }
}.getOrDefault(emptyMap())

/** The drawer as rows: icon, name, and when the app was last used (while sorting by recency) or its package. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppList(model: LauncherModel, items: List<LaunchTarget>, cols: Int, usedAt: ((LaunchTarget) -> Long)?) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
        val perCol = (items.size + cols - 1) / cols
        items.chunked(max(1, perCol)).forEach { column ->
            Column(Modifier.weight(1f)) {
                column.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().height(ListRowHeight).clip(RoundedCornerShape(12.dp))
                            .combinedClickable(role = Role.Button, onClickLabel = t.label, onLongClick = { model.selected.value = t }, onClick = { if (!model.catalog.launch(t)) model.selected.value = t })
                            .padding(horizontal = Space.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(model, t, 48.dp)
                        Spacer(Modifier.size(Space.m))
                        Column(Modifier.weight(1f)) {
                            Text(t.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = when {
                                usedAt == null -> t.component.packageName
                                usedAt(t) > 0 -> stringResource(R.string.l_recent_ago, app.booxultimatum.ui.Format.duration(context, System.currentTimeMillis() - usedAt(t)))
                                else -> stringResource(R.string.l_recent_never)
                            }
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = Ink.Legend, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
                }
            }
        }
        repeat(cols - items.chunked(max(1, perCol)).size) { Spacer(Modifier.weight(1f)) }
    }
}
@Composable
private fun AppActions(model: LauncherModel, t: LaunchTarget, modifier: Modifier) {
    val context = LocalContext.current
    val prefs by model.prefs
    val folder = prefs.folders.values.firstOrNull { t.key in it.apps }
    val pinned = t.key in prefs.favourites || folder != null
    val hidden = t.key in prefs.hidden
    val shortcuts = remember(t.key) { model.catalog.shortcuts(t) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // The panel needs the lower screen; a keyboard left over from search would cover its keys.
    LaunchedEffect(t.key) { focus.clearFocus(); keyboard?.hide() }
    BottomPanel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.label, style = MaterialTheme.typography.headlineSmall)
                Text(t.component.packageName + (folder?.let { "  ·  " + it.name } ?: ""), style = CodeStyle, color = Ink.Legend)
            }
            Key(stringResource(R.string.l_close), onClick = { model.selected.value = null })
        }
        Spacer(Modifier.height(Space.m))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Key(stringResource(R.string.action_open), primary = true, onClick = { model.selected.value = null; model.catalog.launch(t) })
            Key(stringResource(if (pinned) R.string.l_unpin else R.string.l_pin), onClick = { model.togglePin(t) })
            if (t.key in prefs.favourites) {
                Key(stringResource(R.string.l_earlier), onClick = { model.move(t, -1) })
                Key(stringResource(R.string.l_later), onClick = { model.move(t, 1) })
            }
            if (folder != null) Key(stringResource(R.string.l_out_of_folder), onClick = { model.removeFromFolder(t, folder.id) })
            else Key(stringResource(R.string.l_add_to_folder), onClick = { model.selected.value = null; model.folderChooser.value = t })
            Key(stringResource(if (hidden) R.string.l_unhide else R.string.l_hide), onClick = { model.toggleHidden(t); if (!hidden) model.selected.value = null })
            Key(stringResource(R.string.action_app_info), onClick = { model.selected.value = null; model.catalog.openAppInfo(t) })
            if (!model.catalog.isSystem(t)) {
                Key(stringResource(R.string.l_uninstall), onClick = {
                    model.selected.value = null
                    runCatching { context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${t.component.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
            }
        }
        if (shortcuts.isNotEmpty()) {
            Spacer(Modifier.height(Space.m))
            Text(stringResource(R.string.l_shortcuts), style = MaterialTheme.typography.titleSmall)
            shortcuts.forEach { s ->
                Text(
                    (s.shortLabel ?: s.longLabel ?: s.id).toString(),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable { model.selected.value = null; model.catalog.startShortcut(s) }.padding(vertical = 10.dp),
                )
                HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
            }
        } else if (!model.catalog.canShowShortcuts()) {
            Spacer(Modifier.height(Space.s))
            Text(stringResource(R.string.l_shortcuts_default), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
        }
    }
}

@Composable
private fun EditHome(model: LauncherModel, onOpenApp: () -> Unit, onOpenAppearance: () -> Unit, onWindowChanged: () -> Unit) {
    val prefs by model.prefs
    val widgets by model.widgets
    val apps by model.apps
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().wrapContentWidth().widthIn(max = 1000.dp), contentPadding = PaddingValues(horizontal = Space.xl, vertical = Space.l)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.l_edit_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Key(stringResource(R.string.l_done), primary = true, onClick = { model.overlay.value = Overlay.None })
            }
            Spacer(Modifier.height(Space.xl))
        }
        item {
            Plate(stringResource(R.string.l_grid)) {
                Stepper(stringResource(R.string.l_columns), prefs.columns, 3..8) { v -> model.update { it.copy(columns = v) } }
                Stepper(stringResource(R.string.l_rows), prefs.rows, 2..8) { v -> model.update { it.copy(rows = v) } }
                Text(stringResource(R.string.l_rows_note), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
            }
            Spacer(Modifier.height(Space.xl))
        }
        item {
            Plate(stringResource(R.string.l_screen)) {
                Choice(
                    stringResource(R.string.l_status_bar),
                    listOf(stringResource(R.string.l_status_show) to true, stringResource(R.string.l_status_hide) to false),
                    prefs.statusBar,
                ) { v -> model.update { it.copy(statusBar = v) }; onWindowChanged() }
                Text(stringResource(R.string.l_status_note), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
                Choice(stringResource(R.string.l_side_buttons), listOf(stringResource(R.string.l_on) to true, stringResource(R.string.l_off) to false), prefs.sideButtons) { v -> model.update { it.copy(sideButtons = v) } }
                Spacer(Modifier.height(Space.m))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.l_more_in_app), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend, modifier = Modifier.weight(1f).padding(end = Space.m))
                    Key(stringResource(R.string.l_open_appearance), primary = true, onClick = onOpenAppearance)
                }
            }
            Spacer(Modifier.height(Space.xl))
        }
        item {
            Plate(stringResource(R.string.l_widgets), action = { Key(stringResource(R.string.l_add_widget), onClick = { model.overlay.value = Overlay.WidgetPicker }) }) {
                if (widgets.isEmpty()) Paragraph(stringResource(R.string.l_no_widgets), color = Ink.Legend)
                val off = model.widgetsOffscreen.intValue
                if (off > 0) {
                    Text(pluralStringResource(R.plurals.l_widgets_offscreen, off, off), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = Space.s))
                }
                widgets.forEachIndexed { i, w ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text(widgetName(model, w), style = MaterialTheme.typography.titleMedium)
                        val height = when {
                            w.extra > 0 -> pluralStringResource(R.plurals.l_height_taller, w.extra, w.extra)
                            w.extra < 0 -> pluralStringResource(R.plurals.l_height_shorter, -w.extra, -w.extra)
                            else -> stringResource(R.string.l_height_standard)
                        }
                        Text(stringResource(if (w.span == 2) R.string.l_wide else R.string.l_half) + "  ·  " + height, style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
                        Spacer(Modifier.height(Space.s))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            Key(stringResource(R.string.l_earlier), enabled = i > 0, onClick = { model.setWidgets(widgets.toMutableList().apply { add(i - 1, removeAt(i)) }) })
                            Key(stringResource(R.string.l_later), enabled = i < widgets.size - 1, onClick = { model.setWidgets(widgets.toMutableList().apply { add(i + 1, removeAt(i)) }) })
                            Key(stringResource(if (w.span == 2) R.string.l_make_half else R.string.l_make_wide), onClick = {
                                model.setWidgets(widgets.map { if (it.id == w.id) it.copy(span = 3 - it.span) else it })
                            })
                            Key(stringResource(R.string.l_shorter), enabled = w.extra > WidgetSpec.EXTRA_RANGE.first, onClick = {
                                model.setWidgets(widgets.map { if (it.id == w.id) it.copy(extra = it.extra - 1) else it })
                            })
                            Key(stringResource(R.string.l_taller), enabled = w.extra < WidgetSpec.EXTRA_RANGE.last, onClick = {
                                model.setWidgets(widgets.map { if (it.id == w.id) it.copy(extra = it.extra + 1) else it })
                            })
                            Key(stringResource(R.string.l_remove), onClick = { model.removeWidget(w) })
                        }
                    }
                    HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
                }
                if (widgets.any { it.kind == WidgetKind.Weather }) {
                    Spacer(Modifier.height(Space.m))
                    Choice(stringResource(R.string.l_weather_units), listOf("°C" to false, "°F" to true), model.weather.fahrenheit) { v ->
                        model.weather.setFahrenheit(v); model.visibleKey.intValue++
                    }
                }
            }
            Spacer(Modifier.height(Space.xl))
        }
        item {
            val hidden = apps.filter { it.key in prefs.hidden }
            Plate(stringResource(R.string.l_hidden)) {
                if (hidden.isEmpty()) Paragraph(stringResource(R.string.l_hidden_none), color = Ink.Legend)
                hidden.forEach { t ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Key(stringResource(R.string.l_unhide), onClick = { model.toggleHidden(t) })
                    }
                    HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
                }
            }
            Spacer(Modifier.height(Space.xl))
        }
        item {
            Plate(stringResource(R.string.l_about_home)) {
                Paragraph(stringResource(R.string.l_about_home_text), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
                Key(stringResource(R.string.l_open_app), onClick = onOpenApp)
            }
        }
    }
}

@Composable
private fun widgetName(model: LauncherModel, w: WidgetSpec): String {
    val context = LocalContext.current
    return when (w.kind) {
        WidgetKind.System -> remember(w.appWidgetId) { model.widgetManager.getAppWidgetInfo(w.appWidgetId)?.loadLabel(context.packageManager) } ?: stringResource(R.string.w_system_missing)
        else -> stringResource(builtinName(w.kind))
    }
}

private fun builtinName(k: WidgetKind) = when (k) {
    WidgetKind.Clock -> R.string.w_clock
    WidgetKind.Calendar -> R.string.w_calendar
    WidgetKind.Agenda -> R.string.w_agenda
    WidgetKind.Weather -> R.string.w_weather
    WidgetKind.Battery -> R.string.w_battery
    WidgetKind.Alarm -> R.string.w_alarm
    WidgetKind.Note -> R.string.w_note
    WidgetKind.Boox -> R.string.w_boox
    WidgetKind.System -> R.string.w_system
}

private fun builtinBlurb(k: WidgetKind) = when (k) {
    WidgetKind.Clock -> R.string.wb_clock
    WidgetKind.Calendar -> R.string.wb_calendar
    WidgetKind.Agenda -> R.string.wb_agenda
    WidgetKind.Weather -> R.string.wb_weather
    WidgetKind.Battery -> R.string.wb_battery
    WidgetKind.Alarm -> R.string.wb_alarm
    WidgetKind.Note -> R.string.wb_note
    WidgetKind.Boox -> R.string.wb_boox
    WidgetKind.System -> R.string.wb_clock
}

private data class ProviderGroup(val pkg: String, val label: String, val providers: List<android.appwidget.AppWidgetProviderInfo>)

@Composable
private fun WidgetPicker(model: LauncherModel, onAddSystemWidget: (android.appwidget.AppWidgetProviderInfo) -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    // Grouped by app, Boox first: one app's many widgets sit under one heading instead of a long flat list.
    val groups = remember {
        model.providers().groupBy { it.provider.packageName }.map { (pkg, list) ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            ProviderGroup(pkg, label, list.sortedBy { it.loadLabel(pm).lowercase() })
        }.sortedWith(compareBy({ !it.pkg.startsWith("com.onyx") }, { it.label.lowercase() }))
    }
    var open by rememberSaveable { mutableStateOf(groups.firstOrNull { it.pkg.startsWith("com.onyx") }?.pkg) }
    LazyColumn(Modifier.fillMaxSize().wrapContentWidth().widthIn(max = 1000.dp), contentPadding = PaddingValues(horizontal = Space.xl, vertical = Space.l)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.l_add_widget), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Key(stringResource(R.string.l_close), onClick = { model.overlay.value = Overlay.Edit })
            }
            Spacer(Modifier.height(Space.xl))
            Plate(stringResource(R.string.l_builtin_widgets)) {
                listOf(WidgetKind.Boox, WidgetKind.Clock, WidgetKind.Calendar, WidgetKind.Agenda, WidgetKind.Weather, WidgetKind.Battery, WidgetKind.Alarm, WidgetKind.Note)
                    .filter { it != WidgetKind.Boox || app.booxultimatum.core.Tablet.current(context).isBoox }.forEach { k ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = Space.m)) {
                            Text(stringResource(builtinName(k)), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(builtinBlurb(k)), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
                        }
                        Key(stringResource(R.string.l_add), onClick = {
                            val span = if (k == WidgetKind.Weather || k == WidgetKind.Battery || k == WidgetKind.Boox) 2 else 1
                            model.setWidgets(model.widgets.value + WidgetSpec(WidgetStore.newId(), k, span))
                            model.overlay.value = Overlay.Edit
                        })
                    }
                    HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
                }
            }
            Spacer(Modifier.height(Space.xl))
            Text(stringResource(R.string.l_app_widgets, groups.sumOf { it.providers.size }), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Space.s))
            HorizontalDivider(thickness = Lines.engraved, color = Ink.Black)
        }
        items(groups, key = { it.pkg }) { g ->
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clickable { open = if (open == g.pkg) null else g.pkg }.heightIn(min = 64.dp).padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(g.label, style = MaterialTheme.typography.titleMedium)
                        Text(pluralStringResource(R.plurals.l_widget_count, g.providers.size, g.providers.size), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
                    }
                    Text(stringResource(if (open == g.pkg) R.string.action_hide else R.string.action_show_all), style = MaterialTheme.typography.labelLarge)
                }
                if (open == g.pkg) g.providers.forEach { p -> ProviderRow(p) { onAddSystemWidget(p) } }
                HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
            }
        }
    }
}

/** One widget with its preview image as the app draws it, its size, and Add. Previews load only for the open group. */
@Composable
private fun ProviderRow(p: android.appwidget.AppWidgetProviderInfo, onAdd: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val preview = remember(p.provider) {
        runCatching {
            val d = p.loadPreviewImage(context, 0) ?: p.loadIcon(context, 0)
            val w = d.intrinsicWidth.coerceIn(1, 560)
            val h = (d.intrinsicHeight * (w.toFloat() / d.intrinsicWidth.coerceAtLeast(1))).toInt().coerceIn(1, 360)
            d.toBitmap(w, h).asImageBitmap()
        }.getOrNull()
    }
    val size = with(density) { "${p.minWidth.toDp().value.toInt()} × ${p.minHeight.toDp().value.toInt()} dp" }
    Row(Modifier.fillMaxWidth().padding(start = Space.m, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 180.dp, height = 120.dp).border(Lines.hairline, Ink.Rule, RoundedCornerShape(12.dp)).padding(6.dp), contentAlignment = Alignment.Center) {
            if (preview != null) Image(preview, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.size(Space.m))
        Column(Modifier.weight(1f)) {
            Text(p.loadLabel(context.packageManager), style = MaterialTheme.typography.titleSmall)
            Text(size, style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
        }
        Key(stringResource(R.string.l_add), onClick = onAdd)
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, step: Int = 1, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Key("−", onClick = { onChange((value - step).coerceIn(range)) }, enabled = value > range.first)
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 64.dp))
        Key("+", onClick = { onChange((value + step).coerceIn(range)) }, enabled = value < range.last)
    }
}

@Composable
private fun <T> Choice(label: String, options: List<Pair<String, T>>, current: T, onChange: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(Space.s))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            options.forEach { (text, v) -> Key(text, primary = v == current, onClick = { onChange(v) }) }
        }
    }
}
