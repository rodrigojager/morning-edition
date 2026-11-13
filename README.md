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

Release 0.3.3: `androidApp-release.apk`, version code 6, minSdk 28 (Android 9), targetSdk 35.

## Magazine instructions and editions

Open a magazine in **Biblioteca**. **Gerar edição agora**, **Editar instruções da próxima edição** and the
edition list appear before the recipe's source cards. Each edition offers **Abrir**, a read/unread mark and
**Apagar edição**, with confirmation. Editing instructions saves the recipe for subsequent generations; it does
not rewrite existing editions. The dedicated instructions editor preserves the rest of the current recipe.

The library also offers generation and editing directly on each magazine card. A horizontal shelf of edition
cards opens saved editions immediately and shows unread badges. Small icon actions mark an edition read/unread or delete it with confirmation. Reading all stories also clears the unread badge. Subjects, keywords and detailed counts expand
under **Ver detalhes**. Generation stays visible when moving between the library and the magazine page, with a
spinner and source-count progress during collection, indeterminate selection, then article-count progress during
writing. The total number of articles is known only after selection; bars describe each phase, not estimated time.
Collector generation and format rewriting also show activity. The cover keeps its lead near the masthead, with
a source image when available or a locally drawn illustration; compact screens prioritize the lead text.

AI transport failures receive at most two additional attempts with short delays; authentication, rate limits,
invalid answers and cancellations are not automatically retried. If writing still fails, the omitted article's
reason explains that it may be selected in a subsequent edition. An omitted article is not marked as published.
Usage totals count received successful answers; a provider may still bill a request whose response was lost.

The starter recipe asks for practical tools, SDKs, CLIs, MCPs, skills, libraries, applications and services, excluding
opinion articles and predictions. Community, free and open-source projects are preferences, not requirements.
Instructions reach both AI steps: candidate selection and item writing. Explicit exclusions take priority over
ranking and feedback. These are AI decisions, so semantic compliance still needs review with the chosen live model.
An empty keyword list keeps all valid source records for AI curation; a nonempty list enables the literal keyword
prefilter. Keywords are optional and do not replace the instructions.

The contents shortcut in the reading toolbar opens the contents from any chapter. Both full articles and short
notes appear there and offer Ask, Save, More and Less actions, arranged in two consistent rows with icons.
In **Salvos**, **Ler só os salvos** belongs to the magazine and reads saved items across all its editions, keeping
other magazines separate. Saved copies remain available after their original edition is deleted.

The recipe form defaults to a desired minimum of **5** and a target of **10** items, both editable from 1 to 40.
Generation considers balanced batches of up to 160 records, up to three batches and three collection rounds in
total. When below target it consumes remaining candidates or follows explicit collector continuations. Duplicate
links, unchanged feeds and refused sources are not repeatedly fetched to fill the quota. It saves one final edition
and reports a shortfall when quality, source exhaustion or safety limits prevent reaching the target. This is a
bounded best effort, not a guarantee of five suitable items.

**Mais disso** and **Menos disso** are per-magazine preferences. Up to ten titles marked each way are included in
both AI prompts, together with the recipe and matching discovery-source names. Each source's “what to look for” description is included too. They influence subsequent editions, do not train a model or change
collectors, and cannot override explicit exclusions. A mark only supplies an example while its edition remains
stored, because that edition supplies the title.

## Starter collectors and collector generation

The first bundled edition is a manually curated demonstration. It was not produced by saved collectors. Version
0.3.1 also bundles collectors and installs missing ones on existing copies of the starter magazine, preserving
instructions, custom collectors, changed source addresses and deleted sources/editions. Nine sources produce valid
records in saved-response tests. Version 0.3.3 upgrades unchanged bundled collectors: GitHub Trending merges daily
and weekly repositories and can continue with monthly results; Hacker News continues with public pages 2 and 3;
DEV reads up to three pages of 40 entries. Custom collectors and changed URLs remain untouched. Live Apiiro
requests currently return HTTP 403, and a saved JavaScript shell has no readable article links; its
collector is marked **em reparo** and fails explicitly rather than supplying invented items. Python forum collection
currently reads the Python feed; the older demonstration also contained manually selected Rust material. Reddit
subfeeds can refuse access individually, and live source availability is not guaranteed by saved-response tests.

ChatGPT collector generation uses the saved OAuth account and the model/effort selected for the collector job;
it does not require an API key. The **Usar esta configuração em todas as tarefas** action also configures that job.

Before asking the AI to write a collector, Kotlin code discovers feed/JSON links explicitly announced by the page
and reads at most two allowed addresses once each. Bounded evidence includes those responses and embedded JSON,
even when it occurs beyond the raw HTML prefix shown to the AI. The existing source-domain restriction remains.
Discovery does not guess endpoints, execute site JavaScript or automate a browser. HTTP 403/429 stops discovery.
The collector prompt also incorporates the observed-endpoint and capture-reuse approach explored in
[Reverse API Engineer](https://github.com/kalil0321/reverse-api-engineer): inspect actual response fields and
explicit next links, validate real items rather than HTTP success, and repair from the same saved evidence.
This is a prompt adaptation, without installing its Python/browser tooling or copying session credentials.
If acceptance finds a new response while warming up a generated script, the next repair prompt includes that
response and the actual failure. Scripts are saved only after two matching, valid runs against the saved copy.

This adapts the observed-response and replay-validation ideas described by
[api-anything](https://github.com/goodnight000/api-anything/blob/main/docs/REFERENCE.md) to the existing Kotlin and
QuickJS implementation. It adds no runtime, package dependency or language.

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
