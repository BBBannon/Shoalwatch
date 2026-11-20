# Milestone 4 — Downloads and Offline Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One press downloads a film, an episode, a season, a whole series or every unwatched episode, in the original file or a smaller version the server converts. Each download is checked before it starts (the server allows it, and this phone can play it) and checked again when it finishes (complete, the right kind of file, and Media3 can read its tracks). Downloads keep going through backgrounding, loss of network, process death and reboot, and resume where they stopped. Downloaded films and shows keep their metadata, artwork, subtitles and skip markers, and browse offline as **series → season → episode in order**, with overview, runtime and watched state. They play through the existing player from the saved file, and the position watched offline is kept on the phone for Milestone 5 to send back. On a shared tablet, one setting per server lets profiles share downloads, but a restricted profile never sees a title its own account is not allowed to see.

**Architecture:** A new package, `data/downloads`, owns the queue. **Room is the queue** (`downloads`, `download_owners` and four tables of extras, schema v4). WorkManager only drains it: one unique `DownloadWorker`, a foreground `dataSync` worker, fetches one item at a time. Every byte comes through the account's own authenticated, pinned client: the media file through a new `ServerApi.downloadRoute` (the existing guarded `MediaRoute`, plus exactly `/Items/{itemId}/Download`), and subtitles, artwork and metadata through `ServerApi`. File names come only from validated ids and fixed words. Files live in app-private storage: internal storage by default, or the app-specific folder on an SD card by explicit choice. A download is keyed by **server + item** (SPEC §3.3). Who has it in their library is a separate `download_owners` table with a foreign key to `accounts`. Every offline read joins the existing `visible_items` record, so the per-user visibility rule from Milestone 2 decides who sees a shared download. Downloading pins the item, its season and its series (`items.pinned`, whose one writer is now `DownloadStore`), so eviction never removes them. Offline playback goes through the unchanged `PlaybackController`:
- the resolver returns a `LOCAL` plan with a `SavedMedia` instead of a route;
- the engine reads it through a new `ItemBytes` data source, which opens only files inside that item's folder;
- a decorator in front of the gateway serves saved subtitles and skip segments;
- reports for a `LOCAL` plan go to `LocalPlaybackReports`, which writes `offline_plays`, the record Milestone 5's outbox will send.

**Tech Stack:** as Milestone 3, plus AndroidX WorkManager **2.12.0** (`androidx.work:work-runtime`, and `work-testing` for tests). It was checked on 15/11/2026: the AAR's `aar-metadata.properties` gives `minCompileSdk=35` and `minAndroidGradlePluginVersion=8.6.0`, both within this build's compileSdk 35 and AGP 8.10.1. Media3 stays at **1.9.2**: 1.10.1 exists, but the bump is left to Milestone 8, as decided in Milestone 3. No Media3 `DownloadService`, `SimpleCache` or `media3-database` (see Decisions). No new third-party library.

## Global Constraints

- **Read first:**
  - `docs/SPEC.md`: §3 rows 1, 4, 5, 6, 7 and 10; §3.1 and §3.2 (Milestone 5 builds on this data model); §3.3; §4; §6; §7 row 4; §9 "Jellyfin 12 download authentication".
  - `docs/ARCHITECTURE.md`, all of it, especially: "Layer rules"; "Authenticated requests"; "Media route"; "Library cache" (visibility, pinned, maintenance, SQLite 3.22); "Tidy-up actions" (the pending marker, the start-up sweep, "For Milestone 5"); "Artwork"; "Player engine and controller"; "Tracks and subtitles"; "Version checks".
  - `docs/security/milestone-1-check.md` and `milestone-2-check.md`, plus `milestone-3-check.md` once it exists.
  - `research/offline-downloads-research.md`, all of it. Its ranked complaints 1–8 and 11 are what this milestone must beat.
  - `research/android-client-flaws-research.md`, the "Security and privacy" section.
  - Milestone 3's plan, `docs/plans/2026-11-15-milestone-3-player.md`, for the player screen's interface (Task 8).
  - AAFin's `data/downloads/` code. It uses Media3's `DownloadManager`, which this plan deliberately does not use. Port only its ideas: start-up reconciliation of media against metadata, and re-queueing stale work.
- **API reference:** `docs/reference/jellyfin-openapi-12.4.0.json`. Every endpoint, parameter and field this plan names was checked against it on 15/11/2026; the results are below. Re-check anything new.
  - `GET /Items/{itemId}/Download` has security `CustomAuthentication: [Download, DefaultAuthorization]`, so the server enforces the account's download permission. It answers `video/*` or `audio/*` with 200, or 401, 403, 404, 503. **The reference says nothing about `Range`**, so the code is correct whether or not the server honours it (Task 1's rules).
  - `GET /Items`: `ids` (comma-delimited), `fields` (`ItemFields` enum, which includes `CanDownload` and `MediaSources`), `userId`, `enableUserData`.
    - The parameter's description lists fewer fields than the enum; the enum is the schema, so it wins.
  - `BaseItemDto.CanDownload` is a nullable boolean. `MediaSourceInfo` has `Size` (int64, nullable), `Container`, `Id` and `ETag`.
  - `POST /Items/{itemId}/PlaybackInfo`: `PlaybackInfoDto.EnableTranscoding`, `EnableDirectPlay` and `EnableDirectStream` are as Milestone 3 sends them.
  - The subtitle route is `GET /Videos/{routeItemId}/{routeMediaSourceId}/Subtitles/{routeIndex}/Stream.{routeFormat}`.
  - `GET /MediaSegments/{itemId}` and `GET /Items/{itemId}/Images/{imageType}` are as Milestones 2–3 use them.
  - **The reference documents no HLS route** (`master.m3u8`, `main.m3u8` and the segment paths are absent) and **no `ActiveEncodings` route**. As in Milestone 3, the app never builds an HLS path and never asks the server to stop a conversion: it fetches only what `TranscodingUrl` and the playlists name, after validation. Decode with `ignoreUnknownKeys`; every field is optional unless the reference marks it required.
