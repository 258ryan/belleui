#!/system/bin/sh
# Confirms a good boot: the System UI process is running and its status bar window exists.
MODDIR=${0%/*}
D=/data/adb/belleui
mkdir -p $D
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 3; done
sleep 30

# Belle puts the clock at the right end of the status bar next to the battery (Lineage setting: 0 right, 1 centre, 2 left); set once
if [ ! -f $D/clock_right_set ]; then
  settings put system status_bar_clock 0 2>/dev/null
  touch $D/clock_right_set
fi
if [ -n "$(pidof com.android.systemui)" ] && dumpsys window windows 2>/dev/null | grep -q "StatusBar"; then
  echo 0 > $D/sysui_restyled_boots
  echo "$(date): System UI confirmed" >> $D/sysui_restyled.log
else
  echo "$(date): System UI not confirmed" >> $D/sysui_restyled.log
fi
