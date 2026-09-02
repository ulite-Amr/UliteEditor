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

    testOptions {
        unitTests {
            // Robolectric needs the APK resources/manifest to load the app's
            // themes and set up the Compose text environment on the JVM.
            isIncludeAndroidResources = true
        }
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
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    // The UniFFI-generated Kotlin bridge is built on JNA (the @aar packaging
    // bundles the Android natives JNA needs at runtime).
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    testImplementation(libs.junit)
    // Robolectric runs a real Android/Compose text layout on the JVM so these
    // tests can exercise caretXIn's pixel formula against an actual
    // TextLayoutResult (the pure-JVM tests cover only the classification
    // helpers; this beats the gap). @Config(sdk=[34]) keeps JDK 17 running.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test)
    debugImplementation(libs.compose.ui.test.manifest)
}