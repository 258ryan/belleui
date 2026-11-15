# BelleUI - System UI only (restyled stock)
SKIPUNZIP=0
ui_print "- BelleUI System UI (restyled stock)"
if [ "$API" -lt 30 ]; then
  abort "! Android 11 or newer is needed (found API $API)"
fi
ui_print "- Replaces the ROM's System UI with the same one in Belle colours and shapes."
ui_print "- Do not use it together with the BelleUI whole-set module's System UI:"
ui_print "    set install_system_ui=0 in /data/adb/belleui/config there, or remove that module."
ui_print "- If two boots in a row do not come up, the stock System UI is used again."
ui_print "- To undo by hand: touch /data/adb/modules/belleui_sysui_restyled/disable and reboot."
mkdir -p /data/adb/belleui
echo 0 > /data/adb/belleui/sysui_restyled_boots
set_perm_recursive "$MODPATH/system" 0 0 0755 0644
ui_print "- Reboot."
