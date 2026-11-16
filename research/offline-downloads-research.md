# Offline downloads in Jellyfin Android clients — evidence base

**Researched:** 14/11/2026. **Engagement counts** (thumbs-up, comments) are GitHub / Fider API values read on that date.

**Evidence labels used throughout**

- **[V-src]** = verified directly in the client's source code
- **[V-issue]** = verified directly on the issue / PR / release page or its API record
- **[2nd]** = second-hand (forum post, user comment, vendor blog) — not independently confirmed
- **[Inf]** = my inference from the evidence

---

## 1. Summary

- **The owner's complaint maps most exactly onto the official Jellyfin Android app.** Until v2.7.0 (02/08/2026) it handed the original file to Android's system `DownloadManager` and had **no in-app offline library at all**. Since v2.7.0 it has an in-app downloads screen, but it is still **one flat list sorted by download time**, each item in a folder named after the episode title, with **only the video and one poster image** saved.
- **The most-voted request in the whole Jellyfin ecosystem on this topic** is "Support offline mode on Android mobile": **1,827 votes, opened 30/07/2019, status "started"**. "Offline Sync Feature" has 821 votes, "Support download of transcoded files" 521, "Download a batch not only single media file" 221.
- **Findroid** has the right data model (Room tables for show > season > episode, images, external subtitles, offline watched-state flag) but **no season/series batch download** (requests open since 2023, PR unmerged), **no transcoded downloads**, **no custom / SD-card folder picker**, and relies on the **system DownloadManager, which is broken on Android 17** for local-network servers.
- **Streamyfin** is the most feature-complete (season download, transcoded downloads, subtitles, trickplay, series > season grouping) but has the **weakest reliability record**: queue stalls in the background, in-progress downloads lost on app restart, downloads lost after an update, repeated "downloads don't play after update" regressions.
- **Fladder** has series/season "sync" with a Drift database and transcoded downloads, but repeated **"offline playback just spins"** reports.
- **Nobody ships all of:** batch season download + original-or-transcode choice + reliable resumable background queue + SD card + subtitles/audio choice + series > season > episode offline browsing + watched-state sync + smart downloads. That is the gap.

---

## 2. Per-client findings

### 2.1 Official Jellyfin Android app (`jellyfin/jellyfin-android`)

**Latest release:** v2.7.3, 14/09/2026 (v2.7.0 02/08/2026, v2.7.1 11/08, v2.7.2 28/08). [V-issue] https://github.com/jellyfin/jellyfin-android/releases

**How downloads worked up to v2.6.x (what most users have experienced)**

