package app.booxultimatum.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.booxultimatum.R
import app.booxultimatum.ui.screens.AccessScreen
import app.booxultimatum.ui.screens.AppsScreen
import app.booxultimatum.ui.screens.BatteryScreen
import app.booxultimatum.ui.screens.DeviceScreen
import app.booxultimatum.ui.screens.HubScreen
import app.booxultimatum.ui.screens.OverviewScreen
import app.booxultimatum.ui.screens.TweaksScreen
import app.booxultimatum.ui.theme.Ink
import app.booxultimatum.ui.theme.Lines
import app.booxultimatum.ui.theme.Space

enum class Destination(@StringRes val label: Int, val glyph: ImageVector) {
    Overview(R.string.dest_overview, Glyphs.Overview),
    Battery(R.string.dest_battery, Glyphs.Battery),
    Tweaks(R.string.dest_tweaks, Glyphs.Tweaks),
    Apps(R.string.dest_apps, Glyphs.Apps),
    Appearance(R.string.dest_appearance, Glyphs.Appearance),
    Fonts(R.string.dest_fonts, Glyphs.Fonts),
    Sleep(R.string.dest_sleep, Glyphs.Sleep),
    Ink(R.string.dest_ink, Glyphs.Pen),
    Hub(R.string.dest_hub, Glyphs.Hub),
    Device(R.string.dest_device, Glyphs.Device),
    Access(R.string.dest_access, Glyphs.Access),
}

/** Pages that only work with Boox firmware; other tablets don't show them. */
private val BOOX_ONLY = setOf(Destination.Sleep, Destination.Ink)

/**
 * [accessEvents] ticks when Shizuku connects, disconnects or answers a permission request, so privilege
 * readings refresh on events rather than by polling.
 *
 * Seven destinations exceed a bottom bar, and this app lives on a 10" tablet, so the rail is used at every
 * width; on narrow windows it simply takes a little more of the row.
 */
@Composable
fun BooxUltimatumApp(accessEvents: Int, initial: String? = null) {
    var destination by rememberSaveable { mutableStateOf(initial?.let { n -> Destination.entries.firstOrNull { it.name == n } } ?: Destination.Overview) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val boox = androidx.compose.runtime.remember { app.booxultimatum.core.Tablet.current(context).isBoox }
    var readKey by rememberSaveable { mutableIntStateOf(0) }
    val readAgain: () -> Unit = { readKey++ }
    val go: (Destination) -> Unit = { destination = it }
    BackHandler(enabled = destination != Destination.Overview) { destination = Destination.Overview }

    Box(Modifier.fillMaxSize().background(Ink.Paper)) {
    // Ink under the status bar: this firmware draws its icons white over this app, whatever the app asks for.
    StatusStrip(Modifier.align(androidx.compose.ui.Alignment.TopStart))
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val compact = maxWidth < 600.dp
        Row(Modifier.fillMaxSize()) {
            NavigationRail(
                containerColor = Ink.Paper,
                modifier = Modifier.fillMaxHeight().padding(horizontal = Space.xs),
                header = {
                    Spacer(Modifier.height(Space.l))
                    Image(painterResource(R.drawable.ic_mark), contentDescription = stringResource(R.string.app_name), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(Space.m))
                },
            ) {
                // Eleven destinations don't fit every panel: a 7" tablet or a large system font pushes the last ones
                // off the bottom, so the rail scrolls when it has to.
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                Destination.entries.filter { boox || it !in BOOX_ONLY }.forEach { d ->
                    NavigationRailItem(
                        selected = d == destination,
                        onClick = { destination = d },
                        icon = { Icon(d.glyph, contentDescription = null) },
                        label = { Text(stringResource(d.label), style = MaterialTheme.typography.labelMedium, maxLines = 1) },
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
            VerticalDivider(thickness = Lines.rim, color = Ink.Black)
            Box(Modifier.weight(1f)) {
                when (destination) {
                    Destination.Overview -> OverviewScreen(
                        readKey, accessEvents, compact, readAgain,
                        onOpenAccess = { go(Destination.Access) },
                        onOpenBattery = { go(Destination.Battery) },
                        onOpenTweaks = { go(Destination.Tweaks) },
                    )
                    Destination.Battery -> BatteryScreen(readKey, compact, readAgain)
                    Destination.Tweaks -> TweaksScreen(readKey, accessEvents, compact, readAgain) { go(Destination.Access) }
                    Destination.Apps -> AppsScreen(readKey, compact, readAgain) { go(Destination.Access) }
                    Destination.Appearance -> app.booxultimatum.ui.screens.AppearanceScreen(readKey, compact) { go(Destination.Fonts) }
                    Destination.Fonts -> app.booxultimatum.ui.screens.FontsScreen(readKey, compact) { go(Destination.Tweaks) }
                    Destination.Sleep -> app.booxultimatum.ui.screens.SleepScreen(readKey, accessEvents)
                    Destination.Ink -> app.booxultimatum.ui.screens.InkScreen(readKey, compact)
                    Destination.Hub -> HubScreen(readKey, compact)
                    Destination.Device -> DeviceScreen(readKey, compact, readAgain)
                    Destination.Access -> AccessScreen(readKey, accessEvents, compact, readAgain)
                }
            }
        }
    }
    }
}
