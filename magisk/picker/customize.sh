# Belle picker: runs while the module is installed.
SKIPUNZIP=0
ui_print "- Belle Folder and File Picker"
ui_print "- Installs the picker as a system app (nothing on /system is changed)"
if [ "$API" -lt 30 ]; then
  ui_print "! Android 11 or newer is needed (found API $API)"
  abort "! Not installing"
fi
set_perm_recursive "$MODPATH/system/priv-app" 0 0 0755 0644
ui_print "- Reboot, then open any app's folder picker."
ui_print "- To remove it: disable or remove this module in Magisk and reboot."
