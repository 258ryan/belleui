pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // only for NewPipe's extractor (the standalone Tube app)
        maven("https://jitpack.io") {
            content { includeGroup("com.github.TeamNewPipe") }
        }
    }
}
rootProject.name = "belleui"
include(":belle")
include(":app")
include(":music")
include(":clock")
include(":gallery")
include(":browser")
include(":games")
include(":files")
include(":settings")
include(":styles")
include(":keyboard")
include(":video")
include(":notes")
include(":calc")
include(":recorder")
include(":picker")
include(":xposed-stub")
include(":vector")
include(":sysui")
include(":framework-overlay")
// the standalone Tube app is not part of the normal builds (or any module): only with -PwithTube
if (providers.gradleProperty("withTube").isPresent) include(":tube")
