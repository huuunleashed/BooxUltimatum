package app.booxultimatum.ui.screens.fonts

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.booxultimatum.R
import app.booxultimatum.core.FontCategory
import app.booxultimatum.core.FontFamilyInfo
import app.booxultimatum.core.FontLibrary
import app.booxultimatum.core.FontStyleKey
import app.booxultimatum.core.FontUse
import app.booxultimatum.core.Fonts
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.ScreenHeader
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class FontsTab { Installed, Browse }

/** The page title and the two tabs, Installed first. */
@Composable
internal fun FontsHeader(tab: FontsTab, library: FontLibrary?, catalogSize: Int?, onTab: (FontsTab) -> Unit, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val subtitle = when (tab) {
        FontsTab.Installed -> library?.let { lib ->
            if (lib.fonts.isEmpty()) stringResource(R.string.fonts_installed_subtitle_none)
            else pluralStringResource(R.plurals.fonts_installed_subtitle, lib.fonts.size, lib.fonts.size, sizeText(context, lib.size))
        }
        FontsTab.Browse -> catalogSize?.let { pluralStringResource(R.plurals.fonts_subtitle, it, it) }
    }
    ScreenHeader(stringResource(R.string.dest_fonts), subtitle) {
        Key(stringResource(if (tab == FontsTab.Browse) R.string.fonts_refresh else R.string.fonts_check_again), onClick = onRefresh)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Key(library?.fonts?.size?.let { stringResource(R.string.fonts_tab_installed, it) } ?: stringResource(R.string.fonts_tag_installed), primary = tab == FontsTab.Installed, onClick = { onTab(FontsTab.Installed) })
        Key(stringResource(R.string.fonts_tab_browse), primary = tab == FontsTab.Browse, onClick = { onTab(FontsTab.Browse) })
    }
    Spacer(Modifier.height(Space.l))
    HorizontalDivider(thickness = Lines.engraved, color = Ink.Black)
    Spacer(Modifier.height(Space.l))
}

@Composable
internal fun SampleField(value: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth().border(Lines.hairline, Ink.Black, shape).padding(horizontal = 20.dp, vertical = 14.dp)) {
        if (value.isEmpty()) Text(stringResource(R.string.fonts_sample_hint), style = MaterialTheme.typography.bodyLarge, color = Ink.Legend)
        BasicTextField(value, onChange, textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.Black), cursorBrush = SolidColor(Ink.Black), modifier = Modifier.fillMaxWidth())
    }
}

/** Loads a Google family's regular style (or one style) on demand, or null until it arrives. */
@Composable
internal fun rememberFamily(f: FontFamilyInfo?, weight: Int? = null, italic: Boolean = false): FontFamily? {
    val context = LocalContext.current
    val state by produceState<FontFamily?>(null, f?.name, weight, italic) {
        if (f == null) return@produceState
        val file = (if (weight == null) Fonts.regular(context, f) else Fonts.style(context, f, weight, italic)).getOrNull()
        value = file?.let { runCatching { FontFamily(Font(it)) }.getOrNull() }
    }
    return state
}

/** A family from one file on the tablet, for setting an installed font's name in its own face. */
@Composable
internal fun rememberFileFamily(path: String?): FontFamily? {
    val state by produceState<FontFamily?>(null, path) {
        value = path?.let { p -> withContext(Dispatchers.IO) { runCatching { File(p).takeIf { it.canRead() }?.let { FontFamily(Font(it)) } }.getOrNull() } }
    }
    return state
}

/** A plural from outside composition, for messages written after an action finishes. */
internal fun android.content.Context.quantity(id: Int, n: Int, vararg args: Any): String = resources.getQuantityString(id, n, *args)

internal fun sizeText(context: android.content.Context, bytes: Long): String = android.text.format.Formatter.formatShortFileSize(context, bytes)

internal fun dateText(at: Long): String = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(at))

@Composable
internal fun styleText(s: FontStyleKey): String =
    if (s.italic) stringResource(R.string.fonts_style_italic_short, s.weight) else s.weight.toString()

@Composable
internal fun stylesText(styles: List<FontStyleKey>): String = styles.map { styleText(it) }.joinToString(", ")

/** Uses in reading order, "tablet font, NeoReader, home screen". */
@Composable
internal fun usesText(uses: Set<FontUse>): String = FontUse.entries.filter { it in uses }.map { stringResource(useName(it)) }.joinToString(", ")

internal fun useName(u: FontUse) = when (u) {
    FontUse.Tablet -> R.string.fonts_use_tablet
    FontUse.Reader -> R.string.fonts_use_reader
    FontUse.Home -> R.string.fonts_use_home
    FontUse.App -> R.string.fonts_use_app
    FontUse.Sleep -> R.string.fonts_use_sleep
}

internal fun useWords(context: android.content.Context, uses: Set<FontUse>): String =
    FontUse.entries.filter { it in uses }.joinToString(", ") { context.getString(useName(it)) }

internal fun String.capitalised() = replaceFirstChar { it.titlecase() }

internal fun categoryName(c: FontCategory) = when (c) {
    FontCategory.Serif -> R.string.fonts_serif
    FontCategory.Sans -> R.string.fonts_sans
    FontCategory.Display -> R.string.fonts_f_display
    FontCategory.Handwriting -> R.string.fonts_f_hand
    FontCategory.Mono -> R.string.fonts_f_mono
}
