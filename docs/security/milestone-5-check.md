# Milestone 5 security check (S13)

**Date:** 16/11/2026 · **Branch:** `milestone-5`, after the whole-milestone review's "Fix before merge" wave (section 4) · **Scope:** SPEC §6, S1–S13, for sync-back (the outbox), watch-and-delete, shared downloads, auto-download next and Storage

**DRAFT.** Written by the Task 16 implementer by reading the code and the tests, and from the unit-test gate. Nothing was run on a device or against a real Jellyfin server. Every test named below was checked to exist in the tree; the device rows wait for the controller.

- **Sources read:** the task reports (`.superpowers/sdd/task-*-report.md`), the whole-milestone review (`.superpowers/sdd/m5/milestone-5-final-review.md`: 0 Critical, 2 Important, 10 Minor, 6 Nit) and the code after its fix wave.

## Device results

**Not run yet.** The controller fills this in: the on-device suite (including `SyncSmokeTest`), the walkthrough against `tools/mockjelly` as `ben`, `anna` and `kid` (Task 16 brief), screenshots at font scale 1.0 and 2.0, and the TalkBack pass.

## 1. Summary verdict

- **No new permission, dependency or exported component** (section 3).
- **Safeguarding holds:** a profile releases, deletes, counts and sees in Storage only downloads it may see; "Watched together" writes only into other signed-in profiles' own outboxes, for profiles the server showed the item.
- **The session rule holds:** every outbox row is sent only as its own profile, and nothing after that profile is removed.
- **Both Important findings are fixed** (I1, a 403 no longer drops rows without proof; I2, an old offline position no longer comes back after a restart).
- **Open before any release:** Milestone 2 H1 (free profile switching), now wider (section 5).

## 2. S1–S13 (as they apply to Milestone 5)

