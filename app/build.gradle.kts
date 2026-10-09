plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The release channel is the git tag (v0.1.0-debug). CI exports it so the
// APK's own metadata names the release it was built for — a fixed 0.1.0 meant
// every build claimed to be the same version no matter which tag built it.
// A tag that does not parse as a version (e.g. "main" from a branch build)
// falls back to the baseline instead of leaking into versionName.
val releaseTag = System.getenv("CNB_BRANCH") ?: System.getenv("GITHUB_REF_NAME")
val releaseVersion = releaseTag
    ?.removePrefix("v")
    ?.takeIf { it.matches(Regex("""\d+(\.\d+){0,2}(-.*)?""")) }
    ?.substringBefore('-')
val versionParts = releaseVersion?.split('.')?.mapNotNull { it.toIntOrNull() }

android {
    namespace = "com.hermes.android"
    compileSdk = 35
    // Pinned to what the local SDK mirror already provides; AGP would otherwise
    // try to pull build-tools 34.0.0 from dl.google.com, which is very slow here.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.hermes.android"
        minSdk = 31
        targetSdk = 35
        versionCode = versionParts
            ?.let { (it.getOrNull(0) ?: 0) * 10_000 + (it.getOrNull(1) ?: 0) * 100 + (it.getOrNull(2) ?: 0) }
            ?.coerceAtLeast(1)
            ?: 1
        versionName = releaseVersion ?: "0.1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.mockito:mockito-core:5.14.2")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
}
