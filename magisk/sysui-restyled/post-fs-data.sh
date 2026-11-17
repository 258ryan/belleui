#!/system/bin/sh
# Boot guard: counts boots the System UI has not confirmed; at three, this module's System UI is set aside so the stock one runs.
MODDIR=${0%/*}
D=/data/adb/belleui
mkdir -p $D
n=$(cat $D/sysui_restyled_boots 2>/dev/null)
[ -z "$n" ] && n=0
n=$((n + 1))
echo $n > $D/sysui_restyled_boots
if [ "$n" -ge 3 ] && [ -d $MODDIR/system/system_ext ]; then
  mkdir -p $D/removed
  mv $MODDIR/system/system_ext $D/removed/system_ext_$(date +%s) 2>/dev/null || rm -rf $MODDIR/system/system_ext
  echo "$(date): $n unconfirmed boots, restyled System UI set aside" >> $D/sysui_restyled.log
fi
