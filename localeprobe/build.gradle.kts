// The locale probe (2026-10-30): Gemini Nano's speech recognition (ML Kit GenAI) on the voice
// bench's recordings and a few TTS clips, under several locales, for tool-check/locales.py. A
// separate app, never part of Ferb, so it installs beside the release-signed Ferb on the phone.
// No INTERNET: AICore downloads models, and the probe never asks it to.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.abhay.localeprobe"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig {
        applicationId = "dev.abhay.localeprobe"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(libs.mlkit.genai.speech)
    // Ferb gets genai-common 1.0.0-beta4 through the Prompt API; speech alone brings beta3 (no fromPfd with a mode).
    implementation("com.google.mlkit:genai-common:1.0.0-beta4")
    implementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
