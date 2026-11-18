#!/system/bin/sh
# BelleUI System UI chooser and boot guard. Runs before the module's files are mounted.
#   system_ui=belle    the Belle System UI written from scratch (the default)
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
# Boot guard. service.sh sets the counter back to 0 once the System UI is up, so every step below means "the last boot(s) did not get there".
#   3rd unconfirmed boot: the framework overlay and the system font go (they act on the whole system) and stay off until $D/guard_tripped is deleted
#   4th: the stock System UI too
#   5th: the whole module is switched off (skip_mount): the phone boots as if it were not installed. Delete $MODDIR/skip_mount and $D/guard_tripped to try again.
if [ "$n" -ge 3 ] && [ ! -f $D/guard_tripped ]; then
  touch $D/guard_tripped
  echo "$(date): $n unconfirmed boots: framework overlay and system font switched off" >> $D/sysui.log
fi
# The Belle layout of Android's own dialogs (dialogs-overlay) is the newest and riskiest piece, so it goes first: on the 2nd unconfirmed boot.
# That tells you what broke: if the phone then boots, it was the dialog layout. Back on with reset_guard=1 (belleui.conf) or by deleting $D/dialogs_tripped.
if [ "$n" -ge 2 ] && [ ! -f $D/dialogs_tripped ]; then
  touch $D/dialogs_tripped
  echo "$(date): $n unconfirmed boots: the Belle dialog layout is switched off (dialogs_tripped)" >> $D/sysui.log
fi
if [ "$n" -ge 4 ]; then
  variant=none
  echo "$(date): $n unconfirmed boots, using the stock System UI" >> $D/sysui.log
fi
if [ "$n" -ge 5 ]; then
  touch $MODDIR/skip_mount
  echo "$(date): $n unconfirmed boots, the whole module is switched off (skip_mount)" >> $D/sysui.log
fi
# Compatibility gate. The framework overlay, the font, the dialog layout and the System UI replacement are built against ONE ROM: Android 11 whose
# framework-res.apk is signed with the public AOSP platform key (the System UI shares android.uid.systemui, the overlays must match the key, and the
# dialog layout names private framework ids from that exact framework-res.apk). On any other ROM they can wedge the boot, so they stay off there.
BB=/data/adb/magisk/busybox
[ -x $BB ] || BB=busybox
SDK=$(getprop ro.build.version.sdk)
CERT=$($BB unzip -p /system/framework/framework-res.apk 'META-INF/*.RSA' 2>/dev/null | $BB md5sum 2>/dev/null | cut -d' ' -f1)
FWMD5=$($BB md5sum /system/framework/framework-res.apk 2>/dev/null | cut -d' ' -f1)
compat_ok=0
dialogs_ok=0
if [ "$SDK" = "30" ] && [ "$CERT" = "ca440efe2d2e0164fc054448b1eabed4" ]; then compat_ok=1; fi
if [ "$compat_ok" = "1" ] && [ "$FWMD5" = "463b110f0cde1a924626c9c55191ea7d" ]; then dialogs_ok=1; fi
echo "sdk=$SDK cert=$CERT framework=$FWMD5 compat_ok=$compat_ok dialogs_ok=$dialogs_ok" > $D/compat
if [ "$compat_ok" != "1" ]; then
  variant=none
  echo "$(date): this ROM is not the one the module was built for (sdk=$SDK cert=$CERT): framework overlay, font, dialogs and System UI stay off" >> $D/sysui.log
fi
echo $variant > $D/variant

# The Android framework in Belle dress: the colour/controls overlay (/product/overlay) and the Nokia Sans font. Rebuilt every boot from extra/,
# so framework=0 / font=0 in $D/config (or the guard above) take effect on the next boot.
rm -rf $MODDIR/system/product/overlay $MODDIR/system/fonts
if [ ! -f $D/guard_tripped ]; then
  if [ "$compat_ok" = "1" ] && [ "$framework" != "0" ] && [ -f $MODDIR/extra/framework/BelleUIFramework.apk ]; then
    mkdir -p $MODDIR/system/product/overlay
    cp $MODDIR/extra/framework/BelleUIFramework.apk $MODDIR/system/product/overlay/BelleUIFramework.apk
    chmod 755 $MODDIR/system/product $MODDIR/system/product/overlay
    chmod 644 $MODDIR/system/product/overlay/BelleUIFramework.apk
    chown -R 0:0 $MODDIR/system/product
    chcon -R u:object_r:system_file:s0 $MODDIR/system/product 2>/dev/null
  fi
  if [ "$dialogs_ok" = "1" ] && [ "$dialogs" != "0" ] && [ ! -f $D/dialogs_tripped ] && [ -f $MODDIR/extra/framework/BelleUIDialogs.apk ]; then
    mkdir -p $MODDIR/system/product/overlay
    cp $MODDIR/extra/framework/BelleUIDialogs.apk $MODDIR/system/product/overlay/BelleUIDialogs.apk
    chmod 755 $MODDIR/system/product $MODDIR/system/product/overlay
    chmod 644 $MODDIR/system/product/overlay/BelleUIDialogs.apk
    chown -R 0:0 $MODDIR/system/product
    chcon -R u:object_r:system_file:s0 $MODDIR/system/product 2>/dev/null
  fi
  if [ "$compat_ok" = "1" ] && [ "$font" != "0" ] && [ -f $MODDIR/extra/fonts/nosnr60.ttf ]; then
    mkdir -p $MODDIR/system/fonts
    for f in Roboto-Thin Roboto-Light Roboto-Regular; do
      [ -f /system/fonts/$f.ttf ] && cp $MODDIR/extra/fonts/nosnr60.ttf $MODDIR/system/fonts/$f.ttf
    done
    for f in Roboto-Medium Roboto-Bold Roboto-Black; do
      [ -f /system/fonts/$f.ttf ] && cp $MODDIR/extra/fonts/nssb60.ttf $MODDIR/system/fonts/$f.ttf
    done
    chmod 755 $MODDIR/system/fonts
    chmod 644 $MODDIR/system/fonts/*.ttf 2>/dev/null
    chown -R 0:0 $MODDIR/system/fonts
    chcon -R u:object_r:system_file:s0 $MODDIR/system/fonts 2>/dev/null
  fi
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
