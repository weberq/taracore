plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "dev.taracore.client"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    sourceSets { getByName("main") { kotlin.srcDir("src/main/kotlin") } }

    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    // api, not implementation: consumers handle ModelInfo and GenerationResult
    // directly, so the contract types must be on their compile classpath.
    api(project(":api"))
    implementation(libs.kotlinx.coroutines.android)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "dev.taracore"
            artifactId = "client-sdk"
            version = "1.0.0"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Tara Core Client SDK")
                description.set("Kotlin client for the Tara Core on-device inference service.")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}
