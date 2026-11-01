Nib 0.6.0 changes how a new line is drawn: the ink follows the pen the way Boox's own notes and reader do. Everything drawn before the update opens and looks exactly as it did.

## What's new

- **Lines that follow the pen, not the samples.** Nib now smooths a new stroke with the same kind of filter Boox's own note app uses, so a fast line curves evenly instead of stepping between the pen's reports. The Smoothing setting keeps its meaning and its place, and old drawings are untouched.
- **The same pen-up finish as before.** A stroke still ends exactly where you lifted the pen, with the filter's short run-on eased onto that point rather than left trailing.

## Tested

- **Checked:** the engine's unit tests, including the filter's constants and its pen-up behaviour, and Nib's own tests; lint and a release build. Those ran on a desktop, not on the app's target, a Note Air6 C on firmware 4.3.1.
- **Not yet seen:** the new smoothing in the hand on a tablet. Whether a line now sits on top of the display's preview as it should is the owner's check, and the result will be recorded either way.

## Known limits

- The smoothing window counts samples, so a tablet whose pen reports more slowly than a Note Air6 C will smooth over a longer stretch of time at the same setting.
- Only the Note Air6 C on firmware 4.3.1 is tested.

The full history is in [CHANGELOG.md](https://github.com/huuunleashed/BooxUltimatum/blob/main/CHANGELOG.md).

Not affiliated with Onyx or BOOX.
