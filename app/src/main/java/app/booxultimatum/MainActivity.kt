package app.booxultimatum

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.booxultimatum.core.BatterySnapshot
import app.booxultimatum.core.DeviceInfo
import app.booxultimatum.core.PackageScanner
import app.booxultimatum.core.PrivilegeStatus
import app.booxultimatum.ui.EinkButton
import app.booxultimatum.ui.EinkTheme
import app.booxultimatum.ui.KeyValueRows
import app.booxultimatum.ui.SectionTitle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EinkTheme { Surface(Modifier.fillMaxSize(), color = Color.White) { App() } } }
    }
}

private enum class Tab(val title: String) { Device("Device"), Battery("Battery"), Packages("Packages"), Privileges("Privileges") }

@Composable
private fun App() {
    var tab by remember { mutableStateOf(Tab.Device) }
    // Bumped by "Refresh" to re-read data; there is no background polling by design.
    var refresh by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("BooxUltimatum · probe ${BuildConfig.VERSION_NAME}", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tab.entries.forEach { t -> EinkButton(t.title, selected = t == tab) { tab = t } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EinkButton("Refresh") { refresh++ }
            EinkButton("Share report") {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, buildReport(context))
                context.startActivity(Intent.createChooser(send, "Share probe report"))
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Color.Black)
        when (tab) {
            Tab.Device -> Scrollable { SectionTitle("Device"); KeyValueRows(remember(refresh) { DeviceInfo.collect().rows }) }
            Tab.Battery -> Scrollable {
                SectionTitle("Battery snapshot")
                KeyValueRows(remember(refresh) { BatterySnapshot.read(context).rows() })
                Text("Tip: for drain measurements use tools/host/power-logger.ps1 (unplugged runs).", modifier = Modifier.padding(top = 12.dp))
            }
            Tab.Packages -> PackagesScreen(refresh)
            Tab.Privileges -> Scrollable {
                SectionTitle("Privilege tiers")
                KeyValueRows(remember(refresh) { PrivilegeStatus.check(context).rows() })
                Row(Modifier.padding(vertical = 12.dp)) { EinkButton("Request Shizuku permission") { PrivilegeStatus.requestShizuku(); refresh++ } }
                Text("Grant tier T1 from a PC:", fontWeight = FontWeight.SemiBold)
                SelectionContainer { Text(PrivilegeStatus.ADB_GRANT_HELP, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

@Composable
private fun Scrollable(content: @Composable () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) { item { content() } }
}

@Composable
private fun PackagesScreen(refresh: Int) {
    val context = LocalContext.current
    var vendorOnly by remember { mutableStateOf(true) }
    val list = remember(refresh, vendorOnly) { PackageScanner.scan(context, vendorOnly) }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EinkButton("Vendor only", selected = vendorOnly) { vendorOnly = true }
            EinkButton("All", selected = !vendorOnly) { vendorOnly = false }
        }
        Text("${list.size} packages · ${list.count { !it.enabled }} disabled · ${list.count { it.kb != null }} in knowledge base", modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { p ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(p.id, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    val flags = listOfNotNull(if (p.system) "system" else "user", if (!p.enabled) "DISABLED" else null).joinToString(" · ")
                    Text("${p.label} · $flags")
                    p.kb?.let { Text("KB: ${it.purpose} [risk: ${it.risk}]") }
                }
                HorizontalDivider(color = Color.Black, thickness = 0.5.dp)
            }
        }
    }
}

private fun buildReport(context: android.content.Context): String = buildString {
    fun section(title: String, rows: List<Pair<String, String>>) {
        appendLine("## $title")
        rows.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine()
    }
    appendLine("# BooxUltimatum probe report (${BuildConfig.VERSION_NAME})")
    section("Device", DeviceInfo.collect().rows)
    section("Battery", BatterySnapshot.read(context).rows())
    section("Privileges", PrivilegeStatus.check(context).rows())
    appendLine("## Vendor packages")
    PackageScanner.scan(context, vendorOnly = true).forEach { appendLine("${it.id}${if (!it.enabled) " (disabled)" else ""}") }
}
