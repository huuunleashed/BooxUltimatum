package app.booxultimatum.core.feedback

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.booxultimatum.BuildConfig
import app.booxultimatum.R
import app.booxultimatum.core.BatterySnapshot
import app.booxultimatum.core.Launchers
import app.booxultimatum.core.PrivilegeStatus
import app.booxultimatum.core.Tier
import app.booxultimatum.core.ink.InkPrefs
import app.booxultimatum.core.ink.InstantInk
import app.booxultimatum.core.sleep.LivePrefs
import app.booxultimatum.core.sleep.LiveSleep
import app.booxultimatum.core.sleep.LiveSleepService
import app.booxultimatum.core.sleep.SleepStore
import app.booxultimatum.kit.core.Suite
import app.booxultimatum.kit.core.Tablet
import app.booxultimatum.kit.ink.epd.Epd
import app.booxultimatum.kit.ink.input.PenInput
import app.booxultimatum.kit.log.Logbook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

enum class FeedbackType(val template: String) {
    Bug("bug_report.yml"),
    Device("device_finding.yml"),
    Idea("idea.yml"),
}

enum class FeedbackInclude { Device, App, Features, Inputs, Battery, Log }

data class FeedbackSection(val include: FeedbackInclude, val text: String)

data class FeedbackSnapshot(
    val sections: List<FeedbackSection>,
    /** One line for the form's single-line Device field, e.g. "BOOX NoteAir6C · firmware 4.3 · Android 16". */
    val deviceLine: String = "",
    /** The bug form's Access level option that matches what this app holds. */
    val accessOption: String = "Not sure",
) {
    fun textFor(include: FeedbackInclude) = sections.firstOrNull { it.include == include }?.text.orEmpty()
}

data class FeedbackDraft(val url: String, val copiedFullReport: Boolean)

