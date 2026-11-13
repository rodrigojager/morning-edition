// The Android application: Jetpack Compose screens on top of the shared module.
// Kotlin is compiled by AGP's built-in Kotlin support (AGP 9+), so no kotlin-android plugin is applied.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dobra.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.primeiradobra"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    sourceSets {
        // The demonstration magazine and edition (JSON) live in shared/seed and ship as app assets.
        getByName("main").assets.srcDir("../shared/seed")
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged resources and manifest of the app.
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// Robolectric's Android SDK jar, resolved by Gradle (cached like any dependency) instead of being downloaded by
// Robolectric while the tests run. The tests run with robolectric.offline=true, so they never touch the network.
val robolectricAndroidAll: Configuration = configurations.create("robolectricAndroidAll") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    robolectricAndroidAll(libs.robolectric.android.all.sdk35)
}

val syncRobolectricAndroidAll = tasks.register<Sync>("syncRobolectricAndroidAll") {
    from(robolectricAndroidAll)
    into(layout.buildDirectory.dir("robolectric-android-all"))
}

tasks.withType<Test>().configureEach {
    dependsOn(syncRobolectricAndroidAll)
    val jarDir = layout.buildDirectory.dir("robolectric-android-all")
    inputs.files(robolectricAndroidAll).withPropertyName("robolectricAndroidAll")
    systemProperty("robolectric.offline", "true")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Drobolectric.dependency.dir=${jarDir.get().asFile.absolutePath}")
    })
    maxHeapSize = "2g"
}
