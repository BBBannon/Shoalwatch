# Milestone 10 — Manage the library from the phone

**Goal (the owner, 20/11/2026, roadmap Milestone 10):** pick several things at once and act on them together (watched or unwatched,
favourite, download, delete), and, as a server administrator, put a wrong film or series right from the phone: identify it again,
change its pictures, edit its details, refresh it or delete it. Approve a Quick Connect code typed in from a TV or laptop.
Version **1.2.0 (build 4)**. Roadmap decisions D2, D3 and D7 bind it.

**Architecture:** one new data package, `data/manage` (the `Manage` seam, its models, and `api/ManageGateway` with its wire types
and the full-item round-trip `ItemEdit`), built on `ServerApi` exactly as `data/admin` is: gated on `AdminRights`, ids validated
before anything is sent, every change sent once (`retry = false`, `sendOnce`, `sendIf`). One new UI package, `ui/manage` (a hub
and three screens), whose ViewModels extend the existing `AdminScreenModel` and draw through `AdminScaffold`, so they inherit the
admin session rule (closes for good on any profile change, a lost administrator answer or a 401) and `FLAG_SECURE`. Multi-select is
a small shared piece in `ui/browse` (`Selection`, `BulkAction`, `BulkViewModel`) plus `ui/components/SelectionBar`; each browsing
screen takes it as an optional parameter. Quick Connect approval is `data/servers/QuickConnectApprover` and a small Settings screen.
**No new dependency, no new permission, no manifest change, no Room schema change.**

## Decisions

