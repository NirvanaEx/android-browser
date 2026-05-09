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
        // Mozilla artifacts: GeckoView + android-components live here.
        maven("https://maven.mozilla.org/maven2/")
    }
}

rootProject.name = "UpgridBrowser"
include(":app")
