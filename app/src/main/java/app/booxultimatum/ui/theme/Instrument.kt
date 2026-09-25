/*
 * DIRECTION CONTRACT: Braun Instrument (seed 682bc563, grounded candidate 4 of 7)
 * THESIS: The tablet's own precision instrument: one quiet white face, a real tuning-scale readout,
 *   round keys that each do one thing. Refuses the category default of a Material toggle list under a
 *   battery card.
 * OWN-WORLD: Paper white, pure black ink, legend grey, one signal-green lamp meaning "on". Archivo
 *   grotesque, tabular figures. Hairline rules, engraved section titles, pill keys with 1.5 dp rims,
 *   solid black for the single primary key. No cards, shadows, gradients or motion.
 * STORY: At a glance the owner knows the charge, whether it charges, how much it sleeps, and what the
 *   app may do. One tap opens precise readings with their source and time.
 * FIRST VIEWPORT: Rail left. Wordmark and firmware line top, Read again top right. Full-width tuning
 *   scale with the level riding the needle. Plain-language status lines. Drain and Access plates side by
 *   side. Planned pillars as a ruled "not built yet" list.
 * FORM: Braun ET66 / AB1 / T1000 instrument grammar on a single instrument face; no staging deal adopted.
 */
package app.booxultimatum.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.booxultimatum.R
import kotlinx.coroutines.launch

/** The instrument's inks. E-ink scene: a matte panel read under room light or a low front light, so light only. */
object Ink {
    val Paper = Color(0xFFFFFFFF)
    val Black = Color(0xFF000000)
    /** Engraved legends and secondary text. 8.6:1 on paper. */
    val Legend = Color(0xFF4A4A4A)
    /** Minor ticks and hairline rules; never used for text. */
    val Rule = Color(0xFF9A9A9A)
    /** The lamp. Meaning is always doubled by a filled shape, since Kaleido renders it muted. */
    val Signal = Color(0xFF1E7A46)
    val Alert = Color(0xFFB3261E)
}

object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 40.dp
    val xxl = 64.dp
}

object Lines {
    val hairline = 1.dp
    val rim = 1.5.dp
    val engraved = 2.dp
}

@OptIn(ExperimentalTextApi::class)
private fun archivo(weight: Int, width: Float = 100f) = Font(
    R.font.archivo,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width)),
)

val Archivo = FontFamily(archivo(400), archivo(500), archivo(600), archivo(700))

/** Semi-condensed cut for the scale figure and dense numerals. */
val ArchivoNarrow = FontFamily(archivo(500, 88f), archivo(600, 88f))

private const val TABULAR = "tnum, lnum"

private fun style(size: Int, line: Int, weight: Int, tracking: Double = 0.0, family: FontFamily = Archivo) = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
    fontFeatureSettings = TABULAR,
)

/** Material roles on a 1.2 step. Body sits large because the panel is read at arm's length with a stylus in hand. */
val InstrumentType = instrumentType(Archivo, ArchivoNarrow)

/**
 * The same scale set in the system font, for surfaces that must follow the user's chosen typeface (the launcher).
 * Tabular figures are kept; most system faces honour them.
 */
val SystemType = instrumentType(FontFamily.Default, FontFamily.Default)

private fun instrumentType(body: FontFamily, figures: FontFamily) = Typography(
    displayLarge = style(76, 80, 600, -0.03, figures),
    displayMedium = style(56, 60, 600, -0.025, figures),
    displaySmall = style(44, 48, 600, -0.02, figures),
    headlineLarge = style(36, 42, 600, -0.02, body),
    headlineMedium = style(30, 36, 600, -0.015, body),
    headlineSmall = style(25, 32, 600, -0.01, body),
    titleLarge = style(21, 28, 600, -0.005, body),
    titleMedium = style(18, 24, 600, family = body),
    titleSmall = style(16, 22, 600, family = body),
    bodyLarge = style(18, 27, 400, family = body),
    bodyMedium = style(16, 24, 400, family = body),
    bodySmall = style(14, 20, 400, family = body),
    labelLarge = style(16, 20, 600, 0.005, body),
    labelMedium = style(14, 18, 500, 0.01, body),
    labelSmall = style(13, 16, 500, 0.02, body),
)

/** Package ids and property keys are code, so they get the system monospace. */
val CodeStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp)

private val InstrumentColors = lightColorScheme(
    primary = Ink.Black, onPrimary = Ink.Paper,
    primaryContainer = Ink.Black, onPrimaryContainer = Ink.Paper,
    secondary = Ink.Black, onSecondary = Ink.Paper,
    secondaryContainer = Ink.Black, onSecondaryContainer = Ink.Paper,
    tertiary = Ink.Signal, onTertiary = Ink.Paper,
    background = Ink.Paper, onBackground = Ink.Black,
    surface = Ink.Paper, onSurface = Ink.Black,
    surfaceVariant = Ink.Paper, onSurfaceVariant = Ink.Legend,
    surfaceContainer = Ink.Paper, surfaceContainerLow = Ink.Paper, surfaceContainerHigh = Ink.Paper,
    surfaceContainerLowest = Ink.Paper, surfaceContainerHighest = Ink.Paper,
    outline = Ink.Black, outlineVariant = Ink.Rule,
    error = Ink.Alert, onError = Ink.Paper,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstrumentTheme(systemFont: Boolean = false, customFont: FontFamily? = null, content: @Composable () -> Unit) {
    val type = when {
        customFont != null -> androidx.compose.runtime.remember(customFont) { instrumentType(customFont, customFont) }
        systemFont -> SystemType
        else -> InstrumentType
    }
    MaterialTheme(colorScheme = InstrumentColors, typography = type) {
        // Ripples repaint the panel several times per tap; the instrument answers with a flat, instant press mark instead.
        CompositionLocalProvider(
            LocalRippleConfiguration provides null,
            LocalIndication provides InstrumentIndication,
            content = content,
        )
    }
}

/** Press = a flat grey wash for one frame of contact; keyboard focus = a 2 dp black ring. No animation. */
object InstrumentIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource)
    override fun hashCode() = 7
    override fun equals(other: Any?) = other === this

    private class Node(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
        private var pressed = false
        private var focused = false

        override fun onAttach() {
            coroutineScope.launch {
                val presses = mutableListOf<PressInteraction.Press>()
                val focuses = mutableListOf<FocusInteraction.Focus>()
                source.interactions.collect { i ->
                    when (i) {
                        is PressInteraction.Press -> presses += i
                        is PressInteraction.Release -> presses -= i.press
                        is PressInteraction.Cancel -> presses -= i.press
                        is FocusInteraction.Focus -> focuses += i
                        is FocusInteraction.Unfocus -> focuses -= i.focus
                    }
                    pressed = presses.isNotEmpty()
                    focused = focuses.isNotEmpty()
                    invalidateDraw()
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (pressed) drawRect(Ink.Rule, alpha = 0.35f)
            if (focused) drawRect(Ink.Black, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
        }
    }
}
