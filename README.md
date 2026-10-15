# ComicReader

A clean-room, ad-free comic / PDF reader for Android, written in Kotlin.

This is an **original implementation** built to match the *feature set* of a
reference comic reader — not a copy of its code. No ads, no analytics, no
in-app billing, and no network access.

## Download

Get the latest signed APK from the [**Releases**](https://github.com/infinity6901/ComicReader/releases/latest) page.

## Features

### File browser
- Browse local storage by folder; list and grid layouts.
- Sort by name / size / date / type (natural number ordering, so `page2` sorts
  before `page10`).
- Incremental search within the current folder.
- Favorites (star) and reading history, with jump-back to the last page read.
- Long-press a file or folder to favorite it or set it as the home folder.
- Breadcrumb path bar, quick favorite button, back button navigates up.

### Reader
- **Formats:** images (`jpg`, `jpeg`, `png`, `gif`, `bmp`, `webp`, `avif`) and
  archives `zip`/`cbz`, `rar`/`cbr`, `7z`/`cb7`, plus `pdf`.
- **Page modes:** paged (horizontal swipe) or vertical webtoon scrolling.
- **Two-page spread:** facing pages side by side (respects RTL ordering).
- **Page thumbnails:** grid picker to jump straight to any page.
- **Crop:** crop the current page to a PNG.
- **Reading direction:** left-to-right or right-to-left.
- **Zoom / pan:** pinch-to-zoom, drag to pan, double-tap to zoom or reset.
- **Fit modes:** fit screen, fit width, fit height.
- Bookmarks per page, plus a bookmark list to jump between them.
- Go-to-page, orientation lock, rotate, keep-screen-on, night filter,
  immersive fullscreen, and tap-to-toggle the UI bars.
- Resumes at the last page read; per-book reading position is stored in history.

### Settings
- Theme: system / light / dark.
- Per-app language: system, English, 한국어, 日本語, Русский.
- Archive filename charset (UTF-8, CP949, Shift_JIS, GBK, Windows-1251).
- Default reading direction and page mode.
- File-list options (status bar, breadcrumb, quick button).
- Clear history, clear bookmarks, reset all settings.

## Format support mapping

| Extension | Handler | Library |
|---|---|---|
| jpg/jpeg/png/gif/bmp/webp/avif | `ImageSource` | `BitmapFactory` |
| zip / cbz | `ZipSource` | `java.util.zip` |
| rar / cbr | `RarSource` | junrar 8.x (RAR4 and RAR5/7) |
| 7z / cb7 | `SevenZipSource` | Apache Commons Compress |
| pdf | `PdfSource` | platform `PdfRenderer` |

## Project layout

```
app/src/main/java/com/infinity/comicreader/
  App.kt                     application; applies theme + locale
  data/                      Prefs, Library (history/bookmarks/favorites), models
  reader/                    PageSource implementations + factory
  ui/                        ListActivity, ViewerActivity, Settings*, AboutActivity,
                             CropActivity + CropView, PagesActivity + ThumbAdapter,
                             ZoomImageView, PageAdapter, BookAdapter
  util/                      Formats, NaturalOrder, FileUtils/Locales
```

## Building

Requirements: JDK 17+ (JDK 21 verified) and the Android SDK (platform 34, build-tools 34).

```bash
./gradlew assembleDebug
./gradlew assembleRelease   # signed + R8-minified
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`, the
release APK to `app/build/outputs/apk/release/app-release.apk`.

On Windows, if JDK 21 is not on PATH:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat assembleRelease
```

### Release signing

`app/build.gradle.kts` reads `keystore.properties` from the project root and, if
present, signs the release build with `keystore/release.jks`. Both files are
listed in `.gitignore` and **must not be committed**. If `keystore.properties` is
absent the release build still compiles, just unsigned.

Release uses `isMinifyEnabled = true` + `isShrinkResources = true` (R8). The
proguard rules keep junrar and commons-compress whole so the reflection paths in
the archive backends survive minification.

### Prebuilt outputs

`dist/` holds the built artifacts:

| Artifact | Size | Notes |
|---|---|---|
| `ComicReader-1.0-foss-release.apk` | 2.38 MB | v2-signed, R8-minified, offline |
| `ComicReader-1.0-debug.apk` | 12.58 MB | debuggable, unsigned-config |

### Install on a connected device

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Note: the debug and release APKs use different signing keys, so installing one
requires uninstalling the other.

## Permissions

- `READ_MEDIA_IMAGES` (Android 13+) / `READ_EXTERNAL_STORAGE` (≤ Android 12)
- `MANAGE_EXTERNAL_STORAGE` — to browse folders anywhere on internal/external
  storage. The app prompts for "All files access" on first launch.

No internet permission is requested.

## Notes

- `minSdk 26`, `targetSdk 34`.
- AVIF decoding depends on the platform: it works natively on Android 12+
  (API 31+); on older devices AVIF pages may fail to decode.
- The reader decodes pages with `inSampleSize` downsampling to bound memory use,
  then lets `ZoomImageView` scale the result.
- The archive/PDF backends (`SevenZFile`, junrar `Archive`, `PdfRenderer`) keep
  per-instance decode state and are **not thread-safe**. Because the page grid
  loads the current page and its neighbours concurrently, all access to these
  backends is serialized on the source instance (`@Synchronized`).
- Page/thumbnail decode failures are logged (`SevenZipSource`, `RarSource`,
  `PdfSource`, `PageAdapter`, `ThumbAdapter`, `ViewerActivity`) instead of being
  silently swallowed.
- The file browser reloads the current folder on resume, so files added or
  removed outside the app appear without a manual refresh.

## License

Released under the [MIT License](LICENSE).
