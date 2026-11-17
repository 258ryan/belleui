# Belle design tokens from the ROM dump

Everything here is read from `rm-609.zip` (release `dump`); nothing is typed in by hand.

1. `extract.py <dump> belle.tokens.json` reads the Qt Components 1.1 QML in
   `resource/qt/imports/com/nokia/{symbian,extras}.1.1`: constants, per-component durations, easing curves,
   opacities, sizes and the `qtg_*` image names.
2. The platform metrics and palette (`privateStyle.*`) live in the Qt resource embedded in
   `sys/bin/symbianplugin_1_1_2.dll`:
   ```
   python3 tools/e6-firmware/e32unpack.py <dump>/sys/bin/symbianplugin_1_1_2.dll flat.bin
   python3 tools/belle-tokens/qrc_extract.py flat.bin out/
   ```
   gives `params/colors/default.params`, `params/layouts/{640_480_325,640_360_*,fallback}.params` and the 364 theme SVGs
   (`graphics_1_1_2/`, normal and `_inverse`). The SVGs are Nokia artwork: keep them out of git.
   The extractor pairs qrc entries with blobs by data order (offsets are not usable because the compressed and
   raw blobs sit in separate runs); the toolbar glyphs all come out 30px, which checks the pairing.
3. `belle.tokens.json` also carries a `platform` section (colours + layout profiles) merged from step 2.

The `640_480_325` profile is the E6 (640x480). Its landscape values agree with `docs/E6-LAYOUT.md` where the two
overlap (toolbar 68); the Qt Components menu item is 88 whereas the native Avkon popup item is 67.
