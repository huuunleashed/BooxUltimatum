package app.booxultimatum.nib.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.booxultimatum.nib.pen.PenShields
import kotlin.math.roundToInt

/**
 * What the floating panels share: which are open (the last on top), which are pinned, where each was left in each
 * orientation, and where the keys that open them are. Pinned panels stay while the pen draws; the others close when
 * the pen touches the canvas. Main thread only.
 */
@Stable
class PanelState(private val memory: PanelMemory) {
    private val open = mutableStateListOf<String>()
    private val anchors = mutableStateMapOf<String, Area>()

    // Read from the preferences once each; [revision] tells Compose when one changes.
    private val pins = HashMap<String, Boolean>()
    private val spots = HashMap<String, Spot?>()
    private var revision by mutableIntStateOf(0)

    /** The window position of the area the panels float in. */
    var origin by mutableStateOf(Offset.Zero)
        internal set

    val openIds: List<String> get() = open

    fun isOpen(id: String): Boolean = id in open

    /** Opens [id] on top, closing any other unpinned panel. */
    fun show(id: String) {
        open.removeAll { it != id && !pinned(it) }
        open.remove(id)
        open.add(id)
    }

    fun close(id: String) {
        open.remove(id)
    }

    fun toggle(id: String) = if (isOpen(id)) close(id) else show(id)

    fun raise(id: String) {
        if (open.lastOrNull() == id || id !in open) return
        open.remove(id)
        open.add(id)
    }

    fun pinned(id: String): Boolean {
        revision
        return pins.getOrPut(id) { memory.pinned(id) }
    }

    fun setPinned(id: String, pinned: Boolean) {
        pins[id] = pinned
        memory.setPinned(id, pinned)
        revision++
    }

    /** Closes every unpinned panel; returns whether any was open. */
    fun closeUnpinned(): Boolean {
        val gone = open.filter { !pinned(it) }
        open.removeAll(gone)
        return gone.isNotEmpty()
    }

    val anyUnpinnedOpen: Boolean get() = open.any { !pinned(it) }

    fun anchor(id: String): Area? = anchors[id]

    internal fun setAnchor(id: String, a: Area) {
        if (anchors[id] != a) anchors[id] = a
    }

    /** Where the owner left [id] in this orientation, in dp; null beside its key. */
    fun spot(id: String, landscape: Boolean): Spot? {
        revision
        return spots.getOrPut("$id.$landscape") { memory.spot(id, landscape) }
    }

    fun setSpot(id: String, landscape: Boolean, spot: Spot?) {
        spots["$id.$landscape"] = spot
        memory.setSpot(id, landscape, spot)
        revision++
    }

    /** Every panel back beside its key. */
    fun forgetSpots() {
        spots.clear()
        memory.forgetSpots()
        revision++
    }
}

/** Records where the key that opens [id] is, so its panel can open beside it. */
fun Modifier.panelAnchor(state: PanelState, id: String): Modifier = onGloballyPositioned { c ->
    val b = c.boundsInWindow()
    state.setAnchor(id, Area(b.left, b.top, b.right, b.bottom))
}

/** Records where the panels' area is in the window. */
fun Modifier.panelOrigin(state: PanelState): Modifier = onGloballyPositioned { c ->
    val p = c.positionInWindow()
    if (state.origin != p) state.origin = p
}

/**
 * A floating panel: a card with a header you drag it by (grip, title, pin, close) and a body. It opens beside the key
 * that opened it, or where it was last left in this orientation, always wholly on screen, and is laid out in one pass,
 * so it appears in its place at once. Fills the space it's given, so put it in a full-size box over the canvas.
 */
@Composable
fun FloatingPanel(
    id: String,
    title: String,
    state: PanelState,
    shields: PenShields?,
    landscape: Boolean,
    width: Dp,
    pinLabel: String,
    unpinLabel: String,
    closeLabel: String,
    modifier: Modifier = Modifier,
    pinnable: Boolean = true,
    draggable: Boolean = true,
    onClose: () -> Unit = { state.close(id) },
    headerActions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    var drag by remember { mutableStateOf(Offset.Zero) }
    val placed = remember { FloatArray(2) }
    val pinned = pinnable && state.pinned(id)
    val savedDp = state.spot(id, landscape)
    val anchorWindow = state.anchor(id)
    val origin = state.origin
    Layout(
        modifier = modifier,
        content = {
            Column(
                Modifier
                    .penShield(shields, "panel:$id")
                    .slab(radius = Studio.RadiusL, shadow = Studio.ShadowCard)
                    .pointerInput(id) {
                        // Takes every touch on the card, so none falls through to the canvas, and brings it to the top.
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                if (e.changes.any { it.pressed && !it.previousPressed }) state.raise(id)
                            }
                        }
                    }
                    .semantics { paneTitle = title },
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .then(
                            if (draggable) {
                                Modifier.pointerInput(id, landscape) {
                                    detectDragGestures(
                                        onDragEnd = {
                                            state.setSpot(id, landscape, Spot(placed[0] / density, placed[1] / density))
                                            drag = Offset.Zero
                                        },
                                        onDragCancel = { drag = Offset.Zero },
                                    ) { change, amount ->
                                        change.consume()
                                        drag += amount
                                    }
                                }
                            } else {
                                Modifier
                            },
                        )
                        .padding(start = if (draggable) 6.dp else 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (draggable) Icon(StudioGlyphs.Grip, contentDescription = null, tint = Studio.Legend, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(title, style = StudioType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
                    headerActions()
                    if (pinnable) {
                        StudioKey(
                            if (pinned) StudioGlyphs.Pinned else StudioGlyphs.Pin,
                            if (pinned) unpinLabel else pinLabel,
                            onClick = { state.setPinned(id, !pinned) },
                            selected = pinned,
                            size = Studio.Target,
                        )
                    }
                    StudioKey(StudioGlyphs.Close, closeLabel, onClick = onClose, size = Studio.Target)
                }
                Box(Modifier.fillMaxWidth().height(Studio.Outline).background(Studio.Ink))
                content()
            }
        },
    ) { measurables, constraints ->
        val margin = Studio.Edge.roundToPx()
        val w = minOf(width.roundToPx(), constraints.maxWidth - 2 * margin).coerceAtLeast(0)
        val p = measurables.first().measure(Constraints(minWidth = w, maxWidth = w, maxHeight = (constraints.maxHeight - 2 * margin).coerceAtLeast(0)))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val area = Area(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
            val anchor = anchorWindow?.let { a -> Area(a.left - origin.x, a.top - origin.y, a.right - origin.x, a.bottom - origin.y) }
            val saved = savedDp?.let { Spot(it.x * density, it.y * density) }
            val base = PanelPlacement.place(p.width.toFloat(), p.height.toFloat(), area, anchor, saved, margin.toFloat(), Studio.Edge.toPx())
            val d = drag
            val spot = PanelPlacement.clamp(Spot(base.x + d.x, base.y + d.y), p.width.toFloat(), p.height.toFloat(), area, margin.toFloat())
            placed[0] = spot.x
            placed[1] = spot.y
            p.place(spot.x.roundToInt(), spot.y.roundToInt())
        }
    }
}
