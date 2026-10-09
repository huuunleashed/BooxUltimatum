package app.booxultimatum.kit.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.booxultimatum.kit.ui.theme.Ink

/** OWNER: C. Diagonal hatching for "something is wrong here" fills: shape and pattern, never colour alone. */
fun DrawScope.drawHatch(
    topLeft: Offset,
    size: Size,
    color: Color = Ink.Black,
    lineWidth: Float = 2.dp.toPx(),
    spacing: Float = 6.dp.toPx(),
) {}

/** OWNER: C. The same hatching as a background drawn behind the content. */
fun Modifier.hatch(color: Color = Ink.Black, lineWidth: Dp = 2.dp, spacing: Dp = 6.dp): Modifier = this