package app.booxultimatum.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.booxultimatum.R
import app.booxultimatum.core.AllowState
import app.booxultimatum.core.AppFilter
import app.booxultimatum.core.AppInspector
import app.booxultimatum.core.BgMode
import app.booxultimatum.core.Journal
import app.booxultimatum.core.PackageEntry
import app.booxultimatum.core.PackageScanner
import app.booxultimatum.core.Risk
import app.booxultimatum.core.SystemState
import app.booxultimatum.core.exec.Privileged
import app.booxultimatum.core.exec.ShellResult
import app.booxultimatum.ui.ConfirmKey
import app.booxultimatum.ui.ErrorLine
import app.booxultimatum.ui.InstrumentPage
import app.booxultimatum.ui.Key
import app.booxultimatum.ui.Pager
import app.booxultimatum.ui.Paragraph
import app.booxultimatum.ui.Plate
import app.booxultimatum.ui.Reading
import app.booxultimatum.ui.ScreenHeader
import app.booxultimatum.ui.SearchField
import app.booxultimatum.ui.SpecRow
import app.booxultimatum.ui.Tag
import app.booxultimatum.ui.rememberReading
import app.booxultimatum.ui.theme.CodeStyle
import app.booxultimatum.ui.theme.Ink
import app.booxultimatum.ui.theme.Lines
import app.booxultimatum.ui.theme.Space
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private const val APPS_PER_PAGE = 9

@Composable
fun AppsScreen(readKey: Int, compact: Boolean, onReadAgain: () -> Unit, onOpenAccess: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val current = selected
    if (current != null) {
        BackHandler { selected = null }
        AppDetailScreen(current, readKey, compact, onBack = { selected = null }, onOpenAccess = onOpenAccess)
    } else {
        AppListScreen(readKey, compact, onReadAgain, onOpen = { selected = it })
    }
}

@Composable
private fun AppListScreen(readKey: Int, compact: Boolean, onReadAgain: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val inventory = rememberReading(readKey) { PackageScanner.scan(context) }
    var filter by rememberSaveable { mutableStateOf(AppFilter.Boox) }
    var query by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(0) }

    InstrumentPage(compact) {
        item {
            ScreenHeader(stringResource(R.string.dest_apps), stringResource(R.string.apps_subtitle)) {
                Key(stringResource(R.string.action_read_again), onClick = onReadAgain, primary = true)
            }
        }
        if (inventory == null) {
            item { Reading() }
            return@InstrumentPage
        }
        item {
            SearchField(query, { query = it; page = 0 }, stringResource(R.string.apps_search))
            Spacer(Modifier.height(Space.m))
            val filters = AppFilter.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                filters.forEachIndexed { i, f ->
                    SegmentedButton(
                        selected = filter == f,
                        onClick = { filter = f; page = 0 },
                        shape = SegmentedButtonDefaults.itemShape(i, filters.size),
                        icon = {},
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = Ink.Black, activeContentColor = Ink.Paper, activeBorderColor = Ink.Black,
                            inactiveContainerColor = Ink.Paper, inactiveContentColor = Ink.Black, inactiveBorderColor = Ink.Black,
                        ),
                    ) {
                        Text(stringResource(filterLabel(f), inventory.count(f)), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(Space.m))
            Paragraph(stringResource(filterExplain(filter)), color = Ink.Legend)
            if (filter == AppFilter.Boox && query.isBlank()) {
                val boox = inventory.filter(AppFilter.Boox)
                Spacer(Modifier.height(Space.s))
                Text(stringResource(R.string.apps_boox_summary, boox.count { it.dozeExempt }, boox.size), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(Space.l))
            HorizontalDivider(thickness = Lines.engraved, color = Ink.Black)
        }
        val q = query.trim()
        val list = inventory.filter(filter).filter { q.isEmpty() || it.label.contains(q, true) || it.id.contains(q, true) }
        val pages = ((list.size + APPS_PER_PAGE - 1) / APPS_PER_PAGE).coerceAtLeast(1)
        val safePage = page.coerceIn(0, pages - 1)
        if (list.isEmpty()) {
            item {
                Paragraph(
                    if (q.isEmpty()) stringResource(R.string.apps_empty) else stringResource(R.string.apps_no_match, q),
                    modifier = Modifier.padding(vertical = Space.l),
                )
            }
        } else if (q.isNotEmpty()) {
            item { Text(stringResource(R.string.apps_matches, list.size), style = MaterialTheme.typography.labelLarge, color = Ink.Legend, modifier = Modifier.padding(top = Space.m)) }
        }
        items(list.drop(safePage * APPS_PER_PAGE).take(APPS_PER_PAGE), key = { it.id }) { AppRow(it) { onOpen(it.id) } }
        if (pages > 1) item { Pager(safePage, pages, onPage = { page = it }) }
    }
}

@Composable
private fun AppRow(p: PackageEntry, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(p.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(end = Space.m))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Only departures from the norm get a mark: most rows should read clean.
                    if (p.dozeExempt) Tag(stringResource(R.string.tag_doze_exempt), strong = true)
                    if (p.bgRestricted == true) Tag(stringResource(R.string.tag_bg_restricted), strong = true)
                    if (!p.enabled) Tag(stringResource(R.string.tag_disabled))
                    if (!p.system) Tag(stringResource(R.string.tag_user))
                }
            }
            Text(p.id, style = CodeStyle, color = Ink.Legend, modifier = Modifier.padding(top = 2.dp))
            p.kb?.let { kb -> Text(kbLine(kb.purpose, kb.risk), style = MaterialTheme.typography.bodyMedium, color = if (kb.purpose.isEmpty()) Ink.Legend else Ink.Black, modifier = Modifier.padding(top = Space.s)) }
        }
        HorizontalDivider(thickness = Lines.hairline, color = Ink.Rule)
    }
}

