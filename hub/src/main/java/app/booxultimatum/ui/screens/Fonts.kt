package app.booxultimatum.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.booxultimatum.core.FontFamilyInfo
import app.booxultimatum.core.Fonts
import app.booxultimatum.kit.ui.rememberReading
import app.booxultimatum.ui.screens.fonts.FontBrowser
import app.booxultimatum.ui.screens.fonts.FontPage
import app.booxultimatum.ui.screens.fonts.FontsHeader
import app.booxultimatum.ui.screens.fonts.FontsTab
import app.booxultimatum.ui.screens.fonts.InstalledFonts

/**
 * System › Fonts: the fonts BooxUltimatum installed, managed (Installed), and the Google Fonts catalogue to find
 * more (Google Fonts). Both open the same page for one family. Opens on Installed once anything is installed.
 */
@Composable
fun FontsScreen(readKey: Int, compact: Boolean, onOpenTweaks: () -> Unit) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var libraryKey by rememberSaveable { mutableIntStateOf(0) }
    val catalog by produceState<Result<List<FontFamilyInfo>>?>(null, readKey, refreshKey) { value = Fonts.catalog(context, forceRefresh = refreshKey > 0) }
    val byBase = remember(catalog) { catalog?.getOrNull()?.associateBy { Fonts.fileBase(it.name) }.orEmpty() }
    val library = rememberReading(readKey to libraryKey) { Fonts.library(context) { byBase[it]?.name } }
    val lib = library?.getOrNull()
    var tab by rememberSaveable { mutableStateOf<FontsTab?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var sample by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(lib != null) { if (tab == null && lib != null) tab = if (lib.fonts.isEmpty()) FontsTab.Browse else FontsTab.Installed }
    val changed: () -> Unit = { libraryKey++ }

    selected?.let { base ->
        BackHandler { selected = null }
        FontPage(base, byBase[base], lib, sample, { sample = it }, compact, onBack = { selected = null }, onChanged = changed, onOpenTweaks = onOpenTweaks)
        return
    }
    val shown = tab ?: FontsTab.Installed
    val header: @Composable () -> Unit = {
        FontsHeader(shown, lib, catalog?.getOrNull()?.size, onTab = { tab = it }, onRefresh = { if (shown == FontsTab.Browse) refreshKey++ else libraryKey++ })
    }
    when (shown) {
        FontsTab.Installed -> InstalledFonts(library, byBase, compact, header, onOpen = { selected = it }, onBrowse = { tab = FontsTab.Browse }, onChanged = changed)
        FontsTab.Browse -> FontBrowser(catalog, lib, sample, { sample = it }, compact, header, onOpen = { selected = it })
    }
}