- Used Android's system `DownloadManager` (`DownloadManager.Request(uri)` in `utils/SystemUtils.kt` at tags v2.6.1–v2.6.3). [V-src] https://github.com/jellyfin/jellyfin-android/blob/v2.6.3/app/src/main/java/org/jellyfin/mobile/utils/SystemUtils.kt
- Community moderator, 11/07/2024: the app "does not currently support any offline viewing. It will always download the original file." User in the same thread: "I can't access these downloads from within jellyfin." [2nd] https://forum.jellyfin.org/t-in-app-download-android?pid=29578
- Maintainer, Oct 2022 (issue #139): the app loads its web UI from the server, so offline support needs a native rewrite or cached web UI — "a huge task". [V-issue] https://github.com/jellyfin/jellyfin-android/issues/139
- **Result:** a file in the phone's storage, no metadata, no artwork, no link back to series/season, playable only in a third-party player. [Inf from the above]

**How downloads work from v2.7.0 (current `master`)**

| Aspect | Finding | Evidence |
|---|---|---|
| Transport | Own downloader in a WorkManager foreground worker; one file at a time; retries on `IOException` | [V-src] `downloads/DownloadWorker.kt`; [V-issue] PR #2015 https://github.com/jellyfin/jellyfin-android/pull/2015 |
| What is fetched | **Original file** via `libraryApi.getDownloadUrl(itemId)` (`GET /Items/{id}/Download`). No transcode option | [V-src] `downloads/DownloadQueue.kt` line 159 |
| Where | User-picked folder via Storage Access Framework (so SD card is possible); `.nomedia` added | [V-src] `app/StorageManager.kt`; PR #2015 |
| Folder layout | `DownloadEntity.path = item.name ?: item.id` → **one folder per item named after the episode/movie title**, not series/season | [V-src] `downloads/DownloadManager.kt`; confirmed by the "Before" example in PR #2216 |
| Metadata | Whole `BaseItemDto` stored as a column in a Room `download` table | [V-src] `data/entity/DownloadEntity.kt` |
| Artwork | **Primary image only** (`DownloadFileType` has just `ITEM` and `IMAGE_PRIMARY`) | [V-src] `downloads/DownloadFileType.kt`; PR #2019 |
| Subtitles | **External subtitles not downloaded.** PR #2015: "In the future we should also download additional files like poster/thumbnail and external subtitles/audio" | [V-issue] PR #2015; issue #972 still open |
| Downloads UI | Single `LazyColumn`; DAO query is `SELECT * FROM download ORDER BY created_at DESC` → **flat list in download order, no series/season grouping**. Label is built as "Series - S1:E2 - Title" | [V-src] `ui/screens/downloads/DownloadsList.kt`, `data/dao/DownloadDao.kt`, `DownloadEntity.getDisplayName` |
| Watched-state sync | **None** for downloaded playback | [V-issue] #2166, PR #2169 (open, no review) |

**Documented complaints**

| Complaint | Issue | Opened | +1 / comments | State |
|---|---|---|---|---|
| Play downloaded videos inside the app | [#1114](https://github.com/jellyfin/jellyfin-android/issues/1114) | 21/06/2023 | 40 / 3 | Closed as duplicate 14/05/2025 |
| Improve downloads management (progress, delete, offline play) | [#641](https://github.com/jellyfin/jellyfin-android/issues/641) | 08/04/2022 | 32 / 8 | Closed completed 22/08/2026 |
| Music offline mode / caching | [#139](https://github.com/jellyfin/jellyfin-android/issues/139) | 20/09/2020 | 53 / 23 | **Open** |
| Download in a chosen quality | [#316](https://github.com/jellyfin/jellyfin-android/issues/316) | 09/03/2021 | 18 / 1 | **Open** |
| Custom downloads folder | [#1289](https://github.com/jellyfin/jellyfin-android/issues/1289) | 06/02/2024 | 16 / 3 | Open (folder picker shipped in 2.7.0 — see not-verified list) |
| Download subtitles with video | [#972](https://github.com/jellyfin/jellyfin-android/issues/972) | 10/02/2023 | 13 / 3 | **Open** |
| "Download all" on a season/show only downloads the last episode | [#1497](https://github.com/jellyfin/jellyfin-android/issues/1497), [#1602](https://github.com/jellyfin/jellyfin-android/issues/1602), [#1817](https://github.com/jellyfin/jellyfin-android/issues/1817), [#1937](https://github.com/jellyfin/jellyfin-android/issues/1937) | 19/10/2024 → 26/03/2026 | 2/6, 5/6, 0/1, 5/3 | All closed; #1937 closed "not planned" 26/10/2026. Reported 4 times, #1602 closed as stale |
| Download a whole season at a time | [#290](https://github.com/jellyfin/jellyfin-android/issues/290) | 28/01/2021 | 4 / 1 | Closed 14/05/2025 |
| Sync watch history for offline playback + show progress | [#2166](https://github.com/jellyfin/jellyfin-android/issues/2166) | 31/08/2026 | 5 / 0 | **Open**; PR [#2169](https://github.com/jellyfin/jellyfin-android/pull/2169) open, unreviewed |
| Downloaded file doesn't play in app (plays in VLC) | [#2188](https://github.com/jellyfin/jellyfin-android/issues/2188) | 12/09/2026 | 1 / 2 | **Open** |
| Downloads ignore the external-player setting, forced to ExoPlayer | [#2208](https://github.com/jellyfin/jellyfin-android/issues/2208) | 19/09/2026 | 0 / 3 | **Open** |
| Can't reach downloads when server is unavailable (stuck at server screen) | [#2066](https://github.com/jellyfin/jellyfin-android/issues/2066) | 07/07/2026 | 0 / 2 | Closed completed 04/08/2026 |
| Downloads silently fail if notification permission denied (2.7.3) | [#2252](https://github.com/jellyfin/jellyfin-android/issues/2252) | 28/10/2026 | 0 / 1 | Open; PR #2261 open |
| Download resume | [#770](https://github.com/jellyfin/jellyfin-android/issues/770) | 25/08/2022 | 3 / 3 | Open |
| Auto download | [#778](https://github.com/jellyfin/jellyfin-android/issues/778) | 07/09/2022 | 2 / 8 | Open |
| Mirror series/season folder layout on disk | PR [#2216](https://github.com/jellyfin/jellyfin-android/pull/2216) | 24/09/2026 | 0 / 0 | Open, unreviewed |

**Forum colour** [2nd]: "I tried multiple time to have it download the entire season of a show and it only downloads 2 random episodes" (March 2025, as surfaced by search — I did not open the post itself). Thread listing: https://forum.jellyfin.org/t-offline-downloads?pid=42571

---

### 2.2 Findroid (`jarnedemeulemeester/findroid`)

**Latest release:** v1.2.0, 25/10/2026 (v1.1.0 18/07/2026; v1.0.2 12/01/2026; v1.0.0 "Compose" 30/12/2025). No download-related notes in v1.2.0. [V-issue] https://github.com/jarnedemeulemeester/findroid/releases

**How downloads work** — all [V-src] from `core/src/main/java/dev/jdtech/jellyfin/utils/DownloaderImpl.kt` and related files on `main`:

- **Transport:** Android system `DownloadManager` (`downloadManager.enqueue(request)`). A code comment admits: "We should probably move most (if not all) code to a worker. At this moment it is possible that some things are not downloaded due to the user leaving the current screen".
- **File:** original file only — URL built with `videoApi.getVideoStreamUrl(static = true)`. README: "Direct play only, (no transcoding)".
- **Location:** `getExternalFilesDirs(null)[storageIndex]/downloads/{itemId}.{sourceId}.download` — app-private external storage, **opaque extension-less names**, removed on uninstall. `storageIndex` lets the user pick a volume the OS exposes, but there is **no arbitrary folder picker**.
- **Metadata:** Room database (schema v8) with separate tables for **movies, shows, seasons, episodes, sources, media streams, user data, trickplay, segments**. Downloading an episode also inserts its show and season rows.
- **Ordering:** DAO sorts seasons by `indexNumber`, episodes by `parentIndexNumber, indexNumber` — so **episode order is correct offline**.
- **Artwork:** `ImagesDownloaderWorker` saves primary + backdrop to `filesDir/images/{itemId}` (added in v1.0.0, 30/12/2025: "Save images locally when downloading media items").
- **Subtitles:** external media streams downloaded as separate `DownloadManager` jobs.
- **Watched state:** `userdata.toBeSynced` flag set when playback reports fail/offline (`JellyfinRepositoryOfflineImpl`, `ServerDatabaseDao`).
- **Offline mode:** a manual toggle in settings (auto-switch requested: #1204, 11 thumbs-up; PR #627 closed unmerged 02/08/2026).

**Documented complaints**

| Complaint | Issue | Opened | +1 / comments | State |
|---|---|---|---|---|
| Transcode downloads to save space | [#775](https://github.com/jarnedemeulemeester/findroid/issues/775) | 11/06/2024 | 31 / 0 | **Open**; PR [#791](https://github.com/jarnedemeulemeester/findroid/pull/791) (16 / 10) open since 18/07/2024 |
| Download storage location / SD card | [#710](https://github.com/jarnedemeulemeester/findroid/issues/710), [#108](https://github.com/jarnedemeulemeester/findroid/issues/108) | 30/03/2024, 14/04/2022 | 21 / 8, 13 / 1 | **Open**, no maintainer reply on #710 |
| Batch download a season / unwatched episodes | [#865](https://github.com/jarnedemeulemeester/findroid/issues/865) | 22/10/2024 | 19 / 2 | **Open** |
| Multi-select episodes for download | [#579](https://github.com/jarnedemeulemeester/findroid/issues/579) | 21/11/2023 | 15 / 2 | **Open** |
| One-click download all episodes of a show | [#461](https://github.com/jarnedemeulemeester/findroid/issues/461) | 08/08/2023 | 14 / 1 | **Open** |
| Season download PR | PR [#1228](https://github.com/jarnedemeulemeester/findroid/pull/1228) (earlier PR #258 closed 2023) | 18/06/2026 | 5 / 7 | Open, **no maintainer review**; users asking when it will ship |
| Auto download ahead / smart downloads | [#490](https://github.com/jarnedemeulemeester/findroid/issues/490), [#189](https://github.com/jarnedemeulemeester/findroid/issues/189) | 25/08/2023, 31/10/2022 | 12 / 4, 3 / 0 | Open; PR #1313 opened 21/10/2026 |
| Resumable downloads | [#1190](https://github.com/jarnedemeulemeester/findroid/issues/1190) | 14/04/2026 | 8 / 0 | Open |
| Sequential downloads | [#544](https://github.com/jarnedemeulemeester/findroid/issues/544) | 05/10/2023 | 7 / 4 | Open; PR #1263 open |
| **Android 17 blocks downloads** (system DownloadManager lacks local-network permission) | [#1237](https://github.com/jarnedemeulemeester/findroid/issues/1237) | 03/07/2026 | 5 / 9 | **Open**; fix PR [#1293](https://github.com/jarnedemeulemeester/findroid/pull/1293) (in-app OkHttp worker) open since 26/09/2026 |
| Downloads fail silently with split-horizon DNS / private IPs (Android 14+) | [#1179](https://github.com/jarnedemeulemeester/findroid/issues/1179) | 26/03/2026 | 5 / 1 | Open |
| No thumbnails in offline mode | [#525](https://github.com/jarnedemeulemeester/findroid/issues/525) | 26/09/2023 | 5 / 9 | Open (image saving shipped in v1.0.0 — see not-verified list) |
| Downloads don't show covers | [#211](https://github.com/jarnedemeulemeester/findroid/issues/211) | 04/12/2022 | 0 / 2 | Open |
| Downloads failed on multiple devices | [#210](https://github.com/jarnedemeulemeester/findroid/issues/210) | 04/12/2022 | 0 / 10 | Open |
| Downloads to external storage always fail | [#778](https://github.com/jarnedemeulemeester/findroid/issues/778) | 14/06/2024 | 3 / 2 | Open |
| **Can't play downloaded media offline — loading forever** (v1.1.0) | [#1310](https://github.com/jarnedemeulemeester/findroid/issues/1310) | 17/10/2026 | 0 / 2 | **Open** |
| Retry after out-of-space reports success but file is unplayable | [#1251](https://github.com/jarnedemeulemeester/findroid/issues/1251) | 04/08/2026 | 2 / 0 | Open |
| Downloads page shows the whole show when only one episode is downloaded | [#1145](https://github.com/jarnedemeulemeester/findroid/issues/1145) | 01/02/2026 | 3 / 2 | Open |
| Auto-delete after watching | [#407](https://github.com/jarnedemeulemeester/findroid/issues/407) | 08/06/2023 | 3 / 2 | Open |
| Multi-delete (show/season) | [#711](https://github.com/jarnedemeulemeester/findroid/issues/711) | 30/03/2024 | 3 / 1 | Open |
| Offline mode doesn't clear progress for completed episodes | [#990](https://github.com/jarnedemeulemeester/findroid/issues/990) | 08/06/2025 | 2 / 0 | Open; PR #1329 "sync offline watch progress when leaving offline mode" opened 13/11/2026 |
| "FOREIGN KEY constraint failed" on download | [#908](https://github.com/jarnedemeulemeester/findroid/issues/908) | 12/12/2024 | 5 / 9 | Closed 26/10/2025 |
| Downloaded files auto-deleted after a week | [#178](https://github.com/jarnedemeulemeester/findroid/issues/178) | 18/10/2022 | 0 / 0 | Closed 29/10/2022 |

**Forum colour** [2nd]: Findroid is the stock recommendation, with the caveat "make sure that your phone is capable of Direct Playing this content" (28/08/2023). https://forum.jellyfin.org/t-jellyfin-android-app-download-and-play-offline

---

### 2.3 Streamyfin (`streamyfin/streamyfin`) — React Native / Expo, iOS + Android

**Latest release:** v0.55.2, 16/10/2026 (v0.55.0 04/10/2026; v0.55.1 09/10/2026; v0.54.1 02/06/2026). [V-issue] https://github.com/streamyfin/streamyfin/releases

**How downloads work**

- **Transport:** in-house native module `modules/background-downloader` (Android: `OkHttpDownloadManager.kt` + `DownloadService.kt` foreground service). Introduced v0.47.1: "New in-house download module, supports queued background downloads". [V-src] file tree; [V-issue] release notes
- **File:** direct original **or** server transcode. v0.30.2: "Downloads optimized to bypass transcoding when possible"; v0.55.0: "Transcoded downloads with offline subtitles". [V-issue]
- **README is out of date:** it still says "Downloading works by using FFmpeg to convert an HLS stream into a video file on your device" and calls downloading "experimental". [V-src] https://github.com/streamyfin/streamyfin/blob/develop/README.md
- **Metadata store:** MMKV key-value store, not SQL. Structure `DownloadsDatabase { movies, series: { seriesInfo, seasons: { episodes } }, other }` holding the full `BaseItemDto` + `MediaSourceInfo` per item. [V-src] `providers/Downloads/types.ts`, `providers/Downloads/README.md`
- **Only completed items are persisted**; the in-progress queue lives in memory. [V-issue] #1701 (issue text analysing the code)
- **File names:** `{seriesname}_s01e02`, `{movie}_{year}` in the app's Documents directory. [V-src] `providers/Downloads/utils.ts`
- **Extras saved:** trickplay sheets, external subtitles (non-transcoded), cover + series images, intro/credit segments. [V-src] `providers/Downloads/README.md`
- **Location:** app-private only; no SD card. [V-issue] #42

**Documented complaints**

| Complaint | Issue | Opened | +1 / comments | State |
|---|---|---|---|---|
| Choose download location (internal / SD) on Android | [#42](https://github.com/streamyfin/streamyfin/issues/42) | 13/08/2024 | 17 / 10 | **Open**; moved to "In progress" 14/11/2025; PR #1486 open since 11/03/2026 |
| **Download queue stops when app is backgrounded / phone locked** | [#1246](https://github.com/streamyfin/streamyfin/issues/1246) | 29/11/2025 | 7 / 5 | **Open**, no maintainer reply; re-confirmed on Android 0.54.1 on 30/08/2026 |
| In-progress downloads lost when app closed/restarted (queue not persisted; Android `OnDestroy` cancels all) | [#1701](https://github.com/streamyfin/streamyfin/issues/1701) | 08/06/2026 | 2 / 1 | **Open**, labelled "effort: large" |
| Existing downloads lost after update (0.28.0 → 0.30.2), ~5 GB orphaned | [#941](https://github.com/streamyfin/streamyfin/issues/941) | 18/08/2025 | 1 / 6 | Open; data-loss part never confirmed fixed |
| Downloads don't play after latest update | [#588](https://github.com/streamyfin/streamyfin/issues/588) | 06/03/2025 | 1 / 14 | Closed 16/03/2025 |
| Downloads not working on 0.54.1 | [#1661](https://github.com/streamyfin/streamyfin/issues/1661) | 03/06/2026 | 2 / 10 | Closed 27/06/2026 |
| Downloads are 0 KB at "max" quality (iOS; Jellyfin 12 auth change) | [#1548](https://github.com/streamyfin/streamyfin/issues/1548) | 25/04/2026 | 0 / 11 | Open |
| Download stuck on Android | [#1298](https://github.com/streamyfin/streamyfin/issues/1298) | 29/12/2025 | 2 / 4 | Closed 06/10/2026 |
| Crash starting season download of large files | [#1234](https://github.com/streamyfin/streamyfin/issues/1234) | 23/11/2025 | 1 / 4 | Closed 06/10/2026 |
| Downloads don't have subtitles | [#240](https://github.com/streamyfin/streamyfin/issues/240), [#1325](https://github.com/streamyfin/streamyfin/issues/1325) | 02/12/2024, 08/01/2026 | 4 / 8, 2 / 7 | #240 closed 15/08/2025; **#1325 open** |
| Always download external subtitles / subtitles for offline | [#35](https://github.com/streamyfin/streamyfin/issues/35), [#56](https://github.com/streamyfin/streamyfin/issues/56) | 13/08/2024, 16/08/2024 | 8 / 2, 7 / 6 | Closed |
| Offline: audio/subtitle switch button no longer works | [#1198](https://github.com/streamyfin/streamyfin/issues/1198) | 15/11/2025 | 2 / 7 | Open |
| Transcoded downloads burn subtitles in | [#1955](https://github.com/streamyfin/streamyfin/issues/1955) | 13/08/2026 | 0 / 3 | Open |
| Downloads the wrong audio language (iOS) | [#1443](https://github.com/streamyfin/streamyfin/issues/1443) | 13/02/2026 | 2 / 3 | Open |
| No poster image for downloaded items | [#1698](https://github.com/streamyfin/streamyfin/issues/1698) | 07/06/2026 | 2 / 10 | Open; v0.55.0 notes say "Posters show for downloaded content"; a user reported it fixed 07/10/2026 |
| Progress isn't saved for downloaded movies | [#1445](https://github.com/streamyfin/streamyfin/issues/1445) | 14/02/2026 | 5 / 0 | Open |
| Offline progress not synced when server is back | [#2438](https://github.com/streamyfin/streamyfin/issues/2438) | 05/11/2026 | 0 / 0 | Closed 11/11/2026 via PR #2439 |
| Selectable resolution for downloads | [#1137](https://github.com/streamyfin/streamyfin/issues/1137) | 19/10/2025 | 4 / 1 | Open; PR #2386 "choose original or transcode" opened 30/10/2026 |
| Smart downloads / auto-download next N | [#1202](https://github.com/streamyfin/streamyfin/issues/1202), [#2273](https://github.com/streamyfin/streamyfin/issues/2273) | 15/11/2025, 14/10/2026 | 2 / 0, 0 / 4 | Open; PR #2275 open |
| Pause & resume downloads | [#1700](https://github.com/streamyfin/streamyfin/issues/1700) | 08/06/2026 | 2 / 0 | Open; PR #2493 opened 12/11/2026 |
| Whole series/season download button | [#52](https://github.com/streamyfin/streamyfin/issues/52) | 16/08/2024 | 4 / 0 | **Closed (done) 03/12/2024** |

---

### 2.4 Fladder (`DonutWare/Fladder`) — Flutter, all platforms

**Latest release:** v0.11.1, 08/09/2026 (nightly 03/11/2026). [V-issue] GitHub releases API

**How downloads ("sync") work**

- Uses the `background_downloader` Flutter package and a **Drift (SQLite)** database of `SyncedItem` rows with `parentId` (series > season > episode tree), images, trickplay and subtitles fields. [V-src] `lib/providers/sync_provider.dart`, `pubspec.yaml`
- User-chosen sync folder (folder picker) with a per-user subfolder and `.nomedia`; on mobile falls back to the app support directory. [V-src] same file
- Transcoded downloads exist (`transcode_download_model.dart`; PR #847 "feat: Transcoded downloads" closed 15/03/2026). [V-src] file present; merge status of #847 not checked
- README claims "Sync items for all platforms" and "Download items and keep progress in sync". [V-src] README

**Documented complaints**

| Complaint | Issue | Opened | +1 / comments | State |
|---|---|---|---|---|
| Offline playback not working on Android (endless spinner) | [#713](https://github.com/DonutWare/Fladder/issues/713) | 17/01/2026 | 6 / 7 | Closed 14/06/2026 via PR #1030 |
| Can't play synced media when offline | [#394](https://github.com/DonutWare/Fladder/issues/394) | 16/06/2025 | 0 / 7 | Closed 14/08/2025 |
| Download progress stuck at 0 %, then completes | [#451](https://github.com/DonutWare/Fladder/issues/451) | 13/08/2025 | 0 / 18 | Open |
| Offline synced data very slow to load | [#970](https://github.com/DonutWare/Fladder/issues/970) | 22/04/2026 | 4 / 1 | Open |
| Sync stops when app not in foreground | [#990](https://github.com/DonutWare/Fladder/issues/990) | 11/05/2026 | 0 / 4 | Closed 21/05/2026 |
| External subtitles don't work for synced items | [#989](https://github.com/DonutWare/Fladder/issues/989) | 11/05/2026 | 1 / 3 | Open |
| Offline playback on Android stutters every 9 s | [#984](https://github.com/DonutWare/Fladder/issues/984) | 08/05/2026 | 0 / 0 | Open |
| Season sync fails on Jellyfin 12.0 | [#1160](https://github.com/DonutWare/Fladder/issues/1160) | 03/10/2026 | 0 / 0 | Open |
| Sync a whole season | [#214](https://github.com/DonutWare/Fladder/issues/214) | 01/02/2025 | 3 / 1 | Closed (done) 27/07/2025 |
| Report progress during offline playback | PR [#1130](https://github.com/DonutWare/Fladder/pull/1130) | 09/09/2026 | 0 / 0 | Open |

Note: #312 "Can't use offline" (7 thumbs-up) is about the **web build needing internet assets**, not phone downloads. [V-issue]

---

### 2.5 Others (brief — mostly not verified in depth)

| Client | What I could establish | Evidence |
|---|---|---|
| **Finamp** (music) | Routinely recommended on the forum for music offline mode and download management | [2nd] https://forum.jellyfin.org/t-offline-downloads?pid=42571 |
| **Symfonium** (music, paid) | Named by a forum moderator as having an offline mode | [2nd] https://forum.jellyfin.org/t-in-app-download-android?pid=29578 |
| **Yatse** | Offline Jellyfin videos "not playing" thread (24–25/05/2025): downloads worked, playback via the tablet's default player failed; switching to VLC fixed it — Yatse hands offline files to an external player | [2nd] https://community.yatse.tv/t/offline-videos-from-jellyfin-not-playing/5162 |
| **Moonfin** | Flutter "Mobile-Desktop" client; search snippet says Android downloads are saved under `Android/data/com.moonfin.app/files/Moonfin/`. Users in Streamyfin #42 and Findroid PR #1228 say they switched to it | [2nd] search result only; https://github.com/streamyfin/streamyfin/issues/42 |
| **JellyBook, JellyPlay, Plezy** | Not researched beyond seeing them listed as having "offline downloads" | Not verified |

---

### 2.6 Feature-request board (features.jellyfin.org — Fider API, read 14/11/2026) [V-issue]

| Request | Posted | Votes | Comments | Status |
|---|---|---|---|---|
| [Support offline mode on Android mobile](https://features.jellyfin.org/posts/218/support-offline-mode-on-android-mobile) | 30/07/2019 | **1,827** | 82 | started |
| [Offline Sync Feature](https://features.jellyfin.org/posts/1341/offline-sync-feature) | 08/10/2021 | **821** | 36 | planned |
| [Support download of transcoded files](https://features.jellyfin.org/posts/57/support-download-of-transcoded-files) | 29/07/2019 | **521** | 43 | planned |
| Download a batch not only single media file (#655) | 02/06/2020 | **221** | 12 | started |
| Better control of transcoding and downloading options (#1881) | 21/12/2022 | 85 | 5 | open |
| Subtitles download (#1181) | 12/05/2021 | 49 | 10 | open |
| jellyfin-web: Offline Viewing (#3012) | 08/12/2024 | 45 | 0 | open |
| Sync Playlists For Offline Listening (#589) | 29/04/2020 | 36 | 2 | open |
| download next x episodes (#3254) | 11/04/2025 | 6 | 0 | open |
| In Application download for the Android client (#3598) | 22/11/2025 | 3 | 0 | open |
| Automatically download next episode (#4197) | 03/10/2026 | 1 | 0 | open |

---

## 3. Ranked offline-download complaints

Ranked by total engagement across sources (votes + thumbs-up + duplicate count), then by how long it has been open.

| # | Complaint | Clients affected | Strongest evidence | Engagement | Status (Nov 2026) |
|---|---|---|---|---|---|
| 1 | **No true in-app offline mode** — downloads are loose files you can't browse/play in the app | Official app (all versions ≤ 2.6.x) | [Fider #218](https://features.jellyfin.org/posts/218/support-offline-mode-on-android-mobile); [#1114](https://github.com/jellyfin/jellyfin-android/issues/1114); [#641](https://github.com/jellyfin/jellyfin-android/issues/641) | 1,827 votes / 82 comments since 2019; 821 votes on Fider #1341; 40 + 32 thumbs-up | **Partly fixed** in v2.7.0 (02/08/2026): in-app list + player. Still flat, video-only |
| 2 | **No transcode / quality choice for downloads** (original file only) | Official app, Findroid | [Fider #57](https://features.jellyfin.org/posts/57/support-download-of-transcoded-files); [Findroid #775](https://github.com/jarnedemeulemeester/findroid/issues/775); [#316](https://github.com/jellyfin/jellyfin-android/issues/316) | 521 votes since 2019; 31 + 16 (PR) + 18 thumbs-up | **Open** in both. Streamyfin shipped it in v0.55.0 (04/10/2026); Fladder has it |
| 3 | **No (working) batch season/series download** | Findroid (absent); Official app ("download all" grabs only last episode) | [Fider #655](https://features.jellyfin.org/posts/655/download-a-batch-not-only-single-media-file); [Findroid #865](https://github.com/jarnedemeulemeester/findroid/issues/865); [#1937](https://github.com/jellyfin/jellyfin-android/issues/1937) | 221 votes; Findroid 19 + 15 + 14 across 3 issues; official bug reported 4 times | Findroid: PR #1228 unmerged. Official: closed "not planned" 26/10/2026 — whether 2.7.x fixed it is unconfirmed. Streamyfin/Fladder: done |
| 4 | **No SD-card / custom download location** | Findroid, Streamyfin; Official app ≤ 2.6.x | [Findroid #710](https://github.com/jarnedemeulemeester/findroid/issues/710); [Streamyfin #42](https://github.com/streamyfin/streamyfin/issues/42); [#1289](https://github.com/jellyfin/jellyfin-android/issues/1289) | 21 + 13 + 17 + 16 thumbs-up; oldest open since 14/04/2022 | Official: SAF folder picker since 2.7.0. Findroid/Streamyfin: **open** |
| 5 | **Downloads fail, stall, don't resume, die in background** | Findroid, Streamyfin, Fladder | [Findroid #1237](https://github.com/jarnedemeulemeester/findroid/issues/1237); [Streamyfin #1246](https://github.com/streamyfin/streamyfin/issues/1246); [Streamyfin #1701](https://github.com/streamyfin/streamyfin/issues/1701); [Fladder #451](https://github.com/DonutWare/Fladder/issues/451) | Many separate issues: Findroid #1237 (5/9), #1190 (8), #544 (7), #1179 (5), #210 (10 comments); Streamyfin #1246 (7/5); Fladder #451 (18 comments) | **Open**. Findroid's Android 17 fix (PR #1293) unmerged |
| 6 | **Downloaded files won't play offline** | All four | [Findroid #1310](https://github.com/jarnedemeulemeester/findroid/issues/1310); [Streamyfin #588](https://github.com/streamyfin/streamyfin/issues/588); [Fladder #713](https://github.com/DonutWare/Fladder/issues/713); [#2188](https://github.com/jellyfin/jellyfin-android/issues/2188) | Streamyfin #588 14 comments, #1661 10, #1548 11; Fladder #713 6/7; Findroid #1310, #1251 | Recurring regressions; several **open**. Root causes vary: codec not direct-playable, app needs server to load details, partial files marked complete, auth changes in Jellyfin 12 |
| 7 | **Subtitles / audio track missing or wrong offline** | Official app, Streamyfin, Fladder | [#972](https://github.com/jellyfin/jellyfin-android/issues/972); [Streamyfin #1325](https://github.com/streamyfin/streamyfin/issues/1325); [Fider #1181](https://features.jellyfin.org/posts/1181/subtitles-download) | 13 thumbs-up (open since 10/02/2023); Streamyfin #35 (8), #56 (7), #240 (4/8), #1325 (2/7); 49 Fider votes | Official: **not implemented** (source confirms). Streamyfin: partly fixed, #1325/#1198/#1443 open |
| 8 | **Flat, unorganised downloads list; missing artwork/metadata** | Official app (flat list); Findroid/Streamyfin (missing posters) | Source: [DownloadDao.kt](https://github.com/jellyfin/jellyfin-android/blob/master/app/src/main/java/org/jellyfin/mobile/data/dao/DownloadDao.kt); PR [#2216](https://github.com/jellyfin/jellyfin-android/pull/2216); [Findroid #525](https://github.com/jarnedemeulemeester/findroid/issues/525); [Streamyfin #1698](https://github.com/streamyfin/streamyfin/issues/1698) | Low direct thumbs-up (5/9, 2/10) — but it is the substance of complaint #1 | Official: **still flat** on master. Findroid/Streamyfin: series > season grouping exists; poster bugs reportedly fixed in v1.0.0 / v0.55.0 |
| 9 | **No auto-download-next / auto-delete-watched ("smart downloads")** | All | [Findroid #490](https://github.com/jarnedemeulemeester/findroid/issues/490); [Streamyfin #2273](https://github.com/streamyfin/streamyfin/issues/2273); [#778](https://github.com/jellyfin/jellyfin-android/issues/778) | Findroid 12 + 3 + 3; Streamyfin 2 + 1 + 0; Fider 6 + 1 | **Open everywhere**; PRs open in Findroid (#1313) and Streamyfin (#2275) |
| 10 | **Watched state / progress not synced back after offline** | Official app; bugs in Streamyfin, Findroid | [#2166](https://github.com/jellyfin/jellyfin-android/issues/2166); [Streamyfin #1445](https://github.com/streamyfin/streamyfin/issues/1445); [Findroid #990](https://github.com/jarnedemeulemeester/findroid/issues/990) | 5 + 5 + 2 thumbs-up; part of Fider #1341 (821 votes) | Official: **absent**, PR #2169 unreviewed. Streamyfin: fix merged 11/11/2026 (after v0.55.2). Findroid: has mechanism, fix PR #1329 open |
| 11 | **Downloads lost on app update / restart** | Streamyfin | [Streamyfin #941](https://github.com/streamyfin/streamyfin/issues/941); [#1701](https://github.com/streamyfin/streamyfin/issues/1701) | 1/6, 2/1 | Open |
| 12 | **Weak storage management** (bulk delete, size view) | Findroid | [Findroid #711](https://github.com/jarnedemeulemeester/findroid/issues/711) | 3 / 1 | Open. Official added multi-select delete in 2.7.0; Streamyfin added delete-all + live usage in 0.55.0 |

---

## 4. Relevant Jellyfin server API

All paths below **verified against the official OpenAPI spec** (`info.version` = **12.4.0**): https://api.jellyfin.org/openapi/jellyfin-openapi-stable.json (browsable at https://api.jellyfin.org/).

| Need | Endpoint | Notes |
|---|---|---|
| Item metadata (bulk) | `GET /Items` (`ids`, `parentId`, `fields`, `enableUserData`) | Official app uses `fields=MediaSources,Path` in batches of 25 [V-src] |
| Single item | `GET /Items/{itemId}` | |
| Seasons of a series | `GET /Shows/{seriesId}/Seasons` | |
| Episodes of a series/season | `GET /Shows/{seriesId}/Episodes` (`seasonId`, `fields`, `enableUserData`) | Returns `IndexNumber` / `ParentIndexNumber` for ordering |
| Next up (for smart downloads) | `GET /Shows/NextUp` (`seriesId`, `nextUpDateCutoff`) | |
| Resume list | `GET /UserItems/Resume` | |
| Artwork | `GET /Items/{itemId}/Images/{imageType}` (`maxWidth`, `fillWidth`, `tag`) | Primary, Backdrop, Thumb, Logo etc. |
| Original file | `GET /Items/{itemId}/Download` or `GET /Items/{itemId}/File` | Official app uses `/Download` [V-src] |
| Original file via stream | `GET /Videos/{itemId}/stream?static=true&mediaSourceId=…` | Findroid uses this [V-src] |
| Transcoded download | `GET /Videos/{itemId}/stream` with `container`, `videoCodec`, `audioCodec`, `videoBitRate`, `maxWidth`, `audioStreamIndex`, `subtitleStreamIndex` | Progressive transcode to one file; size unknown up front (Streamyfin estimates it) |
| Negotiate what will play | `POST /Items/{itemId}/PlaybackInfo` (device profile in body; `maxStreamingBitrate`, `mediaSourceId`, `audioStreamIndex`, `subtitleStreamIndex`) | Use at download time to decide direct vs transcode — avoids "downloaded but unplayable" |
| External subtitles | `GET /Videos/{routeItemId}/{routeMediaSourceId}/Subtitles/{routeIndex}/Stream.{routeFormat}` | |
| Trickplay | `GET /Videos/{itemId}/Trickplay/{width}/{index}.jpg`, `…/tiles.m3u8` | |
| Intro/credit segments | `GET /MediaSegments/{itemId}` | |
| Playback reporting | `POST /Sessions/Playing`, `POST /Sessions/Playing/Progress`, `POST /Sessions/Playing/Stopped` | For live sessions |
| Watched state after offline | `POST /UserPlayedItems/{itemId}` (`datePlayed`), `DELETE /UserPlayedItems/{itemId}` | `datePlayed` lets you back-date |
| Resume position after offline | `POST /UserItems/{itemId}/UserData` | Set `PlaybackPositionTicks`, `Played`, `LastPlayedDate` directly — better for deferred sync than replaying session events |

- **Auth caution** [2nd]: a Streamyfin collaborator states Jellyfin 12 breaks direct downloads unless legacy auth is enabled on the server (`EnableLegacyAuthorization`), fixed in their beta via PR #1801. Use the `Authorization: MediaBrowser …` header, not legacy query-string keys. https://github.com/streamyfin/streamyfin/issues/1548
- **No bulk "what changed while I was offline" endpoint**: Fider "Add ability to receive bulk playback data" (#2267, 7 votes) and "Add a Differential API" (#2623, 6 votes) are both open. [V-issue] Conflict resolution must be done client-side.

---

## 5. Not verified

- **Reddit (r/jellyfin, r/selfhosted):** the search tool cannot reach reddit.com. **No Reddit evidence is included.**
- **Play Store reviews:** page content did not load (JavaScript-rendered). **No Play Store review themes, ratings or version info included.**
- **features.jellyfin.org comment text:** vote counts, status and dates come from the Fider API; I did not read the comment threads.
- **Whether the "Download all only downloads last episode" bug still exists in v2.7.x.** Issue #1937 was closed "not planned" on 26/10/2026; I did not read the closing comment or test the app.
- **Whether jellyfin-android #1289 (custom folder) is really resolved** — v2.7.0 added a folder picker (PR #2015), but the issue is still open.
- **Whether Findroid #525 / #211 (no thumbnails/covers offline) are resolved** — v1.0.0 added local image saving, but both issues remain open.
- **Whether Findroid's Downloads screen UI groups by show > season.** The database and DAO queries support it and #1145 implies shows are listed as shows; I did not read `DownloadsScreen.kt`.
- **Findroid issue bodies for #525, #210, #990, #1329, #1228 (API read)** — GitHub API rate limit hit; titles and counts come from the search API, #1228 from the web page.
- **Streamyfin fix for offline progress sync (PR #2439, merged 11/11/2026)** — assumed unreleased because latest release is v0.55.2 (16/10/2026); not checked against a newer tag.
- **Fladder:** whether PR #847 (transcoded downloads) was merged or just closed; what PR #1030 changed; subtitle/watched-state behaviour beyond README claims.
- **Jellyfin Android pre-2.7 destination folder** — source at v2.6.3 confirms system `DownloadManager`; the exact destination call was not read (forum posts say the public filesystem).
- **Finamp, Symfonium, Yatse, Moonfin, JellyBook, JellyPlay, Plezy:** forum/search mentions only; no source or issue-tracker review. Finamp's README fetch returned nothing.
- **Vendor blog (jellywatch.app)** says official-app downloads are "experimental" and "less reliable than Findroid's" — a competitor's opinion, not used as evidence.
- **Forum quote "only downloads 2 random episodes" (March 2025)** — seen in a search summary; the post itself was not opened.
- **Year of release dates on GitHub release pages** — taken from the GitHub API for the latest four releases per repo; older release-note dates (e.g. Streamyfin v0.47.1, v0.30.2) show day/month only on the page.
