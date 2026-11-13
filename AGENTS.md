# Primeira Dobra

## Purpose
This is the product repository of `morning-edition`: only what the project needs to build, run, test and ship.
It builds "Primeira Dobra", a personal magazine app for one reader, on Android. The phone itself collects sources,
asks an AI to write the items, validates them against the magazine's format and shows the edition. There is no
server.

## Architecture
A Kotlin Multiplatform project with two modules: `shared` holds all logic (Android and JVM targets, no UI), and
`androidApp` is the Android application whose Jetpack Compose screens call into `shared`.

- `shared/` (package root `dobra`): model, format, seed, store, reader, collect, ai, make under
  `src/commonMain/kotlin/dobra/`. Targets: Android (AGP's `com.android.kotlin.multiplatform.library`, the AGP 9
  replacement for `androidTarget()`) and `jvm()`. The JVM target exists so logic is tested on the development
  machine. Keep it free of Android UI code so it can serve iOS later. `seed/` holds the demonstration magazine and
  edition as JSON.
- `androidApp/` (namespace `dobra.app`, application id `app.primeiradobra`): one folder per feature under
  `src/main/kotlin/dobra/app/` (shell, edition, turn, library, format, saved, sources, settings, ai, ask, work).
  `shell/MainActivity.kt` is the only activity. `shell/AppContainer.kt` is the one place that creates and hands out
  repositories, the worker and the AI jobs (no dependency-injection framework). `shell/AppNav.kt` holds the routes.
- Central files several features touch: `gradle/libs.versions.toml`, `shared/build.gradle.kts`,
  `androidApp/build.gradle.kts`, `androidApp/src/main/AndroidManifest.xml`, `shell/AppNav.kt`, `shell/AppContainer.kt`.
  Add your lines to them; do not reorganise them.

## Conventions
- Kotlin, official code style (`kotlin.code.style=official`), 4-space indentation, 120-column lines.
- Code, identifiers, comments, commit messages and documents are English. Text the reader sees is Brazilian
  Portuguese, plain and didactic, and lives in Android string resources: one file per feature,
  `androidApp/src/main/res/values/strings_<feature>.xml`, with names prefixed by the feature.
- Every library and plugin version is pinned in `gradle/libs.versions.toml`; build files reference the catalog only.
  The header of that file lists the constraints the versions were chosen against (JDK 17, compileSdk 35); look up
  versions in official documentation, never from memory.
- SDK levels: compileSdk 35, targetSdk 35, minSdk 28. JVM target 17 (`jvmToolchain(17)`).
- Build only with the Gradle wrapper from the repository root, one build at a time (the machine is shared).
  `gradle.properties` keeps the daemon at `-Xmx3g` and compiles Kotlin in-process.
- Tests:
  - Logic: `shared/src/commonTest` (and `jvmTest` for JVM-only tests), run with `gradlew :shared:jvmTest`.
    `commonTest` also runs as Android host tests (`gradlew :shared:testAndroidHostTest`).
  - Screens: Compose UI tests under Robolectric in `androidApp/src/test`, run with
    `gradlew :androidApp:testDebugUnitTest`. Use `androidx.compose.ui.test.junit4.v2` rules.
  - Tests never touch the live network or a live AI provider: collectors run against saved pages and AI answers are
    recorded once and replayed. Robolectric runs offline: its Android SDK jar is a Gradle dependency
    (`robolectric-android-all-sdk35` in the catalog), copied to `androidApp/build/robolectric-android-all`.
  - Done means `gradlew :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug` passes.
- No credential in the repository, a log or a test. Configuration secrets come from outside the repository;
  `.env.example` lists the names. `local.properties` (SDK path) is machine-specific and not versioned.
- The manifest requests a permission only when a feature needs it.

## Forbidden
- Creating new top-level folders without being asked.
- Creating `.md` files other than `README.md`, `AGENTS.md` (root and folders with their own rules) and `WORKSPACE.md` (root only).
- Saving AI working artifacts here (plans, ADR drafts, mocks, logs, handoffs, helper scripts, `.scratch/`). They live in the container's work folder.
- Versioned copies of files (`_v2`, `_final`, `_old`, `_copy`, `.bak`).
- Committing build output (`build/`, `.gradle/`, `.kotlin/`, APKs).

See `WORKSPACE.md` for where everything lives.
