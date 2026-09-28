package app.booxultimatum.nib.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.booxultimatum.kit.core.Suite
import app.booxultimatum.kit.core.SuiteApp
import app.booxultimatum.kit.ui.ErrorLine
import app.booxultimatum.kit.ui.Glyphs
import app.booxultimatum.kit.ui.Key
import app.booxultimatum.kit.ui.Paragraph
import app.booxultimatum.kit.ui.Plate
import app.booxultimatum.kit.ui.SpecRow
import app.booxultimatum.kit.ui.StatusStrip
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.Space
import app.booxultimatum.kit.update.UpdatePhase
import app.booxultimatum.kit.update.UpdateText
import app.booxultimatum.kit.update.Updates
import app.booxultimatum.nib.BuildConfig
import app.booxultimatum.nib.R
import app.booxultimatum.nib.export.Exporter
import app.booxultimatum.nib.export.LogShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** About Nib: version, updates (through the hub when it's installed), logs, licence and the disclaimer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val release = remember { Suite.isReleaseBuild(context) }
    val hub = remember { Suite.installed(context, SuiteApp.Hub) }
    val updates by Updates.state.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        StatusStrip()
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            ToolKey(Glyphs.ChevronLeft, stringResource(R.string.action_back), onClick = onBack)
            Spacer(Modifier.width(Space.m))
            Text(stringResource(R.string.title_about), style = MaterialTheme.typography.headlineMedium)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.l, vertical = Space.m)) {
            Plate(stringResource(R.string.app_name)) {
                Paragraph(stringResource(R.string.about_summary))
                Spacer(Modifier.height(Space.s))
                SpecRow(stringResource(R.string.about_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                SpecRow(stringResource(R.string.about_build), stringResource(if (release) R.string.about_build_release else R.string.about_build_development))
            }
            Spacer(Modifier.height(Space.xl))
            Plate(stringResource(R.string.about_updates)) {
                if (hub != null) {
                    Paragraph(stringResource(R.string.about_updates_hub, hub.versionName))
                    Spacer(Modifier.height(Space.m))
                    Key(stringResource(R.string.about_open_hub), onClick = { if (!Suite.open(context, SuiteApp.Hub)) error = context.getString(R.string.message_no_hub) })
                } else {
                    val u = updates[SuiteApp.Nib]
                    Paragraph(u?.let { UpdateText.describe(context, it) } ?: stringResource(R.string.about_updates_idle))
                    Spacer(Modifier.height(Space.m))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Key(stringResource(R.string.about_check), onClick = { Updates.checkNow(context) }, enabled = updates.releaseBuild && u?.busy != true)
                        if (u?.canInstall == true) Key(stringResource(R.string.about_install), onClick = { Updates.install(context, SuiteApp.Nib) }, primary = true)
                        if (u?.phase == UpdatePhase.NeedsUnknownSources) Key(stringResource(R.string.about_allow_installs), onClick = { Updates.openUnknownSources(context) })
                    }
                }
            }
            Spacer(Modifier.height(Space.xl))
            Plate(stringResource(R.string.about_logs)) {
                Paragraph(stringResource(R.string.about_logs_detail))
                Spacer(Modifier.height(Space.m))
                Key(stringResource(R.string.about_share_logs), onClick = {
                    scope.launch {
                        val zip = runCatching { withContext(Dispatchers.IO) { LogShare.build(context) } }.getOrNull()
                        if (zip == null) {
                            error = context.getString(R.string.message_logs_failed)
                        } else {
                            try {
                                context.startActivity(Exporter.shareFile(context, zip, "application/zip"))
                            } catch (_: ActivityNotFoundException) {
                                error = context.getString(R.string.message_no_share)
                            }
                        }
                    }
                })
                error?.let { ErrorLine(it) }
            }
            Spacer(Modifier.height(Space.xl))
            Plate(stringResource(R.string.about_licence_title)) {
                Paragraph(stringResource(R.string.about_licence))
                Spacer(Modifier.height(Space.s))
                Paragraph(stringResource(R.string.about_disclaimer), color = Ink.Legend)
            }
        }
    }
}
