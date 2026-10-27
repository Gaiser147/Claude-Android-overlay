plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing and version come from the environment (the release workflow sets them from
// repository secrets and the run number); local release builds stay unsigned.
val releaseKeystore: String? = System.getenv("RELEASE_KEYSTORE")?.takeIf { it.isNotBlank() }

android {
    namespace = "io.github.gaiser147.claudeoverlay"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.gaiser147.claudeoverlay"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"

        // The Claude Code CLI only ships for linux-arm64 (and x64); every bundled helper in
        // jniLibs is aarch64 too.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    sourceSets {
        // The interfaces shared with the bridge inside the Claude Notes clone (:notesbridge).
        getByName("main").aidl.srcDir("../notesbridge/src/main/aidl")
    }

    packaging {
        jniLibs {
            // The helpers in jniLibs are exec'd as programs, so they must land on disk in
            // nativeLibraryDir (the one app-owned place Android lets us exec from).
            useLegacyPackaging = true
            // libbash.so is a shell script, and the Alpine binaries are already stripped.
            keepDebugSymbols += "**/*.so"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.savedstate.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // On-device text recognition (bundled model, no download) for the live "@c" trigger.
    implementation(libs.mlkit.text.recognition)
    // Handwriting from the pen strokes the Claude Notes bridge reports (model downloaded once).
    implementation(libs.mlkit.digital.ink)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
