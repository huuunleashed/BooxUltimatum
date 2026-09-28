---
name: Nib
description: Cut-card instruments on a desk, for drawing on e-ink. The drawing app of the BooxUltimatum suite.
colors:
  desk: "#DDDDDD"
  desk-grid: "#D0D0D0"
  desk-grid-major: "#BBBBBB"
  card: "#FFFFFF"
  ink: "#000000"
  legend: "#4A4A4A"
  faint: "#9A9A9A"
  wash: "#EEEEEE"
  checker: "#CCCCCC"
  lamp: "#1E7A46"
  alert: "#B3261E"
  paper-white: "#FFFFFF"
  paper-warm: "#F6F0E1"
  paper-grey: "#EEEEEE"
  paper-black: "#111111"
  guide-light: "#B4B4B4"
  guide-dark: "#5C5C5C"
typography:
  wordmark:
    fontFamily: "Archivo"
    fontSize: "56sp"
    fontWeight: 800
    lineHeight: "56sp"
    letterSpacing: "-0.035em"
  heading:
    fontFamily: "Archivo"
    fontSize: "28sp"
    fontWeight: 800
    lineHeight: "34sp"
    letterSpacing: "-0.015em"
  title:
    fontFamily: "Archivo"
    fontSize: "19sp"
    fontWeight: 700
    lineHeight: "24sp"
  label:
    fontFamily: "Archivo"
    fontSize: "15sp"
    fontWeight: 600
    lineHeight: "20sp"
  body:
    fontFamily: "Archivo"
    fontSize: "16sp"
    fontWeight: 400
    lineHeight: "23sp"
  small:
    fontFamily: "Archivo"
    fontSize: "13sp"
    fontWeight: 500
    lineHeight: "18sp"
  badge:
    fontFamily: "Archivo, 88% width"
    fontSize: "12sp"
    fontWeight: 700
    lineHeight: "14sp"
    letterSpacing: "0.04em"
    fontFeature: "tnum, lnum"
  value:
    fontFamily: "Archivo, 88% width"
    fontSize: "18sp"
    fontWeight: 700
    lineHeight: "22sp"
    fontFeature: "tnum, lnum"
  figure:
    fontFamily: "Archivo, 88% width"
    fontSize: "30sp"
    fontWeight: 800
    lineHeight: "32sp"
    fontFeature: "tnum, lnum"
rounded:
  s: "4dp"
  m: "6dp"
  l: "8dp"
spacing:
  s1: "4dp"
  s2: "8dp"
  s3: "12dp"
  s4: "16dp"
  s5: "24dp"
  s6: "32dp"
  edge: "12dp"
components:
  pill:
    backgroundColor: "{colors.card}"
    rounded: "{rounded.l}"
    padding: "4dp"
  key:
    backgroundColor: "{colors.card}"
    textColor: "{colors.ink}"
    rounded: "{rounded.m}"
    size: "52dp"
  key-selected:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.card}"
    rounded: "{rounded.s}"
    size: "52dp"
  key-pressed:
    backgroundColor: "{colors.wash}"
  button-plain:
    backgroundColor: "{colors.card}"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.m}"
    padding: "10dp 16dp"
    height: "48dp"
  button-primary:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.card}"
    typography: "{typography.label}"
    rounded: "{rounded.m}"
    padding: "10dp 16dp"
    height: "48dp"
  value-chip:
    backgroundColor: "{colors.card}"
    textColor: "{colors.ink}"
    typography: "{typography.value}"
    rounded: "{rounded.s}"
    height: "48dp"
  panel:
    backgroundColor: "{colors.card}"
    rounded: "{rounded.l}"
  segment-selected:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.card}"
    typography: "{typography.label}"
    height: "48dp"
---

# Design system: Nib

Nib is the drawing app of the BooxUltimatum suite, on a 10.3" Kaleido 3 e-ink panel (1860 × 2480 at 300 ppi, colour at 150 ppi and muted). This file records Nib's visual world as it is built in `nib/src/main/java/app/booxultimatum/nib/ui/studio/`. The hub keeps its own world; see the last section.

## Overview

**Cut-card instruments laid on a desk.** The canvas is the whole screen: a white page with its own hard shadow on a flat grey desk that carries a faint cutting-mat grid. Nib's tools lie on top of it as white cards with hard black outlines and solid, unblurred offset shadows, like shapes cut from card and laid on paper. It is a stark, printed, neo-brutalist look, chosen for the panel: e-ink draws solid black crisply and turns blur and translucency to mud, so depth comes from offset black, never from softness or glass.

Personality lives in shape, shadow, type and the small inked detail on each glyph, never in motion. Nothing animates, nothing ripples, and a panel appears or goes in a single frame.

