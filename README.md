# Primeira Dobra

A personal magazine app for one reader, on Android. The reader owns each magazine's recipe (subjects, sources,
keywords, instructions for the AI, accepted block types, looks, cadence); the phone collects the sources, has an AI
write and validate the items, and shows the edition as a book, as single sheets or as one scroll. No server.

## Getting started

Requirements: JDK 17 and the Android SDK (platform 35). Point Gradle at the SDK with `ANDROID_HOME` or a
`local.properties` file containing `sdk.dir=<path to the SDK>`.

```
gradlew :androidApp:assembleDebug        # debug APK in androidApp/build/outputs/apk/debug/
gradlew :shared:jvmTest                  # logic tests on the JVM
gradlew :androidApp:testDebugUnitTest    # screen tests (Compose under Robolectric)
```

Install the debug APK on a device with `adb install androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