@Composable
private fun kbLine(purpose: String, risk: Risk) = when {
    purpose.isEmpty() && risk == Risk.Unknown -> stringResource(R.string.kb_unanalysed)
    purpose.isEmpty() -> stringResource(riskName(risk))
    else -> stringResource(R.string.kb_line, purpose, stringResource(riskName(risk)))
}

@Composable
private fun AppDetailScreen(pkg: String, readKey: Int, compact: Boolean, onBack: () -> Unit, onOpenAccess: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var changeKey by remember { mutableIntStateOf(0) }
    val detail = rememberReading(Triple(pkg, readKey, changeKey)) { AppInspector.inspect(context, pkg) }
    val shell = rememberReading(Pair(readKey, changeKey)) { Privileged.ready() } ?: false
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun act(label: String, doneText: String, block: suspend () -> ShellResult) {
        busy = true
        error = null
        message = null
        scope.launch {
            val r = block()
            Journal.log(context, "app", "$label · ${detail?.label ?: pkg}", if (r.ok) "" else r.message, r.ok)
            if (r.ok) message = doneText else error = context.getString(R.string.tweak_failed, r.message)
            busy = false
            changeKey++
        }
    }

    InstrumentPage(compact) {
        item {
            ScreenHeader(detail?.label ?: pkg, pkg) { Key(stringResource(R.string.action_back_to_apps), onClick = onBack) }
        }
        if (detail == null) {
            item { Reading() }
            return@InstrumentPage
        }
        item {
            Plate(stringResource(R.string.plate_about_app)) {
                detail.kb?.let { SpecRow(stringResource(R.string.app_purpose), kbLine(it.purpose, it.risk)) }
                detail.versionName?.let { SpecRow(stringResource(R.string.app_version), it) }
                SpecRow(stringResource(R.string.app_kind), stringResource(if (detail.system) R.string.app_kind_system else R.string.app_kind_user))
                SpecRow(stringResource(R.string.app_status), stringResource(if (detail.enabled) R.string.app_enabled else R.string.app_disabled))
                SpecRow(stringResource(R.string.app_target), detail.targetSdk.toString())
                SpecRow(stringResource(R.string.app_installed), DateFormat.getDateInstance().format(Date(detail.firstInstall)))
                SpecRow(stringResource(R.string.app_updated), DateFormat.getDateInstance().format(Date(detail.lastUpdate)))
                detail.installer?.let { SpecRow(stringResource(R.string.app_installer), it, code = true) }
            }
            Spacer(Modifier.height(Space.xl))
        }
        item {
            Plate(stringResource(R.string.plate_app_power)) {
                SpecRow(stringResource(R.string.app_doze), stringResource(allowName(detail.allow)))
                SpecRow(stringResource(R.string.app_background), stringResource(bgName(detail.background)))
                SpecRow(stringResource(R.string.app_bucket), detail.bucket?.let { stringResource(bucketName(it)) } ?: stringResource(R.string.value_needs_shizuku))
                Spacer(Modifier.height(Space.m))
                if (!shell) {
                    // Android's own per-app battery page holds the same switch, so the fix works without Shizuku too.
                    Paragraph(stringResource(R.string.app_needs_shizuku_bg), color = Ink.Legend)
                    Spacer(Modifier.height(Space.s))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Key(stringResource(R.string.action_battery_page), primary = true, onClick = { openBatteryPage(context, pkg) })
                        Key(stringResource(R.string.action_boox_freeze), onClick = { openBooxFreezePage(context) })
                        Key(stringResource(R.string.action_get_access), onClick = onOpenAccess)
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        when (detail.allow) {
                            AllowState.User -> Key(stringResource(R.string.action_let_sleep), enabled = !busy, onClick = {
                                act(context.getString(R.string.action_let_sleep), context.getString(R.string.done_let_sleep)) { SystemState.setDozeAllowed(pkg, false) }
                            })
                            AllowState.None -> Key(stringResource(R.string.action_keep_awake), enabled = !busy, onClick = {
                                act(context.getString(R.string.action_keep_awake), context.getString(R.string.done_keep_awake)) { SystemState.setDozeAllowed(pkg, true) }
                            })
                            else -> {}
                        }
                        if (detail.background == BgMode.Ignore) {
                            Key(stringResource(R.string.action_allow_background), primary = true, enabled = !busy, onClick = {
                                act(context.getString(R.string.action_allow_background), context.getString(R.string.done_allow_background)) { SystemState.setBackgroundMode(pkg, BgMode.Allow) }
                            })
                        } else {
                            Key(stringResource(R.string.action_restrict_background), enabled = !busy, onClick = {
                                act(context.getString(R.string.action_restrict_background), context.getString(R.string.done_restrict_background)) { SystemState.setBackgroundMode(pkg, BgMode.Ignore) }
                            })
                        }
                        // Boox's own freeze switch is a separate layer from Android's background setting, so it's offered here too.
                        Key(stringResource(R.string.action_boox_freeze), onClick = { openBooxFreezePage(context) })
                    }
                    if (detail.background == BgMode.Ignore && !detail.system) {
                        Spacer(Modifier.height(Space.s))
                        Paragraph(stringResource(R.string.app_bg_restricted_note), color = Ink.Legend)
                    }
                    if (detail.allow == AllowState.System) {
                        Spacer(Modifier.height(Space.s))
                        Paragraph(stringResource(R.string.app_system_allow_note), color = Ink.Legend)
                    }
                }
            }
            Spacer(Modifier.height(Space.xl))
        }
        item {
            Plate(stringResource(R.string.plate_app_actions)) {
                Spacer(Modifier.height(Space.s))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    if (detail.launchable && detail.enabled) {
                        Key(stringResource(R.string.action_open), onClick = {
                            context.packageManager.getLaunchIntentForPackage(pkg)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        })
                    }
                    Key(stringResource(R.string.action_app_info), onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    })
                    if (shell && pkg != context.packageName) {
                        Key(stringResource(R.string.action_force_stop), enabled = !busy, onClick = {
                            act(context.getString(R.string.action_force_stop), context.getString(R.string.done_force_stop)) { SystemState.forceStop(pkg) }
                        })
                        if (detail.enabled) {
                            ConfirmKey(
                                stringResource(R.string.action_disable),
                                stringResource(R.string.action_disable_confirm),
                                enabled = !busy,
                                onConfirm = { act(context.getString(R.string.action_disable), context.getString(R.string.done_disable)) { SystemState.setEnabled(pkg, false) } },
                            )
                        } else {
                            Key(stringResource(R.string.action_enable), enabled = !busy, onClick = {
                                act(context.getString(R.string.action_enable), context.getString(R.string.done_enable)) { SystemState.setEnabled(pkg, true) }
                            })
                        }
                    }
                }
                if (detail.enabled && shell && pkg != context.packageName) {
                    Spacer(Modifier.height(Space.s))
                    Text(stringResource(R.string.app_disable_note), style = MaterialTheme.typography.bodySmall, color = Ink.Legend)
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.m)) }
                error?.let { ErrorLine(it) }
            }
        }
    }
}

