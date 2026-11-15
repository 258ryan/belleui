#!/system/bin/sh
# BelleUI boot guard for the System UI part. Runs before the module's files are mounted.
# If the new System UI did not come up on two boots in a row, or install_system_ui=0 is set in
# /data/adb/belleui/config, the System UI files are removed from the module so the stock one is used,
# and everything else in the module keeps working.
MODDIR=${0%/*}
D=/data/adb/belleui
mkdir -p $D
[ -f $D/config ] && . $D/config
n=$(cat $D/sysui_boots 2>/dev/null)
[ -z "$n" ] && n=0
n=$((n + 1))
echo $n > $D/sysui_boots
if [ "$install_system_ui" = "0" ] || [ "$n" -ge 3 ]; then
  if [ -d $MODDIR/system/system_ext ]; then
    mkdir -p $D/removed
    mv $MODDIR/system/system_ext $D/removed/system_ext_$(date +%s) 2>/dev/null || rm -rf $MODDIR/system/system_ext
    echo "$(date): System UI part removed (boots=$n, install_system_ui=$install_system_ui)" >> $D/sysui.log
  fi
fi
