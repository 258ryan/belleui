#!/system/bin/sh
# BelleUI System UI chooser and boot guard. Runs before the module's files are mounted.
#   system_ui=belle    the Belle System UI written from scratch (default)
#   system_ui=restyled the stock System UI of this ROM with the Belle colours (keeps the lock screen, notifications, hint bar)
#   system_ui=none     the stock System UI, untouched
# in /data/adb/belleui/config. If the chosen one did not come up on two boots in a row, the third boot uses the stock one.
MODDIR=${0%/*}
D=/data/adb/belleui
mkdir -p $D
[ -f $D/config ] && . $D/config
variant=${system_ui:-belle}
[ "$install_system_ui" = "0" ] && variant=none
n=$(cat $D/sysui_boots 2>/dev/null)
[ -z "$n" ] && n=0
n=$((n + 1))
echo $n > $D/sysui_boots
if [ "$n" -ge 3 ]; then
  variant=none
  echo "$(date): $n unconfirmed boots, using the stock System UI" >> $D/sysui.log
fi
T=$MODDIR/system/system_ext/priv-app/SystemUI
rm -rf $MODDIR/system/system_ext/priv-app
case "$variant" in
  belle) src=$MODDIR/extra/SystemUI-belle.apk ;;
  restyled) src=$MODDIR/extra/SystemUI-restyled.apk ;;
  *) src="" ;;
esac
if [ -n "$src" ] && [ -f "$src" ]; then
  mkdir -p $T
  cp "$src" $T/SystemUI.apk
  chmod 644 $T/SystemUI.apk
  touch $T/.replace
fi
# the extra permission whitelist is only for the from-scratch one
if [ "$variant" != "belle" ]; then
  rm -f $MODDIR/system/system_ext/etc/permissions/privapp-permissions-belleui-sysui.xml
fi
echo "$(date): System UI variant $variant (boot count $n)" >> $D/sysui.log
