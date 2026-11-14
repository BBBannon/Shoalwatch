# OfflineFin architecture

Full design lives in `SPEC.md`. This page covers build, package map and version checks.

## Toolchain and build

| Item | Value |
|---|---|
| JDK | 17 (`C:\Users\Ben\Android\jdk17\jdk-17.0.20.1+1`), set `JAVA_HOME` for every Gradle call |
| Android SDK | `C:\Users\Ben\Android\sdk` (platform 35 only), path in git-ignored `local.properties` |
| Gradle / AGP / Kotlin | 8.11.1 / 8.10.1 / 2.2.21 (KSP 2.2.21-2.0.5) |
| SDK levels | `minSdk 29`, `compileSdk 35`, `targetSdk 35` |

```
./gradlew.bat testDebugUnitTest assembleDebug     # unit tests + debug APK
./gradlew.bat assembleRelease                      # R8 minified; signed only if keystore.properties exists
```

- **Dependency verification** is on: `gradle/verification-metadata.xml` (sha256). After adding or bumping a dependency, regenerate with
  `./gradlew.bat --write-verification-metadata sha256 help testDebugUnitTest assembleDebug assembleDebugAndroidTest` and review the diff (the Android-test variant is included so its dependencies are verified too).
- **Release signing** reads an optional, git-ignored `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Absent file = unsigned release, build still configures.
- **Cleartext**: the manifest allows it only because opt-in is per server and enforced in code by `CleartextGuard` (`net/CleartextGuard`). Guards throw `BlockedRequestException` (an IOException carrying an `AppError`; `AppError.from` unwraps it), because OkHttp's `enqueue` turns any other throwable into a generic IOException. Allowed hosts are `host:port` authorities (`ServerAddress.authority`). No `network_security_config`, so user CAs are not trusted.

## Package map (SPEC section 5)

```
app.offlinefin
├── OfflineFinApp, AppGraph
├── core/        AppError, Ticks, dispatchers, logging (redacting)
├── security/    SecretBox (Keystore AES-GCM), AppLock, redaction helpers
├── net/         HttpClientFactory, TrustPolicy (ServerNetConfig), PinnedTrustManager (+ PinAwareHostnameVerifier), KeyChainKeyManager, CertificateInfo,
│                AuthInterceptor, CustomHeaderInterceptor, AuthHeader, BlockedRequestException, CleartextGuard, ServerAddress, NetworkMonitor (+ AndroidNetworkMonitor)
├── data/
│   ├── db/          AppDatabase (Room, `offlinefin.db`), ServerEntity, AccountEntity, ActiveAccountEntity, DAOs
│   ├── servers/     Server, Account, ServerStore, ServerNet (+ ServerNetDraft), DeviceIdentity, JellyfinGateway(+Impl), AuthRepository, QuickConnectState, AddressSelector (+ Connection)
│   ├── library/ images/ playback/ downloads/ sync/
└── ui/          theme, nav, components, one package per screen group (see "UI" below)
```

Only `net/HttpClientFactory` may construct an `OkHttpClient`.

## Network security model

- **Who is trusted:** the platform's own certificate authorities only. User-installed CAs are not trusted (no `network_security_config`).
- **Self-signed servers:** accepted only if the leaf certificate's public key (SPKI SHA-256, base64) equals the pin the user approved. A pin is checked only when system trust fails; a mismatch raises `PinMismatchException`, which `AppError.from` maps to `TLS_PIN_CHANGED`. Pin comparison is constant-time.
- **Hostnames:** normal hostname verification always runs first, so a system-trusted certificate for a different host is refused even on a pinned client. Only if that fails does a pinned client accept, and only when the peer's leaf key is exactly the pinned key (LAN certificates often lack a matching name).
- **TLS versions:** 1.2 and 1.3 only. Plain `http` is allowed by the connection spec but policed per server by `CleartextGuard`.
- **Redirects:** `CleartextGuard`, `CustomHeaderInterceptor` and `AuthInterceptor` run as both application and network interceptors; `followSslRedirects` is off. The token and custom headers only reach the server's own `host:port` authorities.
- **First contact:** `probeCertificate` handshakes with a capture-only trust manager that always fails after recording the leaf, so no request, token or header is sent on the unverified connection. It uses no `clientFor` client. The whole probe has a hard 10 s deadline: on expiry or cancellation its socket is closed (an uninterruptible DNS lookup may leave one daemon thread behind until it returns, but the caller is released).
- **Client certificates:** `KeyChainKeyManager` offers only the stored alias; `KeyChain` calls block and refuse the main thread; a synchronous `execute()` runs the handshake on the caller's thread, so safety rests on every network call in this app running off the main thread. Any KeyChain failure presents no certificate.
- **Clients:** all share one connection pool and dispatcher; no cache, no cookies; connect 10 s, read/write 30 s, no call timeout (it would cut long downloads). Callers hold one client per server configuration (each `clientFor` builds a new SSLContext; no cache).

## Data model (servers and accounts)

- **Tables:** `servers` (id, name, version, home/away address strings, allowCleartext, pinnedSpkiSha256, clientCertAlias, customHeaders BLOB), `accounts` (PK serverId+userId, userName, accessToken BLOB), `active_account` (single row, `slot = 0`).
- **Secrets at rest:** access tokens and custom headers are stored only as `SecretBox` ciphertext in BLOB columns (tests assert the raw bytes contain neither). An account whose token cannot be decrypted (lost Keystore key) is hidden so the user signs in again; unreadable headers load as none.
- **Integrity:** accounts cascade-delete with their server; `active_account` cascades from the account, so removing the active account (or its server) leaves nobody active, and the single-row key means there can never be two. Writes use `@Upsert`, never `REPLACE` (REPLACE deletes first and would cascade away accounts). No destructive-migration fallback; the schema is exported to `app/schemas/`.
- **Pins:** `upsertServer` rejects a malformed pin (`IllegalArgumentException`, fixed message). A damaged pin already in the database loads as `pinnedSpkiSha256 = null` with `Server.pinInvalid = true`; `ServerNet.config` then throws `TLS_UNTRUSTED`, so the next connection prompts for re-trust rather than silently falling back to system trust. Saving other edits to such a server keeps the damaged column untouched.
- **One place for security settings:** `ServerNet.config(addresses, draft-or-Server, authHeader)` is the only code that turns a server's settings into a `ServerNetConfig`.
- **Device identity:** random UUID generated once, kept in private SharedPreferences (excluded from backup); client name `OfflineFin`, version from `BuildConfig.VERSION_NAME`, device name from `Build.MODEL` sanitised.

## Jellyfin client decision (SDK vs direct)

**Decision: `JellyfinGateway` is implemented directly on OkHttp + kotlinx.serialization; the Jellyfin Kotlin SDK is not used for any request.**

- AAFin's `JellyfinClients.kt` shows `OkHttpFactory(httpClient)` does accept an injected `OkHttpClient`, so injection is possible. But the SDK builds its own requests and applies its own authorisation and client options (its sources were not audited for this task), and proving that *every* byte of SDK traffic passes our guards and trust policy would need an audit of the SDK internals on each upgrade.
- The sign-in surface is seven small endpoints, so a direct implementation is cheaper than that audit and gives one auditable HTTP path: every call goes through `HttpClientFactory.clientFor(config)`, so the cleartext, header and token guards, the trust policy and the pin all apply.
- Endpoints: `GET /System/Info/Public`, `GET /QuickConnect/Enabled`, `POST /QuickConnect/Initiate`, `GET /QuickConnect/Connect?secret=`, `POST /Users/AuthenticateWithQuickConnect`, `POST /Users/AuthenticateByName`, `POST /Sessions/Logout`. Unauthenticated calls send `Authorization: MediaBrowser Client, Device, DeviceId, Version` without a token (the interceptor applies it from the config).
- **Untrusted responses (S11):** bodies over 1 MiB are refused, JSON is decoded with `ignoreUnknownKeys`, and a missing token or user id is an error. Error text never includes response content.
- **Documented exception to "no secrets in URLs":** the Quick Connect `secret` is sent as a query parameter for the `Connect` poll because the Jellyfin API requires it. It is short-lived, never logged, and the client logs no URLs.
- **Server identity at sign-in:** before any credential is sent (password) or Quick Connect starts, `AuthRepository` calls `publicInfo(via)` and requires its id to equal `server.id` (case and dashes ignored, empty never matches); after authenticating it requires `AuthResult.serverId` to match too. Either mismatch is `SERVER_MISMATCH` and nothing is stored, so a wrong away address, DNS trick or captive portal cannot mint a token saved under the wrong server.
- **No redirects on API calls:** `ServerClients` derives every client with `followRedirects(false)` (sharing the pool), because passwords and Quick Connect secrets sit in bodies and queries that OkHttp would resend on a 307/308. Any 3xx is an error (`SERVER_NOT_FOUND`).
- **Client holder and eviction:** `ServerClients` (data/servers) holds one client per configuration. `ServerNetConfig` equality is by value (authorities, cleartext flag, pin, client-cert alias, headers, `authKey`) and excludes the token-provider lambda; `authKey` is null for anonymous calls or `serverId:userId`. `ServerNet` has two entry points: `anonymous(...)` (no token, `authKey` null) and `authenticated(addresses, server, userId, device, tokens)` (`authKey` = `serverId:userId`, always set), so a token provider can never be paired with a null key. **Live tokens:** `ServerStore` keeps an in-memory `(serverId, userId) -> token` map (`cachedToken`, suspend-free, no per-request decryption). Rules: it is **never filled as a side effect of reading or mapping rows** (a stale snapshot could resurrect a revoked token); it is written only by `upsertAccount` (new token) and `warmTokens()` (loads all saved accounts once; `AppGraph.tokensWarmed` starts it at app start; safe to repeat). **Cold-start gate:** `awaitWarmed()` completes when the first `warmTokens()` has finished, successfully or not, and `awaitActive()` is `active()` after it; anything that needs the active account for an authenticated request must go through them. `warmTokens` never throws (except cancellation): a Room or Keystore failure is logged by kind only and leaves the map empty, so requests fail closed as `NOT_SIGNED_IN` while start-up still proceeds. `removeAccount` and `removeServer` run in `NonCancellable`, so the in-memory token clear and client eviction happen even if the caller is cancelled while waiting for the lock, and emptied by `removeAccount`/`removeServer` (exact `serverId` match). All three writers share one `Mutex`, and `warmTokens` re-reads the rows inside the lock, so a removal that finished first is simply absent. `authenticated` is handed `store::cachedToken`, so a cached client (which keeps the first provider for its key) sends the current token after a re-sign-in. **A missing token fails loudly:** an authenticated request whose lookup finds no token is refused before it is sent (`BlockedRequestException` wrapping `NOT_SIGNED_IN`, "You're signed out. Sign in again to continue."), never sent anonymously; anonymous configs are unaffected. `signOut` reads the account explicitly and revokes with a provider bound to that token for that call only (nothing is put in the map), then removes the account, which drops its clients. `ServerStore` reports every account upsert, account removal (in a `finally`, independent of the delete), server removal and server upsert (old and new addresses) to `ServerClients.evict`, which drops matching clients and evicts their idle connections. Only `HttpClientFactory` constructs clients.
- **Sign-out:** logout is tried on each address (home, then away) until one succeeds; the account is deleted locally regardless.
- **Clients (older note):** callers reuse one config per operation. The unused `jellyfin-core` and `slf4j-nop` dependencies were removed from the build and the catalogue (their entries may linger in `verification-metadata.xml`, which is harmless).

## Home / away address selection

- `AddressSelector` (data/servers) turns a server's home and away addresses into a live `StateFlow<Connection>` (`Checking` / `Online(address, isHome)` / `Offline`), one probe loop per server id (`connection(server)`; `refresh()`; `forget(serverId)`, called by `ServerStore.removeServer`).
- **Probing is anonymous** (`ServerNet.anonymous`, `publicInfo`): no token reaches an address before it has proved to be the server. An address counts only if its reported id matches `server.id` (`sameServerId`); a different server there is unreachable.
- **Timing:** home 1.5 s, away 5 s, probed concurrently with coroutine timeouts (a timed-out or no-longer-needed probe cancels its HTTP call). Home answering correctly wins at once; otherwise away is used when it answers; otherwise `Offline`. A missing address is skipped.
- **States:** `Checking(previous: Online?)`, `Online(address, isHome)`, `Offline(reason: AppError.Kind?)`. `reason` is set for security problems the user must act on (`TLS_PIN_CHANGED`, `TLS_UNTRUSTED`, `CLEARTEXT_BLOCKED`, `CLIENT_CERT_REQUIRED`, and a damaged saved pin as `TLS_UNTRUSTED`), preferring one if home and away fail differently; null for ordinary unreachability and identity mismatch. No address is ever in a state's text.
- **Re-probing and staleness:** on every `NetworkMonitor.changes` emission and on `refresh()` the state becomes `Checking(previous = the old Online)` **synchronously**, so a pre-change `Online` is never still published (the phone may have joined a network where the same private address is a different device); a generation counter makes check-and-set atomic and discards any in-flight probe that started before the latest change. The probe then starts after a 500 ms debounce for network changes (the monitor emits raw conflated changes; `AddressSelector` debounces probe starts) or at once for `refresh()`. Requests collapse through a conflated channel: one more probe after the running one, never overlapping. Changed addresses or security settings reset to `Checking(null)`. `forget(serverId)` is called when a server is removed or saved with no address; it first publishes `Offline()`, so a collector still holding the flow stops showing Online.
- **Sending a token:** **a held `Online` value is not proof of identity; always obtain the address via `awaitOnline(server)` (the next verified `Online`, or null on `Offline` or after 6 s) or the current state at send time**, never from a value kept earlier. The guarantee is no stronger than the OS's: a value published before the network-change callback arrived can still be returned, so a caller whose request fails calls `refresh()`.
- `AndroidNetworkMonitor` registers `registerDefaultNetworkCallback` when the first collector arrives and unregisters after the last (`shareIn` with `WhileSubscribed`), with signal-strength-only capability updates ignored. `AppGraph` owns the process-wide `appScope` they run in.

## UI (Task 8)

Single activity (`ui/MainActivity`, edge to edge) hosting Compose with Navigation-Compose. Material 3, dark by default, dynamic colour on Android 12+ (`ui/theme/Theme.kt` adds firmer headings and `CodeStyles` for Quick Connect codes and fingerprints).

```
ui/
├── MainActivity, ClientCertChooser (+ KeyChainCertChooser: the Activity-bound KeyChain picker), CertificateSummary, ConnectionView (Connection -> chip model)
├── nav/          AppNav (AppRoot, NavHost, Routes), Routes.kt (one wiring composable per route), GraphViewModel, StartupViewModel
├── components/   AppScreen (top bar, centred 560dp column, IME padding), Buttons, Messages (ErrorMessage live region, NoticeCard), SecurityPrompts
│                 (http / certificate / client-certificate steps shared by Add server and Connection settings), CertificateDetails, ConnectionChip, Dialogs, SecureWindow
├── onboarding/   AddressChecker (state machine), AddServerViewModel + Screen + Steps, SignInViewModel + Screen + Panels
├── servers/      ServersViewModel + Screen, ServerEditViewModel + Screen + Sections + Panes
└── home/         HomeViewModel, HomePlaceholderScreen
```

- **Start-up routing:** a neutral splash shows until `StartupViewModel` has awaited `awaitWarmed()` (via `awaitActive()`); then nothing saved means `welcome`, otherwise `home` (which copes with nobody signed in). No flash of the wrong screen.
- **Navigation graph:** `welcome` (first run) or `home` start; `add_server`; `sign_in/{serverId}`; `servers`; `server/{serverId}` (connection settings). Home -> servers -> add server / sign in / settings. Adding a server goes to its sign-in (popping `add_server`; on first run `welcome` stays underneath so the stack is never empty); signing in clears the stack and lands on `home`. Transitions are short fades with a small slide.
- **ViewModel and state conventions:** plain `androidx.lifecycle.ViewModel`s made by `graphViewModel { ... }` from `AppGraph` (scoped to the nav entry, cleared on pop; no DI framework). Each exposes one immutable `UiState` data class as `StateFlow` plus event functions; all logic and every error string (`AppError.userMessage`) lives there and is unit-tested with fakes. Composables are stateless: state plus an `...Actions` class of lambdas. ViewModels depend on the small interfaces in `data/servers/Seams.kt` (`ServerCatalog`, `Authenticator`, `ServerConnections`, `CertificateProber`), each implemented by one production class. UI states that hold addresses, headers or certificate details override `toString`.
- **Secrets in the UI:** the password lives in a `TextFieldState` owned by `SignInViewModel`, never in `UiState`; it is copied to a `CharArray`, the field is cleared at once, and `AuthRepository` wipes the copy. `FLAG_SECURE` (counted, `SecureWindow`) is set while Sign in and Connection settings are visible. Header values are masked until revealed and the data layer's validation message (`validateCustomHeader`) never repeats a value. Autofill hints are set for username and password.
- **Add server:** `AddressChecker` runs `findServer` and turns each problem into an explicit state: plain http (`NeedsHttpConsent`, shown after an explicit `http://` is blocked, or offered when a scheme-less address failed to connect over https), untrusted certificate (`probeCertificate`, fingerprint, trust pins it), client certificate (`KeyChain` alias). Nothing is saved until the optional second-address step ends; the second address passes the same checks and must be the same server. The first address is sorted home/away by `looksLikeHome()`. One pin and one cleartext flag exist per server, so a second address with its own different self-signed certificate cannot be pinned separately.
- **Tests:** ViewModel unit tests (`ui/**`), `ScreenRenderTest` and `ManagementRenderTest` (Robolectric + Compose test: every state composes, shows its key text, survives 200% font scale), and one on-device `WelcomeSmokeTest` (`app/src/androidTest`, run by the controller).