object FeedbackReports {
    suspend fun snapshot(context: Context): FeedbackSnapshot = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val profile = Tablet.current(app)
        val access = PrivilegeStatus.check(app)
        val battery = BatterySnapshot.read(app)
        val inkPrefs = InkPrefs.load(app)
        val sleep = SleepStore.load(app)
        val launcher = Launchers.current(app)
        val releaseBuild = Suite.isReleaseBuild(app)
        FeedbackSnapshot(
            listOf(
                FeedbackSection(
                    FeedbackInclude.Device,
                    buildString {
                        appendLine("## Device and firmware")
                        appendLine("Name: ${profile.name}")
                        appendLine("Series: ${profile.series}")
                        appendLine("Firmware: ${profile.firmware ?: "not detected"}")
                        appendLine("Android: ${profile.android}")
                        appendLine("Platform: ${profile.platform}")
                        appendLine("Boox: ${yesNo(profile.isBoox)}")
                    }.trim(),
                ),
                FeedbackSection(
                    FeedbackInclude.App,
                    buildString {
                        appendLine("## App")
                        appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        appendLine("Build: ${if (releaseBuild) "release" else "development"}")
                        appendLine("Access tier: ${access.highestTier}")
                        appendLine("Shizuku: running=${yesNo(access.shizukuRunning)}, granted=${yesNo(access.shizukuGranted)}")
                        appendLine("Secure settings: ${yesNo(access.secureSettings)}")
                        appendLine("Usage access: ${yesNo(access.usageStats)}")
                    }.trim(),
                ),
                FeedbackSection(
                    FeedbackInclude.Features,
                    buildString {
                        appendLine("## Feature status")
                        appendLine("Instant ink: enabled=${yesNo(inkPrefs.enabled)}, status=${InstantInk.status}, route=${Epd.route ?: "none"}, pause=${inkPrefs.latencyMs} ms")
                        appendLine("Pen: ${profile.pen?.let { "${it.name}, node ${PenInput.remembered(app) ?: "not seen yet"}" } ?: "no stylus listed by Android"}")
                        appendLine("Readable input nodes: ${PenInput.readableNodes().joinToString { it.removePrefix("/dev/input/") }.ifEmpty { "none" }}")
                        appendLine("Usage access: ${yesNo(InstantInk.usageAccess(app))}")
                        appendLine("Sleep studio: enabled=${yesNo(sleep.active)}, mode=${sleep.mode}")
                        val live = LivePrefs.load(app)
                        val record = LiveSleep.record(app)
                        appendLine(
                            "Live sleep: enabled=${yesNo(live.enabled)}, every ${live.stepMin} min, accessibility=${yesNo(LiveSleep.accessibilityOn(app))}, " +
                                "service running=${yesNo(LiveSleepService.running)}, background allowed=${yesNo(LiveSleep.backgroundAllowed(app))}, " +
                                "last sleep updates=${record?.updates ?: "none"}, missed=${yesNo(record?.missed == true)}",
                        )
                        appendLine("Default launcher: ${if (launcher?.packageName == app.packageName) "BooxUltimatum" else "another launcher or chooser"}")
                    }.trim(),
                ),
                FeedbackSection(
                    FeedbackInclude.Inputs,
                    buildString {
                        appendLine("## Input devices")
                        if (profile.inputs.isEmpty()) appendLine("No input devices reported.")
                        profile.inputs.forEach { appendLine("${it.name.ifBlank { "unnamed" }} · stylus=${yesNo(it.stylus)}") }
                    }.trim(),
                ),
                FeedbackSection(
                    FeedbackInclude.Battery,
                    buildString {
                        appendLine("## Battery snapshot")
                        appendLine("Level: ${battery.levelPct} %")
                        appendLine("Status: ${battery.status}")
                        appendLine("Health: ${battery.health}")
                        appendLine("Temperature: ${battery.tempC?.let { "%.1f °C".format(Locale.US, it) } ?: "not reported"}")
                        appendLine("Cycles: ${battery.cycleCount ?: "not reported"}")
                    }.trim(),
                ),
                FeedbackSection(FeedbackInclude.Log, "## Recent app log\n${recentLog().ifBlank { "No app log lines returned." }}"),
            ),
            deviceLine = listOfNotNull(profile.name, profile.firmware?.let { "firmware $it" }, "Android ${profile.android}").joinToString(" · "),
            accessOption = when (access.highestTier) {
                Tier.T0 -> "T0 (just the app)"
                Tier.T1 -> "T1 (adb grants)"
                Tier.T2 -> "T2 (Shizuku running)"
                Tier.T3 -> "Not sure"
            },
        )
    }

    fun buildReport(type: FeedbackType, area: String, title: String, description: String, includes: Set<FeedbackInclude>, snapshot: FeedbackSnapshot): String = buildString {
        appendLine("# ${type.title()}: ${title.ifBlank { "Untitled" }}")
        if (area.isNotBlank()) appendLine("Area: $area")
        appendLine()
        appendLine(description.ifBlank { "No description entered." })
        val details = details(includes, snapshot)
        if (details.isNotEmpty()) { appendLine(); appendLine(details) }
    }.trim()

    /** Only the sections the owner left switched on, in their fixed order. */
    private fun details(includes: Set<FeedbackInclude>, snapshot: FeedbackSnapshot): String =
        FeedbackInclude.entries.filter { it in includes }.joinToString("\n\n") { snapshot.textFor(it) }

    /**
     * The issue form, filled by field id. Nothing the owner switched off reaches the link: the device line only when
     * Device is included, the access level only when App is. The app version is always given, as the form requires it.
     */
    fun githubDraft(context: Context, type: FeedbackType, area: String, title: String, description: String, includes: Set<FeedbackInclude>, snapshot: FeedbackSnapshot): FeedbackDraft {
        val details = details(includes, snapshot)
        val base = issueUrl(type, title, fieldsFor(type, area, description, details, includes, snapshot))
        if (base.length <= 7_500) return FeedbackDraft(base, false)
        copy(context, buildReport(type, area, title, description, includes, snapshot))
        val short = "The full report was copied from BooxUltimatum. Please paste it here before submitting."
        return FeedbackDraft(issueUrl(type, title, fieldsFor(type, area, description, short, includes, snapshot)), true)
    }

    fun copy(context: Context, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("BooxUltimatum report", text))
    }

    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.feedback_share_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun issueUrl(type: FeedbackType, title: String, fields: Map<String, String>): String {
        val builder = Uri.Builder()
            .scheme("https")
            .authority("github.com")
            .path("/huuunleashed/BooxUltimatum/issues/new")
            .appendQueryParameter("template", type.template)
            .appendQueryParameter("title", title)
        fields.forEach { (k, v) -> builder.appendQueryParameter(k, v) }
        return builder.build().toString()
    }

    private fun fieldsFor(type: FeedbackType, area: String, description: String, details: String, includes: Set<FeedbackInclude>, snapshot: FeedbackSnapshot): Map<String, String> {
        val device = if (FeedbackInclude.Device in includes) snapshot.deviceLine else ""
        val body = listOf(description, details).filter { it.isNotBlank() }.joinToString("\n\n")
        return when (type) {
            FeedbackType.Bug -> buildMap {
                put("device", device)
                put("version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                // Dropdowns only accept their exact option text; anything else is left for the owner to pick.
                if (area in BUG_AREAS) put("area", area)
                if (FeedbackInclude.App in includes) put("access", snapshot.accessOption)
                put("steps", body)
            }
            FeedbackType.Device -> mapOf("device" to device, "finding" to description, "evidence" to details)
            FeedbackType.Idea -> mapOf("problem" to body)
        }
    }

    /** The bug form's Area options, as written in `.github/ISSUE_TEMPLATE/bug_report.yml`. */
    private val BUG_AREAS = setOf("Home screen", "Sleep screen", "Instant ink", "Battery", "Tweaks", "Appearance or fonts", "Nib (drawing)", "Suite, updates or logs", "Something else")

    /** The logbook's latest entries, newest last, with a pointer to the full export. */
    private fun recentLog(): String = runCatching {
        val lines = Logbook.recent(150).joinToString("\n") { e ->
            val fields = e.fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
            val time = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date(e.wallMs))
            "$time ${e.level.name.first()} ${e.category}: ${e.message} $fields".trimEnd() + (e.error?.lineSequence()?.firstOrNull()?.let { " | $it" } ?: "")
        }
        redact(lines) + "\n\nThe full logs of every suite app: Device › Logs › Share logs."
    }.getOrDefault("Could not read this app’s log.")

    private fun redact(text: String): String = text
        .replace(Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""), "[email redacted]")
        .replace(Regex("""(?i)(ssid[^"\n]{0,30})"[^"]+""""), "$1\"[ssid redacted]\"")
        .replace(Regex("""\b(?!app\.booxultimatum\b)(?:[a-zA-Z][a-zA-Z0-9_]*\.){2,}[a-zA-Z0-9_]+\b"""), "[package redacted]")

    private fun FeedbackType.title() = when (this) {
        FeedbackType.Bug -> "Bug"
        FeedbackType.Device -> "Device report"
        FeedbackType.Idea -> "Idea"
    }

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"
}
