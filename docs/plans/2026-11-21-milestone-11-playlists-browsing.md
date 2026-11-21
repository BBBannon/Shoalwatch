# Milestone 11 — Playlists, collections, richer browsing, subtitles from the internet

**Goal (the owner, 20/11/2026, roadmap Milestone 11):** make and play playlists, gather films into collections, see who is in a
film and what else they made, watch its trailer and extras, choose a version, filter a library by genre, year, rating, studio
or tag and jump by letter, find people, genres and collections in search, fetch subtitles from the internet through the server,
and browse photo and home-video libraries. Version **1.3.0 (build 5)**. Roadmap decision D7 binds it (online subtitles go through
the server, so the phone still talks only to the user's servers: S7).

**Architecture:** three new data packages on `ServerApi`, each a seam with internal DTOs, mappers and one gateway, as `data/manage`
is: `data/lists` (`Lists`: playlists and collections, and the "part of a collection" index), `data/discover` (`Discover`: detail
extras, person pages, filtered browsing, genre and studio pages, search extras) and `data/subtitles` (`RemoteSubtitles`). They are
**live only** (no Room table, no schema change): what they show needs the server, and offline they say so. The library grid's own
cached list is unchanged; a filtered or letter list is a separate live list. Playback gains a **queue** (`PlayQueue`, in memory)
so a playlist plays in order or shuffled through the existing Up next and next/previous, and a **version** (`mediaSourceId`) sent in
PlaybackInfo. `ItemKind` gains `PHOTO`, `VIDEO` and `PLAYLIST`, `LibraryKind` gains `PHOTOS` and `HOME_VIDEOS` (stored by name, so
no migration). New UI packages: `ui/lists` (playlists, the add-to picker), `ui/discover` (person, browse, genres and studios, the
detail extras block), `ui/photos` (the viewer) and the subtitle search sheet in `ui/player` and the detail page.
**No new dependency, no new permission, no manifest change, no Room schema change.**

## Endpoints (all checked against `docs/reference/jellyfin-openapi-12.4.0.json`)

| Seam call | Method and path | Sent | Security in the reference |
|---|---|---|---|
| `Lists.playlists` | GET `/Items` | `userId`, `includeItemTypes=Playlist`, `recursive=true`, `sortBy=SortName`, `fields=ChildCount`, images | Default |
| `Lists.playlist` | GET `/Items/{id}` then GET `/Playlists/{id}/Items` | `userId`, `startIndex`, `limit=200` (paged, at most 2,000) | Default |
| `Lists.createPlaylist` | POST `/Playlists` | JSON `{"Name","Ids","UserId","IsPublic":false}` | Default |
| `Lists.renamePlaylist` | POST `/Playlists/{id}` | JSON `{"Name"}` | Default (refused: 403) |
| `Lists.addToPlaylist` | POST `/Playlists/{id}/Items` | `ids` (comma), `userId` | Default (refused: 403) |
| `Lists.removeFromPlaylist` | DELETE `/Playlists/{id}/Items` | `entryIds` (comma) | Default (refused: 403) |
| `Lists.moveInPlaylist` | POST `/Playlists/{id}/Items/{entryId}/Move/{newIndex}` | nothing | Default (refused: 403) |
| `Lists.deletePlaylist` | GET `/Items/{id}?userId=` (`CanDelete`), then DELETE `/Items/{id}` | nothing | Default; refused: **401** |
| `Lists.collections` | GET `/Items` | `includeItemTypes=BoxSet`, `recursive=true`, `sortBy=SortName` | Default |
| `Lists.createCollection` | POST `/Collections` | `name`, `ids` (comma), `isLocked=false` | CollectionManagement |
| `Lists.addToCollection` / `removeFromCollection` | POST / DELETE `/Collections/{id}/Items` | `ids` (comma) | CollectionManagement |
| `Lists.collectionsOf` | GET `/Items?parentId={collection}` for each collection (index, below) | `fields` none, no images | Default |
| `Discover.extras` | GET `/Items/{id}?userId=`, `/Items/{id}/LocalTrailers`, `/Items/{id}/SpecialFeatures`, `/Items/{id}/Similar` | `userId`; Similar `limit=12` | Default |
| `Discover.person` / `personWorks` | GET `/Items/{personId}?userId=`; GET `/Items?personIds=` | `recursive=true`, `includeItemTypes=Movie,Series`, paged | Default |
| `Discover.browse` | GET `/Items` | `parentId`, `genreIds`, `studioIds`, `years`, `officialRatings`, `tags` (one value each), `nameStartsWith` or `nameLessThan=A`, sort, paged | Default |
| `Discover.filterOptions` | GET `/Items/Filters`, `/Genres`, `/Studios` | `parentId`, `userId` | Default |
| `Discover.genres` / `studios` | GET `/Genres`, `/Studios` | `parentId` (optional), paged | Default |
| `Discover.searchMore` | GET `/Persons`, `/Genres`, `/Items?includeItemTypes=BoxSet` | `searchTerm`, `limit=10` | Default |
| `RemoteSubtitles.search` | GET `/Items/{id}/RemoteSearch/Subtitles/{language}` | language `[a-z]{3}` | SubtitleManagement |
| `RemoteSubtitles.download` | POST `/Items/{id}/RemoteSearch/Subtitles/{subtitleId}` | nothing | SubtitleManagement |
| `RemoteSubtitles.subtitleCount` | GET `/Items/{id}?userId=` (`MediaStreams` of type Subtitle) | nothing | Default |
| `RemoteSubtitles.preferredLanguage` | GET `/Users/Me` (`Configuration.SubtitleLanguagePreference`) | nothing | Default |
| Photo picture | GET `/Items/{id}/Images/Primary` | `tag`, `maxWidth` and `maxHeight` (720, 1280 or 1920), `quality=90`, `format=Webp` | anonymous on Jellyfin; sent through `ServerApi` |

## Decisions

1. **Live, not cached.** Playlists, collections, the detail extras, person pages, filtered lists, genre and studio pages and the
   search extras are read from the server each time a screen opens (a ViewModel holds them while it lives). Offline they say
   "You're offline" with Try again. What stays offline: everything Milestones 2 to 5 cached, plus downloads of playlist items.
2. **Playlists** are listed with `GET /Items?includeItemTypes=Playlist&recursive=true` and reached from a **Playlists** card at the
   end of Home's libraries. A new playlist is private (`IsPublic: false`) and owned by the profile. **Who may change one is the
   server's answer**: a refusal (403, or a 401 the server confirms is not the sign-in ending) says "The server didn't let this
   profile change that." and never signs out.
3. **Playlist entries** keep the server's `PlaylistItemId` (`[A-Za-z0-9-]{1,64}`), used for remove and move. Reordering is **Move up /
   Move down** on each entry (accessible, no drag), sent as `Move/{newIndex}`; after any change the list is read again.
4. **Deleting a playlist** asks first and names it, reads the playlist's own `CanDelete`, then `DELETE /Items/{id}` with
   `verifyUnauthorised` (Jellyfin answers a refused delete with 401, Milestone 10).
5. **Play in order or shuffled**: the playlist's playable entries (films, episodes, videos) become a `PlayQueue` (ids in order, or
   shuffled once on the phone), held in memory under a random token that the player route carries. The player's Up next, Next and
   Previous follow the queue instead of the series. After the process is ended the token is unknown and the item plays alone.
