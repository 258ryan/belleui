# Belle Folder and File Picker (Magisk module)

Replaces the stock "choose a folder / choose a file" screen for every app, in the Belle style, with
gamepad navigation. Installs `BelleUIPicker.apk` as a system app through Magisk (systemless) and gives
it access to shared storage at each boot.

* Needs Magisk 20.4+ and Android 11+.
* After flashing, reboot. Apps that ask for a folder (Music, Gallery, Games, file managers...) now open this picker.
* Remove it: Magisk > Modules > remove (or disable) > reboot. Magisk's safe mode disables every module if something goes wrong.
* If an app still shows the old picker: the Android chooser may appear first time; pick Belle Picker and Always.
