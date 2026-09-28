package app.booxultimatum.kit.log

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogExportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class TrackingStream : ByteArrayOutputStream() {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }

    @Test
    fun zipHoldsPrefixedFilesThenExtrasThenTheReadme() {
        val dir = tmp.newFolder("logs")
        val log = File(dir, "log-20260928-031500-1.jsonl").apply {
            writeText("{\"schema\":1}\n")
            setLastModified(T0 + 2000)
        }
        val crash = File(dir, "crash-20260928-031400.json").apply {
            writeText("{\"t\":1}")
            setLastModified(T0)
        }
        val out = TrackingStream()
        val summary = "tablet summary".toByteArray()
        LogExport.write(
            out,
            "app.booxultimatum",
            LogFiles.list(dir),
            linkedMapOf(
                "app.booxultimatum.nib/log-20260928-031000-2.jsonl" to "nib".toByteArray(),
                "summary.txt" to summary,
                "../evil.txt" to summary,
                "/abs.txt" to summary,
                "README.txt" to summary,
            ),
            LogExport.readme(LogConfig()),
        )
        assertFalse(out.closed, "the caller's stream stays open")
        val entries = unzip(out.toByteArray())
        assertEquals(
            listOf(
                "app.booxultimatum/${log.name}",
                "app.booxultimatum/${crash.name}",
                "app.booxultimatum.nib/log-20260928-031000-2.jsonl",
                "summary.txt",
                "README.txt",
            ),
            entries.keys.toList(),
        )
        assertContentEquals(log.readBytes(), entries["app.booxultimatum/${log.name}"])
        assertContentEquals(summary, entries["summary.txt"])
        val readme = entries.getValue("README.txt").toString(Charsets.UTF_8)
        assertTrue(readme.startsWith("BooxUltimatum diagnostic logs"))
        for (phrase in listOf(".jsonl", "crash-", "exit-", "draw", "type", "account and network names", "leaves the tablet")) {
            assertTrue(phrase in readme, phrase)
        }
    }

    @Test
    fun readmeReflectsTheConfig() {
        val readme = LogExport.readme(LogConfig(maxFiles = 3, maxAgeDays = 5, keepCrashes = 7, maxFileBytes = 500_000))
        assertTrue("newest 3 files" in readme)
        assertTrue("older than 5 days" in readme)
        assertTrue("newest 7 are kept" in readme)
        assertTrue("500 KB" in readme)
        assertTrue(readme.lines().filter { it.isNotBlank() }.drop(1).all { it.endsWith(".") }, "paragraphs are single lines")
    }

    @Test
    fun filesThatVanishAreSkipped() {
        val dir = tmp.newFolder("logs")
        val gone = File(dir, "log-20260928-031500-1.jsonl")
        val out = ByteArrayOutputStream()
        LogExport.write(out, "p", listOf(gone), emptyMap(), "readme")
        assertEquals(listOf("README.txt"), unzip(out.toByteArray()).keys.toList())
    }
}
