# Milestone 7 — Locks, Picture-in-Picture, Tablet, Chromecast and Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On a shared family tablet a profile can be **protected with its own PIN**, so a child can neither switch into an adult's profile nor reach it by any other path (signing in again, the expired-sign-in row, a deep link, the recents preview, a notification, picture-in-picture, the Servers screen), and the household settings that act on other people's downloads ("Share downloads between profiles", "Delete shared downloads", a server's connection settings, removing a server, signing out a protected profile) need a protected profile's PIN. An optional **app lock** asks for the phone's own screen lock (fingerprint, face or PIN) on launch and after a chosen time away, and the app can be **hidden from the recents preview**. Playing video goes into **picture-in-picture** when the user leaves the player, smoothly with gesture navigation on Android 14 and later. Series and film pages use **two panes on a landscape tablet**. A new **Settings** screen holds the app lock, profile protection and **"Play the next episode automatically"** (per profile). **Chromecast** is a separate `cast` build flavour; the default `standard` flavour contains no Google Play services at all, and a build check proves it.

**Architecture:** Protection is enforced in the data layer, not only in screens. `security/ProfileLocks` keeps one sealed PIN verifier per protected profile in Room (schema v6, `profile_locks`): PBKDF2-HMAC-SHA256 over the PIN with a random salt, the result sealed by the existing Keystore `SecretBox`, so the database alone cannot be brute-forced off the phone. `StoreCatalog.setActive` (the only switch path the UI can reach) refuses a protected profile; `ProfileSwitch` is the one way in, and it proves the PIN first. `security/LockGate` owns one `LockState` for the whole app: it locks on a cold start, after the chosen time away (counted on the monotonic clock from the activity stopping or entering picture-in-picture), and **whenever the active profile becomes a protected one by any path not proved by that profile's PIN** (one rule covers sign-in, the expired row and anything added later). While locked, `AppRoot` draws `LockScreen` over a navigation stack that has been reset to an empty `locked` route, so no screen, dialog, player or ViewModel of the locked profile exists. `security/HouseholdGuard` answers "may this household setting change now" (yes when no profile is protected, when the active profile is protected, or for 60 s after a protected profile's PIN) and is checked inside `DownloadRepository.setSharing`, `HousekeepingRepository.setSharedDelete`, `StoreCatalog.upsertServer` (an existing server) and `AuthenticatorImpl.removeServer`/`signOut`. The app lock uses the framework `android.hardware.biometrics.BiometricPrompt` behind a `DeviceAuth` seam (no AndroidX biometric library, which would also need a `FragmentActivity`). Picture-in-picture is driven by `ui/player/PictureInPicture.kt` through a `PipHost` that `MainActivity` implements: `setAutoEnterEnabled` on Android 12+, `onUserLeaveHint` on 10–11, one play/pause `RemoteAction` delivered to a receiver registered at run time as not exported. Chromecast lives behind a `data/cast/Casting` seam in `src/main` (with `NoCasting`); only `src/cast/` names Google Play services, and `verifyStandardHasNoPlayServices` fails the build if the `standard` release classpath contains any `com.google.android.gms`, `com.google.firebase` or `androidx.mediarouter` module.

**Tech Stack:** as Milestone 5 (Kotlin, Compose Material 3, Room, OkHttp, Media3 1.9.2, WorkManager 2.12.0). **`standard` flavour: no new dependency.** New permission: `USE_BIOMETRIC` (already listed in SPEC S9). Manifest attribute changes on `MainActivity` only: `taskAffinity=""`, `supportsPictureInPicture="true"`, `configChanges`. Room schema v6 (two new tables, no table rebuilt). **`cast` flavour only (Tasks 11–12, Ben to confirm):** `com.google.android.gms:play-services-cast-framework` and `androidx.mediarouter:mediarouter`, with their transitive modules, each checksum-pinned.

## Global Constraints

