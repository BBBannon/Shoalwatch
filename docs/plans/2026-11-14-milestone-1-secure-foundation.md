# Milestone 1 — Secure Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A signed-in, security-hardened app shell: add a Jellyfin server, sign in (Quick Connect or password), switch automatically between home and away addresses, with tokens encrypted and every network call going through one hardened HTTP stack.

**Architecture:** Single `:app` module, package boundaries from `docs/SPEC.md` §5. `AppGraph` holds lazy singletons (no DI framework). Room is the store for servers and accounts; secrets inside rows are ciphertext produced by `SecretBox` (Android Keystore AES-GCM). One `HttpClientFactory` builds the per-server OkHttp client that enforces S3–S6; nothing else may create an HTTP client.

**Tech Stack:** Kotlin 2.2.21, AGP 8.10.1, Gradle 8.11.1, Compose (BOM 2025.12.01), OkHttp 5.3.2, Jellyfin Kotlin SDK 1.8.12, Room (+KSP), DataStore, kotlinx.serialization, JUnit4 + Robolectric + MockWebServer + Turbine.

## Global Constraints

- **Read `docs/SPEC.md` first.** Security table S1–S13 applies to every task.
- `applicationId`/namespace `app.offlinefin`; `minSdk 29`; `compileSdk 35`; `targetSdk 35`. **Do not install SDK platforms or other machine-level packages.**
- Build with `JAVA_HOME=C:\Users\Ben\Android\jdk17\jdk-17.0.20.1+1`; SDK at `C:\Users\Ben\Android\sdk` (written to `local.properties`, which is git-ignored).
- Start the version catalogue from `..\AAFin\gradle\libs.versions.toml`. Any **new** library version must be checked against Maven metadata for `minCompileSdk <= 35` and Kotlin 2.2.21 compatibility before use; record the check in `docs/ARCHITECTURE.md`.
- AAFin (`C:\Users\Ben\Documents\benmarksresources\AAFin`, GPL-3.0, same owner) is the reference for ported code. **Read-only: never modify AAFin.**
- **Never** log, print, or put in an exception message: a token, a custom header value, or a URL. User-facing text is UK English.
- Only `net/HttpClientFactory` may construct an `OkHttpClient`. If the Jellyfin SDK cannot be given our client, call the needed endpoints directly with OkHttp + kotlinx.serialization and use the SDK only for its model types (decide in Task 6, record why).
- TDD: failing test first, then code. Unit tests: `./gradlew.bat testDebugUnitTest`. Commit after each task with a conventional message ending in the `Co-Authored-By` line from the session.
- No file over ~400 lines. No analytics, crash reporting, or third-party hosts.
- **Never install anything on a physical phone.** Emulator only.

---

### Task 1: Project skeleton and hardened manifest

