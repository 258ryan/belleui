# Belle framework colours: a static overlay on "android", placed in /product/overlay (systemless).
SKIPUNZIP=0
ui_print "- BelleUI: Android framework colours (static overlay)"
if [ "$API" -lt 30 ]; then
  ui_print "! Android 11 or newer is needed (found API $API)"
  abort "! Not installing"
fi
set_perm_recursive "$MODPATH/system/product/overlay" 0 0 0755 0644
ui_print "- Reboot. If a boot ever fails, disable this module in Magisk (or: touch /data/adb/modules/belleui_framework/disable from recovery or adb)."
ui_print "- To remove: remove the module in Magisk and reboot."
