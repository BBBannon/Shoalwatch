# Milestone 2 — Native Browsing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Signed in, the user browses their Jellyfin library natively: a home screen (Continue Watching, Next Up, latest additions, libraries), smooth-scrolling library grids, series → season → episode, film detail, search, and tidy-up actions (remove from Continue Watching, mark watched/unwatched, favourite). Everything already seen is still browsable with no connection.

**Architecture:** The Room database is the single source of truth (SPEC §4.1). Screens observe Room flows only; a repository refreshes Room from the server in the background ("show cache at once, then refresh"). There is no separate offline code path. Every authenticated request goes through one new call path, `ServerApi`, which obtains a verified address from `AddressSelector.awaitOnline`, uses the per-account authenticated client from `ServerClients`, and maps every failure to `AppError`. Artwork is loaded by an opaque `ArtworkRef`, never a URL.

**Tech Stack:** as Milestone 1, plus Coil 3 (image loading, already version-checked in AAFin: `coil = 3.3.0`, `coil-compose`, `coil-network-okhttp`). No Paging library (a small hand-rolled pager over Room is simpler and keeps Room as the source of truth).

## Global Constraints

- **Read first:** `docs/SPEC.md` (§3 rows 1–3, §3.3, §4, §6), `docs/ARCHITECTURE.md` (all of it — layer rules, credentials, token rules, UI conventions), `docs/security/milestone-1-check.md`.
- **API reference:** `docs/reference/jellyfin-openapi-12.4.0.json` (the official Jellyfin 12.4.0 OpenAPI document). Every endpoint, parameter and field used MUST be checked against it; do not rely on memory of the Jellyfin API. Decode with `ignoreUnknownKeys`; treat every field as optional unless the reference marks it required.
- Package root `app.shoalwatch`; `minSdk 29`, `compileSdk`/`targetSdk 35`. Build with `JAVA_HOME=C:\Users\Ben\Android\jdk17\jdk-17.0.20.1+1`. Verify with `./gradlew.bat clean testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease` (the release build runs `verifyReleaseManifest`). Gradle dependency verification is ON: after any dependency change run `./gradlew.bat --write-verification-metadata sha256 help testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease` and commit the metadata; record new versions under "Version checks" in ARCHITECTURE.md (must suit Kotlin 2.2.21, AGP 8.10.1, compileSdk 35).
- **Security rules that bind every task:** only `HttpClientFactory` builds OkHttp clients (via `ServerClients`); no client follows redirects; a token goes only to an address that `AddressSelector.awaitOnline(serverId)` has verified, and only in the `Authorization` header; never log, print or put in an exception message a token, header value, URL or hostname; the UI layer cannot reach tokens, URLs or HTTP types; server responses are untrusted (bounded sizes, ids validated with `JellyfinId`, display strings through `displayText`, HTML in overviews rendered as plain text); no new permissions; no third-party hosts; the merged release manifest check must keep passing.
- **Data rules:** every cached row is scoped by `serverId` (and by `userId` where it is per-user: user data, home rows, library visibility). Design for SPEC §3.3 now: item metadata is per server; watched state, resume position and favourites are per (server, user). Room migrations are explicit and tested; no destructive fallback; schema exported.
- TDD with behavioural RED. Real OkHttp + MockWebServer for network code; in-memory Room for storage; virtual time for coroutines; fakes at the seams for ViewModels. No file over ~400 lines. UK English, plain wording, all user-facing text in `strings.xml`. Typography and layout conventions from ARCHITECTURE.md "UI" (dyslexia-friendly: nothing under 13sp, left-aligned paragraphs).
- Commits: conventional, each ending with `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>`. ChangeLog line per user-visible change (`vX.Y — DD/MM/YYYY — plain English`). Keep ARCHITECTURE.md accurate. Never edit `docs/SPEC.md`. **Never start an emulator, run connected tests or touch a device** — the controller does that.

---

