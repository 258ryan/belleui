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
