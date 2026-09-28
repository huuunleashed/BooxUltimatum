package app.booxultimatum.kit.log

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(dir: File, name: String, modified: Long): File =
        File(dir, name).apply {
            writeText("x")
            setLastModified(modified)
        }

    @Test
    fun pruneCountKeepsTheNewest() {
        val dir = tmp.newFolder("logs")
        val files = (1..5).map { file(dir, "log-2026090$it-000000-1.jsonl", T0 + it * 60_000L) }
        val crash = file(dir, "crash-20260901-000000.json", T0 - 1_000_000)
        val deleted = LogFiles.pruneCount(dir, LogFiles::isEvent, 2)
        assertEquals(files.take(3).toSet(), deleted.toSet())
        assertEquals(listOf(files[4], files[3], crash), LogFiles.list(dir))
    }

    @Test
    fun pruneCountNeverDeletesTheProtectedFile() {
        val dir = tmp.newFolder("logs")
        val files = (1..5).map { file(dir, "log-2026090$it-000000-1.jsonl", T0 + it * 60_000L) }
        LogFiles.pruneCount(dir, LogFiles::isEvent, 2, protect = files[0])
        assertEquals(setOf(files[0], files[4]), LogFiles.list(dir).toSet())
    }

    @Test
    fun pruneAgeDeletesOnlyOldEventFiles() {
        val dir = tmp.newFolder("logs")
        val old = file(dir, "log-20260901-000000-1.jsonl", T0 - 15 * LogFiles.DAY_MS)
        val fresh = file(dir, "log-20260927-000000-1.jsonl", T0 - 13 * LogFiles.DAY_MS)
        val oldCrash = file(dir, "crash-20260901-000000.json", T0 - 30 * LogFiles.DAY_MS)
        val deleted = LogFiles.pruneAge(dir, LogFiles::isEvent, 14 * LogFiles.DAY_MS, T0)
        assertEquals(listOf(old), deleted)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        assertTrue(oldCrash.exists())
    }

    @Test
    fun crashFilesAreKeptByCount() {
        val dir = tmp.newFolder("logs")
        val written = (0 until 5).map {
            CrashFile.write(dir, T0 + it * 1000L, "main", "s", null, null, 1, RuntimeException("c$it"), emptyList(), 3)
                .also { f -> f.setLastModified(T0 + it * 1000L) }
        }
        val left = LogFiles.list(dir)
        assertEquals(3, left.size)
        assertTrue(written.last() in left)
    }

    @Test
    fun listReturnsOnlyLogFilesNewestFirst() {
        val dir = tmp.newFolder("logs")
        val a = file(dir, "log-20260901-000000-1.jsonl", T0)
        val b = file(dir, "crash-20260902-000000.json", T0 + 1000)
        val c = file(dir, "exit-20260903-000000-anr.txt", T0 + 2000)
        val d = file(dir, "exit-20260904-000000-crash_native.pb", T0 + 3000)
        file(dir, "notes.txt", T0 + 4000)
        file(dir, "log-20260905.txt", T0 + 5000)
        File(dir, "log-dir.jsonl").mkdir()
        assertEquals(listOf(d, c, b, a), LogFiles.list(dir))
        assertTrue(LogFiles.list(File(dir, "missing")).isEmpty())
    }

    @Test
    fun resolveRejectsTraversalAndForeignFiles() {
        val root = tmp.newFolder("app")
        val dir = File(root, "logs").apply { mkdirs() }
        val log = file(dir, "log-20260901-000000-1.jsonl", T0)
        file(dir, "notes.txt", T0)
        file(root, "log-20260901-000000-2.jsonl", T0)
        assertEquals(log.canonicalFile, LogFiles.resolve(dir, log.name)?.canonicalFile)
        val rejected = listOf(
            "", ".", "..", "../log-20260901-000000-2.jsonl", "..\\log-20260901-000000-2.jsonl",
            "sub/log-20260901-000000-1.jsonl", "log-..-1.jsonl", "notes.txt", "log-20260101-000000-9.jsonl",
            "log-20260901-000000-1.jsonl\u0000", "/etc/passwd", "shared_prefs/kit.log.xml",
        )
        for (name in rejected) assertNull(LogFiles.resolve(dir, name), name)
    }

    @Test
    fun providerPathsNeedExactlyFilesAndAName() {
        assertEquals("log-a.jsonl", LogFiles.nameFromSegments(listOf("files", "log-a.jsonl")))
        assertNull(LogFiles.nameFromSegments(listOf("files")))
        assertNull(LogFiles.nameFromSegments(listOf("files", "a", "b")))
        assertNull(LogFiles.nameFromSegments(listOf("other", "log-a.jsonl")))
        assertNull(LogFiles.nameFromSegments(listOf("files", "../log-a.jsonl")))
        assertNull(LogFiles.nameFromSegments(listOf("files", "a/b")))
    }

    @Test
    fun namesInTheSameSecondStayUnique() {
        val dir = tmp.newFolder("logs")
        val first = LogFiles.newEventFile(dir, T0, 7).apply { writeText("") }
        val second = LogFiles.newEventFile(dir, T0 + 500, 7)
        assertEquals("log-20260928-031500-7.jsonl", first.name)
        assertEquals("log-20260928-031500-7_2.jsonl", second.name)
        assertTrue(second.name > first.name, "the later file sorts after")
        assertEquals("crash-20260928-031500.json", LogFiles.newCrashFile(dir, T0).name)
        assertEquals("exit-20260928-031500-anr.txt", LogFiles.newExitFile(dir, T0, "anr", ".txt").name)
    }

    @Test
    fun copyCappedStopsAtTheCap() {
        val out = ByteArrayOutputStream()
        assertEquals(1000, LogFiles.copyCapped(ByteArrayInputStream(ByteArray(5000) { 1 }), out, 1000))
        assertEquals(1000, out.size())
        val small = ByteArrayOutputStream()
        assertEquals(10, LogFiles.copyCapped(ByteArrayInputStream(ByteArray(10)), small, 1000))
    }
}
