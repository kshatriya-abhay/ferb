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
// The locale probe app (Gemini Nano's speech under several locales): a separate app, never part of Ferb.
include(":localeprobe")