- **Read first:**
  - `docs/SPEC.md`: §2; §3 row 9; §3.3 (the permission rule); §4; §6 (all, especially **S6, S7, S8, S9, S10, S12**); §7 row 7.
  - `docs/ARCHITECTURE.md`, all of it, especially: "Toolchain and build" and "Build and supply-chain security"; "Layer rules"; "Credentials: where they go"; "Data model (servers and accounts)"; "Library cache" (the migration rules); "Downloads seam" and "Housekeeping" (sharing and "Delete shared downloads"); "UI (Task 8)" (secrets in the UI, tapjacking, `SecureWindow`); "Browsing screens (Milestone 2)" ("One rule for every screen: the session"); "Server admin" (`FLAG_SECURE`, the gate, deep-link note M9); "Player engine and controller" (`autoplayNext`, the host stopping); "Player screen".
  - `docs/security/milestone-1-check.md` (Q5: `taskAffinity`; Q9: StrongBox and "user-auth binding"), `milestone-2-check.md` (**H1** free profile switching; I3 recents), `milestone-3-check.md` (I1 recents; the session rule on every exit path), `milestone-4-check.md` (L-9: any profile can switch sharing on), `milestone-5-check.md` (residual risk: a child switching to an adult can release shared downloads; **final review M4**: any profile can change "Delete shared downloads"), `milestone-6-check.md` (**M9** Navigation's implicit route deep links).
  - `research/android-client-flaws-research.md` rows 7 (Chromecast) and 8 (**PiP broken on Android 14+ gesture navigation**, official #1266).
  - The Milestone 3 plan's "Decisions" (activity-scoped `MediaSession`, no media service, pause when the app is left) and the Milestone 5 plan's Task 12 (the watch-and-delete prompt beside Up next).
  - `TODO.md`: "Protect a profile so a child cannot switch into it", "Add a setting to turn off playing the next episode by itself", the Milestone 7 item, and the Milestone 4 "Ben to confirm" (4): sharing behind the app lock.
- **Base:** `main` after Milestone 5 merges (code today: the `milestone-5` branch). Every file and class named below exists there unless the task says Create.
- Package root `app.shoalwatch`; `minSdk 29`, `compileSdk`/`targetSdk 35`. Build with `JAVA_HOME=C:\Users\Ben\Android\jdk17\jdk-17.0.20.1+1`.
  - **Tasks 1–10** verify with `./gradlew.bat clean testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease`; `gradle/verification-metadata.xml` must not change in them (if it does, stop and find out why).
  - **From Task 11** (flavours exist) the gate is `./gradlew.bat clean testStandardDebugUnitTest testCastDebugUnitTest assembleStandardDebug assembleStandardDebugAndroidTest assembleStandardRelease assembleCastDebug assembleCastRelease verifyStandardHasNoPlayServices verifyManifestRuleFixtures`.
- **Security rules that bind every task** (all Milestone 1–6 rules still hold):
  - **Safeguarding first.** A protected profile is reachable only by its own PIN (or, if forgotten, its server password typed in the lock screen). No path may make it active, or show anything of it, without that: not a switch from Home or Servers, a sign-in (password or Quick Connect), "Sign in again" on an expired row, an intent's data (Navigation's implicit `android-app://androidx.navigation/…` deep links), a notification tap, the recents preview, picture-in-picture, or a process restart.
  - **PINs and passwords:** held only as `CharArray` or Compose `TextFieldState`, filled with zeros (arrays) or cleared (fields) as soon as they are checked; never in a `String`, `UiState`, `SavedStateHandle`, navigation argument, log line, exception message, `toString()` or Room column (only the sealed verifier). PIN fields are `NumberPassword`, obfuscated, with the same semantics masking as the sign-in password (`ARCHITECTURE` "UI (Task 8)"). `FLAG_SECURE` on every PIN and password dialog (`SecureDialog`) and on the lock screen's "Forgot PIN" panel.
  - **Tokens:** unchanged rules: only in the `Authorization` header, only to a verified address. **The single, named exception** is the `cast` flavour's `CastStreamUrl`, which gives the Chromecast a stream URL carrying `api_key` (Task 12; Ben to confirm). `CastBoundaryTest` keeps `api_key` out of every other file.
  - **Clients:** only `HttpClientFactory` builds OkHttp clients; nothing here adds a client.
  - **Never logged:** a PIN, a PIN length, a profile id, user name, server id, title, URL, route name or device name. `AppLog` lines are fixed words plus an `AppError.Kind` or an exception class. Every new type holding a `Session`, name or verifier overrides `toString()` to `"<Type>(<redacted>)"`.
  - **Components and permissions:** no new exported component; the picture-in-picture action receiver is registered at run time with `ContextCompat.RECEIVER_NOT_EXPORTED` and reached only by an explicit, `FLAG_IMMUTABLE` `PendingIntent` with `setPackage(packageName)`. The only new permission is `USE_BIOMETRIC`; `verifyReleaseManifest` and `verifyManifestRuleFixtures` are updated for it in Task 4 and must pass.
  - **S7:** the `standard` flavour contacts nothing new. The `cast` flavour's Play services and the Chromecast's receiver page are Google's (Ben to confirm).
- **Data rules:**
  - Room schema v6 with an explicit `Migration5To6` that only creates two tables (no `ALTER TABLE`, no rebuild); exported `6.json`; tested in `MigrationTest` like `Migration4To5`; `SqlCompatibilityTest` scans the new DAO and migration (no SQL newer than SQLite 3.22).
  - Foreign keys to `accounts` `ON DELETE CASCADE`, so a removed account takes its lock and settings with it. `@Upsert` or `INSERT OR IGNORE` + `UPDATE`, never `REPLACE`.
  - Device lock settings in the private SharedPreferences file `security_prefs` (excluded from backup like every preferences file); the picture-in-picture setting in the existing `playback_prefs`.
- **Size rules:** no main file over ~400 lines (`SourceShapeTest`). **`PlaybackController.kt` (399 lines) is not edited.** `ServerStore.kt` (394 lines) is not edited. `AppGraph.kt` (360 lines) gains at most 8 lines: the new wiring lives in a new `LockParts.kt` (as `HousekeepingParts.kt`). `ServerEditViewModel.kt` (379 lines) is not edited: its gate is at the route.
- TDD with a behavioural RED first.
  - Storage: in-memory Room; `SecretBox` with a plain AES key (as `SecretBoxTest`); `PinHasher` with 1,000 iterations in tests.
  - Time: virtual time (`runTest`, `StandardTestDispatcher`) and a fake `MonotonicClock` for every timeout and wait.
  - Seams: fakes (`FakeProfileLocks`, `FakeHousehold`, `FakeSwitcher`, `FakeDeviceAuth`, `FakeLockPrefs`, `FakePipHost`, `FakeCasting` in `ui/lock/LockFakes.kt` and `ui/cast/CastFakes.kt`, plus the existing `FakeCatalog`, `FakeAuthenticator`, `FakeConnections`, `FakeDownloads`, `FakeHousekeeping`).
  - Screens: Robolectric + Compose test through `RenderBase` (phone `w360dp-h640dp`, tablet `w840dp-h1200dp`, and from Task 10 landscape tablet `w1200dp-h840dp`; font scale 1.0 and 2.0; the player 640x360 and 1200x840).
  - Every control has a TalkBack label naming its target, a 48dp target, and works at font scale 2.0; no text under 13sp.
- UK English, plain words. Every user-facing word in `res/values/strings_lock.xml` (Tasks 2–8), `res/values/strings.xml` (Tasks 7, 9, 10: autoplay, picture-in-picture, tablet) or `res/values/strings_cast.xml` (Tasks 11–12), exactly as written in "Strings" below.
- **Commits:** conventional, each ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. One ChangeLog line per user-visible change (`vX.Y — DD/MM/YYYY — plain English`, minor bump from the top line at the time of committing). Keep ARCHITECTURE.md accurate. **Never edit `docs/SPEC.md`.** **Never start an emulator, run connected tests or touch a device — the controller does that. Nothing is installed on Ben's phone without his explicit yes.** Never contact any real Jellyfin server; only MockWebServer and `tools/mockjelly` (which needs no change in this milestone).

---

## File structure (new and changed)

```
app/src/main/AndroidManifest.xml               (modify: USE_BIOMETRIC; MainActivity taskAffinity="", supportsPictureInPicture, configChanges)
app/build.gradle.kts                           (modify: Task 4 the permission and the taskAffinity rule; Task 11 flavours, per-flavour manifest checks,
                                                verifyStandardHasNoPlayServices)
app/src/main/java/app/shoalwatch/
├── AppGraph.kt                                (modify: lockParts; StoreCatalog/AuthenticatorImpl get the guard; playbackController's autoplay; casting)
├── LockParts.kt                               (new: ProfileLocks, ProvedSwitches, ProfileSwitch, HouseholdGuard, LockPrefs, LockGate, DeviceAuth, ProfilePlayback)
├── ShoalwatchApp.kt                           (modify: lockParts.lockGate.start())
├── data/db/
│   ├── AppDatabase.kt                         (modify: v6, two entities, ProfileDao, Migration5To6)
│   ├── Migration5To6.kt                       (new)
│   └── profile/ProfileLockEntity.kt, ProfilePlaybackEntity.kt, ProfileDao.kt   (new)
├── data/servers/
│   ├── Seams.kt                               (modify: StoreCatalog(store, isProtected, household); AuthenticatorImpl(repository, household))
│   └── ProfileSwitch.kt                       (new: ProfileSwitcher, SwitchResult, ProfileSwitch, ProvedSwitches, ProfileProtectedException)
├── data/downloads/DownloadRepository.kt, HousekeepingRepository.kt   (modify: one guard line each)
├── data/playback/ProfilePlayback.kt, PipPrefs.kt   (new)
├── data/cast/Casting.kt, CastBlock.kt, CastReports.kt   (new, Tasks 11–12: the seam, NoCasting, which servers can cast, the reports)
├── security/
│   ├── PinHasher.kt, PinRules.kt (+ PinThrottle), PinCheck.kt, ProfileLocks.kt (+ ProfileProtection)   (new)
│   ├── HouseholdGuard.kt (+ Household, HouseholdLockedException)   (new)
│   ├── LockPrefs.kt (+ LockSettings, LockAfter), LockGate.kt (+ LockState), DeviceAuth.kt (+ FrameworkDeviceAuth)   (new)
└── ui/
    ├── MainActivity.kt                        (modify: sanitised intents, lock lifecycle, recents privacy, PipHost)
    ├── RecentsPrivacy.kt                      (new)
    ├── components/SecureWindow.kt             (modify: a holder for recents privacy; KDoc)
    ├── nav/AppNav.kt                          (modify: Routes.LOCKED, Routes.SETTINGS, Routes.CAST; ResetOnLock; the lock overlay in AppRoot)
    ├── nav/Routes.kt, BrowseRoutes.kt         (modify: switcher, household, settings and cast wiring; PlayerRoute's autoplay and PiP)
    ├── lock/                                  (new) LockScreen.kt, LockViewModel.kt, PinField.kt, PinDialog.kt, GrownUpDialog.kt,
    │                                          ForgotPinPanel.kt, SwitchFlow.kt (+ SwitchPrompt), LockText.kt
    ├── settings/SettingsScreen.kt, SettingsViewModel.kt, SettingsRows.kt   (new)
    ├── home/HomeViewModel.kt, HomeTopBar.kt, HomeScreen.kt   (modify: switch through the switcher; "Settings" in the account menu)
    ├── servers/ServersViewModel.kt, ServersScreen.kt   (modify: switcher, protected badge, grown-up check, "Settings")
    ├── downloads/DownloadSettingsViewModel.kt, DownloadSettingsScreen.kt   (modify: grown-up check on the two household settings)
    ├── player/PictureInPicture.kt (+ PipHost, PipWish, PipActionReceiver)   (new); PlayerScreen.kt, PlayerControls.kt, PlayerViewModel.kt (modify)
    ├── series/SeriesParts.kt, SeriesTwoPane.kt   (new; SeriesScreen.kt modify); detail/DetailTwoPane.kt (new; DetailScreen.kt modify)
    ├── components/Layout.kt                   (modify: TwoPaneWidth, isTwoPane())
    └── cast/CastScreen.kt, CastViewModel.kt, CastDeviceSheet.kt   (new, Task 12)
app/src/standard/java/app/shoalwatch/cast/CastingFactory.kt              (new, Task 11)
app/src/cast/AndroidManifest.xml, app/src/cast/java/app/shoalwatch/cast/ (new, Tasks 11–12) CastingFactory.kt, CastOptionsProvider.kt,
                                                GoogleCasting.kt, CastRemote.kt, CastStreamUrl.kt, CastProfile.kt
app/src/main/res/values/strings_lock.xml, strings_cast.xml (new); strings.xml (modify); res/drawable/ic_pip_play.xml, ic_pip_pause.xml (new)
app/schemas/app.shoalwatch.data.db.AppDatabase/6.json                     (new, exported by the build)
app/src/test/manifest-fixtures/ok.xml (modify), bad-task-affinity.xml (new)
app/src/test/java/app/shoalwatch/LockBoundaryTest.kt, CastBoundaryTest.kt (new); SourceShapeTest.kt (modify, Task 11: scan every source set)
app/src/test/java/app/shoalwatch/security/   PinHasherTest, PinThrottleTest, ProfileLocksTest, HouseholdGuardTest, LockGateTest, LockPrefsTest
app/src/test/java/app/shoalwatch/data/       ProfileSwitchTest, SwitchPathsTest, HouseholdEnforcementTest; data/db/ProfileDaoTest; MigrationTest (modify)
app/src/test/java/app/shoalwatch/ui/lock/    LockFakes, LockViewModelTest, LockScreenRenderTest, PinFieldExposureTest, SwitchFlowTest,
                                             GrownUpDialogRenderTest, ResetOnLockTest, MainActivityIntentTest
app/src/test/java/app/shoalwatch/ui/settings/SettingsViewModelTest, SettingsRenderTest
app/src/test/java/app/shoalwatch/ui/player/  PictureInPictureTest, PipModeRenderTest, PlayerCornerOrderTest
app/src/test/java/app/shoalwatch/ui/series/SeriesTwoPaneRenderTest; ui/detail/DetailTwoPaneRenderTest
app/src/test/java/app/shoalwatch/ui/cast/    CastFakes, CastViewModelTest, CastRenderTest; data/cast/CastBlockTest, CastReportsTest
app/src/testCast/java/app/shoalwatch/cast/   CastStreamUrlTest, GoogleCastingLogicTest (Task 12)
app/src/androidTest/java/app/shoalwatch/ui/  LockSmokeTest.kt (Task 8), PipSmokeTest.kt (Task 9)
gradle/libs.versions.toml, gradle/verification-metadata.xml              (modify, Task 11 only)
docs/security/milestone-7-check.md                                       (new, Task 13)
```

## Build groups (each pair is built by one implementer, in this order)

| Group | Tasks | Why together | Needs first |
|---|---|---|---|
| **A** | 1 Room v6 · 2 PIN verifier and `ProfileLocks` | One storage boundary; Task 2 is the only user of Task 1's DAO for locks | — |
| **B** | 3 Switching and household enforcement · 4 App lock core (`LockGate`, `DeviceAuth`, prefs, permission) | Both are the data-layer gates the UI will drive; `LockGate` consumes `ProvedSwitches` from Task 3 | A |
| **C** | 5 Lock screen, reset on lock, deep links, `taskAffinity` · 6 PIN in the switch flows, grown-up check | Both are the lock UI; they share `PinField`, `PinDialog` and `LockText` | B |
| **D** | 7 Settings screen and autoplay · 8 Lock smoke test and the prompt's accessibility checks | Task 8 drives the Settings screen built in 7 | C |
| **E** | 9 Picture-in-picture · 10 Tablet two-pane layouts | Both are window-size work on the UI; independent of the Cast flavour | C (MainActivity edits in 4–5) |
| **F** | 11 The `cast` flavour scaffold and its boundary · 12 Casting | **Ben to confirm Cast at all.** If he says no, skip F: nothing else depends on it | E |
| — | 13 Milestone close (controller) | | all |

---

### Task 1: Room v6 — profile locks and per-profile playback settings

**Files:**
- Create: `data/db/profile/ProfileLockEntity.kt`, `data/db/profile/ProfilePlaybackEntity.kt`, `data/db/profile/ProfileDao.kt`, `data/db/Migration5To6.kt`.
- Modify: `data/db/AppDatabase.kt` (version 6, the two entities, `profileDao()`, `Migration5To6` in `addMigrations`).
- Test: `data/db/ProfileDaoTest.kt`; `data/db/MigrationTest.kt` (new case `migrate5To6`); `data/db/SqlCompatibilityTest.kt` (scan list gains the two new files).

**Interfaces — Consumes:** `AccountEntity` (`accounts`, primary key `(serverId, userId)`).

**Produces:**
```kotlin
// data/db/profile/ProfileLockEntity.kt
/** One protected profile. [verifier] is `PinHasher`'s sealed blob (never the PIN). [failures]: wrong PINs since the last right one. */
@Entity(
    tableName = "profile_locks",
    primaryKeys = ["serverId", "userId"],
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["serverId", "userId"], childColumns = ["serverId", "userId"], onDelete = ForeignKey.CASCADE)],
)
class ProfileLockEntity(val serverId: String, val userId: String, val verifier: ByteArray, val failures: Int) {
    override fun toString() = "ProfileLockEntity(<redacted>)"
}

// data/db/profile/ProfilePlaybackEntity.kt
@Entity(tableName = "profile_playback", primaryKeys = ["serverId", "userId"], foreignKeys = [/* as above, CASCADE */])
class ProfilePlaybackEntity(val serverId: String, val userId: String, val autoplayNext: Boolean) {
    override fun toString() = "ProfilePlaybackEntity(<redacted>)"
}

// data/db/profile/ProfileDao.kt
@Dao interface ProfileDao {
    @Query("SELECT serverId, userId FROM profile_locks") fun observeProtected(): Flow<List<ProfileKeyRow>>
    @Query("SELECT * FROM profile_locks WHERE serverId = :serverId AND userId = :userId") suspend fun lock(serverId: String, userId: String): ProfileLockEntity?
    @Query("INSERT OR IGNORE INTO profile_locks (serverId, userId, verifier, failures) VALUES (:serverId, :userId, :verifier, 0)")
    suspend fun insertLock(serverId: String, userId: String, verifier: ByteArray): Long          // -1 when already protected
    @Query("UPDATE profile_locks SET verifier = :verifier, failures = 0 WHERE serverId = :serverId AND userId = :userId")
    suspend fun replaceVerifier(serverId: String, userId: String, verifier: ByteArray): Int
    @Query("UPDATE profile_locks SET failures = :failures WHERE serverId = :serverId AND userId = :userId")
    suspend fun setFailures(serverId: String, userId: String, failures: Int): Int
    @Query("DELETE FROM profile_locks WHERE serverId = :serverId AND userId = :userId") suspend fun deleteLock(serverId: String, userId: String): Int
    @Query("SELECT autoplayNext FROM profile_playback WHERE serverId = :serverId AND userId = :userId") fun observeAutoplay(serverId: String, userId: String): Flow<Boolean?>
    @Upsert suspend fun upsertPlayback(row: ProfilePlaybackEntity)
}
data class ProfileKeyRow(val serverId: String, val userId: String) { override fun toString() = "ProfileKeyRow(<redacted>)" }
```
`Migration5To6`: exactly the two `CREATE TABLE IF NOT EXISTS` statements Room generates for these entities (copy them from the exported `6.json`, as `Migration4To5` did), plus nothing else.

- [ ] **Tests first — `ProfileDaoTest`** (in-memory Room, two accounts A and B seeded through `AccountDao`):
  - `insertLock` twice for A returns a row id then -1, and the first verifier stays;
  - `observeProtected` emits `[]`, then `[A]` after the insert, then `[]` after `deleteLock`;
  - `setFailures` then `replaceVerifier` leaves `failures` 0;
  - deleting account A (the `AccountDao` delete) removes A's lock and A's playback row, and leaves B's;
  - `observeAutoplay` is `null` before any upsert, then `false`, then `true` after a second upsert (no duplicate row);
  - `ProfileLockEntity.toString()` contains neither id nor verifier bytes.
- [ ] **Tests first — `MigrationTest.migrate5To6`:** a v5 database with one account migrates; `sqlite_master` matches Room's v6 schema exactly; the account and its outbox rows are untouched; both new tables are empty.
- [ ] Implement; add the two files to `SqlCompatibilityTest`'s list.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.db.*"` → pass; `6.json` is exported and committed.
- [ ] Commit: `feat(db): schema v6 — profile locks and per-profile playback settings, with a tested migration`.

---

### Task 2: The PIN verifier and `ProfileLocks` (protect, check, change, remove, waits)

**Files:**
- Create: `security/PinRules.kt` (`PinRules`, `PinThrottle`), `security/PinCheck.kt`, `security/PinHasher.kt`, `security/ProfileLocks.kt` (+ `ProfileProtection`).
- Create: `res/values/strings_lock.xml` (the "PIN" group only, Task 2 rows of "Strings").
- Test: `security/PinHasherTest.kt`, `security/PinThrottleTest.kt`, `security/ProfileLocksTest.kt`.

**Interfaces — Consumes:** `SecretBox.seal(String): ByteArray`, `SecretBox.open(ByteArray): String` (throws `AppError` on a lost key or damaged bytes), `MonotonicClock`, `ProfileDao` (Task 1), `Session`.

**Produces:**
```kotlin
// security/PinRules.kt
object PinRules {
    const val MIN = 4; const val MAX = 12
    /** 4 to 12 ASCII digits. */
    fun valid(pin: CharArray): Boolean
}
object PinThrottle {
    const val FREE_TRIES = 5
    /** 0 below FREE_TRIES; then 30 s, doubling for each further wrong PIN, at most 15 minutes. */
    fun waitMs(failures: Int): Long
}

// security/PinCheck.kt
sealed interface PinCheck {
    data object Correct : PinCheck
    /** [triesLeft] before a wait starts (0 = the next wrong PIN starts one). */
    data class Wrong(val triesLeft: Int) : PinCheck
    /** Nothing was checked: wait this long first. */
    data class Wait(val seconds: Int) : PinCheck
    /** The verifier can no longer be opened on this phone (the Keystore key is gone): only "Forgot PIN" helps. */
    data object Unreadable : PinCheck
    data object NotProtected : PinCheck
}

// security/PinHasher.kt
/**
 * PBKDF2-HMAC-SHA256 over the PIN with a 16-byte random salt, 32-byte result, the whole sealed by [box] (Keystore AES-GCM), so the stored
 * bytes cannot be tried offline. Format before sealing: "p1:<iterations>:<base64 salt>:<base64 hash>". Comparison is constant-time
 * (`MessageDigest.isEqual`). Never keeps the PIN; the caller zeroes it.
 */
class PinHasher(private val box: SecretBox, private val iterations: Int = ITERATIONS, private val random: SecureRandom = SecureRandom()) {
    fun seal(pin: CharArray): ByteArray
    /** @throws AppError when the blob cannot be opened (lost key, damaged). */
    fun matches(pin: CharArray, sealed: ByteArray): Boolean
    companion object { const val ITERATIONS = 100_000 }
}

// security/ProfileLocks.kt
/** What screens use to protect their own profile (Task 7). */
interface ProfileProtection {
    val protectedProfiles: Flow<Set<Session>>
    fun isProtectedFlow(s: Session): Flow<Boolean>
    /** False (nothing stored) when [s] is already protected or [pin] breaks [PinRules]. Zeroes [pin]. */
    suspend fun protect(s: Session, pin: CharArray): Boolean
    /** Zeroes both arrays. Correct ⇒ the new PIN replaces the old one. */
    suspend fun changePin(s: Session, old: CharArray, new: CharArray): PinCheck
    /** Zeroes [pin]. Correct ⇒ the profile is no longer protected. */
    suspend fun unprotect(s: Session, pin: CharArray): PinCheck
}
class ProfileLocks(
    private val dao: ProfileDao, private val hasher: PinHasher, private val clock: MonotonicClock, private val io: CoroutineDispatcher,
) : ProfileProtection {
    suspend fun isProtected(s: Session): Boolean
    /** Zeroes [pin]. Wrong PINs are counted in Room; the wait is measured from this process's own clock (see Decisions). */
    suspend fun check(s: Session, pin: CharArray): PinCheck
    /** After a password sign-in from "Forgot PIN" (Task 5): the profile is no longer protected. */
    internal suspend fun clear(s: Session)
}
```
Behaviour of the wait (exact):
- `failures` is read from Room on every `check`. While `failures >= FREE_TRIES` the wait is `PinThrottle.waitMs(failures)` measured from an **in-memory anchor** per profile: the monotonic time of the last wrong PIN in this process, or, if there was none in this process, the time of the first `check` in this process. So killing the app restarts the wait (never shortens it) and changing the phone's clock does nothing.
- While waiting, `check` returns `Wait(seconds rounded up)` without opening the verifier and without counting.
- Hashing and opening run on [io].

- [ ] **Tests first — `PinHasherTest`** (iterations 1,000; `SecretBox` over a plain AES key):
  - `seal` then `matches` the same PIN is true, a different PIN false, a PIN one digit longer false;
  - two seals of the same PIN differ (salt);
  - the opened text starts `p1:1000:` and contains no digit run equal to the PIN;
  - a sealed blob from another key throws `AppError` (as `SecretBoxTest`);
  - `matches` uses `MessageDigest.isEqual` (source check: the file contains `MessageDigest.isEqual` and no `contentEquals`).
- [ ] **Tests first — `PinThrottleTest`:** 0..4 → 0; 5 → 30,000; 6 → 60,000; 9 → 480,000; 10 and 50 → 900,000. `PinRules.valid`: "1234" and "123456789012" yes; "123", "1234567890123", "12a4", "١٢٣٤" (Arabic-Indic digits), "" no.
- [ ] **Tests first — `ProfileLocksTest`** (in-memory Room, fake clock, `StandardTestDispatcher`):
  - `protect` then `check` right ⇒ `Correct`; the PIN array passed in is all zeros afterwards (every entry point);
  - a second `protect` ⇒ false and the first PIN still works;
  - four wrong ⇒ `Wrong(4)…Wrong(1)`; the fifth ⇒ `Wrong(0)`; the sixth (even the right PIN) ⇒ `Wait(30)`; after 30 s of fake time the right PIN ⇒ `Correct` and `failures` back to 0;
  - **restart:** with `failures = 6` in Room and a fresh `ProfileLocks` (new process), the first `check` ⇒ `Wait(60)` even though the old process's anchor is gone;
  - the wall clock is never read (constructor takes no wall clock; source check for `System.currentTimeMillis`);
  - `changePin` with a wrong old PIN ⇒ `Wrong`, the old PIN still works; right ⇒ only the new one works;
  - `unprotect` right ⇒ `isProtected` false and `protectedProfiles` drops it;
  - a verifier sealed under another key ⇒ `Unreadable`, and `failures` is not raised;
  - profile A's failures never affect profile B.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.security.*"` → pass.
- [ ] Commit: `feat(security): profile PINs — sealed by the Keystore, never stored, with waits that a restart cannot skip`.

---

### Task 3: Switching into a protected profile, and the household check, enforced in the data layer

**Files:**
- Create: `data/servers/ProfileSwitch.kt`, `security/HouseholdGuard.kt`, `LockParts.kt` (first part: `profileLocks`, `provedSwitches`, `switcher`, `householdGuard`).
- Modify: `data/servers/Seams.kt` (`StoreCatalog`, `AuthenticatorImpl` constructors), `data/downloads/DownloadRepository.kt` (`setSharing`), `data/downloads/HousekeepingRepository.kt` (`setSharedDelete`), `AppGraph.kt` (`lockParts`, the two constructor calls), `HousekeepingParts.kt` (pass the guard).
- Test: `data/ProfileSwitchTest.kt`, `data/HouseholdEnforcementTest.kt`, `security/HouseholdGuardTest.kt`.

**Interfaces — Consumes:** `ProfileLocks.isProtected/check`, `ServerStore.setActive`, `ServerCatalog.activeSession/accounts/servers`, `MonotonicClock`.

**Produces:**
```kotlin
// data/servers/ProfileSwitch.kt
sealed interface SwitchResult {
    data object Switched : SwitchResult
    /** Protected: ask for its PIN and call [ProfileSwitcher.switchWithPin]. Nothing changed. */
    data object NeedsPin : SwitchResult
    /** The PIN was not accepted (Wrong, Wait or Unreadable). Nothing changed. */
    data class NotSwitched(val check: PinCheck) : SwitchResult
    /** No such usable account (signed out, expired, removed). */
    data object Gone : SwitchResult
}
/** The one way a screen changes the active profile (`LockBoundaryTest`: nothing under ui/ calls `setActive`). */
interface ProfileSwitcher {
    suspend fun switchTo(serverId: String, userId: String): SwitchResult
    /** Zeroes [pin]. */
    suspend fun switchWithPin(serverId: String, userId: String, pin: CharArray): SwitchResult
}
/** Switches proved by the target's own PIN, for [LockGate]: consumed once, valid for 10 s (monotonic). */
class ProvedSwitches(private val clock: MonotonicClock) {
    fun record(s: Session)
    /** True once, within 10 s of [record] for exactly [s]. */
    fun consume(s: Session): Boolean
}
class ProfileProtectedException : IllegalStateException("That profile is protected.")
class ProfileSwitch(
    private val setActive: suspend (serverId: String, userId: String) -> Unit,   // ServerStore.setActive
    private val usable: suspend (Session) -> Boolean,
    private val locks: ProfileLocks, private val proved: ProvedSwitches,
) : ProfileSwitcher

// security/HouseholdGuard.kt
/** The household settings check, as screens see it (Task 6). */
interface Household {
    /** True while a protected profile exists on this phone and the active profile is not protected (or nobody is active). */
    val askNeeded: StateFlow<Boolean>
    /** The protected profiles to choose a grown-up from (names only, token-free). */
    val grownUps: Flow<List<AccountSummary>>
    /** Zeroes [pin]. Correct ⇒ household changes are allowed for 60 s, or until the active profile changes or the app locks. */
    suspend fun confirm(grownUp: Session, pin: CharArray): PinCheck
}
class HouseholdLockedException : IllegalStateException("A protected profile's PIN is needed.")
class HouseholdGuard(
    private val locks: ProfileLocks, private val catalog: ServerCatalog, private val clock: MonotonicClock, scope: CoroutineScope,
) : Household {
    suspend fun allows(): Boolean
    /** Signing out [target]: allowed if [target] is not protected, or is the active profile, or [confirm] was made with [target]'s own PIN in the last 60 s. */
    suspend fun allowsSignOut(target: Session): Boolean
    /** @throws HouseholdLockedException */ suspend fun require()
    /** @throws HouseholdLockedException */ suspend fun requireSignOut(target: Session)
    /** The pass ends now (LockGate calls this when the app locks). */
    fun endPass()
}
```
The rules, exact:
- `ProfileSwitch.switchTo`: not usable ⇒ `Gone`; protected ⇒ `NeedsPin` (nothing sent, nothing set); else `setActive` ⇒ `Switched`. `switchWithPin`: not usable ⇒ `Gone`; not protected ⇒ as `switchTo`; `check` ≠ Correct ⇒ `NotSwitched(check)`; Correct ⇒ `proved.record(s)` **then** `setActive` ⇒ `Switched`.
- `StoreCatalog.setActive(serverId, userId)` now calls `isProtected(Session(serverId, userId))` first and throws `ProfileProtectedException` (nothing set) when true. The constructor becomes `StoreCatalog(store, isProtected: suspend (Session) -> Boolean = { false }, household: suspend () -> HouseholdGuard? = { null })` so every existing test compiles unchanged.
- `StoreCatalog.upsertServer(s)`: when a server with `s.id` is already saved, `household()?.require()` first.
- `AuthenticatorImpl(repository, household: suspend () -> HouseholdGuard? = { null })`: `removeServer` calls `require()` first; `signOut(serverId, userId)` calls `requireSignOut(Session(serverId, userId))` first. Refused ⇒ the exception propagates and nothing is revoked or removed.
- `DownloadRepository.setSharing` and `HousekeepingRepository.setSharedDelete` call `household?.require()` first (a new constructor parameter `household: HouseholdGuard? = null`, passed from `AppGraph`/`HousekeepingParts`).
- `HouseholdGuard.allows()`: no protected profile ⇒ true; the active profile is protected ⇒ true; a pass younger than 60 s whose recorded active session equals the current one ⇒ true; else false. The pass is in memory only.
- `AppGraph`: `internal val lockParts by lazy { LockParts(this) }`; `serverCatalog = StoreCatalog(serverStore, { lockParts.profileLocks.isProtected(it) }, { lockParts.householdGuard })`; `authenticator = AuthenticatorImpl(authRepository) { lockParts.householdGuard }`. The lambdas defer, so there is no initialisation cycle.

- [ ] **Tests first — `ProfileSwitchTest`** (fake `setActive` recorder, real `ProfileLocks` on in-memory Room):
  - unprotected ⇒ `Switched`, one `setActive`;
  - protected ⇒ `NeedsPin`, no `setActive`;
  - wrong PIN ⇒ `NotSwitched(Wrong(4))`, no `setActive`, nothing recorded in `ProvedSwitches`;
  - right PIN ⇒ `Switched`, `proved.consume(s)` true once then false; after 11 s of fake time an unconsumed record is false;
  - an expired or removed target ⇒ `Gone`;
  - the PIN array is zeroed on every path.
- [ ] **Tests first — `StoreCatalog` refusal** (in `ProfileSwitchTest`): `catalog.setActive` on a protected profile throws `ProfileProtectedException` and the active account is unchanged (real `ServerStore` on in-memory Room).
- [ ] **Tests first — `HouseholdGuardTest`** (fake clock, `FakeCatalog`, real `ProfileLocks`):
  - no protected profile ⇒ `allows` true, `askNeeded` false;
  - adult protected, kid active ⇒ false, `askNeeded` true; `grownUps` lists the adult only;
  - `confirm(adult, right)` ⇒ `Correct` and `allows` true for 60 s, false at 61 s; a switch of the active profile ends it at once; `endPass()` ends it;
  - `confirm(adult, wrong)` ⇒ `Wrong`, still false; waits follow `ProfileLocks`;
  - adult active ⇒ true;
  - `allowsSignOut(adult)` with kid active ⇒ false; after `confirm(adult)` ⇒ true; after `confirm(otherProtected)` ⇒ still false; `allowsSignOut(kid)` ⇒ true.
- [ ] **Tests first — `HouseholdEnforcementTest`** (the real repositories behind each seam, as their existing tests build them):
  - with kid active and an adult protected: `Downloads.setSharing` and `Housekeeping.setSharedDelete` throw `HouseholdLockedException` and the stored value is unchanged; `StoreCatalog.upsertServer` of the existing server throws and the stored server is unchanged; `upsertServer` of a **new** server id is allowed; `removeServer` and `signOut(adult)` throw and send no request (MockWebServer sees 0 requests) and remove nothing; `signOut(kid)` works;
  - after `confirm(adult, right)` every one of them succeeds;
  - with no protected profile every one succeeds with no `confirm` (unchanged behaviour).
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.*" --tests "app.shoalwatch.security.*" --tests "app.shoalwatch.ui.*" --tests "app.shoalwatch.SourceShapeTest"` → pass (existing UI tests still compile: every new parameter has a default).
- [ ] Commit: `feat(security): protected profiles cannot be switched into, and household settings need a protected profile's PIN — enforced below the screens`.

---

### Task 4: The app lock core — settings, `LockGate`, the phone's screen lock, recents privacy, the permission

**Files:**
- Create: `security/LockPrefs.kt`, `security/LockGate.kt`, `security/DeviceAuth.kt`, `ui/RecentsPrivacy.kt`.
- Modify: `LockParts.kt` (`lockPrefs`, `deviceAuth`, `lockGate`), `ShoalwatchApp.kt` (`graph.lockParts.lockGate.start()`), `ui/MainActivity.kt` (lifecycle calls; recents privacy), `ui/components/SecureWindow.kt` (a `recents` holder; KDoc), `AndroidManifest.xml` (`USE_BIOMETRIC`, with a comment), `app/build.gradle.kts` (`releaseExpectedPermissions` += `android.permission.USE_BIOMETRIC`), `app/src/test/manifest-fixtures/ok.xml` (the permission).
- Test: `security/LockPrefsTest.kt`, `security/LockGateTest.kt`, `ui/RecentsPrivacyTest.kt`.

**Interfaces — Consumes:** `ProfileLocks.protectedProfiles/isProtected` (Task 2), `ProvedSwitches.consume`, `HouseholdGuard.endPass` (Task 3), `ServerCatalog.activeAccount`, `MonotonicClock`, `SecureWindowFlag`.

**Produces:**
```kotlin
// security/LockPrefs.kt
enum class LockAfter(val ms: Long) { IMMEDIATELY(0), ONE_MINUTE(60_000), FIVE_MINUTES(300_000), FIFTEEN_MINUTES(900_000) }
data class LockSettings(val appLock: Boolean = false, val lockAfter: LockAfter = LockAfter.ONE_MINUTE, val hideInRecents: Boolean = false)
/** `security_prefs`. A value this version cannot read is the default. */
class LockPrefs(private val prefs: SharedPreferences) {
    val settings: StateFlow<LockSettings>
    fun setAppLock(on: Boolean); fun setLockAfter(value: LockAfter); fun setHideInRecents(on: Boolean)
}

// security/LockGate.kt
sealed interface LockState {
    /** Start-up, until the settings, the active profile and its protection are known: the splash shows. */
    data object Checking : LockState
    data object Open : LockState
    /** The phone's own screen lock is asked for ([DeviceAuth]). */
    data object App : LockState
    /** [session]'s PIN is asked for. [userName] is shown ("Ben's profile is protected"). */
    data class Profile(val session: Session, val userName: String) : LockState { override fun toString() = "Profile(<redacted>)" }
}
class LockGate(
    private val settings: StateFlow<LockSettings>, private val locks: ProfileLocks, private val catalog: ServerCatalog,
    private val proved: ProvedSwitches, private val household: HouseholdGuard, private val deviceSecure: () -> Boolean,
    private val clock: MonotonicClock, private val scope: CoroutineScope,
) {
    val state: StateFlow<LockState>
    /** True while the recents preview must be hidden: hideInRecents, or appLock, or any protected profile. */
    val hideRecents: StateFlow<Boolean>
    fun start()
    /** The activity stopped (not a configuration change), or entered picture-in-picture. The earliest time is kept. */
    fun onLeft()
    /** The activity started again, or left picture-in-picture for the full screen. */
    fun onReturned()
    /** Before the app opens its own system screen (the unlock prompt, the client-certificate chooser): that trip is not "leaving". */
    fun expectReturn()
    /** [DeviceAuth] said yes. */
    fun appUnlocked()
    /** Zeroes [pin]. Correct ⇒ Open. */
    suspend fun unlockProfile(pin: CharArray): PinCheck
    /** After "Forgot PIN" cleared the profile's protection (Task 5). */
    fun profileCleared(s: Session)
}

// security/DeviceAuth.kt
enum class AuthOutcome { SUCCESS, CANCELLED, FAILED, UNAVAILABLE }
interface DeviceAuth {
    /** The phone has a secure screen lock (`KeyguardManager.isDeviceSecure`). */
    fun available(): Boolean
    /** Shows the system prompt over [activity]; suspends until it answers. */
    suspend fun authenticate(activity: Activity, title: String, subtitle: String): AuthOutcome
}
/**
 * Framework `BiometricPrompt` (API 28+, no extra library): API 30+ `setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)`;
 * API 29 `setDeviceCredentialAllowed(true)`. No negative button (the device credential is the fallback). Cancelled by a
 * `CancellationSignal` when the coroutine is cancelled.
 */
class FrameworkDeviceAuth(private val context: Context) : DeviceAuth

// ui/RecentsPrivacy.kt
/** API 33+: `Activity.setRecentsScreenshotEnabled(!hide)` (screenshots stay allowed). API 29–32: a `SecureWindowFlag` holder while hidden. */
internal object RecentsPrivacy { fun apply(activity: Activity, hide: Boolean) }
```
The lock rules (`LockGate`, exact; one state for the app):
1. **Start:** `Checking` until the first settings value, the first active-account value and `protectedProfiles` are read. Then: `appLock` on and `deviceSecure()` ⇒ `App`; else the active profile is protected ⇒ `Profile`; else `Open`. (A cold start always asks.)
2. **Leaving and returning:** `onLeft` stores `leftAt` (monotonic) if none is stored and no `expectReturn` is pending; `onReturned` with `now - leftAt >= lockAfter.ms` ⇒ as rule 1 (App first, then Profile); otherwise nothing; `leftAt` is cleared. `expectReturn` makes the next `onLeft`/`onReturned` pair not count (once).
3. **Profile changes:** whenever the active profile changes to a protected one, `proved.consume(it)` true ⇒ stays `Open`; false ⇒ `Profile` (this covers a sign-in, "Sign in again", a restore, and anything added later). A change to an unprotected profile or to nobody while `Profile` ⇒ `Open`.
4. **App then profile:** `appUnlocked()` in `App` ⇒ the active profile protected ⇒ `Profile`, else `Open`.
5. **Settings:** `appLock` turned off while `App` (only possible from a test) ⇒ rule 1 again. If `deviceSecure()` is false at a check while `appLock` is on, the app lock is switched off in `LockPrefs` (removing the phone's screen lock needs the phone's own credential) and the check continues as rule 1.
6. **Any move into App or Profile** calls `household.endPass()`.
7. `hideRecents` = `hideInRecents || appLock || protectedProfiles.isNotEmpty()`.

