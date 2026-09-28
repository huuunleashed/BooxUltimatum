package app.booxultimatum.kit.log

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HousekeepingTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun file(dir: File, name: String, bytes: Int, ageMs: Long): File =
        File(dir, name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes)); setLastModified(now - ageMs) }

    @Test fun oldCacheFilesGoFirstThenTheOldestUntilItFits() {
        val cache = tmp.newFolder("cache")
        val stale = file(cache, "stale.png", 10, 4 * day)
        val old = file(cache, "a/old.bin", 60, 2 * day)
        val mid = file(cache, "mid.bin", 60, day)
        val fresh = file(cache, "fresh.bin", 60, 1000)
        val freed = Housekeeping.tidyCache(cache, { true }, now, maxBytes = 130)
        assertFalse(stale.exists(), "past the age limit")
        assertFalse(old.exists(), "oldest goes until the cache fits")
        assertTrue(mid.exists() && fresh.exists())
        assertEquals(70L, freed)
    }

    @Test fun filesTheCallerExcludesAreNeverTouched() {
        val cache = tmp.newFolder("cache2")
        val download = file(cache, "updates/Nib-0.1.0.apk", 100, 10 * day)
        Housekeeping.tidyCache(cache, { !it.path.contains("updates") }, now, maxBytes = 0)
        assertTrue(download.exists())
    }

    @Test fun exportsKeepTheNewestFewAndNothingOlderThanAWeek() {
        val exports = tmp.newFolder("exports")
        val files = (0 until 8).map { file(exports, "logs-$it.zip", 5, it * 60_000L) }
        val ancient = file(exports, "ancient.zip", 5, 8 * day)
        Housekeeping.tidyExports(exports, now, keep = 5)
        assertEquals(files.take(5), files.filter { it.exists() })
        assertFalse(ancient.exists())
    }

    @Test fun measureCountsNestedFilesOnce() {
        val a = tmp.newFolder("m")
        file(a, "x", 3, 0)
        file(a, "sub/y", 4, 0)
        val u = Housekeeping.measure("cache", true, listOf(a, a))
        assertEquals(7L, u.bytes)
        assertEquals(2, u.files)
        assertEquals(0L, Housekeeping.measure("none", false, listOf(File(a, "missing"))).bytes)
    }
}
