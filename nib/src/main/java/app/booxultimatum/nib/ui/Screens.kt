package app.booxultimatum.nib.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.booxultimatum.kit.ui.StatusStrip
import app.booxultimatum.nib.ui.studio.Pill
import app.booxultimatum.nib.ui.studio.Studio
import app.booxultimatum.nib.ui.studio.StudioGlyphs
import app.booxultimatum.nib.ui.studio.StudioKey
import app.booxultimatum.nib.ui.studio.StudioType
import app.booxultimatum.nib.ui.studio.slab

/** The desk's faint cutting-mat grid, fixed to the screen: minor lines every 24 dp, heavier ones every fifth. */
fun Modifier.deskGrid(): Modifier = drawBehind {
    val cell = 24.dp.toPx()
    var i = 1
    var x = cell
    while (x < size.width) {
        drawLine(if (i % 5 == 0) Studio.DeskGridMajor else Studio.DeskGrid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += cell
        i++
    }
    var j = 1
    var y = cell
    while (y < size.height) {
        drawLine(if (j % 5 == 0) Studio.DeskGridMajor else Studio.DeskGrid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += cell
        j++
    }
}

/**
 * A page outside the editor (Settings, Diagnostics, About): the desk, a back key in its own pill and a heading, then
 * the page's cards.
 */
@Composable
fun StudioPage(title: String, backLabel: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Studio.Desk).deskGrid()) {
        StatusStrip()
        Row(Modifier.fillMaxWidth().padding(horizontal = Studio.Edge, vertical = Studio.Edge), verticalAlignment = Alignment.CenterVertically) {
            Pill { StudioKey(StudioGlyphs.ChevronLeft, backLabel, onClick = onBack) }
            Spacer(Modifier.width(Studio.S4))
            Text(title, style = StudioType.Heading, modifier = Modifier.weight(1f).semantics { heading() })
            actions()
        }
        Column(Modifier.fillMaxSize(), content = content)
    }
}

/** A card on a page: a title strip and its body. */
@Composable
fun StudioSection(title: String, modifier: Modifier = Modifier, action: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .widthIn(max = 820.dp)
            .fillMaxWidth()
            .padding(end = Studio.ShadowCard, bottom = Studio.ShadowCard)
            .slab(radius = Studio.RadiusL, shadow = Studio.ShadowCard),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = StudioType.Title, modifier = Modifier.weight(1f).semantics { heading() })
            action()
        }
        Box(Modifier.fillMaxWidth().height(Studio.Outline).background(Studio.Ink))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** A label and its value, on one line, the value in tabular figures. */
@Composable
fun SpecLine(label: String, value: String, note: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = StudioType.Label, color = Studio.Legend, modifier = Modifier.weight(1f))
            Text(value, style = StudioType.Value)
        }
        if (note != null) Text(note, style = StudioType.Small, color = Studio.Legend)
    }
}