- Package root `app.shoalwatch`; `minSdk 29`, `compileSdk`/`targetSdk 35`. Build with `JAVA_HOME=/path/to/jdk-17`.
  - Verify with `./gradlew.bat clean testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease`.
  - Gradle dependency verification is ON. After the WorkManager dependency is added, run `./gradlew.bat --write-verification-metadata sha256 help testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease`.
  - Review the diff: only `androidx.work` and its declared transitive dependencies may appear (`androidx.startup:startup-runtime` 1.1.1, `androidx.room` 2.7.0 POMs resolved up to the build's 2.8.5, `kotlin-stdlib` 2.1.20 POMs). Commit it.
  - Record the version under "Version checks" in ARCHITECTURE.md.
- **Security rules that bind every task** (all Milestone 3 rules still hold):
  - **Clients:** only `HttpClientFactory` builds OkHttp clients (`newBuilder()` counts, and is forbidden); no client follows redirects.
  - **Tokens:** a token goes only to an address that `AddressSelector.awaitOnline(serverId)` has verified, and only in the `Authorization` header. Never `api_key` in a URL, on disk, in WorkManager input data, in a notification or in a log.
  - **One path for bytes:** every byte comes through `ServerApi` or a route that `ServerApi` handed out, so the pin, client certificate, custom headers and cleartext rule all apply.
  - **Paths and file names** are built only by `DownloadPaths`, from `JellyfinId`-normalised ids, validated image tags, a validated stream index and fixed words. The canonical path of every file opened or deleted must lie inside its root, checked after building and again before deleting. No server string ever becomes part of a path.
  - **Server responses are untrusted.** Sizes are bounded (see each task). Ids are validated with `JellyfinId`. Media source ids and ETags must match `[A-Za-z0-9]{1,64}`; HTTP validators must be 1 to 128 printable ASCII characters. Playlists are parsed by a bounded parser.
  - **Never logged:** a path, URL, title, size or position. `AppLog` lines are fixed words plus an `AppError.Kind` or an exception class.
  - **Notifications** carry no title: a shared tablet's lock screen must not say what a profile is downloading. Every `PendingIntent` is `FLAG_IMMUTABLE` and explicit.
  - **New permissions** are exactly those under "Decisions". `verifyReleaseManifest` is changed deliberately in Task 11, with a fixture for each new rule.
- **Session and sharing rule:**
  - A download belongs to a server, and is in the library of each profile in `download_owners`.
  - A profile sees a download if, and only if, it has a `visible_items` record for the item (and for its season and series, on the series screens) AND it either owns the download or the server's "Share downloads between profiles" setting is on.
  - Items never cross between servers.
  - Every offline read takes a `Session` and joins `visible_items` for that user. A non-owner sees a shared download only once it is `COMPLETE`.
  - Removing a server deletes all of its downloads.
  - Removing an account (sign-out removes it here) applies the ownership rule in Task 12: shared downloads pass to the profiles that can see them, and the rest are deleted. It is tested through the real `ServerStore`.
- **Data rules:**
  - Room schema v4 with an explicit `Migration3To4`, tested like `Migration2To3`. No destructive fallback; schema exported.
  - Foreign keys `ON DELETE CASCADE`. `@Upsert` or `INSERT OR IGNORE` + `UPDATE`, never `REPLACE`.
  - No SQL newer than SQLite 3.22: `SqlCompatibilityTest` scans the new DAOs.
  - Device download settings (mobile data, storage, default quality) live in a private `SharedPreferences` file, `download_prefs`, as `playback_prefs` does. "Share downloads between profiles" is per server, in Room, so it goes with the server.
- TDD with a behavioural RED first.
  - Network code: real OkHttp + MockWebServer.
  - Storage: in-memory Room, and temporary folders (`TemporaryFolder`) for files.
  - Coroutines: virtual time.
  - Seams: fakes (`FakeDownloadGateway`, `FakeVolumes`, `FakeProbe`, `FakeDownloads`, plus the existing fakes).
  - Screens: Robolectric + Compose test, 360x640dp and 840x1200dp at font scale 1.0 and 2.0.
  - No main file over ~400 lines (`SourceShapeTest`). `PlaybackController.kt` is at 400 now, so this plan changes it by **one line** (Task 13).
  - UK English, plain words, all user-facing text in `strings.xml`.
  - Every control has a TalkBack label, a 48dp target, and works at font scale 2.0.
- **Commits:** conventional, each ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
  - One ChangeLog line per user-visible change (`vX.Y — DD/MM/YYYY — plain English`, minor bump).
  - Keep ARCHITECTURE.md accurate. Never edit `docs/SPEC.md`.
  - **Never start an emulator, run connected tests or touch a device** — the controller does that. **Nothing is installed on Ben's phone without his explicit yes.**

---

## File structure (new and changed)

```
app/src/main/java/app/shoalwatch/
├── ShoalwatchApp.kt                          (modify: WorkManager Configuration.Provider, start-up reconcile)
├── AppGraph.kt                               (modify: download singletons, store hooks, routed reports, saved-first gateway)
├── data/api/
│   ├── ServerApi.kt                          (modify: downloadRoute)
│   └── MediaRoute.kt                         (modify: GuardedCallFactory allows one exact extra path)
├── data/db/
│   ├── AppDatabase.kt                        (modify: v4, DownloadDao, OfflineLibraryDao, OfflinePlayDao)
│   ├── Migration3To4.kt                      (new)
│   ├── library/LibraryMaintenanceDao.kt      (modify: a pinned series keeps its seasons list)
│   ├── library/ItemDao.kt                    (modify: clearPendingFor spares rows with an offline play)
│   └── downloads/                            (new) DownloadEntity, DownloadOwnerEntity, DownloadTrackEntity, DownloadSegmentEntity,
│                                             PinnedArtworkEntity, DownloadSettingsEntity, OfflinePlayEntity, Converters,
│                                             DownloadDao, OfflineLibraryDao, OfflinePlayDao, DownloadStore
├── data/library/StartupSweep.kt, PlaystateRepository.kt, PlaystateLocal.kt   (modify: recordLocalStop; sweep spares offline plays)
├── data/sync/OfflinePlays.kt                 (new: the offline_plays record Milestone 5 sends)
├── data/images/PinnedArtwork.kt              (new) + ArtworkFetcher.kt (modify: pinned copy before the network)
├── data/downloads/                           (new package)
│   ├── DownloadModels.kt                     DownloadQuality, DownloadState, DownloadFailure, StorageKind, DownloadTarget, DownloadInfo,
│   │                                         OfflineEntry, OfflineLibrary, OfflineSeries, OfflineSeason, SeriesSummary, DownloadPlan,
│   │                                         PlannedItem, Skipped, SkipReason, QueueStatus, ArtworkSlot, TrackType
│   │                                         (DownloadKey and RemoveOutcome live in DownloadStore.kt, StorageChoice in Volumes.kt,
│   │                                         DownloadSettings in DownloadPrefs.kt)
│   ├── Downloads.kt                          the seam screens use
│   ├── DownloadRepository.kt                 implements Downloads
│   ├── DownloadPaths.kt, Volumes.kt          where files go (+ AndroidVolumes), free space, orphan sweep
│   ├── DownloadPrefs.kt                      device settings (download_prefs)
│   ├── api/DownloadDtos.kt, DownloadGateway.kt (+Impl)   CanDownload, media source sizes
│   ├── HlsPlaylists.kt                       bounded parser and writer (pure)
│   ├── OriginalCheck.kt                      can the original play here? (pure, over PlaybackInfo)
│   ├── DownloadPlanner.kt                    targets -> plan (counts, sizes, skips, conversions, free space)
│   ├── FileFetcher.kt, HlsFetcher.kt         resumable fetches
│   ├── ExtrasFetcher.kt                      tracks, subtitles, segments, artwork
│   ├── DownloadVerifier.kt                   size, file signature, SHA-256, probe (PlayabilityProbe seam)
│   ├── DownloadQueue.kt                      one item at a time, state machine over DownloadStore
│   ├── DownloadWorker.kt, DownloadScheduler.kt, DownloadNotifications.kt, ShoalwatchWorkerFactory.kt
│   ├── DownloadHousekeeping.kt               account/server gone, unpinned artwork, orphan files, visibility re-check
│   └── SavedMedia.kt                         SavedMedia + SavedMediaSource (what a player may open)
├── data/playback/
│   ├── PlaybackModels.kt                     (modify: PlayMethod.LOCAL)
│   ├── PlaybackResolver.kt                   (modify: saved copy first; Resolved carries route OR saved; checkOriginal)
│   ├── PlaybackTracks.kt                     (modify: LOCAL behaves as DIRECT_PLAY, never asks the server again)
│   ├── SavedPlans.kt                         (new: LOCAL plan from saved tracks)
│   ├── SavedFirstPlaybackGateway.kt          (new: saved subtitles and segments before the server)
│   ├── LocalPlaybackReports.kt               (new: LocalPlaybackReports + RoutedPlaybackReports)
│   ├── PlaybackController.kt                 (modify: ONE line, EngineItem from Resolved)
│   ├── api/PlaybackGatewayImpl.kt            (modify: LOCAL never sent; mapped for exhaustiveness)
│   └── engine/PlayerEngine.kt, ExoPlayerEngine.kt, MediaItems.kt (modify); ItemBytes.kt, SavedMediaDataSource.kt, SavedMediaProbe.kt (new)
└── ui/
    ├── nav/AppNav.kt, BrowseRoutes.kt        (modify: downloads routes)
    ├── shell/DownloadsScreen.kt              (delete: replaced by ui/downloads)
    ├── downloads/                            (new) DownloadsViewModel, DownloadsUiState, DownloadsScreen, DownloadRows,
    │                                         OfflineSeriesViewModel, OfflineSeriesScreen, DownloadActions, DownloadSheet,
    │                                         DownloadSettingsViewModel, DownloadSettingsScreen, NotificationAsk, DownloadsPreviews
    ├── present/DownloadText.kt               (new: plain-words mappers, pure)
    ├── present/PlayerText.kt, player/PlayerSheets.kt (modify: "the copy saved on this phone")
    ├── series/*, detail/*                    (modify: download buttons and states)
    └── servers/ServersScreen.kt              (modify: what sign-out and remove do to downloads)
app/src/main/AndroidManifest.xml              (modify: permissions, WorkManager services, initialiser)
app/build.gradle.kts, gradle/libs.versions.toml, gradle/verification-metadata.xml   (modify)
app/src/test/manifest-fixtures/               (new fixtures: bad-unprotected-job-service.xml, bad-diagnostics-receiver.xml, bad-missing-data-sync.xml)
app/src/test/resources/jellyfin/downloads/    items-candownload.json, hostile-items.json, master.m3u8, media.m3u8, hostile-*.m3u8
app/src/androidTest/java/app/shoalwatch/ui/DownloadSmokeTest.kt
tools/mockjelly/                              (modify: download routes, one flag per real-server behaviour)
research/2026-11-downloads-spike.md
docs/security/milestone-4-check.md
```

---

### Task 1: Desk spike, and the stand-in server learns every way a real server might behave

SPEC §7 row 4 asks for a short spike to settle how converted downloads are fetched resumably and whether Jellyfin 12 needs anything special. **No agent or controller can reach Ben's server during the build**, and Ben tests on his phone and server only once, at the very end. So this spike is done at the desk, from the reference and Jellyfin's published source. For every question only a real server can answer, the plan takes **the path that is correct either way**. The stand-in server then gets one flag per behaviour, so both answers are tested. The questions themselves go to "Unproven until Ben's real server" at the end of this plan.

**Files:**
- Create: `research/2026-11-downloads-spike.md`.
- Modify: `tools/mockjelly/mock_jellyfin.py`, `tools/mockjelly/README.md`, `tools/mockjelly/test_mock_jellyfin.py`.

**Interfaces:** Consumes nothing in the app. Produces the mock routes and flags below, and the desk findings.

| Route (mock) | Behaviour (default) |
|---|---|
| `GET /Items?ids=&fields=CanDownload,MediaSources&userId=` | `BaseItemDtoQueryResult` for the listed mock items. The 3 playable items have `CanDownload` true. `MediaSources[0]` has `Id` equal to the item id, `Container` `mp4`, `Size` equal to the exact byte count of `clip.mp4`, and `ETag` `a1b2c3` |
| `GET /Items/{itemId}/Download` | Serves `clip.mp4` as `video/mp4` with `Accept-Ranges: bytes`, `ETag: "a1b2c3"` and `Last-Modified`. Answers `206` with an exact `Content-Range` for `Range: bytes=N-`, and `200` (the whole file) when `If-Range` does not match. 401 without a token; 400 if any `api_key`-like query name is present |
| `POST /Items/{itemId}/PlaybackInfo` with `EnableTranscoding:false` | As Milestone 3, with no `TranscodingUrl` |
| `/videos/{itemId}/master.m3u8` (forced transcode) | As Milestone 3's HLS files, with `#EXT-X-PLAYLIST-TYPE:VOD`. Each segment URI is relative and carries `api_key=MOCKLEAK`, which the app must strip. The play session id is in the playlist path |
| `GET /mock/downloads` | Test only: each media request's path, `Range`, `If-Range`, play session id and whether a token was present (yes or no), as JSON |

**Behaviour flags** (each changes one thing; any combination is allowed):

| Flag | Behaviour |
|---|---|
| `--drop-after=BYTES` | Closes the connection after that many body bytes, on the FIRST media request only |
| `--no-range` | `/Download` ignores `Range` and `If-Range`, sends no `Accept-Ranges` and always answers 200 with the whole file |
| `--etag-changes` | Every `/Download` response has a new `ETag` and `Last-Modified`, so every `If-Range` mismatches |
| `--octet-stream` | `/Download` answers `application/octet-stream` |
| `--no-download` | `CanDownload:false`, and `/Download` is 403 |
| `--candownload-missing` | `CanDownload` absent from `/Items`; `/Download` still allowed |
| `--download-forbidden` | `CanDownload:true`, but `/Download` is 403 (the server's own policy disagrees) |
| `--legacy-auth-only` | `/Download` and HLS segments are 401 unless an `api_key` query is present, while `/Items` and PlaybackInfo accept the header (Jellyfin 12's legacy-authorisation setting, SPEC §9) |
| `--hls-recut` | Each PlaybackInfo gives a media playlist with a different segment count and durations |
| `--hls-idle-kill=SECONDS` | A play session's segments are 404 once no segment of it was requested for that long |

- [ ] **Desk findings, `research/2026-11-downloads-spike.md`.** Read and cite, with links to the exact files at Jellyfin's 10.11 and 12.x tags where published:
  - the 12.4.0 reference (paths, security, parameters, as in "API reference" above);
  - `Jellyfin.Api/Controllers/LibraryController.cs` `GetDownload` (does it serve with range processing on?);
  - `Jellyfin.Api/Auth/DownloadPolicy/` (the `Download` policy);
  - `Emby.Server.Implementations/Dto/DtoService.cs` (when `CanDownload` is filled);
  - `Jellyfin.Api/Controllers/DynamicHlsController.cs` (how segments are cut, restarted after a jump and stopped when idle);
  - the server's authorisation handler for the legacy setting.

  Record what the source says, and for each question say: **"the plan does not depend on this"**, plus the path the code takes either way (below). Nothing in this task contacts any real server.
- [ ] **Correct-either-way rules** (copied into the note; Tasks 5, 6, 7 and 10 implement them):
  - **Range on `/Download`:** resume with `Range` + `If-Range` when the server honours them. A 200 to a range request, or any response without a usable `Content-Range`, restarts that file from zero. A restart only because the server ignores ranges is not "changed on server": it costs an attempt, never a `CHANGED_ON_SERVER`.
  - **If-Range / validators:** a changed validator means a 200 and a restart from zero. Only three restarts in a row with a *changed* validator are `CHANGED_ON_SERVER`.
  - **Size:** `MediaSourceInfo.Size` when present, else the header total; the verifier checks the result either way.
  - **Content-Type:** refuse only types that are surely not media (`text/*`, any `html`, `application/json`, `application/xml`). Anything else is accepted, because the verifier's file-signature and Media3 checks decide.
  - **`CanDownload`:** `true` allows original and converted. `false` allows neither. **Absent or null allows the original only**, because `/Download` enforces the server's own download permission (a 403 there is `NOT_ALLOWED`). Conversions come through `/videos/`, which has no download policy, so they are not offered then: "Your server didn't say whether this account may download converted copies, so only the original can be downloaded."
  - **`/Download` refused while `CanDownload` is true:** `NOT_ALLOWED`. The server's answer wins.
  - **Authentication:** header only, always. A 401 on a media request is followed by ONE `ServerApi` call for the same item (`DownloadGateway.facts`). If that call also gets 401, `ServerApi` signs the account out locally and the item is `SIGN_IN_NEEDED`. If that call succeeds, the token is fine and the server refused header authentication for media: the item is `FAILED(SERVER_AUTH_REFUSED)`, "Your server wouldn't accept this app's sign-in for downloads. Check its setting for older apps, then press Retry." No token is ever put in a URL to work around it.
  - **HLS segment cuts across play sessions:** segments from two play sessions are **never mixed**. A conversion resumes only inside the same play session, which the queue keeps in memory (Task 10). After process death, a reboot, or an in-memory session older than 30 minutes, it asks the server again and **restarts that item's conversion from the first segment**, deleting the old segments. A recut server and a stable server are both correct.
  - **Idle conversions killed by the server:** a 404 or 5xx on a segment in a live session drops the session, and the item restarts from the first segment with a new one (counted as an attempt).
- [ ] **Mock server, tests first.** In `test_mock_jellyfin.py`, one test per default behaviour and per flag:
  - the default `206` with an exact `Content-Range: bytes 100-{n-1}/{n}` and exactly `n-100` bytes, and a mismatched `If-Range` giving 200;
  - `--drop-after` cuts only the first response;
  - `--no-range` always 200 with no `Accept-Ranges`;
  - `--etag-changes` gives a new ETag each time;
  - `--octet-stream` gives that type;
  - `--no-download` gives 403 and false;
  - `--candownload-missing` has no field;
  - `--download-forbidden` gives 403 while `/Items` says true;
  - `--legacy-auth-only` gives 401 for header auth on `/Download` and segments, and 200 on `/Items`;
  - `--hls-recut` gives two PlaybackInfo answers with different shapes;
  - `--hls-idle-kill=2` gives a 404 after 2 s idle;
  - a request carrying `api_key` is 400 unless `--legacy-auth-only` is set.

  Then implement (standard library only, `python -I`). Logging stays method + path + token yes/no. Document every route and flag in the README with one `curl` each.
- [ ] Run: `python -I tools/mockjelly/test_mock_jellyfin.py` → all pass.
- [ ] Commit: `test(mockjelly): download routes, one flag per real-server behaviour, and the desk spike`.

---

### Task 2: Room v4 — the download tables and their migration

**Files:**
- Create: `data/db/downloads/DownloadEntity.kt`, `DownloadOwnerEntity.kt`, `DownloadTrackEntity.kt`, `DownloadSegmentEntity.kt`, `PinnedArtworkEntity.kt`, `DownloadSettingsEntity.kt`, `OfflinePlayEntity.kt`, `Converters.kt`, `data/db/Migration3To4.kt`, and `data/downloads/DownloadModels.kt` (the enums only, used by the converters).
- Modify: `data/db/AppDatabase.kt` (version 4, the new entities, `downloadDao()`, `offlineLibraryDao()`, `offlinePlayDao()`, `Migration3To4`, the new converters); `app/schemas/.../4.json` (generated).
- Test: `data/db/MigrationTest.kt` (3→4, 1→4), `data/db/DownloadCascadeTest.kt`, `data/db/SqlCompatibilityTest.kt` (it scans the new files automatically; confirm it does).

**Interfaces — Consumes:** `ServerEntity`, `AccountEntity`, `ItemKind`, `Quality`.

**Produces:**
```kotlin
// data/downloads/DownloadModels.kt (enums; the rest of the file is added in Task 3)
/** ORIGINAL is the server's own file; the rest are conversions at Milestone 3's streaming qualities. */
enum class DownloadQuality(val quality: Quality?) { ORIGINAL(null), HIGH(Quality.MBPS_8), MEDIUM(Quality.MBPS_4), LOW(Quality.MBPS_1_5) }
enum class DownloadState { QUEUED, RUNNING, PAUSED, VERIFYING, COMPLETE, FAILED }
enum class DownloadFailure {
    NOT_ALLOWED, NOT_PLAYABLE, NO_SPACE, STORAGE_MISSING, CHANGED_ON_SERVER, SERVER_ERROR, SIGN_IN_NEEDED,
    CHECK_FAILED, DAMAGED, GONE_FROM_SERVER,
    /** The token works for ServerApi but the server refused it on a media request (e.g. Jellyfin 12's legacy-authorisation setting). */
    SERVER_AUTH_REFUSED,
    UNKNOWN,
}
enum class StorageKind { INTERNAL, REMOVABLE }
enum class ArtworkSlot { PRIMARY, BACKDROP, THUMB }
enum class TrackType { AUDIO, SUBTITLE }

// data/db/downloads/DownloadEntity.kt — table `downloads`, PK (serverId, itemId), FK serverId -> servers ON DELETE CASCADE,
// indices (serverId, seriesId), (state, queuePosition)
data class DownloadEntity(
    val serverId: String, val itemId: String,
    val kind: ItemKind,                       // MOVIE or EPISODE only
    val seriesId: String?, val seasonId: String?,
    val quality: DownloadQuality,
    val state: DownloadState,
    val failure: DownloadFailure?,
    val storage: StorageKind,
    val volumeId: String?,                    // StorageVolume uuid for REMOVABLE, [A-Za-z0-9-]{1,36}; null for INTERNAL
    val mediaSourceId: String?,               // [A-Za-z0-9]{1,64}
    val container: String?,                   // one of DownloadPaths.MEDIA_EXTENSIONS, or "hls"
    val totalBytes: Long?, val doneBytes: Long,
    val segmentsTotal: Int?, val segmentsDone: Int,
    val validator: String?,                   // ETag or Last-Modified for If-Range, 1..128 printable ASCII
    val restarts: Int, val attempts: Int,
    val runtimeMs: Long?, val audioIndex: Int?,
    val sha256: String?,                      // 64 lower-case hex, set on COMPLETE
    val queuePosition: Long,
    val requestedAt: Long, val completedAt: Long?, val verifiedAt: Long?,
)
// download_owners: PK (serverId, userId, itemId); FK (serverId, userId) -> accounts CASCADE; FK (serverId, itemId) -> downloads CASCADE; index (serverId, itemId)
data class DownloadOwnerEntity(val serverId: String, val userId: String, val itemId: String, val addedAt: Long)
// download_tracks: PK (serverId, itemId, type, streamIndex); FK (serverId, itemId) -> downloads CASCADE
data class DownloadTrackEntity(
    val serverId: String, val itemId: String, val type: TrackType, val streamIndex: Int,
    val label: String, val language: String?, val codec: String?, val kind: SubtitleKind?, val fileFormat: String?,
    val forced: Boolean, val hearingImpaired: Boolean, val isDefault: Boolean, val external: Boolean, val channels: Int?,
    val fileSaved: Boolean,                   // a TEXT/STYLED_TEXT subtitle saved as sub-<streamIndex>.<fileFormat>
)
// download_segments: PK (serverId, itemId, kind, startMs); FK (serverId, itemId) -> downloads CASCADE
data class DownloadSegmentEntity(val serverId: String, val itemId: String, val kind: SegmentKind, val startMs: Long, val endMs: Long)
// pinned_artwork: PK (serverId, itemId, slot); FK serverId -> servers CASCADE (an item can be pinned by several downloads)
data class PinnedArtworkEntity(val serverId: String, val itemId: String, val slot: ArtworkSlot, val tag: String, val bytes: Long, val savedAt: Long)
// download_settings: PK serverId; FK serverId -> servers CASCADE. Milestone 5 adds its "Delete shared downloads" column here.
data class DownloadSettingsEntity(val serverId: String, @ColumnInfo(defaultValue = "0") val shareBetweenProfiles: Boolean)
// offline_plays: PK (serverId, userId, itemId); FK (serverId, userId) -> accounts CASCADE. What Milestone 5's outbox sends (§3.2).
data class OfflinePlayEntity(
    val serverId: String, val userId: String, val itemId: String,
    val positionMs: Long, val played: Boolean,
    val playedAt: Long?,                      // when it was finished, by the phone's clock; never cleared once set
    val lastPlayedAt: Long, val updatedAt: Long,
)

// data/db/Migration3To4.kt
object Migration3To4 : Migration(3, 4)    // the 7 CREATE TABLE and 3 CREATE INDEX statements, copied verbatim from 4.json
```
- `Converters` stores each enum by `name`. A name this version does not know reads as a safe value:
  - `DownloadState` → `FAILED`;
  - `DownloadFailure` → `UNKNOWN`;
  - `DownloadQuality` → `ORIGINAL`;
  - `StorageKind` → `INTERNAL`;
  - `ArtworkSlot`, `TrackType`, `SubtitleKind` and `SegmentKind` → the row is dropped by the DAO mapper.
- No foreign key from `downloads` to `items`, on purpose. A download row and its pinned item rows are written in one transaction (Task 3), and maintenance never deletes a pinned row, so no cascade from `items` is wanted. A download must never vanish because metadata was refreshed.

- [ ] **Tests first — `MigrationTest`:**
  - Build a real v3 database from `3.json` with a row in every v3 table. Migrate with `Migration3To4`. Every v3 row is byte-identical, column by column.
  - The seven tables accept rows, and each cascade fires: removing the server deletes `downloads`, `pinned_artwork` and `download_settings`; removing an account deletes its `download_owners` and `offline_plays`; deleting a download deletes its owners, tracks and segments.
  - `sqlite_master` SQL is identical for a migrated and a fresh v4 database, also for v1 → v4 through all three migrations.
- [ ] **Tests first — `DownloadCascadeTest`** goes through the real `ServerStore.removeAccount` and `removeServer`: the cascades above, and `items`, `user_data` and other accounts' rows untouched.
- [ ] Implement. Build once to export `4.json`, then copy its `createSql` strings into `Migration3To4` exactly (as `Migration2To3` did).
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.db.*"` → pass.
- [ ] Commit: `feat(downloads): Room v4 download tables with an explicit, tested migration`.

---

### Task 3: `DownloadStore` — queue rows, owners, pinning, and the offline library queries

**Files:**
- Create: `data/db/downloads/DownloadDao.kt`, `OfflineLibraryDao.kt`, `DownloadStore.kt`.
- Modify: `data/downloads/DownloadModels.kt` (the models below); `data/db/library/LibraryMaintenanceDao.kt` (a pinned series keeps its `seasons:<seriesId>` list).
- Test: `data/db/DownloadStoreTest.kt`, `data/db/DownloadPinningTest.kt`, `data/db/OfflineLibraryVisibilityTest.kt`, `data/db/LibraryMaintenanceTest.kt` (one new case), `data/db/LibraryPerformanceTest.kt` (one new plan assertion).

**Interfaces — Consumes:** `AppDatabase`, `ItemRow` + `LibraryMappers` (the existing joined read), `LibraryRepository.writeGated` (the library write lock: pins are written under it, so they never interleave with eviction).

**Produces:**
```kotlin
// data/downloads/DownloadModels.kt (added)
data class DownloadInfo(
    val itemId: String, val state: DownloadState, val failure: DownloadFailure?, val quality: DownloadQuality,
    /** 0..1 when known (bytes, or segments for a conversion), else null. */
    val progress: Float?, val totalBytes: Long?,
    val ownedByMe: Boolean, val storage: StorageKind,
    /** False when it was saved to an SD card that is not in the phone now. */
    val available: Boolean,
)
data class OfflineEntry(val item: ItemWithUserData, val download: DownloadInfo)
data class OfflineSeason(val season: ItemWithUserData?, val seasonNumber: Int?, val episodes: List<OfflineEntry>)
data class OfflineSeries(val series: ItemWithUserData?, val seasons: List<OfflineSeason>)
data class SeriesSummary(val series: ItemWithUserData, val episodeCount: Int, val bytes: Long)
data class OfflineLibrary(val inProgress: List<OfflineEntry>, val films: List<OfflineEntry>, val series: List<SeriesSummary>)

// data/db/downloads/DownloadStore.kt
internal class DownloadStore(private val db: AppDatabase, private val now: () -> Long) {
    class NewDownload(val itemId: String, val kind: ItemKind, val seriesId: String?, val seasonId: String?, val quality: DownloadQuality,
                      val storage: StorageKind, val volumeId: String?, val runtimeMs: Long?, val audioIndex: Int?)
    sealed interface Added { data object Queued : Added; data object OwnerAdded : Added; data object Skipped : Added }
    /** One transaction. The item must exist and be visible to [s]. An existing download only gains [s] as an owner. A new one is QUEUED at the end, and its item, season and series are pinned. */
    suspend fun add(s: Session, downloads: List<NewDownload>): Map<String, Added>
    /** Removes [s]'s ownership. With no owner left the row goes too (pins recomputed) and Deleted is returned so the caller removes the files. */
    suspend fun removeOwner(s: Session, itemId: String): RemoveOutcome
    /** The ownership rule after [serverId]'s account was removed (Task 12). Returns the downloads now deleted, whose files must go. */
    suspend fun afterAccountGone(serverId: String): List<DownloadKey>
    suspend fun delete(key: DownloadKey)                       // row + pins recomputed (a failed check, a removed server's leftovers)
    suspend fun next(): DownloadEntity?                         // lowest queuePosition in QUEUED
    suspend fun requeueInterrupted(): Int                       // RUNNING and VERIFYING -> QUEUED (process death, reboot)
    suspend fun update(key: DownloadKey, change: (DownloadEntity) -> DownloadEntity)   // read-modify-write in one transaction
    suspend fun setPaused(s: Session, itemId: String, paused: Boolean): Boolean        // owners only; QUEUED/RUNNING <-> PAUSED
    suspend fun retry(s: Session, itemId: String): Boolean                             // owners only; FAILED -> QUEUED, attempts 0
    suspend fun owners(key: DownloadKey): List<String>
    suspend fun sharing(serverId: String): Boolean
    suspend fun setSharing(serverId: String, on: Boolean)
    fun observeSharing(serverId: String): Flow<Boolean>
}
data class DownloadKey(val serverId: String, val itemId: String) { override fun toString() = "DownloadKey(<redacted>)" }
sealed interface RemoveOutcome { data object KeptForOthers : RemoveOutcome; data class Deleted(val key: DownloadKey) : RemoveOutcome; data object NotYours : RemoveOutcome }

// data/db/downloads/OfflineLibraryDao.kt — every query takes (serverId, userId) and joins visible_items for that user
abstract class OfflineLibraryDao {
    abstract fun observeFilms(serverId: String, userId: String): Flow<List<OfflineRow>>
    abstract fun observeSeries(serverId: String, userId: String): Flow<List<SeriesSummaryRow>>
    abstract fun observeSeriesEpisodes(serverId: String, userId: String, seriesId: String): Flow<List<OfflineRow>>
    abstract fun observeSeasons(serverId: String, userId: String, seriesId: String): Flow<List<ItemRow>>
    abstract fun observeInProgress(serverId: String, userId: String): Flow<List<OfflineRow>>    // owners only, not COMPLETE
    abstract fun observeOne(serverId: String, userId: String, itemId: String): Flow<OfflineRow?>
    abstract fun observeForSeries(serverId: String, userId: String, seriesId: String): Flow<List<OfflineRow>>  // any state, for badges
    internal abstract suspend fun getPlayable(serverId: String, userId: String, itemId: String): DownloadEntity?   // COMPLETE + visible + mine-or-shared
}
```
**SQL rules** (constants in `OfflineLibraryDao`, all SQLite 3.22-safe):
```sql
-- MINE_OR_SHARED (d = downloads):
(EXISTS (SELECT 1 FROM download_owners o WHERE o.serverId = d.serverId AND o.itemId = d.itemId AND o.userId = :userId)
 OR (d.state = 'COMPLETE' AND EXISTS (SELECT 1 FROM download_settings x WHERE x.serverId = d.serverId AND x.shareBetweenProfiles = 1)))
-- every row: FROM downloads d JOIN items i ON i.serverId = d.serverId AND i.itemId = d.itemId
--   JOIN visible_items v ON v.serverId = d.serverId AND v.userId = :userId AND v.itemId = d.itemId
--   LEFT JOIN user_data u ON u.serverId = d.serverId AND u.userId = :userId AND u.itemId = d.itemId
-- series and seasons: the series/season row must ALSO have a visible_items record for :userId
-- episode order: ItemDao.EPISODES_SQL's ORDER BY (numbered seasons, no number, Specials last; episode number, missing last; sort name; id)
-- films: ORDER BY i.nameKey, i.itemId; series: ORDER BY series nameKey, itemId; in progress: ORDER BY d.queuePosition
```
**Pins** (one statement, run on the affected ids after every add and delete):
```sql
UPDATE items SET pinned = CASE WHEN EXISTS (SELECT 1 FROM downloads d WHERE d.serverId = items.serverId
  AND (d.itemId = items.itemId OR d.seasonId = items.itemId OR d.seriesId = items.itemId)) THEN 1 ELSE 0 END
WHERE serverId = :serverId AND itemId IN (:ids)
```
**Maintenance change:** step (1) in `LibraryMaintenanceDao` no longer deletes the `list_entries` and `list_state` of `seasons:<seriesId>` when that series row is pinned, so the online series screen still lists its seasons after 30 days offline. The match is `listKey = 'seasons:' || i.itemId`, joined to a pinned `items` row of the same server.

- [ ] **Tests first — `DownloadStoreTest`** (in-memory Room, through the real `ServerStore` for accounts):
  - `add` queues at the end in call order.
  - A second profile adding the same item gets `OwnerAdded`, with no second row.
  - An item not visible to the session is `Skipped`, with nothing written.
  - `removeOwner` with two owners keeps the row (`KeptForOthers`). The last owner gets `Deleted`. A non-owner gets `NotYours`.
  - `requeueInterrupted` turns RUNNING and VERIFYING into QUEUED and leaves COMPLETE alone.
  - `setPaused` and `retry` refuse non-owners.
  - `update` runs in a transaction: a throw inside it leaves the row as it was.
- [ ] **Tests first — `DownloadPinningTest`:**
  - Adding an episode pins it, its season and its series.
  - A second episode of the same season, then deleting the first, keeps the season and series pinned.
  - Deleting the last one unpins all three.
  - A film pins only itself.
  - `LibraryStore.evict` with a 0-day policy keeps pinned rows, their visibility records and the pinned series's `seasons:` list, and deletes an unpinned series's list (the existing `LibraryMaintenanceTest` stays green).
  - Upserting the item from a server page leaves `pinned` alone.
- [ ] **Tests first — `OfflineLibraryVisibilityTest`.** The safeguarding rule, each case its own test:
  - (a) Profile A's download, sharing off: B sees nothing in any query, even with a visibility record.
  - (b) Sharing on, B has a record: B sees it once COMPLETE, not while RUNNING.
  - (c) Sharing on, B has NO record (a child profile the server never showed it to): B never sees it in films, series, seasons, episodes, `observeOne` or `getPlayable`.
  - (d) B has the episode's record but not the series's: the series is not listed for B.
  - (e) A's record is revoked (`LibraryStore.revokeVisibility`): A no longer sees A's own download.
  - (f) Another server's download with the same item id never appears.
  - (g) Watched state shown is the session user's `user_data`, never the owner's.
  - Each case is mutation-checked: remove the clause and the test fails (record it in the test's comment).
- [ ] **`LibraryPerformanceTest`:** with 5,000 items and 500 downloads, `observeSeriesEpisodes` uses the `(serverId, seriesId)` index on `downloads` and the primary keys of `items` and `visible_items`, with no scan of `items`.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.db.*"` → pass.
- [ ] Commit: `feat(downloads): download store with owners, pinning and per-profile visibility`.

---

### Task 4: Where files go — paths, volumes, free space, settings, and the orphan sweep

**Files:**
- Create: `data/downloads/DownloadPaths.kt`, `data/downloads/Volumes.kt` (+ `AndroidVolumes`), `data/downloads/DownloadPrefs.kt`.
- Test: `data/downloads/DownloadPathsTest.kt`, `data/downloads/AndroidVolumesTest.kt` (Robolectric), `data/downloads/DownloadPrefsTest.kt`, `data/downloads/OrphanSweepTest.kt`.

**Interfaces — Consumes:** `JellyfinId`, `Context` (adapter only).

**Produces:**
```kotlin
// data/downloads/Volumes.kt
data class StorageChoice(val kind: StorageKind, val volumeId: String?, val freeBytes: Long, val available: Boolean)
interface Volumes {
    /** The downloads root for [kind] (and [volumeId] for REMOVABLE), creating it with a `.nomedia` file; null when not mounted. */
    fun root(kind: StorageKind, volumeId: String?): File?
    fun choices(): List<StorageChoice>                         // INTERNAL always; each mounted removable volume
    /** Bytes that may be written: StorageManager.getAllocatableBytes for INTERNAL, File.usableSpace for REMOVABLE. */
    fun freeBytes(kind: StorageKind, volumeId: String?): Long
    /** Reserve the space before an original download starts (StorageManager.allocateBytes on INTERNAL; no-op on REMOVABLE). */
    fun reserve(file: File, bytes: Long)
    fun allRoots(): List<File>                                 // every mounted root, for the orphan sweep
}
class AndroidVolumes(context: Context) : Volumes
// INTERNAL: filesDir/downloads. REMOVABLE: ContextCompat.getExternalFilesDirs(context, null) entries after index 0 whose
// StorageManager.getStorageVolume(dir)?.isRemovable is true and Environment.getExternalStorageState(dir) == MEDIA_MOUNTED,
// as <dir>/downloads. volumeId = StorageVolume.uuid (validated [A-Za-z0-9-]{1,36}; a volume without one is not offered).

// data/downloads/DownloadPaths.kt — the ONLY code that turns ids into file names
internal class DownloadPaths(private val volumes: Volumes) {
    fun itemFolder(key: DownloadKey, storage: StorageKind, volumeId: String?): File?   // <root>/<serverId>/<itemId>
    fun mediaFile(folder: File, container: String): File                               // media.<ext>
    fun partFile(folder: File, container: String): File                                // media.<ext>.part
    fun hlsFolder(folder: File): File                                                  // hls/
    fun segmentFile(hls: File, index: Int): File                                       // hls/seg-00000.ts .. seg-19999.ts
    fun localPlaylist(hls: File): File                                                 // hls/local.m3u8
    fun subtitleFile(folder: File, streamIndex: Int, fileFormat: String): File         // sub-<0..10000>.<srt|vtt|ass|ssa>
    fun artworkFile(serverId: String, itemId: String, slot: ArtworkSlot, tag: String): File   // INTERNAL <root>/<serverId>/art/<itemId>-<SLOT>-<tag>.img
    fun serverFolders(serverId: String): List<File>                                    // on every mounted root
    /** Deletes [target] and everything under it without following links, only if its canonical path is inside a root. */
    fun deleteInside(target: File): Boolean
    companion object { val MEDIA_EXTENSIONS = setOf("mp4", "m4v", "mov", "mkv", "webm", "ts") }
}
// Every builder `require`s: ids equal their JellyfinId-normalised form (32 lower-case hex), tag [A-Za-z0-9]{1,64}, stream index 0..10000,
// extension/format in the fixed sets; then the canonical path must start with the root's canonical path + separator. Fixed messages.

// data/downloads/DownloadPrefs.kt — private SharedPreferences "download_prefs"
data class DownloadSettings(val mobileData: Boolean = false, val storage: StorageKind = StorageKind.INTERNAL, val volumeId: String? = null,
                            /** null = ask each time */ val defaultQuality: DownloadQuality? = null)
class DownloadPrefs(prefs: SharedPreferences) {
    val settings: StateFlow<DownloadSettings>
    fun setMobileData(on: Boolean); fun setStorage(kind: StorageKind, volumeId: String?); fun setDefaultQuality(q: DownloadQuality?)
}
```
- **Orphan sweep** (`DownloadPaths.sweep(known: Set<DownloadKey>, servers: Set<String>)`, called by housekeeping, Task 12). Under each mounted root:
  - delete a `<serverId>` folder whose server is not saved;
  - delete an `<itemId>` folder with no download row;
  - delete an `art/` file with no `pinned_artwork` row;
  - delete anything whose name does not match the patterns above.
  - It never follows a link, never leaves the root, and is bounded to 100,000 entries per run.

- [ ] **Tests first — `DownloadPathsTest`** (`TemporaryFolder`):
  - Every builder refuses a non-normalised id, `..`, `/`, `\`, `%2e`, a NUL character, an upper-case GUID, a 31-character id, a tag with `-` or `.`, an index of -1 or 10,001, and an extension outside the set. Each refusal has its fixed message.
  - A symlink planted inside an item folder that points outside the root is removed as a link, and its target survives.
  - `deleteInside` refuses a path outside every root.
- [ ] **Tests first — `AndroidVolumesTest`** (Robolectric `ShadowStorageManager`/`ShadowEnvironment`):
  - INTERNAL is always offered.
  - A removable mounted volume with a uuid is offered; an unmounted one, or one with no uuid, is not.
  - `freeBytes` falls back to `usableSpace` when `getAllocatableBytes` throws.
  - `.nomedia` is created in each root.
- [ ] **Tests first — `OrphanSweepTest`:** the four deletions above; known folders are kept; a file named like a GUID but outside the patterns is deleted; a root that vanished mid-sweep does not throw.
- [ ] **Tests first — `DownloadPrefsTest`:** defaults are mobile data off, INTERNAL and ask each time; changes persist and emit; an unknown stored value reads as the default.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.downloads.*"` → pass.
- [ ] Commit: `feat(downloads): file names only from validated ids, inside app-private storage`.

---

### Task 5: The download call path — `downloadRoute`, `DownloadGateway` and the playlist parser

**Files:**
- Create: `data/downloads/api/DownloadDtos.kt`, `DownloadGateway.kt` (+ `DownloadGatewayImpl`), `data/downloads/HlsPlaylists.kt`.
- Modify: `data/api/ServerApi.kt` (`downloadRoute`), `data/api/MediaRoute.kt` (`GuardedCallFactory` takes `exactPath`).
- Test: `data/api/DownloadRouteTest.kt`, `data/downloads/DownloadGatewayTest.kt`, `data/downloads/DownloadGatewayHostileTest.kt`, `data/downloads/HlsPlaylistsTest.kt`; fixtures `app/src/test/resources/jellyfin/downloads/`.

**Interfaces — Consumes:** `ServerApi.mediaRoute`'s internals, `JsonBodies`, `JellyfinId`, `MediaPaths.checkServerPath`.

**Produces:**
```kotlin
// data/api/ServerApi.kt
/**
 * mediaRoute's steps 1-3 exactly. The guard allows the /videos/ prefix as now AND, for GET/HEAD only, the exact path
 * <base>/Items/<itemId>/Download for this one validated [itemId] (the server's own download permission applies there).
 */
internal suspend fun downloadRoute(session: Session, itemId: String): MediaRoute

// data/api/MediaRoute.kt
internal class GuardedCallFactory(delegate: Call.Factory, authority: String, videosPrefix: String, scheme: String,
    /** Decoded segments of one more allowed path, compared exactly (case-sensitive except "Items"/"Download"), or null. */
    exactPath: List<String>? = null, stale: () -> AppError.Kind?)

// data/downloads/api/DownloadGateway.kt
/** [canDownload]: true, false, or null when the server did not say (Task 1: null allows the original only). */
internal data class DownloadFacts(val itemId: String, val canDownload: Boolean?, val sourceId: String?, val container: String?, val sizeBytes: Long?, val eTag: String?)
internal interface DownloadGateway {
    /** GET /Items?ids=<up to 50, comma-delimited>&userId=&fields=CanDownload,MediaSources&enableUserData=false. Missing ids are absent from the answer. */
    suspend fun facts(s: Session, itemIds: List<String>): List<DownloadFacts>
}

// data/downloads/HlsPlaylists.kt (pure)
internal object HlsPlaylists {
    sealed interface Parsed {
        data class Master(val variant: StreamSource) : Parsed
        data class Media(val targetSeconds: Int, val segments: List<Segment>) : Parsed
        data class Refused(val reason: String) : Parsed       // a fixed word, never content
    }
    data class Segment(val source: StreamSource, val durationMs: Long)
    /** [playlistPath] is the validated server path the text came from: relative URIs resolve against it, then pass MediaPaths.checkServerPath. */
    fun parse(text: String, playlistPath: String): Parsed
    /** The app's own VOD playlist naming seg-00000.ts... by index only. */
    fun writeLocal(segmentsMs: List<Long>): String
}
```
**Rules:**
- `facts`:
  - ids are normalised and refused before sending if not GUIDs; at most 50 per call; body at most 4 MiB.
  - Elements are decoded one at a time (as `LibraryGatewayImpl`).
  - `CanDownload` must be a JSON boolean to count; absent, null or any other type is `null` (unknown), which Task 6 treats as "original only" (Task 1's rule).
  - Only `MediaSources[0]` is read: `Id` and `ETag` must match `[A-Za-z0-9]{1,64}`, else null. `Container` is the first comma-separated name, lower-cased, `[a-z0-9]{1,16}`. `Size` is kept only within 1..274,877,906,944 (256 GiB), else null.
  - Duplicate ids keep the first.
- **Playlists:**
  - At most 2 MiB of text and 20,000 segments.
  - It must start with `#EXTM3U`. A master playlist has exactly one usable `#EXT-X-STREAM-INF` variant; the first is taken.
  - A media playlist must be VOD (`#EXT-X-PLAYLIST-TYPE:VOD` or `#EXT-X-ENDLIST`).
  - Each `#EXTINF` lies in 0 < d ≤ 60 s.
  - Refused outright: `#EXT-X-KEY` (other than `METHOD=NONE`), `#EXT-X-MAP`, `#EXT-X-BYTERANGE`, `#EXT-X-DISCONTINUITY` (none expected in Jellyfin's VOD output; if a real server sends any, the conversion fails plainly as `SERVER_ERROR` rather than saving something that may not play), absolute URIs, `//`, any segment path not under `/videos/` after resolution, and any segment URI not ending `.ts`.
  - Credential query names are stripped by `checkServerPath`.
  - Lines are cut to 4,096 characters; any other control character refuses the whole playlist.

- [ ] **Tests first — `DownloadRouteTest`** (real `ServerApi`, `ServerClients`, `HttpClientFactory`, MockWebServer, including a pinned self-signed server):
  - `GET <base>/Items/<id>/Download` is sent with `Authorization` and custom headers.
  - The same path for ANOTHER item id is refused, as are `/Items/<id>/Download/../File`, `/Items/<id>/File`, `/items/<id>/download` with a different id, POST, and another authority.
  - `/videos/...` still works.
  - A base path (`/jellyfin`) is kept.
  - A stale route refuses as before.
  - The existing `MediaRouteTest` is unchanged and green.
- [ ] **Tests first — `DownloadGatewayTest` / `DownloadGatewayHostileTest`:**
  - The exact query: `ids` comma-delimited, `fields=CanDownload,MediaSources`, `userId`, `enableUserData=false`.
  - The fixture is mapped field by field.
  - `CanDownload` true, false, absent (null), JSON null (null) and a string (null) map as stated;
  - Hostile cases: `Size` negative, over 256 GiB or fractional; an `Id` with `/`; `Container` `mp4,mov` (becomes `mp4`); a 5 MiB body (refused); 51 ids (chunked into two calls).
- [ ] **Tests first — `HlsPlaylistsTest`** (fixtures):
  - Jellyfin's shape parses, and the segment `api_key` is stripped.
  - Each refusal above is its own case: `hostile-key.m3u8`, `hostile-absolute.m3u8`, `hostile-escape.m3u8` (`../../System/Info`), `hostile-huge.m3u8` (generated), `hostile-live.m3u8` (no ENDLIST), `hostile-byterange.m3u8`.
  - `writeLocal` output parses back with the same durations.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.api.*" --tests "app.shoalwatch.data.downloads.*"` → pass.
- [ ] Commit: `feat(downloads): a download route that reaches only this item's file, and bounded playlist parsing`.

---

### Task 6: The planner — what one press will download, how big it is, and whether it will play

**Files:**
- Create: `data/downloads/OriginalCheck.kt`, `data/downloads/DownloadPlanner.kt`.
- Modify: `data/playback/PlaybackResolver.kt` (`checkOriginal`); `data/downloads/DownloadModels.kt` (plan types).
- Test: `data/downloads/OriginalCheckTest.kt`, `data/downloads/DownloadPlannerTest.kt`.

**Interfaces — Consumes:**
- `Library.refresh(s, RefreshTarget.Series(seriesId, seasonId))` and `RefreshTarget.Item(itemId)`: the existing machinery writes the metadata and the visibility records.
- `ItemDao.getEpisodes` / `getItem` (visibility-filtered reads).
- `DownloadGateway.facts`, `PlaybackResolver.checkOriginal`, `Volumes`, `DownloadPrefs`, `DownloadDao.get(serverId, itemId)` (is it already on this phone, whoever owns it).

**Produces:**
```kotlin
// data/downloads/DownloadModels.kt (added)
sealed interface DownloadTarget {
    data class Item(val itemId: String) : DownloadTarget                          // a film or one episode
    data class Season(val seriesId: String, val seasonId: String) : DownloadTarget
    /** Every numbered season and episodes with no season; Specials (season 0) are left out (they have their own season action). */
    data class Series(val seriesId: String) : DownloadTarget
    /** Every unwatched episode of the series for this user, Specials left out. */
    data class Unwatched(val seriesId: String) : DownloadTarget
}
/**
 * NOT_ALLOWED: CanDownload false, or a 403 from checkOriginal. CONVERSION_UNCONFIRMED: CanDownload unknown and a conversion was needed
 * (asked for, or because the original cannot play here). NOT_FOUND: the server did not return the item.
 */
enum class SkipReason { NOT_ALLOWED, CONVERSION_UNCONFIRMED, NOT_FOUND }
data class Skipped(val itemId: String, val reason: SkipReason)
data class PlannedItem(val itemId: String, val quality: DownloadQuality, val estimatedBytes: Long, val shareOnly: Boolean)
data class DownloadPlan(
    val session: Session, val requested: DownloadQuality, val items: List<PlannedItem>, val skipped: List<Skipped>,
    /** Originals this phone cannot play as they are: planned as HIGH instead (the user sees this before confirming). */
    val convertedInstead: Int,
    val totalBytes: Long, val freeBytes: Long, val enoughSpace: Boolean, val storage: StorageKind, val volumeId: String?,
    val mobileData: Boolean,
) { override fun toString() = "DownloadPlan(items=${items.size})" }

// data/downloads/OriginalCheck.kt (pure)
internal sealed interface OriginalCheck {
    data class Playable(val sourceId: String, val container: String, val sizeBytes: Long?, val eTag: String?,
                        val audio: List<AudioTrack>, val subtitles: List<SubtitleTrack>, val defaultAudio: Int?) : OriginalCheck
    data object NotPlayableHere : OriginalCheck
}
internal object OriginalChecks { fun of(info: PlaybackInfo): OriginalCheck }   // first source; SupportsDirectPlay; container in MEDIA_EXTENSIONS

// data/playback/PlaybackResolver.kt (added)
/** PlaybackInfo with this phone's profile at MAXIMUM, EnableDirectPlay true, EnableDirectStream and EnableTranscoding false: no conversion is set up. Throws AppError. */
internal suspend fun checkOriginal(s: Session, itemId: String): PlaybackInfo

// data/downloads/DownloadPlanner.kt
internal class DownloadPlanner(
    private val library: Library,
    private val items: ItemDao,
    private val gateway: DownloadGateway,
    private val checkOriginal: suspend (Session, String) -> PlaybackInfo,      // PlaybackResolver::checkOriginal
    private val downloads: DownloadDao,
    private val volumes: Volumes,
    private val prefs: DownloadPrefs,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxItems: Int = 500,
    private val concurrency: Int = 4,
) {
    /** Online only (OFFLINE and the other AppError kinds are thrown as they come). Writes metadata through Library.refresh; adds nothing to the queue. */
    suspend fun plan(s: Session, target: DownloadTarget, quality: DownloadQuality): DownloadPlan
}
```
**Rules:**
- **Expanding the target.** `Item`: refresh the item, and for an episode also its series (`RefreshTarget.Series(seriesId, seasonId)`), so the season and series rows exist and are visible. `Season`: refresh that season. `Series` / `Unwatched`: refresh the series, then each numbered season's episodes. Then read the episodes from Room (`getEpisodes`, already in the app's order). Unwatched means `userData.played == false` for this session. At most 500 items: a longer series is not an error; the plan holds the first 500 in episode order, and the UI says "Only the first 500 episodes are included."
- **Allowed** (Task 1's correct-either-way rule). `facts` in chunks of 50. An id that is absent → `NOT_FOUND`.
  - `canDownload == false` → `Skipped(NOT_ALLOWED)`.
  - `canDownload == true` → original or conversion.
  - `canDownload == null` (the server did not say) → the original only, because `/Items/{id}/Download` enforces the server's own permission and a 403 there is `NOT_ALLOWED` (Task 7). A conversion asked for, or needed because the original cannot play here, is `Skipped(CONVERSION_UNCONFIRMED)`.
- **Already here.** A download already on the device for (server, item) → `PlannedItem(shareOnly = true, estimatedBytes = 0)`: confirming only adds this profile as an owner (SPEC §3.3 "stored once"; nobody downloads it again). The planner asked the server for this profile's own `CanDownload`, so it is this profile's permission that counts.
- **Playable.**
  - For ORIGINAL: `checkOriginal` per item, at most 4 at a time. `NotPlayableHere` → planned as HIGH, counted in `convertedInstead`, unless `canDownload` is null (then `CONVERSION_UNCONFIRMED`).
  - For a conversion nothing is asked now: the device profile forces h264/aac in MPEG-TS, which every Android phone decodes. The real PlaybackInfo is asked when the item starts.
- **Size.**
  - ORIGINAL uses `facts.sizeBytes`, else `checkOriginal`'s; unknown counts as `runtime × 25 Mbps` (stated as an estimate).
  - A conversion uses `quality.maxBitrate / 8 × runtimeSeconds`; unknown runtime counts as 45 minutes.
- **Space.** `freeBytes` comes from `Volumes.freeBytes` for the chosen storage. `enoughSpace = totalBytes + reserve <= freeBytes`, with a reserve of 1 GiB on INTERNAL and 256 MiB on REMOVABLE.

- [ ] **Tests first — `OriginalCheckTest`** (Milestone 3's PlaybackInfo fixtures through the real mappers): direct-play mp4 is Playable with its tracks; `SupportsDirectPlay` false is NotPlayableHere; a container outside the set (`avi`) is NotPlayableHere; a refusal (`NotAllowed`) is thrown as its AppError.
- [ ] **Tests first — `DownloadPlannerTest`** (fake gateway, `FakeLibrary` writing into a real in-memory Room, fake resolver check, `FakeVolumes`):
  - A season of 6 with one `CanDownload:false` gives 5 planned and 1 skipped.
  - `CanDownload` null: ORIGINAL is planned; HIGH is `CONVERSION_UNCONFIRMED`; an original that cannot play here is `CONVERSION_UNCONFIRMED`, not converted.
  - Series leaves Specials out; Unwatched leaves out watched episodes and Specials.
  - An original that cannot play here becomes HIGH, with `convertedInstead == 1`.
  - Sizes are summed from `Size`, and the estimates are as above.
  - Not enough space gives `enoughSpace == false`.
  - An episode already downloaded by another profile is `shareOnly`.
  - 600 episodes plans 500.
  - Offline throws `AppError(OFFLINE)` and writes nothing to the queue.
  - At most 4 `checkOriginal` calls are in flight (gate in the fake).
  - Every item planned is visible to the session in Room afterwards.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.downloads.*" --tests "app.shoalwatch.data.playback.PlaybackResolverTest"` → pass.
- [ ] Commit: `feat(downloads): plan a season or series with sizes, permissions and a playability check`.

---

### Task 7: Resumable fetching — the original file and a converted HLS download

**Files:**
- Create: `data/downloads/FileFetcher.kt`, `data/downloads/HlsFetcher.kt`.
- Test: `data/downloads/FileFetcherTest.kt`, `data/downloads/FileFetcherHostileTest.kt`, `data/downloads/HlsFetcherTest.kt`.

**Interfaces — Consumes:** `MediaRoute` (`url`, `callFactory`), `Resolved` (a conversion plan and its route), `DownloadPaths`, `Volumes.reserve`, `HlsPlaylists`, `MonotonicClock`.

**Produces:**
```kotlin
internal sealed interface FetchResult {
    data object Done : FetchResult
    data class Failed(val failure: DownloadFailure) : FetchResult      // not worth retrying by itself
    data class Retry(val kind: AppError.Kind) : FetchResult            // network or server trouble: the worker backs off
    /** 401 on a media request. The queue decides between SIGN_IN_NEEDED and SERVER_AUTH_REFUSED (Task 10). */
    data object Unauthorised : FetchResult
    /** A converted download's play session can no longer be trusted: drop it and start that item's conversion from segment 0. */
    data object RestartConversion : FetchResult
}
/** Progress callback: saved bytes, only after they are on disk and fsync'd. Called at most every 2 s or 8 MiB. */
internal fun interface Progress { suspend fun saved(doneBytes: Long, totalBytes: Long?, validator: String?, restarts: Int) }

internal class FileFetcher(private val io: CoroutineDispatcher, private val volumes: Volumes, private val chunk: Int = 256 * 1024) {
    /**
     * GET <route base>/Items/<itemId>/Download into media.<ext>.part. It resumes from [resumeFrom] (the last fsync'd byte count in
     * Room; the file is truncated to it first) with `Range: bytes=<resumeFrom>-`, plus `If-Range: <validator>` when one is known.
     * Whether or not the server honours either header, the result is correct (Task 1). On success the part file is renamed to
     * media.<ext>. [expectedBytes]: MediaSourceInfo.Size when known. [restarts]: validator-change restarts so far.
     */
    suspend fun fetch(route: MediaRoute, itemId: String, part: File, target: File, resumeFrom: Long, validator: String?,
                      expectedBytes: Long?, restarts: Int, progress: Progress): FetchResult
}

/** One play session's conversion: held in memory only, by the queue. Never written to disk, so two sessions are never mixed. */
internal class LiveConversion internal constructor(
    val itemId: String, val resolved: Resolved, val segments: List<HlsPlaylists.Segment>, val startedAtMs: Long,
) { override fun toString() = "LiveConversion(<redacted>)" }

internal class HlsFetcher(private val io: CoroutineDispatcher, private val clock: MonotonicClock) {
    /**
     * A fresh conversion for [resolved] (a TRANSCODE plan with a validated HLS source): deletes hls/, fetches the master and
     * media playlists through the plan's route, checks the duration sum, and writes hls/local.m3u8. Returns the live conversion,
     * or a FetchResult that is not Done.
     */
    suspend fun open(resolved: Resolved, folder: File, runtimeMs: Long?): Pair<LiveConversion?, FetchResult>
    /** seg-<n>.ts from [fromSegment] for [live] only. A 404 or 5xx on a segment is RestartConversion (the server dropped the session). */
    suspend fun fetch(live: LiveConversion, folder: File, fromSegment: Int, progress: suspend (done: Int, total: Int) -> Unit): FetchResult
}
```
**Rules for `FileFetcher`** (correct whatever the server does with `Range`; see Task 1):
- **Request:** `Accept-Encoding: identity`, so OkHttp does not decompress and byte counts are file bytes.
- **Content-Type:** refused only when it is surely not media (`text/*`, anything containing `html`, `application/json`, `application/xml`), checked before anything is written, → `Failed(SERVER_ERROR)`. Every other type, or none, is accepted; the verifier (Task 9) decides by the file's own bytes.
- **206** (only valid in answer to a range request) needs `Content-Range: bytes <resumeFrom>-<end>/<total>` with an exact start, `end == total-1` and `total` in 1..256 GiB, equal to `expectedBytes` when that is known.
  - A malformed `Content-Range`, or a 206 to a request that sent no `Range`, → truncate to 0 and restart. This is an attempt, not a validator restart.
  - A total that disagrees with `expectedBytes` → `Failed(CHANGED_ON_SERVER)`.
- **200 while resuming:** the server ignored `Range`, or `If-Range` did not match. Truncate to 0 and write the whole body from the start; either way the file ends complete.
  - The restart counts towards `CHANGED_ON_SERVER` only when the response's validator differs from the stored one. Three such restarts in a row → `Failed(CHANGED_ON_SERVER)`.
  - A server that simply never honours `Range` (same or no validator) restarts every time, which is slow but correct. Each restart costs one queue attempt (Task 10).
- **200 on a first fetch** needs `Content-Length` equal to `expectedBytes` when both are known. Chunked with no length is allowed only when `expectedBytes` is known, and is cut off one byte past it.
- **416** with a part file already equal to `expectedBytes` (or the stored total) → Done. Otherwise truncate and restart.
- **Other statuses:** 401 → `Unauthorised`; 403 → `Failed(NOT_ALLOWED)` (the server's own download permission wins, whatever `CanDownload` said); 404 → `Failed(GONE_FROM_SERVER)`; 3xx → `Retry(SERVER_NOT_FOUND)`; 5xx → `Retry(SERVER_ERROR)`.
- **Errors:** a `BlockedRequestException` from the route → `Retry(OFFLINE)` (stale; the queue asks for a new route) or `Failed(SIGN_IN_NEEDED)` for `NOT_SIGNED_IN`. An IOException before or while reading → `Retry(NETWORK)`, keeping what was fsync'd.
- **Writing:** through a `FileChannel` opened for append. `channel.force(false)` before each progress save, so Room never claims bytes that are not on disk. Never more bytes than the total.
- **Space:** on a first start of an INTERNAL download, `Volumes.reserve(part, total)`. An `IOException` with ENOSPC (`ErrnoException.errno == OsConstants.ENOSPC` in the cause chain) → `Failed(NO_SPACE)`.
- **Validator:** the response's `ETag` (as sent, quotes kept), else `Last-Modified`, if printable ASCII of 1..128 characters, else null (then no `If-Range` is sent).

**Rules for `HlsFetcher`** (correct whether or not a server cuts the same segments twice; see Task 1):
- **Segments are fetched only within the play session that produced their playlist.** A `LiveConversion` exists only in memory and only for that session. Nothing about a session is saved, so after process death or a reboot `open` is always a fresh start from segment 0: the old segments are deleted first. A recutting server and a stable one give the same, correct result.
- Playlists come via the plan's route (GET, at most 2 MiB).
- Segments: each at most 64 MiB; `Content-Type` refused only by the same "surely not media" rule; the first byte must be `0x47`; a `Content-Length`, when sent, must match the body. Each is written to `seg-<n>.ts.part`, fsync'd and renamed, and only then is progress saved.
- **Failures:** a 404 or 5xx on a segment → `RestartConversion` (the server reaped the session or failed). An IOException → `Retry(NETWORK)`; the queue keeps the live conversion and resumes the same session. 401 → `Unauthorised`. A segment is never retried in a loop here.
- The total of the durations must be within ±10% of the item's runtime when known, else `Failed(SERVER_ERROR)`.

- [ ] **Tests first — `FileFetcherTest`** (MockWebServer through a real route, `TemporaryFolder`). **Each server behaviour from Task 1 is its own test, and every one ends with a byte-identical file:**
  - **Default:** a whole file arrives and the `.part` is renamed.
  - **Drop then resume, server honours `Range`:** the connection drops at 64 KiB (`SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY`); the second call sends `Range: bytes=<saved>-` and `If-Range` with the ETag and gets 206.
  - **Drop then resume, server ignores `Range` (the `--no-range` behaviour):** the second call gets 200 and restarts from 0; the restart is not counted as a validator change.
  - **Validator changes every time (`--etag-changes`):** restart from 0; three in a row → `CHANGED_ON_SERVER`.
  - **`application/octet-stream`** and **no Content-Type** are accepted.
  - A torn tail (part file longer than the saved count) is truncated before the resume.
  - 416 with a complete part is Done.
  - Progress is never called with more than is on disk (a fake channel records force calls).
  - Cancelling mid-body cancels the call and keeps the saved count.
  - No request has `api_key`, and every request has `Authorization`.
- [ ] **Tests first — `FileFetcherHostileTest`:**
  - `text/html` 200; a 206 to a request with no `Range`; `Content-Range` with the wrong start, a wrong total or garbage.
  - `Content-Length` disagreeing with `Size`; a body longer than declared (cut off, `CHANGED_ON_SERVER`).
  - A gzip `Content-Encoding` (refused: `SERVER_ERROR`).
  - A 301 to another host (not followed).
  - 401 → `Unauthorised`; 403 with `CanDownload` true → `NOT_ALLOWED`.
  - A validator of 129 characters or with a control character (not kept).
  - ENOSPC from a fake channel → `NO_SPACE`.
- [ ] **Tests first — `HlsFetcherTest`:**
  - Master → media → 5 segments → `local.m3u8` parses with the 5 durations.
  - **A network drop at segment 3 within the same live conversion** resumes at 3 with the same play session (the mock's request log shows one session id).
  - **A fresh `open` after a "restart"** (a new fetcher over the same folder: process death) deletes segments 0–2 and fetches 0–4 from the new session. Run once with a stable server and once with `--hls-recut` behaviour: both end with a playlist that matches its own segments, and never with segments of two sessions.
  - **Session reaped (`--hls-idle-kill`):** a 404 on segment 2 → `RestartConversion`.
  - A segment that is HTML, starts with a byte other than `0x47`, or exceeds 64 MiB is refused.
  - A playlist naming `/System/Info` is refused before any segment request.
  - A duration sum off by 20% → `SERVER_ERROR`.
  - 401 on a segment → `Unauthorised`.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.downloads.*"` → pass.
- [ ] Commit: `feat(downloads): downloads that resume byte for byte, and converted downloads saved segment by segment`.

---

### Task 8: Extras — tracks, subtitles, skip segments and pinned artwork

**Files:**
- Create: `data/downloads/ExtrasFetcher.kt`, `data/images/PinnedArtwork.kt`.
- Modify: `data/images/ArtworkFetcher.kt` (the pinned copy is consulted after a disk-cache miss and before the network; the request is shared with the saver); `AppGraph.kt` (`pinnedArtwork` into `ArtworkLoader.build`).
- Test: `data/downloads/ExtrasFetcherTest.kt`, `data/images/PinnedArtworkTest.kt`, `data/images/ArtworkFetcherTest.kt` (two new cases).

**Interfaces — Consumes:** `PlaybackGateway.subtitleFile`, `PlaybackGateway.segments`, `ServerApi.getBytes`, `ArtworkLimits` and `ArtworkValidation` (the same checks, unchanged), `DownloadPaths`, `DownloadDao`.

**Produces:**
```kotlin
internal class ExtrasFetcher(
    private val gateway: PlaybackGateway,          // subtitleFile, segments
    private val api: ServerApi,                    // getBytes for artwork
    private val items: ItemDao,                    // the season and series rows and their image tags
    private val downloads: DownloadDao,            // download_tracks, download_segments, pinned_artwork rows
    private val paths: DownloadPaths,
    private val pinned: PinnedArtwork,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxSubtitles: Int = 30,
) {
    /** Saves the track list (every audio track; every subtitle track, with TEXT/STYLED_TEXT files saved; PICTURE only for ORIGINAL). Best effort per file. */
    suspend fun tracks(s: Session, key: DownloadKey, folder: File, mediaSourceId: String, audio: List<AudioTrack>, subtitles: List<SubtitleTrack>, quality: DownloadQuality)
    suspend fun segments(s: Session, key: DownloadKey)                     // at most 200, as the gateway already bounds them
    /** PRIMARY, BACKDROP, THUMB of the item, its season and its series, where a tag exists: 720 px wide (BACKDROP 1280). Skips what is already pinned with the same tag. */
    suspend fun artwork(s: Session, itemIds: List<String>)
}
// data/images/PinnedArtwork.kt
class PinnedArtwork internal constructor(private val downloads: DownloadDao, private val paths: DownloadPaths, private val io: CoroutineDispatcher = Dispatchers.IO) {
    /** The saved bytes for this ref's (server, item, type, tag), re-checked with the artwork rules; null if none or if they fail (then deleted). */
    internal suspend fun find(ref: ArtworkRef): ByteArray?
    internal suspend fun save(serverId: String, itemId: String, slot: ArtworkSlot, tag: String, bytes: ByteArray)
}
```
**Rules:**
- **Subtitles:**
  - Saved through `PlaybackGateway.subtitleFile`, which already caps them at 8 MiB and checks the type, as `sub-<index>.<fileFormat>`; at most 30 tracks.
  - Each file is written to a `.part`, fsync'd and renamed.
  - A failed subtitle leaves `fileSaved = false`. It does not fail the download (the item still plays; that track says "Not saved with this download").
  - A converted download keeps the one audio track it was made with. Its PICTURE tracks are not saved, because HLS from the server carries none and a burn-in would fix one choice forever. The UI says so before the download starts.
- **Artwork:**
  - Uses the same request as `ArtworkFetcher` (path, `tag`, `maxWidth`, `quality=90`, `format=Webp`) and the same eight checks, so no new parser touches server bytes.
  - Saved only on INTERNAL storage, ever (small, and readable by nothing else).
  - At most 3 × 3 pictures per item.
- **Read path:** in `ArtworkFetcher`, memory → disk cache → `PinnedArtwork.find` → network. The session check still runs first, so a signed-out profile sees no pinned picture either. An episode's fallback to the series poster finds the pinned series poster.

- [ ] **Tests first — `ExtrasFetcherTest`** (fake gateway, MockWebServer for artwork):
  - Three subtitles (srt, ass, a PGS): two files are saved for ORIGINAL; the PGS is listed as in-stream with no file.
  - For HIGH the PGS is not listed.
  - A subtitle that fails leaves `fileSaved = false` and the rest are saved.
  - The segment rows are saved.
  - The artwork of an episode, its season and its series is saved once, even for 6 episodes of one season.
  - An image that fails a check (SVG, trailing bytes) is not saved.
  - File names contain only the id, slot and tag.
- [ ] **Tests first — `PinnedArtworkTest` / `ArtworkFetcherTest`:**
  - Offline, with no disk-cache entry and a pinned file, the picture decodes with no request.
  - A tampered pinned file (bytes changed) is refused, deleted, and the placeholder shows.
  - A signed-out session gets `NOT_SIGNED_IN` before the pinned lookup.
  - Online, with a pinned copy for the same tag, no request is sent.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.downloads.*" --tests "app.shoalwatch.data.images.*"` → pass (all existing artwork tests unchanged).
- [ ] Commit: `feat(downloads): subtitles, skip markers and artwork kept with every download`.

---

### Task 9: Checked playable — the verifier and the Media3 probe

**Files:**
- Create: `data/downloads/DownloadVerifier.kt`, `data/playback/engine/SavedMediaProbe.kt`, `data/playback/engine/ItemBytes.kt`, `data/playback/engine/SavedMediaDataSource.kt`, `data/downloads/SavedMedia.kt`.
- Test: `data/downloads/DownloadVerifierTest.kt`, `data/playback/engine/ItemBytesTest.kt` (Robolectric), `data/playback/engine/SavedMediaProbeTest.kt` (Robolectric, with `app/src/test/resources/media/clip.mp4` copied from Milestone 3's test media plus its 5 HLS segments).

**Interfaces — Consumes:** `DownloadPaths`, `CodecSupport` (Milestone 3's counted decoders), Media3 `MetadataRetriever`.

**Produces:**
```kotlin
// data/downloads/SavedMedia.kt
/** What the player may open for one download: the folder it may read inside and the entry file. Never printed. */
class SavedMedia internal constructor(val serverId: String, val itemId: String, internal val folder: File, internal val entry: File,
                                      val hls: Boolean, val quality: DownloadQuality, internal val mediaSourceId: String) {
    override fun toString() = "SavedMedia(<redacted>)"
}

// data/playback/engine/SavedMediaProbe.kt
internal data class ProbeResult(val videoMimes: List<String>, val audioMimes: List<String>, val durationMs: Long?)
internal fun interface PlayabilityProbe { suspend fun probe(media: SavedMedia): ProbeResult? }   // null: Media3 could not read it
/**
 * MetadataRetriever over DefaultMediaSourceFactory(ItemBytes.Factory(OkHttpDataSource.Factory(RefuseAll), { item })), so it can
 * read only this item's folder and nothing over the network. Uses MetadataRetriever.Builder(context, mediaItem)
 * .setMediaSourceFactory(...).build() with retrieveTrackGroups() and retrieveDurationUs() (Media3 1.8+). Check the 1.9.2 API
 * reference before writing; if the builder is absent, use the static MetadataRetriever.retrieveMetadata(factory, mediaItem).
 * Waits at most 30 s.
 */
internal class SavedMediaProbe(context: Context) : PlayabilityProbe

// data/playback/engine/ItemBytes.kt
/**
 * The one DataSource the player and the probe use. http(s) URIs are passed to [remote] only while the current EngineItem is
 * remote; file URIs are opened with SavedMediaDataSource only while the current item is saved AND the canonical path is inside
 * its folder. Anything else fails at open() with an IOException wrapping BlockedRequestException(FORBIDDEN).
 */
internal class ItemBytes private constructor(private val remote: DataSource, private val current: () -> EngineItem?) : DataSource {
    class Factory(private val remote: DataSource.Factory, private val current: () -> EngineItem?) : DataSource.Factory
}
// data/playback/engine/SavedMediaDataSource.kt — BaseDataSource(isNetwork = false) over RandomAccessFile; honours DataSpec position
// and length; refuses a path that is not a regular file or that is a symlink (Files.isSymbolicLink).

// data/downloads/DownloadVerifier.kt
internal sealed interface Verdict { data class Good(val sha256: String) : Verdict; data class Bad(val reason: String) : Verdict }
internal class DownloadVerifier(private val probe: PlayabilityProbe, private val codecs: CodecSupport, private val io: CoroutineDispatcher) {
    suspend fun verify(media: SavedMedia, expectedBytes: Long?, runtimeMs: Long?, hlsShape: List<Long>?): Verdict
    /** Re-reads the file and compares it with the stored hash (after a playback failure, or "Check again"). */
    suspend fun recheck(media: SavedMedia, sha256: String): Boolean
}
```
**What "checked playable" means** (every check must pass; `Bad.reason` is a fixed word for the log):
1. **Complete:** the file length equals `expectedBytes` (original). For HLS, every `seg-n.ts` named by `local.m3u8` exists and is non-empty.
2. **The right kind of file:** the first bytes match the container.
   - mp4, m4v and mov have `ftyp` at offset 4.
   - mkv and webm start with `1A 45 DF A3`.
   - ts has `0x47` at offsets 0, 188 and 376; for HLS this is checked on every segment.
3. **Readable:** `PlayabilityProbe` returns at least one video track, and at least one audio track when the item listed audio tracks.
4. **This phone can decode it:** every video MIME type maps to a codec that `CodecSupport` counts (Milestone 3's rules: hardware decoders, h264 always). At least one audio MIME type is decodable.
5. **The right length:** the duration is within ±5% of the runtime (±10% for HLS) when both are known.
6. **Fingerprint:** a SHA-256 of the media (the concatenated segments for HLS) is computed while re-reading it and stored, so later damage, such as a corrupted SD card, is detectable (`recheck`).

On `Bad` the queue (Task 10) deletes the media files, keeps the extras, and downloads once more from the start. A second `Bad` is `FAILED(CHECK_FAILED)` with nothing left on disk (research complaint: "partial files marked complete").

- [ ] **Tests first — `DownloadVerifierTest`** (`FakeProbe`, fake `CodecSupport`, `TemporaryFolder`):
  - Each of the six checks fails on its own fixture: a short file; HTML renamed `.mp4`; no video track; hevc with no counted hevc decoder; a 50% duration; a missing segment.
  - A good file gives a stable SHA-256 equal to Java's `MessageDigest` over the same bytes.
  - `recheck` is false after one byte is flipped.
- [ ] **Tests first — `ItemBytesTest`:**
  - With a saved current item: a file inside its folder opens and reads from a position; `../` escapes, another item's folder, a symlink to outside, and an http URI all fail at `open`.
  - With a remote current item, a file URI fails.
  - With no current item, everything fails.
- [ ] **Tests first — `SavedMediaProbeTest`** (Robolectric, real Media3 extractors on `clip.mp4` and the HLS segments): h264 + aac and a duration of about 10 s are found; a truncated file gives null within the timeout.
- [ ] **`Media3BoundaryTest` update** (deliberate):
  - Every player and the probe must be built with `DefaultMediaSourceFactory(ItemBytes.Factory(OkHttpDataSource.Factory(`. The count rule compares `DefaultMediaSourceFactory(` with that phrase.
  - `FileDataSource`, `DefaultDataSource`, `CacheDataSource` and `setWakeMode` stay banned everywhere.
  - `SavedMediaDataSource` is allowed only in `engine/`.
  - Add one scanner case proving `FileDataSource` is still caught.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.downloads.*" --tests "app.shoalwatch.data.playback.engine.*" --tests "app.shoalwatch.Media3BoundaryTest"` → pass.
- [ ] Commit: `feat(downloads): every finished download is checked complete, decodable and fingerprinted`.

---

### Task 10: The queue — one item at a time, every state written to Room

**Files:**
- Create: `data/downloads/DownloadQueue.kt`.
- Test: `data/downloads/DownloadQueueTest.kt`, `data/downloads/DownloadQueueFailureTest.kt`.

**Interfaces — Consumes:** `DownloadStore`, `PlaybackResolver.resolve` (conversion plans) and `checkOriginal`, `ServerApi.downloadRoute`, `DownloadGateway.facts` (telling a dead token from a refused media sign-in), `FileFetcher`, `HlsFetcher`, `ExtrasFetcher`, `DownloadVerifier`, `DownloadPaths`, `Volumes`, `ServerStore.cachedToken` (an owner that can still send), `TrackPreferences` + `TrackChooser` (a conversion's audio track), `MonotonicClock`.

**Produces:**
```kotlin
internal sealed interface QueueRun { data object Drained : QueueRun; data class Retry(val kind: AppError.Kind) : QueueRun; data object Stopped : QueueRun }
internal class DownloadQueue(
    private val store: DownloadStore,
    private val resolver: PlaybackResolver,
    private val downloadRoute: suspend (Session, String) -> MediaRoute,      // ServerApi::downloadRoute
    private val gateway: DownloadGateway,
    private val files: FileFetcher,
    private val hls: HlsFetcher,
    private val extras: ExtrasFetcher,
    private val verifier: DownloadVerifier,
    private val paths: DownloadPaths,
    private val volumes: Volumes,
    private val hasToken: (Session) -> Boolean,                              // serverStore.cachedToken(..) != null
    private val tracks: TrackPreferences,
    private val captions: () -> CaptionDefaults,
    private val clock: MonotonicClock,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxAttempts: Int = 8,
    private val liveConversionMaxAgeMs: Long = 30 * 60_000L,
) {
    /** Called by the worker. Requeues interrupted rows, then runs QUEUED items in order until none is left or [shouldStop] says so. */
    suspend fun drain(shouldStop: () -> Boolean, onProgress: suspend (QueueStatus) -> Unit): QueueRun
    /** Called after a playback failure of a saved copy: re-verifies it on IO and marks it FAILED(DAMAGED) if the fingerprint differs. */
    suspend fun recheck(key: DownloadKey)
}
// data/downloads/DownloadModels.kt (added)
sealed interface QueueStatus {
    data object Idle : QueueStatus
    data class Running(val position: Int, val of: Int, val progress: Float?) : QueueStatus
    data object WaitingForWifi : QueueStatus
    data object WaitingForConnection : QueueStatus
    data object PausedByTimeLimit : QueueStatus
}
```
**One item's pipeline.** Each step's result is written to Room before the next step starts. Nothing here depends on how a real server answers the Task 1 questions: every branch below is the correct-either-way path.
1. **Who fetches.** The earliest owner (`addedAt`) for whom `hasToken` is true. With none, the item becomes `FAILED(SIGN_IN_NEEDED)` and the next item is taken.
2. **Where.** The item folder on its recorded storage. A REMOVABLE volume that is not mounted → `FAILED(STORAGE_MISSING)`. Free space is checked again: less than the remaining bytes + the reserve → `FAILED(NO_SPACE)`.
3. **RUNNING — original.**
   - `checkOriginal` again. A server change since planning becomes `NOT_PLAYABLE` instead of a silent conversion: the user chose the original.
   - `extras.tracks`, `downloadRoute`, then `files.fetch` from the row's `doneBytes`, `validator` and `restarts`.
   - The `Progress` callback writes `doneBytes`, `totalBytes`, `validator` and `restarts`.
4. **RUNNING — conversion.**
   - **A live conversion** for this item, held in the queue's in-memory map, younger than `liveConversionMaxAgeMs`, and from this process: `hls.fetch(live, folder, segmentsDone, ...)` continues the SAME play session.
   - **No usable live conversion** (first start, process death, reboot, or older than 30 minutes): ask the server again with `resolver.resolve(s, PlaybackRequest(itemId, quality.quality!!, audioIndex = <TrackChooser.choose(...).audioIndex over the plan's tracks, the session's remembered choice for the series and captions()>, forceTranscode = true))`. Its plan must be TRANSCODE with an HLS source, else `FAILED(SERVER_ERROR)`.
     - Then `extras.tracks` from that plan (one audio track), and `hls.open` (which deletes old segments).
     - Set `segmentsDone = 0`, keep the new `LiveConversion` in the map, then `hls.fetch` from 0.
     - **Segments of two play sessions are never combined.**
   - `RestartConversion` drops the live conversion, sets `segmentsDone = 0` and `attempts + 1`, and the item is taken again at once (a fresh session).
   - `Done` or `Failed` removes the item's live conversion.
5. `extras.segments` and `extras.artwork`. These are best effort: their failure never fails the item.
6. **VERIFYING:** `DownloadVerifier.verify`. Good → `COMPLETE` with `sha256`, `completedAt` and `verifiedAt`. Bad → delete the media and requeue once from 0, then `FAILED(CHECK_FAILED)`.

**Sign-in failures on media** (Task 1's authentication rule): `Unauthorised` from a fetcher → ONE `gateway.facts(s, listOf(itemId))` through `ServerApi`.
- That call fails `UNAUTHORISED`: `ServerApi` has already invalidated the token locally, so the item is `FAILED(SIGN_IN_NEEDED)`.
- That call succeeds: the token works and the server refused it for media, so `FAILED(SERVER_AUTH_REFUSED)`.
- That call fails for any other reason → `Retry(that kind)`.

**Retries:**
- `Retry` from a fetcher stops the drain and returns `QueueRun.Retry(kind)`; the worker backs off and WorkManager runs it again. The live conversion stays in memory, so a network blip resumes the same session. A `Retry(OFFLINE)` caused by a stale route first asks for a new route once (same plan, same session: `resolver.reroute`).
- `attempts + 1` per item per `Retry`, per `RestartConversion`, and per range-ignored restart of an original. At `maxAttempts` → `FAILED(SERVER_ERROR)` and the queue moves on. Attempts reset to 0 when the item completes or on Retry from the UI.
- `Failed(...)` → `FAILED(that)` and the queue moves on.

**Between items:** `shouldStop()` is checked between items and between segments (the worker's `isStopped`). `PAUSED` rows are skipped. A row deleted while running (cancelled, account or server removed) is detected at the next progress save (`update` finds no row): the call is cancelled, the files are deleted and the live conversion is dropped.

- [ ] **Tests first — `DownloadQueueTest`** (fakes for the fetchers, verifier, gateway and resolver; in-memory Room):
  - Three queued items complete in order.
  - A paused item is skipped and the next runs.
  - Process death mid-original (a new queue over the same database) requeues RUNNING and resumes from the saved byte count.
  - **Process death mid-conversion:** a new queue asks the resolver again and calls `hls.open` (segments from 0), never `fetch` with the old count.
  - **A `Retry(NETWORK)` mid-conversion in the same process:** the next drain resumes the same live conversion at the saved segment, with no new PlaybackInfo.
  - A live conversion older than 30 minutes (virtual monotonic time) is replaced by a fresh one.
  - `RestartConversion` → a fresh session from 0, `attempts + 1`.
  - A share-only owner add never reaches the queue.
  - The conversion asks the resolver with `forceTranscode = true` and the remembered audio language's track.
  - Progress statuses are emitted in order.
- [ ] **Tests first — `DownloadQueueFailureTest`:**
  - No usable owner → SIGN_IN_NEEDED.
  - **`Unauthorised` with `facts` also 401** → SIGN_IN_NEEDED; **`Unauthorised` with `facts` 200** → SERVER_AUTH_REFUSED; with `facts` OFFLINE → Retry.
  - An SD card missing → STORAGE_MISSING.
  - Not enough space → NO_SPACE before any request.
  - A first bad verdict re-downloads, and a second → CHECK_FAILED with the folder empty.
  - 8 attempts (any mix of retries, ignored-range restarts and conversion restarts) → SERVER_ERROR.
  - A row deleted mid-fetch cancels and cleans up.
  - Artwork failing still gives COMPLETE.
  - An original that the server now says cannot play → NOT_PLAYABLE.
  - Nothing here logs an id, path or title (a capturing `AppLog` sink).
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.downloads.*"` → pass.
- [ ] Commit: `feat(downloads): a resumable queue whose every step is saved before the next begins`.

---

### Task 11: Running in the background — WorkManager, the notification, and the manifest

**Files:**
- Create: `data/downloads/DownloadWorker.kt`, `DownloadScheduler.kt`, `DownloadNotifications.kt`, `ShoalwatchWorkerFactory.kt`; fixtures `app/src/test/manifest-fixtures/bad-unprotected-job-service.xml`, `bad-diagnostics-receiver.xml`, `bad-missing-data-sync.xml`.
- Modify: `gradle/libs.versions.toml` (`work = "2.12.0"`, `androidx-work-runtime`, `androidx-work-testing`); `app/build.gradle.kts` (dependencies; `ManifestRules` and `releaseExpectedPermissions`; fixture expectations); `gradle/verification-metadata.xml`; `AndroidManifest.xml`; `ShoalwatchApp.kt` (`Configuration.Provider`); `AppGraph.kt`; `res/values/strings.xml`; `ManifestSecurityTest.kt`; `app/src/test/manifest-fixtures/ok.xml`.
- Test: `data/downloads/DownloadWorkerTest.kt` (Robolectric + `work-testing`), `data/downloads/DownloadSchedulerTest.kt`, `data/downloads/DownloadNotificationsTest.kt`, `ManifestSecurityTest.kt`.

**Interfaces — Consumes:** `DownloadQueue`, `DownloadPrefs`, `ProcessLifecycleOwner` (via `AppGraph.foreground`).

**Produces:**
```kotlin
internal class DownloadWorker(context: Context, params: WorkerParameters, private val queue: DownloadQueue,
                              private val notifications: DownloadNotifications) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result      // setForeground(ForegroundInfo(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)); drain
    override suspend fun getForegroundInfo(): ForegroundInfo
}
class DownloadScheduler internal constructor(private val workManager: WorkManager, private val prefs: DownloadPrefs) {
    /** enqueueUniqueWork("shoalwatch-downloads", KEEP, ...) when anything is queued; REPLACE when the network setting changed. */
    fun kick(replace: Boolean = false)
    val status: StateFlow<QueueStatus>
}
internal class ShoalwatchWorkerFactory(private val graph: () -> AppGraph) : WorkerFactory()   // builds DownloadWorker only; anything else: null
internal class DownloadNotifications(private val context: Context) {
    fun ensureChannel()
    fun running(position: Int, of: Int, progress: Float?): Notification
    fun waiting(status: QueueStatus): Notification            // WaitingForWifi, WaitingForConnection, PausedByTimeLimit
    fun finished(ready: Int)                                   // posts "Downloads finished" when notifications are enabled
    companion object { const val ID = 4201; const val CHANNEL = "downloads" }
}
```
**Worker rules:**
- **Constraints:** `NetworkType.UNMETERED` while mobile data is off (the default; SPEC §3 row 10), `CONNECTED` when the user allowed it; `setRequiresStorageNotLow(true)`. Backoff is EXPONENTIAL from 30 s. No input data at all (nothing to leak; the queue is in Room).
- **Result:** `Drained` → success. `Retry` → `Result.retry()`. Stopped (constraints lost) → `Result.retry()`; WorkManager resumes it when Wi-Fi is back.
- **Android 15's limit:** `dataSync` gets 6 hours per 24. When `getStopReason() == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT`, the status becomes `PausedByTimeLimit` and the worker returns retry. The scheduler `kick`s again when the app next comes to the foreground (the platform resets the limit then).
- **Starting again:** `ShoalwatchApp.onCreate` calls `graph.downloadHousekeeping.onStart()` (Task 12), which requeues interrupted rows and kicks if anything is queued. So app updates and reboots resume even if WorkManager's own record were lost. WorkManager reschedules after reboot through its own `RescheduleReceiver`; no receiver of ours.
- **WorkManager set-up:**
  - Initialised on demand via `Configuration.Provider` (`setWorkerFactory(ShoalwatchWorkerFactory)`, `setMinimumLoggingLevel(android.util.Log.ERROR)`).
  - The manifest removes `androidx.work.WorkManagerInitializer` from `InitializationProvider` (`tools:node="remove"`), as the WorkManager custom-initialisation guide requires.

**Notification** (`DownloadNotifications`):
- Channel `downloads`, "Downloads", `IMPORTANCE_LOW`, no sound.
- `VISIBILITY_PRIVATE` with a public version that says only "Shoalwatch is downloading".
- Text: "Downloading 3 of 12", progress bar; "Waiting for Wi-Fi"; "Downloads paused. Open Shoalwatch to carry on."; and, when done, "Downloads finished" with "%1$d ready to watch offline".
- **Never a title or a profile name.** Tap: an explicit, `FLAG_IMMUTABLE` `PendingIntent` to `MainActivity` with extra `open_tab=downloads`; `MainActivity` accepts only that exact value.
- Ongoing while running; removed when idle.
- If `areNotificationsEnabled()` is false, downloads still run (the foreground notice shows in Task Manager). Research: jellyfin-android #2252 "Downloads silently fail if notification permission denied".

**Manifest and permissions** (each one justified under Decisions):
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<!-- WAKE_LOCK: the tools:node="remove" entry from Milestone 3 is deleted: WorkManager holds a wake lock while foreground work runs.
     The player still sets no wake mode (Media3BoundaryTest bans setWakeMode). -->
<!-- RECEIVE_BOOT_COMPLETED and FOREGROUND_SERVICE arrive from work-runtime's manifest. -->
<service android:name="androidx.work.impl.foreground.SystemForegroundService"
         android:foregroundServiceType="dataSync" tools:node="merge" />
<receiver android:name="androidx.work.impl.diagnostics.DiagnosticsReceiver" tools:node="remove" />
<!-- in the InitializationProvider: <meta-data android:name="androidx.work.WorkManagerInitializer" tools:node="remove" /> -->
```
**`ManifestRules` changes** (in `app/build.gradle.kts`, deliberate and fixture-proved):
- `releaseExpectedPermissions` = `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `app.shoalwatch.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
- **Exported components** may be the launcher activity and, only if it carries `android:permission="android.permission.BIND_JOB_SERVICE"`, `androidx.work.impl.background.systemjob.SystemJobService`. Anything else exported fails, including the diagnostics receiver (`DUMP`-protected, but not needed).
- **New rule:** every `<service>` with a `foregroundServiceType` must have exactly `dataSync`, and `SystemForegroundService` must be present with `dataSync`. Without that type Android 14+ throws when the worker goes to the foreground.
- **New fixtures:**
  - `bad-unprotected-job-service.xml` → "may be exported";
  - `bad-diagnostics-receiver.xml` → "may be exported";
  - `bad-missing-data-sync.xml` → "foregroundServiceType must be dataSync".
  - `ok.xml` gains the WorkManager entries.

- [ ] **Tests first — `DownloadWorkerTest`** (`TestListenableWorkerBuilder` with `ShoalwatchWorkerFactory` over a fake queue):
  - `Drained` gives success.
  - `Retry` gives retry.
  - The foreground info uses the data-sync type and notification id 4201.
  - A stop with `STOP_REASON_FOREGROUND_SERVICE_TIMEOUT` sets `PausedByTimeLimit`.
  - The worker has no input data.
- [ ] **Tests first — `DownloadSchedulerTest`** (`WorkManagerTestInitHelper`, `SynchronousExecutor`):
  - Mobile data off gives an `UNMETERED` constraint; on gives `CONNECTED`.
  - A second `kick` keeps the running work; changing the setting replaces it.
  - Coming to the foreground with queued rows kicks.
- [ ] **Tests first — `DownloadNotificationsTest`** (Robolectric): no title, item id or user name appears in any text, extra or public version; the `PendingIntent` is immutable and explicit; the texts are exactly as above.
- [ ] **Tests first — `ManifestSecurityTest`:** the source manifest removes the diagnostics receiver and the WorkManager initialiser, merges `dataSync` onto `SystemForegroundService`, and no longer removes `WAKE_LOCK`.
- [ ] Implement. Add the dependency. Regenerate the verification metadata and review it. Record the version check. Check the AAR's `aar-metadata.properties` (`minCompileSdk=35`) and that `CoroutineWorker` resolves from `work-runtime` alone; if not, add `work-runtime-ktx` 2.12.0 too and say so in "Version checks".
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.downloads.*" --tests "app.shoalwatch.ManifestSecurityTest" verifyManifestRuleFixtures assembleRelease` → pass (`verifyReleaseManifest` runs with the new rules).
- [ ] Commit: `feat(downloads): downloads carry on in the background and after a restart, with a title-free notification`.

---

### Task 12: The `Downloads` seam, the removal rules, and sharing between profiles

**Files:**
- Create: `data/downloads/Downloads.kt`, `data/downloads/DownloadRepository.kt`, `data/downloads/DownloadHousekeeping.kt`.
- Modify: `AppGraph.kt` (`downloads`, `downloadHousekeeping`; `ServerStore`'s `onServerRemoved` and `onAccountGone` call housekeeping); `ShoalwatchApp.kt` (`onStart`); `app/src/test/java/app/shoalwatch/ui/browse/BrowseFakes.kt` (`FakeDownloads`).
- Test: `data/downloads/DownloadRepositoryTest.kt`, `data/downloads/RemovalRulesTest.kt` (through the real `ServerStore`), `data/downloads/SharingTest.kt`.

**Interfaces — Consumes:** everything above, `ServerStore`, `Library.refresh` (re-confirming visibility).

**Produces:**
```kotlin
/** What screens use. Every read is for one Session and shows only what that profile may see (see "Session and sharing rule"). Flows never throw. */
interface Downloads {
    fun library(s: Session): Flow<OfflineLibrary>
    fun series(s: Session, seriesId: String): Flow<OfflineSeries>
    fun status(s: Session, itemId: String): Flow<DownloadInfo?>
    fun seriesStatus(s: Session, seriesId: String): Flow<Map<String, DownloadInfo>>
    val queue: StateFlow<QueueStatus>
    /** Online. Throws AppError (OFFLINE and the rest, unchanged). */
    suspend fun prepare(s: Session, target: DownloadTarget, quality: DownloadQuality): DownloadPlan
    /** Adds the plan's items for [s] (the plan must be [s]'s; anything no longer visible is skipped) and kicks the worker. */
    suspend fun start(s: Session, plan: DownloadPlan)
    suspend fun pause(s: Session, itemId: String); suspend fun resume(s: Session, itemId: String)
    suspend fun pauseAll(s: Session); suspend fun resumeAll(s: Session)
    suspend fun retry(s: Session, itemId: String)
    suspend fun remove(s: Session, itemId: String): RemoveOutcome
    val settings: StateFlow<DownloadSettings>
    fun storageChoices(): List<StorageChoice>
    fun setMobileData(allowed: Boolean); fun setStorage(kind: StorageKind, volumeId: String?); fun setDefaultQuality(q: DownloadQuality?)
    fun sharing(serverId: String): Flow<Boolean>
    suspend fun setSharing(s: Session, on: Boolean)
}
class DownloadRepository internal constructor(
    private val store: DownloadStore,
    private val offline: OfflineLibraryDao,
    private val planner: DownloadPlanner,
    private val scheduler: DownloadScheduler,
    private val prefs: DownloadPrefs,
    private val volumes: Volumes,
    private val paths: DownloadPaths,
    private val writeGated: suspend (suspend () -> Unit) -> Unit,             // LibraryRepository.writeGated (pins under the library lock)
    private val scope: CoroutineScope,                                        // the app scope: an action finishes even if the screen goes
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Downloads
internal class DownloadHousekeeping(
    private val store: DownloadStore,
    private val paths: DownloadPaths,
    private val queue: DownloadQueue,
    private val scheduler: DownloadScheduler,
    private val library: Library,                                             // reconfirm: Library.refresh(s, RefreshTarget.Item(id))
    private val savedServers: suspend () -> Set<String>,                      // ServerStore's saved server ids, for the orphan sweep
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun onStart()                                   // requeue interrupted, orphan sweep, unpinned-artwork clean-up, kick (once per process, off the main thread)
    fun onServerRemoved(serverId: String)           // cancel its running item, delete <root>/<serverId> on every mounted root
    fun onAccountGone(serverId: String, userId: String)   // the ownership rule, then delete what it returns
    /** When a profile opens Downloads online: re-asks the server about up to 50 shared items (and their series) this profile has not had confirmed for 7 days. */
    suspend fun reconfirm(s: Session)
}
```
**The removal rules (explicit, tested):**
- **Server removed:**
  - the rows cascade (Task 2);
  - housekeeping deletes `<root>/<serverId>` on every mounted root and cancels the running item if it was that server's;
  - pinned artwork rows cascade and their folder goes with the server folder;
  - a card that is not mounted is cleaned by the next `onStart` orphan sweep (the server is no longer saved).
- **Account removed (sign-out removes it here; a 401 does not, so downloads survive an expired sign-in):**
  - its `download_owners` and `offline_plays` cascade;
  - then, for every download of that server left with no owner: if sharing is on for the server, every remaining account on the server that has a `visible_items` record for the item becomes an owner (it was already in their offline library, and their own server permission was already proved);
  - if none qualifies, or sharing is off, the download is deleted, rows and files;
  - **Nothing of the removed account's is left on disk that another profile could not already see.**
- **Remove a download (a profile's own):** removes that profile's ownership. The files go when no owner is left. With sharing on and another owner left: "Removed from your downloads. It stays on this phone for another profile."
- **Turning sharing off** hides shared downloads from non-owners again. Nothing is deleted.

**Sharing:**
- The setting is per server. Any signed-in profile on that server can change it; screens say what it does.
- It is stored in `download_settings`, so removing the server removes it.
- Visibility is "checked when online and remembered" (SPEC §3.3): through the existing `visible_items` record, written whenever the server returns the item to that profile, and revoked by a 404 or 403 on a single-item refresh. `reconfirm` refreshes, for the profile on screen, the shared items it has not been shown within 7 days, so a parental-rating change on the server reaches a shared tablet the next time it is online.

- [ ] **Tests first — `RemovalRulesTest`** (real `ServerStore`, in-memory Room, `TemporaryFolder` roots):
  - Remove a server: its rows and folders go on every mounted root, and another server's stay.
  - Remove a server whose card is unmounted, then mount it and run `onStart`: the folder goes.
  - Remove the only owner, sharing off: the download and its files go, and pins are cleared.
  - Two owners, remove one: kept for the other.
  - Remove the owner, sharing on, B has a visibility record: B becomes the owner and nothing is deleted.
  - Remove the owner, sharing on, B (a child) has NO record: deleted.
  - An expired token (`invalidateToken`) changes nothing.
  - Removing an account while its item is running cancels it and cleans up.
- [ ] **Tests first — `SharingTest`:**
  - The `OfflineLibraryVisibilityTest` cases again, through the `Downloads` seam.
  - `setSharing` emits.
  - `reconfirm` refreshes at most 50, only for shared items not confirmed in 7 days, and a 403 hides the item from that profile at once while the owner still sees it.
- [ ] **Tests first — `DownloadRepositoryTest`:**
  - `start` with a plan made for another session is refused (`IllegalArgumentException`) and adds nothing.
  - Share-only items add owners without kicking the worker.
  - `remove` returns each outcome.
  - pause, resume, retry and their all-items forms change only the caller's own downloads.
  - A malformed id gives an empty or null flow value and never throws.
  - A database failure in a flow is `AppError(UNKNOWN)` to actions and an empty value to flows (as `Library`).
- [ ] Implement, and wire `AppGraph` and the store hooks.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.*"` → pass.
- [ ] Commit: `feat(downloads): removing a profile or server removes its downloads by one tested rule, and sharing obeys each profile's permissions`.

---

### Task 13: Offline playback through the existing player

**Files:**
- Create: `data/playback/SavedPlans.kt`, `data/playback/SavedFirstPlaybackGateway.kt`, `data/playback/LocalPlaybackReports.kt`, `data/sync/OfflinePlays.kt`, `data/db/downloads/OfflinePlayDao.kt`.
- Modify:
  - `data/playback/PlaybackModels.kt` (`PlayMethod.LOCAL`);
  - `PlaybackResolver.kt` (saved first; `Resolved` holds a route or a saved copy);
  - `PlaybackTracks.kt` (LOCAL as DIRECT_PLAY, no re-ask);
  - `PlaybackRules.kt` (a LOCAL decoder failure falls back once to streaming);
  - `PlaybackController.kt` (one line: `engine.load(resolved.engineItem(), atMs, playWhenReady = true)`);
  - `engine/PlayerEngine.kt` (`EngineItem` remote or saved);
  - `engine/ExoPlayerEngine.kt` (`ItemBytes.Factory`);
  - `engine/MediaItems.kt` (file URI);
  - `api/PlaybackGatewayImpl.kt` (LOCAL maps to `DirectPlay`, never sent);
  - `data/library/PlaystateRepository.kt` + `PlaystateLocal.kt` (`recordLocalStop`);
  - `data/library/StartupSweep.kt` and `ItemDao.clearPendingFor` (spare rows with an offline play);
  - `AppGraph.kt` (`RoutedPlaybackReports`, `SavedFirstPlaybackGateway`, the resolver's saved lookup);
  - `ui/present/PlayerText.kt` and `ui/player/PlayerSheets.kt` (Milestone 3 Task 8's files).
- Test: `data/playback/SavedPlaybackTest.kt` (controller over `FakePlayerEngine`, as `PlaybackControllerTestBase`), `data/playback/SavedFirstPlaybackGatewayTest.kt`, `data/playback/LocalPlaybackReportsTest.kt`, `data/sync/OfflinePlaysTest.kt`, `data/library/PlaystateSweepTest.kt` (new cases), `data/playback/engine/MediaItemsTest.kt` (new cases), `ui/present/PlayerTextTest.kt` (new cases).

**Interfaces — Consumes:** `OfflineLibraryDao.getPlayable`, `DownloadPaths`, `DownloadTrackEntity`, `DownloadSegmentEntity`, `DownloadQueue.recheck`, `Volumes`.

**Produces:**
```kotlin
// data/playback/PlaybackModels.kt
enum class PlayMethod { DIRECT_PLAY, DIRECT_STREAM, TRANSCODE, LOCAL }

// data/playback/PlaybackResolver.kt
class PlaybackResolver internal constructor(gateway: PlaybackGateway, capabilities: CodecSupport, routes: suspend (Session) -> MediaRoute,
    io: CoroutineDispatcher = Dispatchers.Default,
    /** A verified copy this session may play, or null. */ private val saved: suspend (Session, String) -> SavedPlan? = { _, _ -> null },
    /** After a saved copy failed to decode: re-check it in the background. */ private val savedFailed: (Session, String) -> Unit = { _, _ -> })
// resolve(): when request.forceTranscode is false and burnInSubtitleIndex is null, a saved copy wins (quality is ignored).
// fallBack(plan) for a LOCAL plan: savedFailed(...) then resolve(request) skipping the saved copy (streams if online; else OFFLINE).
class Resolved(val plan: PlaybackPlan, val route: MediaRoute?, internal val saved: SavedMedia? = null) {
    init { require((route == null) != (saved == null)) { "Exactly one source." } }
    internal fun engineItem(): EngineItem
    internal fun withPlan(p: PlaybackPlan): Resolved
}
internal class SavedPlan(val media: SavedMedia, val plan: PlaybackPlan)

// data/playback/SavedPlans.kt (pure)
internal object SavedPlans {
    /** LOCAL plan: tracks from download_tracks (subtitles only where a file is saved or the track is PICTURE in an original), segments known, no play session. */
    fun plan(itemId: String, media: SavedMedia, tracks: List<DownloadTrackEntity>, runtimeMs: Long?, hasSegments: Boolean): PlaybackPlan
}

// data/playback/engine/PlayerEngine.kt
class EngineItem internal constructor(internal val route: MediaRoute?, internal val source: StreamSource?, internal val saved: SavedMedia?, val mediaId: String)

// data/playback/LocalPlaybackReports.kt
/** Reports for a LOCAL plan: nothing is sent; the position is kept for Milestone 5 (offline_plays) and shown at once (user_data). */
/** The gateway handed to the controller and SubtitleLoader: saved subtitles and segments for a saved copy this session may play, else [server]. */
internal class SavedFirstPlaybackGateway(
    private val server: PlaybackGateway,
    private val saved: suspend (Session, String) -> SavedMedia?,             // the same lookup as the resolver's
    private val downloads: DownloadDao,                                      // download_tracks (fileSaved), download_segments
    private val paths: DownloadPaths,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PlaybackGateway by server
class LocalPlaybackReports internal constructor(private val plays: OfflinePlays, private val playstate: LocalPlaystate, private val now: () -> Long) : PlaybackReports
/** Sends to [server] unless the report's method is LOCAL; ping always to [server] (LOCAL never pings). */
class RoutedPlaybackReports(private val server: PlaybackReports, private val local: PlaybackReports) : PlaybackReports

// data/library/PlaystateRepository.kt (implements)
interface LocalPlaystate {
    /** Same per-user queue and write gate as the other actions; sets the local value with the pending marker; no server call, no rollback. */
    suspend fun recordLocalStop(s: Session, itemId: String, positionMs: Long, finished: Boolean)
}

// data/sync/OfflinePlays.kt
class OfflinePlays internal constructor(private val dao: OfflinePlayDao, private val now: () -> Long) {
    suspend fun position(s: Session, itemId: String, positionMs: Long)              // every progress report (10 s) and pause
    suspend fun stopped(s: Session, itemId: String, positionMs: Long, finished: Boolean)
    fun pending(s: Session): Flow<Int>                                               // for Milestone 5's "waiting to sync"
}
```
**Rules:**
- **Which copy plays.** The saved copy is used only if all of these hold:
  - it is `COMPLETE`;
  - it is visible to this session by the "Session and sharing rule";
  - its storage is mounted, and the media file length (or the segment count) is as recorded (a cheap check before every play);
  - the request asks for no forced conversion or burn-in.
  
  Otherwise the item streams as before. So a phone online at home plays the download (faster, no server load).
- **Subtitles and segments** come from `SavedFirstPlaybackGateway`, the `PlaybackGateway` handed to the controller and `SubtitleLoader`:
  - for a saved copy, `subtitleFile` returns the saved file's bytes, re-checked at 8 MiB and re-parsed by the same untrusted-input parser, and `segments` returns the saved rows;
  - anything else is delegated;
  - a subtitle with no saved file is not offered in a LOCAL plan.
- **Tracks:** LOCAL behaves as DIRECT_PLAY for in-stream audio and picture subtitles, and as FILE for text. It never asks the server again: a language disagreement keeps the player's track. Burn-in, quality and "let the server draw" are not offered for LOCAL; the sheets say "This is the copy saved on this phone, so its quality can't be changed."
- **Failure:** a DECODER or FORMAT failure in LOCAL falls back once. The resolver re-checks the copy in the background (`DownloadQueue.recheck`, which marks it DAMAGED if its fingerprint changed), then streams if the server is reachable, else the player shows its OFFLINE message. A second failure is PLAYBACK_FAILED.
- **Reports:**
  - `ProgressReporter` is unchanged; `AppGraph` passes `RoutedPlaybackReports(serverReports, localReports)` to the controller.
  - LOCAL `started` and `progress` write `offline_plays.positionMs`, so process death keeps it.
  - `stopped` writes `offline_plays` (`played` once finished, never cleared; `playedAt` set once) and `recordLocalStop`, so Continue Watching and the watched tick update on this phone at once, for this profile only.
  - **Nothing is sent to the server for a LOCAL play in this milestone.** Milestone 5's outbox sends `offline_plays` with the original dates.
- **Pending markers survive:** the start-up sweep's snapshot and `clearPendingFor` (the queue's idle clean-up) skip any `user_data` row that has an `offline_plays` row. So a server refresh cannot undo an offline watch before Milestone 5 sends it (SPEC §3.2, "watched is never undone by a sync").
- **Next and previous:** `EpisodeNavigator` is unchanged. Offline, Up next for an episode that is not downloaded fails with the OFFLINE message as any stream does. Up next to the next downloaded episode is offered by the Downloads screen's play order (Task 14), and Milestone 5 adds auto-download-next.
- **Player text** (`PlayerText`): LOCAL "Playing the copy saved on this phone."; with a conversion, also "Saved in High quality (1080p)." / "Medium (720p)" / "Low (480p)".

- [ ] **Tests first — `SavedPlaybackTest`:**
  - With a verified copy and the server OFFLINE, `open` loads a saved `EngineItem` (no route asked, no PlaybackInfo sent).
  - The saved subtitle loads.
  - Skip intro appears from the saved segments.
  - A stop writes `offline_plays` and the local position; nothing reaches `FakePlaybackGateway`'s reports.
  - A finished stop sets `played` and `playedAt`.
  - Switching account mid-play ends it exactly as before (`ActivePlayback`).
  - A profile without visibility gets a stream attempt (OFFLINE), never the file.
  - A decoder failure re-checks once and then streams.
  - A quality change keeps the saved copy.
  - The controller file is still at most 400 lines.
- [ ] **Tests first — `SavedFirstPlaybackGatewayTest`:** saved bytes for a saved item with the matching media source; delegation otherwise; an oversized saved file is refused.
- [ ] **Tests first — `LocalPlaybackReportsTest` / `OfflinePlaysTest`:** positions per profile; watched never undone by a later unfinished stop; cascade with the account; `pending` counts.
- [ ] **Tests first — `PlaystateSweepTest` (new cases):** a pending row with an offline play survives the start-up sweep and the idle clean-up; one without does not (existing behaviour).
- [ ] **Tests first — `MediaItemsTest`:** a saved item gives a `file://` URI under its folder and the HLS MIME type for a conversion; a remote item is unchanged.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.playback.*" --tests "app.shoalwatch.data.library.*" --tests "app.shoalwatch.data.sync.*" --tests "app.shoalwatch.ui.present.*" --tests "app.shoalwatch.Media3BoundaryTest" --tests "app.shoalwatch.SourceShapeTest"` → pass (every Milestone 3 test unchanged).
- [ ] Commit: `feat(playback): downloads play through the same player offline, and what is watched is kept for syncing`.

---

### Task 14: The Downloads tab — the offline library, organised

**Files:**
- Create: `ui/downloads/DownloadsViewModel.kt`, `DownloadsUiState.kt`, `DownloadsScreen.kt`, `DownloadRows.kt`, `OfflineSeriesViewModel.kt`, `OfflineSeriesScreen.kt`, `DownloadsPreviews.kt`; `ui/present/DownloadText.kt`.
- Delete: `ui/shell/DownloadsScreen.kt` (the placeholder and its strings `downloads_body`).
- Modify: `ui/nav/AppNav.kt` (`Routes.DOWNLOADED_SERIES = "downloads/series/{seriesId}"`), `ui/nav/BrowseRoutes.kt`, `ui/MainActivity.kt` (the `open_tab=downloads` extra), `res/values/strings.xml`.
- Test: `ui/downloads/DownloadsViewModelTest.kt`, `ui/downloads/DownloadsRenderTest.kt`, `ui/downloads/OfflineSeriesRenderTest.kt`, `ui/present/DownloadTextTest.kt`, `ui/nav/BrowseNavigationTest.kt` (new destination).

**Interfaces — Consumes:** `Downloads`, `ServerCatalog.activeSession()`, `ServerConnections` (online or not), `Library` (the detail screen, unchanged, opens from here). Rows offer no mark-watched action: tidy-up actions stay online-only until Milestone 5's outbox.

**Produces:**
```kotlin
data class DownloadsUiState(
    val session: Session?, val library: OfflineLibrary, val queue: QueueStatus, val online: Boolean,
    val loading: Boolean, val localFailure: Boolean, val confirmRemove: String?,
)
class DownloadsViewModel(catalog: ServerCatalog, connections: ServerConnections, downloads: Downloads) : ViewModel() {
    val state: StateFlow<DownloadsUiState>
    fun pause(itemId: String); fun resume(itemId: String); fun retry(itemId: String)
    fun askRemove(itemId: String); fun confirmRemove(); fun cancelRemove(); fun pauseAll(); fun resumeAll()
}
class OfflineSeriesViewModel(seriesId: String, catalog: ServerCatalog, downloads: Downloads) : ViewModel() {
    val state: StateFlow<OfflineSeriesUiState>          // (session, OfflineSeries, loading); same session rule as every screen
}
```
**Layout** (the same components as browsing: `PosterCard`, `EpisodeRow`, `SectionHeader`, `Artwork` with pinned pictures):
1. Top bar: "Downloads", with a "Download settings" action (48dp, labelled).
2. **Downloading** (owners only), in queue order:
   - each row shows its artwork, title line, and a status line from `DownloadText` with a linear progress bar written in words for TalkBack ("Downloading, 45 percent");
   - buttons are 48dp, each with its own label: Pause / Resume / Retry / "Cancel download";
   - a "Pause all" / "Resume all" text button;
   - the queue line: "Waiting for Wi-Fi", "Waiting for a connection" or "Paused by Android after six hours. It carries on now that Shoalwatch is open."
3. **Films:** poster grid, title A to Z.
4. **TV:** one card per series: "%1$d episodes · %2$s" (count and size).
5. Empty: "Nothing downloaded yet. Open a film or series and press Download to watch it without a connection." (scrolls at 2.0).

**Opening things:**
- A series opens `OfflineSeriesScreen`: series header (artwork, overview through `plainOverview`), then **each season as a heading** ("Season 1", "Specials" last), with its downloaded episodes in order as `EpisodeRow`s. Each row shows "S1 E3 · Title", runtime, a watched tick or "23 min left", and Play (48dp, "Play Season 1, episode 3, Title").
- Tapping a row opens the existing detail screen (`item/{itemId}`), which reads Room and so works offline.
- A film opens its detail screen; its Play goes to the player, which picks the saved copy.

**States and labels:**
- The `available == false` line: "Insert the SD card this was saved to, to watch it."
- A non-owner's shared item: a small line "Downloaded by another profile". No delete for them.
- Delete, from a row's "more" menu, says "Delete download"; the confirm reads "Delete this download? This frees %1$s on this phone." with "Delete" and "Cancel".
- After `KeptForOthers`, a snackbar: "Removed from your downloads. It stays on this phone for another profile."

**Failure lines** (`DownloadText`, exact):

| Failure | Line |
|---|---|
| NOT_ALLOWED | "Your server doesn't allow this account to download this." |
| NOT_PLAYABLE | "This phone can't play the original file. Download it in High, Medium or Low quality instead." |
| NO_SPACE | "There isn't enough free space. Delete some downloads or choose a smaller quality." |
| STORAGE_MISSING | "The SD card this was saving to isn't in the phone." |
| CHANGED_ON_SERVER | "The file kept changing on your server. Try again later." |
| SERVER_ERROR | "Your server had a problem sending this. Press Retry to try again." |
| SERVER_AUTH_REFUSED | "Your server wouldn't accept this app's sign-in for downloads. Check its setting for older apps, then press Retry." |
| SIGN_IN_NEEDED | "Sign in again to carry on with this download." (with a "Sign in" button to `sign_in/{serverId}`) |
| CHECK_FAILED | "This download didn't pass its check, so it was removed. Press Retry to download it again." |
| DAMAGED | "This download is damaged. Press Retry to download it again." |
| GONE_FROM_SERVER | "This is no longer on your server." |
| UNKNOWN | "Something went wrong with this download. Press Retry to try again." |

**Status lines:** QUEUED "Waiting"; RUNNING "Downloading · 45%" (spoken "Downloading, 45 percent"); VERIFYING "Checking it plays"; PAUSED "Paused"; COMPLETE "Ready to watch"; a conversion adds " · High (1080p)". Sizes use decimal units with plain digits ("1.8 GB", "640 MB").

- [ ] **Tests first — `DownloadsViewModelTest`** (`FakeDownloads`):
  - The session rule: switching account restarts the flows; the first emission of the new session is empty, and the old profile's titles never appear.
  - Remove confirm flow and its outcomes (snackbar text).
  - A flow failure gives `localFailure` with Try again.
  - Offline still shows everything saved (no network call is made by this screen).
- [ ] **Tests first — `DownloadsRenderTest` / `OfflineSeriesRenderTest`** (360x640 and 840x1200, font scale 1.0 and 2.0):
  - Every section heading is a heading, and every row is one focusable element with its whole meaning.
  - Every button has its own label and is at least 48dp.
  - Progress exposes `ProgressBarRangeInfo` and the words.
  - Seasons appear in order with Specials last, and episodes in order.
  - Every failure line shows with its button.
  - The empty state scrolls.
  - The non-owner line shows with no delete.
- [ ] **Tests first — `DownloadTextTest`:** every state and failure maps to the exact line; sizes; conversions; digits never change with the locale.
- [ ] **`BrowseNavigationTest`:** `downloads/series/{id}` resolves; a malformed id gives the "This is no longer available" state; the tab switch keeps its state as the other tabs do.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.ui.downloads.*" --tests "app.shoalwatch.ui.present.*" --tests "app.shoalwatch.ui.nav.*"` → pass.
- [ ] Commit: `feat(downloads): an offline library organised by film, series, season and episode`.

---

### Task 15: Download buttons, the quality and size sheet, and download settings

**Files:**
- Create: `ui/downloads/DownloadActions.kt`, `DownloadSheet.kt`, `DownloadSettingsViewModel.kt`, `DownloadSettingsScreen.kt`, `NotificationAsk.kt`.
- Modify: `ui/series/SeriesScreen.kt` + `SeriesViewModel.kt` (Download season / series / unwatched, and a per-episode state and download control); `ui/detail/DetailScreen.kt` + `DetailViewModel.kt` (Download, and the state); `ui/servers/ServersScreen.kt` (sign-out and remove dialogs); `ui/nav/AppNav.kt` (`Routes.DOWNLOAD_SETTINGS = "download_settings"`); `res/values/strings.xml`.
- Test: `ui/downloads/DownloadActionsTest.kt`, `ui/downloads/DownloadSheetRenderTest.kt`, `ui/downloads/DownloadSettingsRenderTest.kt`, `ui/series/SeriesRenderTest.kt` + `ui/detail/DetailRenderTest.kt` (new cases), `ui/ManagementRenderTest.kt` (dialog text).

**Interfaces — Consumes:** `Downloads`, `ServerCatalog`, `ServerConnections`.

**Produces:**
```kotlin
/** Like ItemActions: the one place screens call Downloads.prepare/start; per-session work; messages travel with their session. */
class DownloadActions(private val scope: CoroutineScope, private val downloads: Downloads) {
    val sheet: StateFlow<DownloadSheetState?>
    fun ask(s: Session, target: DownloadTarget)           // opens the sheet (quality step unless a default quality is set)
    fun choose(quality: DownloadQuality)                  // prepares the plan (spinner), then the confirm step
    fun confirm(); fun dismiss()
    val messages: Flow<SessionMessage>
}
sealed interface DownloadSheetState {
    data class Quality(val session: Session, val target: DownloadTarget) : DownloadSheetState
    data class Preparing(val session: Session) : DownloadSheetState
    data class Confirm(val plan: DownloadPlan) : DownloadSheetState
    data class Failed(val session: Session, val message: String) : DownloadSheetState
}
```
**The sheet** (a `ModalBottomSheet` that scrolls at 2.0; choices are radio buttons that say which is selected):
- **Quality step:**
  - "Original file" with "Best picture and sound, largest files. Keeps every audio and subtitle track."
  - "High (1080p)" — "About 3.6 GB an hour"
  - "Medium (720p)" — "About 1.8 GB an hour"
  - "Low (480p)" — "About 0.7 GB an hour"
  - Under the conversions: "Converted downloads keep one audio track and text subtitles. Picture subtitles (such as PGS) aren't kept."
  - A checkbox "Use this every time", which sets the default quality.
- **Confirm step:**
  - Title: "Download 1 film?" / "Download 12 episodes?".
  - Body: "About 9.8 GB. 41 GB free on this phone." (or "on the SD card").
  - Then only the lines that apply:
    - "3 can't be downloaded: your server doesn't allow it.";
    - "2 can't be downloaded as converted copies: your server didn't say whether that's allowed. Choose Original to download them." (`CONVERSION_UNCONFIRMED`);
    - "2 can't play on this phone as they are, so they'll be downloaded in High (1080p).";
    - "4 are already on this phone and will be added to your downloads.";
    - "Only the first 500 episodes are included.";
    - with mobile data off, "Downloads use Wi-Fi only. You can change this in Download settings."
  - Not enough space: "There isn't enough space. You need about 9.8 GB and have 4.1 GB free." The Download button is then disabled with that reason.
  - Buttons: "Download" and "Cancel". Offline: "You're offline. Connect to download." (the `AppError` message is used as is).
- **Notification ask:** on the first confirm, while notifications are not enabled on Android 13 or later, a dialog explains before the system prompt. It reads "Shoalwatch shows download progress in a notification. If you say no, downloads still carry on and you can see them in Downloads." with "Continue" (then the system prompt) and "Not now". Asked once (remembered in `download_prefs`); never blocks the download.

**Buttons on the browsing screens:**
- **Series screen:** "Download" opens a small menu with "Download season %1$s", "Download whole series" and "Download unwatched episodes" (each 56dp). Each episode row gets a 48dp download control whose label and icon follow its state: "Download Season 2, episode 5", "Downloading, 45 percent", "Downloaded".
- **Detail screen:** "Download" next to Play, becoming "Downloading 45%", "Downloaded" (opens Downloads), or the failure line with Retry.
- Offline, the controls stay visible and the sheet shows the offline message; they never silently do nothing.

**Settings screen** ("Download settings"):
- "Use mobile data" (switch) — "Off: downloads wait for Wi-Fi."
- "Save new downloads to": "This phone" / "SD card" (only when one is mounted), with the warning "Files on an SD card can be read by anyone who takes the card out and puts it in another device. Your sign-ins are never stored there." Existing downloads stay where they are.
- "Quality for new downloads": Ask each time / Original / High / Medium / Low.
- "Share downloads between profiles" (switch, for the active server) — "Profiles signed in to %1$s on this device can watch each other's downloads, but only titles their own account is allowed to see. Each profile keeps its own watched progress."
- A link to the system notification settings when notifications are off: "Notifications are off for Shoalwatch, so download progress shows only here."

**Servers screen dialogs** (added lines):
- Sign out: "Downloads that only this profile kept are deleted from this phone." With sharing on: "Shared downloads stay for the other profiles that can see them."
- Remove server: "Its downloads are deleted from this phone too."

- [ ] **Tests first — `DownloadActionsTest`** (`FakeDownloads`):
  - ask → quality → preparing → confirm → start, with the plan's session.
  - A default quality skips the quality step.
  - A switch of account while preparing drops the sheet and the message.
  - An `AppError` gives `Failed` with its message.
  - Confirm with `enoughSpace == false` does nothing.
  - A double confirm starts once.
- [ ] **Tests first — render tests** (both sizes, both font scales):
  - Every radio option says selected or not selected, and every target is at least 48dp.
  - The confirm lines appear only when their counts are non-zero.
  - The disabled Download button exposes its reason.
  - The settings screen's SD option is absent without a card.
  - The series menu's three actions and the episode control's state labels.
  - The detail button states.
  - The Servers dialogs' new lines.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.ui.*"` → pass.
- [ ] Commit: `feat(downloads): download a season, a series or every unwatched episode, with the size and space shown first`.

---

### Task 16: On-device smoke test

**Files:** Create `app/src/androidTest/java/app/shoalwatch/ui/DownloadSmokeTest.kt` (+ reuse `app/src/androidTest/assets/media/clip.mp4`).

- [ ] Write it (compile here; the controller runs it):
  - Seed one server and account through the real `AppGraph`, as `PlayerSmokeTest` does, cleaning up even if seeding fails.
  - An in-process `MockWebServer` serves `/System/Info/Public`, the film item, `/Items?ids=` (`CanDownload` true, `Size`), `PlaybackInfo` (direct play), `/MediaSegments/{id}`, one SRT subtitle, one WebP poster, and `/Items/{id}/Download` with Range support from the asset bytes. The FIRST download response is cut after 64 KiB.
  - Press Download on the film's detail screen, choose Original, confirm.
  - Wait up to 60 s for `COMPLETE` through the `Downloads` flow; WorkManager runs for real.
  - Assert:
    - the file in `filesDir/downloads/<serverId>/<itemId>/media.mp4` is byte-identical to the asset;
    - a second media request carried `Range: bytes=65536-` and `If-Range`;
    - every request carried `Authorization` and none had `api_key`;
    - the stored SHA-256 matches.
  - Then `mockServer.shutdown()` (the server is gone) and open Downloads: the film is listed with its title and poster, the poster coming from the pinned copy.
  - Play it: within 10 s the controller's `positionMs >= 2_000`, and the subtitle loads.
  - Back out: `offline_plays` holds a position > 0 for this profile, and no request reached the (stopped) server.
  - Finally remove the account and assert the folder is gone.
- [ ] Run: `./gradlew.bat assembleDebugAndroidTest` → builds.
- [ ] Commit: `test(downloads): on-device smoke test — resume, verify, browse and play offline`.

---

### Task 17: Milestone close

- [ ] **Full verification:** `./gradlew.bat clean testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease` → all green. `verifyReleaseManifest` passes with the new rules, and `verifyManifestRuleFixtures` reports 11 rules shown to fail. `SourceShapeTest`, `SqlCompatibilityTest`, `ArtworkBoundaryTest`, `Media3BoundaryTest` and `PlaybackBoundaryTest` pass.
- [ ] **Add `DownloadBoundaryTest`** (a source scan, as `ArtworkBoundaryTest`):
  - nothing under `ui/` references `DownloadStore`, a DAO, `DownloadPaths`, `File`, `java.io`, `MediaRoute` or `WorkManager`;
  - only `DownloadPaths.kt` builds a `File(` under `data/downloads`;
  - no source outside `net/HttpClientFactory` calls `OkHttpClient.Builder` or `newBuilder()`;
  - no `Data.Builder` / `setInputData` anywhere (WorkManager input data stays empty);
  - no `Log.` call outside `AppLog`.
- [ ] **Controller: emulator run** of every instrumented test, including `DownloadSmokeTest`. Repeat the original-download and Medium-download steps below once with each mockjelly behaviour flag from Task 1 (`--no-range`, `--etag-changes`, `--octet-stream`, `--candownload-missing`, `--download-forbidden`, `--legacy-auth-only`, `--hls-recut`, `--hls-idle-kill=5`). Each must end "Ready to watch" or with its plain failure line, never a stuck or unplayable download. Then a walkthrough against `tools/mockjelly` (`python -I mock_jellyfin.py 8096 --drop-after=65536`):
  - download a film (Original);
  - download a season of 3 (Medium: the HLS conversion), then airplane mode mid-season → "Waiting for a connection", and back on → it resumes;
  - kill the app during a download (`adb shell am kill`) → it resumes;
  - reboot the emulator mid-download → it resumes;
  - turn "Use mobile data" on and off;
  - switch to an SD card on an emulator with a virtual SD card, and eject it → "Insert the SD card…";
  - play offline: subtitles, Skip intro, the position is kept, the watched tick shows;
  - **a second profile on the same server**: sharing off → sees nothing; sharing on → sees it; a third profile that the mock never showed the film → never sees it;
  - sign out the owner → the shared film passes to the second profile, and the rest are deleted;
  - remove the server → the folder is gone.
  - Screenshots are checked by eye at font scale 1.0 and 2.0, with a TalkBack pass over every Downloads control, the sheet and the settings.
- [ ] **Whole-milestone review**, code and security against SPEC §6 and the threat model, focused on:
  - path building and deletion (traversal, links, other roots);
  - the download route's exact path;
  - the HLS parser and segment fetching as hostile input;
  - the Range/If-Range logic (a server that lies about lengths);
  - nothing written that is not fsync'd being claimed;
  - the verifier;
  - the sharing rule and visibility joins (mutation checks);
  - the removal rules;
  - pinned artwork re-validation;
  - the `ItemBytes` data source;
  - no token, path or title in a log, notification or WorkManager record;
  - the merged manifest (WorkManager's components, the exported job service, the removed diagnostics receiver);
  - the new tables' cascades;
  - SD-card exposure.
  
  Then a fix wave and a re-review.
- [ ] **`docs/security/milestone-4-check.md`:** one row per S1–S13 with evidence (test names), plus:
  - the new permissions and why;
  - the exported `SystemJobService` (`BIND_JOB_SERVICE`: only the system can bind);
  - **residual risks:**
    - files on a portable SD card are not encrypted and are readable on another device (warned; tokens and metadata never there);
    - downloads on internal storage are protected by Android's file-based encryption and app sandbox only;
    - Media3's extractors parse the downloaded bytes in-process during verification and playback (as in streaming);
    - a server can still make the app download up to 256 GiB per item to its own `/Items/{id}/Download` or `/videos/` paths (bounded by the free-space check);
    - the 6-hour `dataSync` limit;
    - Android 16 job quotas;
    - originals are guarded by both `CanDownload` and the server's own `/Download` permission; conversions (through `/videos/`) only by `CanDownload` being true, and are never offered when it is unknown;
    - a LOCAL play sends nothing to the server until Milestone 5.
- [ ] **ARCHITECTURE.md:**
  - new section "Downloads (`data/downloads`)": the tables, the queue pipeline, paths, the route, the verifier, the worker and notification, the sharing and removal rules, offline playback;
  - "Library cache": `pinned` is written by `DownloadStore`, and the seasons-list exemption;
  - "Tidy-up actions": the sweep and clean-up spare offline plays (the Milestone 5 hook);
  - the package map; "Build and supply-chain security" with the new manifest rules;
  - a "Version checks" row for WorkManager 2.12.0 (AAR metadata, resolved transitive list, merged-manifest result);
  - "Known limits":
    - no moving downloads between storages;
    - no trickplay previews saved (no trickplay yet);
    - a converted download keeps one audio track;
    - LOCAL plays are not reported until Milestone 5;
    - delete for everyone is Milestone 5's watch-and-delete;
    - one item at a time.
- [ ] **ChangeLog:** one plain-English line (minor bump), e.g. "v1.78 — DD/MM/YYYY — Download films, episodes, whole seasons or series to watch without a connection: pick the original or a smaller version, see the size first, and find everything organised by series and season in Downloads. Downloads carry on in the background and after a restart, and each one is checked before it's ready."
- [ ] **TODO.md** (sync script) and the Obsidian project note:
  - tick Milestone 4;
  - add the follow-ups:
    - UIDT jobs for Android 14+ (no Jetpack support yet), revisited at Milestone 8;
    - encryption of SD-card media at rest (Media3 `AesCipherDataSource`, Keystore key), with Ben at Milestone 8;
    - moving downloads between storages;
    - trickplay saved with downloads once the player has trickplay;
    - the results of "Unproven until Ben's real server";
    - Media3 1.10.x;
    - parallel downloads if Ben wants them.
- [ ] **Ben's phone test** (only with his explicit yes). This is the first and only contact with his server. Work through every row of "Unproven until Ben's real server" and record which path each took:
  - his 12.4 server; one season in Original and one in Medium over home Wi-Fi;
  - lock the phone for an hour mid-season;
  - reboot mid-download;
  - play offline in airplane mode: an MKV with ASS subtitles, an MP4 with SRT;
  - his child or restricted profile on the same phone with sharing on (it must see only what its server account allows);
  - an SD card if his phone has one.
  
  Record the results in the security check and ARCHITECTURE.

---

## Decisions made

| Decision | Alternative | Why |
|---|---|---|
| **Room is the queue; one WorkManager unique worker drains it** | Media3 `DownloadManager` + `DownloadService` + `SimpleCache` (AAFin's way) | Media3 keeps media inside its cache, which must be read through `CacheDataSource`, and the docs say not to read the files directly. That needs its own SQLite database (`media3-database`), a `DownloadService` subclass declared in the manifest, and has no notion of owners, visibility or pinning. Our own rows make the shared-tablet rule, verification and removal one transaction away from the library, and survive updates (Streamyfin lost queues on restart and on update) |
| WorkManager 2.12.0 foreground worker, type `dataSync` | User-Initiated Data Transfer jobs (API 34+) | The official docs say "there is currently no Jetpack library that supports UIDT jobs": it needs our own exported `JobService` and `RUN_USER_INITIATED_JOBS`. WorkManager gives persistence, constraints and reboot handling. The 6-hour `dataSync` limit and Android 16 quotas are handled by pausing plainly and resuming on the next foreground. UIDT is revisited at Milestone 8 |
| **One download at a time** | 2–3 in parallel | Each conversion is a server transcode; Ben's server and home upload are the bottleneck. Sequential order is what the user sees in the queue. It is a parameter, and can be raised later |
| Original files from `GET /Items/{itemId}/Download` | `/Videos/{id}/stream?static=true` | `/Download` carries the server's own download permission (`CustomAuthentication: Download`), so the server enforces the owner's rule as well as the app (`CanDownload`). The route allows exactly that one path for that one item. Correct whether or not the server honours `Range`: without it, an interrupted file restarts from zero (slower, never wrong) |
| `CanDownload` true: original or converted; false: neither; unknown: original only | Unknown is "not allowed" (downloads would break entirely on a server that omits it); unknown is "allowed" (conversions would bypass the owner's rule) | Correct either way: `/Items/{id}/Download` enforces the server's own download permission, so an unknown answer is safe for originals. Conversions go through `/videos/`, which has no download policy, so they need an explicit yes |
| **Converted downloads are the server's HLS conversion, saved segment by segment**, with a local playlist written by the app | A single progressive file from `/Videos/{id}/stream` with transcode parameters | A progressive transcode cannot be resumed (no stable length, no Range), and a multi-gigabyte conversion over hours must survive loss of network. Segments resume one by one within one play session, and Media3 plays local HLS. Segments of two sessions are never mixed: after process death, a reboot or 30 minutes, the item's conversion restarts from its first segment. That is correct whether or not a server cuts the same segments twice |
| Qualities: Original, High 8 Mbps 1080p, Medium 4 Mbps 720p, Low 1.5 Mbps 480p | Free bitrate choice | The same steps as Milestone 3's streaming qualities, so the device profile, the size estimates and the wording are shared |
| An original that this phone cannot play is offered as High instead, said before confirming | Download it anyway | "Every download checked playable": research complaint 6 (files that will not play offline); there is no server to fall back on offline |
| **Checked playable** = complete length, matching file signature, Media3 reads the tracks, the phone's counted decoders handle them, runtime within 5%, SHA-256 stored | Trust "download finished" | Research: Findroid #1251 (a partial file marked done), Streamyfin #588, #1661 and Fladder #713 (downloads that will not play). The hash detects later damage (an SD card) without a server checksum, which Jellyfin does not offer |
| **Internal storage by default; SD card (app-specific folder on a removable volume) only by explicit choice, with a plain warning** | SAF folder picker; SD by default | App-specific storage needs no permission and is removed on uninstall. Internal is sandboxed and covered by file-based encryption on API 29+. A portable SD card is not encrypted. SAF would put files where other apps can read them (S10). Tokens, the database and artwork are never on the card |
| Media on the SD card is not encrypted at rest this milestone | Media3 `AesCipherDataSource` with a Keystore key | The media is the user's own library, and paths hold only GUIDs. Encryption doubles the work of every seek and verification. On the to-do list for Ben to decide at Milestone 8 |
| Backup: no change needed | Add rules | `allowBackup="false"`, and both `data_extraction_rules.xml` and `backup_rules.xml` already exclude `root`, `file`, `database`, `sharedpref` and `external` (the official docs say app-specific external files are in `external` and are backed up by default, so that exclusion matters). Removable volumes are never backed up |
| **Keyed by server + item; `download_owners` per profile; visibility through `visible_items`** | One download per profile | SPEC §3.3 design consequence. A second profile's Download on something already here only adds an owner, after the server confirmed that profile's own `CanDownload` |
| Non-owners see a shared download only once it is complete; only owners pause, cancel or delete | Everyone controls everything | A child cannot cancel or delete a parent's download. Delete-for-everyone belongs to Milestone 5's watch-and-delete rules |
| **Account removal rule:** with sharing on, ownerless downloads pass to remaining profiles that can already see them; otherwise they are deleted | Always delete; always orphan | Deleting a parent's sign-in on a shared tablet must not wipe what the children watch, and must leave nothing on disk that no remaining profile may see. A 401 (expired sign-in) keeps everything |
| "Share downloads between profiles" is per server in Room; any profile on that server can change it | Device-wide; admin only | SPEC §3.3 says per server; Room ties it to the server's lifetime. There is no device-level owner concept before the Milestone 7 app lock. The permission rule makes it safe whoever switches it on |
| Series download leaves out Specials; Unwatched leaves them out too | Include them | Specials are often extras or behind-the-scenes, and large. They have their own "Download season" action. The same rule as Next Up and the episode navigator |
| A batch is capped at 500 items | No cap | Bounds the planner's requests (`checkOriginal` × 4 at a time) and the confirm sheet; the UI says so |
| Size estimates: `Size` from the server for originals; quality bitrate × runtime for conversions | Ask PlaybackInfo for every conversion at plan time | One `/Items?ids=` call per 50 items; conversions are not set up until they start |
| Free-space check before the batch AND before each item; reserve 1 GiB internal / 256 MiB SD; `allocateBytes` on internal | Check once | SPEC §3 row 10. The phone fills up during a long batch. `allocateBytes` makes the space real on internal storage; fast allocation is not supported on portable cards |
| **Wi-Fi only by default** ("Use mobile data" off) | Always any network | SPEC §3 row 10 |
| Converted downloads keep one audio track (the profile's remembered language) and text subtitles; picture subtitles only in originals | Burn subtitles in | Burn-in fixes one choice forever (Streamyfin #1955 complaint). The server's HLS carries one audio track |
| **Offline playback through the unchanged controller**: `PlayMethod.LOCAL`, `Resolved` with a `SavedMedia`, an `ItemBytes` data source, a saved-first gateway decorator, routed reports | A separate offline player | SPEC §4 rule 1 ("no separate offline mode"). It keeps every Milestone 3 feature (tracks, subtitles styling, skip, sleep, session teardown). `PlaybackController.kt` is at its 400-line limit, and this costs it one line |
| A saved copy is preferred even when online | Stream when online | Faster, no server load, and the same file that was checked playable. Quality changes do not apply to it (said in the player) |
| A LOCAL play sends nothing to the server in Milestone 4; it writes `offline_plays` and the local position | Report live sessions while online | Milestone 5's outbox sends offline plays with their original dates (SPEC §3.2). Doing half of that now would need a second, throwaway reporting path |
| The start-up sweep and idle clean-up spare `user_data` rows that have an offline play | Leave them as they are | Otherwise the next app start would clear the marker and a server refresh would undo an offline "watched" before Milestone 5 sends it (SPEC §3.2) |
| Pinned artwork in its own internal folder, re-validated with the same eight checks every time it is read | Keep it in Coil's disk cache | The disk cache is evictable by design (250 MB per server). Pinned pictures must stay with the download, and a file at rest is untrusted again when read |
| No trickplay ("previews") saved | Save trickplay tiles | The app has no trickplay yet (Milestone 3 decision); there is nothing to show them with. The extras model has room for it |
| **New permissions:** `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC` (a long-running download must be a `dataSync` foreground service on Android 14+), `POST_NOTIFICATIONS` (the progress notification; downloads run without it), `RECEIVE_BOOT_COMPLETED` (WorkManager's own reschedule receiver: "survives reboot" is a requirement), `WAKE_LOCK` (WorkManager holds one while foreground work runs; the Milestone 3 removal is reverted, and the player still sets no wake mode) | Fewer permissions | Each is required for an owner requirement. **SPEC S9 lists `FOREGROUND_SERVICE (+ data-sync type)` and `POST_NOTIFICATIONS`, but not `WAKE_LOCK` or `RECEIVE_BOOT_COMPLETED`**: this is recorded as a deliberate, justified addition for Ben to confirm at the milestone check (the SPEC is not edited) |
| WorkManager's `SystemJobService` stays exported (`BIND_JOB_SERVICE`); its `DiagnosticsReceiver` is removed | Remove the job service | The job service is how WorkManager runs work on API 23+, and only the system can bind it. The diagnostics receiver (`DUMP`) is not needed. The manifest rule allows exactly that one extra exported component, with that permission |
| A 401 on a media request is told apart by one `ServerApi` call: a dead token signs the account out (`SIGN_IN_NEEDED`); a working token means the server refused header authentication for media (`SERVER_AUTH_REFUSED`) | Put the token in the URL as `api_key` when headers fail | S6: tokens never go in URLs. The user gets a plain message saying what to change on the server (SPEC §9's Jellyfin 12 risk) |
| The spike is done at the desk, and every behaviour only a real server can show is handled by a correct-either-way path, tested with a mockjelly flag per behaviour | Run the spike against Ben's server first | No agent can reach Ben's server during the build; he tests once at the end. Designing for both answers costs a slower path in the worst case, never a wrong file |
| WorkManager initialised on demand with our `WorkerFactory`; logging at ERROR | The default initialiser | The worker gets the graph's queue without reflection on the graph. WorkManager's own logs stay quiet |
| No titles in notifications | "Downloading Episode 5…" | A shared tablet's lock screen and notification shade must not say what a profile is downloading |

## Unproven until Ben's real server

Checked once, at the end of the build, on Ben's phone against his 12.4 server (Task 17). The code already handles both answers to each question; this test says which path real use takes, and whether any message needs rewording.

| Question | The code's path either way | What to look for on the real server |
|---|---|---|
| Does `/Items/{id}/Download` honour `Range` and `If-Range` with header-only authentication? | Resume when it does; otherwise restart that file from zero | Turn Wi-Fi off mid-film for 30 s, then on. The Downloads row continues from its percentage (Range works) or starts again from 0% (it does not); the file is checked "Ready to watch" either way. Note which |
| Does it send `ETag` or `Last-Modified`, and do they stay the same between requests? | `If-Range` when a validator exists; three changed validators in a row is "The file kept changing on your server" | That message never appears for a file nobody changed |
| Is `MediaSources[0].Size` present and equal to the bytes sent? | Size when present, else the header total; the verifier checks the result | No "didn't pass its check" on a good file |
| What `Content-Type` does `/Download` send for MKV and MP4? | Only surely-not-media types are refused; the verifier decides | MKV and MP4 originals both reach "Ready to watch" |
| Is `CanDownload` filled when asked, and does it match the account's download permission? | true: both; false: neither; unknown: original only | With a test account whose download permission is off: every Download says "Your server doesn't allow this account to download this." With it on: originals and conversions both work. If conversions say the server "didn't say", `CanDownload` is not being sent |
| Does `/Download` accept header authentication with Jellyfin 12's legacy-authorisation setting off? | A refused media sign-in with a working token is `SERVER_AUTH_REFUSED`; a dead token signs out | Downloads work with the setting off. If "Your server wouldn't accept this app's sign-in for downloads" appears, record the server setting that fixes it |
| Do two PlaybackInfo answers cut identical HLS segments, and how long does the server keep an idle conversion? | A conversion resumes only within one session; otherwise it restarts from its first segment | Kill the app mid-way through a Medium download: it restarts that episode from 0% and finishes "Ready to watch". Lock the phone for an hour mid-season: every episode finishes |
| For a film with several versions, is the source whose `Id` is the item's id the file `/Items/{id}/Download` sends, and is it the primary one? (Tasks 5-8 review I-4) | That source's `Id`, `Size` and container are used, else the first; a size that disagrees with `Content-Length` is "The file kept changing on your server" | Download a grouped film (for example a 4K and a 1080p version): it reaches "Ready to watch" with the primary version's size |
| Does a reverse proxy in front of Ben's server strip `Range` or redirect media? | Range stripped: restarts from zero. Redirect: refused (no client follows redirects) | Note any download that fails with "Your server had a problem sending this" |

## Other open risks (only a real phone can prove)

- **Conversion speed** on Ben's server hardware (a slow transcoder only makes a converted download slow).
- **WorkManager on real phones:**
  - OEM battery managers (Samsung, Xiaomi) that kill foreground work;
  - the 6-hour `dataSync` limit on a long series;
  - Android 16 job quotas;
  - what happens to queued work across an app update (Shoalwatch's own start-up kick covers it).
- **`StorageManager.allocateBytes` and `getAllocatableBytes`** on Ben's phone; SD-card mounting and unmounting; the behaviour of `getExternalFilesDirs` on his device.
- **Media3 `MetadataRetriever`** on large MKVs (probe time within 30 s); local HLS playback and seeking.
- **Font scale 2.0** on the Downloads tab and the sheet in a small phone's portrait mode (Robolectric's stand-in font does not scale).
- **Shared tablet:** a real restricted (parental rating) profile on Ben's server sees only what its account allows, through sharing.