1. **Long press starts selecting.** On a card (library grid, Home rows, search, a season's episodes, Downloads) a long press
   selects it; a tap then adds or removes. The card's own dots button still opens the one-item sheet, and with exactly one item
   selected the bar offers "More" (the same sheet), so nothing that was reachable is lost. A screen reader gets "Select" as a
   custom action on every card and "Selected" / "Not selected" as its state.
2. **The bar.** While anything is selected the screen's top bar is replaced by the selection bar: Close (X), "3 selected", then
   Mark as watched, Mark as unwatched, Favourite, Remove from favourites, Download, and under the overflow Delete from server (only
   when allowed), Manage (one item, administrators) and More (one item). Back clears the selection first. The selection is per
   profile (a profile change clears it) and is saved across rotation (ids only).
3. **The bar's actions are `BulkAction` values** chosen per screen. Milestone 11 adds `ADD_TO_PLAYLIST` and `ADD_TO_COLLECTION`
   to the enum and one branch in `BulkViewModel`; nothing else changes.
4. **Bulk actions use the existing paths, one item at a time.** Watched and favourite go through `Playstate` (saved on the phone and
   queued in the outbox, so they work offline). Download prepares each item at the default quality (Original if none is set) and
   starts the plan only if it fits; there is no quality sheet for a bulk download (it would ask once per item). Downloads' own
   "Delete download" uses the tab's existing delete dialog and owner rules. One snackbar line sums the result.
5. **Who may manage (D2):** identify, pictures, details and refresh only for a server-confirmed administrator (`AdminRights`).
   **Delete from server** for an administrator, or a profile whose server policy allows deletion (`EnableContentDeletion`, or
   any `EnableContentDeletionFromFolders`): read from the same `GET /Users/Me` answer, held in memory with the administrator answer
   for the active profile only (`AdminRights.allowsDelete`). The server still decides; a refusal says so.
6. **Delete always asks and names the item** ("Delete "The Silent Harbour" from the server?"; several: the first three names and
   "and 2 more"). It says it cannot be undone and removes the files from the server for everyone. If any of them is downloaded on
   this phone the dialog adds "Keep the copy on this phone" (off by default). After a delete the item leaves every list of this
   profile; with the copy kept it stays in Downloads (its record is kept, only its list entries go).
7. **Pictures from online sources (S7):** the list from `GET /Items/{id}/RemoteImages` names third-party hosts (TMDb and others).
   **The phone never loads those addresses.** Jellyfin 12.4.0 has no server-side proxy for them (no `RemoteSearch/Image`, no
   `Images/Remote` in the reference), so each choice is shown as text: provider, size, language and rating, in the server's own
   order (best first). Choosing one asks the server to fetch it (`RemoteImages/Download`); the phone then shows the new picture
   through the normal artwork path, from the user's own server. The same applies to identify results: their `ImageUrl` is never
   loaded; results show name, year, provider and the first lines of the overview, with a letter tile.
8. **Upload (D3):** the Android photo picker (`PickVisualMedia`, images only): **no storage permission**. The picture is read
   through the content resolver (at most 40 MB), its size read first without decoding (`inJustDecodeBounds`; refused over 16,000
   px a side or 120 megapixels), decoded with a power-of-two sample, scaled so its long side is at most 2,000 px (3,840 for a
   backdrop), re-encoded as JPEG quality 90 (at most 10 MB) and sent base64 as the body of `POST /Items/{id}/Images/{type}` with
   `Content-Type: image/jpeg`. Base64, not raw bytes: Jellyfin's `SetItemImage` reads the body as base64 (the 12.4.0 reference says
   `image/*` binary; the server's code is what counts). EXIF and every other piece of metadata is dropped by the re-encode.
9. **After any picture change** the item is fetched again (new image tags, so new cache keys) and the item's pictures are removed
   from the memory cache, so the new one shows at once. Old disk-cache entries are keyed by the old tag and age out.
10. **Edit details:** `GET /Items/{id}/MetadataEditor` (for the age-rating list) and `GET /Items/{id}` (the full item). Saving reads
    the item again, replaces only the typed fields the form edits (name, sort name as `ForcedSortName`, original title, overview,
    year, premiere date, age rating, community rating, genres, tags, studios, locked fields and "lock all") and posts the whole
    object back (Jellyfin needs the full body), as `UserPolicyEdit` does for a policy. Every other key goes back as it came.
11. **Refresh** has the web dashboard's three choices: "Scan for new and updated files" (`Default`/`Default`), "Search for missing
    metadata" (`FullRefresh`/`FullRefresh`) and "Replace all metadata" (`FullRefresh`/`FullRefresh`, `replaceAllMetadata=true`),
    with "Replace existing images" for the last two. `regenerateTrickplay=false`; no `Recursive` (not in the reference).
12. **Identify** searches `RemoteSearch/Movie` for a film and `RemoteSearch/Series` for a series (other kinds are not offered) with
    `{SearchInfo:{Name,Year}, ItemId}`. Applying posts a typed `RemoteSearchResult` (name, year, premiere date, provider ids,
    provider name) rebuilt from validated fields, with "Replace all pictures" (default on, as the web).
13. **Quick Connect approval** (Settings, "Approve a sign-in on another device"): the 6 characters (spaces ignored, letters and
    digits only), then `GET /QuickConnect/Enabled` (so a switched-off server is told apart from a wrong code: Jellyfin answers a
    switched-off Authorize with 401, which would sign this phone out), then `POST /QuickConnect/Authorize?code=` sent once. It signs
    the other device in **as the active profile**, and the screen says so before you approve.
14. **Lock (S8):** every management screen and dialog is under the app lock's cover like every screen; the management screens are
    `FLAG_SECURE` (they reuse `AdminScaffold`) and every dialog reads `LocalCovered`.
15. **Where:** the item screen and the series screen get a "Manage" button for administrators (and "Delete from server" for a
    profile that may only delete); the selection bar offers both for one item.

## Tasks

- [x] T0 This plan.
- [ ] T1 `ServerApi.postBytes` (a non-JSON body, sent once, `sendIf`), red test first.
- [ ] T2 `data/manage`: seam, models, wire types, mappers (S11), `ItemEdit` round-trip, `ManageGateway` (every call gated,
      every change sent once); `AdminRights.allowsDelete` and `known`; MockWebServer and hostile-input tests; `ManageBoundaryTest`.
- [ ] T3 `PickedImages`: bounds, sample, scale, JPEG re-encode (Robolectric native graphics); refusals tested.
- [ ] T4 `Library.forgetDeleted`, `ArtworkEviction.evictItem`; tests.
- [ ] T5 Quick Connect approval: `QuickConnectApprover`, the Settings row, screen and ViewModel; tests.
- [ ] T6 Selection: `Selection`, `BulkAction`, `BulkViewModel`, `SelectionBar`, the card modifier; tests.
- [ ] T7 Selection in the library grid, Home rows, search, the season's episodes and Downloads; render tests.
- [ ] T8 `ui/manage`: hub (refresh, delete), Identify, Images (upload through the picker), Edit details; entry from the item and
      series screens and the bar; ViewModel and render tests.
- [ ] T9 The stand-in server: remote search and apply, images (list, online choices, download, upload, delete), metadata editor
      and save, delete, Quick Connect authorise; Python tests.
- [ ] T10 Emulator walkthrough, security check, ARCHITECTURE, README, version 1.2.0 (build 4), ChangeLog.

## Tests

Gateway: every path, query and body against MockWebServer through the real `ServerApi`; nothing sent without `allows`
(`allowsDelete` for delete); every change sent once and not after the gate closes; hostile answers (huge lists, bad ids, a `Path`
field, third-party URLs never mapped into anything shown, control characters); the full-item round-trip keeps unknown keys and
replaces only typed fields. Picture preparation: a large picture is scaled and re-encoded as JPEG; a non-picture, a huge declared
size and an over-cap file are refused. ViewModels with fakes: selection rules, bulk outcomes, delete confirmation and the
keep-copy choice, each manage screen's gate and actions, Quick Connect code rules and messages. Render tests for the bar, the
delete dialog and each manage screen at phone and tablet sizes and font scale 2.0. Stand-in server: Python tests for every new route.

## Security impact

- **S7:** unchanged. Online pictures and identify results are fetched by the server; the phone shows them only as text, and new
  pictures come from the user's server.
- **S9:** no new permission (the photo picker needs none).
- **S11:** uploads are bounded and re-encoded on the phone; every new answer is bounded and validated; metadata is sent as typed
  fields over the server's own object; server file paths (`ImageInfo.Path`) are never declared.
- **S8 / new risk:** an unlocked phone signed in as an administrator can now delete media. Delete always asks and names the item;
  the screens sit behind the app lock when it is on. Quick Connect approval signs another device in as the active profile: a
  profile's PIN or the app lock protects it like everything else of that profile.

## Decisions made (during the build)

Filled in at the end of the milestone; see the bottom of this file.
