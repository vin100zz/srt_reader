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

rootProject.name = "srt-reader"

// core : logique pure Kotlin (SRT, synchro, serveur web), testable sur PC
// app  : appli Google TV (fenêtre de sous-titres, service)
include(":core", ":app")