| Rule | What it asks | Status | How Milestone 5 meets it | Evidence (tests) |
|---|---|---|---|---|
| **S1** | Tokens and headers encrypted at rest | **Met (unchanged)** | No new secret is stored. The outbox holds ids, kinds, positions, times and counts; `rejected_sign_ins` holds only the account key. The Keystore key needs no unlocked device, so background sync can read the token | `SyncCascadeTest.everyNewTableHoldsARowPerAccount`, `RejectedSignInTest.aRejectedTokenIsNotLoadedAfterARestartAndNoRequestCarriesIt` |
| **S2** | No backup | **Met (unchanged)** | The new tables are in the same Room database, which is excluded; WorkManager's own database holds no input data | `ManifestSecurityTest.backupIsDisabled`, `dataExtractionRulesAreSet` |
| **S3** | HTTPS by default | **Met (unchanged)** | Every sync request goes through `ServerApi` on the account's own client, so `CleartextGuard` applies | `SyncGatewayTest.userDataReadsTheServersCopy` (real `ServerApi`), `SyncIsolationTest.everyRequestCarriesItsOwnRowsAccount` |
| **S4** | Trust on first use; pins | **Met (unchanged)** | The same client and the verified address (`AddressSelector.awaitOnline`); a TLS failure keeps the row and stops the drain | `SyncSenderFailureTest.aConnectionTheUserMustFixLeavesTheRowAndStops` |
| **S5** | Client certificates | **Met (unchanged; device unproven)** | The same client path | Milestone 1 tests |
| **S6** | Token only in `Authorization`; never in a URL, log, error, notification or `toString()` | **Met** | `ServerApi` reads the token live, so a signed-out profile sends nothing; no WorkManager input data; no notification added; every new model and entity prints `<redacted>`; log lines are fixed words plus a kind or class | `SyncIsolationTest.afterRemovingAnAccountNotOneMoreRequestCarriesItsTokenAndTheOtherGoesOn`, `SyncSenderFailureTest.noTokenSendsNothingAtAll`, `SyncSchedulerTest.aChangeKicksAndEnqueuesOneUniqueJobOnAnyNetworkWithNoInputData`, `OutboxStoreTest.nothingHerePrintsAnIdAPositionOrADate`, `SyncGatewayHostileTest.nothingFromTheBodyReachesAMessageOrALogLine`, `SyncBoundaryTest.theSyncCodeKeepsItsBoundaries` (no `Data.Builder` or `setInputData`), `DownloadBoundaryTest` (no `Log.` call outside `AppLog`); on a device `SyncSmokeTest` |
| **S7** | No third-party hosts | **Met (unchanged)** | No new host; no client built outside `HttpClientFactory` | `DownloadBoundaryTest.theDownloadCodeKeepsItsBoundaries` |
| **S8** | App lock, recents hiding | **N/A until M7** | — | — |
| **S9** | Minimum permissions | **Met (unchanged)** | No new permission: the sync job is ordinary background work (`CONNECTED`, not expedited, not foreground); `RECEIVE_BOOT_COMPLETED` for WorkManager's reschedule receiver came in Milestone 4 | `ManifestSecurityTest.theRequestedPermissionsAreTheMilestone4Ones`, `verifyReleaseManifest` (unchanged), section 3 |
| **S10** | App-specific storage; only the launcher exported; immutable `PendingIntent`s | **Met (unchanged)** | No new component; files are deleted only inside a download root, and only once the player lets go | `ManifestSecurityTest.onlyMainActivityIsExported`, `verifyManifestRuleFixtures` (unchanged), `ReleasedFilesTest.aFolderOutsideTheRootIsNeverTouched`, `ReleasedFilesTest.aKeyThatIsOpenIsDeletedOnlyWhenItCloses` |
| **S11** | Responses untrusted | **Met** | User data bounded at 64 KiB, every field nullable, range-checked (count 0..100,000, position, dates 2000..2200); ids validated; a 404 or 403 drops a row only when the server proves it (`/Items` and `/UserViews` for 404; `/UserViews` for 403, final review I1) | `SyncGatewayHostileTest.aBodyOver64KiBIsRefused`, `countsOutOfRangeAreUnknown`, `positionsOutOfRangeAreZero`, `datesThatCannotBeTrustedAreUnknown`, `aWronglyTypedFieldIsAServerError`, `SyncGatewayTest.anIdThatIsNotNormalisedSendsNothing`, `SyncGatewayTest.anItemIsGoneOnlyWhenItIsNotFoundAndTheViewsAnswer`, `SyncSenderFailureTest.forbiddenEverywhereIsDeferredNeverDropped`, `forbiddenWithTheProfileAcceptedIsTheServersAnswerAboutThisItem` |
| **S12** | Pinned, checksum-verified dependencies | **Met (unchanged)** | No dependency added; `gradle/verification-metadata.xml` is byte-for-byte unchanged | Gradle dependency verification (the gate), section 3 |
| **S13** | Security check each milestone | **This document** (draft; device results pending) | — | — |

**Milestone 5's own rules** (plan "Global Constraints"), beyond S1–S13:

| Rule | Evidence (tests) |
|---|---|
| Rows sent only as their own profile; nothing after removal | `SyncIsolationTest` (both tests), `AccountRulesTest.aDrainInFlightWhenTheAccountIsRemovedSendsNothingMore`, `AccountRulesTest.aServerRemovedMidDrainSendsNothingMoreForAnyOfItsProfiles` |
| A retry never counts a play twice; `datePlayed` never sent | `ConflictRuleTest.aPlayRetriedAfterALostReplyResendsTheStoredCountNotServerPlusOne`, `ConflictRuleTest.aPlayWhoseFirstSendLandedIsAlreadyThere`, `OutboxStoreTest.markSendingKeepsTheFirstPlayCountAndDeferBacksOffToSixHours`, `SyncBoundaryTest` (rule 3) |
| Watched is never undone by a sync | `ConflictRuleTest.playedFalseNeverUndoesANewerWatch`, `ConflictRuleTest.theMarginOnBothSides` |
| Durable across a restart and a closed app | `ClosedAfterTickTest.aRowWhoseSendDiedWithTheProcessIsSentOnceByTheJob`, `RejectedSignInTest.aRejectedTokenIsNotLoadedAfterARestartAndNoRequestCarriesIt`, `OfflineRecoveryTest.aRemovalThatReplacedAnOfflinePlaysPositionIsNotUndoneAfterARestart` (I2) |
| Ticks are instant | `PlaystateInstantTest.twentyTicksWhileTheServerIsHeldAllShowAtOnceAndAreSentInOrderAfterwards` |
| A profile releases or counts only what it may see | `ReleaseSafeguardingTest.aProfileWithNoVisibilityRecordGetsNotYoursAndTheSameChoiceAsNoDownload`, `sharingOffAndNotAnOwnerIsNotYours`, `sharingOnButNotCompleteIsNotYours`, `anotherServersDownloadWithTheSameIdIsUntouched` |
| "Watched together" marks only signed-in sharers the server showed the item | `ReleaseSafeguardingTest.togetherNeverMarksAProfileThatWasNotShownTheItem`, `togetherNeverMarksASignedOutProfile`, `togetherNeverMarksAProfileOnAnotherServer`, `togetherNeverMarksAProfileTheServerNeverClearedForTheCopy`, `togetherNeverMarksForACopyThatIsNotComplete` |
| Storage never names another profile's download | `StorageUsageTest.aChildNeverSeesTheAdultsHiddenFilmByTitleSizeOrCountOnlyAsOtherProfilesBytes`, `StorageUsageTest.aChildsDeleteWatchedNeverTouchesTheAdultsDownloads`, `StorageViewModelTest.switchingAccountEmptiesTheScreenAtOnceAndTheOldProfilesTitlesNeverAppear`, `StorageViewModelTest.aConfirmOpenedForOneSessionNeverActsAsAnother` |
| A prompt carries its own session | `WatchedPromptLogicTest.aSessionChangeMidPromptDropsItAndALatePressActsAsNobody`, `WatchedPromptLogicTest.deleteAndTogetherReleaseAsThePromptsOwnSession` |
| Sign-out leaves nothing; a 401 keeps the queue | `AccountRulesTest.signingOutDeletesExactlyThatProfilesRowsMarksSyncStateAndTombstonesAndTheOtherKeepsSending`, `AccountRulesTest.aRejectedSignInKeepsTheRowsAndSendsNothingAndSigningInAgainSendsThemInOrder`, `SyncCascadeTest.removingAnAccountDeletesEveryRowOfItsOwnAndNothingOfAnotherAccount` |
| Migration 4 to 5, explicit, nothing newer than SQLite 3.22 | `MigrationTest.aVersion4DatabaseKeepsEveryRowAndDownloadSettingsGainsDeleteShared`, `MigrationTest.offlinePlaysBecomeOutboxRowsWithAPlainMarkEach`, `MigrationTest.aDatabaseMigratedFromVersion4AndAFreshOneHaveIdenticalSchemas`, `SqlCompatibilityTest` |
| Only `OutboxStore` writes the outbox; screens never reach it | `SyncBoundaryTest.theSyncCodeKeepsItsBoundaries`, `SyncBoundaryTest.eachRuleCatchesItsWordsAndNamesTheLine` |
| `PlaybackController.kt` unchanged; no main file over ~400 lines | `git diff 2022ff7 -- app/src/main/java/app/shoalwatch/data/playback/PlaybackController.kt` is empty; `SourceShapeTest` |

## 3. No new permission, dependency or exported component

Proved against the Milestone 4 + 6 merge base `2022ff7`:

- `git diff --stat 2022ff7 HEAD -- app/src/main/AndroidManifest.xml gradle/verification-metadata.xml gradle/libs.versions.toml app/build.gradle.kts` prints **nothing**: the manifest, the verification metadata, the version catalogue and the app build file are byte-for-byte unchanged.
- So no `<uses-permission>`, no `<service>`, `<receiver>`, `<provider>` or `<activity>`, and no library was added. `verifyReleaseManifest` and `verifyManifestRuleFixtures` run in the gate unchanged.

## 4. Findings (whole-milestone review) and what was done

