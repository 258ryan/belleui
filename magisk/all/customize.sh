# BelleUI - the whole set
SKIPUNZIP=0
ui_print "- BelleUI: launcher and apps as system apps"
if [ "$API" -lt 30 ]; then
  ui_print "! Android 11 or newer is needed (found API $API)"
  abort "! Not installing"
fi
set_perm_recursive "$MODPATH/system" 0 0 0755 0644
set_perm_recursive "$MODPATH/extra" 0 0 0755 0644
ui_print "- If you installed any Belle app normally before, remove it first:"
ui_print "    every build is signed with one shared key, but older builds were not,"
ui_print "    and Android refuses a system app whose key differs from an installed copy."
ui_print "- This also replaces the stock System UI with the Belle one, written from scratch (no lock screen)."
ui_print "    Keep adb working: pair new computers BEFORE installing; pairing needs the stock System UI."
ui_print "    Choose it in /data/adb/belleui/config: system_ui=belle (default), restyled (the ROM's own, Belle colours) or none."
ui_print "    If a boot fails twice the stock System UI is used on the next one."
mkdir -p /data/adb/belleui
# the Belle System UI is the default now: an older config that still says restyled is switched over once
if [ -f /data/adb/belleui/config ] && grep -q '^system_ui=restyled' /data/adb/belleui/config; then
  sed -i 's/^system_ui=restyled/system_ui=belle/' /data/adb/belleui/config
  ui_print "- Switched system_ui from restyled to belle in your config."
fi
echo 0 > /data/adb/belleui/sysui_boots

# --- the Android framework in Belle dress: colours and controls (a static overlay) and the Nokia Sans system font.
# Both can be left out by putting framework=0 and/or font=0 in /data/adb/belleui/config before installing.
CONF=/data/adb/belleui/config
want() { ! grep -q "^$1=0" "$CONF" 2>/dev/null; }
if want framework; then
  ui_print "- Android framework: Belle colours and controls (overlay on android)"
  set_perm_recursive "$MODPATH/system/product/overlay" 0 0 0755 0644
else
  ui_print "- Android framework overlay left out (framework=0 in $CONF)"
  rm -rf "$MODPATH/system/product/overlay"
fi
if want font; then
  mkdir -p "$MODPATH/system/fonts"
  nf=0
  for f in Roboto-Thin Roboto-Light Roboto-Regular; do
    if [ -f "/system/fonts/$f.ttf" ]; then cp "$MODPATH/fonts/nosnr60.ttf" "$MODPATH/system/fonts/$f.ttf"; nf=$((nf+1)); fi
  done
  for f in Roboto-Medium Roboto-Bold Roboto-Black; do
    if [ -f "/system/fonts/$f.ttf" ]; then cp "$MODPATH/fonts/nssb60.ttf" "$MODPATH/system/fonts/$f.ttf"; nf=$((nf+1)); fi
  done
  set_perm_recursive "$MODPATH/system/fonts" 0 0 0755 0644
  ui_print "- System font: Nokia Sans S60 ($nf Roboto files replaced)"
else
  ui_print "- System font left unchanged (font=0 in $CONF)"
fi
rm -rf "$MODPATH/fonts"
ui_print "    If a boot fails after installing: put framework=0 or font=0 in $CONF (or touch /data/adb/modules/belleui_all/disable), reboot."
ui_print "- Reboot. Settings live in /data/adb/belleui/config"
ui_print "- To remove: remove the module in Magisk and reboot (the Vector module APK, if installed, stays: uninstall it like any app)."
