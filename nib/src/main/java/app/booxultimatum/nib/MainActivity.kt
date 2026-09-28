package app.booxultimatum.nib

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.booxultimatum.kit.core.Suite
import app.booxultimatum.kit.core.SuiteApp
import app.booxultimatum.kit.ui.theme.Ink
import app.booxultimatum.kit.ui.theme.InstrumentTheme
import app.booxultimatum.kit.update.Updates
import app.booxultimatum.nib.pen.PenRouter
import app.booxultimatum.nib.ui.NibApp

/**
 * Nib's one activity. It handles rotation itself (see the manifest), and it sees every pen event before Compose does,
 * so the pen session pauses the moment the pen hovers over anything but the canvas.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge with an ink strip under the status bar (see StatusStrip), as in the hub.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
            show(WindowInsetsCompat.Type.statusBars())
        }
        NibSettings.get(this)
        Updates.init(this, setOf(SuiteApp.Nib))
        // With the hub installed, updates come through it; on its own, Nib looks for its own once a day.
        if (Suite.installed(this, SuiteApp.Hub) == null) Updates.checkIfDue(this)
        val open = intent?.getStringExtra(EXTRA_OPEN_DRAWING)
        setContent {
            InstrumentTheme {
                Box(Modifier.fillMaxSize().background(Ink.Paper).navigationBarsPadding().imePadding()) { NibApp(initialDrawing = open) }
            }
        }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        PenRouter.onGenericMotion(ev)
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        PenRouter.onTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    companion object {
        /** Opens the editor on this drawing id straight away (tests, and links from other suite apps). */
        const val EXTRA_OPEN_DRAWING = "app.booxultimatum.nib.extra.OPEN_DRAWING"
    }
}
