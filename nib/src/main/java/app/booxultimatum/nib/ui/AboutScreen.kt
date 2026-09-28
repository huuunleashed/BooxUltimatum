package app.booxultimatum.nib.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.booxultimatum.kit.update.UpdatePhase
import app.booxultimatum.kit.update.UpdateText
import app.booxultimatum.kit.update.Updates
import app.booxultimatum.nib.BuildConfig
import app.booxultimatum.nib.R
import app.booxultimatum.nib.export.Exporter
import app.booxultimatum.nib.export.LogShare
import app.booxultimatum.nib.ui.studio.ButtonKind
import app.booxultimatum.nib.ui.studio.SlabButton
import app.booxultimatum.nib.ui.studio.Studio
import app.booxultimatum.nib.ui.studio.StudioGlyphs
import app.booxultimatum.nib.ui.studio.StudioType
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
    StudioPage(stringResource(R.string.title_about), stringResource(R.string.action_back), onBack) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Studio.S5, vertical = Studio.S3),
            verticalArrangement = Arrangement.spacedBy(Studio.S5),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StudioSection(stringResource(R.string.app_name)) {
                Text(stringResource(R.string.about_summary), style = StudioType.Body)
                SpecLine(stringResource(R.string.about_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                SpecLine(stringResource(R.string.about_build), stringResource(if (release) R.string.about_build_release else R.string.about_build_development))
            }
            StudioSection(stringResource(R.string.about_updates)) {
                if (hub != null) {
                    Text(stringResource(R.string.about_updates_hub, hub.versionName), style = StudioType.Body)
                    SlabButton(stringResource(R.string.about_open_hub), onClick = { if (!Suite.open(context, SuiteApp.Hub)) error = context.getString(R.string.message_no_hub) })
                } else {
                    val u = updates[SuiteApp.Nib]
                    Text(u?.let { UpdateText.describe(context, it) } ?: stringResource(R.string.about_updates_idle), style = StudioType.Body)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Studio.S2), verticalArrangement = Arrangement.spacedBy(Studio.S2)) {
                        SlabButton(stringResource(R.string.about_check), onClick = { Updates.checkNow(context) }, enabled = updates.releaseBuild && u?.busy != true)
                        if (u?.canInstall == true) SlabButton(stringResource(R.string.about_install), onClick = { Updates.install(context, SuiteApp.Nib) }, kind = ButtonKind.Primary)
                        if (u?.phase == UpdatePhase.NeedsUnknownSources) SlabButton(stringResource(R.string.about_allow_installs), onClick = { Updates.openUnknownSources(context) })
                    }
                }
            }
            StudioSection(stringResource(R.string.about_logs)) {
                Text(stringResource(R.string.about_logs_detail), style = StudioType.Body)
                SlabButton(stringResource(R.string.about_share_logs), icon = StudioGlyphs.Share, onClick = {
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
                error?.let { Text(it, style = StudioType.Label, color = Studio.Alert) }
            }
            StudioSection(stringResource(R.string.about_licence_title)) {
                Text(stringResource(R.string.about_licence), style = StudioType.Body)
                Text(stringResource(R.string.about_disclaimer), style = StudioType.Body, color = Studio.Legend)
            }
        }
    }
}
