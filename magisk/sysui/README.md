# BelleUI System UI (experimental)

Replaces Android's SystemUI (`/system/system_ext/priv-app/SystemUI`) with a Belle one. Built from `sysui/`, signed with the AOSP
public platform test key (your ROM's framework carries the same certificate).

What it does: the Belle status bar window (wifi, notification count, clock, battery), the Belle Status menu (pull-down), volume
pop-up, power menu, the recents key (opens the launcher's Recent tab) and the still-image wallpaper.

What it does not do: **no lock screen**, no navigation bar, no screenshot or screen-recording service, no ADB-authorisation or USB
permission dialogs (so pair new computers *before* installing), no heads-up notifications, no stock Quick Settings tiles.

Safety: `post-fs-data.sh` counts boots the new System UI has not confirmed; at 3 it writes `skip_mount` and `disable`. To undo by
hand: `touch /data/adb/modules/belleui_sysui/disable` and reboot (adb, Magisk safe mode or recovery).