The owner's brief pinned this world (Sketchbook's level of interface: floating, flexible, smart), and it refuses Nib 0.1's flat toolbars docked to the edges and any dark glassy overlay.

## Colors

The strategy is restrained: greys and black from the panel's own sixteen levels, one lamp, and otherwise only the owner's ink.

- **Desk** `#DDDDDD` with minor grid lines `#D0D0D0` every 24 dp and major ones `#BBBBBB` every fifth. The grid is fixed to the screen while the page moves on it.
- **Card** `#FFFFFF`, **Ink** `#000000`: fills, outlines, shadows, text, selected fills.
- **Legend** `#4A4A4A` (8.6:1 on white) for secondary text. **Faint** `#9A9A9A` for ticks, disabled glyphs and hairline rules, never for text. **Wash** `#EEEEEE` for insets, the pressed state and the active layer's row. **Checker** `#CCCCCC` on white for transparency.
- **Lamp** `#1E7A46` (the kit's Signal green) means active or live, and only that. It is always doubled by a filled shape, since Kaleido mutes it.
- **Alert** `#B3261E` marks an armed destructive key and error text, nothing else.
- **Paper** is the drawing's own: white, warm `#F6F0E1`, grey `#EEEEEE` or black `#111111`. Guides are `#B4B4B4` on light paper and `#5C5C5C` on dark.
- Every other colour on screen is the owner's ink: the colour well, pen slots, swatches, brush samples.

## Typography

One family, Archivo (the kit's variable font), in two cuts. Words use the normal width; every value uses the 88 % width cut with tabular, lining figures, so numbers never jitter as they change.

| Role | Size / line | Weight | Use |
|---|---|---|---|
| Wordmark | 56 / 56 sp, −0.035 em | 800 | "Nib" on the shelf |
| Heading | 28 / 34 sp, −0.015 em | 800 | Page titles, the empty shelf |
| Title | 19 / 24 sp | 700 | Panel and section titles, card names, menu rows |
| Label | 15 / 20 sp | 600 | Keys, buttons, slider names, segments |
| Body | 16 / 23 sp | 400 | Explanations |
| Small | 13 / 18 sp | 500 | Captions, details, hints |
| Badge | 12 / 14 sp, +0.04 em, narrow | 700 | A brush tile's preview style ("≈ FOUNTAIN"), page sizes |
| Value | 18 / 22 sp, narrow, tabular | 700 | Value chips, readouts, the view chip |
| Figure | 30 / 32 sp, narrow, tabular | 800 | A number being typed |

Sizes are in sp, so they follow the system font scale.

## Layout

- **The editor's first viewport.** Full-bleed desk and page. Top left: a pill with Library (back), Undo and Redo. Top right: a pill with Brushes, the colour well, Layers and Menu. Left edge (right edge with *Tools on the right*): the tool rail with six pen slots, the eraser, lasso, eyedropper and hand, and below it (beside it when the height is short) a pill of two vertical quick sliders, size and opacity. Bottom centre: the view chip ("78 % · 0°") with Reset view and Full screen. A selection adds its action bar above the view chip.
- **Floating controls keep 12 dp from every edge** and from each other; the editor reserves 84 dp at the top and bottom and at each side (156 dp beside a side-by-side rail) when it fits the page.
- **Panels open beside the key that opened them**: below a key in the top band, above a key in the bottom band, and to the side of a key on the rail. They are always wholly on screen, can be dragged by their header, and remember where they were left separately in portrait and landscape. The rules are pure code (`PanelPlacement`) and tested.
- **Pages, not endless scrolls.** The shelf pages its cards (columns every 250 dp, rows every 300 dp), the Layers card pages its layers (six in portrait, four in landscape), Brushes pages by family, and Brush settings splits into Size and ink, Pressure and Feel.
- **Text entry is always at the top** (the entry bar, the shelf's search), where the keyboard can't cover it, and the keyboard never resizes the canvas.
- Portrait and landscape are both first-class: the rail and its sliders stack when there is room and sit side by side when there isn't, and a fitted page is fitted again when the tablet turns.

## Elevation & Depth

Depth is a solid black copy of the shape offset down and to the right, never blurred, never tinted, never a glow.

| Level | Offset | Used on |
|---|---|---|
| Key | 3 dp | Buttons, value chips, swatches, tiles, slider thumbs, selection handles |
| Slab | 4 dp | Pills, the toast, segmented controls on the desk |
| Card | 6 dp | Panels, shelf cards, page sections, the page itself on the desk |

A pressed slab sinks into its shadow: it moves by the offset and its shadow goes, in the same frame. A chosen swatch or tile sits flat (no shadow) with a thicker outline. Disabled controls lose their shadow and outline to Faint.

## Shapes

- **Outlines** are 2 dp black on every card, key, chip, track and thumb; 3 dp marks a focused search field, a chosen swatch or a picked card; a 1 dp Faint hairline separates rows inside a card.
- **Radii** are small: 4 dp (chips, swatches, thumbs, selected key fill), 6 dp (keys, buttons, tiles, segmented controls), 8 dp (pills, panels, cards). The page and its thumbnails are square.
- **Glyphs** (`StudioGlyphs`) are Nib's own: a 24-unit keyline, 2-unit strokes with round caps and joins, and exactly one small inked (filled) detail each, which gives them weight on e-ink: an ink bead on the nib's tip and on Undo and Redo, a shaded graphite cone, a marker's cap band, a crescent of ink on the eraser's working edge, an inked bulb and drop on the eyedropper, the top sheet of Layers, a pinned pin's head. They are drawn at 22 to 26 dp inside 48 to 52 dp keys.

## Components

- **Pill:** a card of flat keys with 2 dp black rules between groups.
- **Key** (`StudioKey`, 52 dp, 48 dp in panels): the glyph black on white; pressed, a Wash fill for that instant; **selected, an inset black fill with a white glyph and the lamp at its top-right corner**. A pen slot is a key whose face is its own stroke, drawn by the engine in its colour with a dot as wide as it draws; chosen, the black fill frames a small paper chip so the ink stays visible.
- **Slab button** (`SlabButton`): plain (white) or primary (black, one per card); danger arms on the first tap (a red rule under its words) and acts on the second, disarming itself after four seconds.
- **Slider** (`StudioSlider`): the name and a value chip on one line, then − and + keys at either end of a 14 dp outlined track with a card thumb that casts its own 3 dp shadow. Values snap to fine steps (a quarter pixel at the thin end of the log width track), the keys step one snap, and the chip opens the entry bar to type an exact value, which is checked and clamped. Tracks can show a fill, a checkerboard fading into the ink (opacity), or colours along their length (hue). A quick slider on the rail is the same, vertical.
- **Value chip:** a small slab of tabular figures, 48 dp tall, that opens the entry bar.
- **Toggle:** an outlined square-cornered track; on, it fills black and its square knob sits right with the lamp inside; the whole row is the target.
- **Segmented:** choices joined in one slab with 2 dp rules; the chosen one is solid black.
- **Choice tile:** a slab with a picture above a 2 dp rule and a name strip below; chosen, the strip turns black with the lamp. Brush tiles show a sample stroke the engine draws at the pen's width and colour; pressure tiles show their curve and a sample; paper tiles show the paper with its guides.
- **Floating panel** (`FloatingPanel`): a card with a header (grip, title, pin, close) over a 2 dp rule. Pinned (the pin's head inked), it stays while the pen draws; unpinned, the pen's next touch on the canvas closes it instead of drawing. It takes every touch on it, so none falls through to the canvas.
- **Entry bar:** a card at the top centre with the field (figures for numbers), what it takes, Set and Cancel, and the reason when an entry is refused.
- **Canvas marks:** the lasso's loop is a dashed 1.5 dp line; a selection is a dashed 2 dp frame with square white handles and a round turn handle, each with a 3 dp shadow; the eyedropper's loupe is a ringed disc of the colour under the pen.

## Do's and Don'ts

- **Do** give every control 48 dp or more and 8 dp between targets.
- **Do** show a property before it is used: every brush property has a picture (a sample, a curve, a dial, a checkerboard).
- **Do** mark state with shape first and the lamp second; never with colour alone.
- **Do** keep panel changes local: they never redraw or invalidate the canvas, and engine pictures are drawn off the main thread and cached.
- **Don't** animate, ripple, fade or blur anything. No transitions between panels, pages or states.
- **Don't** use translucent or dark overlays, gradients as decoration, or glass. The only gradients are colour pickers and the opacity track, where the gradient is the value.
- **Don't** soften the shadows or tint them; don't add a second accent colour.
- **Don't** put a coloured bar on the side of a card, row or message.
- **Don't** put text fields low on the screen or let the keyboard resize the canvas.
- **Don't** restyle `:kit:ui` components for Nib; Nib's components live in `nib/…/ui/studio/`.

## The hub's world

The hub (BooxUltimatum itself) keeps the calm **Braun Instrument** world of `:kit:ui`: paper white, pure black ink, legend grey and the same signal-green lamp; hairline rules, engraved section titles, pill keys with 1.5 dp rims, and no cards, shadows or motion. Its tokens and components are defined in `kit/ui/src/main/java/app/booxultimatum/kit/ui/theme/Instrument.kt` (`Ink`, `Space`, `Lines`, `InstrumentTheme`, `Archivo`, `ArchivoNarrow`) and `kit/ui/src/main/java/app/booxultimatum/kit/ui/Components.kt`. Nib reuses only the kit's ink values, the Archivo font and the status strip; it doesn't change them.
