package app.booxultimatum.nib.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.booxultimatum.nib.editor.OpenSessions
import app.booxultimatum.nib.store.DrawingStore

/** Where the app is. Saved as plain strings, so it survives the process being recreated. */
sealed interface Route {
    data object Library : Route
    data class Editor(val id: String) : Route
    data object Diagnostics : Route
    data object About : Route

    fun encode(): String = when (this) {
        Library -> "library"
        is Editor -> "editor:$id"
        Diagnostics -> "diagnostics"
        About -> "about"
    }

    companion object {
        fun decode(s: String): Route = when {
            s.startsWith("editor:") -> Editor(s.removePrefix("editor:"))
            s == "diagnostics" -> Diagnostics
            s == "about" -> About
            else -> Library
        }
    }
}

/** Nib's screens and the way back between them. */
@Composable
fun NibApp(initialDrawing: String?) {
    val context = LocalContext.current
    val sessions = remember { OpenSessions(DrawingStore.get(context)) }
    var stack by rememberSaveable {
        mutableStateOf(listOfNotNull("library", initialDrawing?.let { Route.Editor(it).encode() }).joinToString("|"))
    }
    val routes = stack.split("|").map { Route.decode(it) }
    val current = routes.last()
    fun push(r: Route) {
        stack = (routes + r).joinToString("|") { it.encode() }
    }
    fun pop() {
        if (routes.size > 1) stack = routes.dropLast(1).joinToString("|") { it.encode() }
    }
    // A drawing stays open while its editor is anywhere on the stack; leaving it for good saves and closes it.
    SideEffect { sessions.retain(routes.filterIsInstance<Route.Editor>().mapTo(HashSet()) { it.id }) }
    DisposableEffect(Unit) { onDispose { sessions.closeAll() } }
    BackHandler(enabled = routes.size > 1) { pop() }
    when (current) {
        Route.Library -> LibraryScreen(
            onOpen = { push(Route.Editor(it)) },
            onDiagnostics = { push(Route.Diagnostics) },
            onAbout = { push(Route.About) },
        )
        is Route.Editor -> EditorScreen(
            drawingId = current.id,
            sessions = sessions,
            onBack = { pop() },
            onDiagnostics = { push(Route.Diagnostics) },
            onAbout = { push(Route.About) },
        )
        Route.Diagnostics -> DiagnosticsScreen(onBack = { pop() })
        Route.About -> AboutScreen(onBack = { pop() })
    }
}
