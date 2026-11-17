plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// A standalone app (not part of any Magisk module): built by the "Build Tube" workflow with -PwithTube.
android {
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/belleui-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    namespace = "com.belleui.tube"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.belleui.tube"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // the extractor uses java.time, streams and other newer library classes
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
        // the extractor's own dependencies are built with a newer Kotlin than this project
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
    packaging {
        resources {
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/INDEX.LIST")
        }
    }
}

dependencies {
    implementation(project(":belle"))
    // NewPipe's extractor: the part of NewPipe that talks to YouTube (GPL-3.0)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
}
