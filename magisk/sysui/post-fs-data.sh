#!/system/bin/sh
# Boot guard. Counts boots that have not been confirmed by the new System UI; at three, the module switches itself off.
MODDIR=${0%/*}
D=/data/adb/belleui
mkdir -p $D
n=$(cat $D/sysui_boots 2>/dev/null)
[ -z "$n" ] && n=0
n=$((n + 1))
echo $n > $D/sysui_boots
if [ "$n" -ge 3 ]; then
  touch $MODDIR/skip_mount $MODDIR/disable
  echo "$(date): $n unconfirmed boots, module switched off" >> $D/sysui.log
fi