### Task 1: The authenticated call path (`ServerApi`) and a stand-in server tool

**Files:** `data/api/ServerApi.kt`, `data/api/ApiRequest.kt`, `data/api/JsonBodies.kt` (bounded read + decode, extracted from `JellyfinGatewayImpl` so both share it), tests `data/api/ServerApiTest.kt`; `tools/mockjelly/mock_jellyfin.py` + `tools/mockjelly/README.md`.

**Produces:**
```kotlin
/** Who a request is made as. Ids are normalised JellyfinId strings. */
data class Session(val serverId: String, val userId: String)

class ServerApi(/* ServerStore, AddressSelector, ServerClients, DeviceIdentity, io dispatcher */) {
    /** GET returning decoded JSON. `path` starts with '/', has no host; `query` values are encoded by the builder. */
    suspend fun <T> get(session: Session, path: String, query: List<Pair<String, String>> = emptyList(),
                        deserializer: DeserializationStrategy<T>, maxBytes: Long = 4L * 1024 * 1024): T
    suspend fun post(session: Session, path: String, query: List<Pair<String, String>> = emptyList(), jsonBody: String? = null)
    suspend fun delete(session: Session, path: String, query: List<Pair<String, String>> = emptyList())
    /** For artwork (Task 6): streams bytes of an authenticated GET, bounded. */
    suspend fun <T> getBytes(session: Session, path: String, query: List<Pair<String, String>>, maxBytes: Long, read: (okio.BufferedSource, contentType: String?) -> T): T
}
```
- Flow of every call: `store.awaitWarmed()` → read the server (missing → `SERVER_NOT_FOUND`) → `selector.awaitOnline(serverId)` (null → `AppError(NETWORK, "You're offline…")`, a distinct kind `OFFLINE` is added to `AppError.Kind`) → authenticated client for (server, user) → build URL from the verified address + path + query (path segments validated: ids must already be `JellyfinId`-normalised; reject anything containing `..`, `//`, `?`, `#`) → execute off the main thread → 401 ⇒ `UNAUTHORISED` ("Your sign-in has expired. Sign in again."), 403 ⇒ `FORBIDDEN`, 404 ⇒ `SERVER_NOT_FOUND`, 3xx ⇒ `SERVER_NOT_FOUND`, 5xx ⇒ `SERVER_ERROR`; IO failure ⇒ `selector.refresh()` then **one** retry through a freshly verified address; second failure ⇒ mapped `AppError`.
- Bounded bodies: default 4 MiB for JSON lists; decode failure ⇒ `SERVER_ERROR` with a fixed message.
- `ServerApi` is the ONLY new place that puts a token on the wire in this milestone; `AuthRepository` remains the only one that sends passwords and performs sign-out.
- `tools/mockjelly/mock_jellyfin.py`: standard-library-only Python stand-in for Jellyfin, for emulator walkthroughs (`python -I mock_jellyfin.py 8096`, reachable from the emulator at `10.0.2.2:8096`). Serves the sign-in endpoints already used plus everything Tasks 2–6 call, from a small in-memory catalogue (2 libraries: Films ×40, TV ×3 series × 2 seasons × 6 episodes; resume and next-up data; images generated on the fly as solid-colour PNGs with the title drawn is NOT possible without PIL — generate plain coloured PNGs with `zlib`/`struct`). Requires `Authorization … Token="…"` on authenticated routes (401 otherwise), logs method + path + whether a token was present, never the token. Mutations (played, favourite, user data) change its in-memory state. README says it is a test stand-in, not a Jellyfin implementation.
- [ ] Tests first (MockWebServer): token sent only after `awaitOnline`; offline ⇒ `OFFLINE`, zero requests; 401/403/404/3xx/5xx mapping; IO failure ⇒ refresh + exactly one retry; body over the limit refused; malformed JSON ⇒ `SERVER_ERROR`; path validation rejects traversal; no token for a signed-out account (`NOT_SIGNED_IN`).
- [ ] Implement, extract the shared body reader, wire into `AppGraph`, pass, commit. Mock server: run it and `curl` each route once (record the commands in the README).

