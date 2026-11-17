plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // one shared debug key for every Belle app, so a newer build always installs over an older one
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/belleui-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    namespace = "com.belleui.games"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.belleui.games"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":belle"))
}