**Files:** `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/*` + `gradlew*` (copy from AAFin), `local.properties`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/data_extraction_rules.xml`, `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`, `app/src/main/java/app/offlinefin/OfflineFinApp.kt`, `AppGraph.kt`, `ui/MainActivity.kt`, `ui/theme/Theme.kt`, `app/src/test/java/app/offlinefin/ManifestSecurityTest.kt`, `LICENSE` (GPL-3.0, copy from AAFin), `docs/ARCHITECTURE.md`.

**Produces:** a debug APK that launches to a placeholder Compose screen; `AppGraph` (empty, `OfflineFinApp.graph(context)`); release build type with R8 minify + shrinkResources, signing read from optional `keystore.properties`.

- [ ] Write `ManifestSecurityTest` (Robolectric) asserting: `allowBackup == false`; `dataExtractionRules` set; `usesCleartextTraffic` is true **only** because per-server opt-in is enforced in code (assert the app-level `CleartextGuard` test tag exists later — here just assert the attribute and document why in a comment); requested permissions are exactly `INTERNET`, `ACCESS_NETWORK_STATE`; the only exported component is `MainActivity`.
- [ ] Run it — fails (no project).
- [ ] Create the Gradle project, catalogue (drop car/media3 entries not needed yet; add Room + KSP + `androidx.room:room-testing`), manifest, rules XML (exclude all domains for cloud-backup and device-transfer), theme (Material 3, dynamic colour on Android 12+, dark default), placeholder screen.
- [ ] Enable Gradle dependency verification: `./gradlew.bat --write-verification-metadata sha256 help testDebugUnitTest assembleDebug` and commit `gradle/verification-metadata.xml`.
- [ ] `./gradlew.bat testDebugUnitTest assembleDebug` passes. Commit.

### Task 2: Core — errors, redaction, logging

**Files:** `core/AppError.kt`, `core/Redact.kt`, `core/AppLog.kt`, `core/Ticks.kt`; tests `core/RedactTest.kt`, `core/AppErrorTest.kt`.

**Produces:**
```kotlin
class AppError(val kind: Kind, val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause) {
  enum class Kind { NETWORK, TIMEOUT, TLS_UNTRUSTED, TLS_PIN_CHANGED, CLEARTEXT_BLOCKED, CLIENT_CERT_REQUIRED,
                    SERVER_NOT_FOUND, SERVER_UNSUPPORTED, UNAUTHORISED, QUICK_CONNECT_DISABLED, FORBIDDEN, SERVER_ERROR, NOT_SIGNED_IN, UNKNOWN }
  companion object { fun from(t: Throwable): AppError }   // maps IOException/SSL*/HTTP codes; never copies t.message into userMessage
}
object Redact { fun url(s: String): String /* "https://host/…" → "<url>" */; fun text(s: String): String /* strips URLs, Token="…", ApiKey=…, api_key=…, Authorization values */ }
object AppLog { fun d(tag: String, msg: String); fun w(tag: String, msg: String, t: Throwable? = null) } // always passes through Redact.text; no-op in release
object Ticks { fun toMs(ticks: Long): Long; fun fromMs(ms: Long): Long }
```
- [ ] Tests first: `Redact.text` removes `https://a.b/c?api_key=XYZ`, `Token="abc"`, `Authorization: MediaBrowser …`; `AppError.from(SSLPeerUnverifiedException)` → `TLS_UNTRUSTED`; `userMessage` of any mapped error contains no `http`, no input message text.
- [ ] Implement, pass, commit.

### Task 3: Security — SecretBox

**Files:** `security/SecretBox.kt`, `security/KeystoreKeyProvider.kt`; tests `security/SecretBoxTest.kt` (JVM with a fake `KeyProvider`), `app/src/androidTest/java/app/offlinefin/security/KeystoreSecretBoxTest.kt`.

**Produces:**
```kotlin
fun interface KeyProvider { fun key(): javax.crypto.SecretKey }
class SecretBox(private val keys: KeyProvider) {
  fun seal(plain: String): ByteArray      // version byte 0x01 | 12-byte random IV | AES-256-GCM ciphertext+tag
  fun open(sealed: ByteArray): String     // throws AppError(UNKNOWN, "Saved sign-in could not be read. Please sign in again.") on any failure
}
class KeystoreKeyProvider(alias: String = "offlinefin.secrets.v1") : KeyProvider // AndroidKeyStore, AES/GCM/NoPadding, 256-bit, not exportable, setRandomizedEncryptionRequired(true), StrongBox if available with fallback
```
- [ ] Tests first: round-trip; two seals of the same text differ; tampering any byte makes `open` throw `AppError`; wrong version byte throws; empty string round-trips.
- [ ] Implement; JVM tests pass. Instrumented test is written and compiled (`assembleDebugAndroidTest`); it is run in Task 9.
- [ ] Commit.

### Task 4: Net — addresses and request guards

**Files:** `net/ServerAddress.kt` (port of AAFin `data/jellyfin/ServerUrl.kt`), `net/CleartextGuard.kt`, `net/AuthInterceptor.kt`, `net/CustomHeaderInterceptor.kt`; tests for each.

