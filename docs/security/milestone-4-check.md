# Milestone 4 security check (S13)

**Date:** 16/11/2026 · **Branch:** `milestone-4`, after the close-out fix wave (Task 17; the commits are listed in section 4) · **Scope:** SPEC §6, S1–S13, for downloads and offline playback

This check was made by reading the code and the tests, and by the unit-test gate. Nothing in it was run on a device or against a real Jellyfin server.

- **Sources read:** the five task reviews of the milestone (`.superpowers/sdd/m4/tasks-*-review.md`), the task reports, and the code at the close-out wave.
- **Not done here:** the whole-milestone review the plan asks for (Task 17) and its fix wave. That is the controller's next step; this document is its input.

## Device results — TO BE FILLED BY THE CONTROLLER

> **TO BE FILLED BY THE CONTROLLER.** Nothing below this line in this box was run by the author of this document. Do not read anything here as a result.
>
> - The on-device suite (`connectedDebugAndroidTest`), including `DownloadSmokeTest`: _not yet run_.
> - The original and Medium download steps once with each stand-in flag (`--no-range`, `--etag-changes`, `--octet-stream`, `--candownload-missing`, `--download-forbidden`, `--legacy-auth-only`, `--hls-recut`, `--hls-idle-kill=5`): _not yet run_.
> - The walkthrough against `tools/mockjelly` (`--drop-after=65536`): a film (Original); a season of 3 (Medium) with airplane mode mid-season; a kill and a reboot mid-download; mobile data on and off; an SD card and its ejection; offline play (subtitles, Skip intro, the position kept, the watched tick); a second and a third profile with sharing off and on; signing out the owner; removing the server: _not yet run_.
> - The new deletes (an episode, a season, a series, as owner; none offered to another profile): _not yet run_.
> - The sharing re-ask with the Downloads tab never opened (clock or server change): _not yet run_.
> - Screenshots at font scale 1.0 and 2.0 and a TalkBack pass over every Downloads control, the sheet and the settings: _not yet run_.

---

## 1. Summary verdict

**Not yet signed off: code reading and unit tests only.** No finding from the five task reviews is open at Critical or Important. The Minors and Nits are either fixed in the close-out wave or carried with a reason (`docs/reviews/milestone-4-carried.md`).

- **Still needed before sign-off:**
  - the controller's device results above;
  - the whole-milestone review;
  - Ben's confirmation of the three permissions and the one exported job service that SPEC S9 and S10 do not list (section 5).
- **Still unproven:** a real phone and Ben's server (section 6).

| Area | Verdict |
|---|---|
| Tokens on the wire, in URLs, logs, notifications and WorkManager | **Strong.** Every byte through the account's own guarded client (`downloadRoute` allows `/Items/{this id}/Download` and `/videos/` only). No title, id or token in a notification, a log line or WorkManager's records (no input data at all) |
| Paths and deletion | **Strong.** `DownloadPaths` builds every name from validated GUIDs and fixed words (the only file under `data/downloads` that builds a `File`, `DownloadBoundaryTest`). Deletion re-checks the real path inside a root and never follows a link |
| Untrusted server data | **Good.** Sizes up to 256 GiB, `Content-Range`/`If-Range` checked against one version of the file, the HLS parser bounded (2 MiB, 20,000 segments, VOD only, no keys or byte ranges), segments capped and budgeted, and every finished file verified before it is ready |
| The shared-tablet rule (strict sharing) | **Strong after the fix wave.** A profile sees, plays or inherits another's copy only with its own server-confirmed `CanDownload` and its own `visible_items` record; a later "no" removes it; the 7-day re-ask runs wherever the app is (I-1 fixed). Offline validity is a recorded residual risk |
| Removal | **Good.** Only owners pause, cancel or delete; account and server removal follow one tested rule; nothing is left on disk that a remaining profile could not already see |
| What other apps and devices see | **Good.** One extra exported component, WorkManager's `SystemJobService`, bindable only by the system (`BIND_JOB_SERVICE`). The notice never names a title. Files on a portable SD card are readable on another device (warned) |
| Build and supply chain | **Good.** WorkManager 2.12.0 and its declared dependencies, sha256 only; the merged-manifest rules extended and each shown to fail by a fixture (11 rules) |

---

## 2. S1–S13 (as they apply to Milestone 4)

