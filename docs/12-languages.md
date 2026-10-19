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

## 6. The glossaries

A language that says a thing in two ways is as hard to read as an English string that uses two names for it, so each language keeps a glossary, and the terms are the same in every string.

**Chinese.** The glossary came with the translation, in the note the translator wrote beside it, and the same words are used in Nib's Chinese.

| English | Chinese |
|---|---|
| Instant ink | 即时墨迹 |
| Sleep screen | 睡眠屏 |
| Sleep image | 睡眠图像 |
| Over Transparent | 透明叠加 |
| sticker | 贴纸 |
| face, plate | 面板 |
| home screen | 主屏幕 |
| home app | 主屏幕应用 |
| tweak | 调整项 |
| journal, log | 日志 |
| Undo | 撤销 |
| Restore | 恢复 |
| Remove | 移除 |
| Delete | 删除 |
| Clear | 清除 |
| Tap | 轻触 |
| Open | 打开 |
| Choose | 选择 |
| Touch and hold | 长按 |
| Turn on | 开启 |
| Turn off | 关闭 |
| font | 字体 |
| preview | 预览 |
| stroke | 笔画 |
| layer | 图层 |
| widget | 小部件 |
| folder | 文件夹 |
| library | 书库 |
| appearance | 外观 |
| diagnostics | 诊断 |
| storage | 存储 |
| background | 后台 |
| refresh | 刷新 |
| e-ink | 墨水屏 |
| usage access | 使用情况访问 |
| adb grant | adb 授权 |
| Today | 今天 |
| History | 历史 |
| Causes | 原因 |
| Health | 健康度 |

**Vietnamese.** The glossary is the one below, and it is the vocabulary the translation was written from.

| English | Vietnamese | Note |
|---|---|---|
| the tablet | máy tính bảng | never *thiết bị* for the reader's own tablet |
| the screen | màn hình | |
| sleep screen | màn hình ngủ | the picture the tablet shows while it sleeps |
| power-off screen | màn hình lúc tắt nguồn | |
| home screen | màn hình chính | |
| home app | ứng dụng màn hình chính | the app that draws the home screen |
| font | phông chữ | |
| tweak | tinh chỉnh | a reversible change |
| journal, log | nhật ký | the record of what was changed, and the log files |
| Undo | Hoàn tác | |
| Restore | Khôi phục | |
| Remove | Gỡ bỏ | takes away what the app added |
| Delete | Xóa | destroys the reader's own content |
| Clear | Dọn | empties a list or a log |
| Tap | Chạm | |
| Open | Mở | |
| Choose | Chọn | |
| Touch and hold | Chạm và giữ | |
| Turn on | Bật | |
| Turn off | Tắt | |
| button | nút | |
| Apply | Áp dụng | |
| face | mặt | a sleep screen design |
| plate | thẻ | the small card drawn over the picture |
| sticker | miếng dán | Boox's own see-through picture |
| Instant ink | Mực tức thì | the feature's name, in every language |
| ink | mực | |
| pen | bút | |
| stylus | bút cảm ứng | |
| brush | bút vẽ | |
| nib | đầu bút | the tip |
| eraser | tẩy | |
| preview | bản xem trước | |
| stroke | nét vẽ | |
| layer | lớp | |
| e-ink | mực điện tử | |
| ghosting | bóng ma | |
| refresh | làm mới | |
| wake lock | khóa đánh thức | |
| usage access | quyền truy cập dữ liệu sử dụng | Android's own term |
| overlay | lớp phủ | |
| Doze | Doze | unchanged |
| freeze (an app) | đóng băng | |
| background | chạy nền | |
| battery | pin | |
| widget | tiện ích | |
| tile | ô | a Quick Settings tile |
| suite | bộ ứng dụng | |
| profile | cấu hình | |
| device | thiết bị | the page's own name |
| storage | dung lượng | |
| library | thư viện | |
| folder | thư mục | |
| appearance | giao diện | |
| diagnostics | chẩn đoán | |
| drawing | hình vẽ | |
| notification | thông báo | |
| permission | quyền | |
| adb grant | quyền adb | |
| accessibility | trợ năng | |
| Overview | Tổng quan | a page's name |
| Suite | Bộ ứng dụng | a page's name |
| Battery | Pin | a page's name |
| System | Hệ thống | a page's name |
| Apps | Ứng dụng | a page's name |
| Access | Truy cập | a page's name |
| Tweaks | Tinh chỉnh | a page's name |
| Logs | Nhật ký | a page's name |
| Storage | Dung lượng | a page's name |
| Today | Hôm nay | |
| History | Lịch sử | |
| Causes | Nguyên nhân | |
| Health | Tình trạng | |

## 7. What is not settled

- **The suite in step.** Each app keeps its own language, because Android keeps a per-app language per package. The hub's Suite page can show which language each app is in, and making one follow the other needs a store the two apps share, which is a bigger change than this one.
- **Traditional Chinese.** There is no translation for it. A reader who wants Chinese today is offered Simplified, by name, and a Traditional translation would be a folder of its own.
- **The Vietnamese has not been read by a speaker of the language.** It was written with an assistant and checked against the English side by script, which holds the structure, the values and the plural forms right, but says nothing about how the words read. A reader who writes Vietnamese is welcome to correct it.
