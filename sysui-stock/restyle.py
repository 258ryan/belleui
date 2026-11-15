#!/usr/bin/env python3
"""
Belle re-theme of the stock (Lineage 18.1) System UI, applied to an apktool-decoded copy.

Colours only: panels, notification rows, quick-settings, power menu and the accent move to the Belle dark palette.
Layouts and code are untouched, so the lock screen, notifications, recents, screenshots and the device's hint bar
all keep working exactly as in the stock System UI.

usage: restyle.py <decoded-dir>
"""
import os
import re
import shutil
import sys

BELLE = {
    # surfaces
    "notification_shade_background_color": "#ff0e0e0e",
    "notification_panel_solid_background": "#ff000000",
    "notification_material_background_color": "#ff1e1e1e",
    "notification_material_background_dark_color": "#ff1e1e1e",
    "notification_material_background_dimmed_color": "#ff141414",
    "notification_material_background_media_default_color": "#ff262626",
    "notification_legacy_background_color": "#ff1e1e1e",
    "qs_customize_background": "#ff121212",
    "qs_customize_decoration": "#ff2a2a2a",
    "global_actions_grid_background": "#ff1b1b1b",
    "global_actions_separated_background": "#ff161616",
    "global_actions_console_item_highlight": "#ff1080dd",
    # text
    "notification_primary_text_color": "#ffffffff",
    "notification_section_header_label_color": "#ffffffff",
    "notification_section_clear_all_btn_color": "#ff5cb4f8",
    "global_actions_text": "#ffffffff",
    "qs_tile_disabled_color": "#ff8a8a8a",
    # lines
    "notification_divider_color": "#ff3a3a3a",
    "qs_tile_divider": "#29ffffff",
    # accent (the Belle blue)
    "GM2_blue_300": "#ff5cb4f8",
    "GM2_blue_500": "#ff1080dd",
    "GM2_blue_600": "#ff1080dd",
    "accent_material_dark": "#ff1080dd",
    "accent_material_light": "#ff1080dd",
}


# sizes: a slimmer status bar text, bigger rounder switch-like quick settings tiles
DIMENS = {
    "status_bar_clock_size": "17.0sp",
    "qs_tile_text_size": "14.0sp",
    "qs_tile_background_size": "56.0dip",
    "qs_quick_tile_size": "56.0dip",
    "qs_tile_height": "108.0dip",
    "qs_tile_icon_size": "28.0dip",
}


DIMENS.update({
    "notification_section_header_height": "40.0dip",
})

# four switches across, like the Belle Status menu
INTEGERS = {
    "quick_settings_num_columns": "4",
    "quick_qs_panel_max_columns": "4",
    "quick_settings_min_num_tiles": "4",
}
# the ROM sets its own counts for landscape, wide and portrait screens; they all become four
INTEGER_FILES = ["values", "values-land", "values-port", "values-w550dp-land", "values-sw600dp-land", "values-sw600dp", "values-sw600dp-port"]


def patch_integers(path):
    try:
        s = open(path, encoding="utf-8").read()
    except FileNotFoundError:
        return 0
    n = 0
    for name, val in INTEGERS.items():
        s, k = re.subn(r'(<integer name="%s">)[^<]*(</integer>)' % re.escape(name), r"\g<1>%s\g<2>" % val, s)
        n += k
    open(path, "w", encoding="utf-8").write(s)
    return n


def patch_theme(d):
    """The System UI's own theme carries the Belle blue as its accent: lit quick-settings tiles, switches, sliders."""
    sp = os.path.join(d, "res/values/styles.xml")
    s = open(sp, encoding="utf-8").read()
    m = re.search(r'(<style name="Theme.SystemUI" [^>]*>)(.*?)(</style>)', s, re.S)
    if not m or "belle_accent" in m.group(2):
        return 0
    extra = (
        '\n        <item name="android:colorPrimary">#ff1080dd</item>'
        '\n        <item name="android:colorAccent">#ff1080dd</item>'
        '\n        <item name="android:colorControlActivated">#ff1080dd</item>'
        '\n        <!-- belle_accent -->'
    )
    s = s[: m.end(2)] + extra + s[m.end(2):]
    open(sp, "w", encoding="utf-8").write(s)
    return 1


