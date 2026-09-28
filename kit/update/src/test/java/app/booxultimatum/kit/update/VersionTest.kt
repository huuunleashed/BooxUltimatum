package app.booxultimatum.kit.update

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionTest {
    private fun v(s: String) = Version.parse(s)!!

    @Test fun parsesPlainAndPrefixed() {
        assertEquals(Version(0, 5, 1), v("0.5.1"))
        assertEquals(Version(0, 5, 1), v("v0.5.1"))
        assertEquals(Version(1, 2, 0), v("1.2"))
        assertEquals(Version(0, 6, 0, listOf("test", "3")), v("0.6.0-test.3"))
        assertEquals(Version(1, 0, 0), v("1.0.0+build.7"))
    }

    @Test fun rejectsGarbage() {
        assertNull(Version.parse("draw-v0.1.0"))
        assertNull(Version.parse(""))
        assertNull(Version.parse("1"))
        assertNull(Version.parse("1.2.3-"))
    }

    @Test fun ordersReleasesAndPreReleases() {
        val ordered = listOf("0.5.1", "0.6.0-alpha", "0.6.0-alpha.1", "0.6.0-alpha.beta", "0.6.0-beta", "0.6.0-beta.2", "0.6.0-beta.11", "0.6.0-rc.1", "0.6.0", "0.6.1", "1.0.0")
        val parsed = ordered.map(::v)
        assertEquals(parsed, parsed.shuffled(java.util.Random(7)).sorted())
    }

    @Test fun testBuildComesBeforeItsRelease() {
        assertTrue(v("0.6.0-test.3") < v("0.6.0"))
        assertTrue(v("0.6.0-test.3") > v("0.5.1"))
        assertTrue(v("0.6.0-test.10") > v("0.6.0-test.9"))
    }

    @Test fun printsBack() {
        assertEquals("0.6.0-test.3", v("v0.6.0-test.3").toString())
        assertEquals("1.2.0", v("1.2").toString())
    }
}
