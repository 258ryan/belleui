# BelleUI

A gamepad-driven Android home-screen launcher for a 3.5" 640x480 (4:3) landscape device.
It blends **Symbian^3** (4x3 icon menu, cyan focus highlight), **Nokia Belle** (glossy
glass tiles, clock/battery widgets, swipeable home pages) and **Asha** (soft-key bar,
simple list views).

Everything is drawn on one Canvas and scales from a 640x480 design grid, so it also
looks right at other 4:3 sizes. No third-party dependencies.

## Pages

| Page | What it is |
|------|------------|
| Home | Clock, date, battery widget, 4 favourite tiles (defaults: phone, messages, browser, camera) |
| Menu | 4x3 Symbian-style grid of all apps, scrolls vertically |
| Recent | Asha-style list of recently opened apps |

## Controls

| Button | Action |
|--------|--------|
| D-pad / left stick | Move focus (past the grid edge switches page) |
| A | Open / select |
| B | Back, one step up to Home (on Home it opens Applications); closes popups; left toolbar icon |
| X | Options for the focused app (open, move, add/remove from home, hide, app info, uninstall) |
| Y / Select | Launcher settings (gear in the toolbar) (theme, button layout, show hidden apps, Android settings) |
| L1 / R1 | Previous / next page |
| Start | Toggle Home / Menu |

The soft-key bar at the bottom always shows what the three buttons do right now.
Settings has a **Nintendo layout** switch that swaps A/B and X/Y.
Touch also works: tap, long-press for options, swipe to change page.

## Build

Needs JDK 17 and the Android SDK (platform 34).

```bash
gradle assembleDebug        # or ./gradlew assembleDebug if you generated the wrapper
```

The APK lands in `app/build/outputs/apk/debug/`. The GitHub Actions workflow
(`.github/workflows/build.yml`) builds it on every push and uploads it as an artifact.

Install it, press Home, and choose BelleUI as the default launcher.

## Look

Follows the Symbian Belle design guidelines (see "Symbian Design Guidelines show Belle UI future", All About Symbian, 2011):

- **Status bar:** slim, one line, solid black: wifi, battery, time
- **Toolbar:** one bar replacing soft keys. Back arrow (mandatory; the apps button on Home), two action buttons (select, settings), three-line menu button on the right that opens the pop-up options menu. Each shows its gamepad letter.
- **Tab bar:** Applications / Recent are parallel views under the status bar, stretched full width, selected tab darker. Back leaves both in one step.
- **Lists:** light list view with a list header band, two-line items, separators, a scrollbar, dark selection band
- **Dialogs:** centred, dark title band with a close cross, light body. The options menu rises from the menu button.
- **Info banners:** messages are auto-dismissing banners across the top
- **Home:** glossy flip-clock tiles and shortcuts straight on a vignetted wallpaper; vivid glossy squircle icons with soft shadows; haptic tick on select

### Icons

Icon lookup order for each app:

1. `Android/data/com.belleui.launcher/files/icons/<package.name>.png` on the device (your own overrides)
2. Bundled N9ish pack, per package: `app/src/main/assets/icons/apk/` (48 apps, e.g. Firefox, WhatsApp, Maps, Play Store)
3. Bundled N9ish pack, per app type: `assets/icons/generic/` (phone, messages, browser, camera, settings, clock, gallery, ...), picked by keywords in the package name and label
4. Fallback: the app's own icon on a baked glass plate

Pack art is drawn as-is with a soft shadow. To add more icons, drop a PNG into `assets/icons/apk/`
named after the package with `_` written as `__` and `.` written as `_`
(`org.mozilla.firefox` -> `org_mozilla_firefox.png`).

The artwork comes from the "N9ish" theme pack by speactra (openrepos.net). Check its licence
before making this repository public or redistributing the APK. `share.zip` in the repo root
is the original download and can be deleted.
