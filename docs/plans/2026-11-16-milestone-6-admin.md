# Milestone 6 — Server Admin Screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the active account is confirmed by its own server to be an administrator, Servers and accounts shows a **Server admin** button for that server. It opens native screens (SPEC §3.4) that show and act on the server live: a dashboard with the server's name, version and "restart needed", and **who is watching** right now (user, device, app, what is playing, how far in, and in plain words whether the server is converting it and why), refreshing every 5 seconds only while it is on screen, with Message, Pause and Stop for a session; **Libraries** (scan all or one, with progress); **Scheduled tasks** (state, last run and result, progress, run now, stop); **Users** (list, last seen, administrator/disabled/downloads/libraries, enable or disable); **Activity** (paged, newest first, with severity); **Devices** (last seen, who, remove one); **Restart or shut down** behind a typed confirmation; and a button that opens the full Jellyfin dashboard in the phone's browser without passing any sign-in. Every destructive action names its target and needs explicit confirmation, is sent once and never retried, and is switched off while offline. Nothing is cached or saved: the moment the profile changes, the account stops being an administrator, or the server answers 401, every admin screen and everything it loaded is gone, and the recents preview never showed any of it.

**Architecture:** One new data package, `data/admin`, and one new UI package, `ui/admin`; shared files get only small, listed, additive edits so this branch (built from `main` in parallel with Milestones 4 and 5) merges cleanly. `data/admin/Admin.kt` is the seam screens use (`Admin`: one suspend function per server action, taking a token-free `Session`, returning validated app models from `AdminModels.kt`). Its only implementation, `data/admin/api/AdminGateway` (internal), sends every request through `ServerApi` (the one authenticated call path: warm gate, `AddressSelector.awaitOnline`, the account's own client from `ServerClients`, token only in `Authorization`, 401 invalidation), and refuses with nothing sent unless `AdminRights.allows(session)` says that exact session is a server-confirmed administrator. `AdminAccess` (the `AdminRights` implementation) asks `GET /Users/Me` and holds the answer **in memory for the active session only**; it forgets on every active-session change. DTOs and mappers (`api/AdminDtos.kt`, `api/AdminMappers.kt`) are the S11 boundary: bounded lists and strings, validated ids, `displayText`, range-checked numbers, element-level tolerance, and the fields that carry secrets or addresses (`DeviceInfoDto.AccessToken`, `SessionInfoDto.RemoteEndPoint`, `SystemInfo.LocalAddress` and every path) are never declared, so they are never decoded. The user enable/disable change round-trips the server's own `UserPolicy` JSON object untouched except `IsDisabled` (`api/UserPolicyEdit.kt`), so fields this app does not know are never reset. `BrowserDashboard` opens `{verified address}/web/` in the browser with no token, query or header. In the UI, every admin ViewModel extends `AdminScreenModel`, which owns an `AdminGuard` (the session that opened the screen, the rights check, a re-check every 60 s while visible, the server's connection, and the closing rules), an `AdminPoller` (loads only while the screen is visible, the gate is open and the server is online; one load at a time) and an `AdminWork` (one action at a time, as the gate's session, never retried). Screens draw through `AdminScaffold`, which sets `FLAG_SECURE` and draws nothing at all once the gate is closed; the route then pops every admin screen.

**Tech Stack:** as Milestone 3. **No new dependencies, no new permissions, no manifest change, no Room schema change** (Milestones 4 and 5 own schema v4 and v5), no `SharedPreferences`, no DataStore. Icons from `material-icons-core` only (already a dependency). Python 3.11 standard library for the stand-in server.

## Global Constraints

- **Read first:** `docs/SPEC.md` (all; especially §3.4, §4, §6, §7 row 6), `docs/ARCHITECTURE.md` (all of it — "Layer rules", "Network security model", "Authenticated requests (`ServerApi`)", "Token rules", "UI (Task 8)" including secrets in the UI, tapjacking and layout rules, "Browsing screens (Milestone 2)" including "One rule for every screen: the session"), `docs/security/milestone-1-check.md`, `milestone-2-check.md`, `milestone-3-check.md`, `research/android-client-flaws-research.md` (row "Jellyfin for Android": the official app's admin is jellyfin-web in a WebView — the weakness SPEC §3.4 rules out). This plan's own "Decisions made" and "Unproven until Ben's real server" sections bind every task.
- **API reference:** `docs/reference/jellyfin-openapi-12.4.0.json`. Every endpoint, parameter, field and enum value used MUST be checked against it; do not rely on memory of the Jellyfin API. The table in Task 3 lists, for each endpoint, the security requirement the reference declares. Decode with `JsonBodies.JSON` (`ignoreUnknownKeys` only); every DTO field nullable with a default. Where the reference is silent, take the path that is correct either way (recorded per case in "Decisions made").
- **Parallel-branch rule:** this milestone is built on its own branch from `main` while Milestones 4–5 change `data/downloads`, the player and the Downloads tab. New code goes only in `data/admin/`, `ui/admin/`, `res/values/strings_admin.xml`, new test files, and `tools/mockjelly/mock_admin.py` + `test_mock_admin.py`. The ONLY edits to existing files are the ones each task's **Files** list names, each additive and small: `AppGraph.kt` (one block at the end of the class), `ui/nav/AppNav.kt` (one call and one import), `ui/nav/Routes.kt` (`ServersRoute` only), `ui/servers/ServersViewModel.kt` and `ServersScreen.kt` (the entry point, optional parameters with defaults so existing tests compile unchanged), `ui/components/SecureWindow.kt` (KDoc only), `tools/mockjelly/mock_jellyfin.py` (import, two hooks, three flags), `tools/mockjelly/README.md`, `docs/ARCHITECTURE.md`, `ChangeLog.txt`, `TODO.md`. Do not touch `strings.xml`, `AppError.kt`, `ShoalwatchApp.kt`, `AndroidManifest.xml`, `AppDatabase.kt`, any DAO, `ui/components/Fakes`-style shared test files (`ui/Fakes.kt`), or any player, browsing or downloads file.
- Package root `app.shoalwatch`; `minSdk 29`, `compileSdk`/`targetSdk 35`. Build with `JAVA_HOME=/path/to/jdk-17`. Verify with `./gradlew.bat clean testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease` (the release build runs `verifyReleaseManifest`; it must pass unchanged). No dependency changes, so no verification-metadata change: if `gradle/verification-metadata.xml` shows a diff, something is wrong — stop and fix.
- **Security rules that bind every task** (carried from Milestone 3 and extended): only `HttpClientFactory` builds OkHttp clients (via `ServerClients`; `OkHttpClient.newBuilder()` counts as building one and is forbidden); no client follows redirects; a token goes only to an address that `AddressSelector.awaitOnline(serverId)` has verified, and only in the `Authorization` header (every admin request goes through `ServerApi`; never `api_key`/`ApiKey` in a URL); plain http only for a server the user explicitly allowed (`CleartextGuard`); **never log, print or put in an exception message a token, header value, URL, hostname, IP address, user name, device name, item title, task error text or message text** (log by `AppError.Kind` or a fixed word only; every admin model overrides `toString` to `"<Type>(<redacted>)"`); the UI layer cannot reach tokens, URLs, `ServerApi`, `data/admin/api`, OkHttp or an `Intent` URI (`AdminBoundaryTest`); **server responses are untrusted** (bounded list sizes and string lengths, ids validated — user and library ids with `JellyfinId`, session and task ids `[A-Za-z0-9]{1,64}`, device ids 1..256 printable ASCII without spaces — display strings through `displayText`, numbers range-checked, dates parsed leniently to null); **destructive actions** (restart, shut down, remove a device, disable or enable a user, stop or pause someone's playback, stop a task) need explicit confirmation naming the target, are sent exactly once and **never retried automatically** (`ServerApi.post`/`delete` with `retry = false`), and are disabled while the server is not `Online`; restart and shut down need a **typed** confirmation (SPEC §3.4); admin calls are sent only as a session that `AdminRights` confirmed as administrator of that server (SPEC §3.4 last bullet; the server enforces the real permission — the client gate exists so no dead ends are shown and nothing is sent as a non-admin); no new permissions and no new dependencies; no third-party hosts (the browser dashboard is opened on the user's own verified address); **`FLAG_SECURE` IS set on every admin screen** (`SecureWindow` in `AdminScaffold`; dialogs use `SecureDialog`), because they show other household members' names, viewing and devices, and the recents preview of a shared tablet must never keep that under another profile.
- **Session rule (shared tablet):** every admin screen belongs to the session that opened it. When `ServerCatalog.activeSession()` stops being that session (an account switch, sign-out, a removed account or server, a 401 that ends the sign-in), or `AdminRights` stops confirming it as administrator (a `/Users/Me` re-check, a 403 that a re-check confirms, a switch), the gate closes for good: the screen draws nothing, the ViewModel drops everything it loaded and any open confirmation or typed text, no further request is sent, and the route pops every admin screen (`ClearBrowsingOnSessionChange` already pops them on a session change: admin routes are deliberately NOT account routes). A request already sent when the gate closes is not undone (it reached the server); its answer is dropped. Nothing from an admin screen is written to Room, `SharedPreferences`, a file, `SavedStateHandle`, a navigation argument, the clipboard or a notification.
- **Data rules:** admin data is live and held only in ViewModel memory while its screen exists. `AdminAccess` holds one `(Session, AdminStatus)` in memory, cleared on every active-session change. No Room table, no preferences file: nothing needs to persist (see "Decisions made").
- TDD with behavioural RED. Real OkHttp + MockWebServer + the real `ServerApi` and an in-memory store for the gateway (`AdminGatewayTestBase`, as `PlaybackGatewayTestBase`); virtual time for every timer (`StandardTestDispatcher`, `MainDispatcherRule`); fakes at the seams for ViewModels (`FakeAdmin`, `FakeAdminRights`, `FakeDashboardOpener` in `ui/admin/AdminFakes.kt`, plus the existing `FakeCatalog` and `FakeConnections`); Robolectric + Compose test for screens (as `RenderBase`: phone 360x640dp and tablet 840x1200dp, font scale 1.0 and 2.0). Every control has a TalkBack label naming its target, a 48dp target, and works at font scale 2.0; nothing under 13sp; left-aligned paragraphs. No file over ~400 lines (`SourceShapeTest`). UK English, plain wording; every user-facing word in `app/src/main/res/values/strings_admin.xml`, exactly as written in this plan.
- Commits: conventional, each ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. ChangeLog line per user-visible change (`vX.Y — DD/MM/YYYY — plain English`, minor bump from the top line at the time of committing). Keep ARCHITECTURE.md accurate. Never edit `docs/SPEC.md`. **Never start an emulator, run connected tests or touch a device — the controller does that. Nothing is installed on Ben's phone without his explicit yes.** Never contact any real Jellyfin server; only MockWebServer and `tools/mockjelly`.

---

## File structure (new and changed)

```
app/src/main/java/app/shoalwatch/
├── AppGraph.kt                                (modify: one block — admin, adminRights, browserDashboard)
├── data/admin/                                (new package)
│   ├── Admin.kt                               the seam screens use (interface Admin)
│   ├── AdminModels.kt                         ServerStatus, WatchSession, NowPlaying, RemoteCommand, AdminPlayMethod, ConversionReason,
│   │                                          PlayingKind, LibraryFolder, LibraryKind, ServerTask, TaskState, TaskRun, TaskOutcome,
│   │                                          ServerUser, ActivityEntry, ActivityPage, Severity, ServerDevice, FULL_SCAN_TASK_KEY
│   ├── AdminRights.kt                         AdminStatus, AdminState, AdminRights, AdminAccess, AdminMe (internal)
│   ├── BrowserDashboard.kt                    DashboardResult, DashboardOpener, BrowserDashboard
│   └── api/AdminDtos.kt, AdminMappers.kt (+ AdminLimits), UserPolicyEdit.kt, AdminGateway.kt   (all internal)
├── ui/components/SecureWindow.kt              (modify: KDoc only — admin screens are now holders too)
├── ui/nav/AppNav.kt                           (modify: adminDestinations(graph, nav) in the NavHost)
├── ui/nav/Routes.kt                           (modify: ServersRoute passes the rights seam and onAdmin)
├── ui/servers/ServersViewModel.kt, ServersScreen.kt   (modify: the "Server admin" entry)
└── ui/admin/                                  (new package)
    ├── AdminNav.kt                            AdminRoutes, adminDestinations (real + test overload), leaveAdmin, the route wiring
    ├── AdminGuard.kt                          AdminGate, AdminProblem, AdminGuard
    ├── AdminPoller.kt, AdminWork.kt (+ AdminNotice), AdminScreenModel.kt (+ AdminFrame)
    ├── AdminText.kt                           plain-words mappers (pure, through ui/present/Strings)
    ├── AdminScaffold.kt, AdminDialogs.kt, AdminParts.kt
    ├── DashboardViewModel.kt, DashboardScreen.kt, SessionCards.kt
    ├── LibrariesViewModel.kt, LibrariesScreen.kt, TasksViewModel.kt, TasksScreen.kt
    ├── UsersViewModel.kt, UsersScreen.kt, ActivityViewModel.kt, ActivityScreen.kt
    ├── DevicesViewModel.kt, DevicesScreen.kt, PowerViewModel.kt, PowerScreen.kt
    └── AdminPreviews.kt
app/src/main/res/values/strings_admin.xml      (new: every admin string)
app/src/test/java/app/shoalwatch/AdminBoundaryTest.kt
app/src/test/java/app/shoalwatch/data/admin/   AdminMappersTest, AdminHostileTest, UserPolicyEditTest, AdminGatewayTestBase,
                                               AdminGatewayTest, AdminGatewayRulesTest, AdminAccessTest, BrowserDashboardTest
app/src/test/java/app/shoalwatch/ui/admin/     AdminFakes, AdminGuardTest, AdminPollerTest, AdminWorkTest, AdminTextTest,
                                               AdminPartsRenderTest, AdminNavigationTest, DashboardViewModelTest,
                                               DashboardSessionsTest, DashboardRenderTest, LibrariesViewModelTest,
                                               TasksViewModelTest, LibrariesTasksRenderTest, UsersViewModelTest, UsersRenderTest,
                                               ActivityViewModelTest, DevicesViewModelTest, ActivityDevicesRenderTest,
                                               PowerViewModelTest, PowerRenderTest
app/src/test/java/app/shoalwatch/ui/servers/   ServersAdminEntryTest, ServersAdminEntryRenderTest
app/src/test/resources/jellyfin/admin/         system-info.json, sessions.json, virtual-folders.json, tasks.json, users.json,
                                               user-policy.json, activity.json, devices.json, hostile-*.json
app/src/androidTest/java/app/shoalwatch/ui/AdminSmokeTest.kt
tools/mockjelly/mock_admin.py, test_mock_admin.py  (new); mock_jellyfin.py, README.md (modify)
docs/security/milestone-6-check.md
```

---

### Task 1: The stand-in server learns admin (users, sessions, scans, tasks, activity, devices, power)

The app's tests use MockWebServer; this is for the controller's emulator walkthrough and the smoke-test shapes. It needs an admin user, a non-admin user, a disabled user, and flags for the behaviours that must be seen both ways.

**Files:**
- Create: `tools/mockjelly/mock_admin.py`, `tools/mockjelly/test_mock_admin.py`.
- Modify: `tools/mockjelly/mock_jellyfin.py` (only: `sys.path` line + `import mock_admin`; `mock_admin.setup(...)` as the last line of `build_catalogue()`; in `/users/authenticatebyname` try `mock_admin.sign_in(creds)` first; in `dispatch` after the logout branch `if mock_admin.handle(self, method, path, q, raw, token): return`; three `argparse` flags copied into `mock_admin.CONFIG`; one docstring line), `tools/mockjelly/README.md` (flags rows, users rows, an "Admin routes" section).

**Interfaces:** Consumes `mock_jellyfin`'s `Handler.reply`, `SERVER_ID`, `USER_ID`, `USER_NAME`, `FILMS_ID`, `TV_ID`, `guid`, `NOW` (passed into `setup`, so `mock_admin` imports nothing from `mock_jellyfin` and there is no import cycle). Produces (paths exactly as the 12.4.0 reference; every route needs a token this server issued, else 401):

```python
# tools/mockjelly/mock_admin.py
CONFIG = {"ben_admin": True, "pending_restart": False, "admin_forbidden": False}
EVENTS = []          # admin actions received, newest last: {"Kind", "Target", "Query"}; message text kept for /mock/admin-events, never printed
def setup(server_id, ben_id, ben_name, guid, now, libraries):  # libraries: [(id, name, collection_type)]
def sign_in(creds):  # -> {"Id", "Name"} for kid/kid-password; None for anything else (ben stays in mock_jellyfin); guest is disabled -> None
def token_user(token):  # -> user id for a token (ben when the token was issued by mock_jellyfin's own sign-in)
def handle(handler, method, path, q, raw, token):  # -> True when it answered
```

| Route | Mock behaviour |
|---|---|
| `GET /Users/Me` | the token's user as `UserDto` with `Policy` (overrides the old Policy-less answer) |
| `GET /Users` | admin: all three users; non-admin: only themselves |
| `GET /Users/{userId}` | admin: any user; non-admin: only themselves, else 403 |
| `POST /Users/{userId}/Policy` | admin only (403 otherwise). 400 unless the body has `AuthenticationProviderId`, `PasswordResetProviderId` **and the seeded `MockFutureField: 7`** (proves the app posts back what it read); applies `IsDisabled` only; a disabled user's tokens then get 401 everywhere |
| `GET /Sessions` | `activeWithinSeconds` accepted. Admin: the seeded sessions plus one per issued token (built from that request's `Authorization` `DeviceId`/`Device`/`Client`/`Version`); non-admin: only sessions with their own `UserId` |
| `POST /Sessions/{sessionId}/Message` | 400 without `Text`; 404 unknown session; 403 for a non-admin and another user's session; 204 and an event |
| `POST /Sessions/{sessionId}/Playing/{command}` | `Pause` sets `IsPaused`; `Stop` removes `NowPlayingItem`, `PlayState.PositionTicks`, `TranscodingInfo`; other commands 204 recorded; 404/403 as above |
| `GET /Library/VirtualFolders` | Films (`movies`) and TV (`tvshows`), `ItemId` = the library ids, `RefreshStatus` `Idle`, `RefreshProgress` null; each GET while scanning adds 25 to progress; at 100 back to `Idle`/null; `Locations` set to deliberate paths (`/srv/media/films`) the app must not show |
| `POST /Library/Refresh` | admin only; every library `Active` at 0, task `RefreshLibrary` `Running` at 0 |
| `POST /Items/{itemId}/Refresh` | admin only; a library id sets that library `Active` at 0 and records the query; another known item 204; unknown 404 |
| `GET /ScheduledTasks` | admin only. Tasks: "Scan Media Library" (`Key` `RefreshLibrary`, `Category` `Library`, last `Completed` yesterday), "Clean Cache Directory" (`DeleteCacheFiles`, `Maintenance`, last `Completed`), "Extract Chapter Images" (`RefreshChapterImages`, `Library`, last `Failed`, `ErrorMessage` "ffmpeg exited with code 1"), "Clean Activity Log" (`CleanActivityLog`, `Maintenance`, never run: `LastExecutionResult` null), and "Mock hidden task" with `IsHidden` true, returned only when `isHidden` is not `false`. Each GET adds 25 to a running task; at 100 it is `Idle` with a `Completed` result |
| `POST` / `DELETE /ScheduledTasks/Running/{taskId}` | admin only; start: `Running` at 0; stop: `Cancelling` on the next GET, then `Idle` with `Cancelled`; unknown id 404 |
| `GET /System/ActivityLog/Entries` | admin only; 120 seeded entries (ids 1..120, every 2 h back from now, every 10th `Warning`, every 25th `Error`, the rest `Information`; names such as "kid is playing Harbour Lights on Living room TV"; sign-in entries carry `ShortOverview` "IP address: 192.168.1.20"); `startIndex`/`limit` honoured; newest first; `TotalRecordCount` 120 |
| `GET /Devices` | admin only; "Living room TV" (`tv-living-room-1`, kid, Jellyfin Android TV 0.17.0, 5 min ago), "Ben's laptop" (`web-ben-laptop`, ben, Jellyfin Web 10.10.7, 2 h ago), "Kid's tablet" (`tablet-kid`, kid, 3 days ago), plus one per issued token from its `DeviceId`; **every device carries `"AccessToken": "MOCKDEVICETOKEN-…"` deliberately** (the app must never show or keep it) |
| `DELETE /Devices?id=` | admin only; 400 without `id`; 404 unknown; removes it, and revokes every token issued to that device id |
| `GET /System/Info` | `ServerName` "Test Jellyfin", `Version` "12.4.0", `Id`, `HasPendingRestart` (flag), `IsShuttingDown` false, plus deliberate `LocalAddress` "http://192.168.1.10:8096", `LogPath`, `ProgramDataPath` the app must not show |
| `POST /System/Restart`, `POST /System/Shutdown` | admin only; 204, recorded; restart clears `HasPendingRestart`. The mock never stops itself |
| `POST /mock/demote`, `POST /mock/promote` | test-only (need ben's token): ben's `IsAdministrator` false / true at run time, to see the screens close mid-use |
| `GET /mock/admin-events` | test-only: `EVENTS` as JSON |

Users: **ben** / `test-password` (`USER_ID`, administrator unless `--ben-not-admin`, `EnableContentDownloading` true, `EnableAllFolders` true); **kid** / `kid-password` (`guid("user-kid")`, not administrator, `EnableContentDownloading` false, `EnableAllFolders` false, `EnabledFolders` [Films id]); **guest** (`guid("user-guest")`, `IsDisabled` true, never signed in, cannot sign in). Every `Policy` also holds both provider ids (`Jellyfin.Server.Implementations.Users.DefaultAuthenticationProvider`, `Jellyfin.Server.Implementations.Users.DefaultPasswordResetProvider`) and `"MockFutureField": 7`.
Seeded sessions: kid on "Living room TV" (Jellyfin Android TV 0.17.0) playing Harbour Lights S2 E3 at 12 min 30 s of 45 min, `PlayMethod` `Transcode`, `TranscodingInfo` `IsVideoDirect` false, `IsAudioDirect` true, `TranscodeReasons` ["VideoCodecNotSupported"], `SupportsMediaControl` true, `SupportedCommands` includes `DisplayMessage`, `RemoteEndPoint` "192.168.1.20" (deliberate); ben on "Ben's laptop" (Jellyfin Web 10.10.7) playing a film, `DirectPlay`, `IsPaused` true; kid on "Kid's tablet", nothing playing, `SupportsMediaControl` false, `SupportedCommands` [].
Flags: `--ben-not-admin` (the entry must not appear), `--pending-restart` (the "Restart needed" notice), `--admin-forbidden` (every `RequiresElevation` route answers 403 while `/Users/Me` still says administrator: the app must say "refused" and stay).

- [ ] **Tests first — `test_mock_admin.py`** (runs the real `mock_jellyfin.Handler` in-process like `test_mock_jellyfin.Served`; signs in as ben and as kid): every admin route is 401 without a token; ben's `/Users/Me` has `Policy.IsAdministrator` true and kid's false; kid gets 403 from `/ScheduledTasks`, `/Devices`, `/System/ActivityLog/Entries`, `/Library/Refresh`, `/System/Restart`, `/Users/{ben}/Policy`; kid's `/Sessions` holds only kid's sessions; ben's `/Sessions` holds the three seeded ones plus his own (with his `DeviceId`); Pause sets `IsPaused`, Stop removes `NowPlayingItem`; a message without `Text` is 400; Policy POST without `MockFutureField` is 400 and with it disables kid, after which kid's token gets 401; guest cannot sign in; `/Library/Refresh` then four GETs of VirtualFolders goes Active 0 → 25 → 50 → 75 → 100/Idle; `/ScheduledTasks?isHidden=false` leaves the hidden task out; start then DELETE gives Cancelling then Idle/Cancelled; activity paging `startIndex=50&limit=50` gives ids 70..21 newest first; deleting kid's device id revokes nothing of ben's but deleting ben's own device id revokes ben's token; `--ben-not-admin` and `/mock/demote` both make `/Users/Me` say false; `--admin-forbidden` makes `/ScheduledTasks` 403 for ben while `/Users/Me` says true; every `/Devices` item has `AccessToken`. Also run `test_mock_jellyfin.py` unchanged (browsing and playback still pass; the old `/Users/Me` test, if any, expects the new Policy-carrying shape).
- [ ] Implement `mock_admin.py` (standard library only; run with `python -I`, so `mock_jellyfin.py` adds its own folder to `sys.path` before `import mock_admin`). The log line stays method + path + auth/token yes/no + status; nothing else is printed. Keep `mock_admin.py` under ~400 lines.
- [ ] README: users table gains kid and guest; flags table gains the three flags; new "Admin routes" section lists every route above with one `curl` example each, and says plainly that `/mock/*` routes are not Jellyfin routes.
- [ ] Run: `python -I tools/mockjelly/test_mock_admin.py` and `python -I tools/mockjelly/test_mock_jellyfin.py` → all pass.
- [ ] Commit: `test(mockjelly): admin routes, an admin, a child and a disabled user, and flags for both ways`.

---

### Task 2: Admin models, wire types and the untrusted-input boundary (pure)

**Files:**
- Create: `data/admin/AdminModels.kt`, `data/admin/api/AdminDtos.kt`, `data/admin/api/AdminMappers.kt`, `data/admin/api/UserPolicyEdit.kt`.
- Test: `data/admin/AdminMappersTest.kt`, `data/admin/AdminHostileTest.kt`, `data/admin/UserPolicyEditTest.kt`; fixtures `app/src/test/resources/jellyfin/admin/{system-info,sessions,virtual-folders,tasks,users,user-policy,activity,devices}.json` written from the reference schemas `SystemInfo`, `SessionInfoDto` (+ `PlayerStateInfo`, `TranscodingInfo`, `BaseItemDto`), `VirtualFolderInfo`, `TaskInfo` (+ `TaskResult`), `UserDto` (+ `UserPolicy`), `ActivityLogEntryQueryResult`, `DeviceInfoDtoQueryResult`; and `hostile-{sessions,tasks,users,activity,devices,system-info}.json`.

**Interfaces — Consumes:** `displayText`, `JellyfinId`, `Ticks`, `JsonBodies.JSON`.

**Produces:**
```kotlin
// data/admin/AdminModels.kt — every type overrides toString() to "<Type>(<redacted>)".
/** GET /System/Info, the four fields shown. Addresses, paths and ports in that answer are never read. */
data class ServerStatus(val name: String, val version: String, val pendingRestart: Boolean, val shuttingDown: Boolean)

/** The two `PlaystateCommand` values this app sends (12.4.0 enum: Stop, Pause, Unpause, NextTrack, PreviousTrack, Seek, Rewind, FastForward, PlayPause). */
enum class RemoteCommand(val wire: String) { PAUSE("Pause"), STOP("Stop") }
/** `PlayerStateInfo.PlayMethod` (12.4.0: Transcode, DirectStream, DirectPlay). */
enum class AdminPlayMethod { DIRECT_PLAY, DIRECT_STREAM, TRANSCODE }
/** `TranscodingInfo.TranscodeReasons` grouped into what can be said in plain words. */
enum class ConversionReason { VIDEO_FORMAT, AUDIO_FORMAT, FILE_TYPE, SUBTITLES, BITRATE, HDR, AUDIO_CHANNELS, DIRECT_PLAY_FAILED, OTHER }
enum class PlayingKind { FILM, EPISODE, OTHER }

data class NowPlaying(
    val kind: PlayingKind, val title: String, val seriesName: String?, val season: Int?, val episode: Int?,
    val positionMs: Long?, val runtimeMs: Long?, val paused: Boolean,
    val method: AdminPlayMethod?, val videoDirect: Boolean?, val audioDirect: Boolean?,
    /** Distinct, in the server's order, at most 8. Empty unless [method] is TRANSCODE. */
    val reasons: List<ConversionReason>,
)
data class WatchSession(
    val id: String, val userName: String, val deviceName: String, val client: String, val clientVersion: String,
    val lastActivity: Instant?, val nowPlaying: NowPlaying?,
    /** `SupportsMediaControl`: Pause and Stop are offered. */ val canControl: Boolean,
    /** `SupportedCommands` contains `DisplayMessage`: Message is offered. */ val canMessage: Boolean,
    /** `DeviceId` equals this phone's `DeviceIdentity.deviceId`: no actions are offered. */ val isThisDevice: Boolean,
)

/** From `CollectionTypeOptions` (12.4.0: movies, tvshows, music, musicvideos, homevideos, boxsets, books, mixed). */
enum class LibraryKind { FILMS, TV, MUSIC, COLLECTIONS, MIXED, OTHER }
data class LibraryFolder(val id: String, val name: String, val kind: LibraryKind, val scanning: Boolean, val progressPercent: Int?)

/** `TaskState` (12.4.0: Idle, Cancelling, Running). */
enum class TaskState { IDLE, RUNNING, CANCELLING }
/** `TaskCompletionStatus` (12.4.0: Completed, Failed, Cancelled, Aborted). */
enum class TaskOutcome { COMPLETED, FAILED, CANCELLED, ABORTED }
data class TaskRun(val outcome: TaskOutcome, val endedAt: Instant?, val errorMessage: String?)
data class ServerTask(
    val id: String, val key: String?, val name: String, val description: String, val category: String,
    val state: TaskState, val progressPercent: Int?, val lastRun: TaskRun?,
)
/** The `Key` of Jellyfin's "Scan Media Library" task, whose progress is the full scan's progress. */
const val FULL_SCAN_TASK_KEY = "RefreshLibrary"

data class ServerUser(
    val id: String, val name: String, val lastSeen: Instant?,
    /** False when the answer had no readable `Policy`: nothing below is known, and enable/disable is not offered. */
    val policyKnown: Boolean,
    val isAdministrator: Boolean, val isDisabled: Boolean, val canDownload: Boolean,
    val allLibraries: Boolean, val libraryIds: List<String>,
    /** The signed-in account itself: it cannot be disabled from here. */ val isYou: Boolean,
)

/** `LogLevel` (12.4.0: Trace, Debug, Information, Warning, Error, Critical, None); Trace, Debug, None and unknown are INFORMATION. */
enum class Severity { INFORMATION, WARNING, ERROR, CRITICAL }
data class ActivityEntry(val id: Long, val text: String, val detail: String?, val at: Instant?, val severity: Severity)
/** [nextStartIndex] is startIndex plus every element the server sent (not just the kept ones). */
data class ActivityPage(val entries: List<ActivityEntry>, val nextStartIndex: Int, val endReached: Boolean)

/** One row per device id; [userNames] are the distinct `LastUserName`s of the rows that share it. */
data class ServerDevice(
    val id: String, val name: String, val userNames: List<String>, val appName: String, val appVersion: String,
    val lastSeen: Instant?, val isThisDevice: Boolean,
)

// data/admin/api/AdminDtos.kt (internal) — only the fields below are declared, so nothing else is ever decoded.
// NOT declared on purpose: DeviceInfoDto.AccessToken, DeviceInfoDto.IconUrl, SessionInfoDto.RemoteEndPoint, SessionInfoDto.NowPlayingQueue,
// SystemInfo.LocalAddress and every *Path field, VirtualFolderInfo.Locations and LibraryOptions, UserDto.Configuration, image tags,
// TaskResult.LongErrorMessage, ActivityLogEntry.Overview. AdminBoundaryTest scans this file for those names.
@Serializable internal class SystemInfoDto(@SerialName("ServerName") val serverName: String? = null, @SerialName("Version") val version: String? = null,
    @SerialName("HasPendingRestart") val hasPendingRestart: Boolean? = null, @SerialName("IsShuttingDown") val isShuttingDown: Boolean? = null)
@Serializable internal class UserMeDto(@SerialName("Id") val id: String? = null, @SerialName("Policy") val policy: PolicyFlagsDto? = null)
@Serializable internal class PolicyFlagsDto(@SerialName("IsAdministrator") val isAdministrator: Boolean? = null, @SerialName("IsDisabled") val isDisabled: Boolean? = null,
    @SerialName("EnableContentDownloading") val enableContentDownloading: Boolean? = null, @SerialName("EnableAllFolders") val enableAllFolders: Boolean? = null,
    @SerialName("EnabledFolders") val enabledFolders: List<JsonElement>? = null)
@Serializable internal class UserDto(@SerialName("Id") val id: String? = null, @SerialName("Name") val name: String? = null,
    @SerialName("LastActivityDate") val lastActivityDate: String? = null, @SerialName("LastLoginDate") val lastLoginDate: String? = null,
    @SerialName("Policy") val policy: JsonElement? = null)
@Serializable internal class SessionDto(@SerialName("Id") val id: String? = null, @SerialName("UserName") val userName: String? = null,
    @SerialName("DeviceName") val deviceName: String? = null, @SerialName("DeviceId") val deviceId: String? = null,
    @SerialName("Client") val client: String? = null, @SerialName("ApplicationVersion") val applicationVersion: String? = null,
    @SerialName("LastActivityDate") val lastActivityDate: String? = null, @SerialName("SupportsMediaControl") val supportsMediaControl: Boolean? = null,
    @SerialName("SupportedCommands") val supportedCommands: List<JsonElement>? = null, @SerialName("NowPlayingItem") val nowPlayingItem: JsonElement? = null,
    @SerialName("PlayState") val playState: JsonElement? = null, @SerialName("TranscodingInfo") val transcodingInfo: JsonElement? = null)
@Serializable internal class NowPlayingItemDto(@SerialName("Name") val name: String? = null, @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Long? = null, @SerialName("IndexNumber") val indexNumber: Long? = null,
    @SerialName("Type") val type: String? = null, @SerialName("RunTimeTicks") val runTimeTicks: Long? = null)
@Serializable internal class PlayStateDto(@SerialName("PositionTicks") val positionTicks: Long? = null, @SerialName("IsPaused") val isPaused: Boolean? = null,
    @SerialName("PlayMethod") val playMethod: String? = null)
@Serializable internal class TranscodingDto(@SerialName("IsVideoDirect") val isVideoDirect: Boolean? = null, @SerialName("IsAudioDirect") val isAudioDirect: Boolean? = null,
    @SerialName("TranscodeReasons") val transcodeReasons: List<JsonElement>? = null)
@Serializable internal class VirtualFolderDto(@SerialName("Name") val name: String? = null, @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("ItemId") val itemId: String? = null, @SerialName("RefreshProgress") val refreshProgress: Double? = null,
    @SerialName("RefreshStatus") val refreshStatus: String? = null)
@Serializable internal class TaskDto(@SerialName("Id") val id: String? = null, @SerialName("Key") val key: String? = null, @SerialName("Name") val name: String? = null,
    @SerialName("Description") val description: String? = null, @SerialName("Category") val category: String? = null, @SerialName("State") val state: String? = null,
    @SerialName("CurrentProgressPercentage") val progress: Double? = null, @SerialName("IsHidden") val isHidden: Boolean? = null,
    @SerialName("LastExecutionResult") val lastExecutionResult: JsonElement? = null)
@Serializable internal class TaskResultDto(@SerialName("Status") val status: String? = null, @SerialName("EndTimeUtc") val endTimeUtc: String? = null,
    @SerialName("ErrorMessage") val errorMessage: String? = null)
@Serializable internal class ActivityResultDto(@SerialName("Items") val items: List<JsonElement>? = null, @SerialName("TotalRecordCount") val total: Long? = null)
@Serializable internal class ActivityEntryDto(@SerialName("Id") val id: Long? = null, @SerialName("Name") val name: String? = null,
    @SerialName("ShortOverview") val shortOverview: String? = null, @SerialName("Date") val date: String? = null, @SerialName("Severity") val severity: String? = null)
@Serializable internal class DeviceResultDto(@SerialName("Items") val items: List<JsonElement>? = null)
@Serializable internal class DeviceDto(@SerialName("Id") val id: String? = null, @SerialName("Name") val name: String? = null,
    @SerialName("CustomName") val customName: String? = null, @SerialName("LastUserName") val lastUserName: String? = null,
    @SerialName("AppName") val appName: String? = null, @SerialName("AppVersion") val appVersion: String? = null,
    @SerialName("DateLastActivity") val dateLastActivity: String? = null)
@Serializable internal class MessageBody(@SerialName("Header") val header: String, @SerialName("Text") val text: String, @SerialName("TimeoutMs") val timeoutMs: Long)

// data/admin/api/AdminMappers.kt (internal)
internal object AdminLimits {
    const val SESSIONS = 100; const val LIBRARIES = 100; const val TASKS = 200; const val USERS = 500; const val DEVICES = 500
    const val LIBRARY_IDS_PER_USER = 500; const val REASONS = 8; const val ACTIVITY_PAGE_MAX = 100
    const val NAME = 80; const val CLIENT = 60; const val VERSION = 40; const val TITLE = 200; const val TASK_NAME = 120
    const val DESCRIPTION = 300; const val CATEGORY = 60; const val ACTIVITY_TEXT = 300; const val ERROR_TEXT = 300
    const val MESSAGE = 200; const val MAX_RUNTIME_MS = 48L * 60 * 60 * 1000
    val SESSION_ID = Regex("[A-Za-z0-9]{1,64}"); val TASK_ID = Regex("[A-Za-z0-9]{1,64}"); val TASK_KEY = Regex("[A-Za-z0-9_.-]{1,64}")
    val DEVICE_ID = Regex("[\\x21-\\x7E]{1,256}"); val VERSION_TEXT = Regex("[0-9A-Za-z.+-]{1,40}")
}
internal fun SystemInfoDto.toStatus(): ServerStatus
internal fun UserMeDto.toMe(): AdminMe
internal fun List<JsonElement>.toSessions(thisDeviceId: String): List<WatchSession>
internal fun List<JsonElement>.toLibraries(): List<LibraryFolder>
internal fun List<JsonElement>.toTasks(): List<ServerTask>
internal fun List<JsonElement>.toUsers(you: String): List<ServerUser>
internal fun ActivityResultDto.toPage(startIndex: Int, limit: Int): ActivityPage
internal fun DeviceResultDto.toDevices(thisDeviceId: String): List<ServerDevice>
internal fun conversionReason(wire: String): ConversionReason

// data/admin/api/UserPolicyEdit.kt (internal, pure)
internal object UserPolicyEdit {
    const val MAX_POLICY_BYTES = 256L * 1024
    /**
     * The `Policy` object of a `UserDto` read from GET /Users/{userId}, with only `IsDisabled` set to [disabled]; every other key
     * kept exactly as the server sent it (unknown ones too). Null — so nothing is posted — when the user's `Id` is not [userId],
     * there is no `Policy` object, or `AuthenticationProviderId` / `PasswordResetProviderId` (required by the 12.4.0 schema) is
     * missing or not a string.
     */
    fun withDisabled(user: JsonObject, userId: String, disabled: Boolean): JsonObject?
}
```

Mapping rules (each a test row):
- **Lists** are decoded one element at a time (`tolerant`, as `data/library/api/Mappers.kt`); a malformed element is skipped and never costs the list; the first occurrence of an id wins; caps from `AdminLimits` apply to kept elements.
- **Sessions:** `Id` must match `SESSION_ID`, else dropped. `UserName`, `DeviceName` through `displayText(…, NAME)`; `Client` (`CLIENT`), `ApplicationVersion` (`VERSION`). `NowPlayingItem` present and decodable ⇒ `NowPlaying`: `Type` `Movie` FILM, `Episode` EPISODE, anything else OTHER; `Name` through `displayText(…, TITLE)`; `SeriesName` (`TITLE`, null if empty, EPISODE only); `ParentIndexNumber`/`IndexNumber` 0..100,000 else null; `RunTimeTicks` → ms in 0..`MAX_RUNTIME_MS` else null; `PlayState.PositionTicks` → ms in 0..`MAX_RUNTIME_MS` else null, and greater than the runtime ⇒ null; `IsPaused` default false; `PlayMethod` by exact reference name else null; `TranscodingInfo` read only when the method is TRANSCODE. `canMessage` is true only if a `SupportedCommands` element is the string `DisplayMessage`. `isThisDevice` is `DeviceId == thisDeviceId` (exact). Sorted: playing first, then `lastActivity` newest first, then user name.
- **Reasons:** `VideoCodecNotSupported`, `VideoProfileNotSupported`, `VideoLevelNotSupported`, `VideoBitDepthNotSupported`, `VideoFramerateNotSupported`, `RefFramesNotSupported`, `AnamorphicVideoNotSupported`, `InterlacedVideoNotSupported`, `VideoCodecTagNotSupported`, `VideoRotationNotSupported` → VIDEO_FORMAT; `AudioCodecNotSupported`, `AudioProfileNotSupported`, `AudioSampleRateNotSupported`, `AudioBitDepthNotSupported`, `AudioIsExternal`, `SecondaryAudioNotSupported` → AUDIO_FORMAT; `ContainerNotSupported` → FILE_TYPE; `SubtitleCodecNotSupported` → SUBTITLES; `ContainerBitrateExceedsLimit`, `VideoBitrateNotSupported`, `AudioBitrateNotSupported`, `VideoResolutionNotSupported` → BITRATE; `VideoRangeTypeNotSupported` → HDR; `AudioChannelsNotSupported` → AUDIO_CHANNELS; `DirectPlayError` → DIRECT_PLAY_FAILED; `UnknownVideoStreamInfo`, `UnknownAudioStreamInfo`, `StreamCountExceedsLimit` and any name not in the 12.4.0 enum → OTHER. At most 32 elements are read.
- **Libraries:** `ItemId` must be a `JellyfinId` (normalised), else dropped (it could not be scanned). `Name` (`NAME`, fallback ""). Kind: `movies` FILMS, `tvshows` TV, `music`/`musicvideos` MUSIC, `boxsets` COLLECTIONS, `mixed` MIXED, anything else or null OTHER. `RefreshProgress` 0..100 (rounded down) else null. `scanning` = `RefreshStatus` present and not `Idle` (any case), OR progress present and below 100. Sorted by name (case-insensitive), then id.
- **Tasks:** `Id` must match `TASK_ID`, else dropped; `Key` must match `TASK_KEY` else null; `IsHidden` true ⇒ dropped (even though `isHidden=false` is sent). `Name` (`TASK_NAME`), `Description` (`DESCRIPTION`), `Category` (`CATEGORY`). `State` by exact name else IDLE. Progress 0..100 rounded down, only while RUNNING, else null. `LastExecutionResult` decodable with a known `Status` ⇒ `TaskRun` (`EndTimeUtc` lenient, `ErrorMessage` through `displayText(…, ERROR_TEXT)` and null when empty; kept only for FAILED and ABORTED), else null. Sorted by category, then name.
- **Users:** `Id` must be a `JellyfinId`, else dropped. `Name` (`NAME`, fallback ""). `lastSeen` = the later of `LastActivityDate` and `LastLoginDate`. `Policy` decodable as `PolicyFlagsDto` ⇒ `policyKnown` true and the flags (missing booleans false), `EnabledFolders` elements that are `JellyfinId`s, normalised, de-duplicated, at most `LIBRARY_IDS_PER_USER`; else `policyKnown` false and every flag false. `isYou` = id equals the session's user id. Sorted: you first, then by name.
- **Activity:** `Id` 0..Long.MAX, else dropped; `Name` through `displayText(…, ACTIVITY_TEXT)`; an entry whose text is empty is dropped; `ShortOverview` (`ACTIVITY_TEXT`, null when empty); `Date` lenient; severity as the enum comment. Kept entries are sorted by `at` newest first (nulls last), then id descending. `nextStartIndex` = startIndex + elements in `Items` (kept or not); `endReached` = no elements came back, or fewer than `limit`, or a usable `TotalRecordCount` (≥ 0 and ≥ what has been seen) is reached.
- **Devices:** `Id` must match `DEVICE_ID`, else dropped. Name = `CustomName` if it has something visible, else `Name` (`NAME`). Rows sharing an id are merged: the latest `DateLastActivity` wins for the time and app, `userNames` collects the distinct sanitised `LastUserName`s (at most 10). `isThisDevice` = id equals `thisDeviceId`. Sorted by `lastSeen` newest first, nulls last.
- **System info:** `ServerName` (`NAME`, fallback ""), `Version` kept only if it matches `VERSION_TEXT` else "", booleans default false.
- **Me:** `Id` normalised with `JellyfinId` (null if not one); `IsAdministrator`/`IsDisabled` default false.

- [ ] **Tests first — `AdminMappersTest`:** each fixture mapped field by field (the kid session is EPISODE "Harbour Lights" S2 E3, 750,000 ms of 2,700,000, TRANSCODE, `videoDirect` false, `audioDirect` true, reasons [VIDEO_FORMAT], `canControl`, `canMessage`; the idle tablet session has `nowPlaying` null, `canMessage` false; the this-phone session is `isThisDevice`); every reason name in the 12.4.0 enum maps as listed (one parameterised test over the enum read from the reference file, so a new enum value fails until it is mapped); library kinds; task states and outcomes; severity mapping; the merged device row has both user names; users sorted with you first.
- [ ] **Tests first — `AdminHostileTest`** (each a named case over `hostile-*.json`): a number where a name should be, a fractional index, an object where a string should be, `null` and a bare string in the array — each costs only its element; a session id `../x`, a 65-character id and an empty id are dropped; duplicate session, task, user and device ids keep the first; 150 sessions keep 100; a 10,000-character title is 200 code points; bidi overrides, zero-width joiners and a tag-character string are removed from names; a name with nothing visible is "" ; position −1, position past runtime and runtime of 100 h are null; progress −5, 100.5 and NaN-as-string are null; 40 transcode reasons read 32 and keep at most 8 distinct; a device whose id holds a space or a control character is dropped; a 300-character device id is dropped; **the devices fixture carries `AccessToken` on every item and the sessions fixture `RemoteEndPoint`, the system info `LocalAddress` and `LogPath`: `toString()` of every mapped list, and the field values of every model (reflection over the data classes' properties), contain none of those values**; a user whose `Policy` is a string has `policyKnown` false; an activity page with `TotalRecordCount` −1 or smaller than seen is treated as unknown.
- [ ] **Tests first — `UserPolicyEditTest`:** the `user-policy.json` fixture (with `MockFutureField`, `BlockedTags`, `AccessSchedules`, every 12.4.0 property) comes back with every key and value equal except `IsDisabled`; a key order change is allowed but no key is lost or added except `IsDisabled` when it was absent; a missing `AuthenticationProviderId`, a `PasswordResetProviderId` that is a number, a `Policy` that is an array, and an `Id` that is another user's (dashed or not, any case) each give null.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.admin.*"` → pass.
- [ ] Commit: `feat(admin): admin models and the untrusted-input rules for every admin answer`.

---

### Task 3: The admin gateway (every endpoint, on `ServerApi`)

**Files:**
- Create: `data/admin/Admin.kt`, `data/admin/api/AdminGateway.kt`.
- Test: `data/admin/AdminGatewayTestBase.kt`, `data/admin/AdminGatewayTest.kt`, `data/admin/AdminGatewayRulesTest.kt`.

**Interfaces — Consumes:** `ServerApi.get` / `post` / `delete` (no change to `ServerApi`), `JsonBodies`, the Task 2 mappers, `Session`, `AppError`.

**Produces:**
```kotlin
// data/admin/Admin.kt
/**
 * The server's admin actions as the UI uses them: token-free [Session] in, validated models out. Implemented by
 * `api/AdminGateway` only. Every call fails `FORBIDDEN` with nothing sent unless the session is a server-confirmed administrator
 * (`AdminRights.allows`). Ids passed in must come from models this seam returned; anything else is `SERVER_NOT_FOUND` with
 * nothing sent. Actions (everything that is not a read) are sent once and never retried.
 */
interface Admin {
    suspend fun serverStatus(s: Session): ServerStatus
    suspend fun sessions(s: Session): List<WatchSession>
    suspend fun sendMessage(s: Session, sessionId: String, header: String, text: String)
    suspend fun command(s: Session, sessionId: String, command: RemoteCommand)
    suspend fun libraries(s: Session): List<LibraryFolder>
    suspend fun scanAll(s: Session)
    suspend fun scanLibrary(s: Session, libraryId: String)
    suspend fun tasks(s: Session): List<ServerTask>
    suspend fun startTask(s: Session, taskId: String)
    suspend fun stopTask(s: Session, taskId: String)
    suspend fun users(s: Session): List<ServerUser>
    suspend fun setUserDisabled(s: Session, userId: String, disabled: Boolean)
    suspend fun activity(s: Session, startIndex: Int, limit: Int): ActivityPage
    suspend fun devices(s: Session): List<ServerDevice>
    suspend fun removeDevice(s: Session, deviceId: String)
    suspend fun restart(s: Session)
    suspend fun shutDown(s: Session)
}

// data/admin/api/AdminGateway.kt
internal class AdminGateway(
    private val api: ServerApi,
    /** This phone's `DeviceIdentity.deviceId`, to mark its own session and device. */
    private val thisDeviceId: String,
    /** `AdminRights.allows`: checked before every call except [me]. */
    private val allows: (Session) -> Boolean,
) : Admin {
    /** GET /Users/Me. Used only by `AdminAccess`; not gated (it is how the gate is opened). */
    suspend fun me(s: Session): AdminMe
    // ... every Admin member
}
```

| Call | Method and path | Sent | Security requirement in the 12.4.0 reference | Retry | Response read |
|---|---|---|---|---|---|
| `me` | GET `/Users/Me` | nothing | `DefaultAuthorization` | GET's one retry | `UserDto` → `UserMeDto` (1 MiB cap) |
| `serverStatus` | GET `/System/Info` | nothing | `FirstTimeSetupOrIgnoreParentalControl`, `DefaultAuthorization` | yes | `SystemInfo` → `SystemInfoDto` (1 MiB) |
| `sessions` | GET `/Sessions` | `activeWithinSeconds=960` | `DefaultAuthorization` (the server filters for non-admins, so the gate decides what is shown) | yes | `SessionInfoDto[]` (4 MiB) |
| `sendMessage` | POST `/Sessions/{sessionId}/Message` | JSON `MessageCommand` `{"Header":…,"Text":…,"TimeoutMs":10000}` | `DefaultAuthorization` | **never** | 204 |
| `command` | POST `/Sessions/{sessionId}/Playing/{command}` | `command` = `Pause` or `Stop`; no query (`seekPositionTicks`, `controllingUserId` left out) | `DefaultAuthorization` | **never** | 204 |
| `libraries` | GET `/Library/VirtualFolders` | nothing | `FirstTimeSetupOrElevated`, `DefaultAuthorization` | yes | `VirtualFolderInfo[]` (1 MiB) |
| `scanAll` | POST `/Library/Refresh` | nothing | `RequiresElevation` | **never** | 204 |
| `scanLibrary` | POST `/Items/{itemId}/Refresh` | `metadataRefreshMode=Default`, `imageRefreshMode=Default`, `replaceAllMetadata=false`, `replaceAllImages=false`, `regenerateTrickplay=false` | `RequiresElevation` | **never** | 204 |
| `tasks` | GET `/ScheduledTasks` | `isHidden=false` | `RequiresElevation` | yes | `TaskInfo[]` (1 MiB) |
| `startTask` | POST `/ScheduledTasks/Running/{taskId}` | nothing | `RequiresElevation` | **never** | 204 |
| `stopTask` | DELETE `/ScheduledTasks/Running/{taskId}` | nothing | `RequiresElevation` | **never** | 204 |
| `users` | GET `/Users` | nothing (`isHidden`, `isDisabled` left out: hidden and disabled users are both shown) | `DefaultAuthorization` | yes | `UserDto[]` (4 MiB) |
| `setUserDisabled` | GET `/Users/{userId}` then POST `/Users/{userId}/Policy` | the read `Policy` object through `UserPolicyEdit.withDisabled`, as the JSON body | GET `IgnoreParentalControl`, `DefaultAuthorization`; POST `RequiresElevation` | GET yes; POST **never** | GET `UserDto` as `JsonObject` (`UserPolicyEdit.MAX_POLICY_BYTES`); POST 204 |
| `activity` | GET `/System/ActivityLog/Entries` | `startIndex` (0..1,000,000), `limit` (1..`ACTIVITY_PAGE_MAX`); `sortBy`/`sortOrder`/`hasUserId`/`minDate` left out | `RequiresElevation` | yes | `ActivityLogEntryQueryResult` (2 MiB) |
| `devices` | GET `/Devices` | nothing (`userId` left out) | `RequiresElevation` | yes | `DeviceInfoDtoQueryResult` (4 MiB) |
| `removeDevice` | DELETE `/Devices` | `id` = the one device id | `RequiresElevation` | **never** | 204 |
| `restart` | POST `/System/Restart` | nothing | `LocalAccessOrRequiresElevation` | **never** | 204 |
| `shutDown` | POST `/System/Shutdown` | nothing | `RequiresElevation` | **never** | 204 |

Rules:
- **Gate first:** every `Admin` call starts with `if (!allows(s)) throw AppError.of(AppError.Kind.FORBIDDEN)` — before any `ServerApi` call, so nothing is sent.
- **Ids in:** `sessionId` must match `SESSION_ID`, `taskId` `TASK_ID`, `libraryId` and `userId` must be already-normalised `JellyfinId`s (`JellyfinId.parseOrNull(id)?.value == id`), `deviceId` `DEVICE_ID`; else `SERVER_NOT_FOUND` with nothing sent. Path segments are passed to `ServerApi` which builds the URL with `HttpUrl.Builder` (`ApiRequest`), so a device id with `/`, `?` or `%` cannot change the path (it is a query value).
- **Message:** `header` and `text` are cleaned with `displayText(…, MESSAGE)`; an empty cleaned text is `require` failure (fixed message, programmer error: the screen never offers Send for it). Body encoded with `JsonBodies.JSON.encodeToString(MessageBody.serializer(), …)`; `TimeoutMs` 10,000.
- **Disable/enable:** after the GET, `UserPolicyEdit.withDisabled(...)` null ⇒ `SERVER_ERROR`, nothing posted. The POST body is `JsonObject.toString()` of the edited policy. Nothing else from the user object is sent.
- **Status mapping** is `ServerApi`'s own (401 signs the account out locally and is `UNAUTHORISED`; 403 `FORBIDDEN`; 404 and 3xx `SERVER_NOT_FOUND`; others `SERVER_ERROR`). Nothing from a response body is ever in a message or log.
- **Logs:** `AppLog.d(TAG, "admin <call> failed: <kind>")` only; `TAG = "Admin"`. Never an id, name, path or text.

- [ ] **Tests first — `AdminGatewayTest`** (real `AdminGateway` on the real `ServerApi`, `ServerClients`, `HttpClientFactory` against MockWebServer, as `PlaybackGatewayTestBase`; `allows` = `{ true }` unless a test says otherwise): for each row of the table, the exact method, path, query names and values, and body (JSON keys compared as a set, values exact); every request carries `Authorization` with the token and no `api_key`-like query name; each response fixture maps through to the models (one assertion per model type); `setUserDisabled(true)` sends GET then POST, and the POST body equals the fixture's `Policy` with `IsDisabled` true (JsonObject equality); `removeDevice("web-ben-laptop")` sends `DELETE /Devices?id=web-ben-laptop`; a device id `a/b?c` is sent as `id=a%2Fb%3Fc` on `/Devices` and nowhere in the path.
- [ ] **Tests first — `AdminGatewayRulesTest`:** with `allows = { false }` every one of the 17 calls is `FORBIDDEN` and `web.requestCount == 0`; each malformed id (`../x`, an uppercase or dashed user id, a 65-character session id, a device id with a space) is `SERVER_NOT_FOUND` with zero requests; **each action (the 10 non-read calls) against a server that drops the connection after the request (`SocketPolicy.DISCONNECT_AFTER_REQUEST`) is sent exactly once and fails `NETWORK`**, while `sessions` is retried once (two requests) as every GET; 401 on `restart` signs the account out locally (`store.cachedToken` null afterwards) and is `UNAUTHORISED`; 403 is `FORBIDDEN`, 404 `SERVER_NOT_FOUND`; a 302 to another host is `SERVER_NOT_FOUND` and no request reaches the other host; a `/Users/{id}` answer for another id, without `Policy`, or without a provider id is `SERVER_ERROR` and no POST is sent; a policy body over 256 KiB is `SERVER_ERROR` with no POST; an activity `limit` of 0 or 101 is an `IllegalArgumentException` before any request; a message whose text cleans to nothing is an `IllegalArgumentException` with no request; the log lines captured during the whole class (Robolectric `ShadowLog.getLogs()`, which receives `AppLog`'s output) contain none of the fixture names, ids, paths, the token or `MOCKDEVICETOKEN`.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.admin.*"` → pass.
- [ ] Commit: `feat(admin): admin gateway on the one authenticated call path, gated and never retrying an action`.

---

### Task 4: Who is an administrator (`AdminAccess`), the browser dashboard, and the graph

**Files:**
- Create: `data/admin/AdminRights.kt`, `data/admin/BrowserDashboard.kt`.
- Modify: `AppGraph.kt` — one block at the end of the class body, nothing else:
  ```kotlin
      // ---- Server admin (Milestone 6, data/admin). Live only: nothing here is cached, saved or kept for another profile.
      private val adminGateway: AdminGateway by lazy { AdminGateway(serverApi, deviceIdentity.deviceId) { s -> adminRights.allows(s) } }
      /** What admin screens call: every request gated on [adminRights] (see "Server admin" in docs/ARCHITECTURE.md). */
      val admin: Admin get() = adminGateway
      /** Whether the active account is a server-confirmed administrator; forgets on every active-session change. */
      val adminRights: AdminRights by lazy {
          AdminAccess(adminGateway::me).also { a -> appScope.launch { serverCatalog.activeSession().collect(a::sessionChanged) } }
      }
      /** Opens the server's own web dashboard in the browser, on the verified address, with no sign-in passed. */
      val browserDashboard: DashboardOpener by lazy { BrowserDashboard(addressSelector) }
  ```
  plus the imports for `Admin`, `AdminRights`, `AdminAccess`, `DashboardOpener`, `BrowserDashboard`, `api.AdminGateway`.
- Test: `data/admin/AdminAccessTest.kt`, `data/admin/BrowserDashboardTest.kt` (Robolectric).

**Interfaces — Consumes:** `AdminGateway.me`, `ServerCatalog.activeSession()` (through `sessionChanged`), `ServerConnections.awaitOnline`, `Connection.Online.address`.

**Produces:**
```kotlin
// data/admin/AdminRights.kt
enum class AdminStatus { ADMIN, NOT_ADMIN, UNKNOWN }
data class AdminState(val session: Session, val status: AdminStatus)

/** Who may see admin screens. Implemented by [AdminAccess]. Holds the active session's answer only, in memory. */
interface AdminRights {
    /** The last answer for the active session, or null (none yet, or the active session changed since). */
    val state: StateFlow<AdminState?>
    /**
     * Asks the server now (GET /Users/Me) as [session]. ADMIN only if the answer's `Id` is this user and `Policy.IsAdministrator`
     * is true and `Policy.IsDisabled` is false. NOT_ADMIN for any other answer, for 401/403/NOT_SIGNED_IN, and for a session that
     * is not the active one (nothing is sent). A failure to reach the server (OFFLINE, NETWORK, TIMEOUT, SERVER_ERROR, TLS) leaves
     * an earlier answer for the same session in place and returns it, or UNKNOWN when there was none. An answer that arrives
     * after the active session changed is dropped and NOT_ADMIN is returned.
     */
    suspend fun check(session: Session): AdminStatus
    /** Records NOT_ADMIN for [session] if it is the one held (a 403 a re-check confirmed). */
    fun lost(session: Session)
    /** True only when [state] is exactly ([session], ADMIN). What `AdminGateway` checks before sending. */
    fun allows(session: Session): Boolean
}

internal data class AdminMe(val userId: String?, val isAdministrator: Boolean, val isDisabled: Boolean) {
    override fun toString() = "AdminMe(<redacted>)"
}

class AdminAccess internal constructor(private val me: suspend (Session) -> AdminMe) : AdminRights {
    /** Fed by `ServerCatalog.activeSession()`; every change (to anyone or nobody) clears [state] at once. */
    fun sessionChanged(active: Session?)
}

// data/admin/BrowserDashboard.kt
enum class DashboardResult { OPENED, OFFLINE, NO_BROWSER }
fun interface DashboardOpener {
    /** Opens [session]'s server dashboard in the browser from [context] (an Activity). No token, header, query or user name is passed. */
    suspend fun open(session: Session, context: Context): DashboardResult
}
class BrowserDashboard(
    private val selector: ServerConnections,
    private val main: CoroutineDispatcher = Dispatchers.Main,
) : DashboardOpener
```
Behaviour:
- `AdminAccess.check` waits (at most 5 s, then answers NOT_ADMIN) until `sessionChanged` has been called once, so the first check after the graph is built is not refused just because the collector has not run yet; then compares with the last active session. A generation counter bumped by `sessionChanged` decides whether an answer may still be stored. Never throws (except cancellation). Failures logged by kind only.
- `BrowserDashboard.open`: `selector.awaitOnline(session.serverId, 5_000)` null ⇒ OFFLINE. URL = the verified `ServerAddress` (`address.value.toHttpUrl()`, base path kept) `.newBuilder().addPathSegment("web").addPathSegment("")` ⇒ `…/web/`, no query, no fragment, no user info (asserted: a built URL with any of those is refused as OFFLINE — defence in depth). `Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())).addCategory(Intent.CATEGORY_BROWSABLE)`, plus `FLAG_ACTIVITY_NEW_TASK` only when [context] is not an Activity; started on [main]; `ActivityNotFoundException` ⇒ NO_BROWSER. No extras. The UI only ever sees the enum.

- [ ] **Tests first — `AdminAccessTest`** (virtual time, fake `me`): before any `sessionChanged`, `check` waits and then answers once it arrives; a check for a session that is not the active one is NOT_ADMIN with zero `me` calls; admin true ⇒ ADMIN and `state == AdminState(s, ADMIN)` and `allows(s)`; `IsAdministrator` false, `IsDisabled` true, another `Id`, a null `Id` ⇒ NOT_ADMIN; `me` throwing `UNAUTHORISED`, `FORBIDDEN`, `NOT_SIGNED_IN` ⇒ NOT_ADMIN; `OFFLINE`/`NETWORK` with an earlier ADMIN ⇒ ADMIN kept; with none ⇒ UNKNOWN and `state` null; `sessionChanged(other)` while `me` is in flight ⇒ the answer is dropped, NOT_ADMIN returned, `state` null; `sessionChanged` clears `state` synchronously; `lost(s)` makes `allows(s)` false; `allows` is false for a different session even with the same server; `toString` of `AdminMe` and `AdminState` holds no id.
- [ ] **Tests first — `BrowserDashboardTest`** (Robolectric, `ShadowApplication.nextStartedActivity`): opened with address `http://127.0.0.1:8096` ⇒ data `http://127.0.0.1:8096/web/`, `ACTION_VIEW`, `CATEGORY_BROWSABLE`, no extras; with `https://example.org/jellyfin` ⇒ `https://example.org/jellyfin/web/`; `awaitOnline` null ⇒ OFFLINE and nothing started; with `shadowOf(application).checkActivities(true)` and no browser ⇒ NO_BROWSER; nothing in the started intent contains the account's token, user name or a query.
- [ ] Implement; add the `AppGraph` block (compile-checked by `assembleDebug`).
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.admin.*"` and `./gradlew.bat assembleDebug` → pass.
- [ ] Commit: `feat(admin): server-confirmed administrator check held in memory, and the browser dashboard link`.

---

### Task 5: The screen machinery — guard, poller, one action at a time, plain words, scaffold and dialogs

**Files:**
- Create: `ui/admin/AdminGuard.kt`, `ui/admin/AdminPoller.kt`, `ui/admin/AdminWork.kt`, `ui/admin/AdminScreenModel.kt`, `ui/admin/AdminText.kt`, `ui/admin/AdminScaffold.kt`, `ui/admin/AdminDialogs.kt`, `ui/admin/AdminParts.kt`; `app/src/main/res/values/strings_admin.xml` (the "Common", "Times" and "Dialogs" strings below).
- Modify: `ui/components/SecureWindow.kt` — KDoc only: "used by Sign in, Connection settings and every Server admin screen (`ui/admin/AdminScaffold`)".
- Test: `ui/admin/AdminFakes.kt`, `ui/admin/AdminGuardTest.kt`, `ui/admin/AdminPollerTest.kt`, `ui/admin/AdminWorkTest.kt`, `ui/admin/AdminTextTest.kt` (Robolectric, real resources), `ui/admin/AdminPartsRenderTest.kt`.

**Interfaces — Consumes:** `ServerCatalog.activeSession()`, `AdminRights`, `ServerConnections.connection`, `AppError.from`, `Strings`, `SecureWindow`, `SecureDialog`, `FilterObscuredTouches`, `ObscuredTouchHint`, `NoticeCard`, `ErrorMessage`, `Loading`.

**Produces:**
```kotlin
// ui/admin/AdminGuard.kt
sealed interface AdminGate {
    data object Checking : AdminGate
    data class Open(val session: Session, val online: Boolean) : AdminGate
    /** For good: a closed gate never opens again in this ViewModel. */
    data object Closed : AdminGate
}
enum class AdminProblem { OFFLINE, NETWORK, REFUSED, GONE, SERVER, CONNECTION, OTHER }

class AdminGuard(
    catalog: ServerCatalog, private val rights: AdminRights, connections: ServerConnections,
    private val scope: CoroutineScope, private val recheckMs: Long = RECHECK_MS,
) {
    val gate: StateFlow<AdminGate>
    val visible: StateFlow<Boolean>
    /** The session to load as: non-null only while the gate is Open, online, and the screen is visible. Distinct values. */
    val live: Flow<Session?>
    fun setVisible(on: Boolean)
    /**
     * What a failed admin call means. Null when it closes the gate (UNAUTHORISED, NOT_SIGNED_IN; FORBIDDEN that a fresh
     * `rights.check` turns into NOT_ADMIN — then `rights.lost`), so the caller shows nothing. Otherwise: FORBIDDEN still admin
     * REFUSED, OFFLINE OFFLINE, NETWORK/TIMEOUT NETWORK, SERVER_NOT_FOUND GONE, SERVER_ERROR SERVER, TLS_UNTRUSTED/TLS_PIN_CHANGED/
     * CLEARTEXT_BLOCKED/CLIENT_CERT_REQUIRED CONNECTION, anything else OTHER. Never called with a CancellationException.
     */
    suspend fun problemFor(e: Throwable): AdminProblem?
    companion object { const val RECHECK_MS = 60_000L }
}

// ui/admin/AdminPoller.kt
/**
 * Loads while [live] names a session: at once, then after the delay [load] returns (null: wait for [kick]). One load at a time;
 * a change of [live] cancels a load in flight. [load] must not throw (the screen model catches).
 */
class AdminPoller(scope: CoroutineScope, live: Flow<Session?>, private val load: suspend (Session) -> Long?) {
    /** Load now (a refresh, or after an action) and restart the wait. Ignored while not live. */
    fun kick()
}

// ui/admin/AdminWork.kt
data class AdminNotice(@StringRes val text: Int, val args: List<String> = emptyList()) { override fun toString() = "AdminNotice(<redacted>)" }
class AdminWork(private val guard: AdminGuard, private val scope: CoroutineScope) {
    /** The key of the action running now, or null. */
    val running: StateFlow<String?>
    val problem: StateFlow<AdminProblem?>
    /**
     * Runs [action] once, as the gate's session, only if the gate is Open and online and nothing is running (else: offline sets
     * OFFLINE, busy is ignored). Never retried. On success [onDone] runs; on failure the problem is set (or nothing, if the gate
     * closed because of it).
     */
    fun run(key: String, action: suspend (Session) -> Unit, onDone: () -> Unit = {})
    fun dismissProblem()
}

// ui/admin/AdminScreenModel.kt
data class AdminFrame(
    val gate: AdminGate = AdminGate.Checking,
    val loading: Boolean = true,
    val updatedAt: Instant? = null,
    val loadProblem: AdminProblem? = null,
    val running: String? = null,
    val problem: AdminProblem? = null,
    val notice: AdminNotice? = null,
) {
    val online: Boolean get() = (gate as? AdminGate.Open)?.online == true
    /** Buttons that act on the server are enabled only when this is true. */
    val canAct: Boolean get() = online && running == null
}

abstract class AdminScreenModel(
    catalog: ServerCatalog, rights: AdminRights, connections: ServerConnections, protected val clock: () -> Instant,
) : ViewModel() {
    protected val guard: AdminGuard
    protected val work: AdminWork
    val frame: StateFlow<AdminFrame>
    fun setVisible(on: Boolean)
    fun dismissProblem()
    fun dismissNotice()
    protected fun notify(notice: AdminNotice)
    /** A poller whose [fetch] failures become `frame.loadProblem` (or nothing, if they closed the gate); a success clears it and stamps `updatedAt`. */
    protected fun poller(fetch: suspend (Session) -> Long?): AdminPoller
    /** Called (on the main thread) the moment the gate closes: drop every loaded list, confirmation and typed text. */
    protected abstract fun clear()
}

// ui/admin/AdminText.kt (pure; numbers as ASCII digits)
class AdminText(private val s: Strings, private val zone: ZoneId) {
    fun ago(at: Instant?, now: Instant): String
    fun progress(positionMs: Long?, runtimeMs: Long?): String?
    fun nowPlayingLine(n: NowPlaying): String
    fun nowPlayingSpoken(n: NowPlaying): String
    fun method(n: NowPlaying): String?
    fun deviceLine(session: WatchSession): String
    fun libraryKind(kind: LibraryKind): String
    fun severity(severity: Severity): String
    fun outcome(outcome: TaskOutcome): String
    fun problem(problem: AdminProblem): String
    fun name(raw: String, @StringRes fallback: Int): String
}
/** Trimmed, case-insensitive, runs of white space as one space. */
fun typedMatches(typed: CharSequence, word: String): Boolean

// ui/admin/AdminScaffold.kt
/**
 * Every admin screen. Sets FLAG_SECURE (SecureWindow). Closed: an empty, unlabelled surface and nothing else. Checking: "Checking
 * you're an administrator…". Open: top bar (title as heading, Back, a Refresh action named "Refresh"), then a LazyColumn capped at
 * 840dp and centred: the offline notice when not online, the load-problem line, the problem line (live region, Dismiss), the
 * notice line (live region, Dismiss), "Last updated 14:02:05", then [content].
 */
@Composable fun AdminScaffold(title: String, frame: AdminFrame, onBack: () -> Unit, onRefresh: (() -> Unit)?, content: LazyListScope.() -> Unit)

// ui/admin/AdminDialogs.kt
@Composable fun AdminConfirmDialog(title: String, body: List<String>, confirmLabel: String, enabled: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit)
@Composable fun TypedConfirmDialog(title: String, body: String, word: String, typed: TextFieldState, confirmLabel: String, enabled: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit)
@Composable fun MessageDialog(title: String, body: String, text: TextFieldState, enabled: Boolean, onSend: () -> Unit, onDismiss: () -> Unit)

// ui/admin/AdminParts.kt
@Composable fun AdminCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)
@Composable fun AdminBadge(text: String)
@Composable fun AdminSectionLink(label: String, detail: String, onClick: () -> Unit)
@Composable fun AdminProgress(percent: Int?, label: String)   // determinate when percent != null; the label is the spoken state
@Composable fun AdminAction(label: String, description: String, enabled: Boolean, caution: Boolean = false, onClick: () -> Unit)
```
Behaviour:
- **Gate:** the first value of `activeSession()` is the owner (null ⇒ Closed). Then `rights.check(owner)`: ADMIN ⇒ Open(owner, online), anything else ⇒ Closed. After that, Closed when: `activeSession()` names anything else (even later the owner again); `rights.state` is anything but `AdminState(owner, ADMIN)` (null after a switch, NOT_ADMIN after a re-check or `lost`); `problemFor` closes it. `online` follows `connections.connection(owner.serverId)` (`Connection.Online` only). While visible, Open and online, `rights.check(owner)` runs every `recheckMs` (virtual time).
- **Dialogs:** all use `SecureDialog`, `FilterObscuredTouches()` and `ObscuredTouchHint`; Cancel is the dismiss button; the confirm button is in the error colour, disabled when `enabled` is false. `TypedConfirmDialog`'s field is a single-line `OutlinedTextField(state = typed)` labelled "Type %1$s to confirm" with the word, keyboard `KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Text, imeAction = ImeAction.Done)`, and the confirm button is enabled only when `typedMatches(typed.text, word)`. `MessageDialog`'s field is multi-line, `InputTransformation.maxLength(200)`, with the counter "%1$s of 200 characters" as supporting text; Send enabled only when the trimmed text is not empty.
- **Times (`AdminText.ago`):** under 1 minute "Just now"; under 60 minutes "%1$s min ago"; the same day "Today at HH:mm"; the day before "Yesterday at HH:mm"; otherwise `d MMM yyyy` with `Locale.UK`; null "Never". Times use `DateTimeFormatter.ofPattern("HH:mm", Locale.UK)` and ASCII digits whatever the phone's language (`DecimalStyle.STANDARD`). `progress`: "12:30 of 45:00", "1:02:03 of 2:00:00" past an hour; null if either part is null.

- [ ] **Tests first — `AdminGuardTest`** (virtual time; `FakeCatalog`, `FakeAdminRights`, `FakeConnections`): owner ADMIN ⇒ Open(owner, true); `activeSession` null at start ⇒ Closed and `check` never called; NOT_ADMIN or UNKNOWN ⇒ Closed; switching to another account ⇒ Closed, and switching back does not reopen; `rights.state` becoming null or NOT_ADMIN ⇒ Closed; connection `Offline` ⇒ Open(online = false) and `live` null; `setVisible(false)` ⇒ `live` null; re-check every 60 s while visible and online, none while hidden or offline (count calls over 5 virtual minutes); `problemFor` table: each kind gives the listed problem; UNAUTHORISED ⇒ null and Closed; FORBIDDEN with the fake re-check answering ADMIN ⇒ REFUSED and still Open, answering NOT_ADMIN ⇒ null, Closed and `lost` called.
- [ ] **Tests first — `AdminPollerTest`:** loads at once when live; the next load after exactly the returned delay; a null delay waits until `kick`; a `kick` during a wait loads at once and restarts the wait; a `kick` while not live does nothing and is not remembered; going not-live cancels a load in flight and nothing loads afterwards; live again ⇒ loads at once; two loads never overlap (a slow fake load and three kicks ⇒ loads run one after another, the extra kicks merged into one).
- [ ] **Tests first — `AdminWorkTest`:** runs once as the gate's session; a second `run` while one runs is ignored (one call); offline ⇒ not run, `problem == OFFLINE`; Closed ⇒ not run, no problem; a failure ⇒ the mapped problem and **the action was invoked exactly once**; `onDone` runs only after success; `running` is the key while running and null after.
- [ ] **Tests first — `AdminTextTest`:** every time case above with a fixed clock and `Europe/London` (including across midnight and a summer-time change); ASCII digits with the default locale set to `ar-EG`; progress formats; every method/reason sentence; the episode line and its spoken form; every `LibraryKind`, `Severity`, `TaskOutcome` and `AdminProblem` word; `name("", R.string.admin_unnamed_user)` is "Unnamed user"; `typedMatches`: "restart" for "Restart ", "RESTART", " restart"; "shut down" for "Shut  down" and "SHUT DOWN"; not for "restar", "restarts", "shutdown".
- [ ] **Tests first — `AdminPartsRenderTest`** (`RenderBase`, phone and tablet, 1.0 and 2.0): `AdminScaffold` with Closed shows no text node at all and no Back button; Checking shows "Checking you're an administrator…"; Open offline shows "You're offline" and its body; the problem line is a live region with "Dismiss"; the Refresh action is described "Refresh" and is 48dp; `TypedConfirmDialog`'s confirm button is disabled until "restart" is typed and enabled after; `MessageDialog`'s Send is disabled for "   " and enabled for "hi"; the counter reads "2 of 200 characters"; `AdminAction` exposes its description as the content description and is at least 48dp high and wide; nothing on any of these is under 13sp (`TextStyle.fontSize` from the semantics `TextLayoutResult`).
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.ui.admin.*"` → pass.
- [ ] Commit: `feat(admin): admin screen machinery — the gate, visible-only polling, one action at a time, plain words and secure dialogs`.

---

### Task 6: The entry point, navigation, and the dashboard (server card and sections)

**Files:**
- Create: `ui/admin/AdminNav.kt`, `ui/admin/DashboardViewModel.kt`, `ui/admin/DashboardScreen.kt`, `ui/admin/AdminPreviews.kt` (dashboard previews; later tasks add theirs).
- Modify:
  - `ui/nav/AppNav.kt` — inside the `NavHost { … }` block, after `composable(Routes.SERVER_EDIT, …)`: `adminDestinations(graph, nav)`; one import. Nothing else (admin routes are deliberately not in `AccountRoutes`, so `ClearBrowsingOnSessionChange` pops them on a session change).
  - `ui/nav/Routes.kt` — `ServersRoute` only: `ServersViewModel(graph.serverCatalog, graph.authenticator, graph.adminRights)` and `onAdmin = { nav.navigate(AdminRoutes.DASHBOARD) { launchSingleTop = true } }`.
  - `ui/servers/ServersViewModel.kt` — constructor gains `private val rights: AdminRights? = null` (default keeps every existing test as it is); `ServersUiState` gains `val adminServerId: String? = null`; on each `activeSession()` value the ViewModel calls `rights.check(session)` once (in `viewModelScope`) and `adminServerId` is the active session's server id only while `rights.state.value == AdminState(thatSession, ADMIN)`.
  - `ui/servers/ServersScreen.kt` — `ServersActions` gains `val onAdmin: () -> Unit = {}`; `ServerCard` gains `showAdmin: Boolean` (passed `server.id == state.adminServerId`) and, after the Settings button, `if (showAdmin) IconTextButton(stringResource(R.string.admin_entry), Icons.Filled.Build, actions.onAdmin, Modifier.semantics { contentDescription = adminLabel })` with `adminLabel = stringResource(R.string.admin_entry_cd, server.name)`.
  - `strings_admin.xml` — "Entry" and "Dashboard" strings.
- Test: `ui/servers/ServersAdminEntryTest.kt`, `ui/servers/ServersAdminEntryRenderTest.kt`, `ui/admin/AdminNavigationTest.kt`, `ui/admin/DashboardViewModelTest.kt`, `ui/admin/DashboardRenderTest.kt` (this task: server card and links; Task 7 adds sessions).

**Interfaces — Consumes:** `AdminScreenModel`, `Admin.serverStatus`, `DashboardOpener`, `AdminRights`, the Task 5 parts.

**Produces:**
```kotlin
// ui/admin/AdminNav.kt
internal object AdminRoutes {
    const val DASHBOARD = "admin"
    const val LIBRARIES = "admin/libraries"
    const val TASKS = "admin/tasks"
    const val USERS = "admin/users"
    const val ACTIVITY = "admin/activity"
    const val DEVICES = "admin/devices"
    const val POWER = "admin/power"
}
/** The real admin destinations, each built from [graph] with `graphViewModel` (no arguments: nothing about the server travels in a route). */
internal fun NavGraphBuilder.adminDestinations(graph: AppGraph, nav: NavHostController)
/** The same routes with the screens passed in, so `AdminNavigationTest` drives this exact graph with recorders. */
internal fun NavGraphBuilder.adminDestinations(
    dashboard: @Composable () -> Unit, libraries: @Composable () -> Unit, tasks: @Composable () -> Unit, users: @Composable () -> Unit,
    activity: @Composable () -> Unit, devices: @Composable () -> Unit, power: @Composable () -> Unit,
)
/** Pops every admin screen at once; does nothing unless an admin screen is on top (so it can never pop Home). */
internal fun NavHostController.leaveAdmin()

// ui/admin/DashboardViewModel.kt (this task's part; Task 7 adds the sessions)
data class DashboardUiState(
    val frame: AdminFrame = AdminFrame(),
    val status: ServerStatus? = null,
    val playing: List<WatchSession> = emptyList(),
    val idle: List<WatchSession> = emptyList(),
    val confirm: SessionConfirm? = null,
    val message: MessageTarget? = null,
)
class DashboardViewModel(
    catalog: ServerCatalog, rights: AdminRights, connections: ServerConnections,
    private val admin: Admin, private val opener: DashboardOpener, clock: () -> Instant = Instant::now,
) : AdminScreenModel(catalog, rights, connections, clock) {
    val state: StateFlow<DashboardUiState>
    fun refresh()
    /** [context] is used for this call only and never kept. */
    fun openDashboard(context: Context)
}
```
Each route composable (in `AdminNav.kt`): builds its ViewModel with `graphViewModel { … }`, collects state with `collectAsStateWithLifecycle`, calls `LifecycleStartEffect(vm) { vm.setVisible(true); onStopOrDispose { vm.setVisible(false) } }`, calls `LaunchedEffect(gate) { if (gate is AdminGate.Closed) nav.leaveAdmin() }`, and draws the screen. The dashboard's section links navigate to the other `AdminRoutes` with `launchSingleTop`.

Dashboard screen (`DashboardScreen`), top to bottom: title "Server admin"; a server card (heading = server name, "Jellyfin 12.4.0"; when `pendingRestart` a caution `NoticeCard` "Restart needed" / "The server needs a restart to finish an update or a settings change." with a button "Restart or shut down" to POWER; when `shuttingDown` the line "The server is shutting down."); the "Who is watching" section (Task 7); a "Manage" heading and the section links: Libraries, Scheduled tasks, Users, Activity, Devices, Restart or shut down (each `AdminSectionLink` with its detail line); then "Open the full dashboard in your browser" (`SecondaryButton`) with the note under it. `openDashboard` ⇒ OFFLINE shows the OFFLINE problem, NO_BROWSER notice "There's no web browser on this phone to open the dashboard."; OPENED shows nothing. The dashboard poll loads `serverStatus` with the sessions (Task 7) and in this task returns `SESSIONS_POLL_MS`.

- [ ] **Tests first — `ServersAdminEntryTest`** (`FakeCatalog`, `FakeAuthenticator`, `FakeAdminRights`): no `rights` ⇒ `adminServerId` always null (old behaviour); rights answering ADMIN for the active session ⇒ that server's id; UNKNOWN or NOT_ADMIN ⇒ null; switching the active account ⇒ null at once (before the new check answers) and then checked for the new session; a check answer for the old session arriving after the switch never sets it; the entry follows `rights.state` going to NOT_ADMIN.
- [ ] **Tests first — `ServersAdminEntryRenderTest`** (phone and tablet, 1.0 and 2.0): with `adminServerId` = server 1, the card for server 1 has a button described "Server admin for Den" (48dp) and server 2's card has none; with null there is no "Server admin" anywhere; pressing it calls `onAdmin`.
- [ ] **Tests first — `AdminNavigationTest`** (a `TestNavHostController` with `adminDestinations` recorders and a Home destination): every route opens its recorder; `leaveAdmin` from LIBRARIES on top of DASHBOARD on top of Home leaves Home; `leaveAdmin` with Home on top does nothing; the real `ClearBrowsingOnSessionChange` (with `FakeCatalog`) pops the admin entries on an account switch (stack becomes Home), proving admin routes are session-bound.
- [ ] **Tests first — `DashboardViewModelTest`** (virtual time, `FakeAdmin`): Checking until the rights answer; Open ⇒ `serverStatus` loaded, shown, `updatedAt` stamped; `pendingRestart` passes through; Closed (switch account) ⇒ `status` null in the very next state and no further `FakeAdmin` calls; offline ⇒ no load and `frame.online` false; `openDashboard` passes the gate's session (never another) and maps OFFLINE/NO_BROWSER as above; a load failure sets `loadProblem` and keeps the last status.
- [ ] **Tests first — `DashboardRenderTest`** (phone and tablet, 1.0 and 2.0): headings "Server admin", the server name, "Manage"; "Jellyfin 12.4.0"; with `pendingRestart` "Restart needed" and its button; each section link is one clickable element with label and detail, 48dp; "Open the full dashboard in your browser" and its note; at 2.0 every link is reachable by scrolling (`performScrollToNode`).
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.ui.admin.*" --tests "app.shoalwatch.ui.servers.*" --tests "app.shoalwatch.ui.nav.*" --tests "app.shoalwatch.ui.ManagementRenderTest" --tests "app.shoalwatch.ui.ServersAndHomeViewModelTest"` → pass (the existing Servers tests unchanged).
- [ ] Commit: `feat(admin): Server admin entry for confirmed administrators, admin navigation and the dashboard`.

---

### Task 7: Who is watching — live sessions, message, pause and stop

**Files:**
- Create: `ui/admin/SessionCards.kt`.
- Modify: `ui/admin/DashboardViewModel.kt`, `ui/admin/DashboardScreen.kt`, `ui/admin/AdminPreviews.kt`, `strings_admin.xml` ("Who is watching" strings).
- Test: `ui/admin/DashboardSessionsTest.kt`, `ui/admin/DashboardRenderTest.kt` (extend).

**Interfaces — Consumes:** `Admin.sessions`, `Admin.sendMessage`, `Admin.command`, `ServerCatalog.activeAccount` (the admin's own user name for the message header), `AdminText`.

**Produces:**
```kotlin
// ui/admin/DashboardViewModel.kt (additions)
sealed interface SessionConfirm {
    val sessionId: String
    data class Pause(override val sessionId: String, val userName: String, val deviceName: String, val title: String) : SessionConfirm
    data class Stop(override val sessionId: String, val userName: String, val deviceName: String, val title: String) : SessionConfirm
}
data class MessageTarget(val sessionId: String, val userName: String, val deviceName: String) { override fun toString() = "MessageTarget(<redacted>)" }
// SessionConfirm overrides toString likewise.
const val SESSIONS_POLL_MS = 5_000L
// DashboardViewModel:
    /** The message being written; cleared when the dialog closes or the gate closes. Never in UiState, never saved. */
    val messageText: TextFieldState
    fun askPause(sessionId: String); fun askStop(sessionId: String); fun confirmCommand(); fun dismissConfirm()
    fun askMessage(sessionId: String); fun sendMessage(); fun dismissMessage()

// ui/admin/SessionCards.kt
@Composable internal fun PlayingSessionCard(session: WatchSession, text: AdminText, now: Instant, canAct: Boolean, onMessage: () -> Unit, onPause: () -> Unit, onStop: () -> Unit)
@Composable internal fun IdleSessionRow(session: WatchSession, text: AdminText, now: Instant, canAct: Boolean, onMessage: () -> Unit)
```
Behaviour:
- **Polling:** the dashboard poll fetches `serverStatus` and `sessions` (in that order, as the live session) and returns `SESSIONS_POLL_MS`; only while visible, Open and online (the `AdminPoller` rules). `playing` = sessions with `nowPlaying`, `idle` = the rest, both in the mapper's order.
- **Card (`PlayingSessionCard`):** user name as the card's heading (fallback "Unnamed user"), "This phone" badge when `isThisDevice`; the now-playing line (film: title; episode: "Harbour Lights · S2 E3 · Title"; spoken "Harbour Lights, season 2, episode 3, Title"); the device line "Living room TV · Jellyfin Android TV 0.17.0" (fallback "Unnamed device"); a thin progress bar with `ProgressBarRangeInfo` and the state "12:30 of 45:00" (plus "Paused" when paused); the method sentence. Buttons "Message" (only `canMessage`), "Pause" (only `canControl` and not paused), "Stop" (only `canControl`), none for `isThisDevice`; each with the content description naming the user and device; all disabled unless `frame.canAct`.
- **Idle rows:** under the heading "Connected, not playing": "kid · Kid's tablet · Last active 3 days ago" ("Last active %1$s"), Message when `canMessage` and not this device.
- **Pause / Stop:** `askPause`/`askStop` only for a session in the latest list with `canControl` and not this device; confirm dialog (`AdminConfirmDialog`) titled and worded as the strings below, confirm label "Pause" / "Stop playback"; `confirmCommand` re-checks the target is still in the latest list (else GONE and a refresh, nothing sent), then `work.run("command:$id")` → `admin.command(...)`; success ⇒ notice "Paused kid's playback." / "Stopped kid's playback." and `poller.kick()`; the dialog closes in both cases.
- **Message:** `askMessage` (only `canMessage`, not this device) opens `MessageDialog` titled "Send a message to kid", body "It shows on Living room TV for 10 seconds."; Send ⇒ `work.run("message:$id")` → `admin.sendMessage(session, id, header = "Message from <admin's user name>", text = trimmed text)`; success ⇒ notice "Message sent to kid." and the text is cleared.
- **The session rule:** the gate closing clears `playing`, `idle`, `status`, `confirm`, `message` and `messageText` at once.

- [ ] **Tests first — `DashboardSessionsTest`** (virtual time, `FakeAdmin` counting calls): visible and online ⇒ loads at 0, 5, 10 s (3 loads in 10.5 s); `setVisible(false)` ⇒ no load in the next 60 s; visible again ⇒ a load at once; offline ⇒ none, online again ⇒ at once; playing sessions before idle ones; `askStop` for the this-phone session or a session without `canControl` does nothing; confirm ⇒ exactly one `command(s, id, STOP)` with the gate's session, notice "Stopped kid's playback.", and an immediate reload; a failing command (NETWORK) ⇒ problem NETWORK, **one call, no retry**, the dialog closed; confirm while offline ⇒ nothing sent, problem OFFLINE; a target that disappeared from the latest list ⇒ GONE, nothing sent; message: Send is refused for blank text, the header is "Message from ben", the text is trimmed; **switching account with the Stop dialog open ⇒ `confirm` null, `messageText` empty, lists empty, and a later `confirmCommand()` sends nothing**; a 403 on Stop with the rights re-check answering NOT_ADMIN ⇒ Closed and no message shown.
- [ ] **Tests first — `DashboardRenderTest` (extend):** a playing card shows the user, the episode line, "12:30 of 45:00", "The server is converting the video because the device can't play its video format."; the Stop button is described "Stop kid's playback on Living room TV", Message "Send a message to kid on Living room TV", both 48dp; the paused film shows "Paused" and no Pause button; the this-phone card shows "This phone" and no buttons; "Nobody is watching anything right now." when empty; offline ⇒ every action disabled; the Stop dialog shows its title and body and "Stop playback"; at 2.0 the buttons wrap and stay reachable.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.ui.admin.*"` → pass.
- [ ] Commit: `feat(admin): who is watching, live while on screen, with message, pause and stop after confirmation`.

---

### Task 8: Libraries and scheduled tasks

**Files:**
- Create: `ui/admin/LibrariesViewModel.kt`, `ui/admin/LibrariesScreen.kt`, `ui/admin/TasksViewModel.kt`, `ui/admin/TasksScreen.kt`.
- Modify: `ui/admin/AdminNav.kt` (wire the two routes), `ui/admin/AdminPreviews.kt`, `strings_admin.xml` ("Libraries", "Scheduled tasks").
- Test: `ui/admin/LibrariesViewModelTest.kt`, `ui/admin/TasksViewModelTest.kt`, `ui/admin/LibrariesTasksRenderTest.kt`.

**Interfaces — Consumes:** `Admin.libraries`, `scanAll`, `scanLibrary`, `tasks`, `startTask`, `stopTask`; `FULL_SCAN_TASK_KEY`.

**Produces:**
```kotlin
const val SCAN_POLL_MS = 3_000L
const val TASKS_BUSY_POLL_MS = 3_000L
const val TASKS_IDLE_POLL_MS = 30_000L

data class LibrariesUiState(
    val frame: AdminFrame = AdminFrame(),
    val libraries: List<LibraryFolder> = emptyList(),
    /** The "Scan Media Library" task: its state and progress are the full scan's. Null when the server has no such task. */
    val fullScan: ServerTask? = null,
) { val anyScanning: Boolean get() = fullScan?.state == TaskState.RUNNING || libraries.any { it.scanning } }
class LibrariesViewModel(catalog: ServerCatalog, rights: AdminRights, connections: ServerConnections, private val admin: Admin, clock: () -> Instant = Instant::now)
    : AdminScreenModel(catalog, rights, connections, clock) {
    val state: StateFlow<LibrariesUiState>
    fun refresh(); fun scanAll(); fun scanLibrary(libraryId: String)
}

data class TaskGroup(val category: String, val tasks: List<ServerTask>)
data class StopTaskConfirm(val taskId: String, val name: String) { override fun toString() = "StopTaskConfirm(<redacted>)" }
data class TasksUiState(val frame: AdminFrame = AdminFrame(), val groups: List<TaskGroup> = emptyList(), val confirm: StopTaskConfirm? = null)
class TasksViewModel(catalog: ServerCatalog, rights: AdminRights, connections: ServerConnections, private val admin: Admin, clock: () -> Instant = Instant::now)
    : AdminScreenModel(catalog, rights, connections, clock) {
    val state: StateFlow<TasksUiState>
    fun refresh(); fun run(taskId: String); fun askStop(taskId: String); fun confirmStop(); fun dismissConfirm()
}
```
Behaviour:
- **Libraries poll:** `libraries` then `tasks` (to find `FULL_SCAN_TASK_KEY`; a `FORBIDDEN`/failure of the tasks part leaves `fullScan` null and is not a load problem); returns `SCAN_POLL_MS` while `anyScanning`, else null (wait for a kick). After `scanAll`/`scanLibrary` succeed: notice "Scan of all libraries started." / "Scan of Films started." and a kick (so polling runs at 3 s until every library is idle). "Scan all libraries" is disabled while `fullScan` is RUNNING; a library's Scan is disabled while it is scanning; all disabled unless `canAct`. No confirmation (a scan changes nothing that cannot be repeated; see "Decisions made").
- **Libraries screen:** a primary button "Scan all libraries"; while the full scan runs, `AdminProgress` "Scanning all libraries: 45%" (or "Scanning all libraries…" without a figure). One card per library: name as heading, kind word, `AdminProgress` "Scanning: 30%" / "Scanning…" or the line "Not scanning", and `AdminAction` "Scan" described "Scan Films". Empty: "This server has no libraries yet."
- **Tasks poll:** returns `TASKS_BUSY_POLL_MS` while any task is RUNNING or CANCELLING, else `TASKS_IDLE_POLL_MS`. Grouped by category (empty category "Other"), groups in alphabetical order.
- **Tasks screen:** a heading per category; per task: name (heading level below), description, state ("Not running", `AdminProgress` "Running: 40%" / "Running…", "Stopping…"), last run ("Last run Today at 03:00: Completed", "Not run yet"), and for FAILED/ABORTED with an error "The server said: ffmpeg exited with code 1". Buttons: "Run now" (described "Run Clean Cache Directory now") when IDLE; "Stop" (described "Stop Scan Media Library") when RUNNING, opening the confirmation "Stop Scan Media Library?" / "The task stops part way. It runs again at its next scheduled time, or you can run it now." with confirm label "Stop task". `run` ⇒ notice "Clean Cache Directory started." and a kick; `confirmStop` re-checks the task is still RUNNING in the latest list (else GONE + refresh, nothing sent). Empty: "This server reported no scheduled tasks."

- [ ] **Tests first — `LibrariesViewModelTest`:** idle libraries ⇒ one load and then none until a kick (virtual 5 minutes); `scanAll` ⇒ one `scanAll` call, notice, then loads every 3 s while the fake reports scanning and stops when all idle; `scanLibrary("films")` sends that id only; buttons' enabled state follows `anyScanning`, `canAct` and the library's own `scanning`; a failure of the tasks part leaves `fullScan` null with no load problem; NETWORK on `scanAll` ⇒ problem, one call; Closed ⇒ lists cleared.
- [ ] **Tests first — `TasksViewModelTest`:** grouping and order; 3 s polling while a task runs, 30 s when idle, none while hidden; `run` sends once and kicks; Stop needs the confirmation; `confirmStop` for a task no longer running sends nothing (GONE); the confirmation is dropped when the gate closes.
- [ ] **Tests first — `LibrariesTasksRenderTest`** (phone and tablet, 1.0 and 2.0): labels and descriptions above; progress semantics carry the percentage ("Scanning: 30%"); disabled states; the failed task shows "Last run …: Failed" and "The server said: …"; the never-run task "Not run yet"; the Stop dialog's title, body and "Stop task"; every action 48dp; at 2.0 everything reachable.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.ui.admin.*"` → pass.
- [ ] Commit: `feat(admin): library scans with progress, and scheduled tasks with run and stop`.

---

### Task 9: Users — list, key policy, enable or disable

**Files:**
- Create: `ui/admin/UsersViewModel.kt`, `ui/admin/UsersScreen.kt`.
- Modify: `ui/admin/AdminNav.kt`, `ui/admin/AdminPreviews.kt`, `strings_admin.xml` ("Users").
- Test: `ui/admin/UsersViewModelTest.kt`, `ui/admin/UsersRenderTest.kt`.

**Interfaces — Consumes:** `Admin.users`, `Admin.libraries` (names for library ids), `Admin.setUserDisabled`.

**Produces:**
```kotlin
data class UserRow(val user: ServerUser, /** Library names in the server's order; null when they could not be read. */ val libraryNames: List<String>?)
data class UserConfirm(val userId: String, val name: String, val disable: Boolean, val isAdministrator: Boolean) { override fun toString() = "UserConfirm(<redacted>)" }
data class UsersUiState(val frame: AdminFrame = AdminFrame(), val users: List<UserRow> = emptyList(), val confirm: UserConfirm? = null)
class UsersViewModel(catalog: ServerCatalog, rights: AdminRights, connections: ServerConnections, private val admin: Admin, clock: () -> Instant = Instant::now)
    : AdminScreenModel(catalog, rights, connections, clock) {
    val state: StateFlow<UsersUiState>
    fun refresh(); fun askDisable(userId: String); fun askEnable(userId: String); fun confirm(); fun dismissConfirm()
}
```
Behaviour:
- Loads once when live (`users`, then `libraries`; a libraries failure leaves `libraryNames` null, not a load problem); manual refresh only (null delay).
- **Card:** name as heading (fallback "Unnamed user"), badges "You", "Administrator", "Disabled"; "Last seen Today at 14:02" or "Not seen yet"; when `policyKnown`: "Can download: yes"/"Can download: no"; libraries: "Libraries: all" when `allLibraries`, "Libraries: none" when the list is empty, "Libraries: Films, TV" (names of the ids found, in that user's id order), "Libraries: Films, TV and 2 more" (beyond 3 names, or ids without a name), "Libraries: 2 chosen" when names are null. Not `policyKnown`: the line "This account's settings couldn't be read." and no button.
- **Buttons:** "Disable account" (caution, described "Disable kid's account") or "Enable account" (described "Enable guest's account"); never for `isYou` (the line "This is you. You can't disable your own account here." instead); disabled unless `canAct`.
- **Confirmations:** disable — title "Disable kid's account?", body "kid won't be able to sign in or watch anything until you enable the account again." plus, for an administrator, "kid is an administrator."; confirm label "Disable account". Enable — "Enable guest's account?" / "guest will be able to sign in and watch again." / "Enable account". `confirm` re-checks the user is in the latest list with the opposite state (else GONE + refresh, nothing sent), then `work.run("user:$id")` → `setUserDisabled`; success ⇒ notice "kid's account is disabled." / "guest's account is enabled." and a kick.
- A footnote at the end of the list: "Resetting passwords and adding users are done in the full dashboard." (SPEC §3.4 excludes password reset; creation is not in SPEC).

- [ ] **Tests first — `UsersViewModelTest`:** `askDisable` for `isYou` or for a user without `policyKnown` does nothing; confirm sends exactly one `setUserDisabled(s, id, true)`; a user already disabled in the latest list ⇒ GONE, nothing sent; NETWORK ⇒ problem, one call; library names resolved and the "and N more" rule; libraries failure ⇒ `libraryNames` null; no polling (5 virtual minutes, one load); Closed ⇒ cleared.
- [ ] **Tests first — `UsersRenderTest`** (phone and tablet, 1.0 and 2.0): every line and badge above; button descriptions; the you-line instead of a button; the administrator line in the disable confirmation; 48dp; reachable at 2.0; the footnote.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.ui.admin.*"` → pass.
- [ ] Commit: `feat(admin): users with their key settings, and enable or disable after confirmation`.

---

### Task 10: Activity log and devices

**Files:**
- Create: `ui/admin/ActivityViewModel.kt`, `ui/admin/ActivityScreen.kt`, `ui/admin/DevicesViewModel.kt`, `ui/admin/DevicesScreen.kt`.
- Modify: `ui/admin/AdminNav.kt`, `ui/admin/AdminPreviews.kt`, `strings_admin.xml` ("Activity", "Devices").
- Test: `ui/admin/ActivityViewModelTest.kt`, `ui/admin/DevicesViewModelTest.kt`, `ui/admin/ActivityDevicesRenderTest.kt`.

**Interfaces — Consumes:** `Admin.activity`, `Admin.devices`, `Admin.removeDevice`.

**Produces:**
```kotlin
const val ACTIVITY_PAGE = 50
const val ACTIVITY_MAX_ENTRIES = 1_000
data class ActivityUiState(
    val frame: AdminFrame = AdminFrame(), val entries: List<ActivityEntry> = emptyList(),
    val loadingMore: Boolean = false, val endReached: Boolean = false, val limitReached: Boolean = false,
)
class ActivityViewModel(catalog: ServerCatalog, rights: AdminRights, connections: ServerConnections, private val admin: Admin, clock: () -> Instant = Instant::now)
    : AdminScreenModel(catalog, rights, connections, clock) {
    val state: StateFlow<ActivityUiState>
    /** Starts again from the newest entry. */ fun refresh()
    fun loadMore()
}

data class DeviceConfirm(val deviceId: String, val name: String, val userNames: String) { override fun toString() = "DeviceConfirm(<redacted>)" }
data class DevicesUiState(val frame: AdminFrame = AdminFrame(), val devices: List<ServerDevice> = emptyList(), val confirm: DeviceConfirm? = null)
class DevicesViewModel(catalog: ServerCatalog, rights: AdminRights, connections: ServerConnections, private val admin: Admin, clock: () -> Instant = Instant::now)
    : AdminScreenModel(catalog, rights, connections, clock) {
    val state: StateFlow<DevicesUiState>
    fun refresh(); fun askRemove(deviceId: String); fun confirmRemove(); fun dismissConfirm()
}
```
Behaviour:
- **Activity:** the first page (`startIndex = 0`, `limit = ACTIVITY_PAGE`) loads when live; manual refresh replaces the list from the start; `loadMore` (button "Show older entries" at the end, never automatic, ignored while a load runs or after the end) asks from `nextStartIndex`; entries de-duplicated by id across pages (first wins) and kept newest first; at `ACTIVITY_MAX_ENTRIES` no more is asked and the end line reads "That's as far back as Shoalwatch shows. The full log is in the dashboard."; a real end "That's everything in the log."; empty "Nothing in the activity log yet.". A `loadMore` failure is shown as the problem and the button stays (no automatic retry). Rows: a severity word with an icon (`Icons.Filled.Info` for Information, `Icons.Filled.Warning` for Warning, Error and Critical; the word is always written, colour never the only signal), the text, the detail when present, and the time ("Today at 14:02"); each row is one element read as "Warning. kid failed to sign in. IP address: 192.168.1.20. Today at 14:02".
- **Devices:** loads once when live; manual refresh. Rows: name as heading (fallback "Unnamed device"), "Signed in as kid, ben" ("Signed in as %1$s"), "Jellyfin Android TV 0.17.0", "Last seen 5 min ago"; `AdminAction` "Remove" (caution, described "Remove Living room TV"). **This phone:** "This phone" badge, no Remove, and the line "This phone. To sign it out, use Sign out in Servers and accounts." Confirmation: title "Remove Living room TV?", body "The sign-in for kid on this device ends, and they'll need to sign in on it again.", confirm label "Remove device". `confirmRemove` re-checks it is in the latest list and is not this phone (else GONE + refresh / nothing), then `work.run("device:$id")` → `removeDevice`; success ⇒ notice "Living room TV removed." and a kick. Empty: "No devices are signed in."

- [ ] **Tests first — `ActivityViewModelTest`:** first page then `loadMore` asks `startIndex = nextStartIndex`; overlapping pages are de-duplicated; a second `loadMore` while one runs is ignored; the 1,000 cap stops asking and sets `limitReached`; `endReached` stops the button; refresh starts again at 0 and drops what was loaded; a failure keeps the entries and sets the problem; no polling; Closed ⇒ cleared.
- [ ] **Tests first — `DevicesViewModelTest`:** `askRemove` for this phone does nothing; confirm sends exactly one `removeDevice(s, id)`; a device gone from the latest list ⇒ GONE, nothing sent; NETWORK ⇒ problem, one call; offline ⇒ nothing; Closed with the dialog open ⇒ dropped, nothing sent.
- [ ] **Tests first — `ActivityDevicesRenderTest`** (phone and tablet, 1.0 and 2.0): the severity word and the whole-row spoken text; "Show older entries" 48dp and its end lines; device lines, badge, the this-phone line with no Remove button; the confirmation's title, body and "Remove device"; reachable at 2.0.
- [ ] Implement.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.ui.admin.*"` → pass.
- [ ] Commit: `feat(admin): activity log newest first with severity, and devices with remove after confirmation`.

---

### Task 11: Restart and shut down (typed confirmation), the boundary test, and the on-device smoke test

**Files:**
- Create: `ui/admin/PowerViewModel.kt`, `ui/admin/PowerScreen.kt`; `app/src/test/java/app/shoalwatch/AdminBoundaryTest.kt`; `app/src/androidTest/java/app/shoalwatch/ui/AdminSmokeTest.kt`.
- Modify: `ui/admin/AdminNav.kt`, `ui/admin/AdminPreviews.kt`, `strings_admin.xml` ("Restart or shut down").
- Test: `ui/admin/PowerViewModelTest.kt`, `ui/admin/PowerRenderTest.kt`.

**Interfaces — Consumes:** `Admin.serverStatus`, `Admin.restart`, `Admin.shutDown`, `TypedConfirmDialog`, `typedMatches`.

**Produces:**
```kotlin
enum class PowerAction { RESTART, SHUT_DOWN }
data class PowerUiState(
    val frame: AdminFrame = AdminFrame(), val status: ServerStatus? = null,
    val confirm: PowerAction? = null, val canConfirm: Boolean = false,
    /** What was asked of the server and accepted; buttons stay off afterwards on this screen. */
    val requested: PowerAction? = null,
)
class PowerViewModel(catalog: ServerCatalog, rights: AdminRights, connections: ServerConnections, private val admin: Admin, clock: () -> Instant = Instant::now)
    : AdminScreenModel(catalog, rights, connections, clock) {
    val state: StateFlow<PowerUiState>
    /** The typed word; never in UiState, cleared on dismiss, after confirming and when the gate closes. */
    val typed: TextFieldState
    fun refresh(); fun ask(action: PowerAction); fun confirm(); fun dismiss()
}
```
Behaviour:
- Loads `serverStatus` once when live (the server name is the target the dialogs name). Screen: the server card (name, version, pending-restart notice), then "Restart server" and "Shut down server" (both `CautionButton`, disabled unless `canAct`, `status` loaded and `requested` null).
- `ask` opens `TypedConfirmDialog`: RESTART — title "Restart Test Jellyfin?", body "Everyone watching will be stopped. The server is usually back within a minute or two.", word "restart", field "Type restart to confirm", confirm label "Restart server". SHUT_DOWN — "Shut down Test Jellyfin?", "Everyone watching will be stopped, and the server won't come back until someone starts it again on the computer it runs on. Shoalwatch can't start it.", word "shut down", "Type shut down to confirm", "Shut down server". `canConfirm` = `typedMatches(typed.text, word)` (via `snapshotFlow`). `confirm` does nothing unless `canConfirm` and `canAct`; then `work.run("power")` → `restart`/`shutDown` once; success ⇒ `requested` set and the notice "Restart requested. The server is usually back within a minute or two." / "Shut-down requested. The server won't come back until it's started again on its computer."; the typed text is cleared whatever the outcome.
- After a request the server will drop off; the app's normal address selector shows it as offline. No automatic re-check is started and nothing is retried.

`AdminBoundaryTest` (source scan, as `PlaybackBoundaryTest`, failures name file and line):
- nothing under `ui/` contains `app.shoalwatch.data.admin.api`, `AdminGateway`, `UserPolicyEdit`, `ServerApi`, `import okhttp3.`, `toHttpUrl`, `Intent.ACTION_VIEW` or `Uri.parse`;
- nothing under `data/admin/` or `ui/admin/` contains `SharedPreferences`, `getSharedPreferences`, `DataStore`, `@Dao`, `@Entity`, `RoomDatabase`, `SavedStateHandle`, `ClipboardManager`, `NotificationCompat`, `File(`, `openFileOutput`, `Log.` (other than `AppLog.`), or `println`;
- `data/admin/api/AdminDtos.kt` declares none of the serial names `AccessToken`, `RemoteEndPoint`, `LocalAddress`, `LogPath`, `ProgramDataPath`, `CachePath`, `WebPath`, `Locations`, `IconUrl`, `LongErrorMessage`, `Overview"` (exact `@SerialName("…")` match);
- every `class` declared in `data/admin/AdminModels.kt` overrides `toString`;
- every `*Screen.kt` in `ui/admin/` draws through `AdminScaffold` (so `FLAG_SECURE` cannot be forgotten).

`AdminSmokeTest` (on a device; compile here, the controller runs it): seeds one server and two accounts (ben = admin, kid = not) through the real `AppGraph` like `BrowseSmokeTest` (plain http to an in-process `MockWebServer` with consent, cleaning up even if seeding fails part way); the mock serves `/System/Info/Public`, `/Users/Me` (per token: ben admin, kid not), `/System/Info`, `/Sessions` (kid playing an episode, transcoding), `POST /Sessions/{id}/Playing/Stop` (204) and the browsing routes Home needs. With ben active: open Servers and accounts, assert "Server admin for <server>" exists, tap it, wait for "Who is watching" and kid's card; assert the activity window has `FLAG_SECURE`; tap "Stop kid's playback on Living room TV", then "Stop playback"; assert exactly one `POST /Sessions/<id>/Playing/Stop` arrived with `Authorization` carrying ben's token and no `api_key`-like query name; then switch the active account to kid through `graph.serverCatalog.setActive`: assert within 5 s that no admin text is on screen, the window no longer has `FLAG_SECURE`, and Servers and accounts shows no "Server admin"; assert no request after the switch went to any admin path with kid's token except `/Users/Me`.

- [ ] **Tests first — `PowerViewModelTest`:** `confirm` without the typed word sends nothing; "Restart" typed for SHUT_DOWN does not match; matched ⇒ exactly one `restart` call, `requested` RESTART, buttons off, typed cleared; a NETWORK failure ⇒ problem, one call, `requested` null, typed cleared; offline ⇒ nothing; the gate closing with the dialog open and text typed ⇒ `confirm` null, typed empty, a later `confirm()` sends nothing.
- [ ] **Tests first — `PowerRenderTest`** (phone and tablet, 1.0 and 2.0): both buttons and their disabled states; the dialogs' titles naming the server, bodies, field labels and confirm labels; the confirm button disabled until the word is typed; the requested notices; 48dp; reachable at 2.0.
- [ ] **Tests first — `AdminBoundaryTest`** as above (it must fail on a deliberately planted violation in a scratch copy before the real tree passes — run once with a temporary `ui/admin/Planted.kt` holding `import okhttp3.OkHttpClient`, see it fail with that file named, delete it).
- [ ] Implement; write `AdminSmokeTest`.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.ui.admin.*" --tests "app.shoalwatch.AdminBoundaryTest" --tests "app.shoalwatch.SourceShapeTest"` → pass; then `./gradlew.bat assembleDebugAndroidTest` → builds.
- [ ] Commit: `feat(admin): restart and shut down behind a typed confirmation, the admin boundary test and the on-device smoke test`.

---

### Task 12: Milestone close

- [ ] Full verification: `./gradlew.bat clean testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease` → all green; `verifyReleaseManifest` unchanged (no new permission, component or `<queries>`); `SourceShapeTest` (no main file over 400 lines), `AdminBoundaryTest`, `PlaybackBoundaryTest`, `ArtworkBoundaryTest`, `GatewayBoundaryTest`, `ManifestSecurityTest`, `SqlCompatibilityTest` pass; `git diff main --stat` touches no file outside the parallel-branch list in Global Constraints (check by eye; list any exception in the security check with the reason); `gradle/verification-metadata.xml` unchanged.
- [ ] **Controller: emulator run** of every instrumented test including `AdminSmokeTest`; walkthrough against `tools/mockjelly` (`python -I tools/mockjelly/mock_jellyfin.py 8096`, then again with `--pending-restart`, `--ben-not-admin`, `--admin-forbidden`): sign in as ben ⇒ "Server admin" on the server card; dashboard shows "Test Jellyfin", "Jellyfin 12.4.0", kid's transcoding card with the plain-words reason, the paused laptop, the idle tablet and "This phone" with no buttons; the mock log shows `/Sessions` every 5 s while the dashboard is shown and **nothing while the app is in the background or another screen is on top**; Pause and Stop kid (confirmation each; the mock's `/mock/admin-events` shows one event each); send a message; scan Films and watch progress 0 → 100; scan all; run "Clean Cache Directory" and stop "Scan Media Library"; Users shows ben (You, Administrator), kid (Can download: no, Libraries: Films), guest (Disabled); disable kid (confirmation) then confirm kid can no longer sign in; enable kid again; Activity pages to 120 entries and shows "That's everything in the log."; Devices shows "Signed in as kid", every device without any token text, this phone without Remove; remove "Kid's tablet"; Restart (typed) ⇒ "Restart requested…"; Shut down typed wrong ⇒ button stays off; "Open the full dashboard in your browser" opens `http://10.0.2.2:8096/web/` (the mock answers 404 there: expected — it is not Jellyfin); `--pending-restart` shows "Restart needed"; `--ben-not-admin` shows no "Server admin"; `--admin-forbidden` shows "The server refused that…" and stays; `POST /mock/demote` while on the dashboard ⇒ every admin screen closes within 60 s (the re-check) or at the next 403; **switch to kid while on Devices** ⇒ admin screens gone at once, recents preview of the app shows no admin content (it was `FLAG_SECURE`), Servers shows no "Server admin", Back never returns to an admin screen; sign out ben while on Users (same); airplane mode on the dashboard ⇒ "You're offline" and every action disabled, polling stops, comes back at once on reconnecting. Screenshots checked by eye at font scale 1.0 and 2.0 on phone and tablet emulators; TalkBack pass over every admin control and dialog.
- [ ] Whole-milestone review (code + security against SPEC §6 and the threat model, focused on: the gate (session change, demotion, 401/403, offline) and that nothing is sent as a non-admin; that every action is sent once and never retried; the policy round-trip (no field lost; required fields checked); device ids as query values; that `AccessToken`, `RemoteEndPoint`, `LocalAddress` and paths are never decoded; `FLAG_SECURE` on every admin screen and dialog; nothing persisted (Room, prefs, files, saved state, nav args, clipboard, notifications); the browser intent (verified address only, no token, no query); logs by kind only; the merged manifest); fix wave; re-review.
- [ ] `docs/security/milestone-6-check.md` (same structure as `milestone-3-check.md`): summary verdict; one row per S1–S13 with evidence (test names: `AdminGatewayRulesTest`, `AdminHostileTest`, `UserPolicyEditTest`, `AdminAccessTest`, `AdminGuardTest`, `AdminWorkTest`, `BrowserDashboardTest`, `AdminBoundaryTest`, `DashboardSessionsTest`, `AdminSmokeTest`); findings by severity; residual risks: (1) the client gate is a convenience — a hostile or compromised server can still claim anyone is an administrator, which only shows that account screens the server would refuse anyway; (2) between a demotion on the server and the next re-check (≤ 60 s) or 403, read-only screens can still show what the account could read without elevation (`/Sessions`, `/Users`, `/System/Info` are `DefaultAuthorization`); (3) an action already sent when the profile changes completes on the server; (4) the browser dashboard opens the verified address, which on a plain-http home address is plain http (no credential is sent by the app; the browser's own sign-in then crosses that network — the user chose http); (5) disabling a user does not end their current playback (Jellyfin's behaviour); (6) the plain-http residual risk is unchanged; (7) other household members' names, viewing and device names are shown to the administrator by design (SPEC §3.4) — protected from screenshots and the recents preview by `FLAG_SECURE`, and never stored. Plus section "Unproven until Ben's real server" (copied from this plan, updated with walkthrough results).
- [ ] `docs/ARCHITECTURE.md`: a new section **"Server admin (`data/admin`, `ui/admin`)"** inserted directly after "Browsing screens (Milestone 2)" (before "Player engine and controller", so it does not collide with Milestone 4–5 sections): the gate and `AdminRights`, the endpoint table from Task 3 with security requirements, the untrusted-input rules from Task 2, the policy round-trip, never-retried actions, polling only while visible, `FLAG_SECURE`, nothing persisted, the browser dashboard, the decisions below, and known limits. Package map: one line `│   ├── admin/       Admin (the seam), AdminModels, AdminRights (+ AdminAccess), BrowserDashboard; api/ AdminDtos, AdminMappers, UserPolicyEdit, AdminGateway (internal); see "Server admin"` inserted directly after the `servers/` entry. The `ui/` tree in "UI (Task 8)": `├── admin/        the Server admin screens (see "Server admin")` after `servers/`. "Layer rules": one bullet "Admin requests go only through `Admin`, gated on `AdminRights`; see Server admin."
- [ ] ChangeLog: one plain-English line (minor bump from the top line at commit time), e.g. "v1.94 — DD/MM/YYYY — Server admin, for server administrators: see who is watching and what (and whether the server is converting it), send them a message or pause or stop it; scan libraries; run or stop scheduled tasks; see users and switch accounts on or off; read the activity log; remove signed-in devices; and restart or shut down the server, each behind a clear confirmation. Nothing from these screens is kept on the phone or shown to another profile."
- [ ] TODO.md via the **project-todo** skill (keeps the Obsidian mirror byte-for-byte): tick "Milestone 6 — server admin screens"; add follow-ups: "Confirm the admin screens on Ben's server (see `docs/security/milestone-6-check.md` §5)"; "Admin: logs viewer — decided out of v1 (SPEC §3.4 does not list it; logs hold addresses and tokens); revisit only if Ben asks"; "Admin: user creation and password reset stay in the browser dashboard (SPEC §3.4)". Update the Obsidian project note with the milestone summary.
- [ ] **Device-only list** (for the controller and Ben; nothing installed without Ben's explicit yes): `AdminSmokeTest` on the emulator; the walkthrough above; TalkBack and font scale 2.0 by eye; recents-preview check after a profile switch; then Ben's phone and tablet against his 12.4 server — every item in "Unproven until Ben's real server", with results recorded in the security check and ARCHITECTURE.

---

## Strings (`app/src/main/res/values/strings_admin.xml`, UK English, exactly as written)

Apostrophes are written as `’` (U+2019) in the XML, as the existing `strings.xml` does (e.g. `banner_generic`); the ellipsis is `…` (U+2026); a literal percent sign is `%%` in a string that has arguments. Tests always read strings through resources (`text(R.string.…)`), never hard-coded, so the typographic characters cannot break them.

**Entry (Task 6):** `admin_entry` "Server admin"; `admin_entry_cd` "Server admin for %1$s".

**Common (Task 5):** `admin_checking` "Checking you're an administrator…"; `admin_offline_title` "You're offline"; `admin_offline_body` "Server admin needs a connection to your server. Buttons are switched off until it's back."; `admin_updated_at` "Last updated %1$s"; `admin_refresh` "Refresh"; `admin_dismiss` "Dismiss"; `admin_problem_offline` "You're offline. Server admin needs a connection to your server."; `admin_problem_network` "Couldn't reach the server. Try again."; `admin_problem_refused` "The server refused that. Your account may not be allowed to do it."; `admin_problem_gone` "That's no longer on the server. The list has been refreshed."; `admin_problem_server` "The server reported a problem. Try again later."; `admin_problem_connection` "The connection to the server needs checking. Open Servers and accounts."; `admin_problem_other` "Something went wrong. Try again."; `admin_refresh_failed` "Couldn't refresh just now. Showing what the server said earlier."; `admin_this_phone` "This phone"; `admin_you` "You"; `admin_unnamed_user` "Unnamed user"; `admin_unnamed_device` "Unnamed device"; `admin_untitled` "Untitled".

**Times (Task 5):** `admin_time_just_now` "Just now"; `admin_time_minutes` "%1$s min ago"; `admin_time_today` "Today at %1$s"; `admin_time_yesterday` "Yesterday at %1$s"; `admin_never` "Never".

**Dialogs (Task 5):** `admin_type_to_confirm` "Type %1$s to confirm"; `admin_type_exactly` "Type the word exactly as shown."; `admin_message_label` "Message"; `admin_message_counter` "%1$s of 200 characters"; `admin_message_send` "Send". (Cancel is the existing `action_cancel`.)

**Dashboard (Task 6):** `admin_title` "Server admin"; `admin_version` "Jellyfin %1$s"; `admin_pending_restart_title` "Restart needed"; `admin_pending_restart_body` "The server needs a restart to finish an update or a settings change."; `admin_shutting_down` "The server is shutting down."; `admin_manage_heading` "Manage"; `admin_link_libraries` "Libraries" / `admin_link_libraries_detail` "Scan for new files and see scan progress"; `admin_link_tasks` "Scheduled tasks" / `admin_link_tasks_detail` "See what runs, and run or stop a task"; `admin_link_users` "Users" / `admin_link_users_detail` "See accounts, and switch them on or off"; `admin_link_activity` "Activity" / `admin_link_activity_detail` "Recent sign-ins, playback and errors"; `admin_link_devices` "Devices" / `admin_link_devices_detail` "Signed-in devices, and remove one"; `admin_link_power` "Restart or shut down" / `admin_link_power_detail` "Restart or shut down the server"; `admin_open_dashboard` "Open the full dashboard in your browser"; `admin_open_dashboard_note` "For everything else. You'll sign in again in the browser: Shoalwatch doesn't pass on your sign-in."; `admin_no_browser` "There's no web browser on this phone to open the dashboard.".

**Who is watching (Task 7):** `admin_watching_heading` "Who is watching"; `admin_watching_none` "Nobody is watching anything right now."; `admin_watching_idle_heading` "Connected, not playing"; `admin_last_active` "Last active %1$s"; `admin_session_episode` "%1$s · S%2$s E%3$s · %4$s"; `admin_session_episode_spoken` "%1$s, season %2$s, episode %3$s, %4$s"; `admin_session_device` "%1$s · %2$s %3$s"; `admin_session_device_no_version` "%1$s · %2$s"; `admin_session_progress` "%1$s of %2$s"; `admin_session_paused` "Paused"; `admin_method_direct_play` "Playing the original file."; `admin_method_direct_stream` "Playing the original video. The server is repackaging it."; `admin_convert_video` "The server is converting the video"; `admin_convert_audio_only` "The server is converting the sound only"; `admin_convert_sentence` "%1$s %2$s"; `admin_convert_no_reason` "%1$s."; `admin_reason_video_format` "because the device can't play its video format."; `admin_reason_audio_format` "because the device can't play its sound format."; `admin_reason_file_type` "because the device can't open its file type."; `admin_reason_subtitles` "so it can draw the subtitles into the picture."; `admin_reason_bitrate` "to fit a quality or bandwidth limit."; `admin_reason_hdr` "so its HDR colours look right on that screen."; `admin_reason_audio_channels` "to mix the sound down for the device."; `admin_reason_direct_play_failed` "because the device couldn't play the original."; `admin_session_message` "Message" / `admin_session_message_cd` "Send a message to %1$s on %2$s"; `admin_session_pause` "Pause" / `admin_session_pause_cd` "Pause %1$s's playback on %2$s"; `admin_session_stop` "Stop" / `admin_session_stop_cd` "Stop %1$s's playback on %2$s"; `admin_confirm_pause_title` "Pause %1$s's playback?"; `admin_confirm_pause_body` "%1$s on %2$s will pause. They can carry on when they like."; `admin_confirm_pause_action` "Pause"; `admin_confirm_stop_title` "Stop %1$s's playback?"; `admin_confirm_stop_body` "%1$s on %2$s will stop."; `admin_confirm_stop_action` "Stop playback"; `admin_message_title` "Send a message to %1$s"; `admin_message_body` "It shows on %1$s for 10 seconds."; `admin_message_header` "Message from %1$s"; `admin_message_sent` "Message sent to %1$s."; `admin_paused_done` "Paused %1$s's playback."; `admin_stopped_done` "Stopped %1$s's playback.".

**Libraries (Task 8):** `admin_libraries_title` "Libraries"; `admin_scan_all` "Scan all libraries"; `admin_scan_all_running` "Scanning all libraries: %1$s%%"; `admin_scan_all_running_unknown` "Scanning all libraries…"; `admin_scan` "Scan" / `admin_scan_cd` "Scan %1$s"; `admin_library_scanning` "Scanning: %1$s%%"; `admin_library_scanning_unknown` "Scanning…"; `admin_library_idle` "Not scanning"; `admin_scan_started_all` "Scan of all libraries started."; `admin_scan_started` "Scan of %1$s started."; `admin_kind_films` "Films"; `admin_kind_tv` "TV"; `admin_kind_music` "Music"; `admin_kind_collections` "Collections"; `admin_kind_mixed` "Films and TV"; `admin_kind_other` "Other"; `admin_libraries_empty` "This server has no libraries yet.".

**Scheduled tasks (Task 8):** `admin_tasks_title` "Scheduled tasks"; `admin_task_idle` "Not running"; `admin_task_running` "Running: %1$s%%"; `admin_task_running_unknown` "Running…"; `admin_task_cancelling` "Stopping…"; `admin_task_last` "Last run %1$s: %2$s"; `admin_task_never` "Not run yet"; `admin_outcome_completed` "Completed"; `admin_outcome_failed` "Failed"; `admin_outcome_cancelled` "Stopped"; `admin_outcome_aborted` "Interrupted"; `admin_task_error` "The server said: %1$s"; `admin_task_run` "Run now" / `admin_task_run_cd` "Run %1$s now"; `admin_task_stop` "Stop" / `admin_task_stop_cd` "Stop %1$s"; `admin_confirm_task_stop_title` "Stop %1$s?"; `admin_confirm_task_stop_body` "The task stops part way. It runs again at its next scheduled time, or you can run it now."; `admin_confirm_task_stop_action` "Stop task"; `admin_task_started` "%1$s started."; `admin_task_category_other` "Other"; `admin_tasks_empty` "This server reported no scheduled tasks.".

**Users (Task 9):** `admin_users_title` "Users"; `admin_user_admin` "Administrator"; `admin_user_disabled` "Disabled"; `admin_user_last_seen` "Last seen %1$s"; `admin_user_never_seen` "Not seen yet"; `admin_user_downloads_yes` "Can download: yes"; `admin_user_downloads_no` "Can download: no"; `admin_user_libraries_all` "Libraries: all"; `admin_user_libraries_none` "Libraries: none"; `admin_user_libraries_some` "Libraries: %1$s"; `admin_user_libraries_more` "%1$s and %2$s more"; `admin_user_libraries_count` "Libraries: %1$s chosen"; `admin_user_policy_unknown` "This account's settings couldn't be read."; `admin_user_disable` "Disable account" / `admin_user_disable_cd` "Disable %1$s's account"; `admin_user_enable` "Enable account" / `admin_user_enable_cd` "Enable %1$s's account"; `admin_user_you_note` "This is you. You can't disable your own account here."; `admin_confirm_disable_title` "Disable %1$s's account?"; `admin_confirm_disable_body` "%1$s won't be able to sign in or watch anything until you enable the account again."; `admin_confirm_disable_admin` "%1$s is an administrator."; `admin_confirm_enable_title` "Enable %1$s's account?"; `admin_confirm_enable_body` "%1$s will be able to sign in and watch again."; `admin_user_disabled_done` "%1$s's account is disabled."; `admin_user_enabled_done` "%1$s's account is enabled."; `admin_users_note_dashboard` "Resetting passwords and adding users are done in the full dashboard.".

**Activity (Task 10):** `admin_activity_title` "Activity"; `admin_severity_information` "Information"; `admin_severity_warning` "Warning"; `admin_severity_error` "Error"; `admin_severity_critical` "Critical"; `admin_activity_row_spoken` "%1$s. %2$s. %3$s"; `admin_activity_more` "Show older entries"; `admin_activity_limit` "That's as far back as Shoalwatch shows. The full log is in the dashboard."; `admin_activity_end` "That's everything in the log."; `admin_activity_empty` "Nothing in the activity log yet.".

**Devices (Task 10):** `admin_devices_title` "Devices"; `admin_device_users` "Signed in as %1$s"; `admin_device_app` "%1$s %2$s"; `admin_device_last_seen` "Last seen %1$s"; `admin_device_remove` "Remove" / `admin_device_remove_cd` "Remove %1$s"; `admin_device_this_phone_note` "This phone. To sign it out, use Sign out in Servers and accounts."; `admin_confirm_device_title` "Remove %1$s?"; `admin_confirm_device_body` "The sign-in for %1$s on this device ends, and they'll need to sign in on it again."; `admin_confirm_device_action` "Remove device"; `admin_device_removed` "%1$s removed."; `admin_devices_empty` "No devices are signed in.".

**Restart or shut down (Task 11):** `admin_power_title` "Restart or shut down"; `admin_restart` "Restart server"; `admin_shutdown` "Shut down server"; `admin_confirm_restart_title` "Restart %1$s?"; `admin_confirm_restart_body` "Everyone watching will be stopped. The server is usually back within a minute or two."; `admin_word_restart` "restart"; `admin_confirm_shutdown_title` "Shut down %1$s?"; `admin_confirm_shutdown_body` "Everyone watching will be stopped, and the server won't come back until someone starts it again on the computer it runs on. Shoalwatch can't start it."; `admin_word_shutdown` "shut down"; `admin_restart_requested` "Restart requested. The server is usually back within a minute or two."; `admin_shutdown_requested` "Shut-down requested. The server won't come back until it's started again on its computer.".

---

## Decisions made

| Decision | Alternative | Why |
|---|---|---|
| **Coverage follows SPEC §3.4**: dashboard (name, version, pending restart, who is watching, stop/message), libraries, scheduled tasks, users (list, last seen, enable/disable), activity log, devices (list, revoke), restart and shut down (typed), browser dashboard for anything else | The brief's wider list | SPEC wins where they differ. Read-only extras that use the same answers and add no new write path are included (users' administrator, downloads and library settings; Pause beside Stop); anything SPEC does not list that adds a new data path or write is left out (below) |
| **No server logs screen** | A logs list (name, size, date) and a viewer | SPEC §3.4 does not list logs (the brief's item conflicts; SPEC wins). Logs hold addresses, paths and tokens; showing them would add the riskiest data path in the app. The browser dashboard covers them. So `FLAG_SECURE`-for-logs and log caching questions do not arise |
| **No password reset and no user creation** | Either in v1 | SPEC §3.4 excludes password reset in the first version; creation is not listed. Both stay in the browser dashboard; the Users screen says so |
| No Resume (`Unpause`) button | Pause / Resume | The owner's items are pause and stop; Resume would act on someone else's viewing without being asked for. Pause is hidden while paused |
| **`FLAG_SECURE` on every admin screen and dialog** | Like browsing screens, none | They show other household members' names, viewing, devices and sign-in addresses; on a shared tablet the recents preview would keep that visible under the next profile — exactly the session rule's concern. Cheap (`SecureWindow` is counted per window) |
| **Admin status held in memory only, for the active session, forgotten on every change** (`AdminAccess`); re-checked on every admin screen open, every 60 s while visible, and after any 403 | Save "is admin" per account in prefs or Room | A saved flag could show an entry the server would refuse, or survive a demotion; nothing needs to persist. `/Users/Me` is one small request. No `SharedPreferences` is needed anywhere in this milestone, so none is used |
| The gateway refuses every call unless `AdminRights.allows(session)` | Rely on the screens' gate | SPEC §3.4 "admin calls are only ever sent with an administrator's token": defence in depth, so a stale screen or a bug cannot send as a non-admin. `me` is the only ungated call |
| The entry point is on the active account's server card in Servers and accounts | Home's account menu; a tab | Brief and SPEC say admins only; Servers is where server-level things live; one place keeps the shared-file edits small. Home with one account already goes straight to Servers in one tap |
| When the gate closes the screens leave silently (no message) | A "you're no longer an administrator" notice | A message would need state on the Servers screen (another shared-file edit) and, after a profile switch, would itself tell the next profile something about the last one. The entry simply is not there any more |
| Admin routes are session-bound (not in `AccountRoutes`) | Keep them like Servers on a switch | They show one account's privileged view; `ClearBrowsingOnSessionChange` must pop them, and it does with no change to it |
| Polling only while visible: sessions 5 s; libraries 3 s while scanning (else none); tasks 3 s while running, else 30 s; everything else on open and on Refresh | Push updates over Jellyfin's WebSocket | No new connection type to secure (the WebSocket would carry the token on a new path, outside `ServerApi`); polling is simple, bounded, testable in virtual time, and stops in the background |
| **Every action is sent once and never retried** (`retry = false`); destructive ones are confirmed naming the target; restart and shut down by typing the word | One confirmation style for all | SPEC §3.4 asks typed confirmation for restart/shut down; a retried restart or device removal could act twice or on the wrong state |
| Scans and "Run now" have no confirmation | Confirm every server action | They change nothing that cannot simply be repeated and are what the screens are for; the button is disabled while one is already running. Stopping a task is confirmed (it abandons work part way) |
| The confirmed target is re-checked against the latest list before sending (gone ⇒ "That's no longer on the server", nothing sent) | Send whatever was shown | Lists move every few seconds; acting on a vanished session or device is a dead end at best |
| **User enable/disable round-trips the server's own `Policy` JSON object, changing only `IsDisabled`** | Decode `UserPolicy` into a DTO and post it back | `POST /Users/{userId}/Policy` replaces the whole policy (two fields are required by the schema); a DTO would silently reset every field this app does not model, including future ones. The required fields are checked before anything is posted |
| The signed-in account cannot be disabled from here, and this phone's device cannot be removed from here | Offer them with a warning | Disabling yourself locks you out of your own server; removing this phone's device signs out every account on this phone for that server in one go. "Sign out" in Servers and accounts is the right tool and is pointed to |
| Sessions use `activeWithinSeconds=960` | No filter | What jellyfin-web's dashboard uses; without it every session the server remembers is listed |
| Per-library scan: `POST /Items/{itemId}/Refresh` with `metadataRefreshMode=Default`, `imageRefreshMode=Default`, `replaceAll*=false`, `regenerateTrickplay=false` | Also send `Recursive=true` as older web clients do | The 12.4.0 reference lists no `Recursive` parameter; sending only documented parameters with "scan for new and changed" modes is correct either way (unproven: that it recurses — below) |
| Full-scan progress from the scheduled task whose `Key` is `RefreshLibrary`; per-library from `VirtualFolderInfo.RefreshProgress`/`RefreshStatus` | Only one source | Neither alone covers both; the scan screen still works (an indeterminate "Scanning…") if the task is missing |
| Activity: no `sortBy`/`sortOrder` sent; each page sorted newest first; pages de-duplicated by id; at most 1,000 entries | Send `sortBy=DateCreated&sortOrder=Descending` | The reference declares those arrays without a style (as `includeSegmentTypes` in Milestone 3), so a server may not bind them; the server's default is newest first (unproven: below); client sorting and de-duplication are correct either way |
| Devices with the same id are one row listing every user | One row per server row | Removal is by id (`DELETE /Devices?id=`) and removes all of them; one row says truthfully what will go |
| `DeviceInfoDto.AccessToken`, `SessionInfoDto.RemoteEndPoint`, `SystemInfo.LocalAddress`, paths and folder locations are never declared in a DTO | Decode and ignore | What is not declared is never decoded, held or printable; `AdminBoundaryTest` keeps it so |
| Device ids sent only as a query value; session/task ids `[A-Za-z0-9]{1,64}`; user and library ids `JellyfinId` | Trust ids from the server's own answer | S11; device ids are client-chosen strings, so they never become a path segment |
| The browser dashboard opens `{verified address}/web/` | `/web/#/dashboard`; the https address if any | `/web/` is the web client's root on every supported version (correct either way: the user is one tap from Dashboard); the verified address is the one that answers now. No token, header or query is passed (SPEC §3.4) |
| All admin strings in a new `res/values/strings_admin.xml` | Append to `strings.xml` | Same resources, no merge conflict with Milestones 4–5, which also add strings |
| No new `AppError` kinds; admin wording via `AdminProblem` and `strings_admin.xml` | New kinds in `AppError` | `AppError.kt` is shared with the parallel branches; existing kinds cover every case |
| `AdminScaffold` builds its own Scaffold + `LazyColumn` (centred, 840dp cap) | `AppScreen` | `AppScreen` scrolls its content, and the activity log and session lists need a lazy list inside |
| Message: header "Message from <admin's user name>", text at most 200 characters, shown 10 s | Free length; the app's name as header | The receiving client shows who it is from; a short, timed message suits a TV; 200 is plenty and bounds what is sent |
| Typed confirmation word: "restart" / "shut down", trimmed, any case, spaces collapsed | Typing the server's name | Server names can be long or hard to type; a fixed short word is accessible and still deliberate. The dialog names the server in its title |

## Unproven until Ben's real server

No agent can reach the owner's server; Ben tests once at the very end (with his explicit yes before anything is installed). Each needs a result in `docs/security/milestone-6-check.md`:

- **Admin detection:** `GET /Users/Me` on 12.4 returns `Policy.IsAdministrator` for Ben's account (and false for a child profile), and a non-admin gets **403** (not 401, which would sign them out locally) from `RequiresElevation` routes.
- **Restart:** `POST /System/Restart` (`LocalAccessOrRequiresElevation`) restarts Ben's install (Docker/systemd/Windows service behave differently: under Docker without a restart policy a "restart" is a stop). Shut down only if Ben is willing to start it again by hand.
- **Per-library scan:** `POST /Items/{libraryId}/Refresh` with `Default` modes scans the whole library (recursively) like the web's "Scan library", without `Recursive`.
- **Scan progress:** `VirtualFolderInfo.RefreshStatus` values (`Idle` / `Active`?) and `RefreshProgress` null when idle; the "Scan Media Library" task's `Key` is `RefreshLibrary`.
- **Policy round-trip:** posting back the policy object as read (only `IsDisabled` changed) is accepted by 12.4 and changes nothing else (compare the user's settings in the web dashboard before and after); a disabled user is refused at sign-in and on their next request.
- **Activity order:** `/System/ActivityLog/Entries` with no `sortBy` returns newest first, and `startIndex` paging is stable while new entries arrive (de-duplication covers overlap).
- **Devices:** `DELETE /Devices?id=` with one value removes the device and signs it out; how 12.4 lists one device used by two accounts (one row or two).
- **Sessions:** `activeWithinSeconds=960` shows Ben's real clients; `SupportedCommands` contains `DisplayMessage` for clients that show messages, and `TimeoutMs` is honoured; Pause/Stop work on the Android TV and web clients; `TranscodingInfo.TranscodeReasons` names match the 12.4.0 enum; `PlayState.PlayMethod` is set while transcoding.
- **Browser dashboard:** `/web/` opens Ben's web client (including behind a reverse proxy base path), and the browser copes with his certificate (a pinned self-signed certificate, custom headers or a client certificate will not be in the browser: expected, recorded).
- **Response sizes:** `/Sessions` with real `NowPlayingItem` objects stays under the 4 MiB cap with several clients playing.

---

## Self-review (done while writing)

- **SPEC §3.4 coverage:** dashboard (Task 6 server card + Task 7 sessions with stop/message), libraries (Task 8), scheduled tasks (Task 8), users incl. last seen and enable/disable, password reset excluded (Task 9), activity log (Task 10), devices + revoke (Task 10), restart and shut down with typed confirmation (Task 11), "anything else" browser button with no token (Tasks 4, 6), explicit confirmation for destructive actions (Tasks 5, 7–11), admin calls only with an administrator's token (Tasks 3, 4, 5), admins only (Tasks 4, 6). Native screens, no web view. §7 row 6 satisfied. §6 S1–S13 checked in Task 12.
- **Placeholders:** none; every path, signature, string, command and constant is given. "DD/MM/YYYY" in the ChangeLog example is the convention's own placeholder, filled at commit time.
- **Type consistency:** `Admin` members match the Task 3 table and the ViewModels' consumers; `AdminRights` (`state`, `check`, `lost`, `allows`) is what `AdminGateway`, `AdminGuard` and `ServersViewModel` use; `AdminGate`/`AdminFrame`/`AdminProblem`/`AdminNotice` are defined once (Task 5) and used unchanged after; route constants live only in `AdminRoutes`.