## Version checks

Checked 2026-11-14 against Maven metadata before use (constraints: Kotlin 2.2.21, AGP 8.10.1, `minCompileSdk <= 35`).

| Library | Version | Check |
|---|---|---|
| `androidx.room:room-runtime`, `room-ktx`, `room-compiler`, `room-testing` | 2.8.5 | `maven.google.com` lists 2.8.5 as latest stable. AAR metadata (`aar-metadata.properties`) of `room-runtime-android`, `room-ktx` and `room-testing-android` 2.8.4 and 2.8.5 reports `minCompileSdk=34` |
| `com.google.devtools.ksp` plugin | 2.2.21-2.0.5 | `repo1.maven.org` lists `2.2.21-2.0.5` as the newest build for Kotlin 2.2.21 (KSP2). Its POM depends only on `symbol-processing-api` and `-common-deps` of the same version |
| `com.squareup.okhttp3:okhttp`, `mockwebserver`, `okhttp-tls` (tests) | 5.3.2 | okhttp and mockwebserver were already verified; `okhttp-tls` added as a test dependency (HTTPS redirect test), metadata regenerated. Tests use `okhttp3.mockwebserver` (deprecated in 5.x in favour of `mockwebserver3`, still shipped) |
| `androidx.compose.ui:ui-test-junit4` (unit tests) | BOM 2025.12.01 | Already used for instrumented tests; also added as a unit-test dependency for the Robolectric render tests, metadata regenerated |
| `navigation-compose` 2.9.7, `lifecycle-viewmodel-compose` 2.9.4 | as AAFin | Already in the catalogue and build from the scaffold (AAFin's versions); nothing new was needed for the UI |
| Everything else | as AAFin | Carried over unchanged from AAFin's catalogue (already checked there). Car app, media3 and coil entries dropped until the milestones that need them |
