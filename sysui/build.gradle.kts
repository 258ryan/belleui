plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // The device's ROM is signed with the public AOSP platform test key. Only that key may share the system UI's user id
    // and hold the status bar permissions, so this build is signed with it (and nothing else in the set is).
    signingConfigs {
        create("platform") {
            storeFile = rootProject.file("keystore/aosp-platform.jks")
            storePassword = "android"
            keyAlias = "platform"
            keyPassword = "android"
        }
    }
    namespace = "com.belleui.sysui"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.android.systemui"
        minSdk = 30
        targetSdk = 30
        // higher than the stock one, so the package manager takes this build for the update
        versionCode = 2100000000
        versionName = "11-belle"
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("platform") }
        getByName("release") { signingConfig = signingConfigs.getByName("platform") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // the volume pop-up, power menu and Status menu are the same code the Vector module draws; one copy, two homes
    sourceSets["main"].kotlin.srcDir(rootProject.file("vector/src/main/java"))
}

// only Panels.kt (and what it needs) comes from the Vector module, not its Xposed hooks
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    exclude("**/ChooserHook.kt", "**/SystemUiHook.kt", "**/Restyle.kt", "**/InfoActivity.kt", "**/Status.kt")
}

dependencies {
    implementation(project(":belle"))
}