**Produces:**
```kotlin
@JvmInline value class ServerAddress private constructor(val value: String) {   // normalised base URL, no trailing slash
  val isSecure: Boolean; val host: String
  companion object { fun parse(input: String): ServerAddress /* adds https:// when no scheme; throws AppError(SERVER_NOT_FOUND, …) if invalid; rejects userinfo, fragments */ }
  override fun toString() = "<server>"
}
class CleartextGuard(private val allowCleartext: () -> Boolean) : okhttp3.Interceptor   // http:// request and !allow → throws AppError(CLEARTEXT_BLOCKED)
class AuthInterceptor(private val allowedHosts: () -> Set<String>, private val header: () -> String?) : okhttp3.Interceptor
   // adds Authorization only when request host ∈ allowedHosts; strips any api_key/ApiKey query parameter; never follows a token to another host
class CustomHeaderInterceptor(private val allowedHosts: () -> Set<String>, private val headers: () -> Map<String, String>) : okhttp3.Interceptor
   // same host rule; refuses header names: Authorization, Host, Cookie, Content-Length
object AuthHeader { fun build(client: String, device: String, deviceId: String, version: String, token: String?): String }  // MediaBrowser Client="…", Device="…", DeviceId="…", Version="…"[, Token="…"], values percent-safe
```
- [ ] Tests first (MockWebServer): token not sent to a second host after redirect; `api_key` removed from query; cleartext blocked/allowed; forbidden custom header names rejected; `ServerAddress.parse("jelly.example")` → `https://jelly.example`; `parse("http://192.168.1.5:8096/")` keeps http and port; `toString()` never reveals the value.
- [ ] Implement, pass, commit.

### Task 5: Net — trust policy and the client factory

**Files:** `net/TrustPolicy.kt`, `net/PinnedTrustManager.kt`, `net/KeyChainKeyManager.kt`, `net/HttpClientFactory.kt`, `net/CertificateInfo.kt`; tests `net/PinnedTrustManagerTest.kt`, `net/HttpClientFactoryTest.kt`.

**Produces:**
```kotlin
data class ServerNetConfig(val hosts: Set<String>, val allowCleartext: Boolean, val pinnedSpkiSha256: String?, val clientCertAlias: String?,
                           val customHeaders: Map<String, String>, val authHeader: () -> String?)
data class CertificateInfo(val subject: String, val issuer: String, val notAfter: java.time.Instant, val spkiSha256: String) // base64
class PinnedTrustManager(system: X509TrustManager, pinnedSpkiSha256: String?) : X509TrustManager
   // accept if system trust passes; else accept only if leaf SPKI SHA-256 == pin; else CertificateException. A pin that no longer matches → cause marked so AppError.Kind = TLS_PIN_CHANGED
class HttpClientFactory(context: Context) {
  fun clientFor(config: ServerNetConfig): OkHttpClient      // interceptor order: CleartextGuard, CustomHeaderInterceptor, AuthInterceptor; followSslRedirects=false; connect 10s/read 30s; TLS 1.2+
  suspend fun probeCertificate(address: ServerAddress): CertificateInfo?   // for the trust-on-first-use prompt: handshakes with a capture-only trust manager, sends no request body, returns leaf info; null for http
}
```
- User-installed CAs are **not** trusted: use the platform default trust manager (targetSdk ≥ 24 excludes user CAs) and do **not** add a `network_security_config` that trusts `user`.
- Client certificates: `KeyChainKeyManager` (X509KeyManager backed by `KeyChain.getPrivateKey/getCertificateChain` for the stored alias). The UI in Task 8 calls `KeyChain.choosePrivateKeyAlias`.
- [ ] Tests first (MockWebServer + `okhttp-tls` `HeldCertificate`, test-only dependency): self-signed server rejected with no pin; accepted with the right pin; rejected with a wrong pin as `TLS_PIN_CHANGED`; a properly chained cert is accepted with no pin; factory client refuses http when `allowCleartext=false`.
- [ ] Implement, pass, commit.

### Task 6: Data — database, servers, accounts, sign-in

**Files:** `data/db/AppDatabase.kt`, `data/db/ServerEntity.kt`, `data/db/AccountEntity.kt`, `data/db/ServerDao.kt`, `data/db/AccountDao.kt`, `data/servers/Server.kt`, `data/servers/Account.kt`, `data/servers/ServerStore.kt`, `data/servers/DeviceIdentity.kt`, `data/servers/JellyfinGateway.kt` (+ impl), `data/servers/AuthRepository.kt`, `data/servers/QuickConnectState.kt`; tests `ServerStoreTest.kt` (Robolectric, in-memory Room), `AuthRepositoryTest.kt` (fake gateway + virtual time), `JellyfinGatewayTest.kt` (MockWebServer).

