#!/system/bin/sh
# Confirms a good boot: the System UI process is running and its status bar window exists.
MODDIR=${0%/*}
D=/data/adb/belleui
mkdir -p $D
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 3; done
sleep 30
if [ -n "$(pidof com.android.systemui)" ] && dumpsys window windows 2>/dev/null | grep -q "StatusBar"; then
  echo 0 > $D/sysui_restyled_boots
  echo "$(date): System UI confirmed" >> $D/sysui_restyled.log
else
  echo "$(date): System UI not confirmed" >> $D/sysui_restyled.log
fi
