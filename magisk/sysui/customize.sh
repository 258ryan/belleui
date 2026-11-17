# BelleUI - System UI replacement (experimental)
SKIPUNZIP=0
ui_print "- BelleUI System UI (experimental)"
if [ "$API" -lt 30 ]; then
  ui_print "! Android 11 or newer is needed (found API $API)"
  abort "! Not installing"
fi
ui_print "- This REPLACES the stock System UI. There is no lock screen with it."
ui_print "- It only works on a ROM signed with the AOSP public test keys."
ui_print "- Keep adb working: pairing a NEW computer needs the stock System UI."
ui_print "- To undo from adb:  adb shell su -c 'touch /data/adb/modules/belleui_sysui/disable'  then reboot"
ui_print "- or in Magisk safe mode / a custom recovery: delete /data/adb/modules/belleui_sysui"
mkdir -p /data/adb/belleui
echo 0 > /data/adb/belleui/sysui_boots
set_perm_recursive "$MODPATH/system" 0 0 0755 0644
