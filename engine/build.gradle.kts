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
        getByName("androidTest") {
            kotlin.srcDir("src/androidTest/kotlin")
            // The GGUF is staged into the test APK rather than pushed to the device.
            // Gradle uninstalls the test package after every run, which wipes
            // /sdcard/Android/data/<pkg>/, so anything pushed beforehand is either
            // gone or owned by shell and invisible to the app. Shipping it inside the
            // APK sidesteps all of that and works identically on CI.
            assets.srcDir(layout.buildDirectory.dir("generated/androidTestAssets"))
        }
    }
}

/**
 * Copy whatever `scripts/fetch-model.sh` downloaded into the test APK's assets.
 *
 * No weights are committed; this stages them at build time if they happen to be
 * present. With no model on disk the task copies nothing, the assets directory is
 * empty, and every model-dependent test skips itself rather than failing.
 */
val stageTestModel by tasks.registering(Copy::class) {
    description = "Stages one downloaded GGUF into the androidTest assets."

    // Exactly one model, the smallest available. Staging every GGUF in build/models
    // produced a 1.5 GB test APK that took minutes to package and install, for tests
    // that only ever load one. Smallest wins because these tests check engine
    // behaviour, not answer quality.
    val modelsDir = rootProject.layout.buildDirectory.dir("models")
    val chosen = modelsDir.map { dir ->
        dir.asFile.listFiles { f -> f.isFile && f.extension == "gguf" }
            ?.minByOrNull { it.length() }
            ?.let { listOf(it) }
            ?: emptyList()
    }
    from(chosen)
    into(layout.buildDirectory.dir("generated/androidTestAssets/models"))
}

tasks.matching { it.name.startsWith("generate") && it.name.contains("AndroidTestAssets") }
    .configureEach { dependsOn(stageTestModel) }

tasks.matching { it.name.startsWith("merge") && it.name.contains("AndroidTestAssets") }
    .configureEach { dependsOn(stageTestModel) }

dependencies {
    implementation(libs.kotlinx.coroutines.android)

    // Test-only: the grammar tests build their GBNF with the same Gbnf helper that
    // clients use. Deliberately not a production dependency -- :engine must not
    // depend on :api, and nothing in the shipped AAR references it.
    androidTestImplementation(project(":api"))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
