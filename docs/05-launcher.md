# 05 · BooxUltimatum launcher

Goal: a home screen that's as good as the Boox launcher or better, richer in features, and extremely compatible, so switching away from the Boox home never costs a function. It must also never compete with the Boox launcher for memory or CPU.

## Compatibility rules (non-negotiable)

1. **Only public launcher APIs.** Use `LauncherApps` for the app list across all user profiles, launching (`startMainActivity`), badged icons, app shortcuts (`getShortcuts` / `startShortcut`, which are available once we're the default home), and package-change callbacks. No reflection and no hidden APIs.
2. **Behave like a home app.** Use `singleTask`, `stateNotNeeded`, `clearTaskOnLaunch`, and its own task affinity. Handle configuration changes (rotation included) without recreating. Pressing Home while already home returns to page one and closes panels. Back never leaves home; it closes what is open, innermost first.
3. **Don't own what we don't need.** Recents are provided by `com.android.onyxquickstep` (verified: `mRecentsComponent`), which doesn't depend on the home app. Boox gestures, NaviBall and EInkWise live in SystemUI and `com.onyx.floatingbutton`. The launcher doesn't replace any of them.
4. **Boox functions are first-class.** Every launchable Onyx app is available, the Boox shelf widget opens Library, Notes (the Boox home) and Storage, and Boox's own widgets are listed first in the picker.
5. **E-ink etiquette.** Refresh only on events: `ACTION_TIME_TICK`, battery and connectivity broadcasts while visible, and package callbacks. Use pages instead of scrolling, keep motion to zero, support side-button page turns, and never reflow the page while the keyboard is up.
6. **Always reversible.** The launcher ships disabled (a HOME alias turned on from Suite › Home screen; before 0.6, Settings › Home screen), the Boox home is journaled before the first switch, and the switcher returns to it in one tap. The adb fallback is `cmd package set-home-activity --user 0 com.onyx/.StartupActivity`.

## What is built

- **Header:** time, date, Wi-Fi with network name, Bluetooth, airplane, Do not disturb and battery, each opening its system control, plus three keys: Boox Settings (gear), BooxUltimatum's own settings (its needle mark) and Edit. Every item can be shown or hidden, in symbols, symbols and words, or words, with 8 Wi-Fi and 8 battery designs (Appearance).
- **Grid:** paged favourites and folders (named, up to four icons in the tile), 3–8 columns and 2–8 rows, never narrower than a comfortable cell. A horizontal swipe turns the page, in one repaint when the finger lifts. Icon shapes (as designed, circle, rounded, squircle, square) and styles (original, monochrome, grayscale). Frames that Boox draws into its own icons are detected and removed inside a shape, so each icon has one outline. A one-word name that is too long for its cell (BooxUltimatum) steps its size down instead of breaking mid-word.
- **Widgets:** clock (AB1 dial), month, agenda, weather (Open-Meteo, a city the user types, units from that city's country), battery, next alarm, note, the Boox shelf, and any app's widget through `AppWidgetHost`. Widgets are half or full width, with Shorter/Taller in 40 dp steps. A row that doesn't fit first gives up its extra height, down to standard, and only then is skipped. Widgets are never pushed over the apps, and Edit and the footer say how many are off screen.
- **Landscape:** widgets and apps sit side by side instead of stacked, so apps keep more than one row. Lists and panels are capped in width so lines stay readable.
- **Typing on home:** city search and the note open in a panel at the top of the screen, above the keyboard. Home pads itself only for the system bars and ignores the keyboard, so typing never reflows it; the bottom panels that take text (folder rename, new folder) lift themselves above the keyboard. An earlier version froze home at the tallest height seen, which could push the footer off screen after an inset change (for example switching to gesture navigation); that is gone.
- **Drawer and panels:** a paged, searchable All apps (swipe or keys to turn pages) that also lists the Boox functions without launcher icons (Notes, Library, Storage, Shop, Boox settings), sorted A–Z, by icon colour or by last use, as a grid or a list. A long-press panel for open, shortcuts, pin, reorder, folders, hide, app info and uninstall. Panels sit on a paper veil that closes them when tapped.
- **Look:** plain paper, the system wallpaper, or a picture (stored at half resolution, EXIF rotation applied, removable), with an adjustable paper veil. The typeface follows the tablet font by default, loaded at every weight (the Boox system font is often a variable file whose default instance is its lightest), or the app's font, or any downloaded Google font; a text-weight offset makes it one step heavier by default. Labels switch between black and white ink with a soft halo depending on the backdrop, and an ink strip sits under the status bar because the firmware keeps its icons white here.

## As the default home (verified 2026-09-26)

Set as default through Settings › Home screen on NA6C FW 4.3. Checked on the tablet:

- The Home key returns to home from any app, and Back on home stays on home.
- App shortcuts appear in the long-press panel (they need the default-home role), and launching one works.
- Library, Notes (the Boox home, opened as a normal screen) and Storage open from the Boox shelf, and Home comes back from each.
- Recents (`com.android.onyxquickstep`) opens and returns to home.
- After the app process is killed, the Home key restarts home at once and the default is kept.

## Resource budget (measured 2026-09-26, `knowledge/experiments.md`)

| | BooxUltimatum home | Boox home (`com.onyx`) |
|---|---|---|
| PSS in the foreground | 64–132 MB (about 60 MB is window buffers just after start) | 455 MB |
| PSS in the background | 29–44 MB | — |
| CPU while idle on home | 0.0 % | 0.2 % |

The design choices behind these figures: no polling; receivers and the network callback registered only between `onStart` and `onStop`; an 8 MB icon cache trimmed when the UI is hidden; the wallpaper decoded at half resolution in RGB_565 and only when the file changes; widget hosting that listens only while visible; weather refreshed only while visible and at most hourly; and the Shizuku helper process released after 45 s of idle.

## Planned

- Notification dots through an opt-in notification listener.
- Onyx front-light and refresh quick actions through the Onyx SDK.
- Per-page layouts and backup/restore of the layout.
