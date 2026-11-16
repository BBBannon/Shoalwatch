# Milestone 4 security check (S13)

**Date:** 16/11/2026 · **Branch:** `milestone-4`, after the close-out fix wave (Task 17) and the final review's fix wave (the commits are listed in section 4) · **Scope:** SPEC §6, S1–S13, for downloads and offline playback

This check was made by reading the code and the tests, and by the unit-test gate. Nothing in it was run on a device or against a real Jellyfin server.

- **Sources read:** the five task reviews of the milestone (`.superpowers/sdd/m4/tasks-*-review.md`), the task reports, the whole-milestone review (`.superpowers/sdd/m4/final-review.md`) and the code after its fix wave.
- **The whole-milestone review** found 2 Medium, 11 Low and 4 Nit, plus one finding from the controller's emulator walkthrough (W-1). Each is fixed, or recorded here or in the carried list (section 4).

## Device results — TO BE FILLED BY THE CONTROLLER

> **TO BE FILLED BY THE CONTROLLER.** Nothing below this line in this box was run by the author of this document. Do not read anything here as a result.
>
> - The on-device suite (`connectedDebugAndroidTest`), including `DownloadSmokeTest`: _not yet run_.
> - The original and Medium download steps once with each stand-in flag (`--no-range`, `--etag-changes`, `--octet-stream`, `--candownload-missing`, `--download-forbidden`, `--legacy-auth-only`, `--hls-recut`, `--hls-idle-kill=5`): _not yet run_.
> - The walkthrough against `tools/mockjelly` (`--drop-after=65536`): a film (Original); a season of 3 (Medium) with airplane mode mid-season; a kill and a reboot mid-download; mobile data on and off; an SD card and its ejection; offline play (subtitles, Skip intro, the position kept, the watched tick); a second and a third profile with sharing off and on; signing out the owner; removing the server: _not yet run_.
> - The new deletes (an episode, a season, a series, as owner; none offered to another profile): _not yet run_.
> - The sharing re-ask with the Downloads tab never opened (clock or server change): _not yet run_.
> - Screenshots at font scale 1.0 and 2.0 and a TalkBack pass over every Downloads control, the sheet and the settings: _not yet run_.
> - The final review's fixes: the notice says only "Downloading" / "Downloads finished"; signing the owner out online with the child's confirmation over a week old deletes the copy; the W-1 Original download of the stand-in's 10 s film reaches "Ready to watch": _not yet run_.
> - The re-review's fixes: the Downloads tab's title and settings cog sit below the status bar and the cog opens the settings (W-2); a conversion queued by the parent and run under the child's sign-in with the child's download permission off on the stand-in never reaches "Ready to watch" for the child (N-1): _not yet run_.

---

## 1. Summary verdict

**Not yet signed off: code reading and unit tests only.** No finding from the five task reviews or the whole-milestone review is open at Critical, Important or Medium. The Minors, Lows and Nits are fixed, recorded as residual risks (section 5) or carried with a reason (`docs/reviews/milestone-4-carried.md`).

- **Still needed before sign-off:**
  - the controller's device results above;
  - Ben's confirmation of the five new permissions (two of them not named by SPEC S9) and the one exported job service that S10 does not list (section 5).
- **Still unproven:** a real phone and Ben's server (section 6).

