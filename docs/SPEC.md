# OfflineFin — Specification

**Approved by Ben:** 14/11/2026 · **Evidence:** `research/offline-downloads-research.md`, `research/android-client-flaws-research.md`

## 1. Purpose

A native Android client for Jellyfin (films and TV) whose offline library behaves exactly like the online one, and which fixes the ten most complained-about problems of the existing Android clients. Security is a design input at every milestone, not a late review.

## 2. Decisions

| Area | Decision |
|---|---|
| Audience | Ben first; built public-ready (GPL-3.0). Repo stays private until Ben says otherwise |
| Platform | Android phone and tablet only. `minSdk 29`, `compileSdk`/`targetSdk 35` (the only platform installed) |
| Stack | Kotlin, Jetpack Compose (Material 3), Media3 ExoPlayer, Jellyfin Kotlin SDK, OkHttp, Room, WorkManager, DataStore, Coil. Versions start from AAFin's proven catalogue |
| Build | Gradle 8.11.1, JDK 17 at `C:\Users\Ben\Android\jdk17`, SDK at `C:\Users\Ben\Android\sdk` |
| Structure | One `:app` module, strict package boundaries (§5). No DI framework: one `AppGraph` of lazy singletons, as in AAFin |
| Reuse | Port AAFin's tested pieces (server URL handling, Quick Connect, error model, device profile, playback resolver, progress reporter) by copying and adapting. No shared library between the two apps |
| Identity | `applicationId` and namespace `app.offlinefin` |
| Servers | Jellyfin 10.10 to 12.x supported; warn on anything else. Ben's server is 12.4 |
| Content in v1 | Films, TV, collections. **Out:** music, live TV, books, SyncPlay, Android Auto (AAFin covers it), Android TV |

## 3. What v1 must do (mapped to the researched top 10)

| # | Complaint | Requirement |
|---|---|---|
| 1 | No real offline library | Offline browsing is the same screens as online: series → season → episode in order, with artwork, overview, runtime and watched state |
| 2 | Continue Watching can't be tidied | Remove an item from Continue Watching; mark played/unplayed from any list |
| 3 | Paged, web-like browsing | Native lists with continuous scrolling, instant from cache, refreshed in the background |
| 4 | Transcoding wrong or missing | Accurate device profile so the server direct-plays where possible; quality choice for streaming and for downloads |
| 5 | Audio/subtitle choices | Remembered per series; subtitle size/colour/offset; subtitles available offline |
| 6 | Season download missing/broken | One action downloads a season, a series, or all unwatched episodes |
| 7 | Downloads stall or won't play | Resumable queue that survives backgrounding, restarts and app updates; every finished download verified playable |
| 8 | Getting connected | Per-server home and away addresses with automatic switching; multiple servers and users; Quick Connect; custom headers; client certificates |
| 9 | Casting and PiP | Picture-in-picture that works on Android 14+; Chromecast as a separable build flavour |
| 10 | Storage and housekeeping | Internal or SD card; storage view; auto-delete watched; auto-download next; watched progress sent back after offline use |

## 4. Architecture principles

1. **The database is the single source of truth.** Screens read only from Room (as flows). The network layer writes into Room. There is no separate "offline mode" code path in the UI.
2. **Cached vs pinned.** Every item row is either *cached* (may be evicted) or *pinned* (belongs to a download and is kept, with its parents: episode pins its season and series).
3. **UI never touches the Jellyfin SDK or a URL.** It uses app models and repositories.
4. **One error type** (`AppError` with a `kind` and a UK-English `userMessage` that never contains a URL or token).
5. **Secrets never travel in URLs or logs.** Authentication goes in the `Authorization` header via an interceptor. Types holding secrets have safe `toString()`.
6. **Small units.** A file that passes ~400 lines is a sign it does too much.

## 5. Package map

```
app.offlinefin
├── OfflineFinApp, AppGraph
├── core/        AppError, Ticks, dispatchers, logging (redacting)
├── security/    SecretBox (Keystore AES-GCM), AppLock, redaction helpers
├── net/         HttpClientFactory, AuthInterceptor, CleartextGuard, TrustPolicy (pins, client certs),
│                ServerAddress, AddressSelector (home/away)
├── data/
│   ├── db/      Room database, entities, DAOs
│   ├── servers/ Server, Account, ServerStore, AuthRepository (Quick Connect, password)
│   ├── library/ LibraryRepository (browse, search, next up, continue watching), sync into Room
│   ├── images/  ImageStore (cached and pinned artwork)
│   ├── playback/DeviceProfile, PlaybackResolver, PlayerController, ProgressReporter, TrackPreferences
│   ├── downloads/DownloadQueue, Downloader, DownloadVerifier, StorageLocations, housekeeping rules
│   └── sync/    PendingPlaystate outbox and its worker
└── ui/          theme, navigation, and one package per screen group
```

