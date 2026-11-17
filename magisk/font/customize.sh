# Nokia Sans S60 as the system font: Roboto files are replaced (systemless) only where the device has them.
SKIPUNZIP=0
ui_print "- BelleUI: Nokia Sans S60 as the system font"
if [ "$API" -lt 30 ]; then
  ui_print "! Android 11 or newer is needed (found API $API)"
  abort "! Not installing"
fi
mkdir -p "$MODPATH/system/fonts"
n=0
for f in Roboto-Thin Roboto-Light Roboto-Regular; do
  if [ -f "/system/fonts/$f.ttf" ]; then cp "$MODPATH/fonts/nosnr60.ttf" "$MODPATH/system/fonts/$f.ttf"; n=$((n+1)); fi
done
for f in Roboto-Medium Roboto-Bold Roboto-Black; do
  if [ -f "/system/fonts/$f.ttf" ]; then cp "$MODPATH/fonts/nssb60.ttf" "$MODPATH/system/fonts/$f.ttf"; n=$((n+1)); fi
done
rm -rf "$MODPATH/fonts"
ui_print "- Replaced $n font files"
if [ "$n" -eq 0 ]; then
  ui_print "! This device has no Roboto-*.ttf in /system/fonts: nothing to replace"
  abort "! Not installing"
fi
set_perm_recursive "$MODPATH/system/fonts" 0 0 0755 0644
ui_print "- Reboot. If a boot ever fails, disable this module in Magisk (or: touch /data/adb/modules/belleui_font/disable from recovery or adb)."
ui_print "- To remove: remove the module in Magisk and reboot."