| Area | Verdict |
|---|---|
| Tokens on the wire, in URLs, logs, notifications and WorkManager | **Strong.** Every media byte through the account's own guarded client (`downloadRoute` allows `/Items/{this id}/Download` and `/videos/` only); subtitles, skip markers, artwork, `facts` and PlaybackInfo through `ServerApi`'s normal authenticated client, which is the same account's own pinned client. No title, id, size, count or token in a notification, a log line or WorkManager's records (no input data at all) |
| Paths and deletion | **Strong.** `DownloadPaths` builds every name from validated GUIDs and fixed words (the only file under `data/downloads` that builds a `File`, `DownloadBoundaryTest`). Deletion re-checks the real path inside a root and never follows a link |
| Untrusted server data | **Good.** Sizes up to 256 GiB and never past the storage reserve, even when the server states no size (the space rule is asked before the first byte: final review M-2), `Content-Range`/`If-Range` checked against one version of the file, the HLS parser bounded (2 MiB, 20,000 segments, VOD only, no keys or byte ranges), segments capped and budgeted, and every finished file verified before it is ready, against the length of the source actually downloaded (W-1) |
| The shared-tablet rule (strict sharing) | **Strong after the fix waves.** A profile sees, plays or inherits another's copy only with its own server-confirmed `CanDownload` and its own `visible_items` record. Only the owner whose own sign-in fetched a copy is outside the rule, and "fetched" means the server checked that sign-in's own permission: an original's `/Download` does, and a conversion (whose `/videos/` does not) first asks `facts` for that sign-in's own `CanDownload` and converts only on a confirmed `true` (re-review N-1). An owner that did not fetch the copy and has no confirmed permission (its answer was unknown) keeps its ownership row but sees, counts, plays and inherits nothing of it, online or offline, until a later "yes" (re-review L-a). Otherwise a profile that inherits a copy, or joins it as "already on this phone", is re-asked after 7 days, refused while stale and the server can be reached, and removed as an owner on a later "no" (final review M-1); while the server can be reached it inherits only with a confirmation younger than 7 days. A confirmation dated ahead of the clock is stale (L-2); a clock held back within the week while online is a recorded residual risk (re-review L-b). The re-ask runs wherever the app is (I-1). Offline validity is a recorded residual risk |
| Removal | **Good.** Only owners pause, cancel or delete; account and server removal follow one tested rule; a deleted download's files, extras and pinned pictures go at once (L-4, L-5). Old copies on an SD card that is out when its server or account goes stay on the card until it is back (carried Tasks 1-4 Minor 1) |
| What other apps and devices see | **Good.** One extra exported component, WorkManager's `SystemJobService`, bindable only by the system (`BIND_JOB_SERVICE`). The notice never names a title and shows no count, size or progress (the queue is the whole device's). Files on a portable SD card are readable on another device (warned) |
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
| **S6** | Token only in `Authorization`; never in a URL, log, error, notification or `toString()` | **Met** | `ServerApi.downloadRoute` (exact path, nothing sent); a 401 told apart by one `ServerApi` call, never by putting the token in a URL (`SERVER_AUTH_REFUSED`); no notification text names or counts anything; WorkManager has no input data; download models print `<redacted>` | `DownloadRouteTest.onlyThisItemsDownloadPathIsAllowedBesidesVideos`, `DownloadNotificationsTest.noTextNamesAnItemAUserAnIdOrANumber`, `DownloadWorkerTest.theWorkerHasNoInputData`, `DownloadQueueTest.downloadModelsNeverPrintIdsSizesOrValidators`, `DownloadBoundaryTest` (no `Data.Builder`; no `Log.` call, `printStackTrace()`, `System.out`/`System.err` or `println(` outside `AppLog`); `DownloadSmokeTest` asserts no `api_key` in any URL (device) |
| **S7** | No third-party hosts | **Met** | No new host; WorkManager's diagnostics receiver removed; no client built outside `HttpClientFactory` (nor `HttpURLConnection`, `openConnection(`, `java.net.http` or `DefaultHttpDataSource` anywhere: final review L-10) | `DownloadBoundaryTest.theDownloadCodeKeepsItsBoundaries`, `eachRuleCatchesItsWordsAndNamesTheLine`, `ManifestSecurityTest.workManagersDiagnosticsReceiverAndDefaultInitialiserAreRemoved` |
| **S8** | App lock, recents hiding | **N/A until M7** | — | — |
| **S9** | Minimum permissions | **Met, with five new permissions (two of them not named by S9) for Ben to confirm** | Merged: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` (S9 lists these), **plus `RECEIVE_BOOT_COMPLETED` and `WAKE_LOCK`** (WorkManager's own; section 5), plus androidx.core's signature-level receiver permission. No storage permission | `ManifestSecurityTest.theRequestedPermissionsAreTheMilestone4Ones`, `wakeLockIsNoLongerRemovedBecauseWorkManagerHoldsOneWhileForegroundWorkRuns`, `verifyReleaseManifest`, fixture `bad-extra-permission.xml` |
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
| 7 | **The strict sharing rule and the visibility joins** | Sees, plays, inherits; revocation; the 7-day re-ask; offline | `MINE_OR_SHARED` and `visible_items` in every offline read; each guarding clause shown to matter by running the query without it; the re-ask runs on a session change, the server coming back and the app coming to the front (`SharingRecheck`), the item about to play first (L-11), and one failing item no longer stops it (L-1). Every profile whose own sign-in did not fetch the copy (shared to it, inherited, or joined as "already on this phone": `download_owners.fetched`, which a conversion sets only after `facts` confirmed that sign-in's own `CanDownload`: re-review N-1) is under the rule, and one with no confirmed permission sees, counts and plays nothing of it even as an owner, online or offline (re-review L-a): a stale copy is not played, nor its subtitles and skip markers served (L-3), while the server can be reached, and a later "no" removes a non-fetching owner too. While the server can be reached, inheritance needs a confirmation younger than 7 days; one dated ahead of the clock is stale everywhere (L-2) | `OfflineLibraryVisibilityTest` (a–g), `OfflineLibraryClausesTest` (h–m, each with its clause removed), `SharingTest`, `StrictSharingOwnersTest` (inheritance online and offline, re-ask and removal of an inherited and a share-only owner, the fetching owner never re-asked, a conversion run by a non-planning owner asked first with yes, no and unknown, an owner with no confirmed permission refused offline), `OfflineLibraryVisibilityTest.hAnOwnerThatDidNotFetchItSeesNothingWithoutItsOwnConfirmedPermission`, `SavedCopiesTest.aSharedCopyWhoseConfirmationIsOverAWeekOldIsNotPlayedWhileTheServerCanBeReachedUntilReAsked`, `aChildWhoNeverOpensDownloadsLosesTheSharedCopyOnceTheServerSaysNo`, `offlineAConfirmationThatCannotBeReAskedStillPlaysHoweverOld`, `aConfirmationDatedAheadOfTheClockIsNotPlayedWhileTheServerCanBeReached`, `theExtrasOfARefusedStaleCopyAreNotServedFromItWhileTheServerCanBeReached`, `SharingRecheckTest` (each trigger, rate limit, one at a time, the played item first) | Sound after I-1 and M-1; mutation evidence in the Task 17 and final-fix reports |
| 8 | **The removal rules** | Owners only; account and server removal; a 401 | `RemoveOutcome.NotYours` for a non-owner; season and series deletes remove only the profile's own COMPLETE episodes (queued episodes of that series carry on, and the confirm says so), each through the store's check and `DownloadPaths` | `RemovalRulesTest`, `DownloadRepositoryTest.removeGivesEachOutcome`, `anOwnerRemovingASeasonDeletesEachOfItsEpisodesFoldersAndNothingElse`, `DownloadsViewModelTest.anOwnerRemovesItsOwnEpisodesOfASeasonAndNeverAnotherProfiles`, `OfflineSeriesPhoneRenderTest.anOwnerCanDeleteAnEpisodeAndASeasonAndANonOwnerGetsNeither` | Sound |
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

### Whole-milestone review (`final-review.md`) and the controller's walkthrough

| Finding | What | Fixed by |
|---|---|---|
| **M-1** | Inheritance ignored the 7-day rule, and an inherited or "already on this phone" owner left the strict rule for good | **`1c14a46`** (`download_owners.fetched`; v4 amended) |
| **M-2** | An original whose size the server does not state could fill the storage past the reserve | **`998c49c`** |
| **W-1** (walkthrough) | The check compared the file with the item's runtime, not the downloaded source's, so a good download was thrown away | **`31a4abd`** |
| L-1 | One item failing its refresh blocked the whole re-ask | `6da3455` |
| L-2 | Moving the clock back kept a confirmation young | **Partly fixed** by `e23b554`: a confirmation dated ahead of the clock is stale; a clock held back within the week while online is a residual risk (re-review L-b, section 5) |
| L-3 | A refused stale copy still served its subtitles and skip markers | `7462c39` |
| L-4 | A deleted download's pinned pictures stayed until the next start | `ef01e27` |
| L-5 | A removal mid-fetch could leave an empty item folder | `31c69b7` |
| L-6 | The Delete confirm promised to free space for a co-owned copy | `307a3bc` |
| L-7 | A renamed file or a `.part` fetched under another owner's sign-in was kept or resumed | `7d7fa8c` |
| L-8, L-9 | Owned copies never re-checked online; any profile can switch sharing on | Recorded (section 5) |
| L-10 | `DownloadBoundaryTest` gaps | `092e230` |
| L-11 | The item being played might not be among the 50 re-asked | `240ea6b` |
| Nit | "Delete downloaded series" removes only ready episodes | `6ddd6e7` (the confirm says queued ones carry on) |
| Nit | `DownloadStore.add` share-only kept an old permission's age | `1c14a46` |
| Nits | An inherited RUNNING row fails for the heir; a stale shared copy is still listed as ready while online | Carried |
| Carried Tasks 9-10 Minor 6 | The notice counted the whole device's queue | `b197ae6` (no counts, sizes or progress) |

### Re-review of that fix wave (`final-fixes-review.md`) and the controller's second walkthrough

| Finding | What | Fixed by |
|---|---|---|
| **N-1** | Running a conversion made a non-planning owner the permanent "fetcher" with no `CanDownload` check (`/videos/` does not enforce it) | **`c4357ea`** (one `facts` call for that sign-in before a fresh conversion: yes confirms then marks; no drops it, or `NOT_ALLOWED` as the only owner; unknown is `NOT_ALLOWED`) |
| L-a | A non-fetching owner whose answer was unknown still played its copy offline | `bc93a6c` (needs its own confirmed permission to see, count or play it, online or offline; the ownership row stays for a later yes) |
| L-b | A clock held back within the week while online keeps a confirmation young | Recorded (section 5; not fixed by decision) |
| L-c | Three inaccuracies in this document | Corrected in this wave |
| L-d | The re-ask carried on one item at a time through network, timeout and TLS failures | `6e8d951` |
| Nit-1 | The folder check after a run was outside the run's guard | `ad4fcf5` |
| **W-2** (walkthrough) | The Downloads tab's title and settings cog were drawn under the status bar, so the cog could not be tapped | `881f7a5` |

### Minor and Nit

- **Fixed in the close-out wave:**
  - Tasks 13-16: M-1 `ee483ac`, M-2 `d51f4f5`, M-3 `76167ad`, M-6 `55b8db1`, M-7 `e3ace36`, M-8 `845318a`, M-9 `523feb3`, M-12 `b814464`.
  - Tasks 11-12: Minor 2 `97985d7` (security: a copy fetched under another owner's sign-in; that fix covered only a fetcher with no permission row at all, and was completed by `7d7fa8c` for the final review's L-7: the bytes must have come through the fetching sign-in itself, and a `.part` begun under another owner restarts), Minor 7 `99cc9d2`, Nits 1 and 2 `fe52232`.
  - Tasks 1-4 Minor 4 and Tasks 5-8 Minor 10: `d798d98`.
- **Fixed earlier** (in each task's own fix wave): as each later review's status table records.
- **Carried, each with its reason and when:** `docs/reviews/milestone-4-carried.md`. Those that touch security or privacy are listed as residual risks below; none is a data-loss risk.

---

## 5. Residual risks

- **Offline validity of a sharing confirmation (the chosen behaviour).** While the server cannot be reached, a profile's confirmation that cannot be re-asked stays valid however old (and a profile then inherits on it), so a child on a plane still plays a shared copy its account was once allowed to download, even if the server has since said no. It is re-asked as soon as the server can be reached (on a session change, the server coming back, the app coming to the front, or before the copy is played), and a "no" then removes it at once. Offline, a confirmation dated ahead of the clock is not refused either (the same rule).
- **A clock held back within the week while online (final review L-2, partly fixed; re-review L-b; not fixed by decision).** A confirmation dated ahead of the clock is stale. But a child who turns automatic time off and keeps setting the date back to within 7 days of its last confirmation keeps that confirmation young while online, so its shared or inherited copy is never re-asked and a later "no" never reaches it. This needs a server that does not prove the time (the plain-http LAN server or a trust-on-first-use certificate does not stop the clock change itself). A fix would keep a high-water mark of the wall clock, or measure the age by `SystemClock.elapsedRealtime`; it is left for a later milestone.
- **Owned copies are never re-checked online (final review L-8; a decision for Milestone 5).** A copy the profile's own sign-in fetched plays LOCAL while online without asking the server, and its pinned `visible_items` record is re-checked only when a screen refreshes the item, so a later parental-rating change on the server does not hide it. SPEC demands the re-ask only for shared copies; this is recorded, not fixed.
- **Any profile can switch "Share downloads between profiles" on (final review L-9).** A child can turn it on for its server. The strict sharing rule limits what that gains (only titles the child's own account may download, once the server has said so). The fix is Milestone 7's app lock, or an owner-of-server check.
- **Permissions and an exported component awaiting the owner's confirmation.**
  - SPEC S9 lists `FOREGROUND_SERVICE` (+ data-sync type) and `POST_NOTIFICATIONS`. The build also has `FOREGROUND_SERVICE_DATA_SYNC` (the data-sync type itself), and **`RECEIVE_BOOT_COMPLETED`** and **`WAKE_LOCK`**, which WorkManager needs ("survives reboot", and its wake lock while foreground work runs).
  - So **five permissions** are new in this milestone, **two of them not named by S9** (`RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`); **Ben to confirm** them.
  - SPEC S10 says only the launcher is exported; WorkManager's **`SystemJobService`** is exported too, protected by `BIND_JOB_SERVICE` (only the system can bind it). **Ben to confirm.** The SPEC is not edited.
- **Files on a portable SD card are not encrypted** and can be read on another device (the settings warn; tokens, the database and artwork are never on the card). Encryption at rest (Media3 `AesCipherDataSource` with a Keystore key) is for Ben to decide at Milestone 8.
- **Downloads on internal storage** are protected by Android's file-based encryption and the app sandbox only.
- **Media3's extractors parse the downloaded bytes in-process**, during verification and playback, as in streaming (text tracks still pass through unread: Milestone 3 M3).
- **A server can make the app download up to 256 GiB per item** to its own `/Items/{id}/Download` or `/videos/` paths, bounded by the free space above the reserve (1 GiB internal, 256 MiB on a card). The rule is asked before the first byte even when the server states no size, and a `NO_SPACE` cuts the part file to zero (final review M-2). Saved subtitles (at most 30 × 8 MiB) are written with no space check of their own, inside the reserve's slack.
- **The 6-hour `dataSync` limit** (Android 15) pauses a long series plainly ("Open Shoalwatch to carry on"); **Android 16 job quotas** may delay work.
- **Originals are guarded twice, conversions once:** an original needs `CanDownload` (or unknown) AND the server's own `/Download` permission; a conversion through `/videos/` needs `CanDownload` to be a confirmed `true`, at plan time for the planner and again in the queue for the sign-in that runs it (one `facts` call before a fresh conversion when that sign-in is not yet the copy's fetcher: re-review N-1), and is never offered or run when it is unknown. The server is not relied on to refuse `/videos/` to an account without download rights (Task 1 found it does not), so this check is the app's own.
- **A LOCAL play sends nothing to the server until Milestone 5;** `offline_plays` keeps it. Until then a later server change to that item's watched state does not reach the tablet while its offline marker is pending.
- **Carried hardening (Minor, see the carried list):** pinned artwork has no per-profile visibility check at read (Tasks 5-8 Minor 9); the HLS master is not tied to the item's own path (Tasks 5-8 Minor 3); `PlannedItem.confirmed` is caller-settable inside the module (Tasks 11-12 Minor 9); a second profile learns that a title it may download is already on the phone (Tasks 1-4 Minor 7). The device-wide notice counts (Tasks 9-10 Minor 6) are fixed: the notice shows none.

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

- Unit-test gate: see the final-fix report (`.superpowers/sdd/m4/final-fixes-report.md`) for the exact command and result.
- Open before sign-off: the controller's device results (above), and Ben's confirmation of the permissions and the job service (section 5).
- **Uninstall first:** the v4 schema was amended again (`download_owners.fetched`), so an earlier Milestone 4 debug install crashes on open.
