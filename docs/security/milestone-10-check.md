# Milestone 10 security check (S13)

**Date:** 20/11/2026 · **Branch:** `milestone-10` · **Scope:** SPEC §6, S1–S13 as they apply to Milestone 10 (roadmap
`docs/plans/2026-11-20-roadmap-replace-jellyfin.md`, plan `docs/plans/2026-11-20-milestone-10-library-management.md`):
multi-select and bulk actions, identify, pictures (online and uploaded), edit details, refresh, delete from the server, and
approving a Quick Connect code. Threat model as SPEC §6, with the roadmap's new risk: a stolen or borrowed unlocked phone signed in
as an administrator can now change and delete media.

This check was made by reading the code and the tests, after the build; no separate review agents were run for this milestone
(see section 5). Results of the gate and the emulator run are in section 4.

---

## 1. Summary verdict

**Not yet signed off: the full gate, the release build and the emulator walkthrough did not complete (section 4).** No finding is open
at Critical or Important. Three known limits are recorded (section 2, S6/S8 rows and section 3).

| Area | Verdict |
|---|---|
| Who may act | **Strong.** Server-confirmed administrator (`AdminRights.allows`) for every management call; for delete, the profile's deletion policy as a hint for showing it and **each item's own `CanDelete` for this user** as the decision, read when Delete is pressed and again just before the DELETE; all held in memory for the active profile only, checked by the screen, by the gateway before every call, and again just before each change is written. Jellyfin's 401 for a refused delete is checked with `GET /Users/Me` and never signs the profile out on its own (review fix, section 6) |
| Changes sent once | **Strong.** Every change `retry = false` + one-shot body (`sendOnce`) + `sendIf`; the upload through `postBytes`, which is always one-shot |
| Third-party hosts (S7) | **Strong.** Online pictures and identify matches are words only; the phone never loads their addresses; the server fetches the chosen one |
| Untrusted server data (S11) | **Good.** Bounded, validated, file paths and third-party addresses never declared (except the one address handed back as a query value); the full-item save replaces typed keys only |
| Uploads | **Good.** Photo picker (no permission), read capped, size read before decoding, decoded upright straight to the target size (`ImageDecoder`), out-of-memory refused as too large, re-encoded as JPEG on the phone (metadata dropped) |
| Manifest, permissions, dependencies | **Unchanged.** No new permission, component, `<queries>` or dependency |

---

## 2. S1–S13 (as they apply to Milestone 10)