/**
 * Android's App battery usage page for [pkg], where "Allow background usage" is the same switch Boox turns off
 * (`RUN_ANY_IN_BACKGROUND`). Falls back to App info when a firmware lacks the direct page.
 */
fun openBatteryPage(context: android.content.Context, pkg: String) {
    val direct = Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(direct) }.isFailure) {
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/**
 * Boox's App Freeze page, which FW 4.3 keeps but hides from its menus: *Freeze new apps* and a freeze switch per app.
 * Opened by its Onyx settings action (verified 2026-09-27); falls back to Boox Settings.
 */
fun openBooxFreezePage(context: android.content.Context) {
    val freeze = Intent("onyx.settings.action.APP_FREEZE_MANAGEMENT").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(freeze) }.isFailure) app.booxultimatum.launcher.BooxIntents.openSettings(context)
}

private fun allowName(a: AllowState) = when (a) {
    AllowState.User -> R.string.allow_user
    AllowState.System -> R.string.allow_system
    AllowState.None -> R.string.allow_none
    AllowState.Unknown -> R.string.allow_unknown
}

private fun bgName(b: BgMode) = when (b) {
    BgMode.Allow -> R.string.bg_allow
    BgMode.Ignore -> R.string.bg_ignore
    BgMode.Default -> R.string.bg_default
    BgMode.Unknown -> R.string.value_needs_shizuku
}

