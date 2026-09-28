package app.booxultimatum.kit.update

import app.booxultimatum.kit.core.SuiteApp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseFeedTest {
    private val sha = "a".repeat(64)
    private val sha2 = "B".repeat(64)

    private fun asset(name: String, digest: String? = null) = JSONObject()
        .put("name", name)
        .put("browser_download_url", "https://example.invalid/$name")
        .apply { if (digest != null) put("digest", "sha256:$digest") }

    private fun release(tag: String, vararg assets: JSONObject, pre: Boolean = true, draft: Boolean = false, body: String = "") = JSONObject()
        .put("tag_name", tag)
        .put("prerelease", pre)
        .put("draft", draft)
        .put("html_url", "https://example.invalid/releases/$tag")
        .put("body", body)
        .put("assets", JSONArray(assets.toList()))

    private fun feed(vararg releases: JSONObject) = JSONArray(releases.toList()).toString()

    private val sample = feed(
        release("test-nib-0.2.0-test.1", asset("test-Nib-0.2.0-test.1.apk", sha)),
        release("test-hub-0.6.0-test.2", asset("test-BooxUltimatum-0.6.0-test.2.apk", sha)),
        release("nib-v0.1.0", asset("Nib-0.1.0.apk", sha)),
        release("v0.6.0", asset("BooxUltimatum-0.6.0.apk", sha), asset("BooxUltimatum-0.6.0-mapping.txt")),
        release("v0.7.0", asset("BooxUltimatum-0.7.0.apk", sha), draft = true),
        release("v0.5.1", asset("BooxUltimatum-0.5.1.apk", sha), pre = false),
    )

    @Test fun readsEachAppsReleases() {
        val all = ReleaseFeed.parse(sample)
        assertEquals(listOf("0.6.0-test.2", "0.6.0", "0.5.1"), all.filter { it.app == SuiteApp.Hub }.map { it.version.toString() })
        assertEquals(listOf("0.2.0-test.1", "0.1.0"), all.filter { it.app == SuiteApp.Nib }.map { it.version.toString() })
        assertTrue(all.none { it.tag == "v0.7.0" }, "drafts are skipped")
        assertTrue(all.first { it.tag.startsWith("test-") }.test)
    }

    @Test fun channelDecidesWhatIsOffered() {
        val all = ReleaseFeed.parse(sample)
        val installed = Version.parse("0.5.1")
        assertEquals("0.6.0", ReleaseFeed.newest(all, SuiteApp.Hub, installed, UpdateChannel(previews = true))?.version.toString())
        assertNull(ReleaseFeed.newest(all, SuiteApp.Hub, installed, UpdateChannel(previews = false)), "0.6.0 is a pre-release")
        assertEquals("0.6.0", ReleaseFeed.newest(all, SuiteApp.Hub, installed, UpdateChannel(previews = true, tests = true))?.version.toString(), "a release beats its own test build")
        val withNewerTest = ReleaseFeed.parse(feed(release("test-hub-0.6.1-test.1", asset("test-BooxUltimatum-0.6.1-test.1.apk", sha)))) + all
        assertEquals("0.6.1-test.1", ReleaseFeed.newest(withNewerTest, SuiteApp.Hub, installed, UpdateChannel(tests = true))?.version.toString())
        assertEquals("0.6.0", ReleaseFeed.newest(withNewerTest, SuiteApp.Hub, installed, UpdateChannel(tests = false))?.version.toString())
        assertEquals("0.6.0", ReleaseFeed.newest(all, SuiteApp.Hub, Version.parse("0.6.0-test.2"), UpdateChannel(tests = true))?.version.toString(), "the release follows its test build")
        assertEquals("0.1.0", ReleaseFeed.newest(all, SuiteApp.Nib, null, UpdateChannel())?.version.toString(), "a first install offers the newest")
        assertNull(ReleaseFeed.newest(all, SuiteApp.Nib, Version.parse("0.1.0"), UpdateChannel()))
    }

    /** What a 0.5.x hub, from before the suite, would offer: the asset named from the tag without "v". */
    private fun legacyOffer(json: String): List<String> {
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getJSONObject(it) }.filter { !it.optBoolean("draft") }.mapNotNull { obj ->
            val version = obj.optString("tag_name").removePrefix("v")
            val assets = obj.getJSONArray("assets")
            (0 until assets.length()).map { assets.getJSONObject(it).optString("name") }.firstOrNull { it == "BooxUltimatum-$version.apk" }?.let { version }
        }
    }

    @Test fun oldHubsNeverSeeTestBuildsOrOtherApps() {
        assertEquals(listOf("0.6.0", "0.5.1"), legacyOffer(sample))
    }

    @Test fun findsTheChecksum() {
        val json = feed(
            release("v1.0.0", asset("BooxUltimatum-1.0.0.apk", sha2)),
            release("v1.1.0", asset("BooxUltimatum-1.1.0.apk"), body = "BooxUltimatum-1.1.0.apk  $sha"),
            release("v1.2.0", asset("BooxUltimatum-1.2.0.apk"), asset("BooxUltimatum-1.2.0.apk.sha256")),
            release("v1.3.0", asset("BooxUltimatum-1.3.0.apk")),
        )
        val all = ReleaseFeed.parse(json).associateBy { it.version.toString() }
        assertEquals(sha2.lowercase(), all.getValue("1.0.0").checksum)
        assertEquals(sha, all.getValue("1.1.0").checksum)
        assertNull(all.getValue("1.2.0").checksum)
        assertNotNull(all.getValue("1.2.0").checksumUrl)
        assertNull(all.getValue("1.3.0").checksum)
        assertNull(all.getValue("1.3.0").checksumUrl)
        assertEquals(sha, ReleaseFeed.checksumIn("$sha  BooxUltimatum-1.2.0.apk\n"))
    }

    @Test fun ignoresUnrelatedTagsAndAssets() {
        val json = feed(
            release("draw-v0.1.0", asset("BooxUltimatum-draw-v0.1.0.apk", sha)),
            release("v0.9.0", asset("booxultimatum-0.9.0.apk", sha)),
            release("test-other-1.0.0", asset("test-Other-1.0.0.apk", sha)),
        )
        assertTrue(ReleaseFeed.parse(json).isEmpty())
    }

    @Test fun notesBecomePlainLines() {
        assertEquals("Faster pen\nFixed a crash", ReleaseFeed.plainNotes("## What's new\n\n- **Faster** pen\n- Fixed a `crash`\n```\ncode\n```").lines().drop(1).joinToString("\n"))
    }
}