**Reference:** AAFin `data/jellyfin/AuthGateway.kt`, `AuthRepository.kt`, `JellyfinClients.kt`, `JellyfinException.kt`, `data/accounts/*`.

**Produces:**
```kotlin
data class Server(val id: String, val name: String, val version: String, val homeAddress: ServerAddress?, val awayAddress: ServerAddress?,
                  val allowCleartext: Boolean, val pinnedSpkiSha256: String?, val clientCertAlias: String?, val customHeaders: Map<String, String>)
data class Account(val serverId: String, val userId: String, val userName: String, val accessToken: String) { override fun toString() = "Account($userName@$serverId)" }
class ServerStore(db: AppDatabase, box: SecretBox) {
  val servers: Flow<List<Server>>; val activeAccount: Flow<Account?>; fun accounts(serverId: String): Flow<List<Account>>
  suspend fun upsertServer(s: Server); suspend fun removeServer(id: String)   // cascades accounts
  suspend fun upsertAccount(a: Account, makeActive: Boolean); suspend fun removeAccount(serverId: String, userId: String); suspend fun setActive(serverId: String, userId: String)
  suspend fun server(id: String): Server?; suspend fun active(): Pair<Server, Account>?
}
// Rows store accessToken and customHeaders ONLY as SecretBox ciphertext (BLOB columns).
data class ServerInfo(val id: String, val name: String, val version: String, val address: ServerAddress, val compatibility: Compatibility) { enum class Compatibility { SUPPORTED, TOO_OLD, NEWER_UNTESTED, UNKNOWN } }  // SUPPORTED = 10.10 … 12.x
interface JellyfinGateway {
  suspend fun publicInfo(address: ServerAddress, net: ServerNetConfig): ServerInfo            // GET /System/Info/Public
  suspend fun quickConnectEnabled(address, net): Boolean; suspend fun quickConnectInitiate(address, net): QcSession /* secret, code */
  suspend fun quickConnectState(address, net, secret: String): Boolean; suspend fun authenticateWithQuickConnect(address, net, secret): AuthResult
  suspend fun authenticateByName(address, net, username: String, password: String): AuthResult
  suspend fun logout(address, net)                                                             // POST /Sessions/Logout — revoke token on sign-out
}
sealed interface QuickConnectState { Starting; AwaitingApproval(code); Authenticating; Success(account); TimedOut; Failed(error: AppError) }
class AuthRepository(gateway, store, device: DeviceIdentity, clock) {
  suspend fun findServer(input: String, draft: ServerNetDraft): ServerInfo
  fun quickConnect(server: Server, via: ServerAddress): Flow<QuickConnectState>     // polls every 4 s, 5 min timeout, tolerates network blips
  suspend fun signInWithPassword(server: Server, via: ServerAddress, username: String, password: String): Account   // password never stored, char array cleared where possible
  suspend fun signOut(serverId: String, userId: String)                              // best-effort server logout, then remove locally
}
```
- [ ] **First step:** read how AAFin wires the Jellyfin SDK's HTTP layer. If SDK 1.8.12 accepts an injected `OkHttpClient`, use it through `HttpClientFactory`; if not, implement `JellyfinGateway` directly on OkHttp (the seven endpoints above are small). Record the decision in `docs/ARCHITECTURE.md`.
- [ ] Tests first: token column bytes ≠ plaintext token and do not contain it; removing a server removes its accounts; Quick Connect happy path, timeout, disabled, and blip tolerance; password sign-in 401 → `UNAUTHORISED`; sign-out calls logout then deletes even if logout fails; compatibility mapping for `10.9.11`, `10.10.0`, `12.4.0`, `13.0.0`, `garbage`.
- [ ] Implement with Room schema export on (`app/schemas/`), pass, commit.

### Task 7: Net — home/away address selection

**Files:** `net/AddressSelector.kt`, `net/NetworkMonitor.kt`; tests `net/AddressSelectorTest.kt`.