private fun bucketName(b: Int) = when {
    b <= 5 -> R.string.bucket_exempt
    b <= 10 -> R.string.bucket_active
    b <= 20 -> R.string.bucket_working
    b <= 30 -> R.string.bucket_frequent
    b <= 40 -> R.string.bucket_rare
    b <= 45 -> R.string.bucket_restricted
    else -> R.string.bucket_never
}

private fun filterLabel(f: AppFilter) = when (f) {
    AppFilter.Boox -> R.string.filter_boox
    AppFilter.Restricted -> R.string.filter_restricted
    AppFilter.DozeExempt -> R.string.filter_doze
    AppFilter.All -> R.string.filter_all
}

private fun filterExplain(f: AppFilter) = when (f) {
    AppFilter.Boox -> R.string.filter_boox_explain
    AppFilter.Restricted -> R.string.filter_restricted_explain
    AppFilter.DozeExempt -> R.string.filter_doze_explain
    AppFilter.All -> R.string.filter_all_explain
}

private fun riskName(r: Risk) = when (r) {
    Risk.Safe -> R.string.risk_safe
    Risk.Caution -> R.string.risk_caution
    Risk.Expert -> R.string.risk_expert
    Risk.Never -> R.string.risk_never
    Risk.Unknown -> R.string.risk_unknown
}
