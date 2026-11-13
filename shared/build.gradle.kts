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
        compileSdk = 35
        minSdk = 28
        // commonTest also runs as Android host tests (`:shared:testAndroidHostTest`), checking the Android actuals.
        withHostTest {}
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
