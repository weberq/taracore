plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

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
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

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

    buildTypes {
        debug {
            applicationIdSuffix = ""
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
