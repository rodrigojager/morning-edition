# Primeira Dobra

A personal magazine app for one reader, on Android. The reader owns each magazine's recipe (subjects, sources,
keywords, instructions for the AI, accepted block types, looks, cadence); the phone collects the sources, has an AI
write and validate the items, and shows the edition as a book, as single sheets or as one scroll. No server.

## Requirements

JDK 17 and the Android SDK. The build compiles against platform 37; the Android Gradle Plugin installs it on the
first build if it is missing. Point Gradle at the SDK with `ANDROID_HOME` or a `local.properties` file containing
`sdk.dir=<path to the SDK>`.

## Build

```
gradlew :androidApp:assembleDebug        # debug APK:   androidApp/build/outputs/apk/debug/androidApp-debug.apk
gradlew :androidApp:assembleRelease      # release APK: androidApp/build/outputs/apk/release/androidApp-release.apk
gradlew :shared:jvmTest                  # logic tests on the JVM
gradlew :androidApp:testDebugUnitTest    # screen tests (Compose under Robolectric)
```

The release APK is minified, optimized and resource-shrunk by R8 (rules in `androidApp/proguard-rules.pro`) and
signed with the release key. The key is never in this repository: the build reads a folder holding
`release-signing.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) and the PKCS12 keystore it
names. The folder is taken from the environment variable `DOBRA_SECRETS`, else the Gradle property
`dobra.secretsDir`, else `../_secrets` next to the repository. Without it the release build stops with a message
saying what is missing.

Release 0.1.0: `androidApp-release.apk`, about 14.4 MB, version 0.1.0, minSdk 28 (Android 9), targetSdk 35.

## Install the release APK on a phone

1. Copy `androidApp-release.apk` to the phone (USB cable, a cloud drive, or e-mail to yourself).
2. On the phone, open the file from the Files app. Android asks to allow installing apps from that source
   (Settings › Apps › Special app access › Install unknown apps): allow it for the app you opened the file with.
3. Confirm "Install". Play Protect may warn that the app is from an unknown developer: choose to install anyway.
4. Open "Primeira Dobra". To make editions, add an AI provider and its key in Ajustes › Provedores de IA.

With a computer and USB debugging on: `adb install androidApp/build/outputs/apk/release/androidApp-release.apk`.

An update must be signed with the same key, so keep the secrets folder; a build signed with another key cannot be
installed over this one without uninstalling it first (which deletes the app's data).
