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
| Archivo (variable TTF) | [Omnibus-Type/Archivo](https://github.com/Omnibus-Type/Archivo) via Google Fonts | SIL OFL-1.1 | `app/src/main/res/font/archivo.ttf`, license text in `app/src/main/assets/licenses/archivo-OFL.txt` |

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

They are called through small clients written from scratch in `core/sleep/`, `core/SystemFont.kt` and `core/ink/`. Onyx's pen SDK (`onyxsdk-pen`) is published as an obfuscated binary and deliberately isn't bundled. The evidence for each interface is in `knowledge/experiments.md`.

## Data

| Data | Source | License / terms | Where |
|---|---|---|---|
| Initial Onyx package and endpoint observations (NA3C) | [fardjad gist](https://gist.github.com/fardjad/97baf36de97d1c4ae3953b3d359bb918) | Facts, restated in our own words | `knowledge/onyx-packages.json` |
| NA6C battery measurements cited in docs | [eWritable review](https://ewritable.net/brands/boox/tablets/boox-note-air6-c/) | Cited with attribution; not redistributed | `docs/00-device-research.md` |
