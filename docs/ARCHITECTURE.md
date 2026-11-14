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
  `./gradlew.bat --write-verification-metadata sha256 help testDebugUnitTest assembleDebug` and review the diff.
- **Release signing** reads an optional, git-ignored `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Absent file = unsigned release, build still configures.
- **Cleartext**: the manifest allows it only because opt-in is per server and enforced in code by `CleartextGuard` (`net/CleartextGuard`). Guards throw `BlockedRequestException` (an IOException carrying an `AppError`; `AppError.from` unwraps it), because OkHttp's `enqueue` turns any other throwable into a generic IOException. Allowed hosts are `host:port` authorities (`ServerAddress.authority`). No `network_security_config`, so user CAs are not trusted.

## Package map (SPEC section 5)

```
app.offlinefin
├── OfflineFinApp, AppGraph
├── core/        AppError, Ticks, dispatchers, logging (redacting)
├── security/    SecretBox (Keystore AES-GCM), AppLock, redaction helpers
├── net/         HttpClientFactory, TrustPolicy (ServerNetConfig), PinnedTrustManager (+ PinAwareHostnameVerifier), KeyChainKeyManager, CertificateInfo,
│                AuthInterceptor, CustomHeaderInterceptor, AuthHeader, BlockedRequestException, CleartextGuard, ServerAddress, AddressSelector
├── data/
│   ├── db/ servers/ library/ images/ playback/ downloads/ sync/
└── ui/          theme, navigation, one package per screen group
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

## Version checks

Checked 2026-11-14 against Maven metadata before use (constraints: Kotlin 2.2.21, AGP 8.10.1, `minCompileSdk <= 35`).

| Library | Version | Check |
|---|---|---|
| `androidx.room:room-runtime`, `room-ktx`, `room-compiler`, `room-testing` | 2.8.5 | `maven.google.com` lists 2.8.5 as latest stable. AAR metadata (`aar-metadata.properties`) of `room-runtime-android`, `room-ktx` and `room-testing-android` 2.8.4 and 2.8.5 reports `minCompileSdk=34` |
| `com.google.devtools.ksp` plugin | 2.2.21-2.0.5 | `repo1.maven.org` lists `2.2.21-2.0.5` as the newest build for Kotlin 2.2.21 (KSP2). Its POM depends only on `symbol-processing-api` and `-common-deps` of the same version |
| `com.squareup.okhttp3:okhttp`, `mockwebserver`, `okhttp-tls` (tests) | 5.3.2 | okhttp and mockwebserver were already verified; `okhttp-tls` added as a test dependency (HTTPS redirect test), metadata regenerated. Tests use `okhttp3.mockwebserver` (deprecated in 5.x in favour of `mockwebserver3`, still shipped) |
| Everything else | as AAFin | Carried over unchanged from AAFin's catalogue (already checked there). Car app, media3 and coil entries dropped until the milestones that need them |
