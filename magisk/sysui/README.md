# BelleUI System UI (experimental)

Replaces Android's SystemUI (`/system/system_ext/priv-app/SystemUI`) with a Belle one. Built from `sysui/`, signed with the AOSP
public platform test key (your ROM's framework carries the same certificate).

What it does: the Belle status bar window (title of the app on screen; clock, battery with a charging bolt, Wi-Fi, Bluetooth,
silent, alarm, airplane and the notification count), the Belle Status menu (pull-down) with its own notification listener (no
"allow access" step), incoming-notification banners, volume pop-up, power menu, the recents key and Fn+B (a Belle list of the
real open apps), low-battery warnings (15 % and 5 %), screenshots (power + volume and the hotkeys), the "Allow USB debugging?"
question for new computers, and the still-image wallpaper.

What it does not do: **no lock screen**, no navigation bar, no screen recording, no USB-permission dialogs for apps, no stock Quick
Settings tiles (the Status menu has Data/Airplane, Wi-Fi, Bluetooth, Silence and Settings, brightness and the profile).

Safety: `post-fs-data.sh` counts boots the new System UI has not confirmed; at 3 it writes `skip_mount` and `disable`. To undo by
hand: `touch /data/adb/modules/belleui_sysui/disable` and reboot (adb, Magisk safe mode or recovery).
