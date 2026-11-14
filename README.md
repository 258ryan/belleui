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
| Hub | To the left of Home: every notification that has arrived (kept after you dismiss it), grouped Today / Yesterday, as cards over your wallpaper |
| Home | Two widgets (clock, date, battery, storage or notifications - you choose) and 4 favourite tiles (defaults: phone, messages, browser, camera) |
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

Hiding apps: X on any app > **Hide from launcher** (works on Home, Applications and Recent). Restore from Y > **Hidden apps**, or turn on **Show hidden apps**.

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
The Belle chrome (status bar, toolbar, options menu, dialogs, info banners) is drawn over the WebView and passes touches and keys through.
**D-pad browsing:** the D-pad (or left stick) jumps a blue focus ring between links, buttons and fields in the direction you press, scrolling them into view; A clicks (text fields open the keyboard); when nothing lies that way the page scrolls. The right stick scrolls freely. X menu > "Images: off" loads pages much faster on slow connections. Default home page and search use DuckDuckGo Lite (light on this hardware). X menu > "D-pad" switches between links, a movable pointer (hold a direction to speed up) and plain page scrolling. L1/R1 are back / forward; Y opens the address field, Start reloads or stops; the menu has bookmarks, desktop site, share and clear data. It also registers for http/https links so it can be chosen as the default browser.
### Games (`belle/games`)