| ID | Requirement (short) | Status | Evidence | Proved by |
|---|---|---|---|---|
| **S1** | Secrets encrypted at rest | **Met (unchanged)** | Nothing new stored. The delete right sits beside the admin answer in `AdminAccess`, memory only. Typed codes and edit fields live only in ViewModel `TextFieldState`s, cleared on a profile change | `DeleteRightsTest.aProfileChangeForgetsIt`, `QuickConnectApproveViewModelTest.aProfileChangeClearsTheCodeAndDropsTheAnswer`, `ManageViewModelsTest.aClosedGateDropsTheTypedForm`, `ManageBoundaryTest.nothingIsSavedLoggedOrPrinted` |
| **S2** | No backup | **Met (unchanged)** | Nothing new stored | `verifyReleaseManifest` |
| **S3** | HTTPS by default | **Met** | Every call through `ServerApi` (the account's own client, `CleartextGuard`) | `ManageGatewayTest`, `QuickConnectApproverTest` (real `ServerApi`) |
| **S4** | Pins | **Met (unchanged)** | Same client path | as S3 |
| **S5** | Client certificates | **Met (unchanged)** | Same client path | Milestone 1 tests |
| **S6** | Token only in `Authorization`, only to its server | **Met** | `ServerApi` only. The Quick Connect code and an online picture's address are query values, never logged (`AppLog` by kind only). Every new model prints without content. **Known limit:** a server that turns Quick Connect off between the Enabled check and Authorize answers 401, which signs this profile out on the phone (denial of service only) | `ServerApiPostBytesTest`, `ServerApiVerifyUnauthorisedTest`, `QuickConnectApproverTest`, `ManageBoundaryTest` |
| **S7** | No third-party hosts | **Met** | `RemoteImageInfo.ThumbnailUrl` and `RemoteSearchResult.ImageUrl` are never declared; `RemoteImage.source` is read by no screen and only handed back to the server; Coil has no network module (unchanged), so a screen could not load it even by mistake | `ManageBoundaryTest.noScreenReachesTheWireOrAThirdPartyAddress`, `theWireTypesNeverDeclareAPathOrAThirdPartyPicture`; `ManageGatewayTest.remoteImagesAskForTheKindAndKeepTheAddressOnlyForTheServer`; walkthrough (no request but to the stand-in in its log) |
| **S8** | App lock, recents | **Met** | Management screens are covered by the app lock like every screen; they draw through `AdminScaffold` (`FLAG_SECURE`); their dialogs are secure and read `LocalCovered`; the selection bar's menu reads it. **Residual (roadmap):** with the app lock off, whoever holds an unlocked administrator's phone can delete media; every delete asks first and names the item. Approving a Quick Connect code warns to approve only one's own device and, for a PIN-protected profile, needs the PIN (review fix 9) | `ManageBoundaryTest.everyManagementScreenIsSecureAndOpensNoRawDialog`, `CoveredWindowsTest.everyWindowOfTheAppReadsTheCover`, `QuickConnectApproveViewModelTest.aProtectedProfileMustTypeItsPinBeforeAnythingIsSent` |
| **S9** | Minimum permissions | **Met (unchanged)** | The photo picker (`PickVisualMedia`) needs no permission; no `READ_MEDIA_*` | `verifyReleaseManifest` (in the release build) |
| **S10** | Exported components | **Met (unchanged)** | No component added; the picker is a result contract of the existing activity | `verifyReleaseManifest` |
| **S11** | Responses untrusted | **Met** | Element-tolerant lists with caps; numbers as `JsonElement`, range-checked; display text cleaned; `ImageInfo.Path` never declared; provider ids validated before they are sent back; online addresses kept only as printable http(s) ≤ 2,048; the full-item save copies the server's object and replaces only typed keys made from cleaned fields; a different `Id` posts nothing. Uploads: read ≤ 40 MB, header-only size check (≤ 16,000 px a side, ≤ 120 MP, as shown after EXIF orientation), decoded straight to ≤ 3,840 px on the long side, out-of-memory refused as too large, re-encoded JPEG ≤ 10 MB, checked again in the gateway. A save writes only the keys the user changed (review fix 6) | `ManageGatewayTest.hostileAnswersCostOnlyThemselves`, `imagesAreListedWithoutPathsAndInOrder`, `saveReadsTheItemAgainAndPostsItWholeWithOnlyTheFormReplaced`, `anItemThatIsNotTheOneAskedForIsNeverPostedBack`, `aPictureThatIsNotAPreparedJpegIsNeverSent`; `PickedImagesTest` (every refusal) |
| **S12** | Pinned dependencies | **Met (unchanged)** | No dependency change; `gradle/verification-metadata.xml` unchanged | `git diff main -- gradle/` empty |
| **S13** | Check each milestone | **Met** | This document | — |

**Who may act, in detail.** `ManageGateway`: every call but `canDelete` and `delete` opens with `gated(` (`allows`); those two
check `allowsDelete` (the policy hint), then the item's own `CanDelete` (`ManageGatewayTest.deleteAsksTheItemsOwnCanDeleteForThisUserJustBeforeSending`,
`anItemThisProfileMayNotDeleteIsNeverSentAndSaysSo`); the DELETE is sent with `verifyUnauthorised` (`aRefused401OnTheDeleteDoesNotSignTheProfileOut`);
every change asks the same rule again just before it is written, so a profile switch while a change waited for the server's address
sends nothing (`ManageGatewayTest.aChangeIsNotWrittenWhenTheGateClosedWhileItWaited`, `nothingIsSentWithoutTheAdministrator`,
`withoutTheDeleteRightNothingIsAskedOrSent`). Ids must already be normalised (`anIdNotInTheLibrarysFormIsRefusedBeforeSending`).
Changes are never resent (`aChangeIsSentOnceOnly`, a 503 `Retry-After: 0` included). The screens: management ViewModels close for
good on a profile change (`aProfileChangeClosesTheHubForGood`); a bulk delete asked by one profile is never sent for the next
(`BulkViewModelTest.aDeleteAskedByOneProfileIsNeverSentForTheNext`); Quick Connect approves only while the asking profile is still
active (`nothingIsAuthorisedOnceTheProfileIsNoLongerActive`).

---

## 3. Unproven until Ben's real server

