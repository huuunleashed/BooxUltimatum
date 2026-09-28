package app.booxultimatum.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.booxultimatum.R
import app.booxultimatum.core.suite.Module
import app.booxultimatum.core.suite.ModuleBlocked
import app.booxultimatum.core.suite.Modules
import app.booxultimatum.kit.core.Suite
import app.booxultimatum.kit.core.SuiteApp
import app.booxultimatum.kit.ui.ConfirmKey
import app.booxultimatum.kit.ui.ErrorLine
import app.booxultimatum.kit.ui.Glyphs
import app.booxultimatum.kit.ui.InstrumentPage
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.Lamp
import app.booxultimatum.kit.ui.Paragraph
import app.booxultimatum.kit.ui.Plate
import app.booxultimatum.kit.ui.ScreenHeader
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Lines
import app.booxultimatum.kit.ui.theme.Space
import app.booxultimatum.kit.update.AppUpdate
import app.booxultimatum.kit.update.UpdatePhase
import app.booxultimatum.kit.update.UpdateText
import app.booxultimatum.kit.update.Updates
import app.booxultimatum.ui.Destination
import kotlinx.coroutines.launch

/**
 * The suite at a glance: the apps that build on BooxUltimatum, installed and updated from here, and the modules built
 * into this app, which can be taken out and added back.
 */
@Composable
fun SuiteScreen(readKey: Int, compact: Boolean, onModulesChanged: () -> Unit, onOpen: (Destination) -> Unit) {
    val context = LocalContext.current
    val updates by Updates.state.collectAsState()
    var changeKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { Updates.init(context, SuiteApp.entries.toSet()) }
    // Back from Android's installer or uninstaller: read what's installed again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { Updates.refreshInstalled(context); changeKey++ }

    InstrumentPage(compact) {
        item {
            ScreenHeader(stringResource(R.string.suite_title), stringResource(R.string.suite_subtitle)) {
                Key(stringResource(R.string.about_check_updates), enabled = updates.releaseBuild && !updates.checking, onClick = { Updates.checkNow(context) })
            }
        }
        item {
            Plate(stringResource(R.string.suite_apps)) {
                Paragraph(stringResource(R.string.suite_apps_explain), color = Ink.Legend, modifier = Modifier.padding(bottom = Space.s))
                SuiteApp.entries.filter { it != SuiteApp.Hub }.forEach { app ->
                    updates[app]?.let { AppRow(it) }
                }
            }
            Spacer(Modifier.height(Space.xl))
        }
        item {
            Plate(stringResource(R.string.suite_modules)) {
                Paragraph(stringResource(R.string.suite_modules_explain), color = Ink.Legend, modifier = Modifier.padding(bottom = Space.s))
                Modules.available(context).forEach { m ->
                    ModuleRow(m, readKey to changeKey, onChanged = { changeKey++; onModulesChanged() }, onOpen = onOpen)
                }
            }
        }
    }
}

@Composable
private fun AppRow(update: AppUpdate) {
    val context = LocalContext.current
    val app = update.app
    val installed = update.installed
    Column(Modifier.fillMaxWidth().padding(vertical = Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (app == SuiteApp.Nib) Glyphs.Nib else Glyphs.Suite, contentDescription = null)
            Spacer(Modifier.size(Space.m))
            Column(Modifier.weight(1f)) {
                Text(app.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    installed?.let { stringResource(R.string.suite_installed, it.versionName) } ?: stringResource(R.string.suite_not_installed),
                    style = MaterialTheme.typography.bodyMedium, color = Ink.Legend,
                )
            }
        }
        if (app == SuiteApp.Nib) Paragraph(stringResource(R.string.suite_nib_summary), modifier = Modifier.padding(top = Space.xs))
        Text(UpdateText.describe(context, update), style = MaterialTheme.typography.bodyMedium, color = Ink.Legend, modifier = Modifier.padding(top = Space.xs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(top = Space.s)) {
            if (update.canInstall) {
                Key(stringResource(if (installed == null) R.string.suite_install else R.string.suite_update), primary = true, onClick = { Updates.install(context, app) })
            }
            if (update.phase is UpdatePhase.NeedsUnknownSources) {
                Key(stringResource(R.string.about_unknown_sources), onClick = { Updates.openUnknownSources(context) })
            }
            if (installed != null) {
                Key(stringResource(R.string.suite_open), onClick = { Suite.open(context, app) })
                ConfirmKey(stringResource(R.string.suite_uninstall), stringResource(R.string.suite_uninstall_confirm), onConfirm = { Suite.uninstall(context, app) })
            }
            Key(stringResource(R.string.about_release_page), onClick = { Updates.openReleasePage(context, app) })
        }
        Spacer(Modifier.height(Space.s))
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}

@Composable
private fun ModuleRow(module: Module, key: Any, onChanged: () -> Unit, onOpen: (Destination) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val added = remember(key) { Modules.added(context, module) }
    val running = remember(key) { Modules.running(context, module) }
    var busy by remember { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    val page = Destination.entries.firstOrNull { it.module == module }
    Column(Modifier.fillMaxWidth().padding(vertical = Space.s)) {
        Row(verticalAlignment = Alignment.Top) {
            Lamp(added && running, Modifier.padding(top = Space.xs))
            Spacer(Modifier.size(Space.m))
            Column(Modifier.weight(1f)) {
                Text(stringResource(module.title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (!added) R.string.suite_removed else if (running) R.string.suite_running else R.string.suite_idle),
                    style = MaterialTheme.typography.bodyMedium, color = Ink.Legend,
                )
                Paragraph(stringResource(module.summary), modifier = Modifier.padding(top = Space.xs))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(top = Space.s)) {
            if (added) {
                if (page != null) Key(stringResource(R.string.suite_go_to), onClick = { onOpen(page) })
                if (module == Module.BatteryLog) Key(stringResource(R.string.suite_go_to), onClick = { onOpen(Destination.Battery) })
                ConfirmKey(stringResource(R.string.suite_remove), stringResource(R.string.suite_remove_confirm), enabled = !busy, onConfirm = {
                    busy = true
                    scope.launch {
                        Modules.remove(context, module).onFailure { e ->
                            error = if (e is ModuleBlocked) context.getString(e.reason) else e.message ?: e.javaClass.simpleName
                        }
                        busy = false
                        onChanged()
                    }
                })
            } else {
                Key(stringResource(R.string.suite_add), primary = true, onClick = { Modules.add(context, module); onChanged() })
            }
        }
        error?.let { ErrorLine(it) }
        Spacer(Modifier.height(Space.s))
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}
