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

Release 0.2.0: `androidApp-release.apk`, version code 2, minSdk 28 (Android 9), targetSdk 35.

## Install the release APK on a phone

1. Copy `androidApp-release.apk` to the phone (USB cable, a cloud drive, or e-mail to yourself).
2. On the phone, open the file from the Files app. Android asks to allow installing apps from that source
   (Settings › Apps › Special app access › Install unknown apps): allow it for the app you opened the file with.
3. Confirm "Install". Play Protect may warn that the app is from an unknown developer: choose to install anyway.
4. Open "Primeira Dobra". In Ajustes › Provedores de IA, connect ChatGPT or add an AI provider with its API key.

With a computer and USB debugging on: `adb install -r androidApp/build/outputs/apk/release/androidApp-release.apk`.

An update must be signed with the same key, so keep the secrets folder; a build signed with another key cannot be
installed over this one without uninstalling it first (which deletes the app's data).

## ChatGPT sign-in (0.2.0)

In Ajustes › Provedores de IA, tap **Continuar com ChatGPT**. Finish authorization in the system browser,
allow ChatGPT plan usage when offered, then return to the app. Select ChatGPT for each AI job and tap
**Listar modelos do provedor** to choose a model available to the connected account. API-key providers remain
available alongside ChatGPT, and each job can use a different provider.

This uses the [official public OAuth flow](https://developers.openai.com/siwc/token-sharing-open-source/sign-in),
with PKCE, state, nonce and signed ID-token validation. One account can be connected at a time on this device.
Tokens are encrypted using AndroidKeyStore in private, non-backup storage; expiring access tokens are refreshed
with serialized, rotating refresh-token writes. Cancel closes the pending callback. Remove signs out and attempts
remote revocation; a warning explains when remote revocation could not be confirmed. The account registration and
host identifier are retained for subsequent authorization. Tokens and authorization URLs are never logged.

ChatGPT requests use the public Responses endpoint with `store: false` and `stream: true`; interrupted or failed
streams are rejected. Plan eligibility, account model access and shared plan limits apply, as described in the
[preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).
The usage button opens ChatGPT's usage and permission settings; the app's token ledger does not estimate remaining
plan allowance. OAuth and AndroidKeyStore still need acceptance on a real phone; local tests use synthetic tokens
and a loopback callback. Shared inference logic remains portable; the iOS app and its OAuth host are not implemented.