- The picture upload body as base64 text with `Content-Type: image/jpeg` (Jellyfin's code reads base64; the reference says binary).
- `RemoteSearch/Apply` with the typed body (no `ImageUrl`): whether the server then fetches pictures by provider id when
  "Replace all pictures" is on.
- `POST /Items/{id}` with the object from `GET /Items/{id}?userId=` (includes `UserData`; the web client does the same).
- `DELETE /Items/{id}` for a delete-only profile, and the folder-limited case (`EnableContentDeletionFromFolders`); its 401 for an
  item the profile may not delete (the sign-in must stay); `CanDelete` on `GET /Items/{id}?userId=` for an administrator with and
  without the deletion policy.
- Quick Connect `Authorize` answers (404 for an unknown code; 401 when switched off).

---

## 4. Results (20/11/2026)

- **Targeted unit run** (every new test class, the boundary tests, `SourceShapeTest`, `CoveredWindowsTest`, `KeyboardLearningTest`):
  237 tests, 6 failed. All six were test-side or one limit: the remote-picture `limit` (300, now 100), a boundary-test regex that
  matched `wish.sourceRect`, a member count (11, not 12), the search body leaving out a null year (correct; test fixed), and two
  render tests that needed a scroll first. Fixed; **the re-run did not complete.**
- **Stand-in server:** `test_mock_manage.py` 13/13, `test_mock_jellyfin.py` 91/91, `test_mock_admin.py` 28/28.
- **Not run to the end:** the full `testDebugUnitTest assembleDebug`, `assembleRelease` (and `verifyReleaseManifest`), lint,
  `connectedDebugAndroidTest`, and the emulator walkthrough. The Android 14 emulator booted, but Claude Code stopped the Gradle run,
  the emulator and the stand-in server because the PC ran critically low on memory.

## 5. Not done

- No security-team (multi-agent) review was run for this milestone; this check is the author's own reading, backed by the tests.
- No device other than the emulator; nothing was installed on a phone.

---

## 6. Code review fixes (21/11/2026)

A code review found nine defects. Each was fixed test-first (the new tests failed on the old code where they could be compiled
against it: findings 1, 4, 6 and 8 were seen failing, 14 tests in all), then the affected test classes were run.

| # | Finding | Fix | Proved by |
|---|---|---|---|
| 1 | **Critical.** A refused `DELETE /Items/{id}` signed the profile out: Jellyfin answers 401 when `!item.CanDelete(user)` | (a) the item's own `CanDelete` for this user is read when Delete is pressed (only those items offered) and again just before sending; false sends nothing. (b) Being an administrator no longer implies the delete right; the policy is a hint for showing it. (c) `ServerApi.delete(verifyUnauthorised = true)` checks a 401 with `GET /Users/Me` first. (d) The stand-in answers 401 too | `ManageGatewayTest` (5 new delete tests), `ServerApiVerifyUnauthorisedTest`, `DeleteRightsTest.anAdministratorWithoutTheDeletionPolicyIsNotAssumedToMayDelete`, `BulkViewModelTest.onlyItemsTheServerLetsThisProfileDeleteAreOffered`, `ManageViewModelsTest.theHubAsksTheServerFirst…`, `test_mock_manage.py` |
| 2 | Deleting a series or season hid its downloaded episodes without asking | Its episodes are found as Downloads lists them; the question offers to keep them; kept, the series and season records stay | `BulkViewModelTest.deletingASeriesOffersToKeep…`, `deletingASeasonWithoutKeeping…` |
| 3 | A phone-side failure after a server delete skipped the clean-up and reported failure | Each phone-side step runs on its own once the server said yes | `BulkViewModelTest.aPhoneSideFailureAfterTheServerDelete…`, `ManageViewModelsTest.aHubDeleteStillLeaves…` |
| 4 | Uploaded photos came out sideways | `ImageDecoder` (applies EXIF orientation) | `PickedImagesTest.aPhotoTakenSidewaysComesOutTheWayItIsShown` |
| 5 | Out of memory while preparing a picture crashed the app | Decoded straight to the target size, no scaled copy, `OutOfMemoryError` is "too large" | `PickedImagesTest.runningOutOfMemoryIsTooLargeNotACrash`, `theTargetSizeKeepsTheShapeAndNeverGrows` |
| 6 | Saving the edit form unchanged still changed server data (lists split on commas, rating rounded, text trimmed) | Only keys whose typed value differs from the form as shown are written; an untouched save sends nothing; lists are chips | `ManageGatewayTest.savingAnUntouchedFormSendsNothing`, `onlyTheChangedFieldIsWritten…`, `ManageViewModelsTest.listsAreEditedEntryByEntry…`, `ManageRenderTest.editShowsEveryFieldAndTheLocks` |
| 7 | A delete confirmed while a bar action ran was silently dropped | Delete is off while it runs and the question stays | `BulkViewModelTest.aConfirmWhileAnotherActionRunsIsKeptNotDropped` |
| 8 | A re-check that changed only the delete right emitted nothing | The right is part of `AdminState` | `DeleteRightsTest.aRecheckThatChangesOnlyTheDeleteRightIsHeard`, `BulkViewModelTest.aChangeOfTheDeleteRightAloneIsHeard` |
| 9 | Quick Connect approval had no warning and no PIN | Warning on the screen; a PIN-protected profile types its PIN (existing check) first | `QuickConnectApproveViewModelTest` (3 new tests) |

**Known limit (unchanged in kind):** if `GET /Users/Me` cannot be made after a refused delete (offline at that moment), the sign-in
is kept and the delete reported as refused; a token that really died is caught by the next ordinary call.

**Not run here:** the full unit suite, connected tests and the emulator (Ben runs these). See the results line below.

**Results (21/11/2026):** the new tests ran against the old code first: 65 tests, 14 failed, each failure the defect it was written
for. Stand-in: `test_mock_manage.py` 15/15, `test_mock_admin.py` 28/28, `test_mock_jellyfin.py` 91/91. **The run of the affected
classes after the fixes, and `assembleDebug`, did not complete:** Claude Code stopped Gradle because the PC ran critically low on
memory. Not signed off until that run passes.
