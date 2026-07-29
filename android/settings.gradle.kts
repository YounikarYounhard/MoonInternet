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
        // sing-box's libbox AAR is published here
        maven("https://jitpack.io")
    }
}
rootProject.name = "MoonInternet"
include(":app")
