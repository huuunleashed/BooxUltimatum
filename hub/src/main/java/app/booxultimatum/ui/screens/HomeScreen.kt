package app.booxultimatum.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.booxultimatum.R
import app.booxultimatum.kit.ui.ErrorLine
import app.booxultimatum.kit.ui.InstrumentPage
import app.booxultimatum.kit.ui.Paragraph
import app.booxultimatum.kit.ui.Plate
import app.booxultimatum.kit.ui.Reading
import app.booxultimatum.kit.ui.ScreenHeader
import app.booxultimatum.kit.ui.rememberReading
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import kotlinx.coroutines.launch

/** The Home screen module's page: which home the tablet uses, with the way back to Boox always one tap away. */
@Composable
fun HomeScreenPage(readKey: Int, compact: Boolean) {
    InstrumentPage(compact) {
        item {
            ScreenHeader(stringResource(R.string.dest_home), null)
            HomeScreenPlate(readKey)
        }
    }
}

/**
 * Safe launcher switching: try any installed home app, with the Boox home one tap away, and every Boox
 * function listed as a shortcut so switching never strands a feature.
 */
@Composable
private fun HomeScreenPlate(readKey: Int) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var changeKey by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val launchers = rememberReading(readKey to changeKey) { app.booxultimatum.core.Launchers.list(context) }
    val shortcuts = rememberReading(readKey) { app.booxultimatum.core.Launchers.booxShortcuts(context) }
    val shell = rememberReading(readKey to changeKey) { app.booxultimatum.core.exec.Privileged.ready() } ?: false
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showShortcuts by rememberSaveable { mutableStateOf(false) }

    fun act(block: suspend () -> app.booxultimatum.core.exec.ShellResult) {
        busy = true; error = null
        scope.launch {
            val r = block()
            if (!r.ok) error = context.getString(R.string.tweak_failed, r.message)
            busy = false; changeKey++
        }
    }

    Plate(stringResource(R.string.plate_home)) {
        Paragraph(stringResource(R.string.home_explain), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
        val ourAlias = remember { android.content.ComponentName(context, "app.booxultimatum.launcher.Home") }
        var ourEnabled by remember(changeKey) {
            mutableStateOf(context.packageManager.getComponentEnabledSetting(ourAlias) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = Space.m)) {
                Text(stringResource(R.string.home_ours), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(if (ourEnabled) R.string.home_ours_on else R.string.home_ours_off), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend)
            }
            app.booxultimatum.kit.ui.Key(stringResource(R.string.home_preview), onClick = {
                context.startActivity(android.content.Intent(context, app.booxultimatum.launcher.LauncherActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            Spacer(Modifier.size(Space.s))
            app.booxultimatum.kit.ui.Key(
                stringResource(if (ourEnabled) R.string.home_ours_disable else R.string.home_ours_enable),
                enabled = !busy,
                onClick = {
                    act {
                        // Hand home back to Boox before hiding our home entry, so the tablet is never left without a home.
                        if (ourEnabled && launchers?.firstOrNull { it.isDefault }?.component?.packageName == context.packageName) {
                            val r = app.booxultimatum.core.Launchers.restore(context)
                            if (!r.ok) return@act r
                        }
                        context.packageManager.setComponentEnabledSetting(
                            ourAlias,
                            if (ourEnabled) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            android.content.pm.PackageManager.DONT_KILL_APP,
                        )
                        ourEnabled = !ourEnabled
                        app.booxultimatum.core.exec.ShellResult(0, "", "")
                    }
                },
            )
        }
        HorizontalDivider(thickness = Lines.engraved, color = Ink.Black)
        if (launchers == null) { Reading(); return@Plate }
        launchers.forEach { l ->
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    app.booxultimatum.kit.ui.Lamp(l.isDefault)
                    Spacer(Modifier.size(Space.m))
                    Column(Modifier.weight(1f).padding(end = Space.m)) {
                        Text(l.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(
                                when {
                                    l.isBoox -> R.string.home_boox
                                    l.component.packageName == context.packageName -> R.string.home_ours_desc
                                    else -> R.string.home_other
                                },
                            ) +
                                if (l.isDefault) "  ·  " + stringResource(R.string.home_current) else "",
                            style = MaterialTheme.typography.bodyMedium, color = Ink.Legend,
                        )
                    }
                    if (!l.isDefault) {
                        app.booxultimatum.kit.ui.Key(
                            stringResource(if (l.isBoox) R.string.action_back_to_boox else R.string.action_use_launcher),
                            enabled = !busy,
                            primary = l.isBoox,
                            onClick = { act { if (l.isBoox) app.booxultimatum.core.Launchers.restore(context) else app.booxultimatum.core.Launchers.setDefault(context, l.component) } },
                        )
                    }
                }
                HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
            }
        }
        if (launchers.count { !it.isBoox } == 0) {
            Spacer(Modifier.height(Space.s))
            Paragraph(stringResource(R.string.home_only_boox), color = Ink.Legend)
        }
        if (!shell) {
            Spacer(Modifier.height(Space.s))
            Paragraph(stringResource(R.string.home_needs_shizuku), color = Ink.Legend)
            Spacer(Modifier.height(Space.s))
            app.booxultimatum.kit.ui.Key(stringResource(R.string.action_home_settings), onClick = {
                runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            })
        }
        error?.let { ErrorLine(it) }
        // Android never stops the home app, so while ours is home its background work is safe. Otherwise Boox's
        // restriction can stop the sleep screen's refreshes, live updates and the battery log.
        val oursIsHome = launchers.any { it.isDefault && it.component.packageName == context.packageName }
        val restricted = remember(launchers) { app.booxultimatum.core.sleep.LiveSleep.backgroundAllowed(context).not() }
        if (!oursIsHome && restricted) {
            Spacer(Modifier.height(Space.s))
            Paragraph(stringResource(R.string.home_not_home_background), color = Ink.Legend)
            Spacer(Modifier.height(Space.s))
            app.booxultimatum.kit.ui.Key(stringResource(R.string.sl_live_bg_open), onClick = { openBatteryPage(context, context.packageName) })
        }
        Spacer(Modifier.height(Space.m))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.home_shortcuts, shortcuts?.size ?: 0),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            app.booxultimatum.kit.ui.Key(stringResource(if (showShortcuts) R.string.action_hide else R.string.action_show_all), onClick = { showShortcuts = !showShortcuts })
        }
        Text(stringResource(R.string.home_shortcuts_explain), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
        if (showShortcuts) shortcuts?.forEach { s ->
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                context.packageManager.getLaunchIntentForPackage(s.pkg)?.let { context.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.label, style = MaterialTheme.typography.bodyLarge)
                        Text(s.pkg, style = app.booxultimatum.kit.ui.theme.CodeStyle, color = Ink.Legend)
                    }
                    Text(stringResource(R.string.action_open), style = MaterialTheme.typography.labelLarge)
                }
                HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
            }
        }
    }
}