**Produces:**
```kotlin
interface NetworkMonitor { val changes: Flow<Unit> }            // ConnectivityManager.NetworkCallback impl; emits on default-network change
sealed interface Connection { data class Online(val address: ServerAddress, val isHome: Boolean) : Connection; data object Offline : Connection; data object Checking : Connection }
class AddressSelector(gateway: JellyfinGateway, monitor: NetworkMonitor, scope: CoroutineScope) {
  fun connection(server: Server, net: ServerNetConfig): StateFlow<Connection>
  // Probe home (1.5 s timeout) and away (5 s) concurrently; prefer home if it answers AND its publicInfo.id == server.id (prevents a different server on the same LAN address receiving the token);
  // re-probe on every NetworkMonitor change and on explicit refresh(); never emit Online for an id mismatch.
  fun refresh()
}
```
- [ ] Tests first (fake gateway, virtual time): home wins when both answer; away used when home times out; id mismatch on home is ignored; Offline when neither answers; re-probes on network change.
- [ ] Implement, pass, commit.

### Task 8: UI — onboarding, sign-in, server management

**Files:** `ui/nav/AppNav.kt`, `ui/onboarding/AddServerScreen.kt` + `AddServerViewModel.kt`, `ui/onboarding/SignInScreen.kt` + `SignInViewModel.kt`, `ui/servers/ServersScreen.kt` + `ServersViewModel.kt`, `ui/servers/ServerEditScreen.kt`, `ui/home/HomePlaceholderScreen.kt`, `ui/components/*.kt`, strings; `AppGraph.kt` wiring; tests `AddServerViewModelTest.kt`, `SignInViewModelTest.kt`, `ServersViewModelTest.kt`.

**Behaviour:**
- **Add server:** type an address → `findServer`. Plain HTTP → blocking warning, explicit "Allow for this server" needed (sets `allowCleartext`). Untrusted certificate → show `CertificateInfo` fingerprint, explicit "Trust this certificate" pins it. `TLS_PIN_CHANGED` later → blocking warning, re-trust only from server settings. Client certificate required → "Choose certificate" (`KeyChain.choosePrivateKeyAlias`). Version outside 10.10–12.x → warning, can continue.
- **Sign in:** Quick Connect first (shows code, cancel abandons), password as alternative. Password field is not saved, not auto-captured by keyboards' learning (`KeyboardOptions(autoCorrect=false, keyboardType=Password)`), cleared after use.
- **Servers:** list servers and accounts, switch active, sign out (revokes), remove server; edit home address, away address, custom headers (values masked), certificate pin (view fingerprint, forget), client certificate.
- **Home placeholder:** active server name, user, and live `Connection` state ("Home network", "Away", "Offline").
- Accessibility: every control labelled, touch targets ≥ 48dp, text scales, no colour-only signals.
- [ ] ViewModel tests first (fakes for repositories): each warning path requires explicit confirmation before a server is saved; cancelling Quick Connect stops polling; error messages are `AppError.userMessage`.
- [ ] Implement, `testDebugUnitTest assembleDebug` pass, commit.

### Task 9: Milestone close — emulator run, security check, docs

- [ ] Boot an emulator from the installed system image (create an AVD if none exists; no downloads). Run `connectedDebugAndroidTest` (Keystore test from Task 3 + a launch smoke test added here).
- [ ] Install the debug APK on the **emulator**, screenshot onboarding, the HTTP warning and the servers screen; check layout by eye.
- [ ] Security check against S1–S7, S9, S10, S12: grep the APK/manifest and source for each; `adb shell run-as` to confirm the database holds no plaintext token after a fake sign-in against a local MockWebServer-style stub; confirm `adb backup`/extraction rules exclude data. Record results in `docs/security/milestone-1-check.md`.
- [ ] Update `docs/ARCHITECTURE.md`, `ChangeLog.txt`, `TODO.md` (via the sync script), Obsidian note.
- [ ] Hand Ben the live-server test steps (sign in on his phone against Jellyfin 12.4; confirm Quick Connect, home/away switch) — **his explicit yes is needed before any install on his phone.**