### Task 2: Library endpoints (`LibraryGateway`)

**Files:** `data/library/api/Dtos.kt` (kotlinx.serialization DTOs: only the fields used), `data/library/api/LibraryGateway.kt` (+Impl), `data/library/Models.kt`, `data/library/api/Mappers.kt`; tests `LibraryGatewayTest.kt`, fixtures under `app/src/test/resources/jellyfin/`.

**Produces (all ids `JellyfinId`-validated, strings `displayText`-sanitised at this boundary):**
```kotlin
enum class ItemKind { MOVIE, SERIES, SEASON, EPISODE, BOX_SET, FOLDER, OTHER }
data class LibraryView(val id: String, val name: String, val kind: LibraryKind) // MOVIES, TV, BOX_SETS, MIXED; others skipped
data class Item(val id: String, val kind: ItemKind, val name: String, val sortName: String?, val parentId: String?,
                val seriesId: String?, val seriesName: String?, val seasonId: String?, val indexNumber: Int?, val parentIndexNumber: Int?,
                val overview: String?, val runtimeMs: Long?, val year: Int?, val premiereDate: Instant?, val communityRating: Float?,
                val officialRating: String?, val childCount: Int?, val images: ItemImages)
data class ItemImages(val primaryTag: String?, val backdropTag: String?, val thumbTag: String?, val seriesPrimaryTag: String?)
data class UserData(val played: Boolean, val positionMs: Long, val playedPercentage: Float?, val playCount: Int, val favourite: Boolean, val lastPlayed: Instant?, val unplayedItemCount: Int?)
data class ItemWithUserData(val item: Item, val userData: UserData?)
data class Page<T>(val items: List<T>, val startIndex: Int, val total: Int)
interface LibraryGateway {
    suspend fun views(s: Session): List<LibraryView>                                   // GET /UserViews
    suspend fun children(s: Session, parentId: String, start: Int, limit: Int, sort: Sort, filter: ItemFilter): Page<ItemWithUserData>  // GET /Items
    suspend fun item(s: Session, itemId: String): ItemWithUserData                      // GET /Items/{itemId}
    suspend fun seasons(s: Session, seriesId: String): List<ItemWithUserData>           // GET /Shows/{seriesId}/Seasons
    suspend fun episodes(s: Session, seriesId: String, seasonId: String?): List<ItemWithUserData> // GET /Shows/{seriesId}/Episodes
    suspend fun resume(s: Session, limit: Int): List<ItemWithUserData>                  // GET /UserItems/Resume
    suspend fun nextUp(s: Session, limit: Int): List<ItemWithUserData>                  // GET /Shows/NextUp
    suspend fun latest(s: Session, viewId: String, limit: Int): List<ItemWithUserData>  // GET /Items/Latest
    suspend fun search(s: Session, term: String, limit: Int): List<ItemWithUserData>    // GET /Items?searchTerm=&recursive=true&includeItemTypes=Movie,Series,Episode
    suspend fun setPlayed(s: Session, itemId: String, played: Boolean)                  // POST / DELETE /UserPlayedItems/{itemId}
    suspend fun setFavourite(s: Session, itemId: String, favourite: Boolean)            // POST / DELETE /UserFavoriteItems/{itemId}
    suspend fun clearResume(s: Session, itemId: String)                                 // POST /UserItems/{itemId}/UserData  {"PlaybackPositionTicks":0}
}
```
- Request `fields` sparingly (Overview, ChildCount, PrimaryImageAspectRatio only where shown); `enableUserData=true`; `enableImageTypes=Primary,Backdrop,Thumb`; page limit ≤ 200. Exact parameter names from the OpenAPI reference.
- Fixtures: realistic JSON written from the reference schemas (`BaseItemDto`, `BaseItemDtoQueryResult`, `UserItemDataDto`), including unknown extra fields, nulls, a non-GUID id (must be dropped, not crash), an over-long name, bidi characters, HTML in an overview (kept as text; rendering is Task 7).
- [ ] Tests first: each endpoint's method, path, query and mapping; hostile cases above; an item of an unknown type maps to `OTHER`; ticks → ms; empty lists.
- [ ] Implement on `ServerApi`, pass, commit.

