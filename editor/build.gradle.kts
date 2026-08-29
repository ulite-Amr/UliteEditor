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
            // The UniFFI bindings are Kotlin sources. With AGP 9's built-in
            // Kotlin they must be added to the AndroidSourceSet.kotlin set —
            // java dirs are no longer folded into Kotlin compilation and
            // kotlin.sourceSets is disallowed.
            kotlin.directories += "build/generated/ffi/kotlin"
            jniLibs.directories += "build/generated/ffi/jniLibs"
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