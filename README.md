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

Release 0.3.0: `androidApp-release.apk`, version code 3, minSdk 28 (Android 9), targetSdk 35.

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
allow ChatGPT plan usage when offered, then return to the app. The connected card shows the account and model
configuration instead of offering sign-in again. Tap **Escolher modelos e esforço** to choose the active provider,
search the account's models and set reasoning effort for each job. Lists load automatically; **Atualizar listas de
modelos** refreshes them. **Usar esta configuração em todas as tarefas** copies the selected provider, model and
options to all three jobs. Choices survive restarting the app. Switching provider clears the previous model and
options; switching model resets options to automatic.

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
plan allowance. The reader confirmed sign-in works on their phone; new model controls are covered locally with
synthetic catalogs and requests, and still need acceptance with real account capabilities. Shared inference logic
remains portable; the iOS app and its OAuth host are not implemented.

## API keys and model options

Claude and OpenCode Console have dedicated cards with service icons, a link to obtain a key, and the same job
configuration controls. Their official API roots are prefilled and editable: `https://api.anthropic.com/v1` and
`https://opencode.ai/inference`. OpenAI, DeepSeek and custom compatible services remain under **Outros serviços e
configuração da API**, with editable presets. Keys are encrypted on the device; no computer or bridge is needed.
Connecting a provider does not select it for every job: the active provider is shown in each job's configuration.

Claude uses separately billed Anthropic API access. Its paginated model catalog supplies display names and effort
capabilities; modern effort uses `output_config.effort`, while saved legacy choices retain thinking budgets.
Fast mode is offered for the generally available model IDs documented by Anthropic (Opus 5.5, Fable 5.2, Mythos 5.2)
and uses `speed: fast`. Preview/deprecated fast-mode beta contracts are not enabled.

OpenCode uses the [public Console inference API](https://opencode.ai/docs/console/harnesses/) with a personal API
key and paid account credits. Subscription OAuth, Go and models restricted to OpenCode's own free tier are not
offered here. The adapter uses the edited root for `/v1/models` and `/openai/v1/chat/completions` and identifies
this app in its user agent.

**Automático** omits explicit effort/speed and lets the service choose. Known effort levels are shown from catalog
metadata. If a compatible service omits capability metadata, the UI explains that the generic low/medium/high
choices may not be accepted by that model. Manual model IDs remain available when discovery is unavailable.
ChatGPT speed tiers are offered only when advertised by its catalog; other services without supported speed modes
show the default. Faster tiers can have separate charges and limits; they are not a promise of account access.

Service icons derive from the [OpenAI sign-in devkit](https://github.com/openai/sign-in-with-chatgpt-devkit) and
the [OpenCode provider assets](https://github.com/anomalyco/opencode/tree/dev/packages/ui/src/assets/provider).
