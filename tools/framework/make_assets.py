#!/usr/bin/env python3
"""Render the Symbian Belle theme SVGs (Qt Components graphics from symbianplugin_1_1_2.dll) into the framework overlay.

usage: make_assets.py <svg-dir> <out-dir>      needs rsvg-convert and Pillow

The graphics are drawn about 1.375 times larger on the E6 profile than their SVG size (button 42 -> 58, switch 44 -> 60,
progress 20 -> 28, big spinner 70 -> 96: Qt Components' privateStyle values), so every SVG is rendered at that zoom, to be
authored at the E6's ~326 ppi, so they go in drawable-xhdpi and Android scales them to the screen's density. Frame graphics become nine-patch PNGs with the Qt border sizes (20 px; 10 px for the banner).
"""
import subprocess, sys, os
from PIL import Image

ZOOM = 1.375
svg_dir, out_dir = sys.argv[1:3]
os.makedirs(out_dir, exist_ok=True)

# name: (svg, (left, top, right, bottom) stretch borders in rendered px)
NINE = {
    "belle_btn_normal": ("qtg_fr_pushbutton_normal", (20, 20, 20, 20)),
    "belle_btn_pressed": ("qtg_fr_pushbutton_pressed", (20, 20, 20, 20)),
    "belle_btn_disabled": ("qtg_fr_pushbutton_disabled", (20, 20, 20, 20)),
    "belle_list_pressed": ("qtg_fr_list_pressed", (20, 20, 20, 20)),
    "belle_list_highlighted": ("qtg_fr_list_highlighted", (20, 20, 20, 20)),
    "belle_progress_track": ("qtg_fr_progressbar_track", (20, 0, 20, 0)),
    "belle_progress_fill": ("qtg_fr_progressbar_fill", (20, 0, 20, 0)),
    "belle_slider_track": ("qtg_fr_slider_h_track_normal", (20, 0, 20, 0)),
    "belle_slider_fill": ("qtg_fr_slider_h_fill_normal", (20, 0, 20, 0)),
    "belle_popup": ("qtg_fr_popup", (20, 20, 20, 20)),
    "belle_infobanner": ("qtg_fr_popup_infobanner", (10, 10, 10, 10)),
}
FIXED = {
    "belle_check_off": "qtg_graf_checkbox_normal_unselected",
    "belle_check_on": "qtg_graf_checkbox_normal_selected",
    "belle_check_pressed": "qtg_graf_checkbox_pressed",
    "belle_check_off_disabled": "qtg_graf_checkbox_disabled_unselected",
    "belle_check_on_disabled": "qtg_graf_checkbox_disabled_selected",
    "belle_radio_off": "qtg_graf_radiobutton_normal_unselected",
    "belle_radio_on": "qtg_graf_radiobutton_normal_selected",
    "belle_radio_pressed": "qtg_graf_radiobutton_pressed",
    "belle_radio_off_disabled": "qtg_graf_radiobutton_disabled_unselected",
    "belle_radio_on_disabled": "qtg_graf_radiobutton_disabled_selected",
    "belle_switch_track": "qtg_graf_switchbutton_track",
    "belle_switch_fill": "qtg_graf_switchbutton_fill",
    "belle_switch_handle": "qtg_graf_switchbutton_handle_normal",
    "belle_switch_handle_pressed": "qtg_graf_switchbutton_handle_pressed",
    "belle_switch_track_off_disabled": "qtg_graf_switchbutton_disabled_off",
    "belle_switch_track_on_disabled": "qtg_graf_switchbutton_disabled_on",
    "belle_slider_handle": "qtg_graf_slider_h_handle_normal",
    "belle_slider_handle_pressed": "qtg_graf_slider_h_handle_pressed",
}
for i in range(1, 11):
    FIXED["belle_spinner_%02d" % i] = "qtg_anim_spinner_large_%d" % i


def render(svg, png):
    subprocess.run(["rsvg-convert", "-z", str(ZOOM), "-o", png, os.path.join(svg_dir, svg + ".svg")], check=True)
    return Image.open(png).convert("RGBA")


def nine_patch(img, borders):
    l, t, r, b = borders
    w, h = img.size
    out = Image.new("RGBA", (w + 2, h + 2), (0, 0, 0, 0))
    out.paste(img, (1, 1))
    px = out.load()
    black = (0, 0, 0, 255)
    # stretchable area: top row = horizontal, left column = vertical (no border on an axis = the whole axis stretches)
    for x in range(1 + l, 1 + w - r):
        px[x, 0] = black
    for y in range(1 + t, 1 + h - b):
        px[0, y] = black
    return out


missing = []
tmp = os.path.join(out_dir, "_tmp.png")
for name, (svg, br) in NINE.items():
    try:
        img = render(svg, tmp)
    except Exception as e:
        missing.append(svg)
        continue
    nine_patch(img, br).save(os.path.join(out_dir, name + ".9.png"))
for name, svg in FIXED.items():
    try:
        img = render(svg, tmp)
    except Exception:
        missing.append(svg)
        continue
    img.save(os.path.join(out_dir, name + ".png"))
if os.path.exists(tmp):
    os.remove(tmp)
print("rendered", len(NINE) + len(FIXED) - len(missing), "graphics; missing:", missing)
if missing:
    sys.exit(1)
