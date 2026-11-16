# Milestone 5 — Housekeeping and Sync-back Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Everything a profile does to its watched state shows **at once** and reaches the server **in order, once, as that profile**, whether the phone is online, offline, closed straight afterwards or rebooted. Watching a downloaded episode or film to the credits offers **"Watched — delete download"**: one press marks it watched and frees the space, while the show still browses offline and the episode shows as watched. On a shared tablet the same press follows one tested rule for downloads other profiles share, and a restricted profile can never delete, or learn of, a title outside its own server permissions. When the server is next reachable, every offline watch is sent with its **original date**, without ever counting a play twice and without ever undoing "watched". The phone can keep the next episodes of a series downloaded by itself, and a Storage screen shows what is using space and deletes it.

**Architecture:** A new package, `data/sync`, owns a **durable outbox in Room** (`sync_outbox` + `sync_marks`, schema v5). Every tidy-up action (watched, unwatched, favourite, off Continue Watching), every stop the server did not hear, and every play of a saved copy writes its local change **and** its outbox row in ONE transaction, then returns. A `SyncSender` drains each profile's rows in sequence order through `ServerApi` (so the account's own pinned, authenticated client, and nothing for a signed-out account). Before each send it reads the server's copy (`GET /UserItems/{itemId}/UserData`) and a pure `ConflictRule` decides what to send, so a retry is idempotent by construction (absolute values, never a play date twice). Sending is triggered in the process (after each change, when a server comes back online, at start-up) and by one unique WorkManager job with a network constraint, so a change made just before the app is closed is still sent. The existing `pendingSyncAt` marker now means **"send this"**, not "discard this": it is cleared only when no outbox row covers the row. Housekeeping lives in `data/downloads`: `ReleaseStore` applies the watch-and-delete rule (per server: after anyone, or after everyone, has watched), writes per-profile `released_episodes` tombstones that keep the show browsable, and deletes the files only once the player has let go of them. `AutoDownloadNext` keeps the next N unwatched episodes on the phone through Milestone 4's `Downloads` seam. A Storage screen reads one new DAO. **`PlaybackController.kt` is not changed**: the player's "finished" mark reaches the screen through `stateOf`.

**Tech Stack:** as Milestone 4. **No new dependency** (WorkManager 2.12.0, `work-testing`, OkHttp `mockwebserver`, Robolectric and Compose test are already in the build) and **no new permission** (the sync job is ordinary, not foreground, background work; `RECEIVE_BOOT_COMPLETED` for WorkManager's reschedule receiver already arrived in Milestone 4). Media3 stays at 1.9.2.

## Global Constraints

- **Read first:**
  - `docs/SPEC.md`: §3 row 10; §3.1, §3.2 and §3.3 (all of them); §4; §6; §7 row 5.
  - `docs/ARCHITECTURE.md`, all of it, especially: "Layer rules"; "Authenticated requests"; "Library endpoints"; "Progress reports and teardown" (the one pending stop this milestone replaces); "Library cache" (visibility, pinned, the pending marker, SQLite 3.22, the download tables); "Tidy-up actions" (the five steps, the per-user queue, `LocalChangeLog`, the start-up sweep and **"For Milestone 5 (outbox)"**); "Player engine and controller" ("Finished", "Up next"); "Player screen".
  - `docs/security/milestone-1-check.md` (the Keystore key needs no unlocked device: background sync relies on it), `milestone-2-check.md` (**H1** free profile switching; **I4** a rejected token reloaded after a restart, scheduled for this milestone), `milestone-3-check.md` (**I4** the pending stop in memory), and `docs/reviews/milestone-3-carried.md` (Task 5 **M-2** "the durable outbox must not let an old resent stop overwrite a newer position", **M-3** "when the server cannot be reached, still save 'watched' and the stop position on the phone").
  - `research/offline-downloads-research.md`: ranked complaints 9 (no auto-download-next / auto-delete-watched), 10 (watched state not synced back) and 12 (weak storage management), and its API table (`POST /UserItems/{itemId}/UserData` "better for deferred sync than replaying session events").
  - The Milestone 3 plan (`docs/plans/2026-11-15-milestone-3-player.md`) for the player screen, and the Milestone 4 plan (`docs/plans/2026-11-15-milestone-4-downloads.md`): **this plan consumes Milestone 4's Produces blocks** for everything not yet built when it starts (named in each task's "Consumes (Milestone 4)").
  - The project to-do list (`TODO.md`): "Make quick ticks instant", "Watch and delete", "Profile sharing of downloaded media", "Watched syncs back up to the server", and "Confirm on the real server that retrying 'mark as watched' does not count the play twice".
- **API reference:** `docs/reference/jellyfin-openapi-12.4.0.json`. Every endpoint, parameter and field below was checked against it on 16/11/2026. Re-check anything new.
  - `GET /UserItems/{itemId}/UserData` (`GetItemUserData`): path `itemId` (uuid, required), query `userId` (uuid, optional). 200 `UserItemDataDto`; 401, 403, 404, 503. Security `DefaultAuthorization`.
  - `POST /UserItems/{itemId}/UserData` (`UpdateItemUserData`): the same parameters; JSON body `UpdateUserItemDataDto`, **every field nullable**: `Rating`, `PlayedPercentage`, `UnplayedItemCount`, `PlaybackPositionTicks` (int64), `PlayCount` (int32), `IsFavorite`, `Likes`, `LastPlayedDate` (date-time), `Played`, `Key`, `ItemId`. 200 `UserItemDataDto`; 401, 403, 404, 503.
    - **The reference is silent** on whether a field left out is left unchanged, and on whether `PlayCount` and `LastPlayedDate` are applied as sent. The plan sends only the fields it means, as absolute values, and is correct either way (Task 4; "Unproven").
  - `POST /UserPlayedItems/{itemId}` (`MarkPlayedItem`): query `userId`, `datePlayed` (date-time, optional). **`datePlayed` is never sent.** `DELETE /UserPlayedItems/{itemId}` (`MarkUnplayedItem`): query `userId`. Both 200, 401, 403, 404, 503.
  - `POST` / `DELETE /UserFavoriteItems/{itemId}`: query `userId`. 200, 401, 403, 503 (no 404 is documented; a 404 is still handled).
  - `UserItemDataDto`: `PlaybackPositionTicks` (int64), `PlayCount` (int32), `IsFavorite`, `Played` are not nullable; `LastPlayedDate` (date-time) is. Decoded with every field nullable anyway (untrusted).
  - `GET /Items/{itemId}` and `GET /UserViews` are as Milestone 2 uses them (here only to tell "item gone" from "route answers 404").
  - `POST /Sessions/Playing`, `/Progress`, `/Stopped` are unchanged and are **never replayed** by the outbox.
  - **The reference has no "server time" endpoint**, so phone-to-server clock skew cannot be measured; the conflict rule leans to the server inside a two-minute margin (Decisions).
- Package root `app.shoalwatch`; `minSdk 29`, `compileSdk`/`targetSdk 35`. Build with `JAVA_HOME=C:\Users\Ben\Android\jdk17\jdk-17.0.20.1+1`.
  - Verify with `./gradlew.bat clean testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease`.
  - No dependency is added, so `gradle/verification-metadata.xml` must not change. If it does, stop and find out why.
- **Security rules that bind every task** (all Milestone 3 and 4 rules still hold):
  - **Clients:** only `HttpClientFactory` builds OkHttp clients (`newBuilder()` counts, and is forbidden); no client follows redirects.
  - **Tokens:** a token goes only to an address that `AddressSelector.awaitOnline(serverId)` has verified, and only in the `Authorization` header. Never `api_key` in a URL, on disk, in WorkManager input data, in a notification or in a log.
  - **One path for requests:** every sync request goes through `ServerApi`, as the `Session` of the outbox row that holds it, and only as that session. `ServerApi` reads the token live, so a signed-out account sends nothing (`NOT_SIGNED_IN`, zero requests).
  - **Session rule for the shared tablet:** every read and write takes a `Session`. A profile's outbox rows are sent only as that profile. "Watched together" writes rows into each other profile's OWN outbox, and only for profiles that are signed in and that the server has shown the item. Nothing a screen holds for one session (a prompt, a sheet, a confirm) acts as another: it carries its own session and is dropped when the active session changes.
  - **Safeguarding:** a profile can release (watch-and-delete), delete or count only downloads it may see under Milestone 4's rule (its own `visible_items` record, plus ownership or sharing on and COMPLETE). The Storage screen never names another profile's download; it shows their usage as one size.
  - **Server responses are untrusted.** User data is bounded (64 KiB), decoded with every field nullable, range-checked (`PlayCount` 0..100,000 else unknown; position 0..`Long.MAX_VALUE / 10_000` ticks else 0; `LastPlayedDate` 2000-01-01..2200-01-01 else unknown). Ids are validated with `JellyfinId`.
  - **Never logged:** an id, path, URL, title, size, position or date. `AppLog` lines are fixed words plus an `AppError.Kind`, a `ChangeKind` or an exception class. Every new type that holds an id or a position has a redacting `toString()`.
  - **Notifications:** this milestone adds none.
  - **New permissions:** none. `verifyReleaseManifest` and its fixtures must pass unchanged.
- **Data rules:**
  - Room schema v5 with an explicit `Migration4To5`, tested like `Migration3To4`. No destructive fallback; schema exported (`5.json`).
  - Foreign keys `ON DELETE CASCADE`. `@Upsert` or `INSERT OR IGNORE` + `UPDATE`, never `REPLACE`.
  - **No SQL newer than SQLite 3.22** (`SqlCompatibilityTest` scans the new DAOs and the migration). A column cannot be added with `ALTER TABLE ADD COLUMN` here, because `MigrationTest` compares `sqlite_master` text and SQLite appends the new column after the table's constraints: a changed table is renamed, created afresh with Room's SQL, copied and the old one dropped (Task 2).
  - Device settings stay in `download_prefs` (Milestone 4). Per-profile settings live in Room so they go with the account; per-server settings in `download_settings` so they go with the server.
