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

rootProject.name = "OnDeviceAssistant"
include(":app")
// The date bench app (ML Kit, TextClassifier): a separate app, never part of Ferb.
include(":datebench")
