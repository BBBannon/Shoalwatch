# Milestone 7 security check (S13)

**Date:** 18/11/2026 · **Branch:** `milestone-7`, code at `a0de708` · **Scope:** SPEC §6, S1–S13 as they apply to Milestone 7: profile PINs and protected profiles, the household (grown-up) check, the app lock, recents privacy, the deep-link strip and `taskAffinity`, picture-in-picture, two panes on a tablet, and per-profile autoplay. The threat model is a child on a shared family tablet, a lost phone, another app sending intents, a hostile network or server, and the supply chain.

**Cast (Tasks 11–12) was not built: Ben to confirm Chromecast.** There is no `src/cast` and no `data/cast`, so this check has no cast flavour, no cast dependency list, no cast merged manifest and no cast residual risk.

This check was made by reading the code and the tests, after six task reviews, two re-reviews and the whole-milestone review (all in `.superpowers/sdd/m7/`), the controller's emulator run and walkthrough, and the final fix wave (`587a33b..a0de708`).

**Gate status, stated plainly:**

- **The last full green gate was at `b614bb1`:** 4,576 unit tests, 0 failures, 1 skipped; lint 0 errors.
- **The final fixes `b614bb1..a0de708`** (final review M1, M3–M6, the nit, and walkthrough W7-1) **passed focused test runs only.**
- **The full gate at `a0de708` has NOT been run.** It was started and then stopped by Claude Code because the PC was low on memory.

---

## 1. Summary verdict

**Signed off for Milestone 7, pending the full gate at the final commit (`a0de708`), and pending Ben's real devices (section 6).** No finding is open at Critical or Important. One Minor finding from the final review (M2, "Forgot PIN" on a plain-http server) is recorded as a Decision for Ben and as a residual risk. A few Minors and nits from the task reviews are carried, each listed in section 5.

- **Every path into a protected profile ends on its PIN** (traced in section 3, and walked through on the emulator in section 7): the switcher, sign-in by password, "Sign in again", a restore, a cold start, a return after "Lock after", picture-in-picture, the download notice, a deep-link intent and the recents switcher.
- **The refusals are in the data layer,** not only on screens: `StoreCatalog.setActive` refuses a protected profile, and every household setting refuses without a grown-up's pass.
- **The PIN is never a `String`, never stored, never logged,** and is zeroed after use. The verifier is sealed by the Keystore, so a copied database cannot be tried offline.
- **A lock resets the app to one empty route,** so no dialog, sheet, player or ViewModel of the locked profile survives it.
- **One new permission** (`USE_BIOMETRIC`), no new component, no new dependency.

| Area | Verdict |
|---|---|
| Paths into a protected profile | **Strong.** One rule (`LockGate` rule 3) covers every path that makes a profile active; the store refuses `setActive`; screens cannot call it (`LockBoundaryTest`) |
| PIN handling | **Strong.** `CharArray` only, zeroed in `finally`; PBKDF2-SHA256 100k sealed by `SecretBox`; constant-time compare; counted before checking; waits on the monotonic clock |
| Household check | **Good.** Enforced below the screens on every household setting; a pass is 60 s, for one profile, ended by any change or lock. Residual risks R1 and R2 (section 8) |
| App lock and recents | **Good.** Framework `BiometricPrompt`; cold start always asks; recents hidden on 29–32 and 33+. The OEM launcher and real biometrics are device checks |
| Intents | **Strong.** Data, clip data, selector and extras stripped before anything is drawn; `taskAffinity=""` enforced by a manifest rule |
| Picture-in-picture | **Good.** Entering is leaving; a stop and start inside the window is not a return; the action is an immutable, explicit broadcast to a not-exported run-time receiver |
| Manifest, permissions, dependencies | **One permission added** (`USE_BIOMETRIC`); no component, `<queries>` or dependency added |

---

## 2. S1–S13 (as they apply to Milestone 7)

