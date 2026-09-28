package app.booxultimatum.core

import android.content.Context
import android.provider.Settings
import app.booxultimatum.core.exec.Privileged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class Namespace(val cli: String) { Global("global"), Secure("secure"), System("system") }

/**
 * Reads and writes Android settings. Prefers the shell (T2) because apps targeting Android 12+ cannot read
 * hidden keys; falls back to the provider, which writes with the adb-granted WRITE_SECURE_SETTINGS (T1).
 */
object SystemSettings {
    private val SAFE = Regex("^[A-Za-z0-9._:,=-]*$")

    suspend fun get(context: Context, ns: Namespace, key: String): String? {
        if (Privileged.ready()) {
            val r = Privileged.sh("settings get ${ns.cli} $key")
            if (r.ok) return r.out.trim().takeUnless { it.isEmpty() || it == "null" }
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val cr = context.contentResolver
                when (ns) {
                    Namespace.Global -> Settings.Global.getString(cr, key)
                    Namespace.Secure -> Settings.Secure.getString(cr, key)
                    Namespace.System -> Settings.System.getString(cr, key)
                }
            }.getOrNull()
        }
    }

    suspend fun put(context: Context, ns: Namespace, key: String, value: String?): Result<Unit> {
        require(SAFE.matches(key) && (value == null || SAFE.matches(value))) { "Unsafe setting: $key=$value" }
        if (Privileged.ready()) {
            val r = Privileged.sh(if (value == null) "settings delete ${ns.cli} $key" else "settings put ${ns.cli} $key '$value'")
            return if (r.ok) Result.success(Unit) else Result.failure(IllegalStateException(r.message))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val cr = context.contentResolver
                val ok = when (ns) {
                    Namespace.Global -> Settings.Global.putString(cr, key, value)
                    Namespace.Secure -> Settings.Secure.putString(cr, key, value)
                    Namespace.System -> Settings.System.putString(cr, key, value)
                }
                check(ok) { "Android refused to change $key" }
            }
        }
    }
}
