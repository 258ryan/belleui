#!/bin/sh
# Rebuilds BelleUIDialogs-unsigned.apk. The layouts name private framework ids (@*android:id/buttonPanel ...), so they are linked
# against the real framework-res.apk of the device's ROM:  ./build.sh /path/to/framework-res.apk
# CI signs the result with the platform key (module.yml) -- it is not a Gradle module on purpose.
set -e
FW=${1:?usage: build.sh framework-res.apk}
cd "$(dirname "$0")"
rm -rf /tmp/dlg-build && mkdir /tmp/dlg-build
aapt2 compile --dir res -o /tmp/dlg-build/compiled.zip
aapt2 link -I "$FW" --manifest AndroidManifest.xml --min-sdk-version 30 --target-sdk-version 30 -o BelleUIDialogs-unsigned.apk /tmp/dlg-build/compiled.zip
