#!/system/bin/sh
# Confirms a good boot: the Belle System UI writes the boot number into a setting once it is the registered status bar.
MODDIR=${0%/*}
D=/data/adb/belleui
mkdir -p $D
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 3; done
sleep 40
ok=$(settings get global belle_sysui_ok)
boot=$(settings get global boot_count)
{
  echo "$(date): boot=$boot ok=$ok"
  logcat -d -s BelleSysUi:V 2>&1 | tail -40
} >> $D/sysui.log
if [ -n "$ok" ] && [ "$ok" = "$boot" ]; then
  echo 0 > $D/sysui_boots
fi
