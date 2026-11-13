// Shared logic: Kotlin Multiplatform with an Android target and a JVM target.
// The JVM target exists so the logic is tested on the development machine (`gradlew :shared:jvmTest`).
// No Android UI code here: this module must stay usable from iOS later.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    jvmToolchain(17)

    // Android target (AGP's com.android.kotlin.multiplatform.library; replaces androidTarget() from AGP 9 on).
    android {
        namespace = "dobra.shared"
        compileSdk = 37
        minSdk = 28
        // commonTest also runs as Android host tests (`:shared:testAndroidHostTest`), checking the Android actuals.
        withHostTest {}
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
            api(libs.androidx.sqlite)
            implementation(libs.androidx.sqlite.bundled)
            // The collector engine (dobra.collect.engine): QuickJS, see AGENTS.md "Collector engine".
            implementation(libs.quickjs.kt)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Android host tests run on the desktop JVM, which cannot load QuickJS's Android native library: they get the
// -jvm artifact (desktop natives) instead, as quickjs-kt's README prescribes for local unit tests.
configurations.matching { it.name.contains("HostTest") && it.name.endsWith("RuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module(libs.quickjs.kt.jvm.get().toString()))
    }
}
