plugins {
    id("com.android.application")
}

// A static runtime resource overlay on the Android framework ("android"): the Belle palette for every app and dialog.
// The device's ROM is signed with the public AOSP platform test key, and an overlay on "android" must carry the same
// signature, so this one is signed with it (see keystore/aosp-platform.jks).
android {
    signingConfigs {
        create("platform") {
            storeFile = rootProject.file("keystore/aosp-platform.jks")
            storePassword = "android"
            keyAlias = "platform"
            keyPassword = "android"
        }
    }
    namespace = "com.belleui.overlay.framework"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.belleui.overlay.framework"
        minSdk = 30
        targetSdk = 30
        versionCode = 1
        versionName = "1"
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("platform") }
        getByName("release") { signingConfig = signingConfigs.getByName("platform") }
    }
}
