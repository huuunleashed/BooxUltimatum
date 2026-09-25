package app.booxultimatum.core.exec

import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import app.booxultimatum.IShellService
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

    override fun exec(command: String): Bundle {
        val result = Bundle()
        try {
            val process = ProcessBuilder("sh", "-c", command).start()
            var err = ""
            val errReader = thread { err = process.errorStream.bufferedReader().readText() }
            val out = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) process.destroy()
            errReader.join(1000)
            result.putInt(KEY_CODE, if (finished) process.exitValue() else TIMEOUT)
            result.putString(KEY_OUT, out)
            result.putString(KEY_ERR, err)
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
    }
}
