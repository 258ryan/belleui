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
ui_print "- Reboot. Settings live in /data/adb/belleui/config"
ui_print "- To remove: remove the module in Magisk and reboot (the Vector module APK, if installed, stays: uninstall it like any app)."