`MainActivity`: `onStop` → `lockGate.onLeft()` unless `isChangingConfigurations`; `onStart` → `lockGate.onReturned()`; collects `lockGate.hideRecents` in `lifecycleScope` → `RecentsPrivacy.apply(this, it)`. The client-certificate chooser (`ui/KeyChainCertChooser.kt`) calls `graph.lockParts.lockGate.expectReturn()` immediately before `KeyChain.choosePrivateKeyAlias` (one added line).

- [ ] **Tests first — `LockPrefsTest`** (Robolectric prefs): defaults; each setter round-trips across a new instance; an unknown `lockAfter` name reads ONE_MINUTE; a wrong type reads the default.
- [ ] **Tests first — `LockGateTest`** (fake clock, `FakeCatalog`, real `ProfileLocks` and `ProvedSwitches`, `StandardTestDispatcher`):
  - start with nothing on ⇒ `Checking` then `Open`; app lock on ⇒ `App`; protected active ⇒ `Profile(adult)`; app lock on and protected active ⇒ `App`, then `appUnlocked()` ⇒ `Profile`;
  - ONE_MINUTE: left 59 s ⇒ stays Open; left 60 s ⇒ locks; IMMEDIATELY: any leave ⇒ locks; a second `onLeft` before returning keeps the earlier time;
  - `expectReturn()` then leave 10 min ⇒ stays Open; the next trip counts again;
  - kid active, an unproved change to adult (the catalog's active account set directly, as a sign-in does) ⇒ `Profile(adult)`; a proved change (`proved.record(adult)` first) ⇒ `Open`;
  - in `Profile(adult)`, a change to kid ⇒ `Open`; `unlockProfile(right)` ⇒ `Open`; wrong ⇒ stays, returns `Wrong`;
  - `deviceSecure` false with app lock on ⇒ `LockPrefs.appLock` becomes false and the state is `Open` (or `Profile`);
  - every move into `App`/`Profile` ends the household pass;
  - `hideRecents` true for each of the three causes and false with none;
  - the wall clock is never read.
- [ ] **Tests first — `RecentsPrivacyTest`** (Robolectric): `@Config(sdk = [34])` `apply(true)` leaves `FLAG_SECURE` off (the screenshot switch is used; read through the shadow or a `RecentsSwitch` seam if the shadow lacks it); `@Config(sdk = [32])` `apply(true)` sets `FLAG_SECURE`, `apply(false)` clears it, and an admin screen's holder acquired meanwhile keeps it set after `apply(false)` (the counting in `SecureWindowFlag` is shared).
- [ ] **Tests first — manifest:** `verifyManifestRuleFixtures` passes with `ok.xml` carrying `USE_BIOMETRIC`; `bad-extra-permission.xml` still fails.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.security.*" --tests "app.shoalwatch.ui.RecentsPrivacyTest" verifyManifestRuleFixtures assembleRelease` → pass (`verifyReleaseManifest` passes with the one new permission).
- [ ] Commit: `feat(security): the app lock core — locks on start and after time away, and whenever a protected profile becomes active without its PIN`.

---

### Task 5: The lock screen, reset on lock, deep links stripped, `taskAffinity`

**Files:**
- Create: `ui/lock/LockScreen.kt`, `ui/lock/LockViewModel.kt`, `ui/lock/PinField.kt`, `ui/lock/ForgotPinPanel.kt`, `ui/lock/LockText.kt`.
- Modify: `ui/nav/AppNav.kt` (`Routes.LOCKED = "locked"`, an empty composable for it; `ResetOnLock(nav, state)`; `AppRoot` shows the splash while `Checking` and draws `LockScreen` over the host while `App` or `Profile`), `ui/MainActivity.kt` (`sanitised(intent)` in `onCreate` before anything reads it, and in `onNewIntent`), `AndroidManifest.xml` (`android:taskAffinity=""` on `MainActivity`), `app/build.gradle.kts` (`ManifestRules`: the launcher activity must have `taskAffinity=""`; fixture expectation), `strings_lock.xml` (lock screen rows).
- Create: `app/src/test/manifest-fixtures/bad-task-affinity.xml`; modify `ok.xml`.
- Test: `ui/lock/LockFakes.kt`, `ui/lock/LockViewModelTest.kt`, `ui/lock/LockScreenRenderTest.kt`, `ui/lock/PinFieldExposureTest.kt`, `ui/lock/ResetOnLockTest.kt`, `ui/lock/MainActivityIntentTest.kt`.

**Interfaces — Consumes:** `LockGate.state/appUnlocked/unlockProfile/expectReturn/profileCleared`, `DeviceAuth`, `ProfileSwitcher.switchTo/switchWithPin` (for "Use another profile"), `ProfileLocks.clear`, `Authenticator.signInRoute/signInWithPassword`, `ServerCatalog.servers/accounts`.

**Produces:**
```kotlin
// ui/lock/LockViewModel.kt
data class OtherProfile(val serverId: String, val userId: String, val userName: String, val serverName: String, val isProtected: Boolean) {
    override fun toString() = "OtherProfile(<redacted>)"
}
data class LockUiState(
    val lock: LockState = LockState.Checking,
    val others: List<OtherProfile> = emptyList(),
    /** The last PIN answer to show (Wrong / Wait / Unreadable), cleared on typing. */
    val pinAnswer: PinCheck? = null,
    val busy: Boolean = false,
    /** "Forgot PIN" is open: the password panel for the locked profile. */
    val forgot: Boolean = false,
    val forgotError: AppError.Kind? = null,
    /** A protected profile chosen under "Use another profile": its name, for the PIN dialog. */
    val choosing: OtherProfile? = null,
)
class LockViewModel(
    private val gate: LockGate, private val switcher: ProfileSwitcher, private val catalog: ServerCatalog,
    private val authenticator: Authenticator, private val clearProtection: suspend (Session) -> Unit,
) : ViewModel() {
    val state: StateFlow<LockUiState>
    /** Never in UiState; cleared after every attempt and when the lock changes. */
    val pin: TextFieldState
    val password: TextFieldState
    fun submitPin(); fun useProfile(p: OtherProfile); fun submitChosenPin(); fun dismissChoice()
    fun openForgot(); fun closeForgot(); fun submitForgot()
}

// ui/lock/PinField.kt — the one PIN field: NumberPassword, obfuscated (RevealLastTyped off: Hidden), autoCorrect off,
// semantics password() + editableText of bullets (as SignInPanels.PasswordPanel), at most PinRules.MAX characters, digits only.
@Composable fun PinField(state: TextFieldState, label: String, onDone: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true)

// ui/nav/AppNav.kt
/** While locked, the stack is one empty `locked` route (every screen, dialog, sheet and player of the locked profile is popped, so
 *  their ViewModels are cleared and the player sends its stop); once open again, Home (Welcome with no server), fresh. */
@Composable internal fun ResetOnLock(nav: NavHostController, state: LockState, hasServers: suspend () -> Boolean)

// ui/MainActivity.kt
companion object {
    /** A copy of [intent] with no data, no clip data, no categories but LAUNCHER, and only the `open_tab=downloads` extra (else none).
     *  Navigation therefore never sees an `android-app://androidx.navigation/…` deep link (closes the Milestone 6 check's M9). */
    internal fun sanitised(intent: Intent?): Intent
}
```
`LockScreen` (draws over everything, opaque, takes every touch; the host beneath gets `Modifier.clearAndSetSemantics {}`):
- **App:** the Shoalwatch mark, "Shoalwatch is locked", body "Unlock with your phone's screen lock.", button "Unlock". The system prompt opens by itself once each time the lock screen becomes visible and the activity is resumed (`gate.expectReturn()` first); "Unlock" opens it again. Prompt title "Unlock Shoalwatch", subtitle "Use your fingerprint, face or screen lock". `UNAVAILABLE` ⇒ body "Your phone's screen lock can't be used right now. Try again." 
- **Profile:** heading "%1$s's profile is protected", body "Enter the PIN for this profile.", `PinField` labelled "PIN", button "Unlock"; answers: Wrong(n>0) "That PIN isn't right. %1$d tries left before a wait."; Wrong(0) "That PIN isn't right. The next wrong PIN starts a wait."; Wait "Too many wrong PINs. Try again in %1$s." (minutes and seconds through `ui/present` duration words); Unreadable "This PIN can't be checked on this phone any more. Use Forgot PIN."
- Then "Use another profile" (heading) listing `others` (every usable profile on this phone but the locked one; each row "%1$s on %2$s", a lock icon and "Protected" for protected ones). An unprotected row switches at once; a protected row opens `PinDialog` (Task 6's component; in this task a minimal secure dialog with `PinField`) for that profile and calls `switchWithPin`.
- "Forgot PIN?" opens `ForgotPinPanel` (inside the lock screen, `SecureWindow` while shown): body "Sign in to %1$s with its server password. This removes the PIN, and you can set a new one in Settings.", the masked password field (the sign-in field's semantics masking), "Sign in and remove PIN", "Cancel". It asks `authenticator.signInRoute(serverId)`; none ⇒ "You're offline. Connect to %1$s to sign in."; else `signInWithPassword(route, userName, password)`; the returned account's `userId` must equal the locked profile's, else nothing is cleared and the error "That's a different account." shows; equal ⇒ `clearProtection(session)` then `gate.profileCleared(session)` ⇒ Open. **Quick Connect is not offered here** (see Decisions).
- Every button 48dp with its own label; the PIN field is focused first; at font scale 2.0 everything scrolls.

`ManifestRules` gains: the launcher activity must declare `android:taskAffinity=""` ("the launcher activity must set taskAffinity to empty"), with `bad-task-affinity.xml` (the launcher without it) in the expectations.

- [ ] **Tests first — `MainActivityIntentTest`** (Robolectric, pure): data `android-app://androidx.navigation/download_settings` ⇒ no data; `open_tab=downloads` kept; `open_tab=servers` dropped; clip data, a selector and extra keys dropped; null ⇒ a plain launcher intent; the result's component is `MainActivity`.
- [ ] **Tests first — `ResetOnLockTest`** (the real `NavHost` with recorder destinations, as `SessionChangeNavigationTest`): Open on `series/x` with `item/y` on top ⇒ `Profile` ⇒ the stack is exactly `[locked]` and both entries' ViewModels are cleared (a recorder VM's `onCleared` ran); back to Open ⇒ `[home?fresh=false]`; with no server ⇒ `[welcome]`; a session change while locked keeps `[locked]` on top (the effect re-asserts it); a configuration change while locked keeps `[locked]`.
- [ ] **Tests first — `LockViewModelTest`** (`FakeSwitcher`, `FakeAuthenticator`, a real `LockGate` over fakes):
  - right PIN ⇒ Open and the field is empty; wrong ⇒ the answer, the field empty;
  - `others` excludes the locked profile and expired accounts, and marks protected ones;
  - an unprotected other ⇒ `switchTo` once, then Open (the gate sees an unprotected profile);
  - a protected other ⇒ `choosing` set, `submitChosenPin` ⇒ `switchWithPin` with that profile only;
  - forgot: offline ⇒ the offline error, nothing cleared; a different user id ⇒ "different account", nothing cleared; the same ⇒ `clearProtection` once and Open; the password field is empty after every attempt;
  - the lock changing (e.g. to another profile) clears both fields and `choosing`.
