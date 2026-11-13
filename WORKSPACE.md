# Product map: morning-edition

One row per folder (and relevant subfolder) of this repository. Keep it portable: no absolute paths, disks or local-only folders.

| Path | What lives there |
|---|---|
| `gradle/` | Gradle wrapper and `libs.versions.toml` (every library and plugin version) |
| `shared/` | Kotlin Multiplatform library with all logic (Android and JVM targets), package root `dobra` |
| `shared/src/commonMain/` | shared logic |
| `shared/src/commonTest/`, `shared/src/jvmTest/` | logic tests (`gradlew :shared:jvmTest`) |
| `shared/seed/` | the demonstration magazine and edition as JSON |
| `androidApp/` | the Android application (Jetpack Compose), package `dobra.app` |
| `androidApp/src/main/kotlin/dobra/app/` | one folder per feature; `shell/` holds the activity, navigation and the app container |
| `androidApp/src/main/res/values/` | reader-facing strings, one `strings_<feature>.xml` per feature |
| `androidApp/src/test/` | screen tests under Robolectric (`gradlew :androidApp:testDebugUnitTest`) |

## Allowed Markdown files
- `README.md`
- `AGENTS.md` (root, and folders with their own rules, up to the second level)
- `WORKSPACE.md` (root only)

AI working artifacts live outside this repository, in the container's `_work/` folder.
