# Milestone 11 security check (S13)

**Date:** 22/11/2026 · **Branch:** `milestone-11` · **Scope:** SPEC §6, S1–S13 as they apply to Milestone 11 (roadmap
`docs/plans/2026-11-20-roadmap-replace-jellyfin.md`, plan `docs/plans/2026-11-21-milestone-11-playlists-browsing.md`):
playlists, collections, cast and extras, trailers, versions, person pages, filtered browsing, genre and studio pages, search extras,
subtitles from the internet (through the server), the play queue, and photo and home-video libraries. Threat model as SPEC §6, with
the roadmap's decision D7: online subtitles go through the user's server, so the phone still talks only to the user's servers.

This check was made by reading the code and the tests. A whole-branch review and a fix-wave re-review ran in the build
(findings and fixes in section 5). Results of the gate are in section 4.

---

## 1. Summary verdict

**No finding is open at Critical or Important.** Known limits are listed in sections 2 and 3. The device-only checks (section 6)
need a real server and are Ben's to run before relying on the release.

| Area | Verdict |
|---|---|
| Who may act | **Strong.** The server decides every change (playlist owner, `CollectionManagement`, `SubtitleManagement`, each item's own `CanDelete`). A refusal is a sentence on screen and **never signs the profile out** |
| Changes sent once | **Strong.** Every change is `retry = false` with a one-shot body; a refused 401 is checked with `GET /Users/Me` (`verifyUnauthorised`) first |
| Third-party hosts (S7) | **Strong.** Subtitle search and download run on the server. An online trailer is only handed to Android with `ACTION_VIEW`; the app never fetches it. Person and photo pictures come from the user's server through the artwork path |
| Untrusted server data (S11) | **Good.** Every list is capped; every id is checked before it becomes a path segment or a query value; trailer and subtitle text is cleaned |
| Profile separation | **Good.** Play queues and the collection index are in memory, keyed by profile, and dropped or never shared when the profile changes |
| Manifest, permissions, dependencies, schema | **Unchanged.** No new permission, component, `<queries>`, dependency or Room schema change |

---

## 2. S1–S13 (as they apply to Milestone 11)

| ID | Requirement (short) | Status | Evidence | Proved by |
|---|---|---|---|---|
| **S1** | Secrets encrypted at rest | **Met (unchanged)** | Nothing new is stored on disk. Queues, the collection index and subtitle results live in memory only; typed names live in `TextFieldState`s cleared on a profile change | `PlayQueueTest`, `ListsGatewayTest`, `ListsViewModelsTest` |
| **S2** | No backup | **Met (unchanged)** | No new storage | `verifyReleaseManifest` |
| **S3** | HTTPS by default | **Met** | Every call goes through `ServerApi` (the account's own client, `CleartextGuard`) | `ListsGatewayTest`, `DiscoverGatewayTest`, `SubtitlesGatewayTest` (real `ServerApi`) |
| **S4** | Pins | **Met (unchanged)** | Same client path | as S3 |
| **S5** | Client certificates | **Met (unchanged)** | Same client path | Milestone 1 tests |
| **S6** | Token only in `Authorization`, only to its server | **Met** | `ServerApi` only. The trailer intent carries an address and nothing else (no token, no header, no extras). Subtitle ids and names are query or path values, never logged (`AppLog` by kind only) | `TrailerIntentTest`, `ServerApiVerifyUnauthorisedTest`, the gateway tests |
| **S7** | No third-party hosts | **Met** | No new OkHttp client, URL or `HttpUrl` outside `ServerApi`. Online subtitle results are text only (no address shown or kept). The one third-party address kept is an online trailer's: printable http or https, at most 2,048 characters, with a host; it is shown by host name and opened by another app on the user's tap. Coil still has no network module | `AdminBoundaryTest` (no screen builds an intent), `ArtworkBoundaryTest`, `DiscoverGatewayTest` (non-http, over-long and host-less trailers), `TrailerIntentTest` |
| **S8** | App lock, recents | **Met** | New screens draw through the existing secure scaffold and read the lock cover. A lock pops the whole stack, so every new ViewModel is cleared. The photo viewer is full screen and still secure | `CoveredWindowsTest`, `ui.lists.*` and `ui.photos.*` render tests |
| **S9** | Minimum permissions | **Met (unchanged)** | Nothing needed | `verifyReleaseManifest` |
| **S10** | Exported components | **Met (unchanged)** | No component added. The trailer intent is an implicit `ACTION_VIEW` with `CATEGORY_BROWSABLE`, no extras and no granted permission; it is built in `data/discover/TrailerIntent.kt`, outside `ui/` | `TrailerIntentTest`, `AdminBoundaryTest`, `verifyReleaseManifest` |
| **S11** | Responses untrusted | **Met** | Element-tolerant lists with caps (playlist entries 2,000; cast 50; similar 12; collections in the index 100, children 500, own adds 1,000; search extras 10 each; ids per call 500). Names are cleaned and bounded (1–200). Playlist entry ids `[A-Za-z0-9-]{1,64}`; subtitle ids `[A-Za-z0-9._~=+-]`, 1–128, no `..`, not all dots; language `[a-z]{3}`; versions only with a 32-hex id (`MediaPaths.isSourceId`); queue tokens are 22 random url-safe characters; a filter is one value with no `,` or `|`. A hostile answer costs only its own part | `ListsGatewayTest`, `DiscoverGatewayTest` (`onlyVersionsThePlayerCanHonourAreOffered`), `SubtitlesGatewayTest`, `PlayQueueTest` |
| **S12** | Pinned dependencies | **Met (unchanged)** | No dependency change; `gradle/verification-metadata.xml` unchanged | `git diff main -- gradle/` empty |
| **S13** | Check each milestone | **Met** | This document | — |

**Who may act, in detail.**

- **Playlists:** the server's own rules (owner and permissions). A refused change (403, or a 401 the server confirms is not the sign-in
  ending) says "The server didn't let this profile change that." and the profile stays signed in. **Delete** asks first and names the
  playlist, reads the playlist's own `CanDelete` for this user, then `DELETE /Items/{id}` with `verifyUnauthorised`.
- **Collections:** `CollectionManagement` decides. Refusal gives its own sentence and keeps the sign-in.
- **Subtitles:** `SubtitleManagement` decides, for both the search and the download. 403 and a checked 401 say "This profile isn't
  allowed to fetch subtitles." and keep the sign-in.
- **Read-only parts** (extras, person, browse, genres, studios, search extras, photos) change nothing.

---

## 2a. Each new endpoint and its refusal handling

| Endpoint | Used for | On a refusal or failure |
|---|---|---|
| `GET /Items` (Playlist, BoxSet, `personIds`, filtered browse, `nameStartsWith`) | Lists, person works, browse | Offline: "You're offline" with Try again. Other failures: "That didn't load" with Try again |
| `GET /Items/{id}`, `GET /Playlists/{id}/Items` | One playlist, its entries (200 a page, at most 2,000) | As above |
| `POST /Playlists`, `POST /Playlists/{id}`, `POST`/`DELETE /Playlists/{id}/Items`, `POST …/Move/{n}` | Create, rename, add, remove, move | 403 or a confirmed 401: the refusal sentence, still signed in. Sent once, never retried |
| `DELETE /Items/{id}` | Delete a playlist | `CanDelete` false: not offered or sent. A refused 401 is checked with `GET /Users/Me`; the sign-in stays |
| `POST /Collections`, `POST`/`DELETE /Collections/{id}/Items` | Create, add, remove | Refusal sentence (CollectionManagement), still signed in. Sent once |
| `GET /Items?parentId=` per collection | Collection index ("Part of this collection") | Any failed read fails the whole index; the line simply does not appear. Never part-filled |
| `GET /Items/{id}/LocalTrailers`, `/SpecialFeatures`, `/Similar` | Extras | Each part fails alone and hides; all failing shows the first error (offline says so) |
| `GET /Items/Filters`, `/Genres`, `/Studios`, `/Persons` | Filter options, names, search extras | As the read rule above; a part failing alone hides that part |
| `GET /Items/{id}/RemoteSearch/Subtitles/{language}` | Find subtitles online | 403 or a confirmed 401: "This profile isn't allowed to fetch subtitles." Still signed in |
| `POST /Items/{id}/RemoteSearch/Subtitles/{subtitleId}` | Download one | As above. Sent once |
| `GET /Items/{id}`, `GET /Users/Me` | Subtitle count (arrival check), preferred language | A failed read counts as "not yet"; the language falls back to the phone's, then English |
| `GET /Items/{id}/Images/Primary` (`maxWidth`/`maxHeight` 720, 1280 or 1920, `format=Webp`) | Photo viewer | Through the artwork path, as every picture; a failure shows the placeholder |
| `ACTION_VIEW` of an online trailer | Opens the browser or a video app | No app to open it: a snackbar says so. Never fetched by Shoalwatch |

---

## 3. Residual risks and limits

**Unproven until a real server (see section 6):**

- Online subtitles with a real provider: the 3-letter language codes (fre, ger, chi…) and real provider ids fitting the accepted set
  (base64-shaped ids are allowed; anything else is dropped, so a provider that uses another character would find nothing).
- A playlist change by a non-owner and a delete refused with 401: that the sign-in stays.
- `CanDelete` on a playlist for an owner and a non-owner.
- First film page on a server with many collections (the index is read four at a time; extras now show before it finishes).
- A film with two files: that the chosen version plays, and that real source ids are 32 hex.

**Accepted limits (the final review's CAN WAIT items):**

- A failed resume refresh can blank an open playlist (shows "You're offline" until Try again) rather than keeping the last list (N1).
- Home hides the libraries' freshness banner whenever there are no libraries to show, even if the cached rows show (N2).
- Photos are ordered by name, not by the grid's sort; a tapped photo past the 500th is prepended ("1 of 501", wrong neighbours) and a
  failed read of it fails the viewer until Try again; zoom is centred, not at the pinch point; Close pads for the status bar only
  (becomes a must-fix if device check 7 shows overlap in landscape).
- The selection bar offers Download and Add to playlist or collection for every kind (a photo library gets "could not be started").
- Read failures other than offline all show "That didn't load" (a changed pin or missing certificate has no specific guidance on a
  new screen first).
- The "offline kinds" test is copied four times; id handling differs slightly between gateways (the subtitle gateway repairs a dashed
  or upper-case id, lists and discover refuse one). Harmless.
- The collection index slots are never pruned for removed profiles (memory only; at most profiles × 100 collections × 1,000 ids). Index
  waiters after a forget re-read one after another. An add that times out after the server applied it is not recorded (stale for at
  most 10 minutes).
- A Play tapped while a paused subtitle reload resolves can be overridden (tap Play again).
- The playback controller is at 399 of 400 lines and `AppGraph` at 397; the next change needs a new parts file.
- Studios are unsorted; genres sort by sort name.
- An online trailer is opened by whichever app the user's phone chooses for a web address; Shoalwatch cannot vouch for it. The user
  taps it and sees the host first.

---

## 4. Results (22/11/2026)

- **Full unit suite after the fix wave:** `:app:testDebugUnitTest --continue`, **BUILD SUCCESSFUL, 5,472 tests in 485 classes,
  0 failures, 0 errors, 1 skipped.**
- `:app:compileDebugAndroidTestKotlin` green.
- **Guard tests** (`ManageBoundaryTest`, `AdminBoundaryTest`, `ArtworkBoundaryTest`, `SourceShapeTest`) pass; the only guard narrowed is
  the management-endpoint scan, for exactly `RemoteSearch/Subtitles/` in `SubtitlesGateway.kt` (new cases prove the exception stays that
  narrow).
- `bash tools/check_public.sh`: **PUBLIC CHECK: PASS** (re-run at release, see the Task 12 report).
- **Not run:** `assembleRelease` with `verifyReleaseManifest`, `lintDebug`, `connectedDebugAndroidTest` and the emulator walkthrough;
  the controller runs the release gate before the tag.

## 5. Review in the build

- **Whole-branch review** (01d1c39..454e52d): no Critical; one Important (I1, the extras waited for the whole collection index).
- **Fix wave** (ba43c9b, ae63e11), re-reviewed: I1 fixed (extras show first; index read four at a time); play-queue wording corrected
  (a lock does not clear queues, sign-out and a profile switch do); a playlist re-reads on return; Home with no libraries shows only the
  Playlists card; the download line gives the true reason; a double tap in Search opens one page; Clear filters on a genre from Search
  goes back; only 32-hex versions are offered; **the trailer intent gained `CATEGORY_BROWSABLE` and moved out of the screens**; the
  photo viewer draws through the artwork code. Verdict: ready to merge, subject to the release gate.
- No separate security-team (multi-agent) review was run for this milestone.

---

## 6. Device checks for Ben (real Jellyfin server over https)

1. **Playlists, as owner:**
   - Create, rename, add from the bar and from the detail page.
   - Move up and down, remove, and delete (it asks first and names the playlist).
   - Play, then Shuffle. Up next, Next and Previous follow the queue; check the end of the queue and Download all.
2. **Playlists, as a non-owner or restricted profile:**
   - Change another user's playlist. Expect "The server didn't let this profile change that."
   - **Still signed in** afterwards. Delete hidden when `CanDelete` is false.
3. **Collections, as a user without CollectionManagement:**
   - Create or add. Expect the refusal sentence, still signed in.
   - With the permission: "Part of this collection" updates at once after an add.
4. **Subtitles online:**
   - **Without SubtitleManagement:** "This profile isn't allowed to fetch subtitles." and still signed in.
   - **With it, and a real provider:** results appear for the 3-letter codes (fre, ger, chi…); check **no result is dropped** (the ids
     fit `[A-Za-z0-9._~=+-]{1,128}`).
   - After a download the player reloads at the same position (paused stays paused) and the new track is pickable. On the detail
     page, "Subtitles added."
5. **First film page on a server with many collections:** the cast and the Version picker should appear at once; time it.
6. **Versions:** a film with two files plays the chosen one. Real source ids are 32 hex.
7. **Trailers:**
   - A remote trailer button names the host and opens another app or the browser. With no browser, the snackbar shows.
   - On a tablet in split screen, Play still works after opening a trailer.
   - A local trailer plays in the app.
8. **Photos:**
   - A photos library and the viewer: sharpness at 720, 1280 and **1920 px**.
   - Pinch 1x–4x, double tap to 2x, swipe pages, a drag pans when zoomed.
   - **Landscape on a notch phone: Close is not under the cutout** (if it is, upgrade that limit to a fix).
   - An album of over 500 photos. TalkBack zoom action.
9. **Home videos:** the library shows and a video plays in the normal player.
10. **A genre from Search (a parental-controls profile):** only permitted libraries are listed. Clear filters goes back to Search.
11. **Profile switch with the playlist player open:** back to Home, and the other profile cannot replay the queue. Also lock, then
    unlock as the same profile.
12. **Airplane mode:** every new screen (Playlists, playlist, Browse, Genres, Studios, Person, Photo, the picker, the extras block and
    the subtitle sheet) shows "You're offline" with Try again. Cached Home and library grids are unchanged. Search shows saved titles only.
13. **Font size 2.0 and TalkBack:** playlist rows (three 48dp buttons beside the title), the letter bar, the filter chips, and the
    Search people, genre and collection rows.
