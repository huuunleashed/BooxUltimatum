# 05 · BooxUltimatum home app

Goal: a home screen that's as good as the Boox home app or better, richer in features, and extremely compatible, so switching away from the Boox home never costs a function. It must also never compete with the Boox home for memory or CPU. The app calls the result *BooxUltimatum home*. In code, the word *launcher* stays in package and class names.

## Compatibility rules (non-negotiable)

1. **Only public APIs for home apps.** Use `LauncherApps` for the app list across all user profiles, launching (`startMainActivity`), badged icons, app shortcuts (`getShortcuts` / `startShortcut`, which are available once BooxUltimatum is the default home app), and package-change callbacks. No reflection and no hidden APIs.
2. **Behave like a home app.** Use `singleTask`, `stateNotNeeded`, `clearTaskOnLaunch`, and its own task affinity. Handle configuration changes (rotation included) without recreating. Pressing Home while already home returns to page one and closes panels. Back never leaves home. It closes what is open, innermost first.
3. **Don't own what BooxUltimatum doesn't need.** Recents (the recent apps screen) are provided by `com.android.onyxquickstep` (verified: `mRecentsComponent`), which doesn't depend on the home app. Boox gestures, NaviBall (Boox's floating navigation button) and EInkWise live in SystemUI and `com.onyx.floatingbutton`. BooxUltimatum home doesn't replace any of them.
4. **Boox functions are first-class.** Every launchable Onyx app is available. The Boox widget has buttons for Library, Notes (the Boox home), Storage and Boox settings, and Boox's own widgets are listed first in the picker.
5. **E-ink etiquette.** Refresh only on events: `ACTION_TIME_TICK`, battery and connectivity broadcasts while visible, and package callbacks. Use pages instead of scrolling, keep motion to zero, support page turns with the volume buttons, and never reflow the page while the keyboard is up.
6. **Always reversible.** BooxUltimatum home ships turned off: its HOME alias is turned on from Suite › Home screen (before 0.6, Settings › Home screen). The Boox home is recorded in the journal before the first switch, and the switcher on the Home screen page returns to it in one tap. With Shizuku the switch is direct. Without it, Android's Default home app page opens for you to confirm. The adb fallback (a command run from a computer) is `cmd package set-home-activity --user 0 com.onyx/.StartupActivity`.

## What is built

- **Header:** time, date, Wi-Fi with network name, Bluetooth, airplane, Do not disturb and battery, each opening its system control. Three buttons sit beside them: Boox settings (a gear), BooxUltimatum settings (its needle mark) and Arrange home (a pencil). Every item can be shown or hidden, as symbols, symbols and words, or words. There are 8 Wi-Fi and 8 battery designs (Appearance).
- **Grid:** paged favourites and folders (named, up to four icons in the tile), 3–8 columns and 2–8 rows, never narrower than a comfortable cell. A horizontal swipe turns the page, in one repaint when the finger lifts. Icon shapes are as designed, circle, rounded, squircle and square. Icon styles are original, monochrome and greyscale. Frames that Boox draws into its own icons are detected and removed inside a shape, so each icon has one outline. A one-word name that is too long for its cell (BooxUltimatum) steps its size down instead of breaking mid-word.
- **Widgets:** clock (a Braun AB1-style dial with minute ticks, heavier hour ticks and two hands, and no second hand, so the screen isn't repainted every second), month, agenda, weather (Open-Meteo, a city you type, units from that city's country), battery, next alarm, note, the Boox widget (Library, Notes, Storage and Boox settings), and any app's widget through `AppWidgetHost`. Widgets are half or full width, and Shorter and Taller change their height in 40 dp steps. A row that doesn't fit first gives up its extra height, down to standard, and only then is skipped. Widgets are never pushed over the apps. Arrange home and the footer say how many are off screen.
- **Landscape:** widgets and apps sit side by side instead of stacked, so apps keep more than one row. Lists and panels are capped in width so lines stay readable.
- **Typing on home:** city search and the note open in a panel at the top of the screen, above the keyboard. Home pads itself only for the system bars and ignores the keyboard, so typing never reflows it. The bottom panels that take text (folder rename, new folder) lift themselves above the keyboard. An earlier version froze home at the tallest height seen, which could push the footer off screen after an inset change (a change in the space the system bars take, for example switching to gesture navigation). That is gone.
- **Drawer and panels:** a paged, searchable All apps (swipe or use the volume buttons to turn pages). It also lists the Boox functions that aren't launchable as apps of their own (Notes, Library, Storage, Shop, Boox settings). Apps can be sorted A–Z, by icon colour or by last use, as a grid or a list. Apps Boox froze are listed dimmed with a frozen tag instead of missing, because Android hides them from home apps while the Boox home still opens them. Touching and holding an app opens a panel for open, shortcuts, pin, reorder, folders, hide, app info and uninstall. Panels sit on a paper veil that closes them when tapped.
- **Look:** plain paper, the system wallpaper, or a picture (stored at half resolution, with the rotation in its EXIF data applied, removable), with an adjustable paper veil. The font follows the tablet font by default, loaded at every weight. The Boox system font is often a variable file (one font file with adjustable weights) whose default instance is its lightest. The font can also be BooxUltimatum's own or any downloaded Google font. A text-weight offset makes text one step heavier by default. Labels switch between black and white ink with a soft halo depending on the backdrop, and an ink strip sits under the status bar because the firmware keeps its icons white here.