- [ ] **Tests first — `LockScreenRenderTest`** (phone, tablet; 1.0 and 2.0): App and Profile layouts with their exact words; every answer line; the other-profile rows' labels ("Kid on Test Jellyfin", "Anna on Test Jellyfin, protected"); the forgot panel; 48dp targets; nothing of the host beneath is in the semantics tree while locked (a host with a node "SECRET" is not found).
- [ ] **Tests first — `PinFieldExposureTest`** (as `PasswordExposureTest`): the PIN field's semantics carry the Password flag and bullets of the right length, never the digits; the forgot panel's password the same.
- [ ] Implement; add the `taskAffinity` rule and fixture.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.ui.lock.*" --tests "app.shoalwatch.ui.nav.*" --tests "app.shoalwatch.SourceShapeTest" verifyManifestRuleFixtures assembleRelease` → pass.
- [ ] Commit: `feat(lock): a lock screen for the app and for protected profiles, with "Forgot PIN" by server password; links into the app are ignored`.

---

### Task 6: The PIN in every switch, the grown-up check on household settings, and "Protected" badges

**Files:**
- Create: `ui/lock/PinDialog.kt`, `ui/lock/GrownUpDialog.kt`, `ui/lock/SwitchFlow.kt`.
- Modify: `ui/home/HomeViewModel.kt` (`switchAccount` through `ProfileSwitcher`; a `switchPrompt`), `ui/home/HomeTopBar.kt` (the lock icon and "Protected" on protected profiles in the menu), `ui/home/HomeScreen.kt` (shows the PIN dialog), `ui/servers/ServersViewModel.kt` (`switchTo` through the switcher; grown-up check before Settings, Remove server and signing out a protected profile), `ui/servers/ServersScreen.kt` (badges; dialogs), `ui/downloads/DownloadSettingsViewModel.kt` + `DownloadSettingsScreen.kt` (grown-up check before "Share downloads between profiles" and "Delete shared downloads"), `ui/nav/Routes.kt`, `ui/nav/BrowseRoutes.kt` (pass the switcher and household), `strings_lock.xml`.
- Test: `ui/lock/SwitchFlowTest.kt`, `ui/lock/GrownUpDialogRenderTest.kt`, additions to `ui/home/HomeViewModelTest.kt`, `ui/ServersAndHomeViewModelTest.kt`, `ui/downloads/DownloadsViewModelTest.kt` or a new `ui/downloads/DownloadSettingsGrownUpTest.kt`, `ui/servers/ServersRenderTest` cases in `ui/ManagementRenderTest.kt`.

**Interfaces — Consumes:** `ProfileSwitcher`, `SwitchResult`, `Household` (Task 3), `ProfileProtection.protectedProfiles` (badges), `PinField` (Task 5).

