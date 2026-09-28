# Third-party code and data

BooxUltimatum is GPL-3.0-or-later. Every piece of upstream code or data that gets incorporated is listed here with its source, the exact commit, its license, and the files involved.

## Libraries (dependencies, not vendored)

| Library | License | Use |
|---|---|---|
| AndroidX / Jetpack Compose | Apache-2.0 | UI and platform support |
| Shizuku API + provider (`dev.rikka.shizuku`) | Apache-2.0 | T2 privilege backend |

## Bundled assets

| Asset | Source | License | Where |
|---|---|---|---|
| Archivo (variable TTF) | [Omnibus-Type/Archivo](https://github.com/Omnibus-Type/Archivo) via Google Fonts | SIL OFL-1.1 | `kit/ui/src/main/res/font/archivo.ttf`, license text in `kit/ui/src/main/assets/licenses/archivo-OFL.txt` (shipped in every suite app) |

## Online services (used at runtime, nothing redistributed)

| Service | Terms | Use |
|---|---|---|
| [Google Fonts](https://fonts.google.com) catalogue and CSS2 API | Fonts are OFL-1.1 or Apache-2.0; each family's license is shown on its page | The Fonts browser downloads families only when the user previews or installs them |
| [Open-Meteo](https://open-meteo.com) forecast and geocoding APIs | Free for non-commercial use; data is CC BY 4.0, credited on the weather widget | The weather widget, for a city the user types; no location is read |
| [AdGuard DNS](https://adguard-dns.io) public resolver | Public service | Target of the optional *Block trackers with private DNS* tweak |

## Vendored code

None yet.

## Interoperability with Boox firmware

BooxUltimatum contains no Onyx/BOOX code, SDKs or assets. The Boox interfaces it uses were learned by observing the tablet and by reading how Boox's own apps call the firmware:

- the sleep-picture and system-font broadcasts;
- the SurfaceFlinger handwriting and post transactions behind Instant ink;
- the EinkWise configuration it reads.

They are called through small clients written from scratch in the hub's `core/sleep/` and `core/SystemFont.kt`, and in `kit/ink/`, which both Instant ink and Nib use. The sleep-picture and font broadcasts aren't publicly documented. The pen path is: Onyx documents it for app developers as its pen SDK, [onyx-intl/OnyxAndroidDemo](https://github.com/onyx-intl/OnyxAndroidDemo) (Apache-2.0, `doc/Onyx-Pen-SDK.md`), which Instant ink credits as the starting point. The SDK itself (`onyxsdk-pen`, `onyxsdk-device`) is published as obfuscated binaries without source, so it isn't bundled. `kit/ink/…/SurfaceInk.kt` tries the same firmware helper the SDK uses (`android.onyx.ViewUpdateHelper`) and falls back to SurfaceFlinger's transactions where that helper is blocked. The evidence for each interface is in `knowledge/experiments.md`.

## Studied for Nib, nothing taken

Nib's design was informed by reading these projects. No code, assets or binaries came from them.

| Project | License | What was learned |
|---|---|---|
| [steffest/Boox-EinkDraw](https://github.com/steffest/Boox-EinkDraw) | None (all rights reserved) | How a Note Air4 C drawing app hands the preview over to its own canvas, and its open zoom problem. It bundles Onyx's AARs and a copy of the firmware's `libneo_pen.so`, which is why none of it can be used. |
| [plateaukao/ADR](https://github.com/plateaukao/ADR), CalliPlus notes | None stated | The SurfaceFlinger transaction codes and style numbers on another Boox model, and a flash-free refresh recipe; facts only |
| [hbmartin/onyx-android-sdk](https://github.com/hbmartin/onyx-android-sdk) | LGPL-3.0, but a reconstruction of Onyx's binary SDK | The `TouchHelper` style constants, used as a cross-check only |
| [Ethran/notable](https://github.com/Ethran/notable) | GPL-3.0 | How an open-source Boox note app stores strokes and handles refresh; compatible, but nothing vendored so far |
| [alexdremov/notate](https://github.com/alexdremov/notate) | MIT | Tiles with levels of detail and a spatial index for an infinite canvas; ideas only |

## Data

| Data | Source | License / terms | Where |
|---|---|---|---|
| Initial Onyx package and endpoint observations (NA3C) | [fardjad gist](https://gist.github.com/fardjad/97baf36de97d1c4ae3953b3d359bb918) | Facts, restated in our own words | `knowledge/onyx-packages.json` |
| NA6C battery measurements cited in docs | [eWritable review](https://ewritable.net/brands/boox/tablets/boox-note-air6-c/) | Cited with attribution; not redistributed | `docs/00-device-research.md` |
