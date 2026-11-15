# Milestone 6 security check (S13)

**Date:** 15/11/2026 · **Branch:** `milestone-6`, code at `1794c72` (the Task 12 fixes on top of `10e1020`) · **Scope:** SPEC §6, S1–S13 as they apply to the Server admin screens (SPEC §3.4). The threat model is a shared family tablet with several Jellyfin profiles (adult administrator and child), a hostile network, a hostile or compromised server, another app on the device, and a lost device.

This check was made by reading the code and the tests, after two task reviews (`.superpowers/sdd/m6/tasks-1-6-review.md`, `tasks-7-11-review.md`) and the Task 12 fix wave. Where the app depends on OkHttp 5's retry behaviour, the claim is proved by a test that fails when the app's own safeguard is removed (see M1 below), not by reading alone.

**Gate run by the Task 12 agent** (no device, no network): `./gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease` and the stand-in server's own tests; results in section 7.

---

## 1. Summary verdict

**Signed off for Milestone 6 on code reading and unit tests, pending the controller's emulator run (section 6) and Ben's server (section 5).** No finding is open at Critical, Important or Medium. Two Minor findings from the first review (M4, M9) and two nits are carried, each with its reason.

- **The gate holds** on every screen: nothing admin is shown, loaded or sent under a session the server has not confirmed as administrator, and the moment the profile changes, the rights go, or a 401 ends the sign-in, every admin screen and everything it loaded is gone.
- **Every action is sent once:** never retried by `ServerApi`, never resent by OkHttp (a dropped kept-alive connection, a 503 `Retry-After: 0`), and never sent at all if the gate closed while it waited for the server's address.
- **Nothing is persisted**, and the recents preview and screenshots never show an admin screen (`FLAG_SECURE`).

| Area | Verdict |
|---|---|
| Who may act (the gate) | **Strong.** Server-confirmed, in memory, active session only; checked by the screen, by the gateway before every call, and again just before each action is written |
| Tokens on the wire, in URLs, logs and errors | **Strong.** Every admin call goes through `ServerApi` (verified address, `Authorization` only). The browser dashboard carries nothing of the sign-in |
| Actions sent once | **Strong.** `retry = false` and a one-shot body on all ten; proved on fresh and pooled connections and against 503 `Retry-After: 0` |
| Untrusted server data | **Good.** Bounded, validated, secret-bearing fields never declared; the policy round-trip changes one key only. Device ids are query values, removable only from a strict set |
| Shared-tablet privacy | **Strong.** `FLAG_SECURE` on every admin screen and dialog; nothing saved; a switch drops everything at once |
| Manifest, permissions, dependencies | **Unchanged.** No new permission, component, `<queries>` or dependency |

---

## 2. S1–S13 (as they apply to Milestone 6)