**Produces:**
```kotlin
// ui/lock/SwitchFlow.kt — shared by Home and Servers so both behave identically.
data class SwitchPrompt(val serverId: String, val userId: String, val userName: String, val answer: PinCheck? = null) {
    override fun toString() = "SwitchPrompt(<redacted>)"
}
internal class SwitchFlow(private val switcher: ProfileSwitcher, private val scope: CoroutineScope) {
    val prompt: StateFlow<SwitchPrompt?>
    val pin: TextFieldState
    fun request(serverId: String, userId: String, userName: String)
    fun submit(); fun dismiss()
}
// ui/lock/GrownUpDialog.kt — one dialog for every household setting.
data class GrownUpAsk(val grownUps: List<AccountSummary>, val chosen: AccountSummary?, val answer: PinCheck? = null) { override fun toString() = "GrownUpAsk(<redacted>)" }
internal class GrownUpFlow(private val household: Household, private val scope: CoroutineScope) {
    val ask: StateFlow<GrownUpAsk?>
    val pin: TextFieldState
    /** Runs [then] at once if no check is needed; otherwise asks, and runs [then] after a right PIN. Dismiss ⇒ [then] never runs. */
    fun guard(then: () -> Unit)
    fun choose(grownUp: AccountSummary); fun submit(); fun dismiss()
}
@Composable internal fun PinDialog(title: String, body: String, pin: TextFieldState, answer: PinCheck?, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit)
@Composable internal fun GrownUpDialog(ask: GrownUpAsk, pin: TextFieldState, onChoose: (AccountSummary) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit)
```
- Every dialog is a `SecureDialog`, behind `FilterObscuredTouches` like the other confirmations.
- **Switch (Home menu and Servers radio):** `switcher.switchTo` ⇒ `Switched` closes; `NeedsPin` ⇒ `PinDialog` titled "Switch to %1$s?", body "%1$s's profile is protected. Enter its PIN.", confirm "Switch"; answers as on the lock screen; `Gone` ⇒ the existing "no longer signed in" handling. The menu and the Servers rows show a lock icon with "Protected" (TalkBack: "%1$s, protected").
- **Grown-up check** (`GrownUpDialog`): title "A grown-up's PIN is needed", body "This setting changes things for everyone on this phone. Choose a protected profile and enter its PIN." With one grown-up it is chosen already; with several, radio rows of names. Confirm "Continue". Applied to: Download settings "Share downloads between profiles" (both directions) and "Delete shared downloads"; Servers "Settings" (before navigating to Connection settings), "Remove server", and "Sign out" of a protected profile that is not active (this one asks for **that profile's** PIN: the dialog is `PinDialog` "Sign out %1$s?", "%1$s's profile is protected. Enter its PIN to sign it out.").
- The data layer refuses anyway (Task 3); if a `HouseholdLockedException` still arrives (a race), the screen shows "A grown-up's PIN is needed. Try again." and changes nothing.

- [ ] **Tests first — `SwitchFlowTest`** (`FakeSwitcher`, virtual time): unprotected ⇒ no prompt, one `switchTo`; protected ⇒ prompt with the name; wrong ⇒ answer shown, field emptied, prompt stays; right ⇒ prompt gone; dismiss ⇒ no call; a second `submit` while one runs ⇒ one call; the PIN is never in `SwitchPrompt` (`toString` and fields checked).
- [ ] **Tests first — Home and Servers:** `HomeViewModel.switchAccount` and `ServersViewModel.switchTo` never call `catalog.setActive` (the `FakeCatalog` records 0 calls) and go through the switcher; Servers' Settings, Remove server and Sign out (protected, not active) call `GrownUpFlow.guard` and run only after a right PIN; Sign out of the active protected profile and of an unprotected one asks nothing.
- [ ] **Tests first — Download settings:** with `askNeeded` true, `setSharing(true)` and `setDeleteShared(EVERYONE)` call nothing on `FakeDownloads`/`FakeHousekeeping` until `confirm` returns Correct; dismiss ⇒ nothing; `askNeeded` false ⇒ called at once.
- [ ] **Tests first — `GrownUpDialogRenderTest`** (phone, tablet; 1.0, 2.0): one grown-up (no choice shown) and three (radios say selected); the PIN field; every answer line; 48dp; the dialog is secure (`DialogProperties.securePolicy` read through the test's dialog window, or a source check that every dialog in `ui/lock` uses `SecureDialog`).
- [ ] **Tests first — render:** the lock icon and "Protected" on Home's menu and Servers' rows, with the spoken label.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.ui.*" --tests "app.shoalwatch.SourceShapeTest"` → pass.
- [ ] Commit: `feat(lock): switching into a protected profile asks for its PIN, and household settings ask for a grown-up's`.

---

### Task 7: The Settings screen — autoplay, the app lock and this profile's protection

**Files:**
- Create: `data/playback/ProfilePlayback.kt`, `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`, `ui/settings/SettingsRows.kt`.
- Modify: `LockParts.kt` (`profilePlayback`), `ui/nav/AppNav.kt` (`Routes.SETTINGS = "settings"`; the composable; `SETTINGS` is **not** an account route, so a session change pops it), `ui/nav/Routes.kt` (`SettingsRoute`), `ui/nav/BrowseRoutes.kt` (`PlayerRoute`: `graph.playbackController(s, scope, graph.lockParts.profilePlayback.autoplayReader(s, scope))`), `ui/home/HomeTopBar.kt` (menu item "Settings"), `ui/servers/ServersScreen.kt` (a "Settings" button in its top bar), `res/values/strings.xml` (autoplay), `strings_lock.xml` (settings rows).
- Test: `data/playback/ProfilePlaybackTest.kt`, `ui/settings/SettingsViewModelTest.kt`, `ui/settings/SettingsRenderTest.kt`, a case in `ui/player/PlayerViewModelTest.kt` (autoplay off ⇒ the Up next card waits).

**Interfaces — Consumes:** `ProfileDao.observeAutoplay/upsertPlayback` (Task 1), `ProfileProtection` (Task 2), `LockPrefs` (Task 4), `GrownUpFlow`, `PinDialog` (Task 6), `DeviceAuth.available/authenticate`, `LockGate.expectReturn`, `UpNextCountdown(autoplay = false)` (existing: waits for a tap).

**Produces:**
```kotlin
// data/playback/ProfilePlayback.kt
class ProfilePlayback(private val dao: ProfileDao) {
    /** Default true (as before this setting existed). */
    fun autoplay(s: Session): Flow<Boolean>
    suspend fun setAutoplay(s: Session, on: Boolean)
    /** For `PlaybackController`'s `autoplayNext`: reads the latest value, kept hot in [scope] (the player screen's). */
    fun autoplayReader(s: Session, scope: CoroutineScope): () -> Boolean
}

// ui/settings/SettingsViewModel.kt
data class SettingsUiState(
    val session: Session? = null, val userName: String? = null,
    val autoplay: Boolean = true,
    val profileProtected: Boolean = false,
    val lock: LockSettings = LockSettings(),
    /** The phone has a secure screen lock (the app lock row is off and explained without one). */
    val deviceSecure: Boolean = false,
    /** Hidden in recents because of the app lock or a protected profile, whatever the switch says. */
    val recentsForced: Boolean = false,
    val pinStep: PinStep? = null,
) { override fun toString() = "SettingsUiState(<redacted>)" }
enum class PinStep { NEW, REPEAT, CURRENT_FOR_CHANGE, NEW_FOR_CHANGE, REPEAT_FOR_CHANGE, CURRENT_FOR_REMOVE }
class SettingsViewModel(
    catalog: ServerCatalog, private val playback: ProfilePlayback, private val protection: ProfileProtection,
    private val prefs: LockPrefs, household: Household, private val deviceSecure: () -> Boolean, hideRecents: StateFlow<Boolean>,
) : ViewModel() {
    val state: StateFlow<SettingsUiState>
    val pin: TextFieldState
    val grownUp: GrownUpFlow
    fun setAutoplay(on: Boolean)
    fun startProtect(); fun startChangePin(); fun startRemovePin(); fun submitPin(); fun cancelPin()
    /** After the system prompt said yes (the screen runs it, as the lock screen does). Grown-up check first. */
    fun setAppLock(on: Boolean)
    fun setLockAfter(value: LockAfter); fun setHideInRecents(on: Boolean)
}
```
The screen (`AppScreen`-style, a back bar titled "Settings"; groups are headings):
- **Playback:** switch "Play the next episode automatically", help "When an episode ends, the next one starts after a short countdown. For this profile only." (Task 9 adds the picture-in-picture row here.)
- **This profile (%1$s):** when unprotected, "Protect this profile with a PIN" (button) and help "Anyone using this phone will need the PIN to switch to this profile. Use 4 to 12 digits."; the PIN steps ask "Choose a PIN", "Enter the PIN again", and on a mismatch "Those PINs don't match. Try again." When protected: "Protected with a PIN", buttons "Change PIN" and "Remove PIN" (each asks the current PIN first). No grown-up check: a profile changes only its own protection, and removing it needs its own PIN.
- **App lock:** switch "Lock Shoalwatch with this phone's screen lock" (off and with help "Set a screen lock on this phone first." when `deviceSecure` is false); turning it on or off runs the system prompt first (`expectReturn`), then the grown-up check, then saves. "Lock after" radios: "Straight away", "After 1 minute" (default), "After 5 minutes", "After 15 minutes"; help "Also how soon a protected profile asks for its PIN again." Grown-up check before saving.
- **Privacy:** switch "Hide Shoalwatch in recent apps"; when `recentsForced`, the switch shows on and disabled with help "Always hidden while the app lock is on or a profile is protected."; grown-up check before saving. On Android 10–12 the help adds "On this phone this also stops screenshots of Shoalwatch."
- **Downloads:** a row "Download settings" that opens the existing screen.
- PINs are never in `SettingsUiState`; the field is cleared after each step and on leaving.

- [ ] **Tests first — `ProfilePlaybackTest`:** default true; set false then true; per profile (A's change leaves B's); `autoplayReader` returns the latest value after the flow emits (virtual time); an account's removal cascades (Task 1).
- [ ] **Tests first — `SettingsViewModelTest`:** autoplay writes for the active profile only; protect: NEW → REPEAT → `protect` once, a mismatch restarts at NEW and calls nothing; change: CURRENT wrong ⇒ answer and stays; CURRENT right ⇒ NEW → REPEAT ⇒ `changePin`; remove: right ⇒ `unprotect`; the field is empty after every submit; app lock, lock after and hide-in-recents go through `GrownUpFlow.guard` and save only after it allows; `recentsForced` follows `hideRecents`; a session change resets `pinStep` and the field.
- [ ] **Tests first — `SettingsRenderTest`** (phone, tablet; 1.0, 2.0): every row and help line with exact words; the disabled app-lock row and its help; the forced recents row; radios say selected; 48dp; reachable by scrolling at 2.0.
- [ ] **Tests first — `PlayerViewModelTest`:** with the reader false, the controller is created with `autoplayNext` false (the `PlaybackFakes` engine reaches the credits; `state.playback.upNext.secondsLeft` is null and nothing plays by itself after 20 s of virtual time); true keeps today's countdown.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.ui.settings.*" --tests "app.shoalwatch.ui.player.*" --tests "app.shoalwatch.data.playback.*" --tests "app.shoalwatch.SourceShapeTest"` → pass; `PlaybackController.kt` unchanged (`git diff --stat` shows nothing for it).
- [ ] Commit: `feat(settings): a Settings screen — turn off playing the next episode by itself, protect a profile with a PIN, and the app lock`.

---

### Task 8: On-device lock smoke test, the boundary test, and the watch-and-delete prompt's order

**Files:**
- Create: `app/src/test/java/app/shoalwatch/LockBoundaryTest.kt`, `app/src/test/java/app/shoalwatch/ui/player/PlayerCornerOrderTest.kt`, `app/src/androidTest/java/app/shoalwatch/ui/LockSmokeTest.kt`.
- Modify (only if a test shows the need): `ui/player/WatchedPrompt.kt`, `ui/player/UpNextCard.kt`, `ui/player/PlayerScreen.kt` (traversal semantics).

**Interfaces — Consumes:** everything from Tasks 2–7.

`LockBoundaryTest` (source scan, as `AdminBoundaryTest`; failures name file and line):
- nothing under `ui/` calls `.setActive(` or names `ServerStore`, `ProfileDao`, `ProfileLockEntity`, `PinHasher` or `ProvedSwitches`;
- nothing under `ui/`, `data/` or `security/` holds a PIN or password in a `String` parameter or property named `pin`, `newPin`, `oldPin` or `password` (regex `(pin|Pin|password)\s*:\s*String`);
- `ui/lock/` and `ui/settings/` never use `rememberSaveable` with a field named `pin` or `password`, `SavedStateHandle`, `ClipboardManager` or `Log.`;
- every `Dialog(`/`AlertDialog(` in `ui/lock/` and `ui/settings/` passes `SecureDialog`;
- `security/ProfileLocks.kt` and `security/LockGate.kt` contain no `System.currentTimeMillis` or `Instant.now`;
- `MainActivity.onCreate` calls `sanitised(` before `setContent` (text order check).

`PlayerCornerOrderTest` (Robolectric, 640x360 and 1200x840, font scale 1.0 and 2.0): with the watch-and-delete card and Up next both shown, the accessibility traversal order is: the card's text, "Watched — delete download", "Watched together…" (when offered), "Keep download", then Up next's title and its buttons, then the player controls; the card is a polite live region; neither card is clipped (each node's bounds lie inside the root) and their bounds do not intersect.

`LockSmokeTest` (on a device; compile here, the controller runs it): seeds one server and three accounts (ben, anna, kid) through the real `AppGraph` (plain http to an in-process `MockWebServer`, as `SyncSmokeTest`), protects ben with PIN `2468` through `graph.lockParts.profileLocks`, makes kid active, sets lock after to IMMEDIATELY, cleans up even if seeding fails part way.
- **Switch:** open Home's account menu, tap ben ⇒ "Switch to ben?"; type `1111` ⇒ "That PIN isn't right. 4 tries left before a wait."; type `2468` ⇒ Home as ben (the top bar's account is ben).
- **Leaving:** `ActivityScenario.moveToState(CREATED)` then `RESUMED` ⇒ "ben's profile is protected"; nothing else is in the tree; "Use another profile" ⇒ tap kid ⇒ Home as kid with no PIN.
- **Sign-in path:** sign ben in again through the Sign in screen with the mock's password ⇒ the lock screen for ben appears straight after (not Home).
- **Deep link:** launch `MainActivity` with data `android-app://androidx.navigation/download_settings` ⇒ Home, and the activity's intent has no data.
- **Household:** as kid, open Download settings, turn on "Share downloads between profiles" ⇒ "A grown-up's PIN is needed"; Cancel ⇒ `graph.downloads.sharing(serverId).first()` is still false; again with `2468` ⇒ true.
- **Recents:** on API 33+ the test only records `Build.VERSION.SDK_INT` (the screenshot switch cannot be read back); on API 29–32 the window has `FLAG_SECURE` while ben is protected.

- [ ] **Tests first — `LockBoundaryTest`**: it must fail on a planted violation before the real tree passes (run once with a temporary `ui/lock/Planted.kt` holding `fun x(c: app.shoalwatch.data.servers.ServerCatalog) = suspend { c.setActive("a", "b") }`, see it fail naming that file, delete it).
- [ ] **Tests first — `PlayerCornerOrderTest`**; fix the order with `isTraversalGroup`/`traversalIndex` in the player only if it fails.
- [ ] Write `LockSmokeTest`.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.LockBoundaryTest" --tests "app.shoalwatch.ui.player.*"` → pass; `./gradlew.bat assembleDebugAndroidTest` → builds.
- [ ] Commit: `test(lock): the lock boundary test, the on-device lock smoke test, and the order TalkBack reads the prompt beside Up next`.

---

### Task 9: Picture-in-picture (Android 14+ gesture navigation included)

**Files:**
- Create: `ui/player/PictureInPicture.kt` (`PipHost`, `PipWish`, `pipParams`, `PipActionReceiver`, `PipEffect`), `data/playback/PipPrefs.kt`, `res/drawable/ic_pip_play.xml`, `res/drawable/ic_pip_pause.xml`.
- Modify: `AndroidManifest.xml` (`MainActivity`: `android:supportsPictureInPicture="true"`, `android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation"`), `ui/MainActivity.kt` (implements `PipHost`; `onUserLeaveHint`; `onPictureInPictureModeChanged` → `lockGate.onLeft()`/`onReturned()`; registers the action receiver while a wish exists), `ui/player/PlayerScreen.kt` (`inPip`: only the picture and subtitles), `ui/player/PlayerControls.kt` (a "Picture-in-picture" button in the top bar), `ui/player/PlayerViewModel.kt` (no logic change: `playPause` is what the action calls), `ui/nav/BrowseRoutes.kt` (`PlayerRoute`: `PipEffect`), `ui/settings/SettingsScreen.kt` + `SettingsViewModel.kt` (the row), `LockParts.kt` or `AppGraph.kt` (`pipPrefs` from `playback_prefs`), `res/values/strings.xml`.
- Test: `ui/player/PictureInPictureTest.kt`, `ui/player/PipModeRenderTest.kt`, `data/playback/PipPrefsTest.kt`; `app/src/androidTest/java/app/shoalwatch/ui/PipSmokeTest.kt`.

**Interfaces — Consumes:** `PlayerUiState.playback` (phase, video size if known — else 16:9), `PlayerViewModel.playPause`, `LockGate.onLeft/onReturned` (Task 4), `HostLifecycle` (existing: the player pauses on ON_STOP, which is when a PiP window is closed).

**Produces:**
```kotlin
// ui/player/PictureInPicture.kt
/** What the player asks of the window. [playing]: auto-enter only while playing. Aspect clamped to 1:2.39 … 2.39:1. */
data class PipWish(val width: Int, val height: Int, val playing: Boolean, val sourceRect: android.graphics.Rect?)
interface PipHost {
    val inPip: StateFlow<Boolean>
    /** null when the player is not shown, or PiP is off in Settings: nothing enters PiP. */
    fun wish(w: PipWish?, onPlayPause: (() -> Unit)?)
    /** The button: enters now if allowed. False if the system refused. */
    fun enterNow(): Boolean
}
/** The params: aspect, one RemoteAction (play or pause: an explicit, FLAG_IMMUTABLE broadcast to this package), seamless resize off,
 *  source rect hint; on API 31+ `setAutoEnterEnabled(wish.playing)`. Pure apart from the Android builders. */
internal fun pipParams(context: Context, wish: PipWish): PictureInPictureParams
internal class PipActionReceiver(private val onPlayPause: () -> Unit) : BroadcastReceiver() {
    companion object { const val ACTION = "app.shoalwatch.action.PIP_PLAY_PAUSE" }
}
val LocalPipHost: ProvidableCompositionLocal<PipHost?>
@Composable internal fun PipEffect(state: PlayerUiState, enabled: Boolean, onPlayPause: () -> Unit)

// data/playback/PipPrefs.kt — `playback_prefs`, key `pip_auto`, default true.
class PipPrefs(prefs: SharedPreferences) { val autoEnter: StateFlow<Boolean>; fun setAutoEnter(on: Boolean) }
```
Rules:
- The wish exists only while the player screen is composed, the phase is not FAILED, and no sheet or the screen lock is up. Auto-enter (API 31+) and `onUserLeaveHint` entry (API 29–30) happen only when `playing` and `PipPrefs.autoEnter`. The button works whenever the wish exists.
- **Auto-enter is the fix for gesture navigation:** the system enters PiP during the swipe home, instead of the app trying in `onUserLeaveHint` after the gesture (the Android 14+ failure the official app has).
- In PiP: no controls, no gestures, no Up next card, no watch-and-delete card, no snackbars; subtitles still drawn. Up next still counts down and autoplays if the profile allows. Leaving PiP for full screen shows the controls once.
- The receiver is registered with `ContextCompat.registerReceiver(this, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)` while a wish exists and unregistered when it goes; it ignores any intent whose action differs.
- Entering PiP counts as leaving for the lock timer; returning to full screen counts as returning; closing the PiP window stops the activity (the player pauses, as today).
- Settings, Playback group: switch "Keep playing in a small window when you leave the app", help "Picture-in-picture, while a video is playing."
- Player top-bar button: icon `PictureInPictureAlt`-like from `material-icons-core` if present, else `ic_pip_play` vector; label "Picture-in-picture".

- [ ] **Tests first — `PictureInPictureTest`** (Robolectric):
  - `pipParams` aspect for 1920x1080 is 16:9; for 4000x1000 it is clamped to 239:100; for 0x0 it is 16:9;
  - exactly one action; its `PendingIntent` is immutable (`isImmutable` on API 31+ shadow) and its intent has `setPackage(context.packageName)` and action `ACTION`;
  - `@Config(sdk = [34])` auto-enter equals `wish.playing`;
  - `@Config(sdk = [30])` `MainActivity.onUserLeaveHint` with a playing wish enters PiP (shadow records it); paused, or `autoEnter` off ⇒ does not; no wish ⇒ does not;
  - the receiver calls back for `ACTION` only;
  - entering PiP calls `lockGate.onLeft()` and leaving to full screen `onReturned()` (a recording fake gate through the graph seam).
- [ ] **Tests first — `PipModeRenderTest`** (player 640x360): `inPip = true` shows no control, no Up next, no prompt, no snackbar (their labels absent); `false` shows the new "Picture-in-picture" button with its label at 48dp.
- [ ] **Tests first — `PipPrefsTest`:** default true; round-trips; wrong type ⇒ true.
- [ ] Write `PipSmokeTest` (device; the controller runs it): starts playback as `PlayerSmokeTest` does; calls `enterPictureInPictureMode(pipParams(...))` through `scenario.onActivity`; waits until `isInPictureInPictureMode`; the position keeps advancing for 3 s; sends the play/pause broadcast (explicit, to the package) ⇒ paused; `moveToState(CREATED)` (the window closed) ⇒ the player is paused when resumed.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.ui.player.*" --tests "app.shoalwatch.data.playback.*" --tests "app.shoalwatch.SourceShapeTest" assembleRelease assembleDebugAndroidTest` → pass; `PlaybackController.kt` unchanged; `verifyReleaseManifest` unchanged in permissions and exported components.
- [ ] Commit: `feat(player): picture-in-picture that enters on the swipe home, with play and pause in the small window`.

---

### Task 10: Tablet — two panes for series and films on a wide screen

**Files:**
- Create: `ui/series/SeriesParts.kt` (the header, actions row, season chips, season header and the episodes list block, moved out of `SeriesScreen.kt` unchanged and made `internal`), `ui/series/SeriesTwoPane.kt`, `ui/detail/DetailTwoPane.kt`.
- Modify: `ui/components/Layout.kt` (`TwoPaneWidth = 900.dp`, `@Composable fun isTwoPane(): Boolean`), `ui/series/SeriesScreen.kt` (two-pane branch), `ui/detail/DetailScreen.kt` (two-pane branch), `ui/browse/RenderBase.kt` (`const val TABLET_LANDSCAPE = "w1200dp-h840dp"`), `res/values/strings.xml` (two pane-heading strings).
- Test: `ui/series/SeriesTwoPaneRenderTest.kt`, `ui/detail/DetailTwoPaneRenderTest.kt`; the existing `SeriesRenderTest`, `DetailRenderTest` must pass unchanged (they use 360 and 840 wide).

**Interfaces — Consumes:** `SeriesUiState`, `SeriesActions`, `DetailUiState`, `DetailActions`, `DownloadControls` (unchanged).

**Produces:**
```kotlin
// ui/components/Layout.kt
/** From this window width the series and film pages use two panes (a landscape tablet; not a portrait one, where one 840dp column reads better). */
val TwoPaneWidth = 900.dp
@Composable fun isTwoPane(): Boolean = screenWidth() >= TwoPaneWidth
// ui/series/SeriesTwoPane.kt
@Composable internal fun SeriesTwoPane(state: SeriesUiState, actions: SeriesActions, download: DownloadControls?)
// ui/detail/DetailTwoPane.kt
@Composable internal fun DetailTwoPane(state: DetailUiState, entry: ItemWithUserData, session: Session, actions: DetailActions, download: DownloadControls?)
```
- **Series:** left pane 400dp wide, its own scroll: the hero (or back bar), title and facts, the overview, the actions row, the season chips. Right pane, the rest of the width (up to 840dp, centred in what remains): the season header and the episode list (its own `LazyColumn`; the Continue Watching highlight scrolls this list). A screen-reader heading at the top of each pane: "About the series" and "Episodes". Back stays first in the traversal order. The sheet and banners behave as in one pane.
- **Detail:** left pane 360dp: the poster (or the episode's still), then the Play/Resume and download buttons; right pane: the title, facts, progress, overview and the rest, scrolling. Headings "About" is not added (the title is the heading).
- Below 900dp nothing changes. Split screen and free-form windows follow the window's own width (`LocalConfiguration.screenWidthDp` is the window's).
- No file over 400 lines: `SeriesScreen.kt` shrinks by what moves to `SeriesParts.kt`.

- [ ] **Tests first — `SeriesTwoPaneRenderTest`** (`TABLET_LANDSCAPE`, 1.0 and 2.0, plus `TABLET` to prove one pane is kept): both pane headings shown at 1200 wide and absent at 840; choosing season 2 changes only the right pane (the left pane's overview node keeps its position); the highlighted episode is scrolled into view in the right pane; Back is the first focusable in traversal; every episode row 48dp; at 2.0 both panes scroll to their last element.
- [ ] **Tests first — `DetailTwoPaneRenderTest`** (same configs): poster and Play in the left pane (their left edges < 400dp), the overview in the right; resume label; download button; at 840 the one-column layout (existing assertions) still holds.
- [ ] Implement (move first, run the existing series tests green, then add the two-pane branch).
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.ui.series.*" --tests "app.shoalwatch.ui.detail.*" --tests "app.shoalwatch.SourceShapeTest"` → pass.
- [ ] Commit: `feat(tablet): series and film pages in two panes on a landscape tablet`.

---

### Task 11: The `cast` flavour scaffold — the default build has no Google Play services, proved by the build (Ben to confirm Cast)

**Skip Tasks 11 and 12 if Ben says no to Cast.** Nothing else depends on them.

**Files:**
- Modify: `app/build.gradle.kts`: `flavorDimensions += "distribution"`; `productFlavors { create("standard") { dimension = "distribution"; isDefault = true }; create("cast") { dimension = "distribution"; versionNameSuffix = "-cast" } }` (same `applicationId`); `"castImplementation"(libs.play.services.cast.framework)`, `"castImplementation"(libs.androidx.mediarouter)`; the manifest check registered per release variant with a per-flavour expected permission set (`castReleaseExpectedPermissions` starts equal to the standard set and may only grow by what Task 11's merged-manifest review lists and justifies); a new task `verifyStandardHasNoPlayServices` (resolves `standardReleaseRuntimeClasspath`; fails on any module whose group starts with `com.google.android.gms`, `com.google.firebase`, `com.google.android.datatransport` or equals `androidx.mediarouter`; wired into `check` and `assembleStandardRelease`/`bundleStandardRelease`).
- Modify: `gradle/libs.versions.toml` (`playServicesCastFramework`, `mediarouter`: the newest stable releases at least two weeks old on Google Maven that compile against `compileSdk 35`; record both in ARCHITECTURE "Version checks"), `gradle/verification-metadata.xml` (`./gradlew.bat --write-verification-metadata sha256 :app:assembleCastRelease :app:testCastDebugUnitTest`, then review the diff: every new entry is a cast-only module or its transitive dependency; list them in the security check).
- Create: `data/cast/Casting.kt` (seam + `NoCasting`), `data/cast/CastBlock.kt` (the enum only; `castBlock()` comes in Task 12), `app/src/standard/java/app/shoalwatch/cast/CastingFactory.kt`, `app/src/cast/java/app/shoalwatch/cast/CastingFactory.kt` (returns `NoCasting` in this task), `app/src/cast/AndroidManifest.xml` (Task 12 fills it; here only the `tools` namespace), `app/src/test/java/app/shoalwatch/CastBoundaryTest.kt`.
- Modify: `AppGraph.kt` (`val casting: Casting by lazy { CastingFactory.create(this) }`), `SourceShapeTest.kt` (scan `src/main`, `src/standard`, `src/cast`), `docs/ARCHITECTURE.md` ("Toolchain and build": the new task names and commands), `tools/mockjelly/README.md` if it names a Gradle task.

**Interfaces — Produces:**
```kotlin
// data/cast/Casting.kt — token-free, like every seam the UI sees.
data class CastDevice(val id: String, val name: String) { override fun toString() = "CastDevice(<redacted>)" }
data class CastNow(val session: Session, val itemId: String, val title: String, val deviceName: String,
                   val positionMs: Long, val durationMs: Long?, val playing: Boolean, val ended: Boolean) {
    override fun toString() = "CastNow(<redacted>)"
}
sealed interface CastResult { data object Started : CastResult; data class NotStarted(val block: CastBlock) : CastResult }
interface Casting {
    /** False in the standard flavour, without Play services, or with no Cast device on the network. */
    val available: StateFlow<Boolean>
    val devices: StateFlow<List<CastDevice>>
    val now: StateFlow<CastNow?>
    fun startDiscovery(); fun stopDiscovery()
    /** Connects to [deviceId] and casts [itemId] as [s] from [startMs]. */
    suspend fun cast(s: Session, itemId: String, startMs: Long, deviceId: String): CastResult
    fun playPause(); fun seekTo(ms: Long)
    /** Stops the Chromecast and ends the cast session (the stop report goes as the casting profile if it is still signed in). */
    fun stop()
}
object NoCasting : Casting { /* available/devices/now constant false/empty/null; every call does nothing; cast ⇒ NotStarted(CastBlock.NOT_IN_THIS_BUILD) */ }
// src/standard/…/cast/CastingFactory.kt and src/cast/…/cast/CastingFactory.kt
internal object CastingFactory { fun create(graph: AppGraph): Casting }
```
`CastBlock` is created in Task 12; in this task `CastResult.NotStarted` takes `CastBlock` declared now in `data/cast/CastBlock.kt` with the values listed in Task 12.

`CastBoundaryTest` (source scan; failures name file and line):
- no file under `src/main` or `src/standard` contains `com.google.android.gms`, `androidx.mediarouter`, `CastContext`, `RemoteMediaClient` or `GoogleApiAvailability`;
- every Kotlin file under `src/cast/java` is in package `app.shoalwatch.cast`;
- the text `api_key` appears in `src/main`, `src/standard` and `src/cast` **only** in `src/cast/java/app/shoalwatch/cast/CastStreamUrl.kt` (Task 12; in this task: nowhere);
- `src/standard/java` contains exactly `app/shoalwatch/cast/CastingFactory.kt`.

- [ ] **Tests first — `CastBoundaryTest`** (fails on a planted `import com.google.android.gms.cast.framework.CastContext` in `src/main/java/app/shoalwatch/Planted.kt` before passing).
- [ ] **Tests first — `verifyStandardHasNoPlayServices`** must fail when proved: temporarily add `implementation(libs.play.services.cast.framework)` (not `castImplementation`), run the task, see it fail naming the module, revert.
- [ ] Add the flavours, the version entries, the factories, the checks; regenerate and review verification metadata; record the cast release's merged-manifest additions (components, permissions, `<queries>`, meta-data) in `.superpowers/sdd/m7/cast-manifest.md` for the reviewer.
- [ ] Run the new gate: `./gradlew.bat clean testStandardDebugUnitTest testCastDebugUnitTest assembleStandardDebug assembleStandardDebugAndroidTest assembleStandardRelease assembleCastDebug assembleCastRelease verifyStandardHasNoPlayServices verifyManifestRuleFixtures` → all green.
- [ ] Commit: `build(cast): a separate cast flavour; the standard build is checked to contain no Google Play services`.

---

### Task 12: Casting to a Chromecast (cast flavour), with the controls in the shared UI

**Files:**
- Create (main): `data/cast/CastReports.kt`, `ui/cast/CastScreen.kt`, `ui/cast/CastViewModel.kt`, `ui/cast/CastDeviceSheet.kt`, `res/values/strings_cast.xml`.
- Create (cast): `src/cast/java/app/shoalwatch/cast/GoogleCasting.kt`, `CastRemote.kt` (our interface over `RemoteMediaClient` + `CastSession`, and its real implementation), `CastOptionsProvider.kt`, `CastStreamUrl.kt`, `CastProfile.kt`; `src/cast/AndroidManifest.xml` (the options provider meta-data; `tools:node="remove"` for the Cast framework's `MediaNotificationService`, `ReconnectionService` and `MediaIntentReceiver`).
- Modify: `data/cast/CastBlock.kt` (adds `castBlock()`), `src/cast/…/CastingFactory.kt` (returns `GoogleCasting`), `ui/player/PlayerControls.kt` (a Cast button when `casting.available`), `ui/nav/AppNav.kt` (`Routes.CAST = "cast"`; session-bound, not an account route), `ui/nav/BrowseRoutes.kt` (`PlayerRoute`: the sheet and the hand-over), `AppGraph.kt` or `LockParts.kt` (stop casting on a lock and on every active-session change).
- Test (main): `data/cast/CastBlockTest.kt`, `data/cast/CastReportsTest.kt`, `ui/cast/CastFakes.kt`, `ui/cast/CastViewModelTest.kt`, `ui/cast/CastRenderTest.kt`. Test (cast): `src/testCast/java/app/shoalwatch/cast/CastStreamUrlTest.kt`, `GoogleCastingLogicTest.kt`.

**Interfaces — Consumes:** `Casting` (Task 11), `ServerCatalog.server` (security settings), `ServerConnections.awaitOnline` (the verified address), `StoredToken` through a cast-only accessor `graph.serverStore.cachedToken(serverId, userId)` (read in `CastStreamUrl` only), `PlaybackGateway.playbackInfo` (internal; with `CastProfile`'s body), `PlaybackReports` (`started/progress/stopped`), `LockGate.state`, `ServerCatalog.activeSession`.

**Produces:**
```kotlin
// data/cast/CastBlock.kt
enum class CastBlock { NOT_IN_THIS_BUILD, NO_PLAY_SERVICES, PINNED_CERTIFICATE, CLIENT_CERTIFICATE, CUSTOM_HEADERS, OFFLINE, SAVED_COPY_ONLY, REFUSED, FAILED }
/** Why [server] cannot be cast from, or null. A Chromecast fetches the stream itself, so it cannot use this phone's pinned
 *  certificate, client certificate or custom headers. Plain http only on an address the user allowed (the same `allowCleartext`). */
fun castBlock(server: Server, online: Connection.Online?): CastBlock?
// data/cast/CastReports.kt — the phone reports a cast as the casting profile, every 10 s while playing, through PlaybackReports.
internal class CastReports(private val reports: PlaybackReports, private val clock: MonotonicClock, private val scope: CoroutineScope) {
    fun follow(now: Flow<CastNow?>)
}
// ui/cast/CastViewModel.kt
data class CastUiState(val now: CastNow? = null, val problem: CastBlock? = null) { override fun toString() = "CastUiState(<redacted>)" }
class CastViewModel(private val casting: Casting) : ViewModel() { val state: StateFlow<CastUiState>; fun playPause(); fun seekTo(ms: Long); fun stop() }
// src/cast/…/CastRemote.kt
internal interface CastRemote {
    val status: Flow<RemoteStatus>   // position, duration, playing, idle reason
    fun load(url: String, contentType: String, title: String, startMs: Long)
    fun playPause(); fun seek(ms: Long); fun stop(); fun endSession()
}
// src/cast/…/CastStreamUrl.kt — THE ONLY FILE THAT WRITES `api_key` (CastBoundaryTest).
internal object CastStreamUrl { fun build(verified: ServerAddress, plan: PlaybackPlan, token: String): String }
```
Rules:
- **Receiver:** Google's Default Media Receiver (`CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID`). `CastOptions`: no notification, no lock-screen controls, `setResumeSavedSession(false)`, reconnection service off; the manifest removes the framework's notification and reconnection components, so no foreground service type and no component is added (the cast release manifest check proves it).
- **Device list:** our own Compose sheet over `MediaRouter.getInstance(context)` with the Cast category selector (`CastMediaControlIntent.categoryForCast(appId)`), discovery only while the sheet is open. `MediaRouteButton` is not used (it needs a `FragmentActivity` and an AppCompat theme). Selecting a route lets the Cast framework start the session.
- **What is cast:** only a stream from the server (never a saved copy: `SAVED_COPY_ONLY` when the server is offline but a download exists). `CastProfile` asks the server for H.264 (High, level 4.1) + AAC stereo in HLS, or direct play of an MP4 with those codecs; the resolved plan's URL is built by `CastStreamUrl` on the **verified** address with `api_key`, `MediaSourceId`, `PlaySessionId`, `DeviceId` and nothing else.
- **Hand-over:** in the player, Cast → the sheet → a device → the local player ends at its position (its normal stop report), `casting.cast(...)` from that position, and the player route is replaced by `cast`.
- **Cast screen:** title, "Casting to %1$s", a seek bar, play/pause, "Stop casting". Back leaves the screen and keeps casting; the player's Cast button then opens this screen while `now` is set.
- **Session rule and locks:** an active-session change or any lock (`App`/`Profile`) calls `casting.stop()` (the stop report goes as the casting profile if still signed in), so a Chromecast never keeps playing a profile the phone has left.
- **Never logged:** the URL, token, device name or title.

- [ ] **Tests first — `CastBlockTest`:** a pinned certificate, a client certificate, custom headers each block; offline blocks; plain http with `allowCleartext` passes and without it blocks; an https server with none of them passes.
- [ ] **Tests first — `CastReportsTest`** (`FakePlaybackReports`, virtual time): started once on the first playing status; progress every 10 s only while playing; stopped once at the end or on `null`, with the last position; nothing after that; all as `CastNow.session`.
- [ ] **Tests first — `CastViewModelTest`, `CastRenderTest`** (`FakeCasting`; phone, tablet; 1.0, 2.0): the exact words; play/pause, seek and stop call through; `now` null ⇒ the screen says "Not casting" and offers Back; every block's message; 48dp.
- [ ] **Tests first — the session rule:** with `FakeCasting` playing as A, switching to B (or the gate locking) calls `stop()` once (a test in `ui/cast` or `data/cast` over the `LockParts` wiring function, which is a plain function taking the flows and the casting).
- [ ] **Tests first (cast flavour) — `CastStreamUrlTest`:** the host and scheme are the verified address's; `api_key` is the token and appears once; no other secret (custom header names or values) appears; the path keeps only validated ids (`JellyfinId`); a hostile `TranscodingUrl` with its own host or `../` is refused (the plan's relative path must start with `/Videos/` or `/videos/`).
- [ ] **Tests first (cast flavour) — `GoogleCastingLogicTest`** (fake `CastRemote`): `cast` loads exactly one URL with the start position; status maps to `CastNow`; `stop()` stops and ends the session; a session change mid-load stops it; nothing is loaded for a blocked server.
- [ ] Implement.
- [ ] Run: the Task 11 gate → all green; the cast release manifest adds no exported component, no foreground service type and no permission beyond what `cast-manifest.md` justifies.
- [ ] Commit: `feat(cast): cast a stream to a Chromecast from the player (cast build only), with its controls, progress reports, and the profile rules`.

---

### Task 13: Milestone close

- [ ] **Full verification** (the Task 11 gate if Cast was built, else Tasks 1–10's): all green. `verifyReleaseManifest` (both flavours) passes with exactly the new `USE_BIOMETRIC`; `SourceShapeTest`, `LockBoundaryTest`, `CastBoundaryTest` (if built), `AdminBoundaryTest`, `SyncBoundaryTest`, `DownloadBoundaryTest`, `PlaybackBoundaryTest`, `Media3BoundaryTest`, `ArtworkBoundaryTest`, `GatewayBoundaryTest`, `ManifestSecurityTest`, `SqlCompatibilityTest` pass. `PlaybackController.kt` and `ServerStore.kt` unchanged since Milestone 5. `gradle/verification-metadata.xml` changed only in Task 11.
- [ ] **Controller: emulator run** of every instrumented test including `LockSmokeTest` and `PipSmokeTest`. Then a walkthrough against `tools/mockjelly` (`python -I tools/mockjelly/mock_jellyfin.py 8096`), signed in as `ben`, `anna` and `kid`:
  - protect ben (PIN), switch to kid; from kid try every path into ben: Home menu, Servers radio, "Sign in again" (sign ben's token out with the mock's revoke route first), signing ben in again by password and by Quick Connect, the download notice, an `adb shell am start -d "android-app://androidx.navigation/series/x"` intent, the recents switcher — each ends on the PIN, never on ben's Home;
  - as kid: "Share downloads between profiles", "Delete shared downloads", Connection settings, Remove server and signing ben out each ask for a grown-up's PIN; wrong PINs reach a 30 s wait that survives killing the app;
  - lock after "Straight away" and "After 1 minute" with the screen off and on, and through the client-certificate chooser (no lock);
  - app lock on with the emulator's PIN and fingerprint (`adb emu finger touch 1`); remove the emulator's screen lock ⇒ the app lock is off;
  - autoplay off for kid only ⇒ kid's Up next waits, ben's counts down;
  - PiP: play, swipe home with gesture navigation ⇒ small window; play/pause in it; expand; wait past the lock time in PiP, expand ⇒ the lock;
  - tablet: a 1280x800 landscape tablet emulator profile ⇒ two panes on a series and a film; portrait ⇒ one column; split screen at half width ⇒ one column;
  - cast flavour (if built) on an emulator without Play services ⇒ no Cast button, no crash.
  - Screenshots by eye at font scale 1.0 and 2.0, and a TalkBack pass over the lock screen, PIN dialogs, Settings, and **the watch-and-delete prompt beside Up next** (Milestone 5's carried check).
- [ ] **Whole-milestone review**, code and security against SPEC §6 and the threat model (a child on a shared tablet; a lost phone; another app sending intents; a hostile server; supply chain), focused on: every path into a protected profile; the data-layer refusals; PIN handling (no String, zeroed, masked, secure dialogs, never logged); the verifier and the waits (restart, clock change); `LockGate` rules and the reset on lock (no dialog or player survives); the deep-link strip; `taskAffinity`; recents privacy on 29–32 and 33+; the PiP receiver and `PendingIntent`; the cast flavour's isolation, its one `api_key`, its manifest, and verification metadata. Then a fix wave and a re-review.
- [ ] **`docs/security/milestone-7-check.md`** (structure as `milestone-6-check.md`): verdict; one row per S1–S13 with evidence (S8 now **Met**: `LockGateTest`, `LockSmokeTest`, `RecentsPrivacyTest`; H1 **closed**: `ProfileSwitchTest`, `HouseholdEnforcementTest`, `LockBoundaryTest`; M6 M9 **closed**: `MainActivityIntentTest`; Milestone 5 final review M4 and Milestone 4 L-9 **closed**: `HouseholdEnforcementTest`); the new permission and manifest attributes; the cast flavour's dependency list and merged manifest; findings by severity; residual risks: (1) a child who is told an adult's PIN, or watches it typed, has the adult's profile; (2) Quick Connect approved from a device where the adult is signed in still signs the tablet in as the adult (the PIN is still asked); (3) clearing the app's data in Android settings removes every profile and download (no bypass, but no protection of the data either); (4) on Android 10–12 hiding from recents also blocks screenshots; (5) the library cache is still not encrypted at rest (Milestone 2 I1); (6) cast flavour only: the Chromecast holds a token in its stream URL for the length of the cast, and Google's receiver and Play services are contacted.
- [ ] **ARCHITECTURE.md:** a new section **"Locks and protected profiles (`security/`, `ui/lock`)"** after "Server admin": the verifier, the waits, `ProfileSwitch`, `ProvedSwitches`, `LockGate`'s rules (copy the seven), the reset on lock, the household check and its enforcement points, recents privacy, the deep-link strip and `taskAffinity`, `DeviceAuth`; a section **"Picture-in-picture"** in "Player screen"; **"Two panes"** in "Browsing screens"; a section **"Cast flavour (`data/cast`, `src/cast`)"** if built; "Library cache" gains v6; the package map gains `security/` lines and `data/cast`; "Toolchain and build" the flavours (if built); "Credentials: where they go" the PIN verifier and (cast) the stream URL; the Keystore key's lack of user-authentication binding (Milestone 1 check Q9) explained as a decision; "Known limits".
- [ ] **ChangeLog:** one plain-English line (minor bump), e.g. "vX.Y — DD/MM/YYYY — Protect a profile with a PIN so children can't switch into it or change shared download settings; an optional app lock with your phone's fingerprint or screen lock; keep watching in a small window when you leave the app; series and films in two panes on a tablet; and a setting to stop the next episode playing by itself." (Add "and a separate Chromecast build" only if built.)
- [ ] **TODO.md** via the **project-todo** skill (keeps the Obsidian mirror byte-for-byte): tick the Milestone 7 item, "Protect a profile so a child cannot switch into it" and "Add a setting to turn off playing the next episode by itself"; update Milestone 4 "Ben to confirm" (4) to "done: behind a grown-up's PIN"; add the follow-ups: "Ben to confirm: profile PIN rather than the phone's screen lock" (Decisions); "Ben to confirm: Chromecast — keep the cast build, and the licence wording" (if built); "Ben to confirm: SPEC S1 wording for a Keystore key that needs no unlock (Milestone 1 check Q9)"; "Ben to confirm: the new permission USE_BIOMETRIC". Update the Obsidian project note with the milestone summary.
- [ ] **Device-only list** (for the controller and Ben; nothing installed without Ben's explicit yes): every row of "Unproven until Ben's real device", with results recorded in the security check and ARCHITECTURE.

---

## Strings

Apostrophes are `’` (U+2019) in the XML, the ellipsis `…`, a literal percent `%%` in a string with arguments. Tests read strings through resources, never hard-coded.

**`strings_lock.xml` — PIN (Task 2):** `pin_label` "PIN"; `pin_wrong` "That PIN isn’t right. %1$d tries left before a wait." (as `<plurals name="pin_wrong">` with one "That PIN isn’t right. 1 try left before a wait." and other as given); `pin_wrong_last` "That PIN isn’t right. The next wrong PIN starts a wait."; `pin_wait` "Too many wrong PINs. Try again in %1$s."; `pin_unreadable` "This PIN can’t be checked on this phone any more. Use Forgot PIN."; `pin_rules` "Use 4 to 12 digits.".

**Lock screen (Task 5):** `lock_app_title` "Shoalwatch is locked"; `lock_app_body` "Unlock with your phone’s screen lock."; `lock_unlock` "Unlock"; `lock_prompt_title` "Unlock Shoalwatch"; `lock_prompt_subtitle` "Use your fingerprint, face or screen lock"; `lock_unavailable` "Your phone’s screen lock can’t be used right now. Try again."; `lock_profile_title` "%1$s’s profile is protected"; `lock_profile_body` "Enter the PIN for this profile."; `lock_other_heading` "Use another profile"; `lock_other_row` "%1$s on %2$s"; `lock_other_row_protected_cd` "%1$s on %2$s, protected"; `lock_protected` "Protected"; `lock_forgot` "Forgot PIN?"; `lock_forgot_body` "Sign in to %1$s with its server password. This removes the PIN, and you can set a new one in Settings."; `lock_forgot_submit` "Sign in and remove PIN"; `lock_forgot_offline` "You’re offline. Connect to %1$s to sign in."; `lock_forgot_other_account` "That’s a different account.". (Cancel is the existing `action_cancel`; the password label is the existing `password_label`.)

**Switching and the grown-up check (Task 6):** `switch_pin_title` "Switch to %1$s?"; `switch_pin_body` "%1$s’s profile is protected. Enter its PIN."; `switch_pin_confirm` "Switch"; `profile_protected_cd` "%1$s, protected"; `grownup_title` "A grown-up’s PIN is needed"; `grownup_body` "This setting changes things for everyone on this phone. Choose a protected profile and enter its PIN."; `grownup_confirm` "Continue"; `grownup_race` "A grown-up’s PIN is needed. Try again."; `signout_pin_title` "Sign out %1$s?"; `signout_pin_body` "%1$s’s profile is protected. Enter its PIN to sign it out.".

**Settings (Task 7):** `settings_title` "Settings"; `settings_open` "Settings"; `settings_playback` "Playback"; `settings_profile` "This profile (%1$s)"; `settings_protect` "Protect this profile with a PIN"; `settings_protect_help` "Anyone using this phone will need the PIN to switch to this profile. Use 4 to 12 digits."; `settings_pin_choose` "Choose a PIN"; `settings_pin_repeat` "Enter the PIN again"; `settings_pin_mismatch` "Those PINs don’t match. Try again."; `settings_pin_current` "Enter the current PIN"; `settings_protected` "Protected with a PIN"; `settings_change_pin` "Change PIN"; `settings_remove_pin` "Remove PIN"; `settings_app_lock_heading` "App lock"; `settings_app_lock` "Lock Shoalwatch with this phone’s screen lock"; `settings_app_lock_no_screen_lock` "Set a screen lock on this phone first."; `settings_lock_after` "Lock after"; `settings_lock_after_help` "Also how soon a protected profile asks for its PIN again."; `lock_after_immediately` "Straight away"; `lock_after_1` "After 1 minute"; `lock_after_5` "After 5 minutes"; `lock_after_15` "After 15 minutes"; `settings_privacy` "Privacy"; `settings_hide_recents` "Hide Shoalwatch in recent apps"; `settings_hide_recents_forced` "Always hidden while the app lock is on or a profile is protected."; `settings_hide_recents_old_android` "On this phone this also stops screenshots of Shoalwatch."; `settings_downloads` "Download settings".

**`strings.xml` (Tasks 7, 9, 10):** `settings_autoplay` "Play the next episode automatically"; `settings_autoplay_help` "When an episode ends, the next one starts after a short countdown. For this profile only."; `settings_pip` "Keep playing in a small window when you leave the app"; `settings_pip_help` "Picture-in-picture, while a video is playing."; `player_pip` "Picture-in-picture"; `pip_play` "Play"; `pip_pause` "Pause"; `pane_series_about` "About the series"; `pane_series_episodes` "Episodes".

**`strings_cast.xml` (Task 12):** `cast_button` "Cast"; `cast_choose_title` "Cast to a device"; `cast_searching` "Looking for Cast devices on this network…"; `cast_none` "No Cast devices found. Check the Chromecast is on the same Wi-Fi."; `cast_title` "Casting"; `cast_to` "Casting to %1$s"; `cast_stop` "Stop casting"; `cast_not_casting` "Not casting"; `cast_block_play_services` "Casting needs Google Play services on this phone."; `cast_block_pinned` "This server uses a certificate a Chromecast can’t check, so it can’t be cast."; `cast_block_client_cert` "This server needs a client certificate, which a Chromecast can’t use."; `cast_block_headers` "This server needs custom headers, which a Chromecast can’t send."; `cast_block_offline` "You’re offline. Casting needs a connection to your server."; `cast_block_saved` "Downloads can’t be cast. Casting streams from your server."; `cast_block_refused` "The server refused that. Try again."; `cast_block_failed` "Casting didn’t start. Try again.".

---

## Decisions made

| Decision | Alternative | Why |
|---|---|---|
| **"Forgot PIN" slows wrong passwords (30 s doubling to 15 minutes) but does not stop them; Jellyfin's own lockout (by default 3 wrong passwords for a user, an admin re-enables) can still be reached by a child in about 90 s** — **Ben to choose** | A hard stop after 2 wrong passwords, cleared only by a right one | A hard stop leaves a parent who has truly forgotten the PIN with no way in but clearing the app's data; a server lockout is undone by any admin on the dashboard. Re-review of Tasks 5-6 M5 (`m7/tasks-5-6-fixes-rereview.md`) |
| **A protected profile asks for its own Shoalwatch PIN (4–12 digits)** — **Ben to confirm** | The phone's screen lock or fingerprint (BiometricPrompt), as the to-do item first said | On a family tablet the children usually know the tablet's unlock code, and their fingerprints may be enrolled too; the phone's lock cannot tell the parent from the child. A per-profile PIN can. The app lock (below) uses the phone's lock, because that one guards against a lost or borrowed phone |
| The PIN verifier is PBKDF2-HMAC-SHA256 (100,000 iterations, 16-byte salt, 32-byte result) **sealed by the existing Keystore `SecretBox`**, compared in constant time | A plain hash in Room; a new Keystore HMAC key | Sealing means a copied database cannot be tried offline (a 4-digit PIN is otherwise 10,000 guesses); it reuses a key and code already reviewed (Milestone 1). The iterations keep a check well under half a second on a slow tablet while still costing an attacker who could run code as the app |
| **Five free tries, then 30 s doubling to 15 minutes; the count is in Room; the wait is timed only by this process's monotonic clock, and a restart starts it again** | Wall-clock "locked until"; a permanent lockout | A child can change the clock or kill the app; neither shortens the wait. A permanent lockout would lock a parent out of their own profile. "Forgot PIN" is the way back |
| **Forgot PIN = the account's server password, typed in the lock screen; Quick Connect is not offered there** | Quick Connect too; only "clear the app's data" | Knowing the server password proves the account is yours. A Quick Connect code can be approved from any device where the adult is signed in (the family laptop), so it is not proof |
| **One rule: whenever the active profile becomes a protected one without a switch proved by that profile's PIN, the profile lock engages** (`LockGate` rule 3) | A check on each path (switch, sign-in, expired row …) | One rule covers every path, including a sign-in by password or Quick Connect, "Sign in again", a restore, and any path added later. The switch itself stays one tap plus the PIN |
| **Enforced in the data layer**: `StoreCatalog.setActive` refuses a protected profile; household settings refuse without a pass inside the repositories and the catalog/authenticator | Screens only | Defence in depth, as the admin gate (Milestone 6): a stale screen, a race or a future screen cannot bypass it; `LockBoundaryTest` keeps `setActive` out of `ui/` |
| **Household (grown-up) check** on: sharing on/off, "Delete shared downloads", a server's Connection settings, removing a server, signing out a protected profile (its own PIN), the app lock, "Lock after", "Hide in recent apps". No check when no profile is protected, or when the active profile is protected. A right PIN allows changes for 60 s, ended by a profile change or a lock | A per-setting owner; admin only; every setting | Closes Milestone 4 L-9 and Milestone 5 final review M4 (a child moving "After everyone" to "After anyone" to delete an adult's download). With no protected profile the app behaves exactly as before. 60 s covers a few toggles without leaving a door open |
| Adding a server or an account, and each profile's own settings (autoplay, "When a download finishes playing", auto-download next), are **not** behind the check | Gate them too | They need a server password or only affect that profile. If Ben wants a parent to enforce autoplay off for a child, it is one `guard` call (noted on the to-do) |
| **App lock uses the framework `BiometricPrompt`** (API 30+: `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`; API 29: `setDeviceCredentialAllowed(true)`) behind `DeviceAuth` | `androidx.biometric` | No new dependency, no verification-metadata change, and `MainActivity` stays a `ComponentActivity` (the AndroidX library needs a `FragmentActivity`). Weak biometrics are allowed because this is a gate, not a key: the same things unlock the phone |
| App lock is **off by default** and needs a secure screen lock; if the phone's screen lock is removed, the app lock turns itself off | Refuse to open; keep it on | Removing the phone's lock needs the phone's own credential, so it is not a bypass; an app that can never open again would be worse |
| **One "Lock after" setting for the app lock and protected profiles** (Straight away, 1 min default, 5, 15), counted from the activity stopping or entering picture-in-picture, on the monotonic clock; a cold start always asks | Separate timers; an idle timer while the app is in use | One setting is easier to understand; SPEC S8 asks "on launch and after a timeout", not an in-use idle timer |
| **A lock resets the app to an empty `locked` route; after unlocking, Home** | Keep the place under an overlay | Dialogs and sheets are separate windows that an in-window overlay cannot cover; resetting pops every screen, so no dialog, sheet, player or ViewModel of the locked profile survives (the player sends its stop) — the same rule as a profile switch. The cost (back at Home after the lock time) is small: Continue Watching is one tap |
| The app's own system screens (the unlock prompt, the client-certificate chooser) do not count as leaving; the browser dashboard does | Count everything | Without it "Straight away" would lock in the middle of unlocking or choosing a certificate; the browser is really leaving the app |
| **Recents hidden whenever the app lock is on, a profile is protected, or "Hide in recent apps" is on.** API 33+: `setRecentsScreenshotEnabled(false)`; API 29–32: `FLAG_SECURE` | `FLAG_SECURE` everywhere | Otherwise the preview shows the protected profile's last screen to whoever opens the app switcher (Milestone 2 I3, Milestone 3 I1). On 33+ screenshots stay allowed; older Android has no other switch, and the help line says so |
| **Intents are stripped of data in `MainActivity`; only `open_tab=downloads` is read; `taskAffinity=""`**, both checked (`MainActivityIntentTest`, a manifest rule) | Rely on the lock overlay | Navigation acts on `android-app://androidx.navigation/<route>` data in any intent to the exported launcher (Milestone 6 M9); the app has no deep links, so none is read. `taskAffinity=""` stops another app's activity joining Shoalwatch's task (Milestone 1 Q5) |
| **"Play the next episode automatically" is per profile** (Room `profile_playback`), default on; off ⇒ the Up next card waits for a tap | Device-wide in `playback_prefs` | A parent can turn it off on a child's profile; it goes with the account like the other per-profile settings. `PlaybackController` already takes `autoplayNext`, so it is not edited |
| **A Settings screen** (from Home's account menu and the Servers screen); Download settings stays its own screen, linked | Put everything in Download settings | The to-do item notes there is no settings screen; lock and profile settings are not about downloads |
| **Picture-in-picture:** `setAutoEnterEnabled` while playing on API 31+, `onUserLeaveHint` on 29–30, a button in the player; one play/pause action; a setting to turn auto-enter off (default on) | Only `onUserLeaveHint` (the official app's way); a media service with background audio | Auto-enter is what Android 12+ provides for gesture navigation (the cause of the official app's Android 14+ bug). A media service needs a foreground service type and an exported component (S9, S10; Milestone 3's decision), so background audio stays out |
| The PiP action is delivered by a **receiver registered at run time as not exported**, through an explicit immutable `PendingIntent` | A manifest receiver; the media session's own controls | No new component (S10); `RECEIVER_NOT_EXPORTED` uses androidx.core's existing signature permission (already in the expected set) |
| `configChanges="screenSize|smallestScreenSize|screenLayout|orientation"` on `MainActivity` | Let PiP and rotation recreate the activity | Entering and leaving PiP would otherwise recreate the activity mid-playback; Compose redraws for these changes on its own. Dark mode, font size and locale still recreate |
| Entering PiP counts as leaving for the lock timer; expanding after the lock time shows the lock | PiP counts as in the app | A PiP window left running could otherwise be expanded by a child into a protected profile |
| In PiP the watch-and-delete prompt is not shown; "Ask each time" then leaves the download in place | Show it in the small window | Buttons in a PiP window are not usable; SPEC §3.1 says ignoring the prompt keeps the download |
| **Two panes from 900dp wide**, for Series and Detail only | From 840dp (Material's "expanded"); every screen | Ben's tablet in portrait (about 800–840dp) reads better as one 840dp column, which the app already has; the existing tablet tests at 840 keep their meaning. Lists, Home and Downloads already use the width well |
| **Chromecast is a separate `cast` flavour; `standard` is the default and has no Play services, proved by `verifyStandardHasNoPlayServices` on the resolved classpath and `CastBoundaryTest` on the sources.** Same `applicationId` | One build with Cast; a second module | SPEC §2 and §9 ("separable build flavour"). A classpath check catches a transitive dependency that a source scan cannot. One module (SPEC "Structure") |
| **Cast is not in the first release unless Ben says so** — **Ben to confirm** | Ship both | The cast flavour breaks three things the rest of the app keeps: S7 (Play services and Google's receiver page are contacted), S6 (the Chromecast must be given the token in its stream URL, because it fetches the video itself), and GPL-3.0 distribution (linking Google's proprietary library needs an additional permission from the copyright holder, Ben, in `LICENSE`). F-Droid would build only `standard` |
| Google's **Default Media Receiver**; the phone sends the progress reports as the casting profile | Jellyfin's own Cast receiver app | The default receiver is maintained by Google and needs no third-party page; Jellyfin's receiver is hosted by the Jellyfin project and would also receive the token |
| **Servers with a pinned certificate, a client certificate or custom headers cannot cast** (`castBlock`) | Try anyway | The Chromecast cannot use any of them; failing clearly is better than a black screen, and the token is never handed over for a server that cannot work |
| Downloads are never cast | Serve the file from the phone | That needs a local HTTP server on the phone, which is a new listening port for any app on the network |
| **A profile change or any lock stops casting** | Keep casting on the TV | The session rule (Milestone 3): nothing plays as a profile the phone has left |
| Our own Compose device sheet over `MediaRouter` | `MediaRouteButton` | It needs a `FragmentActivity` and an AppCompat theme |
| The cast framework's notification, lock-screen and reconnection components are removed | Keep the Cast notification | They add a media foreground service and components the manifest rules forbid; the Cast screen is the control |
| Room v6 adds two tables and rebuilds none | Add columns to `profile_download_settings` | `ALTER TABLE ADD COLUMN` breaks `MigrationTest` here (Milestone 5's lesson); new tables are the simplest migration |
| Wiring in `LockParts.kt` | Grow `AppGraph.kt` | `AppGraph` is near the 400-line limit (as `HousekeepingParts`) |
| `tools/mockjelly` is not changed | Add lock routes | Everything new is on the phone; the mock already has ben, anna and kid |
| The Keystore key stays without user-authentication binding; the reason goes in ARCHITECTURE, and the SPEC S1 wording is left to Ben | Bind it to the app lock | Background downloads and sync decrypt tokens while the phone is locked (Milestone 1 Q9); agents never edit SPEC |

## Unproven until Ben's real device

Checked once, at the end of the build, on Ben's phone and tablet (with his explicit yes before anything is installed), and his server for Cast. The code already takes a safe path either way; this test says which path real use takes.

| Question | The code's path either way | What to look for |
|---|---|---|
| Does picture-in-picture enter smoothly on the swipe home with gesture navigation on Ben's Android 14+ phone? | `setAutoEnterEnabled` while playing; the button as a fallback | Swipe home mid-film: a small window, no black flash, playback continues; play/pause works; expanding shows the controls; closing the window pauses |
| Does the OEM's launcher honour `setRecentsScreenshotEnabled(false)`? | API 33+ uses it; 29–32 uses `FLAG_SECURE` | With a profile protected, the app switcher shows a blank card, not the last screen |
| Does `BiometricPrompt` accept Ben's fingerprint, face and PIN, and fall back to the PIN after failed fingerprints? | `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` | Unlock with each; no loop of prompts with "Straight away" |
| Android 10 (API 29) app lock path (`setDeviceCredentialAllowed`) | Implemented, untested (no API 29 emulator image: see the to-do) | Only if Ben has an Android 10 device |
| How long does a PIN check take on Ben's slowest device? | 100,000 iterations | Under half a second from "Unlock" to Home |
| Do the lock timings hold with the screen off, Doze and a reboot? | Monotonic clock; cold start always locks | Lock after 1 minute: back within 50 s ⇒ no PIN; after 70 s ⇒ PIN; after a reboot ⇒ PIN |
| Does `taskAffinity=""` change how the app comes back from the launcher, a notification or PiP? | One task; the launcher intent brings it forward | No second copy of the app in recents; the download notice opens Downloads (behind the lock if due) |
| Do `configChanges` cause any layout that does not update on rotation or folding? | Compose redraws on configuration | Rotate the player, Home and Settings; fold/unfold if Ben has a foldable |
| Two panes on Ben's tablet in landscape and split screen | 900dp threshold | Series and film pages in two panes in landscape; one column in portrait and at half width |
| **TalkBack and font scale 2.0 with the watch-and-delete prompt beside Up next** (Milestone 5 carried) | Traversal order fixed and tested in Robolectric (`PlayerCornerOrderTest`) | On a small phone in landscape: both cards readable and reachable in order; TalkBack reads the prompt, then its buttons, then Up next |
| TalkBack on the lock screen and PIN dialogs | Password semantics on the PIN field | The digits are never read aloud; each answer line is announced |
| A real child profile on Ben's server: every path into an adult's protected profile | `LockGate` rule 3 and the data-layer refusals | From the child's profile none of the walkthrough's paths reaches the adult's Home without the PIN |
| **Cast (if built):** does Ben's Chromecast play his server's stream through the Default Media Receiver? | HLS H.264/AAC or direct MP4 | A film and an episode start at the right position; seek, pause and stop work; the server dashboard shows the phone's session with the right progress |
| Cast: is Ben's server reachable from the Chromecast with a certificate it accepts? | Pinned, client-certificate and custom-header servers are blocked up front | Record which applies; a reverse proxy with a public certificate is expected to work |
| Cast: does the server's dashboard or log show the `api_key` in the stream URL? | The token is only in the URL given to the Chromecast | Record what appears in the server log, so Ben can judge the S6 exception |
