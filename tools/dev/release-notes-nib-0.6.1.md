Nib 0.6.1 fixes how a stroke looks while you draw it: a light pen now previews as light as the line it leaves behind, instead of solid black under the nib.

## What's new

- **A light stroke looks the same under the nib and after it.** Nib sends the display the lightness its ink will leave on paper rather than solid black, so a graphite pencil and any pen below full opacity no longer darken while drawing and then lighten on the lift. The marker keeps its own half tone.

## Fixed

- **Light pens preview as light as they draw.** A 45 % pencil previews at that grey and a half-opaque pen at half black, instead of both previewing at full strength.

## Tested

- **Checked:** the engine's unit tests, including the new preview colours, and Nib's own tests; lint and a release build. Those ran on a desktop, not on the app's target, a Note Air6 C on firmware 4.3.1.
- **Not yet seen:** the new preview on a tablet. The greys were seen there in the marker style only; whether every other style shows them the same way is the owner's check.

## Known limits

- The preview is a solid colour, so it can't imitate a translucent stroke where it crosses ink already on the page.
- Only the Note Air6 C on firmware 4.3.1 is tested.

The full history is in [CHANGELOG.md](https://github.com/huuunleashed/BooxUltimatum/blob/main/CHANGELOG.md).

Not affiliated with Onyx or BOOX.
