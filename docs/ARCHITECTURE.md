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
├── net/         HttpClientFactory, AuthInterceptor, CleartextGuard, TrustPolicy, ServerAddress, AddressSelector
├── data/
│   ├── db/ servers/ library/ images/ playback/ downloads/ sync/
└── ui/          theme, navigation, one package per screen group
```

Only `net/HttpClientFactory` may construct an `OkHttpClient`.

## Version checks

Checked 2026-11-14 against Maven metadata before use (constraints: Kotlin 2.2.21, AGP 8.10.1, `minCompileSdk <= 35`).

| Library | Version | Check |
|---|---|---|
| `androidx.room:room-runtime`, `room-ktx`, `room-compiler`, `room-testing` | 2.8.5 | `maven.google.com` lists 2.8.5 as latest stable. AAR metadata (`aar-metadata.properties`) of `room-runtime-android`, `room-ktx` and `room-testing-android` 2.8.4 and 2.8.5 reports `minCompileSdk=34` |
| `com.google.devtools.ksp` plugin | 2.2.21-2.0.5 | `repo1.maven.org` lists `2.2.21-2.0.5` as the newest build for Kotlin 2.2.21 (KSP2). Its POM depends only on `symbol-processing-api` and `-common-deps` of the same version |
| `com.squareup.okhttp3:okhttp`, `mockwebserver` | 5.3.2 | Already in the catalogue and in `verification-metadata.xml`; no new dependency added for the `net` guards. Tests use `okhttp3.mockwebserver` (deprecated in 5.x in favour of `mockwebserver3`, still shipped) |
| Everything else | as AAFin | Carried over unchanged from AAFin's catalogue (already checked there). Car app, media3 and coil entries dropped until the milestones that need them |
