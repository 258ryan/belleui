#!/usr/bin/env python3
"""
Belle re-theme of the stock (Lineage 18.1) System UI, applied to an apktool-decoded copy.

Colours only: panels, notification rows, quick-settings, power menu and the accent move to the Belle dark palette.
Layouts and code are untouched, so the lock screen, notifications, recents, screenshots and the device's hint bar
all keep working exactly as in the stock System UI.

usage: restyle.py <decoded-dir>
"""
import re
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
}


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
    if total == 0:
        sys.exit("nothing was changed: is this a decoded System UI?")
