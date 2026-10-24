// The date bench (2026-10-24): ML Kit Entity Extraction and the system TextClassifier read the
// date sets' requests on the phone, for tool-check/DateReadersEval. A separate app, never part
// of Ferb: ML Kit downloads its model itself and needs INTERNET, which Ferb never has.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.abhay.datebench"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig {
        applicationId = "dev.abhay.datebench"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation("com.google.mlkit:entity-extraction:16.0.0-beta6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
