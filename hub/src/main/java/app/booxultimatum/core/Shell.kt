package app.booxultimatum.core

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs commands as the app's own uid (tier T0). Privileged execution goes through Shizuku/root later. */
object Shell {
    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok get() = exitCode == 0
    }

    /**
     * Runs [cmd] as this app. Both streams are read on their own threads while [timeoutSec] runs, so a command that
     * never finishes, or fills stderr while stdout is read, can't hang the caller; a stuck command is killed.
     */
    fun run(vararg cmd: String, timeoutSec: Long = 10): Result = try {
        val p = ProcessBuilder(*cmd).start()
        var out = ""
        var err = ""
        val outReader = thread(isDaemon = true) { out = runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("") }
        val errReader = thread(isDaemon = true) { err = runCatching { p.errorStream.bufferedReader().readText() }.getOrDefault("") }
        val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        outReader.join(2_000)
        errReader.join(2_000)
        Result(if (finished) runCatching { p.exitValue() }.getOrDefault(-1) else -2, out, if (finished) err else "Timed out after $timeoutSec s. $err")
    } catch (e: Exception) {
        Result(-1, "", e.toString())
    }

    fun readFile(path: String): String? = runCatching { File(path).readText().trim() }.getOrNull()

    fun getprops(): Map<String, String> {
        val regex = Regex("""^\[(.+?)]: \[(.*)]$""")
        return run("getprop").stdout.lineSequence()
            .mapNotNull { regex.find(it)?.destructured?.let { (k, v) -> k to v } }
            .toMap()
    }
}
