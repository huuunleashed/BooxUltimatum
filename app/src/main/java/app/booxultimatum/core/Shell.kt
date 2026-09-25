package app.booxultimatum.core

import java.io.File
import java.util.concurrent.TimeUnit

/** Runs commands as the app's own uid (tier T0). Privileged execution goes through Shizuku/root later. */
object Shell {
    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok get() = exitCode == 0
    }

    fun run(vararg cmd: String, timeoutSec: Long = 10): Result = try {
        val p = ProcessBuilder(*cmd).start()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) p.destroy()
        Result(runCatching { p.exitValue() }.getOrDefault(-1), out, err)
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
