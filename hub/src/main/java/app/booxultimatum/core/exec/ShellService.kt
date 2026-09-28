package app.booxultimatum.core.exec

import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import app.booxultimatum.IShellService
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Lives in Shizuku's process under the shell uid (2000). Every privileged action the app takes goes
 * through [exec], so the whole T2 surface is one audited entry point.
 */
class ShellService() : IShellService.Stub() {

    @Keep
    @Suppress("unused")
    constructor(context: Context) : this()

    override fun destroy() {
        exitProcess(0)
    }

    /**
     * Runs [command] with `sh -c`. Both output streams are read on their own threads while the timeout runs, so a
     * command that never finishes (or fills one pipe while the other is read) can't block the caller for more than
     * [TIMEOUT_S] seconds; a stuck command is killed.
     */
    override fun exec(command: String): Bundle {
        val result = Bundle()
        try {
            val process = ProcessBuilder("sh", "-c", command).start()
            var out = Output("", false)
            var err = Output("", false)
            val outReader = thread(isDaemon = true) { out = read(process.inputStream) }
            val errReader = thread(isDaemon = true) { err = read(process.errorStream) }
            val finished = process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            outReader.join(JOIN_MS)
            errReader.join(JOIN_MS)
            result.putInt(KEY_CODE, if (finished) process.exitValue() else TIMEOUT)
            result.putString(KEY_OUT, out.text)
            val notes = listOfNotNull(
                if (!finished) "Timed out after $TIMEOUT_S s." else null,
                if (out.truncated) "Output cut at $MAX_CHARS characters." else null,
            )
            result.putString(KEY_ERR, (notes + err.text).filter { it.isNotEmpty() }.joinToString(" "))
        } catch (e: Exception) {
            result.putInt(KEY_CODE, FAILED)
            result.putString(KEY_ERR, e.toString())
        }
        return result
    }

    companion object {
        const val KEY_CODE = "code"
        const val KEY_OUT = "out"
        const val KEY_ERR = "err"
        const val TIMEOUT = -2
        const val FAILED = -1
        private const val TIMEOUT_S = 20L
        private const val JOIN_MS = 2_000L
        /**
         * Output beyond this is dropped, and the error text says so: the reply crosses the binder, whose buffer is about
         * 1 MB for the whole process, and strings travel as UTF-16. Large dumps go through the app's own DUMP grant.
         */
        private const val MAX_CHARS = 250_000

        private class Output(val text: String, val truncated: Boolean)

        private fun read(input: InputStream): Output {
            val sb = StringBuilder()
            var truncated = false
            input.bufferedReader().use { r ->
                val buf = CharArray(8192)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    val room = MAX_CHARS - sb.length
                    if (n > room) truncated = true
                    if (room > 0) sb.append(buf, 0, minOf(n, room))
                }
            }
            return Output(sb.toString(), truncated)
        }
    }
}