def patch_notification_shade(d):
    """Grey Belle section bars and white 16 sp header buttons in the notification shade."""
    n = 0
    lp = os.path.join(d, "res/layout/status_bar_notification_section_header.xml")
    s = open(lp, encoding="utf-8").read()
    tag = "<com.android.systemui.statusbar.notification.stack.SectionHeaderView "
    if tag in s and "belle_section_bar" not in s:
        s = s.replace(tag, tag + 'android:background="@drawable/belle_section_bar" ', 1)
        open(lp, "w", encoding="utf-8").write(s)
        n += 1
    sp = os.path.join(d, "res/values/styles.xml")
    s = open(sp, encoding="utf-8").read()
    m = re.search(r'(<style name="TextAppearance.NotificationSectionHeaderButton"[^>]*>)(.*?)(</style>)', s, re.S)
    if m:
        body = m.group(2).replace("?wallpaperTextColor", "#ffffffff").replace('name="android:textSize">14.0sp', 'name="android:textSize">16.0sp')
        s = s[: m.start(2)] + body + s[m.end(2):]
        open(sp, "w", encoding="utf-8").write(s)
        n += 1
    return n


def patch_dimens(path):
    try:
        s = open(path, encoding="utf-8").read()
    except FileNotFoundError:
        return 0
    n = 0
    for name, val in DIMENS.items():
        s, k = re.subn(r'(<dimen name="%s">)[^<]*(</dimen>)' % re.escape(name), r"\g<1>%s\g<2>" % val, s)
        n += k
    open(path, "w", encoding="utf-8").write(s)
    return n


# the Belle squircle for the quick-settings tiles, in the 100x100 box the tile shape uses (the ROM's icon mask is a circle)
SQUIRCLE = "M0,50 C0,8 8,0 50,0 C92,0 100,8 100,50 C100,92 92,100 50,100 C8,100 0,92 0,50 Z"


def patch_tiles(d):
    """Quick-settings tiles take their shape from the system icon mask; use the Belle squircle instead."""
    path = os.path.join(d, "smali/com/android/systemui/qs/tileimpl/QSTileBaseView.smali")
    s = open(path, encoding="utf-8").read()
    # the first PathParser call after the icon-mask string (0x1040225 = config_icon_mask) is the tile shape
    i = s.find("0x1040225")
    j = s.find("invoke-static {v1}, Landroid/util/PathParser;->createPathFromPathData", i) if i >= 0 else -1
    if j < 0:
        print("tile shape: pattern not found, left as it is")
        return 0
    s = s[:j] + 'const-string v1, "%s"\n\n    ' % SQUIRCLE + s[j:]
    open(path, "w", encoding="utf-8").write(s)
    return 1


def copy_overlay(src, dst):
    """Whole-file replacements (flat Belle panels and rows) from overlay/res/ over the decoded res/."""
    n = 0
    for root, _, files in os.walk(src):
        for f in files:
            rel = os.path.relpath(os.path.join(root, f), src)
            target = os.path.join(dst, rel)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            shutil.copyfile(os.path.join(root, f), target)
            n += 1
    return n


def patch(path):
    try:
        s = open(path, encoding="utf-8").read()
    except FileNotFoundError:
        return 0
    n = 0
    for name, val in BELLE.items():
        s, k = re.subn(r'(<color name="%s">)[^<]*(</color>)' % re.escape(name), r"\g<1>%s\g<2>" % val, s)
        n += k
    open(path, "w", encoding="utf-8").write(s)
    return n


if __name__ == "__main__":
    d = sys.argv[1]
    total = 0
    # the day palette and the night palette both become the Belle dark one
    for f in ("values/colors.xml", "values-night/colors.xml"):
        k = patch("%s/res/%s" % (d, f))
        print(f, k, "colours changed")
        total += k
    k = patch_dimens("%s/res/values/dimens.xml" % d)
    print("dimens", k, "sizes changed")
    total += k
    k = 0
    for q in INTEGER_FILES:
        k += patch_integers("%s/res/%s/integers.xml" % (d, q))
    print("integers", k, "changed")
    total += k
    k = patch_theme(d)
    print("theme accent", k, "edits")
    total += k
    k = patch_notification_shade(d)
    print("notification shade", k, "edits")
    total += k
    k = patch_tiles(d)
    print("tiles", k, "shape patched")
    here = os.path.dirname(os.path.abspath(__file__))
    k = copy_overlay(os.path.join(here, "overlay", "res"), "%s/res" % d)
    print("overlay", k, "files copied")
    total += k
    if total == 0:
        sys.exit("nothing was changed: is this a decoded System UI?")
