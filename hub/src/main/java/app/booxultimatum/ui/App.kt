package app.booxultimatum.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.booxultimatum.R
import app.booxultimatum.core.suite.Module
import app.booxultimatum.core.suite.Modules
import app.booxultimatum.kit.core.Tablet
import app.booxultimatum.kit.ui.Glyphs
import app.booxultimatum.kit.ui.StatusStrip
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import app.booxultimatum.ui.screens.AccessScreen
import app.booxultimatum.ui.screens.AppearanceScreen
import app.booxultimatum.ui.screens.AppsScreen
import app.booxultimatum.ui.screens.BatteryCausesScreen
import app.booxultimatum.ui.screens.BatteryHealthScreen
import app.booxultimatum.ui.screens.BatteryHistoryScreen
import app.booxultimatum.ui.screens.BatteryTodayScreen
import app.booxultimatum.ui.screens.DeviceScreen
import app.booxultimatum.ui.screens.FontsScreen
import app.booxultimatum.ui.screens.HomeScreenPage
import app.booxultimatum.ui.screens.HubScreen
import app.booxultimatum.ui.screens.InkScreen
import app.booxultimatum.ui.screens.LogsScreen
import app.booxultimatum.ui.screens.OverviewScreen
import app.booxultimatum.ui.screens.SleepScreen
import app.booxultimatum.ui.screens.StorageScreen
import app.booxultimatum.ui.screens.SuiteScreen
import app.booxultimatum.ui.screens.TweaksScreen

/** The rail: five sections, so it fits every panel without scrolling. */
enum class Section(@StringRes val label: Int, val glyph: ImageVector) {
    Overview(R.string.section_overview, Glyphs.Overview),
    Suite(R.string.section_suite, Glyphs.Suite),
    Battery(R.string.section_battery, Glyphs.Battery),
    System(R.string.section_system, Glyphs.Tweaks),
    Device(R.string.section_device, Glyphs.Device),
}

/**
 * Every page, in its section's tab order. The names are stable: other parts of the app and BooxUltimatum home open a
 * page by name through [app.booxultimatum.MainActivity.EXTRA_DESTINATION]. A page that belongs to a [module] shows
 * only while that module is added.
 */
enum class Destination(val section: Section, @StringRes val label: Int, val module: Module? = null) {
    Overview(Section.Overview, R.string.dest_overview),
    Suite(Section.Suite, R.string.dest_suite),
    Home(Section.Suite, R.string.dest_home, Module.Home),
    Sleep(Section.Suite, R.string.dest_sleep, Module.Sleep),
    Ink(Section.Suite, R.string.dest_ink, Module.Ink),
    Battery(Section.Battery, R.string.dest_battery_today),
    BatteryHistory(Section.Battery, R.string.dest_battery_history),
    BatteryCauses(Section.Battery, R.string.dest_battery_causes),
    BatteryHealth(Section.Battery, R.string.dest_battery_health),
    Tweaks(Section.System, R.string.dest_tweaks),
    Apps(Section.System, R.string.dest_apps),
    Appearance(Section.System, R.string.dest_appearance),
    Fonts(Section.System, R.string.dest_fonts),
    Hub(Section.System, R.string.dest_hub),
    Device(Section.Device, R.string.dest_device),
    Access(Section.Device, R.string.dest_access),
    Logs(Section.Device, R.string.dest_logs),
    Storage(Section.Device, R.string.dest_storage),
}

/**
 * [accessEvents] ticks when Shizuku connects, disconnects or answers a permission request, so privilege readings
 * refresh on events rather than by polling. The rail holds the five sections; a section with several pages shows
 * them as a row of tabs above the page.
 */
