package app.booxultimatum.nib.export

import android.content.Context
import android.os.Build
import app.booxultimatum.kit.core.Suite
import app.booxultimatum.kit.core.SuiteApp
import app.booxultimatum.kit.core.Tablet
import app.booxultimatum.kit.ink.SurfaceInk
import app.booxultimatum.kit.log.Logbook
import app.booxultimatum.nib.BuildConfig
import app.booxultimatum.nib.NibSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One zip of Nib's logs with a summary of the tablet, for the owner to send with a report. */
object LogShare {
    private val log = Logbook.logger("nib.ui")

    fun build(context: Context): File {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val zip = File(dir, "nib-logs-$stamp.zip")
        zip.outputStream().use { Logbook.exportZip(context, it, mapOf("device.txt" to summary(context).toByteArray())) }
        log.i("logs exported", "bytes" to zip.length())
        return zip
    }

    fun summary(context: Context): String {
        val t = Tablet.current(context)
        val s = NibSettings.get(context)
        return buildString {
            appendLine("Device: ${t.name} (${t.series})")
            appendLine("Firmware: ${t.firmware ?: "not detected"}")
            appendLine("Android: ${t.android} (API ${Build.VERSION.SDK_INT})")
            appendLine("Pen: ${t.pen?.name ?: "none listed"}")
            appendLine("Nib: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), ${if (Suite.isReleaseBuild(context)) "release" else "development"} build")
            appendLine("BooxUltimatum: ${Suite.installed(context, SuiteApp.Hub)?.let { "${it.versionName} (${it.versionCode})" } ?: "not installed"}")
            appendLine("Display route: ${SurfaceInk.route ?: "none"}, max pressure ${SurfaceInk.maxTouchPressure ?: "unknown"}")
            appendLine("Settings: finger drawing ${s.fingerDrawing}, finger pan ${s.fingerPan}, unverified styles ${s.tryUnverifiedStyles}, swap delay ${s.swapDelayMs} ms, recorder ${s.penRecorder}")
        }
    }
}
