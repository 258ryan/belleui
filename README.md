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

Follows the real Symbian Belle (Qt Quick Components 1.1, nHD profile) measurements and colours, taken from
[likidu/symbian-belle-ui](https://github.com/likidu/symbian-belle-ui) (`DESIGN.md`, `belle.tokens.json`, reference screenshots),
plus the All About Symbian write-up of the Belle design guidelines. One design px = one device px; the 640x480
screen is ~228 ppi, close to Belle's 210 ppi.

- **Status bar** 26px, black: page title on the left; wifi, battery and clock on the right
- **Toolbar** 60px (`heightToolBar`), 30px white glyphs spread evenly edge to edge. First button is Back (the apps button on Home), last is the three-line options menu; select and settings sit between. The gamepad letter is shown beside each.
- **Tab bar** 50px under the status bar: Applications / Recent are real tabs that switch in place (no sliding). Press Up from the top row to focus the tab bar, then Left/Right to switch; L1/R1 also switch tabs.
- **Applications**: black page, 70px icon frames, 18px labels, blue highlight
- **Recent**: 70px `ListItem`s, 12px inset, 50px icon, 22px title, 18px light subtitle, `#444` 1px separators, blue pressed frame
- **Options menu** slides up from the bottom over the toolbar: 64px items, 22px text, full-width Cancel button
- **Dialogs** (Settings) are `SelectionDialog` style: `#1E1E1E` rounded panel, Belle-blue title with a close icon, 64px items, max 504px wide
- **Info banner**: 60px, slides in under the status bar and dismisses itself
- Nothing dims the screen behind an overlay. Belle blue is `#1080DD`; text white / `#999999`.
- **Wallpaper:** the launcher window is transparent, so your actual system wallpaper shows on Home (set it from the system wallpaper picker); Applications and Recent are black like real Belle apps.
- **Home** is the wallpaper page: a clock widget and a battery widget on the Belle widget plate, then shortcuts.
- **Icons:** pack art is drawn exactly as supplied, with a drop shadow traced from the icon's own outline (no overlay). Apps without pack art keep their own colours: adaptive icons are clipped to the Belle superellipse (exponent 2.8), legacy icons are left untouched, and an app whose icon cannot be rendered gets a blue tile with its initial.

Frames and stock glyphs (status bar, toolbar, tab bar, list pressed, popups, buttons, info banner, back/menu/settings/close, battery) are the real Belle theme graphics rendered to PNG in `assets/belle/` and drawn as 9-slice images. Home widgets use the Belle widget plate from the Symbian HomeScreen widget template. The Nokia Pure typeface is proprietary and not bundled; text uses the system sans-serif at the same sizes. See `docs/THIRD-PARTY.md` for licences.

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

## Belle kit (`:belle`) and apps

`belle/` is a shared Android library with the Belle look: the theme graphics (`assets/belle`), `Ui` (glyphs, lists, tab bar, toolbar, status bar, IconFrame, progress bar, switch), and a small app shell (`BelleActivity`, `BelleShell`, `Screen`) with gamepad key mapping, options menu, selection / query / tumbler dialogs and info banners. The launcher uses its graphics; the apps on the `belle/music`, `belle/clock`, `belle/gallery` and `belle/browser` branches are built on it.

### Music (`belle/music`)

Belle-style music player on the shared kit. Tabs Songs / Albums / Artists over MediaStore audio, Now playing with album art, progress bar, shuffle/repeat, and background playback with a media notification and MediaSession (headset / Bluetooth buttons).
Settings (X menu) can hide songs shorter than a chosen length, from 0:01 to 2:00.
Gamepad: D-pad up/down browse, A play, L1/R1 switch tabs (previous/next song on Now playing), left/right seek 10 s, up/down volume, Y play/pause, X options menu.
### Clock (`belle/clock`)

Tabs Clock / Alarms / Stopwatch / Timer. Analog + digital clock with world clocks; alarms with Belle tumblers and switches that really fire (AlarmManager alarm-clock API, foreground ringing service, lock-screen ring screen with Snooze 10 min / Stop, re-armed after reboot); stopwatch with laps; countdown timer that rings when done.
Gamepad: A toggles an alarm / starts-stops, Y adds an alarm (Alarms) or starts / pauses, Start laps / resets, X opens the options menu (edit time, repeat, delete), L1/R1 switch tabs.
### Gallery (`belle/gallery`)

Tabs Photos / Albums over MediaStore images and videos. 5-column square thumbnail grid (loaded in the background), album list, and a full-screen viewer with zoom + pan (EXIF-aware), slideshow, share and details.
Gamepad: D-pad moves, A opens / zooms / plays a video, L1/R1 previous / next photo, Y share, Start zoom, X options menu (details, share, slideshow).
### Browser (`belle/browser`)

A Belle-styled web browser on Android **WebView**, which is the Chromium engine. It is not a Chromium fork: building Chromium needs a 100 GB+ checkout and hours of compile time, far beyond this repo's CI.
The Belle chrome (status bar, toolbar, options menu, dialogs, info banners) is drawn over the WebView and passes touches and keys through. Gamepad: D-pad / left stick moves an on-screen **pointer**, A clicks, pushing against the top or bottom edge scrolls the page, hold a direction to speed up; L1/R1 are back / forward; Y opens the address field (system keyboard), Start reloads or stops; X opens the menu (bookmarks, desktop site, pointer on/off, share, clear data). It also registers for http/https links so it can be chosen as the default browser.
Gamepad: D-pad moves, A opens / zooms / plays a video, L1/R1 previous / next photo, Y share, Start slideshow, A zoom, X options menu (details, share, slideshow).
