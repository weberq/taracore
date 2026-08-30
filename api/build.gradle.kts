plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "dev.taracore.api"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir("src/main/kotlin")
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // Intentionally empty: :api is the wire contract and must stay dependency-free
    // so that any consumer can depend on it without dragging in transitive versions.
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "dev.taracore"
            artifactId = "api"
            version = "1.0.0"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Tara Core API")
                description.set("AIDL contract for binding to the Tara Core on-device inference service.")
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