An Ovi Store-style games hub: a Featured tab with a hero banner and shelves (recently played, favourites, recently added), a Games grid with box art, Systems, and an Emulators tab.
- **RetroArch:** games start through RetroArch's documented Android launch intent (ROM, libretro core, config and data folders), with a per-system core choice. While a game runs, the Emulators tab talks to RetroArch over its UDP network-command port: save state, load state, pause, reset, quit and status. Switch on Settings > Network > Network Commands in RetroArch.
- **PPSSPP:** PSP games open straight in the standalone PPSSPP app (or in RetroArch's ppsspp core, your choice).
- Games are found in the folders you choose (Settings > ROM folders; Android 11+ needs "All files access"). Box art comes from the libretro thumbnail server and is cached; play counts and play time are tracked.
- This branch has its own workflow (`games.yml`) that builds only the Games app, and its own release (`games-latest`).

**EmulationStation-style cards (EasyRoms and similar):** point the app at a card and it reads it the way EmulationStation does. A `roms` folder with one subfolder per system (`gba`, `SNES`, `psx`, `megadrive`, `mastersystem`, `neogeo`, `dreamcast`... any capitalisation) is detected on its own (Settings > Find ROM cards); only the file types each system uses are listed; `bios`, `media`, `images` and save folders are skipped; `.m3u` playlists stand in for their discs; `gamelist.xml` supplies names, descriptions, developer, genre, year, players and rating; local art from `images/`, `media/box2dfront|boxart|screenshots`, `downloaded_images` and the like is used before anything is downloaded; and an `es_systems.xml` on the card teaches it extra system names and file types. 40 systems are built in.
### Files (`belle/files`)

A Symbian-style file manager. A home page of drives (phone memory, memory card, USB) with free-space bars and quick places (Downloads, Pictures, Camera, Music, Videos, Documents), then drill-down folders with the original Symbian folder and file icons. Mark items, copy, cut, paste, delete, rename, new folder, details, sort and hidden files; opens files with the matching app.
Gamepad: D-pad up/down browse, right or A opens, left or B goes up, Y marks and moves on, Start pastes (or makes a new folder), X opens the options menu.
Own workflow `files.yml` builds only this app and publishes `files-latest`.
### System settings (`belle/settings`)

A settings app that covers the everyday things so you rarely need Android's own: **Storage** (drive usage bars, media breakdown), **Apps** (installed / system tabs, detail page with open, app info and uninstall), **Connectivity** (live Wi-Fi, Bluetooth, airplane, mobile, NFC and IP state, opening the system quick panels to toggle), **Battery** (level, status, time to full, current, health, temperature, voltage, battery saver), **Display** (brightness, auto-brightness, screen timeout, auto-rotate, wallpaper) and **Sound** (media, ringtone, alarm and notification volumes, ringer mode), **About** (device, Android, kernel, processor, memory, screen, uptime), plus a link to Android settings for the rest.
Left / right adjust a bar; A opens. Brightness and timeout need the one-time "modify system settings" permission, which the app asks for.
Own workflow `settings.yml` builds only this app and publishes `settings-latest`.

## CI

On `belle/all` every app has its own workflow (`launcher.yml`, `music.yml`, `clock.yml`, `gallery.yml`, `browser.yml`, `games.yml`, `files.yml`, `settings.yml`) that runs only when that app's folder, the shared kit (`belle/`) or the build files change, and publishes its own `<app>-latest` release. Changing the launcher rebuilds only the launcher. `all.yml` is manual: run it from the Actions tab to build everything into one zip (`apks-latest`).

## Hub and home widgets

- **Hub:** press left past the first Home tile (or L1) to reach it. It lists the notifications your apps post, newest first, with app icon, title, text and time, and keeps them after you clear them from the shade. A opens the notification (or its app); X offers Remove, Clear all and Notification access. The first time, press A on the Hub to open Android's notification-access screen and switch BelleUI Hub on - Android only lets an app read notifications with that permission.
- **Home widgets:** X on any Home tile > **Edit widgets** (or X on an empty tile) chooses what the left and right slots show: Clock, Date, Battery, Storage or Notifications. Tapping a widget opens its app (the Notifications widget jumps to the Hub). These are BelleUI's own widgets; hosting other apps' Android widgets is not built.

### Styles (`belle/styles`)
Wallpapers and sounds. **Wallpaper** has built-in Belle-style backgrounds and your own photos; D-pad pans, L1/R1 zoom, A sets it for the Home screen, the lock screen or both. **Ringtone / Notification tone / Alarm tone** list the system tones and your music (A previews and sets). Touch sounds, vibrate-on-touch and the screen-lock sound are switches. Setting tones needs the "Modify system settings" switch for Styles.

### Keyboard (`belle/keyboard`)
A two-row on-screen keyboard (an Android input method) built for game pads. Turn it on in the Keyboard app (Turn on, then Use it). While it is up: D-pad moves along the keys, **A** types, **B** deletes, **X** space, **Y** shift (twice for caps lock), **L1/R1** move the text cursor, **R2** or Start is enter, **L2** swaps letters and symbols, Select hides it. Letters are A to Z or QWERTY; number fields open on the number page. **Word suggestions** appear above the keys as you type: **R2** takes the first one, or press **Up** from the top row, pick with left/right and press A. It learns the words you type (Keyboard app > Forget typed words clears them) and stays off in password, email and web-address fields.

### Games, redesigned
List-first like a Belle app: **Home** (a "Continue playing" banner, favourites and recently added shelves), **Games** (box-art list; L1/R1 jump by letter), **Systems**, **Emulators**. **A starts a game straight away**; X opens the menu for the highlighted game (favourite, details, another version or disc, core), Y favourites it. Versions and discs of one title are a single entry.

### Music folders
Music Settings > Music folders limits the library to the folders you pick (such as the Music folder on an SD card). Choosing a folder also asks Android to index its files.

### Games for big libraries
With thousands of games across many systems, start from **Library**: All games, Favourites, Played, then one row per system. Inside any list you can **search as you type** (Y, or the search button), show only favourites / played / never played, pick a **genre**, change the **order** (A to Z, recently added, most played, last played) and jump by letter with L1 / R1. The Store tab stays the front page; its search button searches everything.

### Video, Notes, Calculator, Voice recorder
**Video** lists your videos with thumbnails (All, Folders, Continue) and plays them full screen; it remembers where you stopped (D-pad left/right seek, up/down volume, L1/R1 previous/next, X picture fit, Y speed). **Notes** keeps plain-text notes, one file each. **Calculator** has a gamepad keypad, brackets, percent and history. **Voice recorder** records from a wired, USB or Bluetooth headset (the device has no microphone of its own), with a level meter, and keeps recordings in Recordings.

### Theme colour and sounds
Styles > Theme colour recolours the launcher and every Belle app (selection, progress bars, buttons, switches). Styles > Navigation sounds turns the soft Symbian-style clicks on or off for all apps. Other apps pick the change up when you open them.

### Home widgets
Home can show one large Belle widget instead of two small ones: **Flip clock**, **Today** (calendar), **Music player** (controls whichever app is playing; needs notification access), **Notifications** and **Quick switches**. Choose it with X > Edit widgets. Press Up from the shortcut row to focus a widget; left/right pick a button.

### Belle picker (Magisk module)
`magisk/picker` + `picker/`: a Belle-style, gamepad-friendly replacement for Android's folder and file picker, packed as a Magisk module (release `picker-latest`). It installs the picker as a system app, so every app that asks for a folder (open document tree), a file (open document / get content) or a place to save a file gets it. It hands the app access to just what you chose, through its own documents provider. Needs Magisk 20.4+ and Android 11+; reboot after flashing. Untested on hardware: if anything misbehaves, disable or remove the module in Magisk (safe mode also turns modules off).

### Belle Chooser (Vector module)
`vector/`: a Vector (Xposed) module that restyles Android's "Open with" and Share lists in the Belle look (dark panel, blue highlight, gamepad focus). It only changes how the list looks; which apps appear and what happens when one is chosen stay Android's own. Install the APK, turn it on in Vector (Modules), tick "System Framework" (and "Android System" if listed) as scope, reboot. Check the Vector log for "BelleChooser" to see if the hook ran. It also catches the recent-apps key in the system process and opens the Belle launcher's Recent tab instead of the stock screen (the stock screen still opens if the launcher isn't installed). Built against the classic Xposed API, which Vector still runs.
