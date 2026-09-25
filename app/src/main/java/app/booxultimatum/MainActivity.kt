package app.booxultimatum

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import app.booxultimatum.ui.BooxUltimatumApp
import app.booxultimatum.ui.theme.InstrumentTheme
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    // Bumped by Shizuku callbacks so access readings refresh without polling.
    private val accessEvents = mutableIntStateOf(0)
    private val onBinder = Shizuku.OnBinderReceivedListener { accessEvents.intValue++ }
    private val onBinderDead = Shizuku.OnBinderDeadListener { accessEvents.intValue++ }
    private val onPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> accessEvents.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lay out below the system bars instead of edge to edge: Boox's status bar only draws its icons then.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
            show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }
        app.booxultimatum.core.BatteryLog.schedule(this)
        app.booxultimatum.core.BatteryLog.record(this, "open")
        app.booxultimatum.core.BatteryLog.ensureNotRestricted(this)
        Shizuku.addBinderReceivedListenerSticky(onBinder)
        Shizuku.addBinderDeadListener(onBinderDead)
        Shizuku.addRequestPermissionResultListener(onPermission)
        // A font chosen in Fonts › Use in this app replaces Archivo everywhere in the UI.
        val appFont = app.booxultimatum.core.Fonts.appFont(this)?.let { path ->
            runCatching { androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(java.io.File(path))) }.getOrNull()
        }
        setContent { InstrumentTheme(customFont = appFont) { BooxUltimatumApp(accessEvents.intValue, initialDestination()) } }
    }

    private fun initialDestination(): String? = intent?.getStringExtra(EXTRA_DESTINATION)

    companion object {
        /** Opens the app on a given screen (a [app.booxultimatum.ui.Destination] name); used by BooxUltimatum home. */
        const val EXTRA_DESTINATION = "app.booxultimatum.extra.DESTINATION"
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(onBinder)
        Shizuku.removeBinderDeadListener(onBinderDead)
        Shizuku.removeRequestPermissionResultListener(onPermission)
        super.onDestroy()
    }
}