### Task 3: Library cache schema (Room v2)

**Files:** `data/db/library/*.kt` (entities, DAOs), `AppDatabase` v2 + `Migration1To2`, `app/schemas/…/2.json`; tests `LibraryDaoTest.kt`, `MigrationTest.kt` (Robolectric with `MigrationTestHelper` or a hand-rolled open-v1-then-migrate test using the exported v1 schema).

**Tables (all with `serverId`; composite primary keys; foreign key to `servers` ON DELETE CASCADE):**
- `items(serverId, itemId, kind, name, sortName, parentId, seriesId, seriesName, seasonId, indexNumber, parentIndexNumber, overview, runtimeMs, year, premiereDate, communityRating, officialRating, childCount, primaryTag, backdropTag, thumbTag, seriesPrimaryTag, fetchedAt, pinned)` — indices on (serverId, parentId), (serverId, seriesId, seasonId, parentIndexNumber, indexNumber). `pinned` (default 0) is reserved for Milestone 4 downloads: pinned rows are never evicted.
- `user_data(serverId, userId, itemId, played, positionMs, playedPercentage, playCount, favourite, lastPlayed, unplayedItemCount, updatedAt)` — FK to `accounts` ON DELETE CASCADE.
- `views(serverId, userId, viewId, name, kind, position)`.
- `list_entries(serverId, userId, listKey, position, itemId)` — ordered lists: `resume`, `nextUp`, `latest:<viewId>`, `children:<parentId>:<sort>:<filter>`, `seasons:<seriesId>`, `episodes:<seriesId>:<seasonId|all>`, `search:<term>` is NOT cached.
- `list_state(serverId, userId, listKey, total, loadedCount, refreshedAt)`.
- DAO flows return joined `(item, userData)` rows for a list key in position order, with `LIMIT`; single item with user data; write methods replace a list page transactionally (delete entries from `start` onward when a refresh restarts at 0; upsert items and user data).
- Eviction query: delete unpinned `items` not referenced by any `list_entries` and older than a cutoff; cap total unpinned rows.
- [ ] Tests first: episode ordering by (season index, episode index) with nulls last; two users on one server see their own user data and lists while sharing item rows; removing an account cascades its user data/lists but leaves items; removing a server cascades everything; page replace keeps earlier positions; eviction never removes pinned or referenced rows; migration 1→2 keeps existing servers/accounts (tokens still decryptable ciphertext, byte-for-byte).
- [ ] Implement, pass, commit.

### Task 4: `LibraryRepository` — cache first, refresh behind

**Files:** `data/library/LibraryRepository.kt`, `data/library/ListKey.kt`, `data/library/Refresh.kt` (staleness + in-flight de-duplication), `data/library/LibrarySeams.kt` (interfaces the UI uses); tests `LibraryRepositoryTest.kt`.

