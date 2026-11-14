#!/system/bin/sh
# Runs at every boot once Android is up: lets the picker see shared storage.
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 3; done
sleep 8
PKG=com.belleui.picker
appops set $PKG MANAGE_EXTERNAL_STORAGE allow
pm grant $PKG android.permission.READ_EXTERNAL_STORAGE 2>/dev/null
pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null