@Composable
fun BooxUltimatumApp(accessEvents: Int, initial: String? = null) {
    val context = LocalContext.current
    val boox = remember { Tablet.current(context).isBoox }
    // Bumped when a module is added or removed, so the tabs follow.
    var modulesKey by rememberSaveable { mutableIntStateOf(0) }
    val visible = remember(modulesKey) {
        Destination.entries.filter { d -> d.module?.let { Modules.added(context, it) && (boox || !it.booxOnly) } ?: true }
    }
    var destination by rememberSaveable { mutableStateOf(initial?.let { n -> Destination.entries.firstOrNull { it.name == n } } ?: Destination.Overview) }
    if (destination !in visible) destination = visible.firstOrNull { it.section == destination.section } ?: Destination.Overview
    var readKey by rememberSaveable { mutableIntStateOf(0) }
    val readAgain: () -> Unit = { readKey++ }
    val go: (Destination) -> Unit = { destination = it }
    BackHandler(enabled = destination != Destination.Overview) { destination = Destination.Overview }

    Box(Modifier.fillMaxSize().background(Ink.Paper)) {
        // Ink under the status bar: this firmware draws its icons white over this app, whatever the app asks for.
        StatusStrip(Modifier.align(Alignment.TopStart))
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val compact = maxWidth < 600.dp
            Row(Modifier.fillMaxSize()) {
                SectionRail(destination.section) { section -> destination = visible.first { it.section == section } }
                VerticalDivider(thickness = Lines.rim, color = Ink.Black)
                Column(Modifier.weight(1f)) {
                    val pages = visible.filter { it.section == destination.section }
                    if (pages.size > 1) PageTabs(pages, destination) { destination = it }
                    Box(Modifier.weight(1f)) {
                        when (destination) {
                            Destination.Overview -> OverviewScreen(
                                readKey, accessEvents, compact, readAgain,
                                onOpenAccess = { go(Destination.Access) },
                                onOpenBattery = { go(Destination.Battery) },
                                onOpenTweaks = { go(Destination.Tweaks) },
                            )
                            Destination.Suite -> SuiteScreen(readKey, compact, onModulesChanged = { modulesKey++ }, onOpen = go)
                            Destination.Home -> HomeScreenPage(readKey, compact)
                            Destination.Sleep -> SleepScreen(readKey, accessEvents)
                            Destination.Ink -> InkScreen(readKey, compact)
                            Destination.Battery -> BatteryTodayScreen(readKey, compact, readAgain, onOpen = go)
                            Destination.BatteryHistory -> BatteryHistoryScreen(readKey, compact, readAgain)
                            Destination.BatteryCauses -> BatteryCausesScreen(readKey, compact, readAgain, onOpen = go)
                            Destination.BatteryHealth -> BatteryHealthScreen(readKey, compact, readAgain)
                            Destination.Tweaks -> TweaksScreen(readKey, accessEvents, compact, readAgain) { go(Destination.Access) }
                            Destination.Apps -> AppsScreen(readKey, compact, readAgain) { go(Destination.Access) }
                            Destination.Appearance -> AppearanceScreen(readKey, compact) { go(Destination.Fonts) }
                            Destination.Fonts -> FontsScreen(readKey, compact) { go(Destination.Tweaks) }
                            Destination.Hub -> HubScreen(readKey, compact)
                            Destination.Device -> DeviceScreen(readKey, compact, readAgain)
                            Destination.Access -> AccessScreen(readKey, accessEvents, compact, readAgain)
                            Destination.Logs -> LogsScreen(readKey, compact)
                            Destination.Storage -> StorageScreen(readKey, compact)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionRail(current: Section, onSection: (Section) -> Unit) {
    NavigationRail(
        containerColor = Ink.Paper,
        modifier = Modifier.fillMaxHeight().padding(horizontal = Space.xs),
        header = {
            Spacer(Modifier.height(Space.l))
            Image(painterResource(R.drawable.ic_mark), contentDescription = stringResource(R.string.app_name), modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(Space.m))
        },
    ) {
        // A large system font can still push the last section off a small panel, so the rail scrolls when it must.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Section.entries.forEach { s ->
                NavigationRailItem(
                    selected = s == current,
                    onClick = { onSection(s) },
                    icon = { Icon(s.glyph, contentDescription = null) },
                    label = { Text(stringResource(s.label), style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                    alwaysShowLabel = true,
                    modifier = Modifier.padding(vertical = 2.dp),
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = Ink.Paper, selectedTextColor = Ink.Black, indicatorColor = Ink.Black,
                        unselectedIconColor = Ink.Black, unselectedTextColor = Ink.Legend,
                    ),
                )
            }
        }
    }
}

/** The pages of one section as a row of tabs: the current one set in black, like a pressed key. */
@Composable
private fun PageTabs(pages: List<Destination>, current: Destination, onPage: (Destination) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.l, vertical = Space.s)) {
            pages.forEach { d ->
                val on = d == current
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .background(if (on) Ink.Black else Ink.Paper)
                        .clickable(role = Role.Tab) { onPage(d) }
                        .semantics { selected = on }
                        .padding(horizontal = Space.m),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(d.label), style = MaterialTheme.typography.labelLarge, color = if (on) Ink.Paper else Ink.Black, maxLines = 1)
                }
                Spacer(Modifier.size(Space.xs))
            }
        }
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}