**Produces:**
```kotlin
sealed interface Freshness { data object Fresh; data object Refreshing; data class Stale(val reason: AppError.Kind) }  // Stale = showing saved copy
data class Listing(val items: List<ItemWithUserData>, val total: Int?, val freshness: Freshness, val canLoadMore: Boolean)
interface Library {                                           // implemented by LibraryRepository; UI depends on this
    fun home(s: Session): Flow<Home>                          // views, resume, nextUp, latest per view; each with its own Freshness
    fun children(s: Session, parentId: String, sort: Sort, filter: ItemFilter): Flow<Listing>
    suspend fun loadMore(s: Session, parentId: String, sort: Sort, filter: ItemFilter)   // next page; no-op if one is in flight or all loaded
    fun series(s: Session, seriesId: String): Flow<SeriesDetail>                           // item + seasons + episodes of the selected season
    fun item(s: Session, itemId: String): Flow<ItemDetail?>
    suspend fun search(s: Session, term: String): List<ItemWithUserData>                   // online: server; offline: LIKE search over cached names
    suspend fun refresh(s: Session, target: RefreshTarget)                                 // pull-to-refresh
}
```
- Collecting a flow emits the cached rows immediately, then triggers a refresh if the list is older than its staleness window (home rows 2 min, listings 15 min, item detail 5 min) or was never loaded. Refresh failure with `OFFLINE`/`NETWORK`/`TIMEOUT` ⇒ `Freshness.Stale(kind)` and the cached rows stay; `UNAUTHORISED` is surfaced so the UI can prompt sign-in.
- One refresh per list key at a time (join the in-flight one). Page size 100. Refresh of page 0 replaces the list transactionally so removed server items disappear.
- Eviction runs at most once a day, off the main thread.
- [ ] Tests first (fake gateway, in-memory Room, virtual time): cache emitted before network completes; offline shows cache with `Stale`; never-loaded + offline ⇒ empty with `Stale`; concurrent collectors cause one request; `loadMore` appends in order and stops at total; refresh replaces removed items; user switch shows the other user's data without a refetch of shared item rows where cached; offline search finds cached titles case-insensitively.
- [ ] Implement, wire into `AppGraph`, pass, commit.

### Task 5: Tidy-up actions (`Playstate`)

**Files:** `data/library/PlaystateRepository.kt`, seam `Playstate` in `LibrarySeams.kt`; tests `PlaystateRepositoryTest.kt`.

**Produces:** `interface Playstate { suspend fun setPlayed(s, itemId, played); suspend fun setFavourite(s, itemId, favourite); suspend fun removeFromContinueWatching(s, itemId) }`
- Optimistic: update Room first (played flag, position 0, remove from the `resume` list; for a series/season mark, update `unplayedItemCount` and child episodes already cached), then call the server; on failure roll back exactly and throw the `AppError`. Marking an episode played refreshes `nextUp` and `resume` afterwards.
- Online-only in this milestone: when `OFFLINE`, roll back and throw `AppError(OFFLINE, "You're offline. Try again when you're connected.")`. (Milestone 5 replaces the direct call with the playstate outbox behind this same seam — keep the seam narrow.)
- [ ] Tests first: optimistic state visible before the server answers; rollback restores the exact previous rows; remove-from-continue-watching clears position and the resume entry; failure messages are `AppError.userMessage`.
- [ ] Implement, pass, commit.

### Task 6: Artwork

**Files:** `data/images/ArtworkRef.kt`, `data/images/ArtworkFetcher.kt` (Coil 3 `Fetcher` + `Keyer`), `data/images/ArtworkLoader.kt` (builds the single `ImageLoader`), `ui/components/Artwork.kt`; tests `ArtworkFetcherTest.kt`.

**Produces:** `data class ArtworkRef(val serverId: String, val userId: String, val itemId: String, val type: ArtworkType /* PRIMARY, BACKDROP, THUMB */, val tag: String)`; composable `Artwork(ref: ArtworkRef?, contentDescription: String?, modifier, aspect)`.
- The fetcher resolves the image through `ServerApi.getBytes` → `GET /Items/{itemId}/Images/{type}?tag=&maxWidth=&quality=90` (width bucketed to 160/320/480/720/1280 from the requested size). The UI never sees a URL. Bounded to 8 MiB; only `image/*` content types accepted; decode size capped (no decompression bombs: Coil size resolver + a hard pixel cap).
- Cache key = (serverId, itemId, type, tag, widthBucket) — NOT the address, so home/away switching reuses the cache. Disk cache in the app's cache dir (250 MB cap; evictable — pinned artwork for downloads is Milestone 4), memory cache at Coil defaults. Coil's own network fetcher is NOT installed (so Coil can never fetch an arbitrary URL).
- Placeholder: brand-tinted block with the title's initial; error state the same (no broken-image icon); crossfade short.
- [ ] Tests first: correct path/query; cache key independent of address; non-image content type and oversize body refused; offline ⇒ falls back to disk cache or placeholder without throwing to the UI.
- [ ] Add Coil at AAFin's version (`coil-compose` only, plus what the custom fetcher needs — not `coil-network-okhttp` unless required), regenerate verification metadata, implement, pass, commit.

