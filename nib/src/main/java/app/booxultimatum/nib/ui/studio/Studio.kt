/*
 * DIRECTION CONTRACT: Nib · cut-card instruments on a desk (brief-pinned by the owner, 2026-09-28)
 * THESIS: The canvas is the whole screen; Nib's tools lie on it like cut-card instruments laid on paper: white
 *   cards, hard black outlines, solid unblurred offset shadows. Refuses 0.1's flat edge-docked toolbars and glassy
 *   dark overlays, which e-ink can't render. Solid black is what the panel draws crisply; blur turns to mud.
 * OWN-WORLD: A flat grey desk with a faint cutting-mat grid, a white page with its own hard shadow, floating cards
 *   (2 dp outline, 3 to 6 dp black offset shadow, 4 to 8 dp radii, 48 dp and larger targets). Selected keys fill
 *   black with a white glyph and carry the single green lamp. Colour comes only from the owner's ink and the lamp.
 *   Archivo for words, ArchivoNarrow tabular figures for values. Own 2 dp glyphs, each with one inked detail.
 * STORY: The owner draws; every tool is one tap from the pen, every property shows what it does before the pen
 *   touches the page, and nothing moves unless the owner moves it.
 * FIRST VIEWPORT: Full-bleed desk and page. Top left: Library, Undo, Redo. Top right: Brushes, Colour well,
 *   Layers, Menu. Left rail: six pen slots drawn as their strokes, the tools, two quick sliders. Bottom centre:
 *   the view chip "85 % · 12°" with Reset view. Panels open as cards beside their key.
 * FORM: Neo-brutalist cut-paper print, e-ink edition: no motion, no ripple, no blur, instant state changes.
 */
package app.booxultimatum.nib.ui.studio

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.booxultimatum.kit.ui.theme.Archivo
import app.booxultimatum.kit.ui.theme.ArchivoNarrow

/** Nib's tokens: the desk, the card, the ink, and the few measures every component shares. */
object Studio {
    /** The desk: a flat grey from the panel's sixteen, so it dithers to nothing. */
    val Desk = Color(0xFFDDDDDD)
    val DeskGrid = Color(0xFFD0D0D0)
    val DeskGridMajor = Color(0xFFBBBBBB)

    /** Cards and the page. */
    val Card = Color(0xFFFFFFFF)

    /** Outlines, shadows, text, selected fills. */
    val Ink = Color(0xFF000000)

    /** Secondary text: 8.6:1 on white. */
    val Legend = app.booxultimatum.kit.ui.theme.Ink.Legend

    /** Ticks, disabled glyphs, checker squares; never text. */
    val Faint = Color(0xFF9A9A9A)

    /** Insets, empty tracks and the pressed state. */
    val Wash = Color(0xFFEEEEEE)

    /** The one lamp: active or live. Always doubled by a filled shape, since Kaleido mutes it. */
    val Lamp = app.booxultimatum.kit.ui.theme.Ink.Signal

    /** Destructive confirmations. */
    val Alert = app.booxultimatum.kit.ui.theme.Ink.Alert

    val Outline = 2.dp
    val Hairline = 1.dp

    /** Keys and chips cast a short shadow, slabs a middle one, panels and cards the longest. */
    val ShadowKey = 3.dp
    val ShadowSlab = 4.dp
    val ShadowCard = 6.dp

    val RadiusS = 4.dp
    val RadiusM = 6.dp
    val RadiusL = 8.dp

    /** The smallest touch target, and the rail's key. */
    val Target = 48.dp
    val Key = 52.dp

    /** The gap floating controls keep from the screen edge and from each other. */
    val Edge = 12.dp

    val S1 = 4.dp
    val S2 = 8.dp
    val S3 = 12.dp
    val S4 = 16.dp
    val S5 = 24.dp
    val S6 = 32.dp
}

/** Nib's type roles: Archivo for words, the narrow cut with tabular figures for every value. */
object StudioType {
    private const val FIGURES = "tnum, lnum"

    val Wordmark = TextStyle(fontFamily = Archivo, fontWeight = FontWeight(800), fontSize = 56.sp, lineHeight = 56.sp, letterSpacing = (-0.035).em)
    val Heading = TextStyle(fontFamily = Archivo, fontWeight = FontWeight(800), fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.015).em)
    val Title = TextStyle(fontFamily = Archivo, fontWeight = FontWeight(700), fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.005).em)
    val Label = TextStyle(fontFamily = Archivo, fontWeight = FontWeight(600), fontSize = 15.sp, lineHeight = 20.sp)
    val Body = TextStyle(fontFamily = Archivo, fontWeight = FontWeight(400), fontSize = 16.sp, lineHeight = 23.sp)
    val Small = TextStyle(fontFamily = Archivo, fontWeight = FontWeight(500), fontSize = 13.sp, lineHeight = 18.sp)
    val Badge = TextStyle(fontFamily = ArchivoNarrow, fontWeight = FontWeight(700), fontSize = 12.sp, lineHeight = 14.sp, letterSpacing = 0.04.em, fontFeatureSettings = FIGURES)
    val Value = TextStyle(fontFamily = ArchivoNarrow, fontWeight = FontWeight(700), fontSize = 18.sp, lineHeight = 22.sp, fontFeatureSettings = FIGURES)
    val Figure = TextStyle(fontFamily = ArchivoNarrow, fontWeight = FontWeight(800), fontSize = 30.sp, lineHeight = 32.sp, fontFeatureSettings = FIGURES)
}