| ID | Requirement (short) | Status | Evidence | Proved by |
|---|---|---|---|---|
| **S1** | Secrets encrypted at rest (Keystore AES-GCM) | **Met** | The PIN verifier is PBKDF2-HMAC-SHA256 (100,000 iterations, 16-byte salt, 32-byte result), sealed by the existing `SecretBox`. The PIN itself is never stored. The Keystore key still has no user-authentication binding: a decision, because background downloads and sync decrypt tokens while the phone is locked (Milestone 1 check Q9; the SPEC S1 wording is left to Ben) | `PinHasherTest.theOpenedTextIsTheVersionedFormatWithoutThePin`, `aBlobSealedUnderAnotherKeyThrowsAppError`, `matchesComparesInConstantTime`, `theDefaultIsOneHundredThousandIterations`; `ProfileLocksTest.aVerifierFromAnotherKeyIsUnreadableAndCountsNothing`; on a device `KeystoreSecretBoxTest` |
| **S2** | No backup | **Met (unchanged)** | The new `security_prefs` file and the Room v6 tables (`profile_locks`, `profile_playback`) are covered by `allowBackup="false"` and the exclude-all rules, so an old copy with `failures = 0` cannot be restored | `ManifestSecurityTest.backupIsDisabled`, `dataExtractionRulesAreSet`; `MigrationTest.migrate5To6` |
| **S3** | HTTPS by default; http only by consent | **Met (unchanged)** | No new network path. "Forgot PIN" on a server Ben allowed over plain http trusts whatever answers on that network (final review M2: a Decision and residual risk R3) | `ManifestSecurityTest.cleartextIsAllowedAtManifestLevelOnly`; the existing interceptor tests, unchanged |
| **S4** | No user CAs; trust on first use | **Met (unchanged)** | Unchanged. A pinned certificate blocks the M2 spoof | on a device `TrustOnDeviceTest` (unchanged) |
| **S5** | Client certificates from KeyChain | **Met (unchanged)** | `KeyChainCertChooser` only adds `expectReturn()` and, after the final review, `tripDone()`, so the chooser is not "leaving" | `LockGateTest.anExpectedTripDoesNotCountButTheNextOneDoes`, `anExpectedTripThatNeverStartsDoesNotExcuseALaterOne`, `anExcusedTripStillLocksAfterTwoMinutes`, `aPromptThatAnsweredWithoutStoppingTheAppExcusesNoLaterTrip`; `LockBoundaryTest.everyExpectedTripIsEndedWhenItsPromptAnswers` |
| **S6** | Token only in the header; nothing printable | **Met (unchanged)** | No new token use (Cast not built). Every new type redacts `toString` (`LockState.Profile`, `Judged`, `ClearProof`, `LockUiState`, `ProfileLockEntity`, `SettingsUiState`). No `AppLog`/`Log` call in `security/` or `ui/lock` | `LockGateTest.profileStateIsRedactedAndTheWallClockIsNeverRead`; `ProfileLocksTest.toStringNamesNothing`; `LockBoundaryTest.noPinOrPasswordIsAString`, `lockAndSettingsScreensKeepNoPinAnywhereElse` |
| **S7** | No third-party calls | **Met (unchanged)** | The framework `BiometricPrompt`, not `androidx.biometric`. No new dependency | `verifyReleaseManifest`; dependency verification (`gradle/verification-metadata.xml` unchanged) |
| **S8** | App lock on launch and after a timeout; recents hiding | **Met** (was partly met) | `LockGate` rules 1–7; `DeviceAuth` (`BIOMETRIC_WEAK or DEVICE_CREDENTIAL`; API 29 `setDeviceCredentialAllowed`); one "Lock after" on `elapsedRealtime`; a cold start always asks; any lock resets to the `locked` route. Recents: API 33+ `setRecentsScreenshotEnabled(false)`, API 29–32 `FLAG_SECURE`, whenever the app lock is on, a profile is protected, or "Hide in recent apps" is on. Picture-in-picture counts as leaving. Profile PINs go beyond S8 | `LockGateTest` (31, including `withTheAppLockOnItAsksForThePhonesScreenLock`, `oneMinuteLocksAtSixtySecondsAway`, `immediatelyLocksOnAnyLeave`, `theAppLockReturnsAfterTimeAway`, `withoutASecureScreenLockTheAppLockIsTurnedOff`, `theRecentsPreviewIsHiddenForEachOfTheThreeCauses`); `LockBoundaryTest.onlyTheScreenLockPromptsSuccessOpensTheAppLock`, `theLockIsTimedOnlyByTheMonotonicClock`; `RecentsPrivacyTest` (4); `ResetOnLockTest` (8); `CoveredWindowsTest`; `LockStateAtOnceTest`; `PictureInPictureTest.enteringCountsAsLeavingAndExpandingAsReturning`, `aStopAndStartWhileStillInTheSmallWindowIsNotAReturn`; on a device `LockSmokeTest`, `PipSmokeTest` (both passed, section 7) |
| **S9** | Minimum permissions | **Met** | `USE_BIOMETRIC` is added: a normal permission, on S9's list, and in `releaseExpectedPermissions`. Nothing else is added | `ManifestSecurityTest.theRequestedPermissionsAreTheMilestone4OnesAndTheAppLocksPrompt`; `verifyReleaseManifest` |
| **S10** | App storage; only the launcher exported; immutable `PendingIntent`s | **Met** | No new component. The PiP action is an explicit-package, `FLAG_IMMUTABLE` broadcast to a run-time receiver registered `RECEIVER_NOT_EXPORTED`. `taskAffinity=""` on `MainActivity`, enforced by a manifest rule. Launch intents are stripped before `super.onCreate` and in `onNewIntent` | `PictureInPictureTest.oneActionAnImmutableBroadcastToThisPackageOnly`, `theReceiverAnswersOnlyItsOwnAction`, `theReceiverIsRegisteredOnlyWhileAWishExists`; `ManifestSecurityTest.onlyMainActivityIsExported`; `verifyManifestRuleFixtures` (`bad-task-affinity.xml`); `MainActivityIntentTest` (5); `LockBoundaryTest.theLaunchIntentIsCleanedBeforeAnythingIsDrawn` |
| **S11** | Server responses untrusted | **Met** | Two panes and Settings show existing data as before. `ClearProof` needs the server's answer to be exactly the locked account; nothing else is trusted from it. See M2 | `LockBoundaryTest.aClearProofIsMadeOnlyOnTheForgotPinPath`, `aPinIsClearedOnlyWithTheProofOfAPasswordSignIn`, `theLockScreenOffersNoQuickConnect`; `LockViewModelTest` |
| **S12** | Pinned, verified dependencies; release hardening | **Met (unchanged)** | `gradle/` (verification metadata and version catalogue) is unchanged over `9afb637..a0de708`. `app/build.gradle.kts` changes only the manifest rules (`taskAffinity`, `USE_BIOMETRIC`) | `git diff --stat 9afb637..a0de708 -- gradle/` is empty |
| **S13** | A security check each milestone; **Milestone 7 = a full security-team review before the first release** | **Met for the Milestone 7 check; the full security-team review is still owed** | This document and the whole-milestone review. The SPEC's "full security-team review before the first release" has **not** been run; it goes on the to-do list before release | — |