| ID | Requirement (short) | Status | Evidence | Proved by |
|---|---|---|---|---|
| **S1** | Tokens and headers encrypted at rest | **Met (unchanged)** | No new secret is stored. Admin status is one `(Session, AdminStatus)` in memory (`AdminAccess`), never saved | `AdminAccessTest.aSwitchClearsTheStateAtOnce`, `nothingPrintsAnId`; `AdminBoundaryTest.nothingAdminIsSavedLoggedOrPrinted` |
| **S2** | No backup | **Met (unchanged)** | Nothing new is stored, so there is nothing new to back up | `ManifestSecurityTest`, `verifyReleaseManifest` |
| **S3** | HTTPS by default; http only by consent | **Met** | Every admin call uses the account's own client through `ServerApi`, so `CleartextGuard` applies. The browser dashboard opens the verified address, which is http only for a consented home address (residual risk 4) | `AdminGatewayTest` (real `ServerApi` and `HttpClientFactory`); `BrowserDashboardTest.opensTheWebClientOnTheVerifiedAddress` |
| **S4** | Trust on first use; pins | **Met (unchanged)** | Same client path, so the pin applies to every admin call. The browser does not know the app's pin (expected; section 5) | `AdminGatewayTest` on the shared client path |
| **S5** | Client certificates | **Met (device unproven)** | Same client path. The browser does not have the app's client certificate (expected; section 5) | Milestone 1 tests |
| **S6** | Token only in `Authorization`, only to its server; never in a URL, log, error or `toString()` | **Met** | `ServerApi` only; device ids only as a query value; no `api_key`-like query name; logs by call name and `AppError.Kind`; every admin model and UI state prints `<Type>(<redacted>)`; `AccessToken` is never declared | `AdminGatewayTest` (every row checks `Authorization` and the query names), `AdminGatewayRulesTest.logsHoldNoNameIdPathTextOrToken`, `AdminHostileTest.secretsAndAddressesNeverReachAModel`, `BrowserDashboardTest.nothingOfTheSignInIsPassed`, `AdminBoundaryTest`; on a device `AdminSmokeTest` (Stop carries ben's token in `Authorization`, nothing token-like in the query) |
| **S7** | No third-party hosts | **Met** | Admin calls go to the verified address; the browser opens `{verified address}/web/`, never a server-supplied string | `BrowserDashboardTest.opensTheWebClientOnTheVerifiedAddress`, `keepsTheBasePath` |
| **S8** | App lock, recents hiding | **Met for admin (ahead of M7)** | `FLAG_SECURE` on every admin screen (`SecureWindow` in `AdminScaffold`, every gate state) and dialog (`SecureDialog`). App lock stays Milestone 7 | `AdminBoundaryTest.everyAdminScreenDrawsThroughTheScaffold`, `everyAdminDialogIsASecureDialog`; on a device `AdminSmokeTest` (`secure()` while on the dashboard, not after the switch) |
| **S9** | Minimum permissions | **Met (unchanged)** | No new permission; the browser intent needs none | `verifyReleaseManifest` (in the gate) |
| **S10** | Only the launcher exported | **Met (unchanged)** | No component added. Navigation's implicit route deep links could start `MainActivity` on an admin route; the gate holds (M9, carried) | `verifyReleaseManifest`; `AdminGuardTest.nobodySignedInClosesWithoutAsking`, `notAdminOrUnknownCloses` |
| **S11** | Responses untrusted | **Met** | Section 3, check 5 | `AdminHostileTest`, `AdminMappersTest`, `UserPolicyEditTest`, `AdminGatewayRulesTest.malformedIdsAreNotFoundAndNothingIsSent`, `anUnusableUserAnswerPostsNothing`, `aPolicyAnswerOver256KiBPostsNothing` |
| **S12** | Pinned, checksum-verified dependencies | **Met (unchanged)** | No dependency change; `gradle/verification-metadata.xml` unchanged against `main` | `git diff main -- gradle/` is empty |
| **S13** | Security check each milestone | **Met** | This document | — |

---

## 3. The checks

| # | Check | What was checked | Evidence | Verdict |
|---|---|---|---|---|
| 1 | **The gate: a profile switch** | A switch while a screen is open, while a load is in flight, while a confirmation is open, while an action waits for the address | The guard closes for good on any other active session (even the owner again); state is masked from `frames`; `dropLoaded` clears lists, dialogs and typed text; polls write only while Open; the route pops every admin screen; `AdminWork` cancels a running action; the gateway asks `allows` again just before writing (`sendIf`) | Sound. `AdminGuardTest.aSwitchClosesForGoodEvenIfTheOwnerComesBack`, `DashboardViewModelTest.aSwitchDropsEverythingAndStopsAtOnce`, `anAnswerArrivingAfterTheGateClosedIsNotKept`, `DashboardSessionsTest.switchingAccountWithTheStopDialogOpenDropsEverything`, `AdminNavigationTest.anAccountSwitchPopsTheAdminScreens`, `AdminWorkTest.anActionStillWaitingWhenTheGateClosesIsNeverSent`, `AdminGatewayRulesTest.anActionWhoseGateClosesWhileItWaitsForTheAddressIsNotSent`; device: `AdminSmokeTest` |
| 1 | **The gate: demotion, 401, 403, offline** | A re-check that says no; a 401; a 403 still admin or not; no connection | Re-checked every 60 s while visible and online; a 401/NOT_SIGNED_IN closes and calls `rights.lost`; a 403 closes only if a fresh check confirms; offline is Open with every action off and nothing loading | Sound. `AdminGuardTest.reCheckedEveryMinuteOnlyWhileVisibleAndOnline`, `aReCheckThatSaysNoCloses`, `signInProblemsClose`, `aSignInThatEndedAlsoTakesTheRightsAway`, `aRefusalWhileStillAdminStaysOpen`, `aRefusalThatTheReCheckConfirmsCloses`, `offlineIsOpenButNotLive`; `AdminWorkTest.offlineDoesNotRun` |
| 1 | **The gate: nothing sent as a non-admin** | Every one of the 17 calls without the rights; another session; the administrator check itself | `gated(` opens every call but `me`; FORBIDDEN with zero requests. `AdminAccess` waits for the session it is asked about to be the active one | Sound. `AdminGatewayRulesTest.withoutAdminRightsEveryCallIsRefusedAndNothingIsSent`, `anotherSessionIsRefusedToo`; `AdminAccessTest.aSessionThatIsNotTheActiveOneSendsNothing`, `aCheckForTheNextSessionWaitsForTheSwitchToBeHeard`; `AdminBoundaryTest.everyGatewayCallIsGatedAndEveryActionIsSentOnce`, `adminEndpointsAreNamedOnlyInTheGateway` |
| 2 | **Every action sent once, never retried** | Fresh connection dropped; pooled connection dropped; 503 `Retry-After: 0`; a second tap; a parallel action | `retry = false`, `sendOnce` (one-shot body) on all ten, through the gateway's one `send`; one action at a time per screen; dialogs close before sending | Sound. `AdminGatewayRulesTest.everyActionIsSentExactlyOnceWhenTheConnectionDrops`, `anActionOnAPooledConnectionIsSentOnce`, `anActionAnswered503WithRetryAfterZeroIsNotResent`; `AdminWorkTest.aSecondRunWhileOneRunsIsIgnored`; `PowerViewModelTest.theTypedWordSendsOnceAndSwitchesTheButtonsOff`; playback unchanged: `PlaybackReportPooledTest` |
| 2 | **Confirmation names the target, and the target is re-checked** | A session, device, user or task gone; a session playing something else; restart and shut down | GONE (list refreshed, nothing sent); CHANGED for Pause, Stop and Message when the item differs; typed word, never pre-filled, a blank word never matches | Sound. `DashboardSessionsTest.aSessionThatWentAwayIsNotActedOn`, `aSessionNowPlayingSomethingElseIsNotActedOn`; `DevicesViewModelTest.aDeviceGoneFromTheLatestListIsNotRemoved`; `UsersViewModelTest.aUserAlreadyDisabledIsGone`; `PowerViewModelTest.nothingIsSentWithoutTheTypedWord`; `AdminTextTest.aBlankWordNeverMatches` |
| 2 | **Self-harm** | Disabling the signed-in administrator; removing this phone's device | Never offered by the screens, and refused by the gateway with nothing sent | Sound. `UsersViewModelTest.youAndUnreadAccountsCannotBeAskedAbout`, `DevicesViewModelTest.thisPhoneCannotBeAskedAbout`, `AdminGatewayRulesTest.theSignedInAdministratorsOwnAccountIsNeverDisabled`, `thisPhonesOwnDeviceIsNeverRemoved` |
| 3 | **The policy round-trip** | No field lost or defaulted; required fields; wrong user; size | The server's own `Policy` object, only `IsDisabled` replaced; nothing posted on any refusal; 256 KiB cap; the gate asked again before the POST | Sound. `UserPolicyEditTest.everyKeyComesBackUnchangedExceptIsDisabled`, `theFixtureHoldsEveryReferenceProperty`, `nothingIsPostedWhenTheAnswerIsNotUsable`; `AdminGatewayTest.disablingAUserPostsBackItsOwnPolicy`; `AdminGatewayRulesTest.setUserDisabledSendsNothingIfTheGateClosesBetweenReadAndWrite` |
| 4 | **Device ids** | Path characters; separators; length; this phone | Only ever a query value (OkHttp `addQueryParameter` encodes `/ : = ~ +`); removable only inside `[A-Za-z0-9._~+/=:-]{1,256}`; an over-long id is listed under a `#` digest, never removable, and the screen says why | Sound. `AdminGatewayTest.aDeviceIdWithPathCharactersIsOnlyAQueryValue`, `AdminGatewayRulesTest.malformedIdsAreNotFoundAndNothingIsSent`, `AdminMappersTest.aDeviceIdOutsideTheStrictSetIsListedButNeverRemovable`, `aDeviceIdOverTheLengthCapIsListedButNeverRemovable`, `ActivityDevicesRenderTest.aDeviceThatIsNotRemovableHasNoRemove` |
| 5 | **Untrusted server data** | Ids, sizes, strings, numbers, dates, duplicates, fields never decoded | Lists capped and de-duplicated; `displayText` with a bound on every string; numbers range-checked; `AccessToken`, `RemoteEndPoint`, `LocalAddress`, paths, `IconUrl`, `LongErrorMessage`, `Overview` never declared | Sound. `AdminHostileTest` (all 14), `AdminBoundaryTest.theWireTypesNeverDeclareSecretsOrAddresses` |
| 6 | **`FLAG_SECURE` on every admin screen and dialog** | Screens, a second screen function in a file, raw dialogs or popups, routes | Every `*Screen` through `AdminScaffold`; every dialog `AdminDialog` with `SecureDialog`; every route `AdminRouteEffects`. Mutation-tested (below) | Sound in code; the first frame and the recents preview are device checks. `AdminBoundaryTest`, `AdminPartsRenderTest.closedShowsNothingAtAll`; device: `AdminSmokeTest` |
| 7 | **Nothing persisted** | Room, prefs, files, saved state, nav args, clipboard, notifications | No admin route carries an argument; the source scan bans every storage and printing API in `data/admin` and `ui/admin` | Sound. `AdminBoundaryTest.nothingAdminIsSavedLoggedOrPrinted`; `AdminNavigationTest.everyRouteOpensItsScreen` |
| 8 | **The browser intent** | Address, token, query, fragment, user info, extras, context | `{verified address}/web/` only; nothing of the sign-in; opened from the application context (a new task), never holding the screen | Sound. `BrowserDashboardTest` (all 5), `DashboardViewModelTest.theBrowserDashboardIsOpenedFromTheApplicationNotTheScreen` |
| 9 | **Logs** | Names, ids, paths, message text, tokens | `AppLog.d("Admin", "admin <call> failed: <kind>")` only | Sound. `AdminGatewayRulesTest.logsHoldNoNameIdPathTextOrToken`, `AdminAccessTest.nothingPrintsAnId` |
| 10 | **Merged manifest and files outside the milestone** | Permissions, components, `<queries>`; `git diff main --stat` | Manifest unchanged; shared-file edits as the plan lists, plus two exceptions (below) | `verifyReleaseManifest` in the gate |

**The boundary test catches what the review said it would miss** (`f044a6b`; each rule proved by a deliberate mutation of the real source, then reverted):

| Mutation | Rule that failed |
|---|---|
| `api.post(s, "/System/Restart")` added to `PlaybackGatewayImpl.kt` | `adminEndpointsAreNamedOnlyInTheGateway` (names the file and line) |
| `sendOnce = true` removed from the gateway's POST; `restart` opened with `logged(` instead of `gated(` | `everyGatewayCallIsGatedAndEveryActionIsSentOnce` (both lines named) |
| A raw `AlertDialog(` in `DevicesScreen.kt` | `everyAdminDialogIsASecureDialog` |
| `AdminRouteEffects` removed from `DevicesRoute` | `everyAdminRouteAppliesTheRouteEffects` |

**Files outside the parallel-branch list** (`git diff main --stat`, checked by eye): `data/api/ServerApi.kt` (the additive `sendOnce` and `sendIf` opt-in; every other caller unchanged, `PlaybackReportPooledTest`) and `ui/player/PlayerWindowTest.kt` (a test-only fix of an intermittent failure that exists on `main` too; see section 4, "Also fixed"). `HttpClientFactory`, `AppError`, `strings.xml`, the manifest, the database and `gradle/` are untouched.

---

## 4. Findings

### Critical

None.

### Important (review of Tasks 1–6)

| Finding | Status |
|---|---|
| **I1** — the one-shot body changed playback reports | **Fixed** `8161253`: `sendOnce` is opt-in, passed only by `AdminGateway`. `PlaybackReportPooledTest.aReportOnAStalePooledConnectionIsRecovered`, `aPingAndAStoppedReportAreRecoveredToo` |
| **I2** — the administrator check raced an account switch | **Fixed** `833e206`: the check waits (≤ 5 s) for its session to be heard. `AdminAccessTest.aCheckForTheNextSessionWaitsForTheSwitchToBeHeard`, `ServersAdminEntryTest.withTheRealRightsAnAdministratorSeesItAfterASwitchHeardLate` |
| **I3** — a comma in a device id could remove a second device | **Fixed** `9312bd8`: `REMOVABLE_DEVICE_ID`; refused with nothing sent. `AdminGatewayRulesTest.malformedIdsAreNotFoundAndNothingIsSent` |

### Minor (review of Tasks 1–6)

| Finding | Status |
|---|---|
| **M1** — OkHttp resends on 503 `Retry-After: 0` | **Fixed, proved** `3aeec4b`: OkHttp never follows up a request whose body is one-shot, which every `sendOnce` action already is. `AdminGatewayRulesTest.anActionAnswered503WithRetryAfterZeroIsNotResent` sends 503 then 204: one request each; with the one-shot mark removed (a mutation) it fails ("sendMessage was sent again after a 503 expected:<1> but was:<2>"). The `ServerApi` note that called this a residual risk is corrected |
| **M2** — an action whose gate closed during a wait was still sent | **Fixed** `9a20e7d` (the policy POST) and `9c50579` (all actions: `AdminWork` cancels on close, and the gateway's `sendIf` asks `allows` just before writing). `AdminWorkTest.anActionStillWaitingWhenTheGateClosesIsNeverSent`, `AdminGatewayRulesTest.anActionWhoseGateClosesWhileItWaitsForTheAddressIsNotSent` |
| **M3** — a 401 left the rights in place | **Fixed** `9b507a5`. `AdminGuardTest.aSignInThatEndedAlsoTakesTheRightsAway` |
| **M4** — an UNKNOWN answer on the Servers screen is never asked again | **Carried.** The fix needs `ServerConnections` in `ServersViewModel` and its route wiring, both shared with the parallel branches, for a fail-safe gap (the entry is hidden until the screen is opened again or the session changes). Recorded in ARCHITECTURE "Known limits" |
| **M5** — the dashboard opener held the Activity across a wait | **Fixed** `f8d8335`: only the application context is kept. `DashboardViewModelTest.theBrowserDashboardIsOpenedFromTheApplicationNotTheScreen` |
| **M6** — a late poll answer written after the gate closed | **Fixed** `576fe25`, and the same rule in every later poll. `DashboardViewModelTest.anAnswerArrivingAfterTheGateClosedIsNotKept` |
| **M7** — a merged device kept the older row's name | **Fixed** `1c89e68`. `AdminMappersTest.aMergedDeviceTakesTheNewerRowsName` |
| **M8** — `NotBuiltYet` stand-ins | **Fixed** `215fcb9`; `AdminBoundaryTest` bans the name |
| **M9** — Navigation's implicit route deep links | **Carried.** A test needs the whole `MainActivity` graph under Robolectric; not small. The gate holds by construction: a restored or deep-linked admin route shows "Checking…", then closes and pops for a non-admin, with nothing loaded (`AdminGuardTest.nobodySignedInClosesWithoutAsking`, `notAdminOrUnknownCloses`). A device check in section 5 |

### Minor (review of Tasks 7–11)

| Finding | Status |
|---|---|
| **M-a** — self-harm guards only in the ViewModels | **Fixed** `6c624c9`. `AdminGatewayRulesTest.thisPhonesOwnDeviceIsNeverRemoved`, `theSignedInAdministratorsOwnAccountIsNeverDisabled` |
| **M-b** — the boundary test was narrower than its KDoc | **Fixed** `f044a6b`; four new rules, each mutation-proved (section 3) |
| **M-c** — (M2 remainder) | **Fixed** `9c50579` (above) |
| **M-d** — a blank word would let an empty field confirm | **Fixed** `0401cde`. `AdminTextTest.aBlankWordNeverMatches` |
| **M-e** — a non-removable device gave no reason; an over-long id was not listed | **Fixed** `6ca5d65`: "This device can’t be removed from here. Use the full dashboard in your browser."; an id over 256 characters is listed under a digest, never removable. `AdminMappersTest.aDeviceIdOverTheLengthCapIsListedButNeverRemovable`, `ActivityDevicesRenderTest.aDeviceThatIsNotRemovableHasNoRemove` |
| **M-f** — the smoke test left a different active account | **Fixed** `340318a`: it restores the account that was active and removes what it seeded, also when seeding fails part way. Compiled only |
| **M-g** — Pause, Stop and Message re-checked by session id only | **Fixed** `262c6a7`: the item id (and the title) the dialog named must still be playing, else "What’s playing there has changed, so nothing was sent." `DashboardSessionsTest.aSessionNowPlayingSomethingElseIsNotActedOn` |

### Nits (review of Tasks 7–11)

- Two test indents — **fixed** `1794c72`.
- `AdminGatewayRulesTest` KDoc said `retry = false` marks the body one-shot — **fixed** `3aeec4b`.
- `PowerUiState.canConfirm` is computed but not read by `PowerScreen` — **carried** (harmless; the ViewModel's own `confirm()` check is the one that counts).
- `DashboardViewModelTest.anAnswerArrivingAfterTheGateClosedIsNotKept` reads private fields by reflection — **carried** (brittle on a rename, not wrong).

### Also fixed in Task 12

- **An intermittent unit-test failure** (`PlayerWindowTest.leavingThePlayerPutsTheOrientationBackAndStoppingTheAppPauses`, "expected:<-1> but was:<6>"): test-environment only, not a player defect. An earlier Robolectric test in the same sandbox can end with Compose's process-wide main dispatcher waiting for a looper message that the per-test reset throws away; that dispatcher then never runs another recomposition, so the player never left the composition. `10e1020`'s longer wait could not help (reproduced); `821218c` runs the test on the Compose test rule's own clock. Details in the Task 12 report.

---

## 5. Unproven until Ben's real server

No agent can reach the owner's server; Ben tests once at the very end (with his explicit yes before anything is installed). Each needs a result here. Copied from the plan, with the review additions:

- [ ] **Admin detection:** `GET /Users/Me` on 12.4 returns `Policy.IsAdministrator` for Ben's account (and false for a child profile), and a non-admin gets **403** (not 401, which would sign them out locally) from `RequiresElevation` routes.
- [ ] **Restart:** `POST /System/Restart` (`LocalAccessOrRequiresElevation`) restarts Ben's install (Docker/systemd/Windows service behave differently: under Docker without a restart policy a "restart" is a stop). Shut down only if Ben is willing to start it again by hand.
- [ ] **Per-library scan:** `POST /Items/{libraryId}/Refresh` with `Default` modes scans the whole library (recursively) like the web's "Scan library", without `Recursive`.
- [ ] **Scan progress:** `VirtualFolderInfo.RefreshStatus` values (`Idle` / `Active`?) and `RefreshProgress` null when idle; the "Scan Media Library" task's `Key` is `RefreshLibrary`.
- [ ] **Policy round-trip:** posting back the policy object as read (only `IsDisabled` changed) is accepted by 12.4 and changes nothing else (compare the user's settings in the web dashboard before and after); a disabled user is refused at sign-in and on their next request.
- [ ] **Activity order:** `/System/ActivityLog/Entries` with no `sortBy` returns newest first, and `startIndex` paging is stable while new entries arrive (de-duplication covers overlap).
- [ ] **Devices:** `DELETE /Devices?id=` with one value removes the device and signs it out; how 12.4 lists one device used by two accounts (one row or two).
  - Whether 12.4 binds `DELETE /Devices?id` comma-split (the app never sends a comma either way).
  - Whether the server matches device ids case-sensitively (if not, an id differing from this phone's only in case would be offered for removal; very unlikely with SQLite's default `BINARY` collation).
  - How Ben's real clients' device ids fall against the strict set `[A-Za-z0-9._~+/=:-]` and the 256-character cap (outside either, a device is listed with "can’t be removed from here").
- [ ] **Sessions:** `activeWithinSeconds=960` shows Ben's real clients; `SupportedCommands` contains `DisplayMessage` for clients that show messages, and `TimeoutMs` is honoured; Pause/Stop work on the Android TV and web clients; `TranscodingInfo.TranscodeReasons` names match the 12.4.0 enum; `PlayState.PlayMethod` is set while transcoding; `NowPlayingItem.Id` is present (the Pause/Stop/Message re-check compares it).
- [ ] **Browser dashboard:** `/web/` opens Ben's web client (including behind a reverse proxy base path), and the browser copes with his certificate (a pinned self-signed certificate, custom headers or a client certificate will not be in the browser: expected, recorded).
- [ ] **Response sizes:** `/Sessions` with real `NowPlayingItem` objects stays under the 4 MiB cap with several clients playing.
- [ ] **On a device (from the reviews):** `FLAG_SECURE` from an admin screen's first frame, and the recents preview after a profile switch shows no admin content; an intent with `android-app://androidx.navigation/admin` data under a non-admin session ends on Home with no admin call but `/Users/Me` (M9); TalkBack over every admin control and dialog; font scale 2.0 on phone and tablet.

---

## 6. The controller's emulator results

**TO BE FILLED BY THE CONTROLLER.** Not run by the Task 12 agent (no emulator, no `adb`, no connected tests).

- [ ] On-device suite including `AdminSmokeTest`: _result_
- [ ] Walkthrough against `tools/mockjelly` (default, `--pending-restart`, `--ben-not-admin`, `--admin-forbidden`, `POST /mock/demote`), as listed in Task 12 of the plan: _result_
- [ ] Switch to kid while on Devices; sign out ben while on Users; airplane mode on the dashboard: _result_
- [ ] Recents preview after a switch; font scale 1.0 and 2.0 on phone and tablet; TalkBack: _result_

---

## 7. Status after the check (15/11/2026, branch `milestone-6`)

**Residual risks, even after the fixes above:**

1. **The client gate is a convenience.** A hostile or compromised server can still claim anyone is an administrator; that only shows that account screens the server would refuse anyway.
2. **Between a demotion on the server and the next re-check (≤ 60 s) or 403,** read-only screens can still show what the account could read without elevation (`/Sessions`, `/Users`, `/System/Info` are `DefaultAuthorization`).
3. **An action already sent when the profile changes completes on the server.** Only one still waiting to be written is cancelled; its answer is dropped either way.
4. **The browser dashboard opens the verified address,** which on a plain-http home address is plain http. No credential is sent by the app; the browser's own sign-in then crosses that network. The user chose http.
5. **Disabling a user does not end their current playback** (Jellyfin's behaviour).
6. **The plain-http residual risk is unchanged** from Milestone 2: admin calls to a consented http home address carry the token in the clear on that network.
7. **Other household members' names, viewing and device names are shown to the administrator by design** (SPEC §3.4). They are protected from screenshots and the recents preview by `FLAG_SECURE`, and never stored.

**What is proved, and how:**

- **By code reading (this check and the two reviews):** every finding above.
- **By the Task 12 agent's gate run:** see the Task 12 report (unit count, failures, and the stand-in server's tests).
- **By mutation:** the four new boundary rules; the 503 test (fails without the one-shot mark).
- **Unproven until the controller's emulator run and Ben's server:** sections 5 and 6.
