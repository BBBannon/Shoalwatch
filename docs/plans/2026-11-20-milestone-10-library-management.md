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

Made without the owner (he asked for no involvement); each can be changed.

1. **Management screens reuse the admin machinery.** `ui/manage` ViewModels extend `AdminScreenModel` and draw through
   `AdminScaffold`, so they are `FLAG_SECURE`, close for good on any profile change and drop what they loaded. The cost: the
   recents preview of these screens is blank, as for the admin screens.
2. **No pictures of identify matches or online picture choices** (S7): the 12.4.0 reference has no server proxy for them. They are
   words only. If Ben prefers thumbnails, it needs a server plugin that proxies them, or an S7 exception.
3. **Upload body is base64**, matching Jellyfin's server code rather than the reference's `image/*` binary. Unproven against a real
   12.x server until Ben's.
4. **Delete-only profiles** (policy allows deletion, not an administrator) get "Delete from server" on the item screen and in the
   bar, and nothing else of management.
5. **"Keep the copy on this phone" is off by default**: a delete from the server also removes this profile's download unless ticked.
   Kept, the item stays in Downloads and plays offline; watched marks made to it later cannot reach the server any more (the
   item is gone there).
6. **Bulk download skips the quality sheet** and uses the default quality (Original when none is set); an item whose plan does not
   fit is not started and is counted in "could not be done".
7. **One action at a time, one item after another.** Simple and safe (each through the same checks); a long selection is slow.
8. **The bar shows three actions as buttons** (watched, favourite, download or delete download) and the rest under More options, so
   it fits at twice the text size on a small phone.
9. **Refresh offers the web dashboard's three choices**; "Search for missing details" is the default, as the web's.
10. **Quick Connect approval checks `/QuickConnect/Enabled` first**: Jellyfin answers a switched-off Authorize with 401, which this
    app treats as the sign-in ending. The rare case of a server switched off between the two requests still signs the profile
    out on the phone (recorded as a known limit).
11. **Logo, Banner and later backdrops are listed as text** (size, Remove), because the artwork path shows only the poster, backdrop
    and thumbnail; adding the other kinds to `ArtworkRef` touches the pinned-artwork rules and was left out.
12. **The stand-in server** gained the routes in `tools/mockjelly/mock_manage.py`; anna may now delete (to test a delete-only
    profile), and `POST /mock/quickconnect-code` (this machine only) starts a code as a TV would.

### After the code review (21/11/2026)

13. **Each item's own `CanDelete` decides a delete** (review 1a). It is read (`GET /Items/{id}?userId=`, the read the edit form
    already uses, which fills every field) when Delete is pressed, so only the items the server allows are offered and the rest are
    named in one line; and again just before each DELETE, so nothing is sent for an item that has since become undeletable.
    Missing or not a boolean counts as false.
14. **Being an administrator no longer implies the delete right** (review 1b): Jellyfin's `BaseItem.CanDelete(user)` reads the
    deletion policy only. The policy (`EnableContentDeletion` or any `EnableContentDeletionFromFolders`) stays as a hint for
    showing the action; the hub hides Delete without it. This replaces part of decision 4. The policy hint is still required before
    the per-item read (it can only make the app send less; Jellyfin cannot answer `CanDelete` true without it).
15. **A 401 on `DELETE /Items/{id}` is checked, not believed** (review 1c): `ServerApi.delete(verifyUnauthorised = true)` asks
    `GET /Users/Me` once. A 401 there signs out as before; anything else (including the check failing, for example offline) keeps
    the sign-in and says "The server refused to do that." Choosing "keep the sign-in" when the check cannot be made errs towards
    not signing a profile out over a refusal; a dead token still fails the next ordinary call.
16. **Deleting a series or season offers to keep its downloaded episodes** (review 2): the episodes are found as Downloads lists
    them (`downloads.series`). Kept: the episode, series and season records stay and only leave the lists. Not kept: each episode
    download is removed and its record goes. Kept episodes also leave the lists (Next Up, Continue Watching), since they are gone
    from the server.
17. **A server delete counts as done once the server said yes** (review 3): every phone-side step after it is on its own.
18. **Uploaded pictures are decoded with `ImageDecoder`** (reviews 4, 5): it turns phone photos upright and decodes straight to the
    target size; transparency is laid on white by a post-processor in place, so there is one bitmap only. Running out of memory
    is "too large". Robolectric's native graphics supports `ImageDecoder`, so the EXIF case is tested with a real JPEG.
19. **Edit details saves only what was changed** (review 6): the form as first shown is kept and sent with the save; the gateway
    writes only the keys whose typed value differs, and nothing at all (not even the read) for an untouched form. Lists are chips
    (add and remove), and an entry typed but not added is saved as one entry. A rating left as shown is sent back as the server's
    exact value.
20. **A delete confirmed while another bar action runs is not dropped** (review 7): Delete is off in the dialog, with a line saying
    why, and a confirm then does nothing, so the question stays until it can be sent. (Queueing was simpler to get wrong.)
21. **The delete hint is part of the held admin state** (review 8): `AdminState.mayDelete`, so a re-check that changes only it is
    heard. Screens compare states with `isAdminFor`, so a change to the hint alone never closes an admin screen.
22. **Quick Connect approval warns and, for a PIN-protected profile, asks for the PIN first** (review 9), through the existing
    `ProfileLocks.check` (wrong tries and waits as everywhere). If whether the profile is protected cannot be read, the PIN is asked.
23. **The stand-in answers a refused delete with 401**, as Jellyfin does, reads the deletion policy only (no "administrators always"),
    and gives `CanDelete` on `GET /Items/{id}`.

## Follow-ups (for the to-do list)

- Confirm against Ben's real server: base64 upload, `RemoteSearch/Apply` with a typed body, `POST /Items/{id}` with the full item,
  `DELETE /Items/{id}` for a delete-only profile, its 401 for an item the profile may not delete (the sign-in must stay), and
  `CanDelete` on `GET /Items/{id}?userId=` for an administrator with and without the deletion policy.
- Thumbnails for online choices if a server-side proxy appears (or a Jellyfin plugin).
- Show Logo and Banner pictures (extend `ArtworkRef` with those kinds).
- Milestone 11: `ADD_TO_PLAYLIST` / `ADD_TO_COLLECTION` in `BulkAction`.
