# Milestone 4 — small points carried forward

From the five task reviews of Milestone 4 (`.superpowers/sdd/m4/tasks-1-4-review.md`, `tasks-5-8-review.md`, `tasks-9-10-review.md`, `tasks-11-12-review.md`, `tasks-13-16-review.md`), triaged at the milestone close on 16/11/2026 (branch `milestone-4`), and the whole-milestone review (`final-review.md`, same date) with its fix wave.

- **Rule used:** a point that touches security, privacy or data loss, or is cheap and safe, was fixed in the close-out wave, each in its own commit with a test (listed at the end).
- **Everything else is here,** with why it waits and when it should be done. Nothing was dropped silently.
- These belong on the project to-do as one item ("Small points carried over from the Milestone 4 reviews"); the controller adds it (this wave does not edit `TODO.md`).

**When:**
- **Tidy-up** = the next small-fixes pass, any milestone.
- **M5** = with Milestone 5 (outbox, watch-and-delete).
- **M8** = with Milestone 8 (security-team review).
- **Real server** = after Ben's phone test shows which path real use takes.

## Carried

| Source | Item | Why it waits | When |
|---|---|---|---|
| Tasks 1-4 Minor 1 | The orphan sweep keeps a known item's folder on EVERY root, so a copy redone on the other storage while a card was out leaves the old one on that card | Wastes space only; never deletes a wanted file. Needs the sweep to know each row's own root | Tidy-up |
| Tasks 1-4 Minor 2 | `DownloadPaths.deleteTree` is not counted against the sweep's 100,000-entry bound | The bound is a safety net for one run; deletion stays inside the root and never follows links | Tidy-up |
| Tasks 1-4 Minor 3 | Every path builder calls `Volumes.allRoots()` (one storage lookup per name) | Performance only; correct | Tidy-up |
| Tasks 1-4 Minor 5 | The link-safety tests skip (`assumeTrue`) where the host cannot make links (Windows without the privilege) | They run where links can be made; a CI fail-instead-of-skip switch needs a CI first | M8 |
| Tasks 1-4 Minor 7 | A second profile's Download of a title already on the phone shows "already on this phone", so it learns another profile kept it | Low privacy signal, and only for a title its own account was confirmed as allowed to download (strict rule). Needs a product decision on the wording | M5 (with the storage view) |
| Tasks 1-4 Minor 8 | `DownloadStore(db, now, lock = None)`: a trailing lambda would bind to `lock` | Constructor-order trap only; every call names its arguments today | Tidy-up |
| Tasks 1-4 Minor 9 (part) | The stand-in server compares a date `If-Range` exactly in its ETag mode (fixed in `--no-etag` mode) | Test tool only | Tidy-up |
| Tasks 5-8 Minor 3 | An HLS conversion's master playlist is held to `/videos/`, not to `/videos/<this item>/` | Defence in depth (the server still checks access). Tying it to the item id could break real conversions if Jellyfin ever names a media source id there (grouped versions): needs the real server first | Real server, then M8 |
| Tasks 5-8 Minor 4 | A 400 or 403 on an HLS playlist or segment says "not allowed" | Wrong words only; the download stops safely | Tidy-up |
| Tasks 5-8 Minor 6 | A weak `ETag` (`W/…`) is kept as the `If-Range` validator, so every resume behind such a proxy restarts from zero | Slower, never wrong (the server must compare strongly). Only behind some proxies | Real server |
| Tasks 5-8 Minor 8 | "Already on this phone" also counts another owner's FAILED or QUEUED copy | Wording only; the copy is shared only once COMPLETE | Tidy-up |
| Tasks 5-8 Minor 9 | `PinnedArtwork.find` does not check the profile's `visible_items` record | The Tasks 11-12 review judged it acceptable: the loader is only given refs built from rows the session can already see, and pictures are not titles. Adding it means seeding visibility in every artwork test | M8 |
| Tasks 5-8 Minor 11 | The HLS parser accepts an `EXTINF` above `TARGETDURATION` and ignores `#EXT-X-GAP` / `#EXT-X-I-FRAMES-ONLY` | Harmless: every segment is still bounded and checked | Tidy-up |
| Tasks 5-8 Minor 12 (part) | An internal-storage write error other than "no space" (EIO, read-only) is retried as a network error | Correct outcome (a retry, then a failure after 8 attempts); wrong words | Tidy-up |
| Tasks 9-10 Minor 1 | A conversion resumed in the same process always expects an audio track | A no-audio conversion fails its check and is fetched again; rare (films with no audio) | Tidy-up |
| Tasks 9-10 Minor 2 | A finished conversion is fetched again if the process dies during its extras or check | Wasted download, never a wrong file | Tidy-up |
| Tasks 9-10 Minor 3 | The verifier's audio map has no `audio/raw`, `audio/mpeg-L2` or `audio/ac4` | Such a file fails its check plainly ("didn't pass its check"); real files will show whether it matters | Real server |
| Tasks 9-10 Minor 4 | The fetching owner is chosen once per item, so an owner whose sign-in expired mid-item fails it instead of handing over to the next owner | The item says "Sign in" and the next run picks a signed-in owner | Tidy-up |
| Tasks 9-10 Minor 8 | No test that the EARLIEST owner with a token fetches when both have one | Test gap; the code is a one-line `firstOrNull` over owners in order | Tidy-up |
| Tasks 9-10 Nit 1 | `DownloadQueue`'s `io` parameter is unused | Pre-existing dead code: flagged, not deleted (surgical-change rule) | Tidy-up |
| Tasks 9-10 Nit 3 | `ItemBytes.inside` returns the unresolved file, not the checked real path | App-private storage only; the check is at every open | M8 |
| Tasks 11-12 Minor 3 | A conversion refused by the server never tries the next owner (an original does) | Conversions need a confirmed permission at plan time; the asymmetry is safe (it fails) | Tidy-up |
| Tasks 11-12 Minor 4 | Dolby Vision passes the check whenever HEVC is counted, whatever the DV profile | The planner gates first; a profile 5 or 10 file with no decoder would fail to play and fall back | Real server |
| Tasks 11-12 Minor 5 (part) | The mobile-data-on `WaitingForConnection` branch is untested; `theWorkerHasNoInputData`'s first assertion checks a builder default | Test gaps only | Tidy-up |
| Tasks 11-12 Minor 6 | Every retry posts "Waiting for a connection", whatever the cause | Wrong words only | Tidy-up |
| Tasks 11-12 Minor 8 | `start` gives no signal when nothing was added | The sheet already shows the skipped counts before the yes | Tidy-up |
| Tasks 11-12 Minor 9 | `PlannedItem.confirmed` is caller-settable | Hardening only; an `internal` constructor protects nothing in a one-module app, so it needs an opaque planner token | M8 |
| Tasks 11-12 Minor 10 | The v4 schema hash changed during the milestone (again for the final review's M-1: `download_owners.fetched`), so an older Milestone 4 debug build crashes on open | Development builds only (no release had v4); the uninstall step is in the controller's emulator steps | Done at release (note only) |
| Tasks 11-12 Nit 3 | (a) `OfflineLibraryDao.CONFIRMED` is unused; (b) `SharingTest`'s "a fresh record is not asked about" passes only through the 50 limit; (c) `AppGraphDownloadsTest`'s first `assertSame` is a tautology | Dead code flagged, not deleted; weak tests | Tidy-up |
| Tasks 13-16 M-4 | A kick can cancel a worker WorkManager has just started (check-then-act) | Accepted and documented in ARCHITECTURE "Known limits": the replacing work starts at once and requeues it | Accepted |
| Tasks 13-16 M-5 | The notification ask is marked before the answer; a recreated activity while the system prompt is up drops the pending download | Rare (rotation during the prompt); the next press downloads | Tidy-up |
| Tasks 13-16 M-10 | A failed sharing switch snaps back with no message | Rare (Room failing) | Tidy-up |
| Tasks 13-16 M-11 | `SavedCopies` repeats its file check on every lookup (about 1,200 file stats for a long Low conversion) | Performance only, on IO | Tidy-up |
| Tasks 13-16 Nits | `DownloadActions.target` written and never read; `DetailViewModel.downloadsSeam` duplicates `downloads`; `retryDownload` uses `runCatching` (swallows cancellation, fails silently); `SavedFirstPlaybackGateway.read` checks no-follow then opens following links | Tidy-ups; the last needs write access to the app's own folder | Tidy-up |
| Final review Nit | A download that was RUNNING when its account went fails for the heir with "Sign in again" (`FAILED(SIGN_IN_NEEDED)`: the removed account's route went stale), though the heir is signed in | The heir presses Retry and it carries on; a variant of Tasks 9-10 Minor 4 (no hand-over mid-item) | Tidy-up |
| Final review Nit | A shared copy whose confirmation is stale is still listed as "Ready" in the Downloads tab and on the detail badge while online, but Play streams it | Cosmetic; it must stay listable offline by design, and it is re-asked before it plays | Tidy-up |

## Fixed in the close-out wave (for the record)

| Source | Item | Commit |
|---|---|---|
| Tasks 13-16 I-1 | The 7-day re-ask ran only from the Downloads tab | `e4df4d9` |
| Tasks 13-16 I-2, M-8 | No delete for an episode, season or series; cancelling claimed the full size | `845318a` |
| Tasks 13-16 M-1, M-2 | The smoke test's offline step could not fail; it left device state | `ee483ac`, `d51f4f5` |
| Tasks 13-16 M-3 | A remembered conversion did not record which account opened it | `76167ad` |
| Tasks 13-16 M-6, M-7 | "season 0" labels; the sharing text understated the rule | `55b8db1`, `e3ace36` |
| Tasks 13-16 M-9 | Dates written as 16/11/2026 on 15/11/2026 | `523feb3` |
| Tasks 13-16 M-12 | The download controls untested inside their screens | `b814464` |
| Tasks 11-12 Minor 2 | A file left by another owner's fetch was kept under an unconfirmed fetcher | `97985d7` |
| Tasks 11-12 Minor 7 | An exception escaping the drain failed the work for good | `99cc9d2` |
| Tasks 11-12 Nits 1, 2 | ARCHITECTURE credited the wrong caller; the merged components not checked | `fe52232` |
| Tasks 1-4 Minor 4, Tasks 5-8 Minor 10 | Download models printed ids, sizes and validators | `d798d98` |

## Fixed in the final review's wave (for the record)

| Source | Item | Commit |
|---|---|---|
| Tasks 9-10 Minor 6 (needed Ben's acceptance) | The notice's "Downloading N of M", progress bar and "N ready" covered every profile's downloads: it now says only "Downloading" and "Downloads finished" | `b197ae6` |
| Tasks 1-4 Minor 10 | `QueueSpace.fits` now saturates (folded into M-2) | `998c49c` |
| Final review M-1, M-2, L-1, L-3 to L-7, L-10, L-11, two Nits; walkthrough W-1 | See `docs/security/milestone-4-check.md` section 4 | `1c14a46` … `092e230` |
| Final review L-2 | **Partly fixed:** a confirmation dated ahead of the clock is stale (`e23b554`); a clock **held back** within the week while online keeps a confirmation young (re-review L-b), recorded as a residual risk in `docs/security/milestone-4-check.md` section 5 (not fixed by decision) | `e23b554` |
| Re-review N-1, L-a, L-d, Nit-1; walkthrough W-2 | See `docs/security/milestone-4-check.md` section 4 | `c4357ea` … `881f7a5` |
| Final review L-8, L-9 | Recorded as residual risks in `docs/security/milestone-4-check.md` section 5 (not fixed by decision) | — |
