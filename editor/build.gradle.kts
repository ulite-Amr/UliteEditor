import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.uliteeditor.editor"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    sourceSets {
        getByName("main") {
            // Consumes the UniFFI bridge output produced by
            // scripts/build-ffi.sh: Kotlin bindings under the FFI package
            // dir, and the per-ABI cdylib (libulite_editor_core.so).
            java.srcDir("build/generated/ffi/kotlin")
            jniLibs.srcDir("build/generated/ffi/jniLibs")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Path-scopes NewApi off the UniFFI-generated bindings (see lint.xml).
        lintConfig = file("lint.xml")
    }
}

kotlin {
    sourceSets {
        // The UniFFI bindings are Kotlin sources; AGP's built-in Kotlin
        // doesn't follow the java source dir added above, so the bridge
        // package must be declared as a Kotlin source dir directly.
        getByName("main") {
            kotlin.srcDir("build/generated/ffi/kotlin")
        }
    }

    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    testImplementation(libs.junit)
}