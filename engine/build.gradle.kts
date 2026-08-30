plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.taracore.engine"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                // Exceptions stay on: llama.cpp throws on malformed GGUF and we turn
                // that into a LoadResult at the JNI boundary. RTTI likewise.
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
            }
        }

        ndk {
            // arm64 is the only ABI any real Android phone needs for this workload.
            // x86_64 is added back for debug builds only, below, so the emulator works.
            abiFilters += "arm64-v8a"
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            isJniDebuggable = true
            ndk {
                // Emulator support. Guarded to debug so a release APK can never
                // carry a ~40 MB slice that no phone can execute.
                abiFilters += "x86_64"
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    flavorDimensions += "backend"
    productFlavors {
        create("cpu") {
            dimension = "backend"
            externalNativeBuild {
                cmake { arguments += listOf("-DTARACORE_VULKAN=OFF", "-DTARACORE_OPENCL=OFF") }
            }
        }
        create("gpu") {
            dimension = "backend"
            externalNativeBuild {
                // Needs a host-built vulkan-shaders-gen on PATH; see docs/SETUP.md.
                cmake { arguments += listOf("-DTARACORE_VULKAN=ON", "-DTARACORE_OPENCL=OFF") }
            }
        }
    }

    packaging {
        jniLibs {
            // Keep the .so uncompressed and page-aligned so the loader can mmap it,
            // which is what makes 16 KB page alignment meaningful.
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    sourceSets {
        getByName("main") { kotlin.srcDir("src/main/kotlin") }
        getByName("androidTest") { kotlin.srcDir("src/androidTest/kotlin") }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