### Task 7: Browsing screens

**Files:** `ui/shell/*` (bottom navigation: Home, Search, Downloads [placeholder: "Downloads arrive in a later update"]; top bar with account switch + Servers), `ui/home/*`, `ui/library/*` (grid), `ui/series/*`, `ui/detail/*` (film/episode detail), `ui/search/*`, `ui/components/*` (PosterCard, EpisodeRow, ItemActionsSheet, FreshnessBanner, EmptyState, SkeletonRow), ViewModels + tests, render tests; replace `HomePlaceholderScreen`.

**Behaviour:**
- **Home:** Continue Watching (wide cards with progress bar and "S2 E5 · title"), Next Up, Latest in each library, then libraries. Long-press or the card's ⋮ opens an actions sheet: Remove from Continue Watching, Mark watched/unwatched, Favourite, Go to series. Pull to refresh. Connection chip stays (compact, in the top bar).
- **Library grid:** adaptive poster grid, continuous scrolling (`loadMore` when within ~2 screens of the end), sort (Name, Date added, Release date, Rating) and filter (Unwatched, Favourites) chips; scroll position kept on return; watched tick and unwatched-count badge on cards.
- **Series:** backdrop header, overview (expandable, plain text — HTML tags stripped), season selector (chips or tabs; Specials last), episodes in order with number, title, runtime, watched tick, progress; "Mark season watched". **Film / episode detail:** artwork, title, year · runtime · rating, overview, watched state, a prominent Play button that is present but shows "Playback arrives in the next update" (the player is Milestone 3) — do not fake playback.
- **Search:** debounced (300 ms), results grouped Films / Series / Episodes; works offline over the cache with a note saying so.
- **States:** skeletons while first loading (no layout jump), friendly empty states, `FreshnessBanner` when showing a saved copy ("You're offline — showing what's saved on this phone"), inline retry on errors, sign-in prompt on `UNAUTHORISED`. Nobody signed in ⇒ existing signed-out state.
- **Account switching:** switching the active account re-scopes every screen to that (server, user) session.
- Conventions from ARCHITECTURE.md "UI": stateless composables + `…Actions`, one immutable `UiState` per ViewModel, logic in ViewModels tested with fakes of the `Library`/`Playstate` seams, strings in resources, accessibility (labels, 48dp targets, headings, nothing by colour alone, font scale 2.0), tablet layout (grid columns adapt; detail screens capped at a readable width), `@Preview`s for main states. No `FLAG_SECURE` on browsing screens.
- [ ] ViewModel tests first; render tests for every state at 1.0 and 2.0 font scale, phone and tablet widths; one compile-only instrumented smoke test that signs in against a MockWebServer-backed fake and lands on Home (the controller runs it).
- [ ] Implement, pass, commit.

### Task 8: Milestone close

- [ ] Controller: emulator run of all instrumented tests; walkthrough against `tools/mockjelly` (home, grid scrolling through 40 films, series → season → episodes, search, mark watched, remove from Continue Watching, airplane mode → cached browsing with the banner); screenshots checked by eye.
- [ ] Whole-milestone review (code + security against SPEC §6 and the threat model, focusing on the new authenticated path, cache scoping between users, and artwork fetching); fix wave; re-review.
- [ ] `docs/security/milestone-2-check.md`, ARCHITECTURE.md, ChangeLog, TODO.md (sync script), Obsidian note.
