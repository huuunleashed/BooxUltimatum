package app.booxultimatum.ui.screens.fonts

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.booxultimatum.R
import app.booxultimatum.core.BulkChange
import app.booxultimatum.core.FontFamilyInfo
import app.booxultimatum.core.FontInventory
import app.booxultimatum.core.FontLibrary
import app.booxultimatum.core.FontNames
import app.booxultimatum.core.FontState
import app.booxultimatum.core.FontUse
import app.booxultimatum.core.Fonts
import app.booxultimatum.core.InstalledFilter
import app.booxultimatum.core.InstalledFont
import app.booxultimatum.core.InstalledSort
import app.booxultimatum.kit.ui.ConfirmKey
import app.booxultimatum.kit.ui.ErrorLine
import app.booxultimatum.kit.ui.InstrumentPage
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.Lamp
import app.booxultimatum.kit.ui.Pager
import app.booxultimatum.kit.ui.Paragraph
import app.booxultimatum.kit.ui.Plate
import app.booxultimatum.kit.ui.Reading
import app.booxultimatum.kit.ui.SpecRow
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import kotlinx.coroutines.launch

private const val INSTALLED_PER_PAGE = 6
private const val FOREIGN_SHOWN = 8

/** Every family the app installed: totals and bulk actions, then the list, sorted, filtered and paged. */
@Composable
internal fun InstalledFonts(
    library: Result<FontLibrary>?,
    catalog: Map<String, FontFamilyInfo>,
    compact: Boolean,
    header: @Composable () -> Unit,
    onOpen: (String) -> Unit,
    onBrowse: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sort by rememberSaveable { mutableStateOf(InstalledSort.Name) }
    var filter by rememberSaveable { mutableStateOf(InstalledFilter.All) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    fun bulk(block: suspend () -> Result<BulkChange>, done: (BulkChange) -> String) {
        busy = true; message = null; error = null
        scope.launch {
            val r = block()
            r.onSuccess { c ->
                message = done(c).takeIf { c.done.isNotEmpty() }
                error = c.failed.firstOrNull()?.let { (family, e) -> context.getString(R.string.fonts_bulk_failed, family, e.message ?: e.toString()) }
            }.onFailure { error = context.getString(R.string.tweak_failed, it.message ?: it.toString()) }
            busy = false
            onChanged()
            if (r.getOrNull()?.restart == true) restart(context)
        }
    }

    InstrumentPage(compact) {
        item { header() }
        val result = library
        if (result == null) {
            item { Reading() }
            return@InstrumentPage
        }
        val lib = result.getOrElse {
            item { ErrorLine(stringResource(R.string.fonts_scan_failed, it.message ?: it.toString())) }
            return@InstrumentPage
        }
        fun nameOf(f: InstalledFont) = catalog[f.base]?.name ?: f.family

        if (lib.fonts.isEmpty()) {
            item {
                Paragraph(stringResource(R.string.fonts_lib_empty), color = Ink.Legend)
                Spacer(Modifier.height(Space.m))
                Key(stringResource(R.string.fonts_browse), primary = true, onClick = onBrowse)
                Spacer(Modifier.height(Space.xl))
            }
        } else {
            item {
                val on = lib.fonts.count { it.state == FontState.On }
                val inUse = lib.fonts.count { lib.usesOf(it.base).isNotEmpty() }
                val unused = lib.unused.size
                Plate(stringResource(R.string.fonts_lib_title)) {
                    SpecRow(stringResource(R.string.fonts_lib_count), lib.fonts.size.toString(), note = stringResource(R.string.fonts_lib_on_off, on, lib.fonts.size - on))
                    SpecRow(stringResource(R.string.fonts_lib_size), sizeText(context, lib.size), note = stringResource(R.string.fonts_lib_size_note))
                    SpecRow(stringResource(R.string.fonts_lib_in_use), pluralStringResource(R.plurals.fonts_n_fonts, inUse, inUse))
                    Spacer(Modifier.height(Space.m))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        if (unused > 0) {
                            ConfirmKey(
                                pluralStringResource(R.plurals.fonts_delete_unused, unused, unused),
                                pluralStringResource(R.plurals.fonts_delete_unused_confirm, unused, unused),
                                enabled = !busy,
                                onConfirm = {
                                    bulk({ Fonts.deleteUnused(context) }) { r ->
                                        context.quantity(R.plurals.fonts_bulk_deleted, r.done.size, r.done.size, sizeText(context, r.bytes))
                                    }
                                },
                            )
                        } else Key(stringResource(R.string.fonts_delete_unused_none), enabled = false, onClick = {})
                        if (on > 0) {
                            ConfirmKey(
                                stringResource(R.string.fonts_turn_all_off),
                                pluralStringResource(R.plurals.fonts_turn_all_off_confirm, on, on),
                                enabled = !busy,
                                onConfirm = {
                                    bulk({ Fonts.turnAllOff(context) }) { r ->
                                        context.quantity(R.plurals.fonts_bulk_off, r.done.size, r.done.size)
                                    }
                                },
                            )
                        }
                        if (on < lib.fonts.size) {
                            Key(stringResource(R.string.fonts_turn_all_on), enabled = !busy, onClick = {
                                bulk({ Fonts.turnAllOn(context) }) { r ->
                                    context.quantity(R.plurals.fonts_bulk_on, r.done.size, r.done.size)
                                }
                            })
                        }
                    }
                    if (busy) Text(stringResource(R.string.state_working), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.m))
                    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.m)) }
                    error?.let { ErrorLine(it) }
                    Text(stringResource(R.string.fonts_bulk_note), style = MaterialTheme.typography.bodySmall, color = Ink.Legend, modifier = Modifier.padding(top = Space.m))
                }
                Spacer(Modifier.height(Space.xl))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    InstalledSort.entries.forEach { s -> Key(stringResource(sortName(s)), primary = s == sort, onClick = { sort = s; page = 0 }) }
                }
                Spacer(Modifier.height(Space.s))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    InstalledFilter.entries.forEach { f -> Key(stringResource(filterName(f)), primary = f == filter, onClick = { filter = f; page = 0 }) }
                }
                Spacer(Modifier.height(Space.m))
                HorizontalDivider(thickness = Lines.engraved, color = Ink.Black)
            }
            val list = FontInventory.arrange(lib.fonts, lib.uses, sort, filter).let { l -> if (sort == InstalledSort.Name) l.sortedBy { nameOf(it).lowercase() } else l }
            val pages = ((list.size + INSTALLED_PER_PAGE - 1) / INSTALLED_PER_PAGE).coerceAtLeast(1)
            val safe = page.coerceIn(0, pages - 1)
            item {
                Text(pluralStringResource(R.plurals.fonts_n_fonts, list.size, list.size), style = MaterialTheme.typography.labelLarge, color = Ink.Legend, modifier = Modifier.padding(vertical = Space.m))
                if (list.isEmpty()) Paragraph(stringResource(R.string.fonts_lib_empty_filter), color = Ink.Legend)
            }
            items(list.drop(safe * INSTALLED_PER_PAGE).take(INSTALLED_PER_PAGE), key = { it.base }) { f ->
                InstalledRow(f, nameOf(f), lib.usesOf(f.base)) { onOpen(f.base) }
            }
            if (pages > 1) item { Pager(safe, pages, onPage = { page = it }) }
        }
        if (!lib.readerChecked) item {
            Spacer(Modifier.height(Space.l))
            Paragraph(stringResource(R.string.fonts_reader_unchecked), color = Ink.Legend)
        }
        if (lib.foreign.isNotEmpty()) item { ForeignPlate(lib) }
    }
}