## As the default home (verified 2026-09-26)

Set as the default home app through the Home screen page (then under Settings, now under Suite) on the maintainer's Note Air6 C (NA6C), firmware 4.3. Checked there:

- The Home button returns to home from any app, and Back on home stays on home.
- App shortcuts appear in the touch-and-hold panel (they need the default home app role), and launching one works.
- Library, Notes (the Boox home, opened as a normal screen) and Storage open from the Boox widget, and Home comes back from each.
- Recents (`com.android.onyxquickstep`) opens and returns to home.
- After the app process is killed, the Home button restarts home at once and the default is kept.

## The window and the wallpaper

- **Why the window is transparent.** In System wallpaper mode it has no background of its own and shows the wallpaper through `FLAG_SHOW_WALLPAPER`. That's the only way: since Android 13, reading the system wallpaper takes a permission ordinary apps aren't given.
- **The ghost it caused.** A transparent home shows whatever Android leaves beneath it. Android keeps a picture of the home screen when the screen turns off, and on unlock it can show that picture as a starting window. Behind a transparent home it looked like a frozen, faded copy one status bar lower, and on other unlocks its white background hid the wallpaper (reported by the maintainer, 2026-09-28).
- **The fix.** Home has its own theme, `Theme.BooxUltimatum.Home`: transparent, with the wallpaper declared and `windowDisablePreview`. On Android 13 and later it also calls `setRecentsScreenshotEnabled(false)`, so Android only ever keeps a picture of the transparent theme. The Plain paper and A picture backgrounds paint an opaque background over it at runtime. The first frame after each start is logged under `home`, with whether the window shows the wallpaper.

**Pen ink left over home.** The display doesn't notice when an app that opened a pen session dies, so an app killed mid-stroke (Nib during an update, for instance) left its preview drawing over home. No app draws over home, and Instant ink pauses there. So 1.5 s after home comes to the front, `StrayInk` reads the display's pen state off the main thread. If a session is still drawing, it recovers the screen the way the Instant ink page's *Recover screen* does. It logs a warning under `ink` when it does.

## Resource budget (measured 2026-09-26, `knowledge/experiments.md`)

Measured on the maintainer's Note Air6 C with `dumpsys meminfo` and `top`. PSS (proportional set size) is the memory a process really uses, with shared memory counted by its share.

| | BooxUltimatum home | Boox home (`com.onyx`) |
|---|---|---|
| PSS in the foreground | 64–132 MB (about 60 MB is window buffers just after start) | 455 MB |
| PSS in the background | 29–44 MB | — |
| CPU while idle on home | 0.0 % | 0.2 % |

The design choices behind these figures:

- No polling.
- Receivers and the network callback are registered only between `onStart` and `onStop`.
- An 8 MB icon cache is trimmed when the UI is hidden.
- The wallpaper is decoded at half resolution in RGB_565 (16 bits per pixel) and only when the file changes.
- Widget hosting listens only while visible.
- Weather is refreshed only while visible and at most hourly.
- The Shizuku helper process is released after 45 s of idle.

## Planned

- Notification dots through an opt-in notification listener.
- Onyx front-light and refresh quick actions through the Onyx SDK.
- Per-page layouts and backup/restore of the layout.
