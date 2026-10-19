# 12 · Languages: how the suite is put into words, and how a reader chooses them

**Status: built on 2026-10-19.** BooxUltimatum and Nib follow the tablet's language until the reader chooses another one, each app declares the languages it has in its own `res/xml/locales_config.xml`, and `tools/dev/check_translations.py` keeps a translation honest. The Simplified Chinese translation came from the reader who raised issue #6; the Vietnamese one is the maintainer's own. This document is the convention a translator works to, and the record of why the language is chosen the way it is.

## 1. In plain words

A translation is only worth making if the reader can reach it. Boox tablets are sold with Chinese firmware all over the world, so a reader whose own language is Vietnamese, or English, or anything else, very often has a tablet whose language is something else. A translation that can only be reached by changing the whole tablet's language therefore reaches almost nobody.

So the app's language is a setting of its own, and it is per app: BooxUltimatum and Nib are separate programs, and each keeps its own choice. The default is the tablet's language, so nothing has to be chosen for the common case.

Three things make the choice, in the order the reader meets them:

- **The tablet's language, by default.** A Chinese tablet reads Chinese with no work at all. This is the default and it stays the default.
- **The system's own App languages screen, on Android 13 and later.** Android reads `locales_config.xml` and offers the app's languages under the system's settings. The choice is stored by the platform, per app, and it survives an update of the app.
- **A Language row in the app.** On the Device page in BooxUltimatum, and on the About page in Nib. On Android 13 and later this writes to the same place the system's screen writes to, so the two can never disagree; on Android 11 and 12 the app keeps the choice itself and starts the screen again in the new language.

## 2. The convention

- **One folder per language, with the same file names.** `hub/src/main/res/values-zh-rCN/strings_sleep.xml` sits beside `hub/src/main/res/values/strings_sleep.xml`, and the two hold the same keys in the same order. The kit's own strings follow: `kit/ui/src/main/res/values-zh-rCN/`, `kit/update/src/main/res/values-zh-rCN/`.
- **Only specific tags.** A language is `values-vi`, `values-zh-rCN`, `values-zh-rTW`. A generic `values-zh` is not used: it would give Simplified text to a reader who asked for Traditional without either of them knowing, and it would double the folders to keep in step.
- **A language is complete or absent.** Half a translation is worse than none, because half of the suite then reads in one language and half in another. A folder is either every string or it is not there.
- **What stays in English.** Brand and product names (`BooxUltimatum`, `Boox`, `Onyx`, `Nib`, `Shizuku`, `Android`, `Boox Notes`, `NaviBall`, `Archivo`), units and protocol words (`mAh`, `mA`, `px`, `dp`, `sp`, `adb`, `API`, `Doze`, `Wi-Fi`, `APK`), the paths and identifiers the reader may type (`Android/data/app.booxultimatum/files/logs`, `tools\host\start-shizuku.ps1`), the log tags the code matches on (`BU/ink`), and the strings that exist only to show which glyphs a font covers (`fonts_charset_vi`, `fonts_sample_vi`).
- **The values in a string stay as they are.** `%1$s` stays `%1$s`, and the order may change but the index may not. A string marked `formatted="false"` has no values in it and shows its text as it is, so a percent sign in it is a single `%`.
- **A string marked `translatable="false"` is not translated.** Those are the names the code matches on.
- **Each language keeps its own glossary.** The terms in `AGENTS.md` (*Words*) are the English ones. A language that says a thing in two ways is as hard to read as an English string that uses two names for it, so the glossary travels with the translation.

## 3. The check

`tools/dev/check_translations.py` runs on every push, through the unit tests (`test_check_translations.py`). It reads every `values-*` folder against the English one and reports:

- a string the English side has and the translation has not, and one the translation has and the English side has not;
- a value that changed: a different index, a different type, a value left out, a value added;
- a percent sign doubled in a string that is not formatted, which would show as `%%` on the screen;
- a plural form the language does not use. Chinese and Vietnamese have one form, `other`, so a translation that keeps the English `one` item carries a form Android will never ask for. Where the two texts differed, the `other` one is the one kept, because it is the one that reads correctly for any number;
- `formatted` and `translatable` that do not match the English side;
- an empty translation;
- a language an app offers that it has no translation for, and a translation that no app offers.

`python tools/dev/check_translations.py --report` prints what each language covers.

## 4. The declarations

Three things have to agree about which languages exist, and the check holds them together:

- **`SuiteLanguage`** (`kit/core/.../Language.kt`) names each language three times over: the resource folder (`zh-rCN`), the BCP 47 name Android uses (`zh-CN`), and the language in its own words (简体中文), so a reader finds it whichever language the app is in.
- **`res/xml/locales_config.xml`** in each app says the same thing to Android. The app reads this file for its own picker, so the app's picker and the system's App languages screen cannot offer different things.
- **The folders** say what actually exists.

The rule that ties them together: an app offers a language when it has the words for it, and the suite is complete when every app has them.

## 5. The font

The bundled Archivo is a Latin face: it has every Vietnamese letter, Đ included, so Vietnamese needs nothing. It has no Chinese character at all, so Chinese text falls back to the tablet's own system font, and the Chinese words are then drawn in that face. On a tablet whose system font has no Chinese, the Chinese text would be empty boxes; the Fonts page can put a face with Chinese coverage in its place.

The fallback is Compose's own: a font family with no glyph for a character falls back to the system font. The face a language is drawn in is therefore the tablet's, not Archivo's, unless the reader has chosen a font in the app.

## 6. What is not settled

- **The suite in step.** Each app keeps its own language, because Android keeps a per-app language per package. The hub's Suite page can show which language each app is in, and making one follow the other needs a store the two apps share, which is a bigger change than this one.
- **Traditional Chinese.** There is no translation for it. A reader who wants Chinese today is offered Simplified, by name, and a Traditional translation would be a folder of its own.
- **Nib.** Nib's own 533 strings are not translated yet, so Nib offers only the tablet's language. The Chinese translation of Nib has been asked for in issue #6, and the Vietnamese one is the maintainer's own.