- TDD with a behavioural RED first.
  - Network code: real OkHttp + MockWebServer; the real `ServerApi` where the session or token is what is being proved.
  - Storage: in-memory Room, `TemporaryFolder` for files.
  - Coroutines: virtual time (`runTest`, `StandardTestDispatcher`); WorkManager with `WorkManagerTestInitHelper` and `SynchronousExecutor`.
  - Seams: fakes (`FakeSyncGateway`, `FakeSync`, `FakeHousekeeping`, plus Milestone 4's `FakeDownloads`, `FakeVolumes` and the existing fakes).
  - Screens: Robolectric + Compose test, 360x640dp and 840x1200dp (the player 640x360dp and 1200x840dp) at font scale 1.0 and 2.0.
  - No main file over ~400 lines (`SourceShapeTest`). **`PlaybackController.kt` is not edited.** `ServerStore.kt` is at 388 lines: Tasks 9 and 10 add at most 8 between them.
  - UK English, plain words, all user-facing text in `strings.xml` (counts as `<plurals>`).
  - Every control has a TalkBack label, a 48dp target, and works at font scale 2.0; no text under 13sp.
- **Commits:** conventional, each ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
  - One ChangeLog line per user-visible change (`vX.Y — DD/MM/YYYY — plain English`, minor bump).
  - Keep ARCHITECTURE.md accurate. Never edit `docs/SPEC.md`.
  - **Never start an emulator, run connected tests or touch a device** — the controller does that. **Nothing is installed on Ben's phone without his explicit yes.**
  - Never contact any real Jellyfin server.

---

## File structure (new and changed)

```
app/src/main/java/app/shoalwatch/
├── ShoalwatchApp.kt                          (modify: syncScheduler.start(), autoDownloadNext.start(), offline-play recovery)
├── AppGraph.kt                               (modify: outbox, sender, scheduler, sync seam, housekeeping seam, store hooks)
├── data/db/
│   ├── AppDatabase.kt                        (modify: v5, new entities and DAOs, SyncConverters, Migration4To5)
│   ├── Migration4To5.kt                      (new)
│   ├── RejectedSignInEntity.kt               (new: a 401 remembered across restarts; M2 check I4)
│   ├── AccountDao.kt                         (modify: getAllUsable, reject, clearRejected)
│   ├── library/ItemDao.kt                    (modify: pending rows covered by an outbox mark are never "leftovers")
│   ├── library/LibraryStore.kt               (modify: writePage leaves out items an unsent change took out of that list)
│   ├── sync/                                 (new) OutboxEntity, SyncMarkEntity, SyncStateEntity, SyncConverters, OutboxDao
│   └── downloads/
│       ├── DownloadSettingsEntity.kt         (modify: deleteShared)
│       ├── Converters.kt                     (modify: AfterFinish, SharedDelete)
│       ├── DownloadDao.kt                    (modify: pins also count tombstones; recomputePinned)
│       ├── DownloadStore.kt                  (modify: a re-download removes its tombstone; the last download of a series takes its tombstones)
│       ├── OfflineLibraryDao.kt              (modify: observeReleased)
│       ├── OfflinePlayDao.kt                 (modify: deleteCovered, unqueued)
│       └── (new) ReleasedEpisodeEntity, ProfileDownloadSettingsEntity, AutoNextRequestEntity, HousekeepingDao, ReleaseStore
├── data/sync/                                (new package, plus Milestone 4's OfflinePlays.kt)
│   ├── SyncModels.kt                         ChangeKind, Change, SyncStatus, DrainResult, ServerUserData, UserDataUpdate
│   ├── OutboxStore.kt                        ordered, coalesced rows per profile, with their marks
│   ├── ConflictRule.kt                       pure: what to send, given the server's copy
│   ├── SyncSender.kt                         one drain per profile, in order, once
│   ├── SyncScheduler.kt, SyncWorker.kt       when sending happens (in process, on reconnect, at start, WorkManager)
│   ├── OnlineEvents.kt                       "this server just came online" for the sender and auto-download
│   ├── Sync.kt                               the seam screens use (+ SyncRepository)
│   ├── OfflinePlays.kt                       (modify: recoverUnqueued)
│   └── api/SyncGateway.kt                    (+ SyncGatewayImpl, UpdateUserItemDataBody, SyncJson)
├── data/library/
│   ├── Playstate.kt                          (modify: KDoc, recordStop(reached), Recompute keys)
│   ├── PlaystateRepository.kt                (modify: local change + outbox row, no server call, no rollback)
│   ├── PlaystateLocal.kt                     (modify: restore removed; landed)
│   ├── PlaystateLanding.kt                   (new: what happens on this phone when a row is confirmed or overruled)
│   ├── StartupSweep.kt                       (modify: KDoc; the snapshot leaves marked rows alone)
│   └── api/Mappers.kt                        (modify: UserItemDataDto.toServerUserData)
├── data/playback/
│   ├── ServerPlaybackReports.kt              (modify: the in-memory pending stop is replaced by the outbox)
│   ├── LocalPlaybackReports.kt               (modify, Milestone 4's: LocalPlaying, onFinished)
│   └── PlaybackState.kt                      (modify: finished, set in stateOf; the controller is untouched)
├── data/downloads/
│   ├── DownloadModels.kt                     (modify: AfterFinish, SharedDelete, ReleaseOutcome, ReleaseChoice, ProfileSettings,
│   │                                         StorageView + rows, WatchedSummary, DownloadTarget.NextUnwatched, OfflineSeason.released)
│   ├── Housekeeping.kt                       (new: the seam screens use) + HousekeepingRepository.kt
│   ├── LocalPlaying.kt, ReleasedFiles.kt     (new: files go once the player lets go)
│   ├── AutoDownloadNext.kt                   (new)
│   ├── DownloadPlanner.kt                    (modify: NextUnwatched)
│   ├── DownloadRepository.kt                 (modify, Milestone 4's: OfflineSeason.released)
│   └── ShoalwatchWorkerFactory.kt            (modify, Milestone 4's: builds SyncWorker too)
├── data/servers/ServerStore.kt               (modify: rejected sign-ins persisted; usableAccounts)
└── ui/
    ├── player/WatchedPrompt.kt, WatchedPromptLogic.kt   (new)
    ├── player/PlayerViewModel.kt, PlayerUiState.kt, PlayerScreen.kt   (modify)
    ├── downloads/StorageScreen.kt, StorageViewModel.kt, StorageRows.kt, SyncStatusLine.kt   (new)
    ├── downloads/DownloadsScreen.kt, DownloadsViewModel.kt, OfflineSeriesScreen.kt,
    │   DownloadSettingsScreen.kt, DownloadSettingsViewModel.kt   (modify, Milestone 4's)
    ├── servers/ServersScreen.kt, ServersViewModel.kt   (modify: waiting changes per profile, sign-out and remove)
    ├── browse/ItemActions.kt                 (modify: an offline tick no longer fails)
    ├── nav/AppNav.kt                         (modify: Routes.STORAGE = "downloads/storage")
    └── present/SyncText.kt, StorageText.kt   (new, pure)
app/src/main/res/values/strings.xml           (modify)
app/src/test/java/app/shoalwatch/ui/browse/BrowseFakes.kt   (modify: FakeSync, FakeHousekeeping; FakeDownloads gains NextUnwatched)
app/src/androidTest/java/app/shoalwatch/ui/SyncSmokeTest.kt   (new)
tools/mockjelly/                              (modify: three profiles with their own state, playstate flags, test-only routes)
docs/security/milestone-5-check.md            (new)
```

---

### Task 1: The stand-in server learns profiles of their own, and every way a real server might take a sync

SPEC §3.2's open questions (does `UpdateItemUserData` apply `PlayCount` and `LastPlayedDate`? does a repeated mark count twice? does a reply get lost after the write?) can only be answered by Ben's server, once, at the end. So the stand-in server gets **one flag per behaviour**, and the app's tests and the emulator walkthrough run both ways. It also needs more than one profile: until now its watched state is global and it has one user.

**Files:**
- Modify: `tools/mockjelly/mock_jellyfin.py`, `tools/mockjelly/test_mock_jellyfin.py`, `tools/mockjelly/README.md`.

**Interfaces:** Consumes nothing in the app. Produces the users, routes and flags below.

**Profiles.** Three users, each with a password, a token set of its own and its own watched state (`STATE[(userId, itemId)]`, replacing the global `STATE[itemId]`):

| User | Id | Password | Sees |
|---|---|---|---|
| `ben` (unchanged) | `aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa` | `test-password` | everything |
| `anna` | `bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb` | `test-password-2` | everything |
| `kid` | `cccccccccccccccccccccccccccccccc` | `test-password-3` | everything except the film "Night Shift" (`GET /Items/{id}` 404, left out of every list) |

A request's user is the token's user. A `userId` query naming another user is 403 (Jellyfin refuses a non-admin acting for someone else).

**Playstate behaviour (default), per user:** `GET /UserItems/{id}/UserData` returns that user's `UserItemDataDto` (with `LastPlayedDate` when set). `POST /UserItems/{id}/UserData` applies each non-null field as sent (`Played`, `PlayCount`, `PlaybackPositionTicks`, `IsFavorite`, and `LastPlayedDate` parsed as given, not "now"). `POST /UserPlayedItems/{id}` sets played, position 0, `count = max(count, 1)`, last played now; a `datePlayed` query is **400** (the app must never send one). `DELETE` unplays (count 0, last played null).

**Flags** (each changes one thing; any combination is allowed):

| Flag | Behaviour |
|---|---|
| `--userdata-ignores-dates` | `POST /UserItems/{id}/UserData` ignores `PlayCount` and `LastPlayedDate` (applies the rest) |
| `--markplayed-counts` | `POST /UserPlayedItems/{id}` adds one to the play count on every call, even when already played |
| `--lose-reply-once` | the FIRST `POST /UserItems/*/UserData` or `/UserPlayedItems/*` is applied, then the connection is closed with no response |
| `--no-userdata-get` | `GET /UserItems/{id}/UserData` is 404 for every item (a server without the route); `/Items/{id}` still answers |
| `--refuse-userdata=ITEMID` | every user-data route for that item is 403 |
| `--clock-skew=SECONDS` | every `LastPlayedDate` the server writes itself is shifted by that many seconds |

**Test-only routes** (no token needed, loopback only, never in the app):
- `GET /mock/userdata?user=<name>` — that user's state for every item touched: `played`, `count`, `pos`, `fav`, `last`, as JSON.
- `POST /mock/userdata?user=<name>&item=<id>` with a JSON body of those fields — "another device changed it" (for the conflict rule).
- `GET /mock/playstate` — every playstate request in order: method, path, the token's user, the `userId` query, whether `datePlayed` was present, and the body's field names (never values), as JSON.

- [ ] **Tests first, in `test_mock_jellyfin.py`:** one per profile rule (the token's user decides; a foreign `userId` is 403; `kid` never sees Night Shift in `/Items`, `/Items/{id}`, `/Shows/.../Episodes` or `/UserItems/Resume`; `anna`'s tick does not change `ben`'s state); one per default playstate behaviour (`datePlayed` 400; absolute `PlayCount`; `LastPlayedDate` kept as sent); one per flag; and the three test routes. Existing tests keep passing with `ben` as before.
- [ ] Implement (standard library only, `python -I`). Logging stays method + path + token yes/no. Document every user, route and flag in the README with one `curl` each.
- [ ] Run: `python -I tools/mockjelly/test_mock_jellyfin.py` → all pass.
- [ ] Commit: `test(mockjelly): three profiles with their own watched state, and one flag per way a server might take a sync`.

---

### Task 2: Room v5 — the outbox and housekeeping tables, with an explicit, tested migration

**Files:**
- Create: `data/db/sync/OutboxEntity.kt`, `SyncMarkEntity.kt`, `SyncStateEntity.kt`, `SyncConverters.kt`; `data/db/RejectedSignInEntity.kt`; `data/db/downloads/ReleasedEpisodeEntity.kt`, `ProfileDownloadSettingsEntity.kt`, `AutoNextRequestEntity.kt`; `data/db/Migration4To5.kt`; `data/sync/SyncModels.kt` (the `ChangeKind` enum only; the rest in Task 3).
- Modify: `data/db/AppDatabase.kt` (version 5, the seven new entities, `@TypeConverters(..., SyncConverters::class)`, `outboxDao()`, `housekeepingDao()`, `Migration4To5`); `data/db/downloads/DownloadSettingsEntity.kt`; `data/db/downloads/Converters.kt`; `data/downloads/DownloadModels.kt` (`AfterFinish`, `SharedDelete`); `app/schemas/.../5.json` (generated).
- Test: `data/db/MigrationTest.kt` (4→5, 1→5), `data/db/SyncCascadeTest.kt`, `data/db/SqlCompatibilityTest.kt` (confirm it scans the new files).

**Interfaces — Consumes:** `AccountEntity`, `ServerEntity`, `ItemKind` (+ `ItemKindConverter`). **Consumes (Milestone 4):** `DownloadSettingsEntity`, `OfflinePlayEntity`, `DownloadQuality`, `Converters`, `Migration3To4`, `4.json`.

**Produces:**
```kotlin
// data/sync/SyncModels.kt (enum; the rest of the file is Task 3's)
/** PLAYED and FAVOURITE carry [Change.flag]; POSITION carries [Change.positionMs] (0 = off Continue Watching); PLAY is one finished watch of a saved copy, at [Change.occurredAt]. */
enum class ChangeKind { PLAYED, FAVOURITE, POSITION, PLAY }

// data/downloads/DownloadModels.kt (added)
/** What happens when a downloaded item is finished in the player (SPEC 3.1), per profile. */
enum class AfterFinish { ASK, ALWAYS, NEVER }
/** "Delete shared downloads" (SPEC 3.3), per server. */
enum class SharedDelete { ANYONE, EVERYONE }

// data/db/sync/OutboxEntity.kt — table `sync_outbox`; FK (serverId, userId) -> accounts CASCADE; index (serverId, userId, seq)
@Entity(tableName = "sync_outbox", /* FK, index as above */)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,       // INTEGER PRIMARY KEY AUTOINCREMENT: never reused, so it is the send order
    val serverId: String, val userId: String, val itemId: String,
    val itemKind: ItemKind,
    val kind: ChangeKind,
    val flag: Boolean?,                                       // PLAYED, FAVOURITE
    val positionMs: Long?,                                    // POSITION
    val occurredAt: Long,                                     // phone wall clock, epoch ms: when the change was made (PLAY: when it was finished)
    val refreshKeys: String,                                  // Recompute keys joined by ',' (at most 2,000 characters)
    val fromOfflinePlay: Boolean,
    @ColumnInfo(defaultValue = "0") val sentOnce: Boolean = false,  // a request for this row may have reached the server
    val sentPlayCount: Int? = null,                           // PLAY: the absolute PlayCount sent first, resent unchanged
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    @ColumnInfo(defaultValue = "0") val serverErrors: Int = 0,
    @ColumnInfo(defaultValue = "0") val notBefore: Long = 0,
) { override fun toString() = "OutboxEntity(kind=$kind)" }

// data/db/sync/SyncMarkEntity.kt — table `sync_marks`; PK (seq, itemId, leftList); FK seq -> sync_outbox(seq) CASCADE;
// index (serverId, userId, itemId). leftList "" is a plain mark ("this row's pendingSyncAt is mine"); any other value is a
// list key the change took the item out of (e.g. "resume").
data class SyncMarkEntity(val seq: Long, val serverId: String, val userId: String, val itemId: String, @ColumnInfo(defaultValue = "") val leftList: String)

// data/db/sync/SyncStateEntity.kt — table `sync_state`; PK (serverId, userId); FK -> accounts CASCADE
data class SyncStateEntity(val serverId: String, val userId: String, val lastSyncedAt: Long)

// data/db/RejectedSignInEntity.kt — table `rejected_sign_ins`; PK (serverId, userId); FK -> accounts CASCADE
data class RejectedSignInEntity(val serverId: String, val userId: String, val rejectedAt: Long)

// data/db/downloads/ReleasedEpisodeEntity.kt — table `released_episodes`; PK (serverId, userId, itemId); FK -> accounts CASCADE;
// index (serverId, seriesId). "This profile watched and deleted this episode": keeps the episode, season and series pinned.
data class ReleasedEpisodeEntity(val serverId: String, val userId: String, val itemId: String, val seriesId: String, val seasonId: String?, val releasedAt: Long)

// data/db/downloads/ProfileDownloadSettingsEntity.kt — table `profile_download_settings`; PK (serverId, userId); FK -> accounts CASCADE
data class ProfileDownloadSettingsEntity(
    val serverId: String, val userId: String,
    @ColumnInfo(defaultValue = "ASK") val afterFinish: AfterFinish,
    @ColumnInfo(defaultValue = "0") val autoNextCount: Int,  // 0, 1, 2, 3 or 5
)

// data/db/downloads/AutoNextRequestEntity.kt — table `auto_next_requests`; PK (serverId, userId, seriesId); FK -> accounts CASCADE
data class AutoNextRequestEntity(
    val serverId: String, val userId: String, val seriesId: String,
    val anchorItemId: String, val quality: DownloadQuality, val requestedAt: Long,
    @ColumnInfo(defaultValue = "0") val tries: Int, val blockedAt: Long?,     // blockedAt: the last plan had too little free space
)

// data/db/downloads/DownloadSettingsEntity.kt (changed)
data class DownloadSettingsEntity(
    val serverId: String,
    @ColumnInfo(defaultValue = "0") val shareBetweenProfiles: Boolean,
    @ColumnInfo(defaultValue = "ANYONE") val deleteShared: SharedDelete = SharedDelete.ANYONE,
)

// data/db/Migration4To5.kt
object Migration4To5 : Migration(4, 5)
```
**`Migration4To5`, in this order** (all `CREATE` statements are Room's generated SQL copied verbatim from `5.json`):
1. `CREATE TABLE` for `sync_outbox`, `sync_marks`, `sync_state`, `rejected_sign_ins`, `released_episodes`, `profile_download_settings`, `auto_next_requests`, and `CREATE INDEX` for `index_sync_outbox_serverId_userId_seq`, `index_sync_marks_serverId_userId_itemId`, `index_released_episodes_serverId_seriesId`.
2. **`download_settings` rebuilt** (a new column; see Data rules): ``ALTER TABLE `download_settings` RENAME TO `download_settings_v4` ``; Room's `CREATE TABLE` for `download_settings`; ``INSERT INTO `download_settings` (`serverId`, `shareBetweenProfiles`, `deleteShared`) SELECT `serverId`, `shareBetweenProfiles`, 'ANYONE' FROM `download_settings_v4` ``; ``DROP TABLE `download_settings_v4` ``. Nothing references `download_settings`, so the rename touches no other table on any SQLite version.
3. **Milestone 4's offline plays become outbox rows**, so nothing watched under v4 is lost:
   - ``INSERT INTO sync_outbox (serverId, userId, itemId, itemKind, kind, flag, positionMs, occurredAt, refreshKeys, fromOfflinePlay, sentOnce, sentPlayCount, attempts, serverErrors, notBefore) SELECT p.serverId, p.userId, p.itemId, COALESCE((SELECT i.kind FROM items i WHERE i.serverId = p.serverId AND i.itemId = p.itemId), 'OTHER'), 'PLAY', NULL, NULL, p.playedAt, 'resume,nextUp', 1, 0, NULL, 0, 0, 0 FROM offline_plays p WHERE p.played = 1 AND p.playedAt IS NOT NULL ORDER BY p.playedAt``;
   - the same for `'POSITION'`, `p.positionMs`, `p.updatedAt`, `WHERE p.played = 0 OR (p.positionMs > 0 AND p.updatedAt > p.playedAt)`, `ORDER BY p.updatedAt`;
   - ``INSERT INTO sync_marks (seq, serverId, userId, itemId, leftList) SELECT seq, serverId, userId, itemId, '' FROM sync_outbox``.
   
   Plain `INSERT ... SELECT`, `COALESCE` and `CASE`-free SQL: nothing newer than SQLite 3.22.

- `SyncConverters` stores `ChangeKind` by name. A name this version does not know cannot be sent, so `OutboxDao` reads `kind` as text (`OutboxRow`) and the store deletes that row (with its marks) and logs "Unknown change dropped" (Task 3). `Converters` gains `AfterFinish` (unknown → `ASK`) and `SharedDelete` (unknown → `ANYONE`).

- [ ] **Tests first — `MigrationTest`:**
  - Build a real v4 database from `4.json` with a row in every v4 table, and three `offline_plays` rows: finished once (`played = 1`, `playedAt = updatedAt`), finished then re-watched part way (`playedAt < updatedAt`, `positionMs > 0`), and unfinished. Migrate with `Migration4To5`.
  - Every v4 row is identical, column by column, except `download_settings`, which gains `deleteShared = 'ANYONE'` with the same `shareBetweenProfiles`.
  - The offline plays became exactly: PLAY at `playedAt`; PLAY then POSITION; POSITION at `updatedAt`; each `fromOfflinePlay`, each with one plain mark, in that order of `seq`, with `itemKind` from `items` (or `OTHER` when the item row is missing).
  - The new tables accept rows; `sync_outbox.seq` keeps rising after the highest row is deleted (AUTOINCREMENT).
  - `sqlite_master` SQL is identical for a migrated and a fresh v5 database, from v4 and from v1 through all four migrations (including `sqlite_sequence`).
- [ ] **Tests first — `SyncCascadeTest`** (through the real `ServerStore.removeAccount` and `removeServer`): removing an account deletes its `sync_outbox` rows (and their `sync_marks` through the outbox), `sync_state`, `rejected_sign_ins`, `released_episodes`, `profile_download_settings` and `auto_next_requests`, and nothing of another account; removing a server deletes all of them for every account on it and its `download_settings`. Deleting an outbox row deletes its marks.
- [ ] Implement. Build once to export `5.json`, then copy its `createSql` strings into `Migration4To5` exactly.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.db.*"` → pass.
- [ ] Commit: `feat(sync): Room v5 outbox and housekeeping tables with an explicit, tested migration`.

---

### Task 3: `OutboxStore` — ordered, coalesced, durable changes per profile

**Files:**
- Create: `data/db/sync/OutboxDao.kt`, `data/sync/OutboxStore.kt`; the rest of `data/sync/SyncModels.kt`.
- Test: `data/sync/OutboxStoreTest.kt`, `data/sync/OutboxOrderTest.kt`.

**Interfaces — Consumes:** `AppDatabase`, `Session`, `ItemKind`. **Consumes (Milestone 4):** nothing.

**Produces:**
```kotlin
// data/sync/SyncModels.kt
/** One change to send. Never printed: it holds an item and a position. */
data class Change(
    val itemId: String, val itemKind: ItemKind, val kind: ChangeKind,
    val flag: Boolean? = null, val positionMs: Long? = null,
    val occurredAt: Long, val fromOfflinePlay: Boolean = false,
) {
    init {
        require((kind == ChangeKind.PLAYED || kind == ChangeKind.FAVOURITE) == (flag != null)) { "A flag goes with PLAYED and FAVOURITE only." }
        require((kind == ChangeKind.POSITION) == (positionMs != null && positionMs >= 0)) { "A position goes with POSITION only." }
    }
    override fun toString() = "Change(kind=$kind)"
}

/** What one profile's waiting changes look like to a screen. */
data class SyncStatus(val waiting: Int, val stuck: Int, val needsSignIn: Boolean, val lastSyncedAt: Long?) {
    companion object { val NONE = SyncStatus(waiting = 0, stuck = 0, needsSignIn = false, lastSyncedAt = null) }
}

enum class DrainResult { EMPTY, WAITING_FOR_CONNECTION, WAITING_FOR_SIGN_IN, BACKING_OFF, STOPPED }

/** The server's copy, validated (Task 4). [playCount] and [lastPlayedAt] null: not sent, or out of range. */
data class ServerUserData(val played: Boolean, val playCount: Int?, val positionMs: Long, val favourite: Boolean, val lastPlayedAt: Long?) {
    override fun toString() = "ServerUserData(<redacted>)"
}

/** The body of POST /UserItems/{id}/UserData: only non-null fields are sent. */
data class UserDataUpdate(val played: Boolean? = null, val playCount: Int? = null, val positionMs: Long? = null, val lastPlayedAt: Long? = null) {
    override fun toString() = "UserDataUpdate(<redacted>)"
}

// data/sync/OutboxStore.kt
internal class OutboxStore(private val db: AppDatabase, private val now: () -> Long) {
    /** Marks and the lists the change took items out of, for [enqueue]. */
    class Marks(val itemIds: Collection<String>, val leftLists: Collection<Pair<String, String>>)   // (itemId, listKey)
    class Landed(val itemId: String, val itemKind: ItemKind, val kind: ChangeKind, val occurredAt: Long, val fromOfflinePlay: Boolean,
                 val unmarked: List<String>, val refreshKeys: List<String>) { override fun toString() = "Landed(<redacted>)" }

    /**
     * MUST be called inside the caller's transaction (the local change and its row commit together). Adds [change] for [s]
     * after every earlier row. An unsent row for the same (profile, item, kind) is replaced (its marks go with it), except PLAY,
     * which is never coalesced (each is a separate watch). Returns the new seq.
     */
    suspend fun enqueue(s: Session, change: Change, marks: Marks, refreshKeys: Collection<String>): Long
    /** The next row to send for [s], by the order rule below; null when nothing is due. */
    suspend fun head(s: Session, nowMs: Long): OutboxEntity?
    /** Committed BEFORE the request leaves: sentOnce = 1, attempts + 1, and for PLAY the absolute count (kept if already set). */
    suspend fun markSending(seq: Long, playCount: Int?)
    /** A 5xx: serverErrors + 1, notBefore = now + min(60 s × 2^(serverErrors − 1), 6 h). */
    suspend fun deferAfterServerError(seq: Long)
    /** In the caller's transaction: deletes the row (and its marks) and says which marks are now uncovered. Null if it is gone. */
    suspend fun take(seq: Long): Landed?
    suspend fun hasMark(s: Session, itemId: String): Boolean
    /** Items an unsent change took out of [listKey] for [s] (for LibraryStore.writePage). */
    suspend fun leftList(s: Session, listKey: String): Set<String>
    fun status(s: Session, signedIn: Flow<Boolean>): Flow<SyncStatus>
    suspend fun waiting(s: Session): Int
    suspend fun accountsWithWork(): List<Session>
    fun serversWithWork(): Flow<Set<String>>
    suspend fun touchSynced(s: Session)           // sync_state.lastSyncedAt = now
    companion object { const val STUCK_AFTER = 10; const val MAX_REFRESH_CHARS = 2_000 }
}
```
**The order rule (`head`).** Rows are read for `s` in `seq` order (50 at a time):
- a row with `serverErrors < STUCK_AFTER` and `notBefore > now` → **null** (the whole profile waits, so order is kept);
- a **stuck** row (`serverErrors >= STUCK_AFTER`) not yet due is passed over, and every later row **for the same item** is passed over too (per-item order is never broken);
- otherwise that row is the head.

So one item the server keeps refusing with 5xx cannot block every other change, and nothing for that item overtakes it.

**Other rules:**
- `enqueue` refuses (`IllegalArgumentException`, fixed message) ids that are not `JellyfinId`-normalised and `refreshKeys` over 2,000 characters; it is the only writer of `sync_outbox` and `sync_marks`.
- Coalescing happens only while a row is unsent or in flight: a row being sent may be replaced; its later `take` then finds nothing and returns null (Task 5 treats that as "a newer row will carry it").
- `take` lists in `unmarked` each item of the row's marks that no other row for `s` still marks.
- A row whose `kind` this version cannot read is deleted on read and logged (fixed words).
- `status`: `waiting` = rows for `s`; `stuck` = stuck rows; `needsSignIn` = `waiting > 0` and not signed in; `lastSyncedAt` from `sync_state`. Reads that fail emit `SyncStatus.NONE` (fail closed, as `ReadHealth`), never throw.

- [ ] **Tests first — `OutboxStoreTest`** (in-memory Room):
  - enqueue in a rolled-back transaction leaves no row and no mark;
  - PLAYED true then PLAYED false for one item leaves one row (the second, with a new seq, carrying the second's marks); FAVOURITE and POSITION the same; two PLAYs stay two rows;
  - coalescing never crosses profiles, items or kinds;
  - `take` returns the uncovered marks only (a season row and an episode row both marking an episode: taking the season leaves the episode covered);
  - `leftList` returns only unsent changes' departures for that profile and list;
  - `status` counts per profile; `needsSignIn` follows the flow; a failing read emits `NONE`;
  - `toString` of every type above prints no id, position or date.
- [ ] **Tests first — `OutboxOrderTest`:** heads come in seq order; a deferred (5xx) row holds back the profile until due (virtual clock); a stuck row is passed over by another item's row but not by its own item's later row; another profile's rows are unaffected by either.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.sync.*" --tests "app.shoalwatch.data.db.SqlCompatibilityTest"` → pass.
- [ ] Commit: `feat(sync): a durable, ordered outbox per profile that coalesces repeated taps`.

---

### Task 4: `SyncGateway` and `ConflictRule` — what is sent, checked against what the server holds

**Files:**
- Create: `data/sync/api/SyncGateway.kt` (interface, `SyncGatewayImpl`, `UpdateUserItemDataBody`, `SyncJson`), `data/sync/ConflictRule.kt`.
- Modify: `data/library/api/Mappers.kt` (`UserItemDataDto.toServerUserData()`, using the existing lenient `instant` parser).
- Test: `data/sync/api/SyncGatewayTest.kt` (MockWebServer, real `ServerApi` via `ServerApiTestBase`), `data/sync/api/SyncGatewayHostileTest.kt`, `data/sync/ConflictRuleTest.kt`, `data/sync/FakeSyncGateway.kt` (test source).

**Interfaces — Consumes:** `ServerApi.get/post/delete`, `LibraryGateway.item` and `views`, `UserItemDataDto` (internal, Milestone 2), `JellyfinId`, `Ticks`. **Consumes (Milestone 4):** nothing.

**Produces:**
```kotlin
internal interface SyncGateway {
    /** GET /UserItems/{id}/UserData?userId=. Null: the route answered 404 but GET /Items/{id} found the item (a server without the route). */
    suspend fun userData(s: Session, itemId: String): ServerUserData?
    /** POST /UserItems/{id}/UserData?userId= with only [body]'s non-null fields. Idempotent (absolute values), so ServerApi's one retry is allowed. */
    suspend fun update(s: Session, itemId: String, body: UserDataUpdate)
    /** POST /UserPlayedItems/{id}?userId= — NEVER with datePlayed. For seasons and series, whose children the server marks. */
    suspend fun markPlayed(s: Session, itemId: String)
    suspend fun markUnplayed(s: Session, itemId: String)                 // DELETE /UserPlayedItems/{id}?userId=
    suspend fun favourite(s: Session, itemId: String, on: Boolean)       // POST / DELETE /UserFavoriteItems/{id}?userId=
    /** True only when GET /Items/{id} is SERVER_NOT_FOUND AND GET /UserViews succeeds: the item is gone, not the route or the proxy. */
    suspend fun itemGone(s: Session, itemId: String): Boolean
}
internal class SyncGatewayImpl(private val api: ServerApi, private val library: LibraryGateway) : SyncGateway

@Serializable internal class UpdateUserItemDataBody(
    @SerialName("Played") val played: Boolean? = null,
    @SerialName("PlayCount") val playCount: Int? = null,
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long? = null,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null,       // ISO-8601 UTC, e.g. "2026-11-15T14:05:00Z"
)
internal object SyncJson { val ENCODER: Json }                              // explicitNulls = false, encodeDefaults = true (as PlaybackJson)

// data/sync/ConflictRule.kt (pure)
internal sealed interface Step {
    /** The server already has it: confirm without sending. */ data object AlreadyThere : Step
    /** The server holds a newer change: drop this one; the server's copy is shown. */ data object ServerIsNewer : Step
    data class Update(val body: UserDataUpdate) : Step
    data object MarkPlayed : Step
    data object MarkUnplayed : Step
    data class Favourite(val on: Boolean) : Step
}
internal object ConflictRule {
    const val MARGIN_MS = 120_000L
    /** [server] null: unknown (no user-data route). Needs no server copy for FAVOURITE or a container's PLAYED. */
    fun decide(row: OutboxEntity, server: ServerUserData?): Step
    fun needsServerCopy(row: OutboxEntity): Boolean
}
```
**The rule, per kind.** "Newer on the server" means `server.lastPlayedAt != null && server.lastPlayedAt >= row.occurredAt - MARGIN_MS`: inside the margin the server wins, because the clocks cannot be compared exactly and the server's copy is the one that cannot lose "watched".

| Row | Server copy | Step |
|---|---|---|
| PLAYED true, film or episode | played | `AlreadyThere` |
| | not played, or unknown | `Update(played = true, playCount = max(server.playCount ?: 0, 1)` (left out when unknown), `positionMs = 0)` |
| PLAYED true, season, series or other | not read | `MarkPlayed` |
| PLAYED false | not played | `AlreadyThere` |
| | played and newer | `ServerIsNewer` (**watched is never undone by a sync**) |
| | played and older, or unknown | `MarkUnplayed` |
| FAVOURITE | not read | `Favourite(flag)` (an explicit tap; the server keeps no time for it) |
| POSITION p | played and newer, or newer | `ServerIsNewer` (the later change wins) |
| | older, or unknown | `Update(positionMs = p, lastPlayedAt = row.occurredAt` only when `p > 0)` |
| PLAY at t | `sentOnce` and `server.playCount >= row.sentPlayCount` | `AlreadyThere` (the earlier send landed) |
| | otherwise | `Update(played = true, playCount = row.sentPlayCount ?: server.playCount?.plus(1)` (left out when unknown), `lastPlayedAt = t` and `positionMs = 0` **only when not newer on the server**) |

**Why a retry cannot double-count:** a PLAY's count is computed ONCE, from the server's copy, stored (`markSending`) before the request leaves, and every retry sends that same absolute number, or nothing if the server already shows it. A tick sends `max(count, 1)`. No request ever carries `datePlayed`, so the server's "add one per dated mark" path is never used. The worst case (another device plays the item between our read and our write) is one play under-counted, never one over.

**Gateway rules:**
- Paths only from `JellyfinId`-normalised ids; `userId` is always the session's user. Bodies by `SyncJson.ENCODER` (no nulls). Dates are `Instant.ofEpochMilli(...).toString()` truncated to seconds (UTC, `Z`).
- `userData`: 64 KiB bound; decoded with `UserItemDataDto` (every field nullable); validated by `toServerUserData` as in Global Constraints; an undecodable body is `SERVER_ERROR`. A `SERVER_NOT_FOUND` answer calls `library.item(s, itemId)`: found → `null` (unknown copy); its own failure passes through.
- Retries: `userData` and `update` opt in to `ServerApi`'s one retry (a read; an absolute write). `markPlayed`, `markUnplayed`, `favourite` opt in too (idempotent; as Milestone 2).

- [ ] **Tests first — `ConflictRuleTest`:** every row of the table; the margin on both sides (119 s and 121 s); a PLAY whose first send landed (count equal, and count higher) is `AlreadyThere`; a PLAY retried after a lost reply resends the STORED count, not `server + 1`; unknown copies never produce a `playCount` for PLAY; POSITION 0 never sends a date.
- [ ] **Tests first — `SyncGatewayTest`:** exact method, path and `userId` query per call; exact JSON bodies (`{"Played":true,"PlayCount":2,"PlaybackPositionTicks":0,"LastPlayedDate":"2026-11-15T14:05:00Z"}`, no null fields); no request ever has `datePlayed` or `api_key`; the token only in `Authorization`; 404 with the item present → `null`; 404 with the item 404 and views 200 → `itemGone` true; a 302 on views → `itemGone` false; 403 passes as `FORBIDDEN`.
- [ ] **Tests first — `SyncGatewayHostileTest`:** a 65 KiB body is refused; `PlayCount` −1 and 10^9 → null; `PlaybackPositionTicks` −5 → 0; a `LastPlayedDate` of `"yesterday"`, year 1700 or year 2300 → null; `Played` as a string → `SERVER_ERROR`; nothing from the body appears in any message or log line (capturing `AppLog` sink).
- [ ] Implement, with `FakeSyncGateway` (programmable copies per (session, item), a call log with the session of each call, a gate to hold calls, forced failures, and "apply then fail" for lost replies).
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.sync.*"` → pass.
- [ ] Commit: `feat(sync): read the server's copy first, and send absolute values so a retry can never count a play twice`.

---

### Task 5: `SyncSender` — sending in order, once, as the right profile

**Files:**
- Create: `data/sync/SyncSender.kt`; the `SyncLanding` interface in `data/sync/SyncModels.kt`.
- Test: `data/sync/SyncSenderTest.kt`, `data/sync/SyncSenderFailureTest.kt`, `data/sync/SyncIsolationTest.kt` (real `ServerApi`, two accounts, MockWebServer).

**Interfaces — Consumes:** `OutboxStore`, `SyncGateway`, `ConflictRule`, `ServerStore.awaitWarmed` and `cachedToken`, `AppError.Kind`. **Consumes (Milestone 4):** nothing.

**Produces:**
```kotlin
/** What this phone does when a row is done; implemented in data/library (Task 7). */
internal interface SyncLanding {
    /** The server has the change: the row goes, uncovered markers clear, lists the server recomputes are refreshed. */
    suspend fun confirmed(s: Session, seq: Long)
    /** The server's newer copy wins, or it refused the item: the row goes, markers clear, the item and lists are refreshed so the server's copy shows. */
    suspend fun overruled(s: Session, seq: Long)
}
internal class SyncSender(
    private val outbox: OutboxStore,
    private val gateway: SyncGateway,
    private val landing: SyncLanding,
    private val hasToken: (Session) -> Boolean,                 // serverStore.cachedToken(serverId, userId) != null
    private val awaitWarmed: suspend () -> Unit,                // serverStore.awaitWarmed
    private val scope: CoroutineScope,                          // the app scope
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Starts a drain for [s] in [scope], or joins the one running. Never throws. */
    fun kick(s: Session): Deferred<DrainResult>
    /** Every profile with rows, one drain each (in parallel, at most 4); the worst result. The worker awaits this. */
    suspend fun drainAll(): DrainResult
    /** Called by the store hooks: ends any wait for that account (or server) and forgets its joined drain. */
    fun forget(serverId: String, userId: String? = null)
}
```
**One drain (per profile; at most one at a time per profile):**
1. `awaitWarmed()`. If `hasToken(s)` is false → `WAITING_FOR_SIGN_IN`, nothing sent.
2. `head(s, now())`; none → `EMPTY`.
3. If `ConflictRule.needsServerCopy(row)` → `gateway.userData(s, row.itemId)`.
4. `ConflictRule.decide`. `AlreadyThere` → `landing.confirmed`. `ServerIsNewer` → `landing.overruled`.
5. Otherwise `outbox.markSending(seq, playCount)` (committed), then the one gateway call, then `landing.confirmed`. Check `hasToken(s)` again before each request.
6. Repeat from 2.

**Failures (`AppError.Kind` of the read or the send):**

| Kind | What happens | Result |
|---|---|---|
| `OFFLINE`, `NETWORK`, `TIMEOUT` | the row stays as it is; the drain stops (order) | `WAITING_FOR_CONNECTION` |
| `SERVER_ERROR` | `deferAfterServerError`; continue with the next head (which is null unless only stuck rows wait) | `BACKING_OFF` when it stops |
| `UNAUTHORISED`, `NOT_SIGNED_IN` | the row stays (`ServerApi` has already invalidated the token); the drain stops | `WAITING_FOR_SIGN_IN` |
| `FORBIDDEN` | `landing.overruled` (the server's answer about this item for this profile) | continue |
| `SERVER_NOT_FOUND` | `gateway.itemGone(s, itemId)`: true → `landing.overruled`; false (a redirect, a proxy) → as `SERVER_ERROR` | continue / defer |
| TLS kinds, `CLIENT_CERT_REQUIRED`, `CLEARTEXT_BLOCKED` | the row stays; the drain stops (the user must fix the connection) | `WAITING_FOR_CONNECTION` |
| anything else, or a local database failure | logged by kind or class; the drain stops | `STOPPED` |

A row that `take` no longer finds (coalesced while in flight, or the account went) is simply passed: a newer row, or nothing, carries it. **No row is ever deleted for failing**: only a confirmation, an overruling answer from the server, coalescing, or account removal ends one (SPEC §3.2: "only cleared item by item once the server confirms").

- [ ] **Tests first — `SyncSenderTest`** (`FakeSyncGateway`, in-memory Room, a recording `SyncLanding` over the real `OutboxStore`):
  - five rows for one profile are sent in seq order, each once; the landing hears each seq once;
  - **a lost reply:** a PLAY applied by the fake and then failing `NETWORK` is retried by the next drain, which reads the copy, sees the stored count, and confirms with NO second update; the fake's play count rose by exactly one;
  - a PLAY whose first read failed before anything was sent computes its count on the next drain from that drain's read;
  - a row replaced while in flight: the old send's confirm finds nothing, the new row is sent next;
  - `kick` twice joins one drain; a profile's drain never runs twice at once (a gate holds the first);
  - two profiles drain in parallel and each only as itself (the fake's call log names the session of every call);
  - nothing is logged with an id, position or date (capturing sink).
- [ ] **Tests first — `SyncSenderFailureTest`:** every row of the failure table, including: `SERVER_ERROR` ten times parks the row and the next item's row goes; `SERVER_NOT_FOUND` with `itemGone` false is deferred, never dropped; `hasToken` false sends nothing at all; `forget` during a held call means nothing more is sent for that account after it returns.
- [ ] **Tests first — `SyncIsolationTest`** (real `ServerApi`, `ServerStore` with two accounts on one server holding different tokens, MockWebServer): interleaved rows of A and B produce requests whose `Authorization` token and `userId` query are always the row's own account; after `removeAccount(B)` not one more request carries B's token, and A's continue.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.sync.*"` → pass.
- [ ] Commit: `feat(sync): send each profile's changes in order, once, and only as that profile`.

---

### Task 6: When sending happens — `SyncScheduler`, `SyncWorker`, reconnection and start-up

**Files:**
- Create: `data/sync/SyncScheduler.kt`, `data/sync/SyncWorker.kt`, `data/sync/OnlineEvents.kt`.
- Modify: `data/downloads/ShoalwatchWorkerFactory.kt` (builds `SyncWorker` too), `ShoalwatchApp.kt` (`graph.syncScheduler.start()`), `AppGraph.kt`.
- Test: `data/sync/SyncSchedulerTest.kt` (Robolectric + `work-testing`), `data/sync/SyncWorkerTest.kt`, `data/sync/OnlineEventsTest.kt`, `data/sync/ClosedAfterTickTest.kt`.

**Interfaces — Consumes:** `SyncSender`, `OutboxStore.serversWithWork` and `accountsWithWork`, `ServerConnections.connection` (`Connection.Online`). **Consumes (Milestone 4):** `ShoalwatchWorkerFactory` (Task 11 there: "builds DownloadWorker only; anything else: null"), WorkManager's on-demand `Configuration.Provider` set-up.

**Produces:**
```kotlin
internal class OnlineEvents(private val connections: ServerConnections, private val servers: Flow<Set<String>>, private val scope: CoroutineScope) {
    /** A server id each time its connection becomes Online after being anything else (Checking, Offline, or a different Online). */
    val cameOnline: SharedFlow<String>
}
class SyncScheduler internal constructor(
    private val workManager: WorkManager,
    private val sender: SyncSender,
    private val outbox: OutboxStore,
    private val online: OnlineEvents,
    private val scope: CoroutineScope,
) {
    /** After every enqueue: kick [s] in process AND enqueueUniqueWork("shoalwatch-sync", REPLACE, CONNECTED, no input data). */
    fun changed(s: Session)
    /** ShoalwatchApp.onCreate: kick every profile with rows, schedule the job if any, and kick a server's profiles each time it comes online. */
    fun start()
    /** The store hook after a sign-in: that profile's rows can go now. */
    fun signedIn(serverId: String, userId: String)
    companion object { const val WORK_NAME = "shoalwatch-sync" }
}
internal class SyncWorker(context: Context, params: WorkerParameters, private val sender: SyncSender) : CoroutineWorker(context, params) {
    /** drainAll: EMPTY, WAITING_FOR_SIGN_IN → success; WAITING_FOR_CONNECTION, BACKING_OFF, STOPPED → retry. */
    override suspend fun doWork(): Result
}
```
**Rules:**
- **The job:** `OneTimeWorkRequestBuilder<SyncWorker>()`, `Constraints(requiredNetworkType = NetworkType.CONNECTED)` (any network: these are small requests, and a home-only address simply waits for home), `BackoffPolicy.EXPONENTIAL` from 30 s, **no input data**, not expedited and not foreground (no notification, no permission).
- **`REPLACE` is safe here:** the worker only awaits `sender.drainAll()`, which runs in the app scope, so replacing the job cancels a wait, never a send. The newest request always covers the whole outbox. (`KEEP` could drop a change enqueued in the instant a finishing job still counts as running; `APPEND_OR_REPLACE` would grow a chain while offline.)
- **A change made just before the app is closed:** the row is committed before the action returns, and the job is enqueued in the same call. If the process dies before the in-process kick sends it, WorkManager runs the job when a network is there, in a new process, and the worker drains it. After a reboot WorkManager's own reschedule receiver (Milestone 4) brings the job back; `start()` kicks anyway.
- **Reconnection:** `start()` collects `online.cameOnline` for the servers with rows and kicks those servers' profiles.
- **Doze and OEM battery rules** can delay the job; the next app start or reconnection sends regardless.

- [ ] **Tests first — `SyncSchedulerTest`:** `changed` enqueues exactly one unique work named `shoalwatch-sync` with a `CONNECTED` constraint, exponential backoff and empty input data; a second `changed` replaces it; `start` with rows kicks each profile and schedules; with none it schedules nothing; a server going Checking → Online kicks only that server's profiles, once.
- [ ] **Tests first — `SyncWorkerTest`** (`TestListenableWorkerBuilder` through `ShoalwatchWorkerFactory` over a fake sender): each `DrainResult` maps as above; the factory still builds `DownloadWorker` and returns null for anything else.
- [ ] **Tests first — `OnlineEventsTest`:** Online → Online (same instance) emits nothing; Offline → Online emits once; Online on another address emits once; a server leaving the set stops being watched.
- [ ] **Tests first — `ClosedAfterTickTest`:** a row enqueued through `changed` while the in-process kick is held at the gateway; the app scope is cancelled (the process "dies"); a NEW sender, store and worker over the same database run the job → the row is sent once.
- [ ] Implement, and wire `AppGraph` (`outboxStore`, `syncGateway`, `syncSender`, `onlineEvents`, `syncScheduler`).
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.sync.*" --tests "app.shoalwatch.data.downloads.DownloadWorkerTest"` → pass.
- [ ] Commit: `feat(sync): changes are sent straight away, on reconnection, at start-up, and by a background job if the app was closed`.

---

### Task 7: Quick ticks are instant — `Playstate` on the outbox, markers and lists kept until sent

**Files:**
- Create: `data/library/PlaystateLanding.kt`.
- Modify: `data/db/downloads/OfflinePlayDao.kt` (Milestone 4's: `deleteCovered`), `data/library/Playstate.kt` (KDoc; `recordStop(..., reached)`; `Recompute.key()` and `parseRecompute(key)`), `PlaystateRepository.kt`, `PlaystateLocal.kt`, `StartupSweep.kt` (KDoc), `data/db/library/ItemDao.kt` (`pendingRows`, `clearPendingFor`), `data/db/library/LibraryStore.kt` (`writePage`), `ui/browse/ItemActions.kt`, `AppGraph.kt`, `app/src/test/.../ui/browse/BrowseFakes.kt` (`FakePlaystate.recordStop`).
- Test: `data/library/PlaystateInstantTest.kt` (new), `PlaystateOutboxTest.kt` (new), `PlaystateLandingTest.kt` (new); existing `PlaystateResumeTest`, `PlaystatePlayedTest`, `PlaystateSeriesTest`, `PlaystateConcurrencyTest`, `PlaystateRefreshRaceTest`, `PlaystateStalenessTest`, `PlaystateStampTest`, `PlaystateCoalesceTest`, `PlaystateSweepTest`, `PlaystateRecordStopTest` (updated, below); `PlaystateRestoreTest` (replaced by `PlaystateLandingTest`'s overruling cases); `ui/browse/ItemActionsTest.kt`.

**Interfaces — Consumes:** `OutboxStore.enqueue/take/hasMark/leftList`, `SyncScheduler.changed`, `SyncLanding`, `LibraryRepository.writeGated` and `refreshInBackground`, `LocalChangeLog`. **Consumes (Milestone 4):** `LocalPlaystate.recordLocalStop` (Task 13 there), `ItemDao.clearPendingFor` sparing rows with an offline play (kept).

**Produces:**
```kotlin
interface Playstate {
    suspend fun setPlayed(s: Session, itemId: String, played: Boolean)
    suspend fun setFavourite(s: Session, itemId: String, favourite: Boolean)
    suspend fun removeFromContinueWatching(s: Session, itemId: String)
    /**
     * [reached]: the stop report reached the server. finished && reached: watched on this phone, nothing queued (the report did it).
     * finished && !reached: watched, and a PLAYED tick queued (never a PLAY: the server may already count it from its own session).
     * !finished: the position on this phone; queued as POSITION only when !reached.
     */
    suspend fun recordStop(s: Session, itemId: String, positionMs: Long, finished: Boolean, reached: Boolean = true)
}
internal fun Recompute.key(): String          // "resume", "nextUp", "seasons:<id>", "episodes:<id>:<id>", "item:<id>"
internal fun parseRecompute(key: String): Recompute?             // null for anything else (ids re-validated with JellyfinId)

class PlaystateRepository internal constructor(
    private val library: LibraryRepository,
    private val outbox: OutboxStore,
    private val changed: (Session) -> Unit,          // SyncScheduler::changed
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    clock: () -> Long,
) : Playstate, LocalPlaystate

internal class PlaystateLanding(
    private val library: LibraryRepository,
    private val outbox: OutboxStore,
    private val offlinePlays: OfflinePlayDao,         // Milestone 4's, + deleteCovered below
    private val clock: () -> Long,
) : SyncLanding
// data/db/downloads/OfflinePlayDao.kt (added)
@Query("DELETE FROM offline_plays WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId AND updatedAt <= :occurredAt")
suspend fun deleteCovered(serverId: String, userId: String, itemId: String, occurredAt: Long): Int
```
**The new action sequence** (replacing steps 3–5 of "Tidy-up actions"):
1. The item must be one this user was shown (else `SERVER_NOT_FOUND`, nothing changed).
2. Under the library write gate, in ONE transaction: the local change exactly as today (`PlaystateLocal`, every row marked `pendingSyncAt`, noted in `LocalChangeLog`) **and** `outbox.enqueue(s, change, marks, refreshKeys)`. The marks are `Touched.markedIds`; the departures are `Touched.removed` (item, list key); the refresh keys are `Touched.recompute` as keys.
3. `changed(s)`, then return. **No server call, no rollback.** The per-user queue stays, but each action now holds it only for its local transaction (milliseconds), so the next tick shows at once.

| Action | Change queued |
|---|---|
| `setPlayed` (any kind) | `PLAYED(played)` for the item itself (a season or series too: the server marks its children) |
| `setFavourite` | `FAVOURITE(favourite)` |
| `removeFromContinueWatching` | `POSITION(0)` |
| `recordStop` | as its KDoc |
| `recordLocalStop` (Milestone 4) | finished: `PLAY` at now, `fromOfflinePlay = true`; else `POSITION(p)`, `fromOfflinePlay = true` |

**`PlaystateLanding`** (under the write gate, one transaction): `outbox.take(seq)`; for every `unmarked` item, clear its marker and note it in `LocalChangeLog` (so a refresh asked for before this moment cannot overwrite it; as step 4 today); for a `fromOfflinePlay` row, `offlinePlays.deleteCovered(s, itemId, occurredAt)`; `outbox.touchSynced(s)`. Then `refreshInBackground` the row's keys, plus `Recompute.Item(itemId)` when overruled.

**Markers now mean "send":**
- `ItemDao.pendingRows` (the start-up sweep's snapshot) and `ItemDao.clearPendingFor` (the idle clean-up) leave out every row with a `sync_marks` mark for that (server, user, item): one `NOT EXISTS` clause each. Milestone 4's offline-play clause stays.
- A marker with no mark is still a leftover of a killed process from before this milestone (or a database fault), and is cleared as before.
- **Lists:** `LibraryStore.writePage` leaves out of list L every item in `outbox.leftList(s, L)`, in the same transaction, so a refresh after a restart cannot put an unsent "off Continue Watching" back (the durable form of `LocalChangeLog`'s list memory, which the ARCHITECTURE note asked for).
- **Removed as orphaned:** `PlaystateRepository.rollBack`, `clearOneByOne`, the server-call step, and `PlaystateLocal.restore` and `confirm`.

**Screens:** an action now returns once saved on this phone; offline it succeeds. `ItemActions` keeps showing an `AppError` only for what can still fail (`SERVER_NOT_FOUND`, a local failure).

- [ ] **Tests first — `PlaystateInstantTest`** (the to-do item): with the sync gateway held, 20 ticks on 20 episodes of one season each return in virtual time 0 after their local write, and Room shows all 20 watched and both badges moved by 20; released, the gateway receives 20 sends in call order; every marker is clear afterwards.
- [ ] **Tests first — `PlaystateOutboxTest`:**
  - each action queues exactly the table's change, with its marks, departures and refresh keys, in the same transaction (a forced failure after the local write leaves neither);
  - offline (`OFFLINE` from the gateway) keeps the change on screen and the row in the outbox; nothing is rolled back;
  - watched then unwatched quickly leaves one row (unwatched);
  - a season tick marks its episodes and is ONE row; an episode tick after it is a second row;
  - after a simulated restart (a new repository and sweep over the same database), the sweep clears no marked row, and a `resume` refresh answered by the server WITH the item still leaves it out of Continue Watching until its row is confirmed.
- [ ] **Tests first — `PlaystateLandingTest`:** confirming clears only uncovered markers and refreshes the row's lists; overruling also refreshes the item, so the server's copy replaces the local one (the old `PlaystateRestoreTest` intent: a refused change does not stay on screen); a 403 from the server for one item leaves other items' rows alone.
- [ ] **Update the existing tests:** expectations of "rolled back and thrown" on a failed send become "kept and queued"; order, shared badges, staleness, stamps and coalesced refreshes keep their assertions (a refresh now follows the confirmation, not the action). `PlaystateRecordStopTest` gains the `reached` cases. `ItemActionsTest`: an offline tick is no longer an error.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.library.*" --tests "app.shoalwatch.data.sync.*" --tests "app.shoalwatch.ui.browse.*" --tests "app.shoalwatch.SourceShapeTest"` → pass.
- [ ] Commit: `feat(sync): every tick shows at once and is sent in order behind the scenes, even offline`.

---

### Task 8: The player's stops and offline plays go through the outbox

**Files:**
- Modify: `data/playback/ServerPlaybackReports.kt` (the in-memory pending stop goes), `data/playback/LocalPlaybackReports.kt` (Milestone 4's), `data/sync/OfflinePlays.kt` (`recoverUnqueued`), `data/db/downloads/OfflinePlayDao.kt` (`unqueued`), `ShoalwatchApp.kt` (recovery at start, before `syncScheduler.start()`), `AppGraph.kt`.
- Create: `data/downloads/LocalPlaying.kt`.
- Test: `data/playback/ServerPlaybackReportsTest.kt` (updated), `data/playback/LocalPlaybackReportsTest.kt` (new cases), `data/sync/OfflinePlaysTest.kt` (new cases), `data/sync/OfflineRecoveryTest.kt`.

**Interfaces — Consumes:** `Playstate.recordStop(..., reached)`, `OutboxStore`, `SyncScheduler.changed`. **Consumes (Milestone 4):** `LocalPlaybackReports(plays, playstate, now)`, `RoutedPlaybackReports`, `OfflinePlays.position/stopped`, `OfflinePlayDao`, `LocalPlaystate.recordLocalStop`, `DownloadKey`.

**Produces:**
```kotlin
// data/downloads/LocalPlaying.kt
/** Which saved copies the player has open right now (memory only), so a released download's files go only once it lets go. */
internal class LocalPlaying {
    fun opened(key: DownloadKey)
    /** Runs every action waiting for [key] once its last holder closes. */
    fun closed(key: DownloadKey)
    fun isOpen(key: DownloadKey): Boolean
    /** Runs [action] at once if [key] is not open, else when it closes. */
    fun whenClosed(key: DownloadKey, action: () -> Unit)
}
// data/playback/LocalPlaybackReports.kt (constructor gains two parameters)
class LocalPlaybackReports internal constructor(
    private val plays: OfflinePlays, private val playstate: LocalPlaystate, private val now: () -> Long,
    private val playing: LocalPlaying,
    private val onFinished: (Session, String) -> Unit,      // { _, _ -> } here; HousekeepingRepository::onLocalFinished from Task 11
) : PlaybackReports
// data/sync/OfflinePlays.kt (added)
/** At start, before anything plays: an offline_plays row no outbox row covers is queued in an idempotent form (played → PLAYED true; else POSITION). */
suspend fun recoverUnqueued(): Int
// data/db/downloads/OfflinePlayDao.kt (added)
@Query("SELECT * FROM offline_plays p WHERE NOT EXISTS (SELECT 1 FROM sync_outbox o WHERE o.serverId = p.serverId AND o.userId = p.userId AND o.itemId = p.itemId AND o.fromOfflinePlay = 1)")
suspend fun unqueued(): List<OfflinePlayEntity>
```
**Rules:**
- **`ServerPlaybackReports.stopped`:** a stop that reached the server is `recordStop(..., reached = true)` as today. One that was not sent because the server could not be reached (`OFFLINE`, `NETWORK`, `TIMEOUT`, `SERVER_ERROR`) is `recordStop(..., reached = false)`: finished → watched on the phone at once and a PLAYED tick queued; otherwise the position queued as POSITION with its time. The in-memory pending stop, its resend without `PlaySessionId`, `keep` and `resendWhenBack` are deleted (orphaned). The forget counting stays (a stop overlapping a forget of its own account is still not recorded). `NOT_SIGNED_IN` still records nothing.
  - This closes Milestone 3's carried **M-3** (watched and the position are kept when unreachable) and **M-2** (an old stop cannot overwrite a newer position: POSITION rows coalesce per item, and the conflict rule sends a position only if no newer change is on the server), and the Milestone 3 check's **I4** (the pending stop no longer lives in memory).
- **`LocalPlaybackReports`:** `started` → `playing.opened(key)`; `stopped` → Milestone 4's writes (`offline_plays` and `recordLocalStop`, which now queues PLAY or POSITION), then `playing.closed(key)`, then, if finished, `onFinished(s, itemId)`.
- **Recovery** covers a process that died mid-play (positions were written to `offline_plays` every 10 s but no stop came) and the instant between Milestone 4's two writes. It never queues a PLAY, so it can never add a play count.
- **`offline_plays` rows are deleted once a confirmed row covers them** (`deleteCovered`, Task 7's landing). Milestone 4's "kept when the download is deleted" still holds until then; "`playedAt` never cleared" holds for as long as the row exists. Milestone 4's `pending(s)` count is superseded by `Sync.status` (Task 9) and is removed if nothing else uses it.

- [ ] **Tests first — `ServerPlaybackReportsTest`:** an unsent unfinished stop queues one POSITION with the report's position and time; an unsent finished stop queues PLAYED true and the item shows watched at once; a sent stop queues nothing; a later unsent stop for the same item replaces the earlier row; `NOT_SIGNED_IN` queues nothing; there is no resend when the connection returns (the outbox does it).
- [ ] **Tests first — `LocalPlaybackReportsTest`:** a finished LOCAL stop queues exactly one PLAY at the stop's time and calls `onFinished` once; an unfinished one queues POSITION and does not call it; `opened`/`closed` pair up; a second profile's play never touches the first's rows.
- [ ] **Tests first — `OfflineRecoveryTest`:** a row with positions and no stop is queued as POSITION; a played row with no PLAY is queued as PLAYED true; a row covered by a queued row is left; running recovery twice queues nothing new; after confirmation the covered row is gone.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.playback.*" --tests "app.shoalwatch.data.sync.*" --tests "app.shoalwatch.data.library.*"` → pass (every Milestone 3 player test unchanged apart from the pending-stop cases).
- [ ] Commit: `feat(sync): a stop the server missed and every offline watch are kept and sent with their original times`.

---

### Task 9: The `Sync` seam, and "waiting to sync" on screen

**Files:**
- Create: `data/sync/Sync.kt` (`Sync`, `SyncRepository`), `ui/present/SyncText.kt`, `ui/downloads/SyncStatusLine.kt`.
- Modify: `data/servers/ServerStore.kt` (`usableAccounts`, one line), `ui/downloads/DownloadsViewModel.kt` + `DownloadsScreen.kt` (Milestone 4's: the line under the top bar), `ui/servers/ServersViewModel.kt` + `ServersScreen.kt` (the line under each profile), `AppGraph.kt`, `BrowseFakes.kt` (`FakeSync`), `res/values/strings.xml`.
- Test: `data/sync/SyncRepositoryTest.kt`, `ui/present/SyncTextTest.kt`, `ui/downloads/DownloadsRenderTest.kt` (new cases), `ui/ManagementRenderTest.kt` (new cases).

**Interfaces — Consumes:** `OutboxStore.status/waiting`, `SyncSender.kick`. **Consumes (Milestone 4):** `DownloadsUiState`, `DownloadsViewModel(catalog, connections, downloads)`.

**Produces:**
```kotlin
/** What screens use. Every read is for one Session. Flows never throw. */
interface Sync {
    fun status(s: Session): Flow<SyncStatus>
    suspend fun waiting(s: Session): Int
    /** Sends what it can for [s] within [timeoutMs]; returns how many are still waiting. Never throws. */
    suspend fun sendNow(s: Session, timeoutMs: Long = 10_000): Int
}
class SyncRepository internal constructor(private val outbox: OutboxStore, private val sender: SyncSender,
                                          private val signedIn: StateFlow<Set<Pair<String, String>>>) : Sync   // ServerStore.usableAccounts
// data/servers/ServerStore.kt (added; the private `usable` flow, read-only)
/** Accounts whose token is loaded now (what can send). Not secret. */
val usableAccounts: StateFlow<Set<Pair<String, String>>>
// ui/present/SyncText.kt (pure)
data class SyncWords(val main: String, val detail: String?)
object SyncText { fun words(status: SyncStatus, nowMs: Long, zone: ZoneId, res: SyncStrings): SyncWords }
// ui/downloads/SyncStatusLine.kt
@Composable fun SyncStatusLine(status: SyncStatus, nowMs: Long, modifier: Modifier = Modifier)
// DownloadsUiState gains: val sync: SyncStatus = SyncStatus.NONE ; DownloadsViewModel gains a `sync: Sync` constructor parameter
```
**Exact words** (`SyncText`; times `HH:mm`, dates `d MMMM`, `Locale.UK`, digits 0–9 always):
- Nothing waiting, synced before: "All your changes are on your server." with detail "Last synced at 14:05" (today) or "Last synced on 12 November".
- Nothing waiting, never synced: nothing is shown.
- Waiting: plurals `sync_waiting` — one "%d change waiting to sync", other "%d changes waiting to sync"; detail "They're sent when your server can be reached." (plus the "Last synced" text on a second detail line when known).
- Needs sign-in: plurals `sync_sign_in` — one "Sign in again to send %d change.", other "Sign in again to send %d changes."
- Stuck: plurals `sync_stuck` — one "%d change your server keeps refusing. Shoalwatch will try again later.", other "%d changes your server keeps refusing. Shoalwatch will try again later."

The line is plain text (13sp minimum, wraps at font scale 2.0), read by TalkBack as one element, and a polite live region only when the count changes.

- [ ] **Tests first — `SyncRepositoryTest`:** status per profile; another profile's rows never counted; `sendNow` returns the remaining count and gives up at the timeout (virtual time) without cancelling the drain; a failing read emits `NONE`.
- [ ] **Tests first — `SyncTextTest`:** every case above, singular and plural; today vs another day across midnight in `Europe/London` (including a clock change day); Arabic and Hindi locales still give digits 0–9.
- [ ] **Tests first — render tests:** Downloads shows the line under the top bar and Servers under each profile, at both sizes and font scales; nothing shows when there is nothing to say; text never below 13sp.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.sync.*" --tests "app.shoalwatch.ui.*"` → pass.
- [ ] Commit: `feat(sync): see how many changes are waiting to sync and when the last sync worked`.

---

### Task 10: Sign-out, removal and expired sign-ins — the outbox rules for accounts

**Files:**
- Modify: `data/servers/ServerStore.kt` (rejection persisted), `data/db/AccountDao.kt`, `AppGraph.kt` (store hooks: `syncSender.forget`, `syncScheduler.signedIn`), `ui/servers/ServersViewModel.kt`, `ui/servers/ServersScreen.kt`, `res/values/strings.xml`.
- Test: `data/sync/AccountRulesTest.kt` (real `ServerStore`), `data/RejectedSignInTest.kt`, `ui/servers/ServersSyncTest.kt` (ViewModel), `ui/ManagementRenderTest.kt` (dialog lines).

**Interfaces — Consumes:** `ServerStore.removeAccount`, `removeServer`, `invalidateToken`, `upsertAccount`, `warmTokens`; `Sync.waiting` and `sendNow` (Task 9's seam); `Authenticator.signOut`, `removeServer`. **Consumes (Milestone 4):** the Servers dialog lines added by Milestone 4 Task 15.

**Produces:**
```kotlin
// data/db/AccountDao.kt (added)
@Query("SELECT a.* FROM accounts a WHERE NOT EXISTS (SELECT 1 FROM rejected_sign_ins r WHERE r.serverId = a.serverId AND r.userId = a.userId)")
suspend fun getAllUsable(): List<AccountEntity>
@Query("INSERT OR IGNORE INTO rejected_sign_ins (serverId, userId, rejectedAt) VALUES (:serverId, :userId, :at)")
suspend fun reject(serverId: String, userId: String, at: Long)
@Query("DELETE FROM rejected_sign_ins WHERE serverId = :serverId AND userId = :userId")
suspend fun clearRejected(serverId: String, userId: String)
// data/servers/ServerStore.kt: invalidateToken also calls accountDao.reject; upsertAccount calls clearRejected; warmTokens reads getAllUsable
```
**The rules (each tested through the real `ServerStore`):**

| Event | The profile's outbox |
|---|---|
| **Sign-out** (the account is removed here) | the Servers screen first calls `sync.sendNow(session, 10_000)` (through the verified address, as that profile), then signs out; whatever is still waiting is **deleted with the account** (FK cascade), with `syncSender.forget` from `onAccountGone` |
| **Remove server** | `sendNow` for each of its profiles in parallel within 10 s in all, then removal; the rest cascade |
| **Sign-in rejected (401)** | **kept**; nothing is sent (no token); `invalidateToken` also writes `rejected_sign_ins`, so after a restart `warmTokens` (now `getAllUsable`) does not load the dead token and no request carries it (Milestone 2 check **I4**) |
| **Signed in again** | `upsertAccount` clears `rejected_sign_ins`; `onAccountSignedIn` → `syncScheduler.signedIn` → the waiting rows go |
| **Another profile's events** | nothing of this profile changes |

The dialogs say what will happen:
- Sign out, with changes waiting: plurals `sign_out_waiting` — one: "%d change hasn't reached your server yet. Shoalwatch will try to send it first. If it can't, the change is deleted from this phone." other: "%d changes haven't reached your server yet. Shoalwatch will try to send them first. Any it can't send are deleted from this phone."
- Remove server, with changes waiting: plurals `remove_server_waiting` — one: "%d change hasn't reached this server yet. Shoalwatch will try to send it first. If it can't, the change is deleted from this phone." other: "%d changes haven't reached this server yet. Shoalwatch will try to send them first. Any it can't send are deleted from this phone."
- While sending before sign-out: the button shows a spinner with the label "Sending changes…" (at most 10 s).

- [ ] **Tests first — `AccountRulesTest`:** sign-out deletes exactly that profile's rows, marks, sync state and tombstones; the other profile's rows stay and keep sending; a 401 keeps the rows and sends nothing, and signing in again sends them in order; removing the server deletes every profile's rows; a drain in flight when the account is removed sends nothing more (the gate is held, `removeAccount` runs, the gate is released: zero further requests).
- [ ] **Tests first — `RejectedSignInTest`:** after `invalidateToken`, a new `ServerStore` over the same database (a restart) loads no token for that account and `ServerApi` sends zero requests for it; `upsertAccount` brings it back; `removeAccount` cascades the record. `ServerStore.kt` stays at or under 396 lines.
- [ ] **Tests first — `ServersSyncTest`:** sign-out calls `sendNow` with the profile's own session before `signOut`, and proceeds after 10 s (virtual time) even if the send never returns; the dialog shows the count only when it is above 0.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.*" --tests "app.shoalwatch.ui.servers.*" --tests "app.shoalwatch.ui.ManagementRenderTest"` → pass.
- [ ] Commit: `feat(sync): signing out sends what it can first, an expired sign-in keeps its changes, and a rejected token stays rejected after a restart`.

---

### Task 11: Watch-and-delete and shared downloads — the release rules

**Files:**
- Create: `data/db/downloads/ReleaseStore.kt`, `data/db/downloads/HousekeepingDao.kt`, `data/downloads/Housekeeping.kt` (seam), `data/downloads/HousekeepingRepository.kt`, `data/downloads/ReleasedFiles.kt`.
- Modify: `data/db/downloads/DownloadDao.kt` (`recomputePins` also counts `released_episodes`; `recomputePinned(serverId)`), `DownloadStore.kt` (`add` removes the item's tombstones; deleting the last download of a series removes the series' tombstones; `afterAccountGone` ends with `recomputePinned`), `OfflineLibraryDao.kt` (`observeReleased`), `data/downloads/DownloadModels.kt`, `data/downloads/DownloadRepository.kt` (Milestone 4's: fills `OfflineSeason.released`), `AppGraph.kt`, `BrowseFakes.kt` (`FakeHousekeeping`).
- Test: `data/downloads/ReleaseRulesTest.kt`, `data/downloads/ReleaseSafeguardingTest.kt` (each clause with a mutation check), `data/downloads/ReleasedFilesTest.kt`, `data/downloads/TombstoneTest.kt`, `data/db/DownloadPinningTest.kt` (new cases).

**Interfaces — Consumes:** `Playstate.setPlayed`, `ServerStore.cachedToken` (who is signed in), `LocalPlaying`. **Consumes (Milestone 4):** `DownloadStore` (`LibraryWriteLock`, `removeOwner`, `owners`, `sharing`), `DownloadDao.accountsShown`, `OfflineLibraryDao.getPlayable` and its `MINE_OR_SHARED`/`VISIBLE_JOIN` clauses, `DownloadPaths.itemFolder` and `deleteInside`, `DownloadKey`, `RemoveOutcome`, `Downloads.remove`, `DownloadHousekeeping.onStart`'s orphan sweep and unpinned-artwork clean-up.

**Produces:**
```kotlin
// data/downloads/DownloadModels.kt (added)
enum class ReleaseOutcome { DELETED, KEPT_FOR_OTHERS, KEPT_UNTIL_EVERYONE_WATCHED, NOT_YOURS }
/** What the prompt may offer. [othersWhoShare]: signed-in profiles that also see this shared download (a count; never names). */
data class ReleaseChoice(val canRelease: Boolean, val shared: Boolean, val rule: SharedDelete, val othersWhoShare: Int, val film: Boolean)
data class ProfileSettings(val afterFinish: AfterFinish = AfterFinish.ASK, val autoNextCount: Int = 0)
// OfflineSeason gains: val released: List<ItemWithUserData> = emptyList()   (this profile's watched-and-deleted episodes, episode order)

// data/downloads/Housekeeping.kt
/** What screens use for watch-and-delete, per-profile settings and storage. Every call is for one Session. Flows never throw. */
interface Housekeeping {
    fun profileSettings(s: Session): Flow<ProfileSettings>
    suspend fun setAfterFinish(s: Session, value: AfterFinish)
    suspend fun setAutoNext(s: Session, count: Int)                 // 0, 1, 2, 3 or 5; anything else IllegalArgumentException
    fun sharedDelete(serverId: String): Flow<SharedDelete>
    suspend fun setSharedDelete(s: Session, value: SharedDelete)    // any signed-in profile of that server, as sharing itself
    suspend fun releaseChoice(s: Session, itemId: String): ReleaseChoice
    /** Marks it watched for [s] (and, with [together], for every other profile counted in ReleaseChoice), then applies the rule. */
    suspend fun release(s: Session, itemId: String, together: Boolean): ReleaseOutcome
    fun storage(s: Session): Flow<StorageView>                      // Task 14
    suspend fun watchedSummary(s: Session): WatchedSummary          // Task 14
    suspend fun deleteWatched(s: Session): Int                      // Task 14
    suspend fun deleteSeries(s: Session, seriesId: String): Int     // Task 14
}
class HousekeepingRepository internal constructor(
    private val releases: ReleaseStore,
    private val dao: HousekeepingDao,
    private val playstate: Playstate,
    private val downloads: Downloads,
    private val volumes: Volumes,
    private val files: ReleasedFiles,
    private val onWatched: (Session, String) -> Unit,               // AutoDownloadNext::onWatched (Task 13); { _, _ -> } until then
    private val hasToken: (Session) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Housekeeping {
    /** LocalPlaybackReports' onFinished: ALWAYS → release without a second watched mark; then auto-download next. */
    internal fun onLocalFinished(s: Session, itemId: String)
}
// data/db/downloads/ReleaseStore.kt
internal class ReleaseStore(private val db: AppDatabase, private val now: () -> Long, private val lock: LibraryWriteLock) {
    sealed interface Result {
        data class Deleted(val key: DownloadKey, val storage: StorageKind, val volumeId: String?) : Result
        data object KeptForOthers : Result
        data object KeptUntilEveryoneWatched : Result
        data object NotYours : Result
    }
    /** One transaction under the lock. [s] must already be marked watched on this phone. */
    suspend fun release(s: Session, itemId: String): Result
    suspend fun choice(s: Session, itemId: String, signedIn: (String) -> Boolean): ReleaseChoice
    /** Other profiles (user ids) that share it, are signed in and were shown it: whom "Watched together" marks. */
    suspend fun sharers(s: Session, itemId: String, signedIn: (String) -> Boolean): List<String>
}
// data/downloads/ReleasedFiles.kt
internal class ReleasedFiles(private val paths: DownloadPaths, private val playing: LocalPlaying, private val scope: CoroutineScope,
                             private val io: CoroutineDispatcher = Dispatchers.IO) {
    /** Deletes the item's folder now, or once the player lets go of it. A process that dies first leaves it to the next start's orphan sweep. */
    fun delete(key: DownloadKey, storage: StorageKind, volumeId: String?)
}
```
**The release rule (`ReleaseStore.release`, one transaction):**
1. **Who may:** `OfflineLibraryDao.getPlayable(serverId, userId, itemId)` must return the row: COMPLETE, the profile's own `visible_items` record, and owned by it or shared. Otherwise `NotYours`, nothing changes. **A profile can never release a download it cannot see**, so a child cannot remove an adult's download of a title outside its server permissions, or learn that one exists (the answer is the same as for no download at all).
2. **Sharing off:** only this profile's claim goes (`removeOwner`). Other owners left → `KeptForOthers` (each owner downloaded it for themselves and has not agreed to share). No owner left → the row goes → `Deleted`.
3. **Sharing on, "After anyone has watched" (the default, SPEC §3.3):** the row goes for everyone → `Deleted`.
4. **Sharing on, "After everyone has watched":** "everyone" is every account on this server on this phone that owns it or was shown it (`owners ∪ accountsShown`). If every one of them has `user_data.played` for the item on this phone → as 3 (`Deleted`); otherwise nothing changes → `KeptUntilEveryoneWatched`. (A later release by anyone, or "Watched together", completes it.)
5. **On `Deleted`:** the row, owners, tracks and segments go (cascade); for an episode a `released_episodes` tombstone is written for this profile and for every owner, so their offline series still lists it ("Watched and deleted") and its episode, season and series stay pinned with their artwork; pins are recomputed; `ReleasedFiles.delete` follows outside the transaction. A film writes no tombstone (there is nothing to browse under it).

**`release(s, itemId, together)`** (the seam):
1. `playstate.setPlayed(s, itemId, true)`.
2. With `together`, `setPlayed(other, itemId, true)` for each of `sharers(...)`. Each goes into that profile's OWN outbox and is sent with its own token; one that fails `SERVER_NOT_FOUND` is skipped.
3. `releases.release(s, itemId)`.
4. `onWatched(s, itemId)` (auto-download next).

`together` with sharing off, or no sharers, is the same as without.

**Tombstones:** a re-download of the item (`DownloadStore.add`) removes its tombstones; deleting the last download of a series removes every tombstone of that series (the show then leaves Downloads, and normal eviction applies); account removal cascades them, then `recomputePinned(serverId)`. **The pin rule becomes:** an item is pinned while any download on its server is that item, or in that season or series, **or any `released_episodes` row is**.

**Outside the player, a tick never deletes anything** (SPEC §3.1's "explicit mark watched" is read as the player's own finish; see Decisions). Storage's "Delete watched" is the explicit way (Task 14).

- [ ] **Tests first — `ReleaseRulesTest`** (in-memory Room, real `DownloadStore`, `TemporaryFolder`):
  - sharing off, only owner → DELETED, folder gone, tombstone written, pins kept for episode, season and series;
  - sharing off, two owners → KEPT_FOR_OTHERS, the other owner still plays it, this profile's library shows it released;
  - sharing on + ANYONE, a non-owner releases → DELETED for everyone, with tombstones for each owner and the releaser;
  - sharing on + EVERYONE, one of two has watched → KEPT_UNTIL_EVERYONE_WATCHED; the second releases → DELETED;
  - "Watched together" with EVERYONE → DELETED in one press, and the other profile's outbox holds its own PLAYED row (its session, not the releaser's);
  - a film → DELETED with no tombstone;
  - re-downloading a released episode removes its tombstone; deleting the series' last download removes the series' tombstones and unpins.
- [ ] **Tests first — `ReleaseSafeguardingTest`** (each case also run with its guarding clause taken out, to show that clause is what keeps it safe, as `OfflineLibraryClausesTest` does):
  - (a) a profile with no `visible_items` record (the child) gets NOT_YOURS, nothing changes, and `releaseChoice` says `canRelease = false` with `othersWhoShare = 0`, exactly as for an item never downloaded;
  - (b) sharing off and not an owner: NOT_YOURS;
  - (c) sharing on but the download not COMPLETE: NOT_YOURS;
  - (d) "Watched together" never marks a profile that has no `visible_items` record, or is signed out (no token), or is on another server;
  - (e) another server's download with the same item id is untouched.
- [ ] **Tests first — `ReleasedFilesTest`:** a key that is open is deleted only on `closed`; one that is not open is deleted at once; two holders need two closes; a folder outside the root is never touched (`DownloadPaths` refuses it).
- [ ] **Tests first — `TombstoneTest` / `DownloadPinningTest`:** the pin rule with tombstones; `OfflineSeason.released` lists only this profile's tombstones that pass the same series and season visibility clauses as the episodes; account removal leaves no stale pin.
- [ ] Implement, and wire `AppGraph` (`releaseStore`, `localPlaying`, `releasedFiles`, `housekeeping`) and `LocalPlaybackReports(..., playing = localPlaying, onFinished = housekeeping::onLocalFinished)`.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.downloads.*" --tests "app.shoalwatch.data.db.*"` → pass.
- [ ] Commit: `feat(downloads): watch-and-delete frees the space by one tested rule for shared downloads, and the show still browses offline`.

---

### Task 12: The prompt in the player, and the watch-and-delete settings

**Files:**
- Create: `ui/player/WatchedPrompt.kt` (composable), `ui/player/WatchedPromptLogic.kt`.
- Modify: `data/playback/PlaybackState.kt` (`finished`, set in `stateOf`), `ui/player/PlayerUiState.kt`, `PlayerViewModel.kt` (a `housekeeping: Housekeeping` parameter; the logic is in `WatchedPromptLogic`), `PlayerScreen.kt`, `ui/nav/BrowseRoutes.kt` (`PlayerRoute` passes `graph.housekeeping`), `ui/downloads/DownloadSettingsScreen.kt` + `DownloadSettingsViewModel.kt` (Milestone 4's), `res/values/strings.xml`.
- Test: `data/playback/PlaybackControllerTest.kt` (new case), `ui/player/WatchedPromptLogicTest.kt`, `ui/player/PlayerScreenRenderTest.kt` (new cases), `ui/player/PlayerViewModelTest.kt` (new cases), `ui/downloads/DownloadSettingsRenderTest.kt` (new cases).

**Interfaces — Consumes:** `Housekeeping.profileSettings/releaseChoice/release/setAfterFinish/sharedDelete/setSharedDelete`, `PlaybackState.method == PlayMethod.LOCAL`. **Consumes (Milestone 4):** `PlayMethod.LOCAL`, `DownloadSettingsViewModel`/`DownloadSettingsScreen` (Task 15 there), `Downloads.sharing(serverId)`.

**Produces:**
```kotlin
// data/playback/PlaybackState.kt
data class PlaybackState(/* ...unchanged..., */ val closed: Boolean = false, val finished: Boolean = false)   // stateOf: finished = c?.finished == true
// ui/player/PlayerUiState.kt
data class WatchedPromptUi(val session: Session, val itemId: String, val film: Boolean, val together: Int, val always: Boolean) {
    override fun toString() = "WatchedPromptUi(<redacted>)"
}
// PlayerUiState gains: val watched: WatchedPromptUi? = null
enum class WatchedMessage { DELETED, KEPT_FOR_OTHERS, KEPT_UNTIL_EVERYONE, FAILED }
internal class WatchedPromptLogic(private val housekeeping: Housekeeping, private val scope: CoroutineScope) {
    val prompt: StateFlow<WatchedPromptUi?>
    val messages: Flow<WatchedMessage>
    fun onState(state: PlaybackState)                // decides when to offer; once per (session, item)
    fun delete(); fun together(); fun keep()
}
// PlayerViewModel gains: fun watchedDelete(); fun watchedTogether(); fun keepDownload()
```
**When it shows (`WatchedPromptLogic.onState`):**
- The plan is `LOCAL`, `finished` has just become true (playback crossed the credits or 90%, never a seek: the controller's existing rule), and the item has not been offered before in this player.
- Then `profileSettings(session)` and `releaseChoice(session, itemId)` are read:
  - **ASK** with `canRelease` → the card;
  - **ALWAYS** → the card with the information line only (the release itself happens at the stop, in the data layer: Task 11's `onLocalFinished`);
  - **NEVER**, or `canRelease` false → nothing.
- Gone when the item changes, the player closes, the session changes (the prompt carries its own session and acts only as it), or the user presses Keep download.
- Hidden while the screen is locked, shown again on unlock if still offered.

**It never blocks playback:** it is a card stacked above the Up next card at the bottom end, with its own focus order; the countdown keeps running; ignoring it leaves the download in place.

**Exact words:**
- Card text: "You've finished this episode." / "You've finished this film."
- Buttons (each at least 48dp, own TalkBack label):
  - "Watched — delete download";
  - "Watched together", only when `together > 0`, with the label "Watched together: also mark it watched for the other profiles that share it";
  - "Keep download".
- ALWAYS: "This download is deleted when it closes, as set in Download settings." (no buttons).
- Snackbars:
  - DELETED "Marked as watched. The download is deleted when this closes.";
  - KEPT_FOR_OTHERS "Marked as watched. It stays on this phone for another profile.";
  - KEPT_UNTIL_EVERYONE "Marked as watched. It stays on this phone until everyone who shares it has watched it.";
  - FAILED "Couldn't delete this download. You can delete it in Storage."
- The card is a polite live region announced once: its text, then "Watched — delete download, button".

**Download settings (added):**
- "When a download finishes playing" (radio): "Ask each time" (default) / "Always delete when finished" / "Never". Help line: "Deleting frees the space. The show still appears in Downloads and the episode stays watched. For this profile only."
- "Delete shared downloads" (radio, only while "Share downloads between profiles" is on for the active server): "After anyone has watched" (default) / "After everyone has watched". Help line: "For downloads shared between profiles on %1$s."

- [ ] **Tests first — `PlaybackControllerTest`:** `state.finished` becomes true when playback crosses the mark and stays false after a seek past it; `PlaybackController.kt` is byte-for-byte unchanged (the test reads its line count: 400 after Milestone 4).
- [ ] **Tests first — `WatchedPromptLogicTest`** (`FakeHousekeeping`, virtual time):
  - each setting;
  - a stream (not LOCAL) never offers;
  - a seek past the mark never offers;
  - offered once per item, again for the next episode;
  - a session change mid-prompt drops it, and a late press acts as nobody;
  - `delete` calls `release(promptSession, itemId, together = false)` and `together` with true;
  - each outcome gives its message;
  - a failure gives FAILED.
- [ ] **Tests first — render tests** (640x360 and 1200x840, font scales 1.0 and 2.0): the card and the Up next card never overlap and both stay fully on screen; every button is at least 48dp with its own label; "Watched together" is absent when the count is 0; the card is hidden while locked; text is at least 13sp.
- [ ] **Tests first — `DownloadSettingsRenderTest`:** the two new groups; "Delete shared downloads" only while sharing is on; every radio says selected or not.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.ui.player.*" --tests "app.shoalwatch.ui.downloads.*" --tests "app.shoalwatch.data.playback.*" --tests "app.shoalwatch.SourceShapeTest"` → pass.
- [ ] Commit: `feat(player): one press at the credits marks a download watched and deletes it, without stopping the next episode`.

---

### Task 13: Auto-download next

**Files:**
- Create: `data/downloads/AutoDownloadNext.kt`.
- Modify: `data/downloads/DownloadModels.kt` (`DownloadTarget.NextUnwatched`), `DownloadPlanner.kt` (expand it), `data/db/downloads/HousekeepingDao.kt` (requests), `ui/downloads/DownloadSettingsScreen.kt` + `ViewModel` (the setting), `ShoalwatchApp.kt` (`autoDownloadNext.start()`), `AppGraph.kt` (passes `autoDownloadNext::onWatched` to `HousekeepingRepository`), `BrowseFakes.kt` (`FakeDownloads` accepts the new target), `res/values/strings.xml`.
- Test: `data/downloads/AutoDownloadNextTest.kt`, `data/downloads/DownloadPlannerTest.kt` (new cases), `ui/downloads/DownloadSettingsRenderTest.kt` (new cases).

**Interfaces — Consumes:** `OnlineEvents.cameOnline`, `HousekeepingDao`, `ProfileSettings.autoNextCount`. **Consumes (Milestone 4):** `Downloads.prepare(s, target, quality)` and `start(s, plan)`, `DownloadPlan.enoughSpace`/`items`, `DownloadPrefs.state` (`mobileData`, `defaultQuality`), `DownloadPlanner.numbered` (numbered seasons, Specials left out), `DownloadDao.get` (the anchor's quality), the queue's own Wi-Fi constraint.

**Produces:**
```kotlin
// DownloadTarget (added)
/** The first [count] unwatched episodes after [afterItemId] in the app's episode order (numbered seasons; Specials never). */
data class NextUnwatched(val seriesId: String, val afterItemId: String, val count: Int) : DownloadTarget
// data/downloads/AutoDownloadNext.kt
internal class AutoDownloadNext(
    private val dao: HousekeepingDao,
    private val downloads: Downloads,
    private val prefs: DownloadPrefs,
    private val metered: () -> Boolean,                 // ConnectivityManager.isActiveNetworkMetered, as QualityDefault; failure counts as metered
    private val online: OnlineEvents,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** [itemId] (an episode this profile had downloaded) was finished or released: remember the series for [s], then try. */
    fun onWatched(s: Session, itemId: String)
    /** For every waiting request of [s]: plan and start, or keep it for later. Never throws. */
    suspend fun runPending(s: Session)
    /** ShoalwatchApp.onCreate: run every waiting request once, and again whenever its server comes online. */
    fun start()
    companion object { const val MAX_TRIES = 5; val ALLOWED = setOf(0, 1, 2, 3, 5) }
}
```
**Rules:**
- **Per profile, default Off** (`autoNextCount = 0`); choices Off / 1 / 2 / 3 / 5.
- **Triggered only by a downloaded episode being finished in the player, or watch-and-delete** (`onLocalFinished`, `release`), never by a tick in a list.
- **What it fetches:** the first N unwatched episodes after the anchor, in the app's season order (`NextUnwatched`), for this profile. Episodes already on the phone for this profile cost nothing (the planner makes them share-only or skips them). So the phone keeps N unwatched episodes ahead and never runs away.
- **Quality:** `DownloadPrefs.defaultQuality` if set, else the anchor download's own quality (kept in the request, since the anchor may have just been released).
- **A durable request** (`auto_next_requests`, one per profile and series, the newest anchor winning) is written first, so nothing is lost offline, signed out (cascade on removal) or across a restart.
- **Running a request:**
  - not while metered with "Use mobile data" off (the next Wi-Fi connection runs it);
  - otherwise `prepare` then `start`. The queue's own constraint still keeps the bytes on Wi-Fi.
- **Outcomes:**
  - started, or nothing to fetch → the request goes;
  - `enoughSpace == false` → nothing is started (no partial batch), `blockedAt` is set, Storage says so (Task 14), and it is tried again at the next start or trigger;
  - `OFFLINE`, `NETWORK`, `TIMEOUT`, `NOT_SIGNED_IN` → kept;
  - anything else → `tries + 1`, dropped after `MAX_TRIES`.
- **Safeguarding:** the planner only plans items this profile was shown, and the server's `CanDownload` for this profile decides (Milestone 4).

**Setting** (Download settings): "Download next episodes automatically" (radio): "Off" (default), "Next episode", "Next 2 episodes", "Next 3 episodes", "Next 5 episodes". Help line: "After you watch a downloaded episode, Shoalwatch downloads the next unwatched ones in the same quality. They wait for Wi-Fi unless you allowed mobile data. For this profile only."

- [ ] **Tests first — `DownloadPlannerTest`:** `NextUnwatched` picks the next N unwatched after the anchor across a season boundary, never Specials, never watched ones, and nothing for an anchor in Specials or not in the list.
- [ ] **Tests first — `AutoDownloadNextTest`** (`FakeDownloads`, in-memory Room, virtual time):
  - Off does nothing;
  - 2 plans `NextUnwatched(count = 2)` with the anchor's quality;
  - a default quality wins;
  - offline keeps the request and runs it on `cameOnline`;
  - metered with mobile data off waits;
  - not enough space starts nothing and sets `blockedAt`;
  - five failures drop it;
  - a second watch of the same series replaces the anchor;
  - removing the account removes the request;
  - another profile's setting never applies.
- [ ] **Tests first — `DownloadSettingsRenderTest`:** the setting's five radios, labels and 48dp targets.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.downloads.*" --tests "app.shoalwatch.ui.downloads.*"` → pass.
- [ ] Commit: `feat(downloads): keep the next episodes of a show downloaded, on Wi-Fi, if a profile asks for it`.

---

### Task 14: Storage view, and "watched and deleted" in the offline series

**Files:**
- Create: `ui/downloads/StorageScreen.kt`, `StorageViewModel.kt`, `StorageRows.kt`, `ui/present/StorageText.kt`.
- Modify: `data/db/downloads/HousekeepingDao.kt` (usage queries), `HousekeepingRepository.kt` (`storage`, `watchedSummary`, `deleteWatched`, `deleteSeries`), `data/downloads/DownloadModels.kt` (`StorageView` and rows, `WatchedSummary`), `ui/downloads/DownloadsScreen.kt` (a "Storage" top-bar action), `ui/downloads/OfflineSeriesScreen.kt` (the released section), `ui/nav/AppNav.kt` + `BrowseRoutes.kt` (`Routes.STORAGE = "downloads/storage"`), `res/values/strings.xml`.
- Test: `data/downloads/StorageUsageTest.kt`, `ui/downloads/StorageViewModelTest.kt`, `ui/downloads/StorageRenderTest.kt`, `ui/downloads/OfflineSeriesRenderTest.kt` (new cases), `ui/present/StorageTextTest.kt`, `ui/nav/BrowseNavigationTest.kt` (new destination).

**Interfaces — Consumes:** `Housekeeping`, `ReleaseStore.release`, `Volumes.choices`. **Consumes (Milestone 4):** `Downloads.remove` (own deletes, with Milestone 4's file removal), `OfflineLibraryDao` clauses (`MINE_OR_SHARED`, `VISIBLE_JOIN`, `SERIES_VISIBLE`, `SEASON_VISIBLE`), `StorageChoice`, `DownloadText` size formatting ("1.8 GB", "640 MB"), `PosterCard`/`EpisodeRow`/`SectionHeader`/`Artwork`, `OfflineSeriesScreen`.

**Produces:**
```kotlin
data class VolumeUse(val kind: StorageKind, val volumeId: String?, val freeBytes: Long, val usedByDownloads: Long, val available: Boolean)
data class SeriesUse(val series: ItemWithUserData, val episodes: Int, val watched: Int, val bytes: Long, val deletableBytes: Long)
data class FilmUse(val film: ItemWithUserData, val bytes: Long, val watched: Boolean, val ownedByMe: Boolean)
/** One profile's view of the phone's storage. Other profiles' downloads appear only as [otherProfilesBytes]. */
data class StorageView(val volumes: List<VolumeUse>, val series: List<SeriesUse>, val films: List<FilmUse>,
                       val otherProfilesBytes: Long, val autoNextBlocked: Boolean)
/** What "Delete watched" would do for this profile: [sharedForEveryone] are shared and go for every profile (ANYONE rule). */
data class WatchedSummary(val count: Int, val bytes: Long, val sharedForEveryone: Int)

data class StorageMessage(val kind: Kind, val count: Int = 0) { enum class Kind { DELETED, KEPT_FOR_OTHERS, FAILED } }
data class StorageUiState(val session: Session?, val view: StorageView?, val loading: Boolean, val localFailure: Boolean, val confirm: StorageConfirm?)
sealed interface StorageConfirm {
    data class Watched(val summary: WatchedSummary) : StorageConfirm
    data class Series(val seriesId: String, val name: String, val bytes: Long) : StorageConfirm
    data class Film(val itemId: String, val name: String, val bytes: Long) : StorageConfirm
}
class StorageViewModel(catalog: ServerCatalog, housekeeping: Housekeeping, downloads: Downloads) : ViewModel() {
    val state: StateFlow<StorageUiState>
    val messages: Flow<StorageMessage>
    fun askDeleteWatched(); fun askDeleteSeries(seriesId: String); fun askDeleteFilm(itemId: String)
    fun confirm(); fun cancel()
}
```
**What is counted:**
- Series and films are what this profile may see by Milestone 4's rule. Sizes are `totalBytes` for COMPLETE downloads, `doneBytes` otherwise, sorted by size, largest first.
- `deletableBytes` is what this profile owns.
- `otherProfilesBytes` is everything else on this server's downloads, as one number: no title, no count.
- Volumes come from `Volumes.choices()` plus the summed bytes of every download on that volume.

**Deleting:**
- A series or a film deletes only this profile's own downloads (`Downloads.remove`, Milestone 4's rule: the files go with the last owner).
- "Delete watched" applies the watch-and-delete rule (`ReleaseStore.release`) to every download this profile may see and has watched. With "After anyone has watched", shared ones go for every profile, and the confirm says so first.

**Exact words:**
- Title "Storage". Downloads' top-bar action "Storage" (48dp).
- Volumes:
  - "This phone: %1$s free" / "SD card: %1$s free", each with "Downloads use %1$s";
  - a card not in the phone: "SD card: not in the phone. %1$s of downloads are saved on it."
- Headings "Series", "Films". Series row: plurals `storage_series_row` — one "%1$d episode · %2$s", other "%1$d episodes · %2$s"; and plurals `storage_watched` — one "%d watched", other "%d watched".
- Non-owned shared row: "Downloaded by another profile" (no delete).
- "Other profiles' downloads: %1$s".
- Delete labels: "Delete downloads of %1$s" / "Delete download of %1$s".
- Confirms:
  - series: "Delete the downloaded episodes of %1$s?" with "This frees %1$s on this phone. Episodes downloaded by another profile stay.";
  - film: "Delete this download? This frees %1$s on this phone." (Milestone 4's string).
- "Delete watched" button. Confirm: plurals `delete_watched_title` — one "Delete %d watched download?", other "Delete %d watched downloads?". Body: "This frees %1$s on this phone. The shows still appear in Downloads and stay watched." Plus, when `sharedForEveryone > 0`, plurals `delete_watched_shared` — one "%d of them is shared and is deleted for every profile.", other "%d of them are shared and are deleted for every profile." Buttons "Delete" and "Cancel".
- Done: plurals `deleted_count` — one "Deleted %d download.", other "Deleted %d downloads."; or Milestone 4's "Removed from your downloads. It stays on this phone for another profile."
- Auto-download blocked: "Next episodes weren't downloaded automatically: there isn't enough free space."
- Empty: "Nothing is downloaded on this phone."
- **Offline series:** under each season, a sub-heading "Watched and deleted", then that profile's released episodes as `EpisodeRow`s with the watched tick, no Play, and the line "Deleted from this phone. Download it again when you're online."

- [ ] **Tests first — `StorageUsageTest`** (in-memory Room): a child profile's view never includes the adult's hidden film (by title, size or count) and its bytes appear only in `otherProfilesBytes`; owners vs shared viewers; sizes by state; SD card volumes; `watchedSummary` counts match what `deleteWatched` then deletes, under both rules.
- [ ] **Tests first — `StorageViewModelTest`** (`FakeHousekeeping`, `FakeDownloads`): the session rule (an account switch empties the screen at once and the old profile's titles never appear; a confirm opened for one session never acts as another); each confirm and its message; a failure gives `localFailure` with Try again.
- [ ] **Tests first — `StorageRenderTest` / `OfflineSeriesRenderTest`** (360x640 and 840x1200, font scales 1.0 and 2.0): headings are headings; each row is one focusable element with its whole meaning; every button 48dp with its own label; no delete on a non-owned row; the shared line only when it applies; the released section with ticks and no Play; nothing under 13sp; the empty state scrolls.
- [ ] **Tests first — `StorageTextTest`** (every string, plurals, digits never localised) and **`BrowseNavigationTest`** (`downloads/storage` resolves; the tab keeps its state).
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.downloads.*" --tests "app.shoalwatch.ui.*"` → pass.
- [ ] Commit: `feat(downloads): a Storage screen that shows what uses space and deletes it, and watched-and-deleted episodes still listed offline`.

---

### Task 15: On-device smoke test

**Files:** Create `app/src/androidTest/java/app/shoalwatch/ui/SyncSmokeTest.kt` (+ reuse `app/src/androidTest/assets/media/clip.mp4`).

- [ ] Write it (compile here; **the controller runs it**):
  - Seed one server and TWO accounts (A and B, different tokens) through the real `AppGraph`, as `PlayerSmokeTest` does, cleaning up even if seeding fails.
  - An in-process `MockWebServer` with a switchable "down" mode (`SocketPolicy.DISCONNECT_AT_START` for everything) serves `/System/Info/Public`, a series with three episodes and one film (items, seasons, episodes), `PlaybackInfo`, and the user-data routes with per-account state (keyed by the token), recording every request.
  - **Instant and offline:**
    - Browse the series as A while up, then go down.
    - Tick the three episodes watched through `AppGraph.playstate`: each call returns within 500 ms and Room shows each watched at once.
    - As B, favourite the film.
    - Bring the server up and call `addressSelector.refresh()`. Within 30 s:
      - A's requests arrive in tick order (GET then POST `/UserItems/{id}/UserData`, body `Played` true), every one with A's token and `userId`;
      - B's favourite arrives with B's token;
      - no request has `datePlayed` or `api_key`;
      - `Sync.status` for both reads 0 waiting with a last-synced time.
  - **The job is there:** after a tick while down, `WorkManager.getWorkInfosForUniqueWork("shoalwatch-sync")` holds one ENQUEUED request with a `CONNECTED` constraint and no input data.
  - **Watch-and-delete:**
    - Seed a COMPLETE download of one episode for A (`DownloadStore.add` + `DownloadStore.update` to COMPLETE, the asset copied to `DownloadPaths.mediaFile`) with A's `visible_items` record.
    - Go down. Open the episode's detail and press Play. Within the clip's length plus 10 s, "Watched — delete download" is shown.
    - Press it, then Back.
    - The item folder is gone. The episode is still listed in the offline series under "Watched and deleted" with its tick. A's outbox holds a PLAY and a PLAYED row.
    - Bring the server up: a POST `/UserItems/{id}/UserData` with `PlayCount` 1 and `LastPlayedDate` (the phone's finish time, to the second) arrives with A's token. After a forced second drain the server's count is still 1.
  - **Sign-out drops the rest:** go down, tick once as B, sign B out through the Servers screen (the dialog says "1 change hasn't reached your server yet…"); B's rows are gone and A's untouched.
- [ ] Run: `./gradlew.bat assembleDebugAndroidTest` → builds.
- [ ] Commit: `test(sync): on-device smoke test — instant ticks offline, ordered per-profile sync, watch-and-delete`.

---

### Task 16: Milestone close

- [ ] **Full verification:** `./gradlew.bat clean testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease` → all green. `verifyReleaseManifest` and `verifyManifestRuleFixtures` pass **unchanged**; `gradle/verification-metadata.xml` is unchanged. `SourceShapeTest`, `SqlCompatibilityTest`, `ArtworkBoundaryTest`, `Media3BoundaryTest`, `PlaybackBoundaryTest` and Milestone 4's `DownloadBoundaryTest` pass. `PlaybackController.kt` is unchanged since Milestone 4.
- [ ] **Add `SyncBoundaryTest`** (a source scan, as `DownloadBoundaryTest`):
  - nothing under `ui/` references `OutboxStore`, `OutboxDao`, `SyncSender`, `SyncGateway`, `ReleaseStore`, `HousekeepingDao`, `LocalPlaying` or `WorkManager`;
  - only `SyncGatewayImpl` names `/UserItems/` with `UserData` in `data/sync`;
  - the string `datePlayed` appears nowhere in `src/main`;
  - nothing in `data/sync` names `/Sessions/Playing`;
  - no `Data.Builder` / `setInputData` anywhere;
  - only `OutboxStore` writes `sync_outbox` or `sync_marks` (`INSERT`/`DELETE`/`UPDATE` on them appear only in `OutboxDao`, called only by `OutboxStore`), except `Migration4To5`.
- [ ] **Controller: emulator run** of every instrumented test, including `SyncSmokeTest`. Then a walkthrough against `tools/mockjelly` (`python -I mock_jellyfin.py 8096`), signed in as `ben`, `anna` and `kid`:
  - airplane mode: tick five episodes in a row as `ben` (each shows at once), favourite one, take one off Continue Watching; swipe the app away; airplane mode off → within a few minutes, without opening the app, `GET /mock/playstate` shows them in order as `ben` only;
  - play a download offline to the credits as `ben` → the prompt; press it → the space is freed (Storage), the show still in Downloads with the episode under "Watched and deleted";
  - online again → `GET /mock/userdata?user=ben` shows the play count raised by exactly one and the finish time as last played;
  - repeat with `--lose-reply-once`, `--markplayed-counts`, `--userdata-ignores-dates`, `--no-userdata-get` and `--clock-skew=600`: never a count of 2 for one watch, never an unwatch of something watched later on "another device" (`POST /mock/userdata` before reconnecting);
  - sharing on, "After anyone": `anna` releases a download `ben` made → gone for both; "After everyone": stays until both have watched; "Watched together" marks both (`/mock/userdata` per user);
  - `kid` never sees Night Shift in Downloads, Storage (no size line beyond "Other profiles' downloads") or the prompt, and cannot release it;
  - auto-download next = 2 for `ben`: finish episode 1 → episodes 2 and 3 queue (on Wi-Fi), and none for `anna`;
  - sign out `anna` with a change waiting → the dialog line, then her rows are gone;
  - reboot the emulator with changes waiting → they are sent after boot.
  - Screenshots checked by eye at font scale 1.0 and 2.0, with a TalkBack pass over the prompt (beside Up next), Storage, the settings and the sync lines.
- [ ] **Whole-milestone review**, code and security against SPEC §6 and the threat model, focused on:
  - the session rule in the sender (rows sent only as their own profile; nothing after removal);
  - the idempotency argument (stored absolute counts, no `datePlayed`, lost replies);
  - the conflict rule and its margin;
  - marker and list durability across a restart;
  - the release rule and its visibility clauses (mutation checks), and "Watched together";
  - deferred file deletion and the orphan sweep;
  - the migration (the rebuilt table, the converted plays);
  - the persisted rejection;
  - WorkManager (no input data, `REPLACE`, no foreground);
  - no id, title, position or date in a log;
  - the Storage screen's leak surface for a child profile.
  
  Then a fix wave and a re-review.
- [ ] **`docs/security/milestone-5-check.md`:** one row per S1–S13 with evidence (test names), plus:
  - no new permission, dependency or exported component;
  - **residual risks:**
    - **free profile switching (Milestone 2 H1) now also lets a child switch to an adult and release shared downloads or mark them watched**; still Milestone 7's, and it must land before any release;
    - "Watched together" lets one profile change another profile's watched state on the server, by design (SPEC §3.3), and only for profiles that can see the item;
    - a phone clock set wrong can make a position win or lose a conflict (watched is never undone either way);
    - offline plays reach the server's own watched state, count and dates, but not plugins that count only live sessions (Playback Reporting);
    - a lost update (another device playing the same item between our read and write) can under-count one play;
    - the library cache and the outbox are not encrypted at rest (as Milestone 2 I1).
- [ ] **ARCHITECTURE.md:**
  - new section **"Sync-back (`data/sync`)"**: the tables, the order and coalescing rules, the conflict table, the idempotency argument, the failure table, the triggers and the job, the account rules;
  - **"Tidy-up actions"** rewritten for local-first actions (the five steps become three; the per-user queue holds only the local write; markers mean "send"; "For Milestone 5" replaced by what was done);
  - **"Progress reports and teardown":** the pending stop is now the outbox;
  - **"Library cache":** v5 tables, the rebuilt `download_settings`, the pin rule with tombstones, Migration 4 to 5;
  - new section **"Housekeeping (`data/downloads`)"**: the release rule, tombstones, deferred file deletion, auto-download next, Storage;
  - **"Player screen":** the prompt;
  - the package map;
  - "Known limits": a release pressed just before the process dies leaves the files to the next start's sweep; auto-download next follows only downloaded episodes finished in the player; outside the player a tick never deletes.
- [ ] **ChangeLog:** one plain-English line (minor bump), e.g. "vX.Y — DD/MM/YYYY — Ticks show straight away, even offline, and reach your server in order with the time you actually watched; finishing a downloaded episode offers one press to mark it watched and delete it; shared tablets follow one clear rule for shared downloads; the next episodes can download by themselves on Wi-Fi; and a new Storage screen shows what is using space."
- [ ] **TODO.md** (sync script) and the Obsidian project note:
  - tick Milestone 5, "Make quick ticks instant", "Watch and delete", "Profile sharing of downloaded media" and "Watched syncs back up to the server";
  - update "Confirm on the real server that retrying 'mark as watched' does not count the play twice" to the rows of "Unproven until Ben's real server" below;
  - add the follow-ups:
    - "Protect a profile so a child cannot switch into it" now also covers shared downloads (note it on the existing item);
    - a per-item "Sync now" if Ben wants one;
    - Playback Reporting plugin support for offline plays, if Ben uses the plugin.
- [ ] **Ben's phone test** (only with his explicit yes; the one contact with his server). Work through every row of "Unproven until Ben's real server" and record which path each took:
  - tick five episodes offline, close the app, reconnect: the server's watched state and dates in the web dashboard match;
  - watch a downloaded episode in airplane mode to the credits, delete it, reconnect: play count raised by one, last played at the real time;
  - a restricted profile on the same tablet with sharing on.
  
  Record the results in the security check and ARCHITECTURE.

---

## Decisions made

| Decision | Alternative | Why |
|---|---|---|
| **The outbox is Room rows (`sync_outbox`), one per change, ordered by an AUTOINCREMENT `seq`, with a foreign key to the account** | WorkManager input data per change; an in-memory queue | Survives process death, reboot and updates; is one transaction away from the local change; cascades with the account; and nothing sensitive ever goes into WorkManager's own database (it has no input data at all) |
| **Every tidy-up action is local-first: the change and its outbox row commit together, and the call returns** | Keep the online-only rollback | The to-do item "Make quick ticks instant" and SPEC §3.2's queue. The per-user queue stays (it is tested), but now holds only a millisecond write |
| Repeated taps coalesce per (profile, item, kind), except PLAY | Send every tap | Bounds the queue and sends the final state; each PLAY is a real watch and must count |
| **An offline watch is sent as `POST /UserItems/{id}/UserData` with `Played`, an absolute `PlayCount` computed once from the server's copy and stored before sending, `LastPlayedDate` = the finish time, position 0** | `POST /UserPlayedItems/{id}?datePlayed=` | A dated mark adds one to the count on every call (Jellyfin's behaviour as described in its source, unproven here), so a retry after a lost reply would count twice. An absolute number resent unchanged cannot. It also carries the original date (SPEC §3.2) |
| A film or episode tick sends `Played` with `PlayCount = max(count, 1)`; a season or series tick uses `/UserPlayedItems` (no date) | Every tick through `/UserPlayedItems` | Absolute values are retry-safe; only the server knows a container's children, so containers keep the server's own mark (its idempotency was already believed safe and is on the to-do) |
| A finished online stop the server never heard is queued as a PLAYED tick, not a PLAY | Queue a PLAY | The server already saw that session start and may count it itself when the session times out; a tick can never add a second play |
| **Never replay session reports** (`/Sessions/Playing*`) for offline plays | Replay start + stop at reconnection | A replay shows a phantom "now playing" on the dashboard, carries the reconnection time, and goes through the server's own counting. Research: "better for deferred sync than replaying session events" |
| **Conflict rule:** read the server's copy first. "Newer on the server" = its `LastPlayedDate` is at or after our change minus 2 minutes. A newer server copy wins for position and for an unwatch; **watched is never undone**; a favourite (no server time) is the user's tap | Pure last-writer-wins by timestamp; server always wins | SPEC §3.2. The phone's and server's clocks cannot be compared (no time endpoint), so ties lean to the side that cannot lose "watched" |
| A server without the user-data read (404 while the item exists) gives "unknown": the local change is sent, a PLAY without a count | Drop the change; guess | Correct either way: under-counting is possible, double-counting is not |
| A row ends only by confirmation, an overruling server answer (403, or 404 proved by `/Items/{id}` 404 with `/UserViews` 200), coalescing, or account removal | Drop after N failures | SPEC §3.2 "only cleared item by item once the server confirms". The extra check stops a misconfigured proxy's redirect (also `SERVER_NOT_FOUND`) from discarding a queue |
| Transient failures hold the whole profile's queue (order); ten 5xx answers park a row so other items can pass, but never that item's later rows | Strict order always; no order | One broken item must not block everything; per-item order is what correctness needs |
| **Triggers:** in process after each change, on reconnection, at start-up, plus one unique WorkManager job (`CONNECTED`, `REPLACE`, not expedited, not foreground) | A periodic job; a foreground service | "A change made just before the app is closed is sent, not dropped" with no new permission and no notification. `REPLACE` is safe because the worker only waits on the app-scope drain |
| **Sign-out sends what it can (10 s), then the rest is deleted with the account; a rejected sign-in (401) keeps its outbox** | Keep a signed-out profile's queue; drop on 401 | On a shared tablet a signed-out profile must leave nothing behind, and the dialog says how many changes are at stake. A 401 is not the user's choice, so their changes wait for them to sign in again |
| Rejected sign-ins are remembered in Room (`rejected_sign_ins`) | Memory only (today) | Milestone 2 check I4, scheduled for this milestone: background sync after a restart would otherwise send a dead token once per start |
| **The pending marker now means "send"**: cleared only when no outbox mark covers the row; the start-up sweep and idle clean-up clear only unmarked rows; list departures are durable (`sync_marks.leftList`) and applied in `writePage` | Keep `LocalChangeLog` as the only memory | ARCHITECTURE "For Milestone 5": after a restart the process-local log is empty, so a Continue Watching refresh could otherwise bring back an item the user removed offline |
| Milestone 4's `offline_plays` rows are deleted once a confirmed row covers them; start-up recovery queues only idempotent forms | Keep them forever | A row left behind must never cause a second PLAY |
| **The watch-and-delete setting is per profile** (Ask each time, default) | Per device | SPEC §3.1 does not say. On a shared tablet a parent may want Never while a child's profile is Always |
| "Delete shared downloads" is per server in `download_settings`, default "After anyone has watched" | Per profile | SPEC §3.3, and it travels with "Share downloads between profiles" |
| **Release rule:** sharing off → only this profile's claim (files go with the last owner); sharing on + after anyone → gone for everyone; sharing on + after everyone → gone once every profile that owns or was shown it has watched it here | Always delete for everyone; a third setting | SPEC §3.3 for sharing on. With sharing off the other owners never agreed to share, so one profile cannot delete their copy |
| **A profile can only release what it may see** (Milestone 4's `getPlayable` clauses), and the "can't" answer is identical to "no download" | Check ownership only | Safeguarding: a child can neither remove an adult's download of a title outside its permissions nor learn it exists |
| "Watched together" marks only other profiles that share it, are signed in and were shown the item, each in its own outbox; the button shows a count, never names | Mark every profile on the server | SPEC §3.3; "nothing from one profile's outbox is sent as another" stays true |
| **Files are deleted only once the player lets go** (`LocalPlaying`); a process that dies first leaves them to the next start's orphan sweep | Delete at the press | The button is pressed during the credits while the engine still reads the file; a deleted file would fail a seek back |
| **Per-profile tombstones (`released_episodes`)** keep the episode, season and series pinned and listed under "Watched and deleted"; they go when the series' last download goes | A new download state; nothing | SPEC §3.1 "the show still browses normally offline and the episode shows as watched". A new state would leak into every Milestone 4 query that filters on state |
| "Finished" for watch-and-delete is the player's own mark (credits or 90% by playback); a tick in a list never deletes; ALWAYS acts at the stop, in the data layer, with no undo | Delete on any watched tick | SPEC §3.1's "an explicit 'mark watched'" is read as within the player, where there is no mark-watched control; deleting from a list tick would surprise. ALWAYS is an explicit setting, and acting at the stop means the file is no longer open |
| **Auto-download next: per profile, default Off (0, 1, 2, 3 or 5)**, triggered by a finished downloaded episode, keeping the next N unwatched (numbered seasons only) in the anchor's quality, through a durable request | On by default; a device setting; trigger on any tick | A child's profile must not fill a shared tablet by default; the same season rule as Next Up and the episode navigator; durable so offline and restarts lose nothing |
| Auto-download plans only on an unmetered network while "Use mobile data" is off; too little space starts nothing and says so in Storage | Plan anywhere; partial batches | SPEC §3 row 10 "within storage and network rules" |
| **Storage shows only what the profile may see**, and everything else as one size; deleting a series or film removes only the profile's own; "Delete watched" applies the release rule | Show every download | Safeguarding; Milestone 4's "only owners delete"; one rule for every watched deletion |
| Sync status on the Downloads tab and under each profile on the Servers screen | A new screen | SPEC §3.2 "shows how many items are waiting to sync, and when the last sync succeeded" where offline use and sign-out decisions happen |
| `PlaybackController.kt` is not edited: `finished` reaches the screen through `stateOf` | Add a callback to the controller | It is at its 400-line limit; `stateOf` already lives outside it |
| The stand-in server gains three profiles with their own state | Test one profile only | Isolation between profiles is the heart of this milestone; it must be shown on the emulator |
| No new permission or dependency | — | Everything needed (WorkManager, its reschedule receiver) arrived in Milestone 4 |

## Unproven until Ben's real server

Checked once, at the end of the build, on Ben's phone against his 12.4 server (Task 16). The code already handles both answers to each question; this test says which path real use takes.

| Question | The code's path either way | What to look for on the real server |
|---|---|---|
| Does `POST /UserItems/{id}/UserData` apply `PlayCount` and `LastPlayedDate` as sent, and leave left-out fields unchanged? | Only intended fields are sent, as absolute values; `Played` is the part that matters most | After an offline watch: the dashboard shows the item played, the count up by one and the real watch time. If the count or date is not applied, record it (watched is still right) |
| Does `POST /UserPlayedItems/{id}` without `datePlayed` add to the count when repeated? | Used only for season and series ticks, at most once plus `ServerApi`'s one retry | Tick a season twice: the episodes' counts stay at one |
| Is `LastPlayedDate` moved by playback reports and by marks? | The conflict rule reads it as "the server's last change" | Watch part of an episode on the web, then sync an older offline position: the web position stays |
| How far apart are the phone's and the server's clocks? | A 2-minute margin, the server winning ties | No change synced in the same minute as a web change is lost the wrong way |
| Is `GET /UserItems/{id}/UserData` present for every item kind? | Missing → unknown copy, local change sent, no count | Sync works for films, episodes, seasons and series |
| Does a restricted profile get 403 or 404 on another's item through the user-data routes? | Both drop that row and show the server's copy | A child's queued change on a title since restricted disappears without an error |
| Does the server count a timed-out session (an online stop it never heard) as a play? | That case is queued as a tick, never a play | One watch is one count either way |
| Do statistics plugins (Playback Reporting) see offline plays? | Not designed to: no session is replayed | Record whether Ben uses the plugin; a follow-up if he does |

## Other open risks (only a real phone can prove)

- **WorkManager on real phones:** OEM battery managers and Doze may delay the sync job; the next start or reconnection sends anyway.
- **Process death between pressing "Watched — delete download" and leaving the item:** watched is kept and the row gone; the files wait for the next start's orphan sweep.
- **Font scale 2.0** with the prompt and the Up next card together on a small phone in landscape (Robolectric's stand-in font does not scale).
- **TalkBack** order between the prompt, Up next and the player controls.
- **Shared tablet:** a real restricted (parental rating) profile on Ben's server, sharing on, sees and releases only what its account allows.
