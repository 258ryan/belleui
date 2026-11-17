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
# which System UI: belle (written from scratch, the default), restyled (the ROM's own with Belle colours), none (stock)
system_ui=belle
# 0 puts the stock System UI back whatever system_ui says
install_system_ui=1
# install the Belle Chooser (Vector) module APK once
install_vector_module=1
# the Android framework in Belle dress: 1 = on (default), 0 = off. They act on the whole system, so after three boots that
# do not come up they are switched off by themselves (until you delete /data/adb/belleui/guard_tripped)
framework=1
font=1
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

# Boot guard: the counter that post-fs-data.sh keeps goes back to zero only once the System UI (the Belle one, or the stock one if the guard
# fell back to it) has been up for FOUR MINUTES. A boot that comes up and dies a minute later (the loop that an overlay or a font can cause)
# therefore still counts as failed. Runs in the background so the rest of this script is not held up.
variant=$(cat $DIR/variant 2>/dev/null)
[ -z "$variant" ] && variant=${system_ui:-belle}
(
  sleep 240
  ok=0
  if [ "$variant" = "belle" ]; then
    # the Belle System UI writes the boot number into a setting once it is the registered status bar
    [ "$(settings get global belle_sysui_ok)" = "$(settings get global boot_count)" ] && ok=1
  else
    if [ -n "$(pidof com.android.systemui)" ] && dumpsys window windows 2>/dev/null | grep -q "StatusBar"; then ok=1; fi
  fi
  {
    echo "$(date): System UI check after 4 minutes variant=$variant ok=$ok"
    logcat -d -s BelleSysUi:V 2>&1 | tail -20
  } >> $DIR/sysui.log
  [ "$ok" = "1" ] && echo 0 > $DIR/sysui_boots
) &

# --- control and diagnostics you can reach from adb without root ---------------------------------------------------------
# /sdcard/Download/belleui.conf (framework=0|1, font=0|1, system_ui=..., reset_guard=1) is merged into /data/adb/belleui/config here,
# then deleted; the settings that act on boot (framework, font) apply from the next boot on.
SD=/sdcard/Download
CF=$SD/belleui.conf
if [ -f $CF ]; then
  for k in framework font system_ui set_default_home set_default_keyboard install_system_ui; do
    v=$(grep -E "^$k=" $CF | tail -1 | cut -d= -f2 | tr -d '\r ')
    if [ -n "$v" ]; then
      sed -i "/^$k=/d" $CONF
      echo "$k=$v" >> $CONF
      echo "$(date): config $k=$v (from belleui.conf)" >> $DIR/sysui.log
    fi
  done
  if grep -q '^reset_guard=1' $CF; then
    rm -f $DIR/guard_tripped $MODDIR/skip_mount
    echo 0 > $DIR/sysui_boots
    echo "$(date): guard reset (from belleui.conf)" >> $DIR/sysui.log
  fi
  rm -f $CF
fi
# when the guard has switched things off, keep what the system recorded about the failed boots where adb can read it
if [ -f $DIR/guard_tripped ] && [ ! -f $DIR/crash_collected ]; then
  OUT=$SD/belleui-crash
  mkdir -p $OUT
  cp -r /data/system/dropbox $OUT/dropbox 2>/dev/null
  cp -r /data/tombstones $OUT/tombstones 2>/dev/null
  cp $DIR/*.log $OUT/ 2>/dev/null
  logcat -b crash -d > $OUT/logcat-crash.txt 2>&1
  dmesg > $OUT/dmesg.txt 2>&1
  chmod -R a+rX $OUT 2>/dev/null
  touch $DIR/crash_collected
  echo "$(date): crash logs copied to $OUT" >> $DIR/sysui.log
fi
rm -f $DIR/crash_collected_stale 2>/dev/null
[ ! -f $DIR/guard_tripped ] && rm -f $DIR/crash_collected
# a status file to read with adb pull /sdcard/Download/belleui-status.txt
{
  echo "date: $(date)"
  echo "module: $(grep '^version=' $MODDIR/module.prop)"
  echo "variant: $(cat $DIR/variant 2>/dev/null)   boots unconfirmed: $(cat $DIR/sysui_boots 2>/dev/null)"
  echo "guard_tripped: $([ -f $DIR/guard_tripped ] && echo yes || echo no)   skip_mount: $([ -f $MODDIR/skip_mount ] && echo yes || echo no)"
  echo "--- config"; cat $CONF
  echo "--- overlay in module:"; ls -la $MODDIR/system/product/overlay 2>&1
  echo "--- fonts in module:"; ls -la $MODDIR/system/fonts 2>&1
  echo "--- Roboto-Regular now:"; ls -la /system/fonts/Roboto-Regular.ttf 2>&1
  echo "--- overlays:"; cmd overlay list 2>&1 | grep -i -B1 belle
  echo "--- sysui.log (tail)"; tail -15 $DIR/sysui.log 2>&1
} > $SD/belleui-status.txt 2>&1
chmod 666 $SD/belleui-status.txt 2>/dev/null

# Belle puts the clock at the right end of the status bar next to the battery (Lineage setting: 0 right, 1 centre, 2 left); set once
if [ ! -f $DIR/clock_right_set ]; then
  settings put system status_bar_clock 0 2>/dev/null
  touch $DIR/clock_right_set
fi