| ID | Requirement (short) | Status | Evidence | Proved by |
|---|---|---|---|---|
| **S1** | Tokens and headers encrypted at rest | **Met (unchanged)** | No new secret is stored. The download tables hold ids, sizes, states and validators; `download_prefs` holds device settings; nothing on an SD card but media and subtitle files | `MigrationTest.theNewTablesAreUsableAfterMigration`, `DownloadCascadeTest` |
| **S2** | No backup | **Met (unchanged)** | `allowBackup="false"`; both rule files exclude `root`, `file`, `database`, `sharedpref` and `external` (app-specific external files are in `external`); removable volumes are never backed up | `ManifestSecurityTest.backupIsDisabled`, `dataExtractionRulesAreSet`, `verifyReleaseManifest` |
| **S3** | HTTPS by default | **Met** | Downloads use the account's own client, so `CleartextGuard` applies | `DownloadRouteTest.theItemsOwnDownloadIsSentWithTheTokenAndTheCustomHeaders` (the same client as `MediaRouteTest`) |
| **S4** | Trust on first use; pins | **Met** | The same client, so the pin applies to every download request | `MediaRouteTest` (pinned self-signed server); `DownloadRouteTest` uses the same `ServerApi` path |
| **S5** | Client certificates | **Met (device unproven)** | The same client path | Milestone 1 tests |
| **S6** | Token only in `Authorization`; never in a URL, log, error, notification or `toString()` | **Met** | `ServerApi.downloadRoute` (exact path, nothing sent); a 401 told apart by one `ServerApi` call, never by putting the token in a URL (`SERVER_AUTH_REFUSED`); no notification text names anything; WorkManager has no input data; download models print `<redacted>` | `DownloadRouteTest.onlyThisItemsDownloadPathIsAllowedBesidesVideos`, `DownloadNotificationsTest.noTextNamesAnItemAUserOrAnId`, `DownloadWorkerTest.theWorkerHasNoInputData`, `DownloadQueueTest.downloadModelsNeverPrintIdsSizesOrValidators`, `DownloadBoundaryTest` (no `Data.Builder`, no `Log.` call outside `AppLog`); `DownloadSmokeTest` asserts no `api_key` in any URL (device) |
| **S7** | No third-party hosts | **Met** | No new host; WorkManager's diagnostics receiver removed; no client built outside `HttpClientFactory` | `DownloadBoundaryTest.theDownloadCodeKeepsItsBoundaries`, `ManifestSecurityTest.workManagersDiagnosticsReceiverAndDefaultInitialiserAreRemoved` |
| **S8** | App lock, recents hiding | **N/A until M7** | — | — |
| **S9** | Minimum permissions | **Met, with three additions for Ben to confirm** | Merged: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` (S9 lists these), **plus `RECEIVE_BOOT_COMPLETED` and `WAKE_LOCK`** (WorkManager's own; section 5), plus androidx.core's signature-level receiver permission. No storage permission | `ManifestSecurityTest.theRequestedPermissionsAreTheMilestone4Ones`, `wakeLockIsNoLongerRemovedBecauseWorkManagerHoldsOneWhileForegroundWorkRuns`, `verifyReleaseManifest`, fixture `bad-extra-permission.xml` |
| **S10** | App-specific storage; only the launcher exported; immutable `PendingIntent`s | **Met, with one exception for Ben to confirm** | Internal `filesDir/downloads` by default; an SD card's app-specific folder only by choice, with its warning. **WorkManager's `SystemJobService` is exported** with `android:permission="android.permission.BIND_JOB_SERVICE"`, so only the system can bind it (the rule allows exactly that). The notice's tap is an explicit `FLAG_IMMUTABLE` intent | `ManifestSecurityTest.theForegroundServiceIsMergedAsDataSync`, `verifyManifestRuleFixtures` (`bad-unprotected-job-service.xml`, `bad-diagnostics-receiver.xml`, `bad-missing-data-sync.xml`), `DownloadNotificationsTest.theTapIsAnExplicitImmutableIntentToTheDownloadsTabOnly`, `AndroidVolumesTest` |
| **S11** | Responses untrusted; names never from the server | **Met** | Names from GUIDs and fixed words only; sizes, ranges, playlists and segments bounded; every file verified | `DownloadPathsTest.everyBuilderRefusesABadIdWithItsFixedMessage`, `DownloadGatewayHostileTest`, `FileFetcherHostileTest`, `HlsPlaylistsTest`, `HlsFetcherTest`, `DownloadVerifierTest` |
| **S12** | Pinned, checksum-verified dependencies | **Met** | `androidx.work:work-runtime` and `work-testing` 2.12.0, sha256 only; the older WorkManager artefacts in the verification file are said plainly in ARCHITECTURE "Version checks" | Gradle dependency verification (the gate) |
| **S13** | Security check each milestone | **This document** (pending the device results and the whole-milestone review) | — | — |

---

## 3. The checks

| # | Check | What was checked | Evidence | Verdict |
|---|---|---|---|---|
| 1 | **Path building and deletion** | Traversal, links, other roots, a card that is out | `DownloadPaths`: ids must be normalised GUIDs; every builder checks its result's canonical path lies under a root; `deleteInside` re-checks the real path and removes a link as itself; the sweep has an entry limit | `DownloadPathsTest.aLinkPlantedInAnItemFolderIsRemovedAsALinkAndItsTargetSurvives`, `deleteInsideRemovesAFolderAndRefusesEverythingOutsideTheRoots`, `OrphanSweepTest.aLinkInTheTreeIsRemovedAsItselfAndItsTargetSurvives`, `DownloadBoundaryTest` | Sound (link tests skip where the host cannot make links: carried) |
| 2 | **The download route's exact path** | Which requests the route allows | GET/HEAD to `<base>/Items/<this id>/Download` and `/videos/` only; another item, `/File`, a longer path, POST, another authority are refused | `DownloadRouteTest` (8 cases) | Sound |
| 3 | **HLS playlists and segments as hostile input** | Size, shape, keys, URIs, segment bytes | Parser bounds and refusals (keys, maps, byte ranges, discontinuities, absolute or root-relative URIs, non-`.ts`); segments capped, budgeted against the conversion's size, `0x47` checked, written to `.part`, fsync'd, renamed | `HlsPlaylistsTest.anEncryptionKeyIsRefusedButMethodNoneIsAllowed`, `aPathThatEscapesVideosIsRefused`, `tooManySegmentsOrTooMuchTextIsRefused`, `HlsFetcherTest` | Sound. The master is held to `/videos/`, not to this item (carried: needs the real server) |
| 4 | **Range and If-Range against a lying server** | Lengths, totals, validators, 200 to a resume | A 206 must carry the `If-Range` validator sent and the stored total; a 200 to a resume cuts to zero and says so to Room first; three validator changes in a row is "changed on server" | `FileFetcherHostileTest.aContentRangeWithTheWrongStartOrGarbageRestartsAndAWrongTotalIsChangedOnServer`, `aContentLengthThatDisagreesWithTheSizeIsChangedOnServer`, `FileFetcherResumeTest`, `FileFetcherTest.aValidatorThatChangesEveryTimeRestartsAndThreeInARowIsChangedOnServer` | Sound |
| 5 | **Nothing claimed that is not fsync'd** | Progress counts, segments, subtitles | `force` before each progress save; segments and subtitles renamed only after fsync | `FileFetcherTest.aTornTailIsCutBeforeTheResume` (and the recording `FileChannel`) | Sound |
| 6 | **The verifier** | Complete, signature, readable, decodable, length, SHA-256 | COMPLETE is written in one place, behind the check and a named media source and container | `DownloadVerifierTest.check2HtmlRenamedMp4IsTheWrongKindOfFile`, `aCopyWithNoMediaSourceIsNeverGood`, `DownloadQueueTest.aRowWithNoContainerOrMediaSourceIsNeverMarkedComplete` | Sound (Dolby Vision is coarse: carried) |
| 7 | **The strict sharing rule and the visibility joins** | Sees, plays, inherits; revocation; the 7-day re-ask; offline | `MINE_OR_SHARED` and `visible_items` in every offline read; each guarding clause shown to matter by running the query without it; the re-ask runs on a session change, the server coming back and the app coming to the front (`SharingRecheck`), and a stale copy is not played while the server can be reached | `OfflineLibraryVisibilityTest` (a–g), `OfflineLibraryClausesTest` (h–m, each with its clause removed), `SharingTest`, `SavedCopiesTest.aSharedCopyWhoseConfirmationIsOverAWeekOldIsNotPlayedWhileTheServerCanBeReachedUntilReAsked`, `aChildWhoNeverOpensDownloadsLosesTheSharedCopyOnceTheServerSaysNo`, `offlineAConfirmationThatCannotBeReAskedStillPlaysHoweverOld`, `SharingRecheckTest` (each trigger, rate limit, one at a time) | Sound after I-1; mutation evidence in the Task 17 report |
| 8 | **The removal rules** | Owners only; account and server removal; a 401 | `RemoveOutcome.NotYours` for a non-owner; season and series deletes remove only the profile's own episodes, each through the store's check and `DownloadPaths` | `RemovalRulesTest`, `DownloadRepositoryTest.removeGivesEachOutcome`, `anOwnerRemovingASeasonDeletesEachOfItsEpisodesFoldersAndNothingElse`, `DownloadsViewModelTest.anOwnerRemovesItsOwnEpisodesOfASeasonAndNeverAnotherProfiles`, `OfflineSeriesPhoneRenderTest.anOwnerCanDeleteAnEpisodeAndASeasonAndANonOwnerGetsNeither` | Sound |
| 9 | **Pinned artwork re-validation** | A file at rest is untrusted | The same eight artwork checks at every read; a failing file and row are dropped | `PinnedArtworkTest.aTamperedPinnedFileIsRefusedDeletedAndThePlaceholderShows` | Sound (no per-profile visibility check at read: carried, Tasks 5-8 Minor 9) |
| 10 | **`ItemBytes`** | The one data source for the player and the probe | `file:` only for the current saved item, real path strictly inside its folder; links out refused | `ItemBytesTest.aSavedItemCannotReachOutsideItsFolder`, `aLinkInsideTheFolderThatLeadsOutsideIsRefused` | Sound |
| 11 | **Logs, notifications, WorkManager records** | Tokens, paths, titles | Logs are fixed words and exception class names; the notice is title-free with a private public version; no input data | `DownloadNotificationsTest`, `DownloadWorkerTest.theWorkerHasNoInputData`, `DownloadBoundaryTest` | Sound |
| 12 | **The merged manifest** | WorkManager's components, the job service, the diagnostics receiver | Checked on 16/11/2026: `SystemJobService` (exported, `BIND_JOB_SERVICE`), `SystemForegroundService` (`dataSync`, not exported), `ForceStopRunnable$BroadcastReceiver` and `RescheduleReceiver` (not exported), Room's `MultiInstanceInvalidationService` (not exported); no `DiagnosticsReceiver`, no `SystemAlarmService`, no `ConstraintProxy` | `verifyReleaseManifest`, `verifyManifestRuleFixtures` (11 rules shown to fail) | Sound |
| 13 | **The new tables' cascades** | Server, account, download removal; a 401 | As ARCHITECTURE "Download tables" | `DownloadCascadeTest` (7 cases) | Sound |
| 14 | **SD-card exposure** | What is on the card | Media and subtitle files only, under the app-specific folder, named by GUIDs; the database, tokens and artwork stay internal; a `.nomedia` marker | `AndroidVolumesTest.eachRootGetsANomediaFile`, `PinnedArtworkTest` (artwork internal only) | Residual risk (section 5) |

---

## 4. Findings

Severity is each review's own (Critical, Important, Minor, Nit). Commits are on `milestone-4`.

### Critical

None, in any of the five reviews.

### Important — all fixed

| Review | Finding | Fixed by |
|---|---|---|
| Tasks 1-4 I-1 | Series screens did not require the season's visibility record | `4792aa1` |
| Tasks 1-4 I-2 | Six safeguarding SQL clauses had no test | `4792aa1`, `efaf565` |
| Tasks 1-4 I-3 | `update` could move a row's series or season without re-pinning | `fbb3fac` |
| Tasks 1-4 I-4 | Reserving space by file descriptor changed the `.part` length | `70f73e8` |
| Tasks 1-4 I-5 | The sweep deleted `seg-N.ts.part`; no builder for it | `4e2cda7` |
| Tasks 1-4 I-6 | The stand-in had no `--no-etag` mode and gave 404 where Jellyfin gives 400 | `f351c15`, `0bab599`, `5156c41` |
| Tasks 5-8 I-1 | A share-only owner was added when `CanDownload` was unknown | `1f9316b` |
| Tasks 5-8 I-2 | A resume could join two versions of a file | `29900a8` |
| Tasks 5-8 I-3 | A conversion had no size budget or reservation | `127c61f` |
| Tasks 5-8 I-4 | The first media source might not be the file `/Download` sends | `581b001` |
| Tasks 9-10 I-1 | A pause was ignored and later undone | `a8de5a7` |
| Tasks 9-10 I-2 | An owner without its own confirmed `CanDownload` could keep a copy (the strict sharing rule) | `1a7df92` |
| Tasks 9-10 I-3 | An unexpected exception left a row RUNNING and blocked the queue | `40bc7ad` |
| Tasks 9-10 I-4 | Dolby Vision originals failed the check | `4479286` |
| Tasks 11-12 I-1 | A later "no" never removed access | `9796498` |
| Tasks 11-12 I-2 | `reconfirm` stopped at the first refused item | `7704935` |
| Tasks 11-12 I-3 | A kick could not cut a backoff short | `d272663` |
| Tasks 11-12 I-4 | A remembered conversion was reused for a new row | `2d777d2` |
| **Tasks 13-16 I-1** | The 7-day re-ask ran only from the Downloads tab, so a child playing from Home kept a stale confirmation | **`e4df4d9`** (`SharingRecheck`; `SavedCopies.plan` streams a stale shared copy while the server can be reached) |
| **Tasks 13-16 I-2** | An owner could not delete a downloaded episode, season or series | **`845318a`** |

### Minor and Nit

- **Fixed in the close-out wave:**
  - Tasks 13-16: M-1 `ee483ac`, M-2 `d51f4f5`, M-3 `76167ad`, M-6 `55b8db1`, M-7 `e3ace36`, M-8 `845318a`, M-9 `523feb3`, M-12 `b814464`.
  - Tasks 11-12: Minor 2 `97985d7` (security: a copy fetched under another owner's sign-in), Minor 7 `99cc9d2`, Nits 1 and 2 `fe52232`.
  - Tasks 1-4 Minor 4 and Tasks 5-8 Minor 10: `d798d98`.
- **Fixed earlier** (in each task's own fix wave): as each later review's status table records.
- **Carried, each with its reason and when:** `docs/reviews/milestone-4-carried.md`. Those that touch security or privacy are listed as residual risks below; none is a data-loss risk.

---

## 5. Residual risks

- **Offline validity of a sharing confirmation (the chosen behaviour).** While the server cannot be reached, a profile's confirmation that cannot be re-asked stays valid however old, so a child on a plane still plays a shared copy its account was once allowed to download, even if the server has since said no. It is re-asked as soon as the server can be reached (on a session change, the server coming back, the app coming to the front, or before the copy is played), and a "no" then removes it at once.
- **Permissions and an exported component awaiting the owner's confirmation.**
  - SPEC S9 lists `FOREGROUND_SERVICE` (+ data-sync type) and `POST_NOTIFICATIONS`. The build also has `FOREGROUND_SERVICE_DATA_SYNC` (the data-sync type itself), and **`RECEIVE_BOOT_COMPLETED`** and **`WAKE_LOCK`**, which WorkManager needs ("survives reboot", and its wake lock while foreground work runs).
  - So **five permissions** are new in this milestone; **Ben to confirm** them, in particular the two S9 does not name.
  - SPEC S10 says only the launcher is exported; WorkManager's **`SystemJobService`** is exported too, protected by `BIND_JOB_SERVICE` (only the system can bind it). **Ben to confirm.** The SPEC is not edited.
- **Files on a portable SD card are not encrypted** and can be read on another device (the settings warn; tokens, the database and artwork are never on the card). Encryption at rest (Media3 `AesCipherDataSource` with a Keystore key) is for Ben to decide at Milestone 8.
- **Downloads on internal storage** are protected by Android's file-based encryption and the app sandbox only.
- **Media3's extractors parse the downloaded bytes in-process**, during verification and playback, as in streaming (text tracks still pass through unread: Milestone 3 M3).
- **A server can make the app download up to 256 GiB per item** to its own `/Items/{id}/Download` or `/videos/` paths, bounded by the free-space check and reserve.
- **The 6-hour `dataSync` limit** (Android 15) pauses a long series plainly ("Open Shoalwatch to carry on"); **Android 16 job quotas** may delay work.
- **Originals are guarded twice, conversions once:** an original needs `CanDownload` (or unknown) AND the server's own `/Download` permission; a conversion through `/videos/` needs `CanDownload` to be a confirmed `true` and is never offered when it is unknown.
- **A LOCAL play sends nothing to the server until Milestone 5;** `offline_plays` keeps it. Until then a later server change to that item's watched state does not reach the tablet while its offline marker is pending.
- **Carried hardening (Minor, see the carried list):** pinned artwork has no per-profile visibility check at read (Tasks 5-8 Minor 9); the HLS master is not tied to the item's own path (Tasks 5-8 Minor 3); `PlannedItem.confirmed` is caller-settable inside the module (Tasks 11-12 Minor 9); a second profile learns that a title it may download is already on the phone (Tasks 1-4 Minor 7); the queue's counts are device-wide (Tasks 9-10 Minor 6).

---

## 6. Unproven until Ben's real server (and a real phone)

From the plan (Task 17 asks Ben's one phone test to record which path each takes):

| Question | The code's path either way | What to look for |
|---|---|---|
| Does `/Items/{id}/Download` honour `Range` and `If-Range` with header-only authentication? | Resume when it does; otherwise restart that file from zero | Wi-Fi off mid-film for 30 s: the row continues (Range works) or starts from 0%; "Ready to watch" either way |
| Does it send `ETag` or `Last-Modified`, and do they stay the same? | `If-Range` when a validator exists; three changes in a row is "The file kept changing on your server" | That message never appears for an unchanged file |
| Is `MediaSources[0].Size` present and equal to the bytes sent? | Size when present, else the header total; the verifier checks | No "didn't pass its check" on a good file |
| What `Content-Type` does `/Download` send for MKV and MP4? | Only surely-not-media types are refused | Both reach "Ready to watch" |
| Is `CanDownload` filled, and does it match the account's download permission? | true: both; false: neither; unknown: original only | With the permission off: "Your server doesn't allow this account to download this." With it on: both work |
| Does `/Download` accept header authentication with Jellyfin 12's legacy-authorisation setting off? | `SERVER_AUTH_REFUSED` vs signed out | Downloads work with it off, or record the setting that fixes it |
| Do two PlaybackInfo answers cut identical HLS segments, and how long is an idle conversion kept? | A conversion resumes only within one session, else restarts from segment 0 | Kill mid-Medium download: it restarts that episode and finishes; lock for an hour mid-season: every episode finishes |
| For a film with several versions, is the source whose `Id` is the item's the file `/Download` sends? | That source's `Id`, `Size` and container, else the first | A grouped film reaches "Ready to watch" with the primary version's size |
| Does a reverse proxy strip `Range` or redirect media? | Stripped: restarts from zero; a redirect is refused | Any "Your server had a problem sending this" |

Added by the task reports and this wave:

- Real playlist shape (`hls1/main/N.ts`, decimal `EXTINF`, one variant, no `DISCONTINUITY`/`MAP`), segment and playlist `Content-Type`s, and **whether a conversion's `TranscodingUrl` is always `/videos/<item id>/…`** (decides carried Tasks 5-8 Minor 3).
- What a direct-play-only PlaybackInfo answers for MKV and MP4 (`SupportsDirectPlay`), and whether a refusal is a 403 or an `ErrorCode`.
- `ENOSPC` as an `ErrnoException` on a really full disk.
- Subtitle formats and types the server sends for embedded `mov_text` (fetched as `srt`); series and season image tags against the episodes' `SeriesPrimaryTag`; real WebP pictures.
- Real 401 behaviour (`SERVER_AUTH_REFUSED`), session reaping across a long series, the time to the first converted byte.
- **A real restricted (parental) profile on Ben's server:** shared copies appear only after its own `CanDownload` is confirmed, and a later "no" removes them without opening the Downloads tab (the I-1 fix).
- What `reconfirm` costs on a large shared library; a card's folder swept after it is reinserted.
- **On a real phone:**
  - WorkManager under OEM battery managers, the 6-hour limit on a long series, Android 16 quotas, work across an app update;
  - `allocateBytes` / `getAllocatableBytes`, SD-card mount and unmount, `getExternalFilesDirs`;
  - `MetadataRetriever` on large MKVs (within 30 s), local HLS playback and seeking;
  - font scale 2.0 on a small phone's Downloads tab and sheet;
  - a weak `ETag` behind a proxy (carried Tasks 5-8 Minor 6).

---

## 7. Status after the check (16/11/2026, branch `milestone-4`)

- Unit-test gate: see the Task 17 report (`.superpowers/sdd/m4/task-17-report.md`) for the exact command and result.
- Open before sign-off: the controller's device results (above), the whole-milestone review and its fix wave, and Ben's confirmation of the permissions and the job service (section 5).
