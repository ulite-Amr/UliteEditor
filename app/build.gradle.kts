import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.uliteeditor.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.uliteeditor.app"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // Build provenance surfaced in the UI (see MainActivity status bar):
        // the exact git commit this APK was built from and whether the tree
        // was dirty, so a device report can be tied to source unambiguously.
        // Failure to read git (CI checkout, missing .git) falls back to
        // "unknown" rather than breaking the build.
        val gitSha = try {
            providers.exec {
                commandLine("git", "rev-parse", "--short", "HEAD")
            }.standardOutput.asText.get().trim()
        } catch (_: Exception) { "unknown" }
        val gitDirty = try {
            providers.exec {
                commandLine("git", "status", "--porcelain")
            }.standardOutput.asText.get().isNotBlank()
        } catch (_: Exception) { false }
        buildConfigField("String", "GIT_SHA", "\"${gitSha}\"")
        buildConfigField("boolean", "GIT_DIRTY", "${gitDirty}")
    }

    signingConfigs {
        // Real release key, provisioned by the CI keystore-create job and
        // passed via repository secrets (base64 in RELEASE_KEYSTORE_B64). The
        // file only exists on the CI runner / in secret storage — never in
        // the repo. Absent it (PRs from forks, local dev), release still
        // builds, debug-signed, so key material never blocks a build.
        val releaseKeyPath = System.getenv("RELEASE_KEYSTORE_PATH")
        if (!releaseKeyPath.isNullOrEmpty() && File(releaseKeyPath).isFile) {
            create("release") {
                storeFile = File(releaseKeyPath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD").orEmpty()
                keyAlias = System.getenv("RELEASE_KEY_ALIAS").orEmpty()
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD").orEmpty()
            }
        }
    }

    buildTypes {
        release {
            // Testing-speed release: R8 on, real signing when the CI secret
            // keystore is present, otherwise the debug key so PRs and local
            // runs never need key material, and never published to any store.
            // The "-test" suffix separates these builds from a future
            // publishable release.
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            versionNameSuffix = "-test"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":editor"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)
}