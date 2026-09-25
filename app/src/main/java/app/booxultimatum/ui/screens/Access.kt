package app.booxultimatum.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.booxultimatum.R
import app.booxultimatum.core.PrivilegeStatus
import app.booxultimatum.ui.CodeBlock
import app.booxultimatum.ui.InstrumentPage
import app.booxultimatum.ui.Key
import app.booxultimatum.ui.LampRow
import app.booxultimatum.ui.Paragraph
import app.booxultimatum.ui.Plate
import app.booxultimatum.ui.Reading
import app.booxultimatum.ui.ScreenHeader
import app.booxultimatum.ui.rememberReading
import app.booxultimatum.ui.theme.Ink
import app.booxultimatum.ui.theme.Space

@Composable
fun AccessScreen(readKey: Int, accessKey: Int, compact: Boolean, onReadAgain: () -> Unit) {
    val context = LocalContext.current
    val access = rememberReading(readKey to accessKey) { PrivilegeStatus.check(context) }
    var shizukuMessage by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    InstrumentPage(compact) {
        item {
            ScreenHeader(stringResource(R.string.dest_access), stringResource(R.string.access_subtitle)) {
                Key(stringResource(R.string.action_read_again), onClick = onReadAgain, primary = true)
            }
        }
        if (access == null) {
            item { Reading() }
            return@InstrumentPage
        }
        item {
            Plate(stringResource(R.string.tier_app)) {
                LampRow(true, stringResource(R.string.tier_app_state), stringResource(R.string.tier_app_explain))
            }
            Spacer(Modifier.height(Space.xl))
        }
        item {
            Plate(stringResource(R.string.tier_adb)) {
                Paragraph(stringResource(R.string.tier_adb_explain), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
                LampRow(access.secureSettings, stringResource(R.string.grant_secure_settings), stringResource(R.string.grant_secure_settings_why))
                LampRow(access.dump, stringResource(R.string.grant_dump), stringResource(R.string.grant_dump_why))
                LampRow(access.readLogs, stringResource(R.string.grant_logs), stringResource(R.string.grant_logs_why))
                LampRow(access.usageStats, stringResource(R.string.grant_usage), stringResource(R.string.grant_usage_why))
                if (access.t1Granted < PrivilegeStatus.T1_GRANTS) {
                    Spacer(Modifier.height(Space.m))
                    Text(stringResource(R.string.grant_how), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(Space.s))
                    SelectionContainer { CodeBlock(PrivilegeStatus.ADB_GRANT_HELP) }
                    Spacer(Modifier.height(Space.m))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        Key(stringResource(R.string.action_copy_commands), onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("adb", PrivilegeStatus.ADB_GRANT_HELP))
                            copied = true
                        })
                        if (copied) Text(stringResource(R.string.state_copied), style = MaterialTheme.typography.labelLarge, color = Ink.Legend)
                    }
                }
            }
            Spacer(Modifier.height(Space.xl))
        }
        item {
            Plate(stringResource(R.string.tier_shizuku)) {
                Paragraph(stringResource(R.string.tier_shizuku_explain), color = Ink.Legend, modifier = Modifier.padding(vertical = Space.s))
                LampRow(access.shizukuRunning, stringResource(R.string.shizuku_server), stringResource(
                    if (access.shizukuRunning) R.string.shizuku_server_on else R.string.shizuku_server_off,
                ))
                LampRow(access.shizukuGranted, stringResource(R.string.shizuku_permission), stringResource(
                    if (access.shizukuGranted) R.string.shizuku_permission_on else R.string.shizuku_permission_off,
                ))
                if (!access.shizukuGranted) {
                    Spacer(Modifier.height(Space.m))
                    Key(stringResource(R.string.action_request_shizuku), primary = false, onClick = {
                        shizukuMessage = if (PrivilegeStatus.requestShizuku()) null else context.getString(R.string.shizuku_unreachable)
                        onReadAgain()
                    })
                }
                shizukuMessage?.let {
                    Spacer(Modifier.height(Space.s))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink.Alert)
                }
            }
            Spacer(Modifier.height(Space.xl))
        }
        item {
            Plate(stringResource(R.string.tier_root)) {
                Column(Modifier.fillMaxWidth()) {
                    LampRow(access.suBinary != null, stringResource(R.string.tier_root_state), stringResource(R.string.tier_root_explain))
                }
            }
        }
    }
}