@Composable
private fun InstalledRow(font: InstalledFont, name: String, uses: Set<FontUse>, onOpen: () -> Unit) {
    val context = LocalContext.current
    val face = rememberFileFamily(font.preview?.path)
    val status = when (font.state) {
        FontState.Off -> stringResource(R.string.fonts_row_off)
        FontState.Partial -> stringResource(R.string.fonts_row_partial)
        FontState.On -> if (uses.isEmpty()) stringResource(R.string.fonts_row_on_unused) else stringResource(R.string.fonts_row_on_uses, usesText(uses))
    }
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.Top) {
            Lamp(font.state == FontState.On, Modifier.padding(top = 12.dp))
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f).padding(end = Space.m)) {
                Text(
                    name,
                    style = TextStyle(fontFamily = face ?: FontFamily.Default, fontSize = 28.sp, lineHeight = 36.sp, color = if (face == null) Ink.Rule else Ink.Black),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Space.xs))
                Text(status, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stylesText(font.styles) + "  ·  " + sizeText(context, font.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.Legend,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(stringResource(R.string.action_open), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        }
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}

/** Fonts in the Boox folder from elsewhere: read-only, grouped by family, never touched. */
@Composable
private fun ForeignPlate(lib: FontLibrary) {
    val context = LocalContext.current
    var all by rememberSaveable { mutableStateOf(false) }
    val groups = lib.foreign.groupBy { f -> f.name.substringBefore('-', f.name.substringBeforeLast('.')) }.toList().sortedBy { it.first.lowercase() }
    Spacer(Modifier.height(Space.xl))
    Plate(
        stringResource(R.string.fonts_foreign_title),
        action = if (groups.size > FOREIGN_SHOWN) { { Key(stringResource(if (all) R.string.action_hide else R.string.action_show_all), onClick = { all = !all }) } } else null,
    ) {
        Paragraph(stringResource(R.string.fonts_foreign_note), color = Ink.Legend, modifier = Modifier.padding(bottom = Space.s))
        (if (all) groups else groups.take(FOREIGN_SHOWN)).forEach { (group, files) ->
            SpecRow(FontNames.display(group), sizeText(context, files.sumOf { it.size }), note = pluralStringResource(R.plurals.fonts_files, files.size, files.size))
        }
    }
}

internal fun restart(context: android.content.Context) {
    (context as? Activity)?.recreate()
}

private fun sortName(s: InstalledSort) = when (s) {
    InstalledSort.Name -> R.string.fonts_sort_name
    InstalledSort.Size -> R.string.fonts_sort_size
    InstalledSort.Recent -> R.string.fonts_sort_recent
}

private fun filterName(f: InstalledFilter) = when (f) {
    InstalledFilter.All -> R.string.fonts_f_all
    InstalledFilter.InUse -> R.string.fonts_show_in_use
    InstalledFilter.Unused -> R.string.fonts_show_unused
    InstalledFilter.Off -> R.string.fonts_show_off
}
