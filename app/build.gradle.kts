plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.abhay.assistant"
    // Latest AndroidX needs API 37 to compile; this doesn't change min (33) or target (36).
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.abhay.assistant"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Needle engine is only linked for 64-bit ARM (Pixel, Poco F5); no x86 emulators.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_static" }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // The bundled model stays uncompressed in the APK, so the engine maps it in place.
    androidResources {
        noCompress += "cact"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // The release build (R8 and all) as its own app, dev.abhay.assistant.smoke, signed with the
        // debug key: for the smoke test on a phone, beside the installed Ferb and never touching its
        // data. Ferb Link answers it when Ferb Link is a debug build too (same key). Never released.
        create("smoke") {
            initWith(getByName("release"))
            applicationIdSuffix = ".smoke"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    // The smoke build compiles release's own stand-ins (no bench, no private lists).
    sourceSets.getByName("smoke").kotlin.directories += "src/release/kotlin"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // The test APK must use the app's versions (consistent resolution), and androidx.test and Truth
    // need these newer ones; without them it stopped building (2026-10-29). Both are tiny.
    constraints {
        implementation("androidx.concurrent:concurrent-futures:1.2.0")
        implementation("com.google.errorprone:error_prone_annotations:2.41.0")
    }
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material.components)
    implementation(libs.mlkit.genai.speech)
    implementation(libs.mlkit.genai.prompt)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)
    // Compose UI tests (androidTest) need their test activity in the debug manifest.
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.natural.date.parser)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
