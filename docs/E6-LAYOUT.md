# E6 layout data, read from the firmware

The kit's metrics now come from the Nokia E6's own layout tables in the RM-609 dump
(release `dump`, `rm-609.zip`), not from screenshots.

## Where the data is

- `sys/bin/2001cb90.dll` is the layout pack DLL, an E32 image with byte-pair compression. It holds the
  `Vga4_touch` family. The E6 uses **`Elaf_Vga4_touch_land_Normal_Pack`** (640x480):
  - Avkon instance #30: lookup at 0xEE270, byte data at 0xC030
  - Apps instance #31: lookup at 0xF99E8, byte data at 0x2F6EC
- `sys/bin/aknlayout2scalable.dll` is the decoder. It is a ROM (XIP) image, code at 0x8172C328.
  It was disassembled with REA/Ghidra:
  - value codes: `0xFB` long, `0xFC` p+u16, `0xFD` p+s8, `0xFE` empty (0xC004), `0xFF` u16
  - parent-relative values sit on a base of 0x4000
  - this matches the Symbian Foundation `AknLayout2Scalable.cpp` exactly.
- Unlike the Symbian Foundation sources, the firmware's lookup slots are **32-bit**. The E6 data is a
  2012 revision of the 2010 SF `Vga4_touch` pack.
- Line names come from the SF CDL order (`oss.FCL.sf.mw.uiresources`,
  `generated/Vga4_touch_akn_app`). The interface is append-only. Avkon has 5870 APIs (SF names 5637);
  Apps has 8053 (SF names 6374). Lines past the SF range are dumped as `api_<n>`.
- Agreement: 1437 Avkon lines are identical to SF and 1794 changed, with the same variety counts,
  which confirms the index→name mapping. The changes are the Anna→Belle redesign, for example
  `area_bottom_pane` 46→82/68.

## Key landscape values (640x480)

| Element | Layout line | Value |
|---|---|---|
| Status pane (flat) | `area_top_pane` | 35 px |
| Toolbar / control pane | `area_bottom_pane`, `main_pane` t=35 b=68 | 68 px |
| Softkey label | `control_pane_t1` | title font, text pane 40 |
| Title text | `title_pane_t1` (flat) | title font, text pane 35 |
| List insets | `list_gen_pane` | 14 px left/right |
| Single row | `list_single_pane` | 75 px; text l=20, primary pane 35 |
| Double row | `list_double_pane` | 94 px; t1 at y=14 (primary 35), t2 at y=50 (secondary 30) |
| Large-icon row | `list_double_large_graphic_pane` | 94 px; icon 72x72 at (10,11); text at x=90 |
| Small-icon row | `list_single_graphic_pane_g1` | 37x37 at (5,19); text at x=46 |
| Options menu | `popup_menu_window` / `list_single_pane_cp2` | 480 wide, 67 px items |
| Popup heading | `heading_pane` | 53 px, 8 px inset |
| App grid cell | `cell_app_pane` var 2 | 151x121 |
| App grid label | `cell_app_pane_t1` | title font, text pane 30 |

Font IDs are self-describing:
- bit 31: encoded
- bits 0–3: category (1 primary, 2 secondary, 3 title, 4 primary small, 5 digital)
- bit 4: bold
- bits 21–30: text-pane height in px. Symbian sizes the font so that ascent+descent fills this height.

## Reproduce

```
python3 tools/e6-firmware/e32unpack.py <dump>/sys/bin/2001cb90.dll flat/2001cb90.bin
python3 tools/e6-firmware/e6layout.py <scratch>      # needs the SF uiresources checkout under <scratch>/src/uires
python3 tools/e6-firmware/e6q.py <scratch> show list_double_pane
```

`docs/e6_layout.json.gz` is the full decoded dump: every Avkon and Apps line, every variety.
