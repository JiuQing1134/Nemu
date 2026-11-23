import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Private release signing. keystore.properties + keystore/ are gitignored;
// the same pair is backed up outside the repo. A missing keystore fails
// assembleRelease/bundleRelease unless -PallowDebugRelease=true is passed
// explicitly for a local throwaway build.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val allowDebugRelease =
    providers.gradleProperty("allowDebugRelease").orNull == "true"
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.nemu.hibernate"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.nemu.dream"
        minSdk = 30
        targetSdk = 36
        versionCode = 9
        versionName = "1.3.3"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Optional ABI split for an x86_64 emulator build. The normal app is a
    // universal APK; pass -PnemuAbiSplits=true to emit arm64-v8a/x86_64 APKs.
    splits {
        abi {
            isEnable = providers.gradleProperty("nemuAbiSplits").orNull == "true"
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(
                    keystoreProperties.getProperty("storeFile")
                )
                storePassword =
                    keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Debug-only tooling must not ship in a root-capable build.
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                project.logger.warn(
                    "WARNING: release uses the public debug keystore. " +
                        "Configure keystore.properties for distribution."
                )
                signingConfigs.getByName("debug")
            }
        }
    }

    // Fail the actual release packaging, not every debug build. The release
    // build type is configured even for `assembleDebug`, so a configuration
    // throw would break all local development.
    project.tasks.matching {
        !keystorePropertiesFile.exists() && !allowDebugRelease &&
            (it.name == "assembleRelease" ||
                it.name == "bundleRelease" ||
                it.name.startsWith("packageRelease"))
    }.configureEach {
        doFirst {
            throw GradleException(
                "Release signing is not configured. Create keystore.properties " +
                    "(storeFile/storePassword/keyAlias/keyPassword) or pass " +
                    "-PallowDebugRelease=true for a throwaway debug-signed build."
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
