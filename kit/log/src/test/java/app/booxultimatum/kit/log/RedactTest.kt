package app.booxultimatum.kit.log

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RedactTest {
    @Test
    fun hashIsTheFirstTenHexOfSha256() {
        assertEquals("ba7816bf8f", Redact.hash("abc"))
        assertEquals("e3b0c44298", Redact.hash(""))
        assertEquals(Redact.hash("Sketch 12.nib"), Redact.hash("Sketch 12.nib"))
        assertNotEquals(Redact.hash("Sketch 12.nib"), Redact.hash("Sketch 13.nib"))
        assertTrue(Regex("[0-9a-f]{10}").matches(Redact.hash("漢字 😀")))
    }

    @Test
    fun pkgKeepsSuiteAndSystemPackages() {
        for (p in listOf(
            "app.booxultimatum", "app.booxultimatum.nib", "com.onyx", "com.onyx.kreader", "com.android.settings",
            "android", "android.ext.services",
        )) {
            assertEquals(p, Redact.pkg(p), p)
        }
    }

    @Test
    fun pkgHidesEverythingElse() {
        for (p in listOf(
            "com.example.bank", "app.booxultimatumx", "com.onyxfake.app", "com.androidx.evil", "androidx.core",
            "org.app.booxultimatum", "", " ", null,
        )) {
            assertEquals("other", Redact.pkg(p), p.toString())
        }
    }

    @Test
    fun pkgKeepsAllowedPackages() {
        assertEquals("org.koreader.launcher", Redact.pkg("org.koreader.launcher", setOf("org.koreader.launcher")))
        assertEquals("other", Redact.pkg("org.koreader.launcher.x", setOf("org.koreader.launcher")))
    }
}
