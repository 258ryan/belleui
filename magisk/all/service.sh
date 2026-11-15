#!/system/bin/sh
# BelleUI: runs at every boot once Android is up. It only grants access and sets defaults; nothing else on the system is changed.
MODDIR=${0%/*}
DIR=/data/adb/belleui
CONF=$DIR/config
mkdir -p $DIR
if [ ! -f $CONF ]; then
  cat > $CONF <<'EOT'
# 1 = on, 0 = off. Edit and reboot.
# make the Belle launcher the home app
set_default_home=1
# make the Belle keyboard the input method
set_default_keyboard=0
# which System UI: belle (written from scratch), restyled (the ROM's own with Belle colours), none (stock)
system_ui=belle
# 0 puts the stock System UI back whatever system_ui says
install_system_ui=1
# install the Belle Chooser (Vector) module APK once
install_vector_module=1
EOT
fi
. $CONF

until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 3; done
sleep 12

LOG=$DIR/boot.log
{
  echo "boot: $(date)"
  echo "module system apps present:"; ls $MODDIR/system/priv-app $MODDIR/system/app 2>&1
  echo "mounted system apps:"; ls /system/priv-app /system/app 2>&1 | grep -i belle
} > $LOG 2>&1

# an app that was uninstalled "for the user" (from the app list or with adb) stays on the system image
# and can be brought back: do that for every Belle app, so they never stay missing
ALL="launcher settings files picker music clock gallery browser games styles keyboard video notes calc recorder"
for n in $ALL; do
  cmd package install-existing com.belleui.$n >> $LOG 2>&1
done
echo "installed Belle packages:" >> $LOG
pm list packages | grep belleui >> $LOG 2>&1

L=com.belleui.launcher
# shared storage, for the apps that browse or scan files
for p in com.belleui.files com.belleui.games com.belleui.picker com.belleui.music com.belleui.gallery com.belleui.video com.belleui.recorder com.belleui.notes com.belleui.styles com.belleui.settings $L; do
  appops set $p MANAGE_EXTERNAL_STORAGE allow 2>/dev/null
  pm grant $p android.permission.READ_EXTERNAL_STORAGE 2>/dev/null
  pm grant $p android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null
done
# changing system settings (brightness, tones, sounds)
for p in $L com.belleui.settings com.belleui.styles; do
  appops set $p WRITE_SETTINGS allow 2>/dev/null
done
# usage access, for the launcher's recent apps and the Settings app's app list
for p in $L com.belleui.settings; do
  appops set $p GET_USAGE_STATS allow 2>/dev/null
done
pm grant $L android.permission.READ_CALENDAR 2>/dev/null
pm grant com.belleui.recorder android.permission.RECORD_AUDIO 2>/dev/null
# the Hub: see notifications, and the music widget: see what is playing
cmd notification allow_listener $L/$L.HubListenerService 2>/dev/null

if [ "$set_default_home" = "1" ]; then
  cmd role add-role-holder --user 0 android.app.role.HOME $L 0 2>/dev/null
fi
if [ "$set_default_keyboard" = "1" ]; then
  ime enable com.belleui.keyboard/.BelleIme 2>/dev/null
  ime set com.belleui.keyboard/.BelleIme 2>/dev/null
else
  ime enable com.belleui.keyboard/.BelleIme 2>/dev/null
fi

# the Belle Chooser Vector module is a normal app (Vector turns it on); install it once
if [ "$install_vector_module" = "1" ] && [ ! -f $DIR/vector_installed ] && [ -f $MODDIR/extra/BelleUI-Chooser-Vector.apk ]; then
  pm install -r $MODDIR/extra/BelleUI-Chooser-Vector.apk >/dev/null 2>&1 && touch $DIR/vector_installed
fi

# System UI boot guard: once the chosen System UI is up, the "unconfirmed boots" counter that post-fs-data.sh keeps goes back to zero.
variant=${system_ui:-belle}
[ "$install_system_ui" = "0" ] && variant=none
if [ "$variant" != "none" ]; then
  sleep 30
  ok=0
  if [ "$variant" = "belle" ]; then
    # the Belle System UI writes the boot number into a setting once it is the registered status bar
    [ "$(settings get global belle_sysui_ok)" = "$(settings get global boot_count)" ] && ok=1
  else
    # the stock one: its process is running and the status bar window exists
    if [ -n "$(pidof com.android.systemui)" ] && dumpsys window windows 2>/dev/null | grep -q "StatusBar"; then ok=1; fi
  fi
  {
    echo "$(date): System UI check variant=$variant ok=$ok"
    logcat -d -s BelleSysUi:V 2>&1 | tail -20
  } >> $DIR/sysui.log
  [ "$ok" = "1" ] && echo 0 > $DIR/sysui_boots
fi
