plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.vela.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.vela.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.12.1"

        // The mod menu ABI ships GlossHook for these two only, and these are the
        // ABIs real phones run. Building x86 too would fail the SDK's own check
        // and double the APK for no one.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // A fixed debug keystore, committed to the repository.
    //
    // Android refuses to install an update signed by a different key, so a
    // per-run generated debug keystore makes every build un-installable over the
    // previous one. This key is a throwaway: it must never sign a release.
    signingConfigs {
        create("fixedDebug") {
            storeFile = rootProject.file("ci/debug.keystore")
            storePassword = "veladebug"
            keyAlias = "veladebug"
            keyPassword = "veladebug"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixedDebug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // org.json is part of Android, so the platform jar used for unit
            // tests only has stubs. The real implementation is added below.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// Print each test by name, so the CI log is the evidence rather than a claim.
tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