| Finding | Done |
|---|---|
| **I1** a 403 dropped rows with no proof | Fixed: overruled only when `GET /UserViews` answers as the profile; a 403 or 404 there defers (`SyncSenderFailureTest`, `SyncGatewayTest.profileAnswersAsksTheViewsAndPassesAFailureThrough`) |
| **I2** an old offline position came back after a restart | Fixed: any confirmed PLAYED, POSITION or PLAY row clears the offline plays it covers, and so does a heard online stop (re-review m1) (`OfflineRecoveryTest`) |
| **M2** a stop cut off mid-send waits for the next start | Documented in ARCHITECTURE ("Progress reports and teardown") |
| **M5** the stand-in leaked Night Shift episodes to `kid` by `seasonId` | Fixed in `tools/mockjelly` with its test |
| **M7** auto-download next held after a real outage | Fixed: a return after Offline is never paused (`AutoDownloadNextTest.aReconnectionAfterOfflineIsNeverHeldByThePause`) |
| M1, M9, M10 | Known limits in ARCHITECTURE; residual risks below |
| M3, M4, M6, M8, N1–N6 | Carried (Milestone 8; M4 with Milestone 7) |

## 5. Residual risks

- **Free profile switching (Milestone 2 H1) now also lets a child switch to an adult and release shared downloads or mark them watched.** Still Milestone 7's, and it **must land before any release**.
  - **Related (final review M4):** any signed-in profile, a child included, can change the server-wide "Delete shared downloads" (and "Share downloads between profiles"), so it can move "After everyone" to "After anyone" and then delete an adult's shared download with "Delete watched", without switching. Milestone 7's profile protection should gate both per-server settings.
- **"Watched together"** lets one profile change another profile's watched state on the server, by design (SPEC §3.3), and only for profiles that can see the item.
- **A phone clock set wrong** can make a position win or lose a conflict (watched is never undone either way).
- **The 2-minute margin** (final review M1) also catches the user's own unwatch or removal made within 2 minutes of the server's last-played date, including one this phone's own PLAY just set: it is quietly reverted. Watched is never lost.
- **Offline plays** reach the server's own watched state, count and dates, but not plugins that count only live sessions (Playback Reporting).
- **A lost update** (another device playing the same item between our read and write) can under-count one play.
- **An "After everyone" download can be kept for ever** (final review M9): signed-out owners and owners who lost visibility still count, and "Watched together" cannot mark them. It can still be deleted by hand.
- **An in-player press with auto-download next on shows the episode unwatched until the stop** (final review M10); the count is right.
- **The library cache and the outbox are not encrypted at rest** (as Milestone 2 I1).

## 6. Unproven until Ben's real server (and a real phone)

| Question | The code's path either way | What to look for |
|---|---|---|
| Does `POST /UserItems/{id}/UserData` apply `PlayCount` and `LastPlayedDate` as sent, and leave left-out fields unchanged? | Only intended fields are sent, as absolute values | After an offline watch: played, the count up by one, the real watch time |
| Does `POST /UserPlayedItems/{id}` without `datePlayed` add to the count when repeated? | Used only for season and series ticks | Tick a season twice: counts stay at one |
| Is `LastPlayedDate` moved by playback reports and by marks? | The conflict rule reads it as "the server's last change" | Watch part on the web, then sync an older offline position: the web position stays |
| **Does a playback START set `LastPlayedDate`?** (added by the whole-milestone review) | The I2 fix no longer depends on it, for a removal or for a heard online stop after an offline partial play (the stop now deletes the older offline play itself; re-review m1); the M1 margin's reach depends on it | Start an episode on the web and stop at once: does its last-played time move? |
| How far apart are the phone's and the server's clocks? | A 2-minute margin, the server winning ties | No change synced in the same minute as a web change is lost the wrong way |
| Is `GET /UserItems/{id}/UserData` present for every item kind? | Missing → unknown copy, change sent, no count | Sync works for films, episodes, seasons and series |
| Does a restricted profile get 403 or 404 on another's item through the user-data routes? | Both drop that row (once `/UserViews` answers) and show the server's copy | A child's queued change on a title since restricted disappears without an error |
| Does the server count a timed-out session (an online stop it never heard) as a play? | Queued as a tick, never a play | One watch is one count |
| Do statistics plugins (Playback Reporting) see offline plays? | Not designed to | Record whether Ben uses the plugin |

Only a real phone can prove: WorkManager under OEM battery rules and Doze; process death between "Watched — delete download" and leaving the item; font scale 2.0 with the prompt and Up next on a small phone in landscape; TalkBack order; a real restricted profile with sharing on.