**Closed by this milestone:**

| Earlier finding | Closed by | Proved by |
|---|---|---|
| **S8** (app lock and recents) | `LockGate`, `DeviceAuth`, `RecentsPrivacy` | `LockGateTest`, `LockSmokeTest`, `RecentsPrivacyTest` |
| **H1** (a child can switch into an adult's profile) | `ProfileSwitch`, `LockGate` rule 3, `StoreCatalog.setActive` refusal | `ProfileSwitchTest`, `SwitchPathsTest` (10), `HouseholdEnforcementTest`, `LockBoundaryTest.noScreenChangesTheActiveProfileButThroughTheSwitcher` |
| **Milestone 6 M9** (Navigation's deep links through the exported launcher) | `MainActivity.sanitised` | `MainActivityIntentTest.aNavigationDeepLinkLosesItsData`, `clipDataASelectorAndOtherExtrasAreDropped` |
| **Milestone 5 final review M4 and Milestone 4 L-9** (a child changes "Delete shared downloads" or sharing) | `HouseholdGuard` in `DownloadRepository.setSharing` and `HousekeepingRepository.setSharedDelete` | `HouseholdEnforcementTest.withAKidActiveEveryHouseholdChangeIsRefusedAndNothingChanges`, `afterAGrownUpsPinEveryChangeIsAllowed`, `withNoProtectedProfileNothingIsAsked`; `DownloadSettingsGrownUpTest`; `HouseholdGuardTest` |
| **Milestone 1 Q5** (another app's activity joining the task) | `taskAffinity=""` | `verifyManifestRuleFixtures` (`bad-task-affinity.xml`) |
| **Milestone 2 I3, Milestone 3 I1** (the recents preview shows the last screen) | `RecentsPrivacy` | `RecentsPrivacyTest` |

---

## 3. The checks

### Paths into a protected profile (traced in the final review, then walked through on the emulator)

| Path | What stops it | Holds? |
|---|---|---|
| The switcher (Home's account menu, the Servers rows, "Use another profile") | `ProfileSwitch.switchWithPin`: the PIN is checked, the proof recorded, then `setActive`. No proof is left behind by a failed or empty switch (final review M4) | Yes. `ProfileSwitchTest.aSetThatFailsLeavesNoProof`, `aSwitchToTheProfileAlreadyActiveLeavesNoProof` |
| `ServerCatalog.setActive` from a stale or future screen | `StoreCatalog.setActive` throws `ProfileProtectedException`; a read failure throws too (fails closed); screens cannot reach it | Yes. `LockBoundaryTest.noScreenChangesTheActiveProfileButThroughTheSwitcher` |
| Password sign-in, Quick Connect, "Sign in again", a restore | `LockGate` rule 3 locks at once from the protected set already in hand; the store's answer can only add a lock | Yes. `SwitchPathsTest` |
| Re-adding the account row | `AccountDao.upsert` and `ServerDao.upsert` are `@Upsert`, not REPLACE, so the lock row survives | Yes |
| Signing out, or removing the server (both delete `profile_locks` by cascade) | Signing out a protected profile that is not active needs its own PIN (`requireSignOut`). Removing a server needs the household check plus each protected profile's own PIN (`requireRemoveServer`, final review M5) | Yes. `HouseholdEnforcementTest.removingAServerNeedsTheOwnPinOfEachProtectedProfileOnIt` |
| "Forgot PIN" | `ClearProof` is made only from the server's answer for the locked user's own password, throttled by `ForgotThrottle`. On a plain-http server see M2 | Yes, except M2 |
| A cold start or process death | `relock()`; the splash shows while `Checking`; a saved back stack is not restored under a locked start (final review M3) | Yes. `ResetOnLockTest.aSavedStackIsNotRestoredWhenTheAppStartsLocked`, `aSavedStackIsRestoredWhenTheAppIsOpen` |
| A return after "Lock after" | `onReturned` on `elapsedRealtime` (counts deep sleep, ignores the wall clock). A prompt that answers ends its excused trip (final review M1) | Yes. `LockGateTest.aPromptThatAnsweredWithoutStoppingTheAppExcusesNoLaterTrip` |
| Picture-in-picture: expanding after the lock time | Entering is leaving; a stop and start while still in the window is not a return (Tasks 9–10 C1) | Yes. `PictureInPictureTest.aStopAndStartWhileStillInTheSmallWindowIsNotAReturn`; manual C1 check (section 7) |
| An intent from another app | `MainActivity.sanitised` before `super.onCreate` and in `onNewIntent`: data, clip data, selector and every extra but `open_tab=downloads` dropped | Yes. `MainActivityIntentTest` (5); walkthrough |
| The household check | In the data layer: `StoreCatalog.upsertServer` for a saved server, `AuthenticatorImpl`, `DownloadRepository.setSharing`, `HousekeepingRepository.setSharedDelete`, the `LockPrefs` setters, and `ProfileLocks` protect, change and remove when another protected profile exists. "Active protected" counts only while `LockGate.isOpenFor(active)`. A pass is bound to the profile active when the PIN was typed (final review M6) | Yes. `HouseholdGuardTest.aSwitchWhileThePinIsCheckedRecordsNoPass` |

### The brief's checks

- **PIN handling:**
  - `CharArray` everywhere below the field, zeroed in `finally` (`ProfileLocks`, `HouseholdGuard.confirm`, `ProfileSwitch`, `LockViewModel`, `SwitchFlow`, `SettingsViewModel`).
  - `PBEKeySpec.clearPassword()`; the derived and expected hashes zeroed; `MessageDigest.isEqual`.
  - The field: `OutlinedSecureTextField`, always hidden, digits only, no autofill (`NoAutofill`) and no keyboard learning (`NoKeyboardLearning`); accessibility sees bullets.
  - Every PIN dialog and the grown-up dialog is a `SecureDialog` behind the obscured-touch filter; the Forgot PIN panel uses `SecureWindow`; the lock screen itself is behind the filter too (final review nit, `LockBoundaryTest.theLockScreenIsBehindTheObscuredTouchFilter`).
  - Tests: `PinFieldExposureTest` (3), `NoAutofillTest`, `LockBoundaryTest.everyDialogInTheLockAndSettingsScreensIsSecure`.
- **The waits:**
  - Five free tries, then 30 s doubling to 15 minutes. The count is in Room and written before the verifier is opened; a count that cannot be saved checks nothing.
  - The wait is timed by `elapsedRealtime` from an anchor in memory, so a restart restarts it in full and a clock change does nothing. A 4-digit PIN at 15 minutes a try is about 100 days for the whole space.
  - Tests: `ProfileLocksTest.fiveFreeTriesThenAThirtySecondWaitThenTheRightPinResetsTheCount`, `aRestartStartsTheWaitAgainAndNeverShortensIt`, `theWallClockIsNeverRead`, `whenTheCountCannotBeSavedNoPinIsEverCorrect`; `PinThrottleTest`; `ForgotThrottleTest`.
- **`LockGate`'s seven rules:** implemented as documented in ARCHITECTURE "App lock"; every move into `App` or `Profile` ends the household pass (rule 6); `hideRecents` starts true (rule 7).
- **The reset on lock:** `ResetOnLock` makes the stack one `locked` route; every window site reads `LocalCovered` (`CoveredWindowsTest.windowsLeftOpenAreGoneOnTheFrameTheCoverAppears`, `everyWindowOfTheAppReadsTheCover`).
- **Recents on 29–32 and 33+:** applied in `onCreate` before the first frame; hidden until the settings are known (`RecentsPrivacyTest`).
- **The PiP receiver and `PendingIntent`:** S10 row above.

---

## 4. Manifest, permission and supply chain

| Change | Where | Checked by |
|---|---|---|
| **`android.permission.USE_BIOMETRIC`** (normal, granted at install) for the app lock's prompt | `AndroidManifest.xml`; `releaseExpectedPermissions` in `app/build.gradle.kts` | `verifyReleaseManifest`; `ManifestSecurityTest.theRequestedPermissionsAreTheMilestone4OnesAndTheAppLocksPrompt` |
| `android:taskAffinity=""` on `MainActivity` | manifest; a new rule in `verifyReleaseManifest` | `verifyManifestRuleFixtures` (`bad-task-affinity.xml`) |
| `android:supportsPictureInPicture="true"` on `MainActivity` | manifest | — |
| `android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation"` on `MainActivity` | manifest | `PlayerWindowTest` (a recreation that still happens is not leaving) |
| `tools:ignore="MissingClass"` on the removed `ProfileInstallReceiver` | manifest (lint only) | lint 0 errors at `b614bb1` |
| No new component, `<queries>` or dependency | — | `ManifestSecurityTest.onlyMainActivityIsExported`; `gradle/` unchanged |

**Cast flavour: not built** (Ben to confirm Chromecast), so there is no cast dependency list or merged manifest to record. `PlaybackController.kt` and `ServerStore.kt` are unchanged over `9afb637..a0de708`, and `gradle/verification-metadata.xml` is unchanged.

---

## 5. Findings

Every finding below is from the reviews in `.superpowers/sdd/m7/`. "Fixed" names the commit; "carried" says why.

### Critical

| Finding | Status |
|---|---|
| **Tasks 9–10 C1** — a stop and start while in picture-in-picture reset the lock timer, so expanding hours later skipped the PIN | **Fixed** `1ffe6ed`: `ActivityPip.onActivityStarted` counts a return only outside the window. `PictureInPictureTest.aStopAndStartWhileStillInTheSmallWindowIsNotAReturn`, `aStartInTheSmallWindowSaysSoAndIsNotAReturn`; manual check on the emulator (section 7) |

### Important

| Finding | Status |
|---|---|
| **Tasks 1–2 I1** — a wrong PIN was counted only after the hash, so a failed write gave free guesses | **Fixed** `9b00e56`: counted before checking. `ProfileLocksTest.whenTheCountCannotBeSavedNoPinIsEverCorrect` |
| **Tasks 3–4 I1** — a child could protect their own profile and then pass every household check | **Fixed** `48f9bfd`: protect, change and remove ask the household check when another protected profile exists. The first protection is still free (residual risk R1) |
| **Tasks 3–4 I2** — "the active profile is protected" allowed household changes while that profile was locked | **Fixed** `48f9bfd`: only while `LockGate.isOpenFor` that session |
| **Tasks 3–4 I3** — the gate decided one thread hop after screens saw the new profile | **Fixed** `48f9bfd`: decided at once from the set in hand; `openFor` covers anything not yet judged |
| **Tasks 5–6 I1** — "Forgot PIN" and PIN fields could be filled by a password manager | **Fixed in code** `fda5d6f` (`NoAutofill`, `NoKeyboardLearning`; `NoAutofillTest`). **A device check with a saved credential is still owed** (section 6) |
| **Tasks 5–6 re-review I-A** — a failed read of the saved locks crashed the app through the lock screen | **Fixed** `6a9a023`: `retryingReads` on the screens' copies |
| **Tasks 7–8 I1** — `LockPrefs` writes had no household check below the screen | **Fixed** `2975f48`: the setters are `suspend` and call `HouseholdGuard.require`; `turnOffAppLock` (rule 5) only from `LockParts`. `LockPrefsTest`, `LockBoundaryTest.onlyLockPartsTurnsTheAppLockOffWithoutTheHouseholdCheck` |
| **Tasks 7–8 I2** — `LockSmokeTest` could sign in to another saved server | **Fixed** `aab13cc`: the button inside the test server's card only; assumes no other protected profile |
| **Tasks 9–10 I1** — `PipSmokeTest` would fail at `moveToState(CREATED)` | **Fixed** `aab13cc`: closed with `moveTaskToBack(true)`; passed on the emulator |
| **Re-review N1** — the Tasks 9–10 M1 close fired on every recreation in PiP (dark mode, locale, font) | **Fixed** `fd878d2`: only when this instance held a wish. `PictureInPictureTest.aRecreationInTheSmallWindowDoesNotCloseIt`. Device check: `cmd uimode night yes` in PiP (section 6) |
| **Re-review N2** — saving a lock setting could crash on a failed Room read | **Fixed** `c929044`: "That change wasn't saved". `SettingsViewModelTest.aFailedReadInTheStoresCheckSavesNothingAndSaysSo` |
| **Walkthrough W7-1** — lock screen words black on a near-black background (WCAG 1.4.3) | **Fixed** `672543b`: on the theme's `Surface`. `LockScreenRenderTest.theWordsAreInTheThemesTextColourInTheDarkTheme`, `theProfileLocksWordsAreInTheThemesTextColourInTheDarkTheme`. **A screenshot after the fix was not taken** (the emulator was stopped for low memory) |

### Minor

| Finding | Status |
|---|---|
| **Tasks 1–2 M1** — `clear()` removed protection with no proof | **Fixed** `07a6500`: `clear` takes a `ClearProof` |
| **Tasks 1–2 M2** — `Wrong(0)` and the string `pin_wrong_last` disagreed | **Fixed:** `pin_wrong_last` now says the wait has started |
| **Tasks 1–2 M3** — one mutex per `ProfileLocks` instance | **Fixed** `f2128e9`: exactly one, `LockPartsShapeTest` |
| **Tasks 1–2 M4** — 100,000 iterations not timed on a slow tablet | **Device check** (section 6) |
| **Tasks 3–4 M1** — an excused trip had no length limit | **Fixed** `48f9bfd`: capped at max("Lock after", 2 min). `LockGateTest.anExcusedTripStillLocksAfterTwoMinutes` |
| **Tasks 3–4 M2 / final M4** — a leftover switch proof | **Fixed** `155f05c` (see section 3) |
| **Tasks 3–4 M3 / final M5** — removing a server signed out every protected profile on any grown-up's pass | **Fixed** `7b97c25`. **The choice made:** each protected profile on the server needs its own PIN (unless it is the active, open one); with one such profile its own PIN is asked; with two or more the Servers screen refuses and says to sign them out first, because one pass covers one grown-up. `ServersAndHomeViewModelTest.removingAServerWithAnotherProtectedProfileAsksThatProfilesOwnPin`, `aServerWithTwoOtherProtectedProfilesIsNotRemovedUntilOneIsSignedOut` |
| **Tasks 3–4 M4 / final M6** — the pass bound to whoever was active after the check | **Fixed** `ebf4e33` (see section 3) |
| **Tasks 3–4 M5 / Tasks 5–6 M6** — read failures crashed the gate | **Fixed** `e001db3`, then I-A `6a9a023`: stays locked and says so; retried every 5 s |
| **Tasks 3–4 M6** — test gaps; the API 29–32 recents path only on Robolectric | `SwitchPathsTest` now has 10 cases. **Device check** for API 29–32 `FLAG_SECURE` (section 6) |
| **Tasks 5–6 M1** — a dialog or sheet above the lock screen for a frame | **Fixed** `8580cbe`: `LocalCovered`. `CoveredWindowsTest` |
| **Tasks 5–6 M2** — the lock state one dispatch after `onStart` | **Fixed** `d6d5cfb` (and `af64aef`, `cbae014`, `3ab1d78` for its test): `Main.immediate`. `LockStateAtOnceTest` |
| **Tasks 5–6 M3** — `ClearProof` can be made by any code with an `AccountSummary` | **Partly fixed** `88812eb`: the KDoc corrected and a source scan (`LockBoundaryTest.aClearProofIsMadeOnlyOnTheForgotPinPath`). **Carried:** the factory is still public; a type-level guard would be stronger |
| **Tasks 5–6 M4** — the I3 test proved nothing | **Fixed** `e24a3bb` |
| **Tasks 5–6 M5** — "Forgot PIN" had no local throttle | **Fixed** `3eb4a37` (`ForgotThrottle`). Jellyfin's own lockout can still be reached: a Decision row in the plan, **Ben to choose** |
| **Tasks 5–6 I3 residual** — screens load (and fetch, with the locked profile's own token) under the cover | **Carried:** the gated catalog for ViewModels was not built. Nothing reaches the child: the cover is up, semantics are cleared and the stack is reset |
| **Tasks 5–6 re-review m-1** — the ChangeLog overstated the throttle | **Fixed** (v1.130 says the server may still lock the account) |
| **Re-review m-2** — the Forgot PIN wait says "That password was not accepted" when nothing was sent | **Carried** (wording; the wait is right) |
| **Re-review m-3** — a child can keep the parent in the 15-minute Forgot PIN wait | **Carried** (a denial of service on Forgot PIN only; in the plan's Decision row with M5) |
| **Re-review m-4** — `CoveredWindowsTest`'s scan misses `ExposedDropdownMenu`, tooltips and counts per file | **Carried** (none of those is used today) |
| **Re-review m-5** — no test for a profile change during a failed read; a failure on the very first read leaves the splash with no message | **Carried** (fails closed) |
| **Re-review m-6** — after Forgot PIN, a failed `isProtected` leaves the profile locked until the next read | **Carried** (fails closed; any PIN then gives "not protected", or Forgot PIN again) |
| **Tasks 7–8 M1** — Cancel during a PIN check was overwritten | **Fixed** `2975f48` (a generation per run) |
| **Tasks 7–8 M2** — `noPinOrPasswordIsAString`'s pattern has gaps | **Carried** (no violation today) |
| **Tasks 7–8 M3** — `PlayerCornerOrderTest` relies on reflection | **Fixed** `84ffb09`: a plain traversal-group check beside it |
| **Tasks 7–8 M4** — silent failures in Settings | **Fixed** `2975f48` |
| **Tasks 7–8 M5** — with one account, Settings is reached from Servers, not Home | **Carried** (usability) |
| **Tasks 7–8 M6** — the player's corner group also moves "Skip intro" ahead of Back | **Carried** (judged acceptable: a time-limited action) |
| **Tasks 9–10 M1, M3, M4, M5** — the window showing a page, `inPip` after recreation, `IllegalArgumentException`, the activity wiring | **Fixed** `1ffe6ed` and `24426da` |
| **Tasks 9–10 M2** — mode-change order on an OEM | **No fix needed:** at worst the lock comes one "Lock after" late, never skipped |
| **Tasks 9–10 M6 / re-review N3** — season changes and the right pane's scroll | **Fixed** `21c6daf` then `0ae48ce`: only the chip scrolls to the top. `SeriesTwoPaneRenderTest.aSeasonTheLibraryChoosesKeepsTheHighlightInView` |
| **Final review M1** — a fingerprint unlock left the expected trip pending, excusing a real trip for up to 2 minutes | **Fixed** `7d71fa9`: `tripDone()`. `LockGateTest.aPromptThatAnsweredWithoutStoppingTheAppExcusesNoLaterTrip`, `aPromptThatStoppedTheAppStillExcusesItsOwnTrip`; `LockBoundaryTest.everyExpectedTripIsEndedWhenItsPromptAnswers`. **Device check:** a fingerprint prompt does not stop the activity |
| **Final review M2** — "Forgot PIN" trusts whatever answers on a plain-http address | **Not fixed: recorded** as a Decision for Ben in the plan and as residual risk R3 below |
| **Final review M3** — a restored back stack composed for one frame under a locked cold start | **Fixed** `35ebfda` (see section 3). **Device check:** "Don't keep activities" |

### Nits

- **Fixed:** the lock screen behind the obscured-touch filter (final review, `f7368cc`); `LockGate`'s rule 2 KDoc names the cap (final review); a `false` from `moveTaskToBack` is logged (re-review); a later successful save clears "not saved" (re-review).
- **Carried, harmless:** Tasks 1–2 N1–N4 (key copy until GC, no AAD binding, an unreachable overflow, the seal error's wording); Tasks 3–4 N1–N3 (a `HouseholdCheck` interface, import order, a commit message); Tasks 5–6 N1–N4 (a KDoc, `requestedTab` reading extras from a crafted bundle could crash the app — a denial of service only, no `FLAG_SECURE` on the lock screen's own window, defaulted route parameters); Tasks 5–6 re-review n-1 (a stale KDoc in `LockGateTest`); Tasks 7–8 nits (imports, `remember` keys, a silent no-activity tap); Tasks 9–10 nits (`sourceRect` in the window, the receiver registered without the feature, `hasSystemFeature` on each recomposition, one test name); re-review nits (`turnOffAppLock` pattern misses a method reference; the PiP C1 test uses a recording gate, not a real `LockGate`).

---

## 6. Unproven until Ben's real device

Checked once, at the end, on Ben's phone and tablet, **with his explicit yes before anything is installed**. The code takes a safe path either way; this says which path real use takes. Copied from the plan (Cast rows dropped: not built), plus the review additions.

- [ ] **Picture-in-picture on the swipe home** with gesture navigation on Ben's Android 14+ phone: a small window, no black flash, playback continues; play and pause work; expanding shows the controls; closing the window pauses.
- [ ] **The OEM launcher honours `setRecentsScreenshotEnabled(false)`:** with a profile protected, the app switcher shows a blank card, not the last screen. On an API 29–32 device, `FLAG_SECURE` blanks it (Tasks 3–4 M6).
- [ ] **`BiometricPrompt` accepts Ben's fingerprint, face and PIN,** and falls back to the PIN after failed fingerprints; no loop of prompts with "Straight away".
- [ ] **Android 10 (API 29) app lock path** (`setDeviceCredentialAllowed`): only if Ben has an Android 10 device (no API 29 emulator image).
- [ ] **How long a PIN check takes on Ben's slowest device** (100,000 iterations): under half a second from "Unlock" to Home (Tasks 1–2 M4).
- [ ] **Lock timings with the screen off, Doze and a reboot:** "Lock after 1 minute": back within 50 s, no PIN; after 70 s, PIN; after a reboot, PIN.
- [ ] **`taskAffinity=""` and coming back** from the launcher, a notification or PiP: no second copy of the app in recents; the download notice opens Downloads (behind the lock if due).
- [ ] **`configChanges`:** rotate the player, Home and Settings; fold and unfold if Ben has a foldable; nothing fails to update.
- [ ] **Two panes on Ben's tablet:** landscape two panes; portrait and split screen at half width one column.
- [ ] **TalkBack and font scale 2.0 with the watch-and-delete prompt beside Up next** (Milestone 5 carried): on a small phone in landscape both cards are readable and reachable in order; TalkBack reads the prompt, its buttons, then Up next.
- [ ] **TalkBack on the lock screen and PIN dialogs:** the digits are never read aloud; each answer line is announced.
- [ ] **A real child profile on Ben's server:** from the child's profile, none of the walkthrough's paths reaches the adult's Home without the PIN.
- [ ] **From the reviews and the walkthrough:**
  - the **lock screen in the dark theme** after the W7-1 fix: a screenshot by eye at font scale 1.0 and 2.0;
  - **`adb shell cmd uimode night yes` while in PiP** (re-review N1): the window stays open and expanding after "Lock after" still locks;
  - **"Don't keep activities"** (or `am kill`) on the player of a protected profile, then return (final review M3): the lock, with no start or stop sent for the player;
  - **a fingerprint prompt does not stop the activity** (final review M1): unlock by fingerprint with "Straight away", press Home at once, return: the lock;
  - **no autofill offer** on the Forgot PIN field or any PIN field with a saved Jellyfin credential, and no "save password?" after a PIN (Tasks 5–6 I1);
  - **a sheet left open, then the lock, then return:** the sheet is gone (Tasks 5–6 M1);
  - **TalkBack** over the lock screen, PIN dialogs and Settings.

---

## 7. The controller's emulator results

Run by the controller on 17/11/2026 on an Android 14 emulator (`shoalwatch_test2`, API 34) against `tools/mockjelly` on port 8096, build `587a33b` (before the final fix wave). Profiles: ben (protected, PIN, "Lock after" 1 minute), anna, kid.

- [x] **On-device suite on `587a33b`: 28 of 28 passed,** including the first runs of `LockSmokeTest` and `PipSmokeTest`. (`WelcomeSmokeTest` needs the app uninstalled first.)
- [x] **Manual C1 check, PASS, 17/11 23:06–23:07:** Home while playing entered PiP by itself; screen off and on in PiP gave a stop at 23:06:34 and a start at 23:06:38 while still pinned; expanding at 23:07:45, after "Lock after" (1 minute), showed "ben's profile is protected", and the PIN unlocked.
- [x] **Walkthrough, 23:08–23:52:**
  - **From kid into ben, each ended on the PIN, never on ben's Home:** Home's account menu; the Servers radio; "Sign out ben" (asked ben's own PIN); signing ben in again by password; "Sign in again as ben" after a 401; `am start -d "android-app://androidx.navigation/series/x"` (ignored, kid stayed on kid's Home); the download notice (kid's own Downloads); recents (a blank card when opened from the launcher).
  - **As kid, a grown-up's PIN was asked for:** Connection settings, Remove server, "Lock after", "Protect this profile" (another protected), "Share downloads between profiles". "Hide in recent apps" is forced on with its help line; autoplay has no check (by design).
  - **Wrong PINs:** 4, 3, 2, 1 tries left, then "Too many wrong PINs. Try again in 30 seconds."; the wait survived `am kill`, then the right PIN switched.
  - **"After 1 minute":** screen off 25 s and 31 s, no PIN; away over a minute, PIN. **"Straight away":** screen off 5 s, PIN.
  - **App lock:** with a phone PIN set, turning it on asked the phone's screen lock first; a cold start asked the phone's lock, then ben's PIN; removing the phone's screen lock turned the app lock off ("Set a screen lock on this phone first.").
  - **Autoplay off for kid only:** kid's Up next waited; ben's counted down.
  - **PiP:** Home while playing gave the small window; expanding within the lock time went back to the player.
  - **Tablet (2560x1600 at 320dpi):** landscape two panes on a series and a film; portrait one column.
  - **Font scale 2.0:** Settings wraps cleanly.
  - **Found and fixed:** W7-1, lock screen contrast (`672543b`).
- [ ] **Observation, not reproduced:** the very first "screen off about 30 s" run asked for the PIN early (straight after a PIN switch that followed a return through the download notice). Two repeats did not ask. Possibly the leave time from the Home press before the notice tap was kept. **It fails the safe way: asks early, never late.**
- [ ] **Not run:** Quick Connect for ben from kid (off on the stand-in); "Delete shared downloads" as kid (shown only with sharing on); the client-certificate chooser (no such server on the stand-in); fingerprint (`adb emu finger touch 1`; none enrolled); PiP play, pause and close by hand (taps did not reach the window on the headless emulator; covered by `PipSmokeTest`); split screen at half width; the PIN dialogs at font scale 2.0 by eye (secure windows block screenshots, by design); TalkBack; the lock screen screenshot after W7-1 (emulator stopped for low memory).
- [ ] **Not run on the final commit:** the on-device suite and the full gate at `a0de708`.

---

## 8. Status after the check (18/11/2026, branch `milestone-7`)

**Residual risks, even after the fixes above:**

1. **A child who is told an adult's PIN, or watches it typed, has the adult's profile.**
2. **Quick Connect approved from a device where the adult is signed in** (the family laptop) still signs the tablet in as the adult. The PIN is still asked.
3. **"Forgot PIN" on a plain-http address (final review M2):** on an address Ben allowed over plain http, someone impersonating the server on that Wi-Fi can answer Forgot PIN and clear a PIN. **Ben to confirm, or Forgot PIN could be limited to HTTPS.** HTTPS with a CA certificate or a pinned certificate blocks it; the adult would see their saved sign-in replaced.
4. **Clearing the app's data in Android settings removes every profile and download.** It is no bypass, but the data is not protected either.
5. **On Android 10–12, hiding from recents also blocks screenshots** (`FLAG_SECURE`; the help line says so).
6. **The library cache is still not encrypted at rest** (Milestone 2 I1).
7. **R1 — the first PIN is free.** While no profile is protected, protecting one asks nothing. A child who protects their own profile first holds the household: the adult then needs the child's PIN to protect their own, and the child can change every household setting. **Advice for Ben: protect the adult profiles first.**
8. **R2 — a protected child's profile counts as a grown-up.** If a parent protects a child's profile (for example against siblings), that child can change household settings, turn off the app lock (still needing the phone's own credential) and remove a server (now only with each other protected profile's own PIN). The Settings help text does not say so.
9. **"Forgot PIN" slows but does not stop wrong server passwords.** Jellyfin's own lockout (by default 3 wrong passwords for a user) can still be reached in about 90 s, and a child can keep the 15-minute wait running (re-review m-3). A Decision for Ben.
10. **Screens of a locked profile still load under the cover** and fetch with that profile's own token (Tasks 5–6 I3 residual). Nothing is shown, read out or touchable.

**What is proved, and how:**

- **By code reading:** the six task reviews, two re-reviews and the whole-milestone review, and this check.
- **By unit tests:** the full gate at `b614bb1` (4,576 tests, 0 failures, 1 skipped; lint 0 errors); focused runs for each fix in `b614bb1..a0de708`. **The full gate at `a0de708` is still to run.**
- **By the emulator:** the on-device suite (28 of 28 on `587a33b`), the manual C1 check and the walkthrough (section 7).
- **Unproven until Ben's devices:** section 6.