6. **Download a whole playlist**: each film or episode through `Downloads.prepare` and `start` at the default quality (as the bulk
   download, Milestone 10 decision 6); one line sums it.
7. **Collections**: create, add, remove; the server's CollectionManagement policy decides (a refusal says so). Collections open as
   before (a collection is a library-like grid). **"Part of this collection"** on a film's page comes from a per-profile index in
   memory: the collections (at most 100, by name) and each one's direct children (at most 500 each), read once in the background
   when the first film page opens and kept for 10 minutes (an add or remove made in the app updates it at once). Jellyfin has no
   "which collections hold this item" query.
8. **The bar** gains **Add to playlist** and **Add to collection** (under More options). Both open one picker (a secure dialog):
   the existing playlists or collections, and "New playlist…" / "New collection…" with a name (1 to 200 visible characters). The
   detail page has the same two buttons.
9. **Detail extras** (films, episodes, series, videos): Cast and crew (at most 50, each with the server's own picture of the person
   through the artwork path, `ArtworkRef(personId, PRIMARY, PrimaryImageTag)`), Trailers, Extras, More like this (12), the
   collections it is part of, and Versions. Each part fails on its own (empty), so one refusal never blanks the page.
10. **Trailers (S7):** a local trailer plays in the app's player (it is an item). An online trailer (`RemoteTrailers`) is kept only
    as printable `http`/`https` of at most 2,048 characters with a host; the button names the host ("Watch the trailer on
    youtube.com") and opens it with `ACTION_VIEW` in another app or the browser. **The phone never fetches it** and no token or header
    goes with it (the address is handed to Android, not to the app's network code).
11. **Versions:** a film with more than one `MediaSource` shows "Version" with each source's name; the choice is sent as
    `MediaSourceId` in PlaybackInfo and the plan uses that source (else the first). It lasts for that play only; downloads use the
    server's first version.
12. **Filters:** one value for each of genre, year, age rating, studio and tag (so the reference's comma-or-pipe array question
    never arises), with the choices from `/Items/Filters` (years, ratings, tags) and `/Genres`, `/Studios` (ids), all for the
    library. **Jump by letter:** A to Z (`nameStartsWith`) and "#" (`nameLessThan=A`). The library grid's Filter button opens the
    live filtered list; with no filter the cached grid is what shows.
13. **Genre and studio pages:** Genres and Studios buttons on a library's top bar list them for that library; each opens the
    filtered list. A person opens their page (picture, overview, their films and shows).
14. **Search** also shows People, Genres and Collections (10 each) from the server while online; offline only saved titles show.
15. **Subtitles from the internet (D7):** in the player's subtitle sheet ("Find subtitles online") and on the detail page. The
    language defaults to the profile's own subtitle language (`/Users/Me`), else the phone's, else English, and can be changed.
    Results are text only: provider, name, format, rating, downloads, "hearing impaired" and "forced" (no address is shown or
    kept). Downloading is sent once. **Refusals:** the server's SubtitleManagement policy decides; 403 and a checked 401 say "This
    profile isn't allowed to fetch subtitles." and keep the sign-in.
16. **After a download** the server adds the subtitle and refreshes the item itself. The phone reads the item's subtitle count after
    2, 4, 8 and 16 seconds; when it grows, the player asks the server again **at the same position** (as a quality change does) and
    the new track can be picked; the detail page refreshes the item. If it has not appeared: "Downloaded. It can take a minute to
    show."
17. **Photos and home videos:** `photos` and `homevideos` libraries are offered (no other new kind). Their grid lists direct
    children (albums and folders open like a collection). A photo opens a **full-screen viewer** (a pager over that folder's
    photos, pinch to zoom 1x to 4x, double tap 2x), drawing the server's own picture at screen size (`ArtworkType.PHOTO`, at most
    1,920 px and the existing 4-megapixel decode cap). A video opens its page and plays in the normal player.
18. **`verifyUnauthorised`** is used for every change in this milestone and for the policy-gated subtitle search, so a refusal
    answered with 401 is checked with `GET /Users/Me` before anything is signed out (`ServerApi.post` and `get` gain the option).

## Tasks

- [x] T0 This plan.
- [ ] T1 Models: `ItemKind` PHOTO, VIDEO, PLAYLIST; `LibraryKind` PHOTOS, HOME_VIDEOS; mappers and destinations; `ArtworkType.PHOTO`.
- [ ] T2 `ServerApi.post` / `get` with `verifyUnauthorised`; red test first.
- [ ] T3 `data/lists`: seam, models, DTOs, mappers, gateway, the collection index; MockWebServer and hostile tests.
- [ ] T4 `data/discover`: seam, models, gateway; tests.
- [ ] T5 `data/subtitles`: seam, models, gateway, the "has it arrived" check; tests.
- [ ] T6 Playback: `PlayQueue` and the queue navigator, `mediaSourceId`; tests.
- [ ] T7 `ui/lists`: Playlists and playlist screens, the add-to picker, bulk actions; Home card; tests.
- [ ] T8 `ui/discover`: detail extras block (cast, trailers, extras, similar, collections, versions), person page, filtered list
      with filters and letters, genres and studios pages, search extras; tests.
- [ ] T9 Subtitle search: the sheet in the player (reload at the position) and on the detail page; tests.
- [ ] T10 `ui/photos`: the viewer; tests.
- [ ] T11 The stand-in server: every route above, refusing as Jellyfin does; Python tests.
- [ ] T12 Security check, ARCHITECTURE, README, version 1.3.0 (build 5), ChangeLog.

## Tests

Gateways against MockWebServer through the real `ServerApi`: every path, query and body; hostile answers (huge lists, bad ids, a
non-http trailer, an entry id with a slash, control characters, a subtitle id with `..`); every change sent once; a refused 401
checked and the sign-in kept. Pure tests for the queue (order, shuffle keeps every item, next and previous at the ends), the
decision's version pick, the letter and filter query. ViewModels with fakes. Render tests at phone and tablet sizes and font 2.0
for the new screens. Stand-in server: Python tests for every new route.

## Security impact

- **S7:** unchanged. Online subtitles and identify go through the server (D7). Online trailers are handed to another app by
  `ACTION_VIEW` and never fetched by this app. Person pictures come from the user's server through the artwork path.
- **S9:** no new permission. **S10:** no new component; the trailer intent is an implicit `ACTION_VIEW` for an http(s) address,
  with no extras and no granted permissions.
- **S11:** every answer bounded and validated; subtitle and entry ids checked before they become path segments; no third-party
  address is kept except a trailer's, which is validated and only ever handed to Android.

## Decisions made (during the build)

Recorded as they happen; each can be changed.
