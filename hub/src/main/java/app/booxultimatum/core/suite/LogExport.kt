package app.booxultimatum.core.suite

import android.content.Context
import android.os.Build
import app.booxultimatum.BuildConfig
import app.booxultimatum.kit.core.Suite
import app.booxultimatum.kit.core.SuiteApp
import app.booxultimatum.kit.core.Tablet
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.kit.log.SuiteLogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One zip with the logs of every suite app on the tablet, for a problem report. The other apps hand theirs over
 * through their log provider, which only answers apps signed with the same key.
 */
object LogExport {
    private val log = Logbook.logger("suite")

    suspend fun build(context: Context): File = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val dir = File(app.getExternalFilesDir(null), "exports").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("booxultimatum-logs-") }?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val zip = File(dir, "booxultimatum-logs-$stamp.zip")
        val extra = LinkedHashMap<String, ByteArray>()
        var others = 0
        extra["device.txt"] = summary(app).toByteArray()
        SuiteApp.entries.filter { it.packageName != app.packageName && Suite.installed(app, it) != null }.forEach { other ->
            val files = SuiteLogs.listFiles(app, other.packageName)
            if (files.isEmpty()) extra["${other.packageName}/unavailable.txt"] = "The app didn't share its logs: it may be signed with another key, or not have started yet.\n".toByteArray()
            if (files.isNotEmpty()) others++
            files.forEach { f -> SuiteLogs.open(app, other.packageName, f.name)?.use { extra["${other.packageName}/${f.name}"] = it.readBytes() } }
        }
        zip.outputStream().use { Logbook.exportZip(app, it, extra) }
        log.i("logs exported", "bytes" to zip.length(), "other apps" to others)
        zip
    }

    private fun summary(context: Context): String {
        val t = Tablet.current(context)
        return buildString {
            appendLine("Device: ${t.name} (${t.series})")
            appendLine("Firmware: ${t.firmware ?: "not detected"}")
            appendLine("Android: ${t.android} (API ${Build.VERSION.SDK_INT})")
            appendLine("Platform: ${t.platform}")
            appendLine("Pen: ${t.pen?.name ?: "none listed"}")
            appendLine("BooxUltimatum: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), ${if (Suite.isReleaseBuild(context)) "release" else "development"} build")
            SuiteApp.entries.filter { it != SuiteApp.Hub }.forEach { a ->
                appendLine("${a.title}: ${Suite.installed(context, a)?.let { "${it.versionName} (${it.versionCode})" } ?: "not installed"}")
            }
            appendLine("Modules: ${Modules.available(context).joinToString { "${it.name}=${if (Modules.added(context, it)) "added" else "removed"}" }}")
        }
    }
}
