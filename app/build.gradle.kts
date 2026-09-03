import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing.
 *
 * Read from `keystore.properties` (git-ignored) or from environment variables so CI
 * can sign without a file on disk. When neither is present the release build is left
 * unsigned rather than falling back to the debug key -- a debug-signed "release" that
 * installs fine locally and is rejected by Play is a genuinely expensive mistake.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(key: String, env: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(env)

val hasSigningConfig = signingValue("storeFile", "TARACORE_KEYSTORE") != null

android {
    namespace = "dev.taracore.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.taracore"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // :engine restricts its own CMake build to arm64, but that says nothing
            // about what :app packages. AndroidX ships prebuilt .so files for all
            // four ABIs, so without this the release artefact advertised
            // armeabi-v7a, x86 and x86_64 as supported -- ABIs that carry no
            // libtaracore_jni.so at all. Play would then offer the app to 32-bit ARM
            // and x86 devices where System.loadLibrary fails and there is no engine,
            // which is worse than simply not being listed for them.
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // VERSION_NAME is read by the update checker to decide whether a GitHub release
    // is newer, so it must stay a plain dotted version.

    flavorDimensions += "backend"
    productFlavors {
        create("cpu") {
            dimension = "backend"
            buildConfigField("String", "BACKEND_FLAVOUR", "\"cpu\"")
        }
        create("gpu") {
            dimension = "backend"
            buildConfigField("String", "BACKEND_FLAVOUR", "\"gpu\"")
        }
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(signingValue("storeFile", "TARACORE_KEYSTORE")!!)
                storePassword = signingValue("storePassword", "TARACORE_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "TARACORE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "TARACORE_KEY_PASSWORD")
                // v1 off: minSdk 26 means every target device honours v2/v3, and v1
                // signatures are the ones that carry the zip-entry weaknesses.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // No applicationIdSuffix: the service is bound by other apps using a
            // fixed package name, so a suffixed debug build would be invisible to
            // every client and to the sample app.
            isDebuggable = true
            ndk {
                // Emulator support, matching :engine's debug ABI. Never in release.
                abiFilters += "x86_64"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Keeps the native symbols out of the shipped artefact but uploadable to
            // Play, so a native crash in llama.cpp is still readable in Vitals.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
    }

    bundle {
        // One APK per ABI. The engine is arm64-only in release, so this mostly saves
        // users the Compose/resource duplication rather than native weight.
        abi { enableSplit = true }
        density { enableSplit = true }
        language {
            // Off: the app is English-only today, and splitting languages means a
            // user who switches locale gets an app with missing strings until Play
            // delivers the split.
            enableSplit = false
        }
    }

    dependenciesInfo {
        // Play requires the dependency blob for its own scanning; it is omitted from
        // the APKs we publish on GitHub so a sideloaded build carries no opaque
        // encrypted section.
        includeInApk = false
        includeInBundle = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        // Release-blocking correctness only; style nits are for review, not CI.
        checkReleaseBuilds = true
        disable += setOf("GradleDependency", "OldTargetApi")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    sourceSets { getByName("main") { kotlin.srcDir("src/main/kotlin") } }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
            )
        }
    }
}

dependencies {
    implementation(project(":service"))
    // The Playground talks to the service through the public SDK rather than
    // reaching into :service directly, so the integration path other apps use is
    // the one we exercise every day.
    implementation(project(":client-sdk"))
    implementation(project(":api"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // The update check: one unauthenticated GET of GitHub's public releases endpoint.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
