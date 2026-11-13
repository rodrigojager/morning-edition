import java.util.Properties

// The Android application: Jetpack Compose screens on top of the shared module.
// Kotlin is compiled by AGP's built-in Kotlin support (AGP 9+), so no kotlin-android plugin is applied.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The release signing key lives outside the repository, in the container's secrets folder: a PKCS12 keystore and a
// properties file (storeFile, storePassword, keyAlias, keyPassword) beside it. The folder comes from the environment
// variable DOBRA_SECRETS, else the Gradle property dobra.secretsDir, else ../_secrets next to the repository.
val secretsDir: File = (System.getenv("DOBRA_SECRETS") ?: providers.gradleProperty("dobra.secretsDir").orNull)
    ?.let { file(it) } ?: rootProject.file("../_secrets")
val signingFile: File = File(secretsDir, "release-signing.properties")
val signing: Properties? = signingFile.takeIf { it.isFile }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "dobra.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.primeiradobra"
        minSdk = 28
        targetSdk = 35
        versionCode = 5
        versionName = "0.3.2"
    }

    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = File(secretsDir, signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signing != null) signingConfig = signingConfigs.getByName("release")
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
    // The device's fold, where the book's spine goes.
    implementation(libs.androidx.window)
    // Editions made in the background on each magazine's cadence.
    implementation(libs.androidx.work.runtime.ktx)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.window.testing)
    testImplementation(libs.androidx.work.testing)

    robolectricAndroidAll(libs.robolectric.android.all.sdk35)

    // Screen tests open the real store on the JVM: the bundled SQLite of the Android build only has phone libraries,
    // so the tests swap in the desktop build of the same library (same classes, desktop native files).
    testImplementation("androidx.sqlite:sqlite-bundled-jvm:${libs.versions.androidx.sqlite.get()}")
}

configurations.matching { it.name.startsWith("testDebugUnitTest") && it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
    exclude(group = "androidx.sqlite", module = "sqlite-bundled")
}

// A release build without the signing key fails here, with the reason, instead of producing an unsigned APK.
val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    val present = signing != null && File(secretsDir, signing.getProperty("storeFile").orEmpty()).isFile
    val where = signingFile.absolutePath
    doLast {
        if (!present) {
            throw GradleException(
                "Release signing key not found: expected $where and the keystore it names. " +
                    "Set DOBRA_SECRETS (or -Pdobra.secretsDir) to the folder that holds them.",
            )
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseSigning) }

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
