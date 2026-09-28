package app.booxultimatum.kit.update

import app.booxultimatum.kit.core.Suite
import app.booxultimatum.kit.core.SuiteApp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Which releases the owner wants offered. Test builds are for checking work in progress on the tablet. */
data class UpdateChannel(val previews: Boolean = true, val tests: Boolean = false)

/**
 * One suite app's APK in one GitHub release.
 *
 * Public releases are tagged `<tagPrefix><version>` with an asset `<assetName>-<version>.apk`. Test builds are tagged
 * `test-<key>-<version>` with an asset `test-<assetName>-<version>.apk`. Hubs from before the suite (0.5.x) only
 * offer a release whose asset is `BooxUltimatum-<tag without "v">.apk`, so they never see a test build or another
 * app's release.
 */
data class Release(
    val app: SuiteApp,
    val version: Version,
    val tag: String,
    val test: Boolean,
    val preRelease: Boolean,
    val htmlUrl: String,
    val apkName: String,
    val apkUrl: String,
    /** Lower-case SHA-256 of the APK when the release states it; see [checksumUrl] otherwise. */
    val checksum: String?,
    /** A `.sha256` asset to read the checksum from when the release doesn't state it directly. */
    val checksumUrl: String?,
    val notes: String,
)

object ReleaseFeed {
    const val API = "https://api.github.com/repos/${Suite.OWNER}/${Suite.REPO}/releases?per_page=50"
    private val HEX_64 = Regex("""\b[0-9a-fA-F]{64}\b""")

    /** Every suite app release in GitHub's release list (newest first as GitHub returns it); drafts are skipped. */
    fun parse(json: String): List<Release> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            .filter { !it.optBoolean("draft") }
            .flatMap { obj -> SuiteApp.entries.mapNotNull { app -> releaseFor(app, obj) } }
    }

    /** The newest release of [app] the channel allows that is newer than [installed] (any, when not installed). */
    fun newest(releases: List<Release>, app: SuiteApp, installed: Version?, channel: UpdateChannel): Release? = releases
        .filter { it.app == app }
        .filter { channel.previews || !it.preRelease || it.test }
        .filter { channel.tests || !it.test }
        .filter { installed == null || it.version > installed }
        .maxByOrNull { it.version }

    private fun releaseFor(app: SuiteApp, obj: JSONObject): Release? {
        val tag = obj.optString("tag_name")
        val testPrefix = "test-${app.key}-"
        val test = tag.startsWith(testPrefix)
        val versionText = when {
            test -> tag.removePrefix(testPrefix)
            tag.startsWith("test-") -> return null
            tag.startsWith(app.tagPrefix) -> tag.removePrefix(app.tagPrefix)
            else -> return null
        }
        val version = Version.parse(versionText) ?: return null
        val apkName = (if (test) "test-" else "") + "${app.assetName}-$versionText.apk"
        val assets = obj.optJSONArray("assets") ?: JSONArray()
        val list = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
        val apk = list.firstOrNull { it.optString("name") == apkName } ?: return null
        val notesRaw = obj.optString("body")
        val digest = apk.optString("digest").takeIf { it.startsWith("sha256:", ignoreCase = true) }?.substringAfter(':')?.takeIf { HEX_64.matches(it) }
        val inNotes = notesRaw.lineSequence().firstOrNull { it.contains(apkName) }?.let { HEX_64.find(it)?.value }
        return Release(
            app = app,
            version = version,
            tag = tag,
            test = test,
            preRelease = obj.optBoolean("prerelease"),
            htmlUrl = obj.optString("html_url"),
            apkName = apkName,
            apkUrl = apk.optString("browser_download_url"),
            checksum = (digest ?: inNotes)?.lowercase(Locale.US),
            checksumUrl = list.firstOrNull { it.optString("name") == "$apkName.sha256" }?.optString("browser_download_url")?.takeIf { it.isNotBlank() },
            notes = plainNotes(notesRaw),
        )
    }

    /** The first 64-hex value in a `.sha256` file's text. */
    fun checksumIn(text: String): String? = text.lineSequence().mapNotNull { HEX_64.find(it)?.value }.firstOrNull()?.lowercase(Locale.US)

    /** Release notes as plain lines: Markdown marks and code blocks removed, at most 800 characters. */
    fun plainNotes(raw: String): String = raw
        .replace(Regex("""```[\s\S]*?```"""), "")
        .replace(Regex("""[*_`>#\[\]]"""), "")
        .lineSequence()
        .map { it.trim().trimStart('-', '•').trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .take(800)
}
