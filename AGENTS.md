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

## Collector engine
Collectors (one TypeScript script per source) run in `shared/.../dobra/collect/engine` (`CollectorEngine`).

- **Engine: QuickJS through quickjs-kt** (`io.github.dokar3:quickjs-kt`, pinned in the catalog). Chosen because it is
  one Kotlin Multiplatform library for Android, the JVM (Windows, Linux and macOS natives) and Kotlin/Native iOS;
  the host defines functions the script calls (sync and suspending); it has a memory limit (`memoryLimit`) and
  stops a busy script when the calling coroutine is cancelled (`withTimeout`), and its runtime has no file, network
  or OS access (only the ECMAScript globals). Rejected: Rhino (JVM only, no iOS, no memory limit), Zipline (QuickJS too,
  but built to load signed Kotlin/JS modules, much more than a script runner needs), J2V8/Javet (large native V8 per ABI, no iOS), GraalJS (no Android).
- **Path to iOS:** add an iOS target to `shared`; quickjs-kt already ships the iOS artifacts, and the engine, the
  type stripper, the keyword matcher and the record checks are common code, so they move unchanged. (The system's
  JavaScriptCore would be the alternative behind the same `CollectorEngine` interface if QuickJS ever had to go.)
- **TypeScript rule: collectors are erasable TypeScript** (the subset of TypeScript's `erasableSyntaxOnly`). The
  app removes the types itself, in common code (`TypeStripper`), before the engine runs the script; removed text
  becomes spaces, so error lines and columns match the TypeScript. `enum`, `namespace`, parameter properties,
  decorators, `<T>value` casts, JSX, `import` and export lists are refused with their position. Types inside
  template substitutions (`${...}`) are not removed.
- **Contract:** the script defines `collect(input)` (may be `async`) with `input = { url, keywords, cursor }` and
  returns `{ records, cursor }`; a record is `{ title, link, date, rawText, signal?, media? }` (`date` and `rawText`
  are strings, `""` when the site has none). No cursor in the result keeps the previous one; `null` resets it.
- **Sandbox:** a fresh runtime per run. The only way out is `fetch(address)`, which resolves relative addresses
  against the source and reaches only the source's host and its subdomains (a leading `www.` is ignored), through
  the `FetchTransport` the caller gives (tests use `SavedPages`; no test touches the network). A refused address
  fails the run even if the script caught the error. `Date.now()`, `new Date()` without arguments and
  `Math.random()` throw; `WeakRef` and `FinalizationRegistry` are removed. Limits per run (`CollectorLimits`): time,
  memory, stack, characters of the result, number of records, number of fetches, size of a response.
- **Output:** checked against the record shape (bad records are refused with the reason and never used), then kept
  only when a keyword appears word for word in the title or text, ignoring case and accents (`KeywordMatcher`).
- Saved pages and hand-written test collectors live in `shared/fixtures/collect` (invented content on
  `example.org`).
- Android host tests swap `quickjs-kt-android` for `quickjs-kt-jvm` (see `shared/build.gradle.kts`): a desktop JVM
  cannot load the Android native library. A Robolectric test in `androidApp` that runs a collector needs the same
  substitution in `androidApp/build.gradle.kts`.

## Editions on schedule

- **Library: WorkManager** (`androidx.work`, pinned in `gradle/libs.versions.toml`), the platform's library for
  deferrable background work that must run with the app closed and survive reboots. Code in `androidApp/.../work`.
- **One unique periodic job per magazine** (`Generation.sync`, unique name `edition-<magazine id>`): every 1 day
  (daily) or 7 days (weekly), first run at the cadence's hour. A job is kept while its cadence and conditions are the
  same and replaced (`CANCEL_AND_REENQUEUE`) when either changes; deleting a magazine cancels its job.
  `AppContainer.startSchedules` follows the store's magazines.
- **Conditions**: the reader's two options become the job's constraints: "Só no Wi-Fi" = unmetered network (else any
  connected network), "Só carregando" = requires charging. The system may delay the job (Doze, battery saving); that
  is accepted and nothing tries to defeat it.
- **Long-running job**: making an edition means many fetches and AI calls and may pass WorkManager's 10-minute limit
  for a plain job, so `EditionWorker` runs as a long-running job (`setForegroundAsync`, foreground service type
  `dataSync`, declared in the manifest) with a progress notification. When Android does not allow starting a
  foreground service from the background, the job continues as a plain job.
- **Same making as "Gerar edição agora"**: the job calls `MakeRunner.run`, the same path the button uses.
- **Notifications**: progress while it runs; "Edição pronta" when an edition was stored, opening it in the reader
  (`EditionNotices.EXTRA_OPEN_EDITION` read by `MainActivity`). The permission to notify (Android 13+) is asked once,
  the first time the reader opens "Geração de edições"; without it, editions are still made silently.

## Conventions
- Kotlin, official code style (`kotlin.code.style=official`), 4-space indentation, 120-column lines.
- Code, identifiers, comments, commit messages and documents are English. Text the reader sees is Brazilian
  Portuguese, plain and didactic, and lives in Android string resources: one file per feature,
  `androidApp/src/main/res/values/strings_<feature>.xml`, with names prefixed by the feature.
- Every library and plugin version is pinned in `gradle/libs.versions.toml`; build files reference the catalog only.
  The header of that file lists the constraints the versions were chosen against (JDK 17, compileSdk 37); look up
  versions in official documentation, never from memory.
- SDK levels: compileSdk 37, targetSdk 35, minSdk 28. compileSdk is 37 only because quickjs-kt 1.0.18 (the collector
  engine) declares minCompileSdk 37 in its AAR metadata; targetSdk stays 35. JVM target 17 (`jvmToolchain(17)`).
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