## 6. Security requirements

**Threat model:** lost or borrowed unlocked phone; hostile or shared Wi-Fi; another app on the device; device backup extraction; a malicious or compromised server response; supply-chain compromise of a dependency; accidental leaks via logs or bug reports.

| ID | Requirement |
|---|---|
| S1 | Access tokens, custom header values and any stored password-equivalents are encrypted at rest with an AES-256-GCM key held in the Android Keystore (hardware-backed where available). Passwords are never stored |
| S2 | `android:allowBackup="false"` and data-extraction rules excluding everything |
| S3 | HTTPS by default. Plain HTTP is refused unless the user has opted in for that specific server after a clear warning. Enforced in-app by an OkHttp interceptor (the manifest cannot express per-user hosts) |
| S4 | User-installed CAs are not trusted. A self-signed server is supported by explicit trust-on-first-use: the user is shown the certificate fingerprint and it is pinned for that server; a later change blocks with a warning |
| S5 | Client certificates (mTLS) come from the Android KeyChain; the app never handles the private key |
| S6 | Tokens are sent only in the `Authorization` header, only to the server they belong to. No token in a URL, log line, crash text, notification or `toString()` |
| S7 | No analytics, crash reporting or third-party network calls. The only hosts contacted are the user's servers (and the GitHub releases API if the user enables update checks) |
| S8 | Optional app lock (biometric or device credential) on launch and after a timeout; optional hiding of the app from the recents preview (`FLAG_SECURE`) |
| S9 | Minimum permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE` (+ data-sync type), `POST_NOTIFICATIONS`, `USE_BIOMETRIC`. No storage, location or settings permissions |
| S10 | Downloads live in app-specific storage (internal or SD card). No exported components except the launcher activity. All `PendingIntent`s immutable |
| S11 | Server responses are treated as untrusted: file names are never taken from the server (paths are built from validated GUIDs), sizes are bounded, HTML in overviews is rendered as plain text |
| S12 | Dependencies are version-pinned and checksum-verified (Gradle dependency verification). Release builds are minified, non-debuggable, signed locally; the key never leaves Ben's PC |
| S13 | Each milestone ends with a security check against this table; milestone 7 is a full security-team review before the first release |

## 7. Milestones

Each milestone has its own plan in `docs/plans/`, ends with automated tests passing, an emulator run, a security check (S13), and a test on Ben's phone against his server. **Nothing is installed on Ben's phone without his explicit yes.**

| # | Delivers |
|---|---|
| 1 | Project skeleton, security core (S1–S7, S12), servers and accounts, Quick Connect and password sign-in, home/away switching, custom headers, self-signed and client-certificate support, app shell |
| 2 | Room library cache, native browsing (home, libraries, series/season/episode, film detail, search), Continue Watching tidy-up, artwork cache |
| 3 | Player: device profile, direct play/transcode, quality choice, resume and progress reporting, track memory, subtitle styling, gestures, speed, skip segments, sleep timer |
| 4 | Downloads and offline library: queue, batch season/series/unwatched, original or transcoded, artwork/subtitles/previews pinned, verification, internal/SD storage. Begins with a short spike against the real server to settle how transcoded downloads are fetched resumably and whether Jellyfin 12 needs anything special for downloads |
| 5 | Housekeeping (auto-delete watched, auto-download next, storage view) and playstate outbox sync |
| 6 | Picture-in-picture, Chromecast flavour, tablet layout, app lock (S8), polish |
| 7 | Security-team review, fixes, signed first release, README |

## 8. Testing

- **JVM unit tests** (JUnit, Robolectric, MockWebServer, Turbine) for all `core`, `security`, `net` and `data` logic. New behaviour is written test-first.
- **Emulator** instrumented smoke tests for database, Keystore and navigation.
- **Real server, real phone** at the end of each milestone, driven by Ben.

## 9. Known risks

- **Jellyfin 12 download authentication** — a Streamyfin collaborator reports direct downloads need legacy auth enabled server-side (second-hand). Tested in milestone 1's live check and again in the milestone 4 spike.
- **Styled subtitles (ASS/PGS)** — Media3 support is limited; v1 falls back to server-side rendering. A second player engine is only considered if that proves inadequate.
- **Chromecast** needs Google Play Services, so it is isolated in its own flavour.
- **Reddit and Play Store evidence is missing** from the research; priorities rest on GitHub and the Jellyfin feature board.
