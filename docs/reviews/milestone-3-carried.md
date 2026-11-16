# Milestone 3 — small points carried forward

From the whole-milestone review of 15/11/2026 (branch `milestone-3`). Each line says whether it is fixed, carried to the to-do list or dropped. Tracked by the to-do item "Small points carried over from the Milestone 3 reviews".

### Minor (new)

| # | Finding | Where | Decision |
|---|---|---|---|
| **N-1** | The media session publishes the Jellyfin item id as `mediaId` to Bluetooth devices and notification-listener apps. Nothing reads it back (Task 6 M-6) | `engine/MediaItems.kt:22` | **Fix before merge:** a constant `mediaId`, and a one-line test |
| **N-2** | Task 8 m-2 (the `carries` re-ask through `TrackRules.step`) has no test that fails on the old code | `PlaybackTracks.kt:90-93` | **Fix before merge:** an external picture track answered without its burn-in asks again with `burnInSubtitleIndex` and `forceTranscode`, not `pictureSubtitleIndex` |
| **N-3** | ARCHITECTURE ("failing closed", `:570`) and ChangeLog v1.89 ("a subtitle file built to overload the phone is refused") claim more than the code does | `docs/ARCHITECTURE.md`, `ChangeLog.txt` | **Fix before merge** with I-1 |
| N-4 | A headset or Bluetooth "play" while the app is stopped (screen off, Home pressed) is allowed by `ControllerRules`, so the film can play in the background with the screen locked. The plan says no background playback this milestone | `ExoPlayerEngine.kt:227-235`, `PlaybackController.onHostStopped` | Carry: check on a device; if confirmed, refuse `COMMAND_PLAY_PAUSE` from controllers while the host is stopped |
| N-5 | `SubtitleFiles.allowed` keeps Media3 spans by class-name prefix (`androidx.media3.common.text.`). With R8 on in release (`isMinifyEnabled = true`), those classes can be renamed, so ruby and emphasis marks may vanish in release only. It fails safe | `SubtitleFiles.kt:131-133` | Carry: `is RubySpan`, `is TextEmphasisSpan`, `is HorizontalTextInVerticalContextSpan` |
| N-6 | `TrackControl.onReady`'s re-ask passes no `burnInFormat`, unlike every other re-ask | `PlaybackTracks.kt:225` | Carry: pass `TrackRules.burnInFormat(plan.copy(request = r))` |
| N-7 | After the process is ended in the background, the restored player may close rather than resume: the first `activeSession()` value can arrive before the saved sign-ins are loaded (an account without a loaded token is hidden), or before `ActivePlayback` has heard the active session (`begin` then refuses). It fails closed | `PlayerViewModel.onSession`, `ActivePlayback.begin` | Carry: device check with `adb shell am kill` while in the background |
| N-8 | `MediaRoute.base` is stored and never read (dead code) | `data/api/MediaRoute.kt:23` | Carry (tidy) |
| N-9 | A stray, duplicated KDoc line above `cleanHook` | `SubtitleFiles.kt:46` | Carry (tidy with I-1) |
| N-10 | ARCHITECTURE "Player screen" still says Retry is `open(itemId, RESUME)`; it is the current item | `docs/ARCHITECTURE.md:586` | Carry (or with N-3) |
| N-11 | Autoplay of the next episode is always on (no setting): accepted in Task 8, but Ben has not been told in the to-do | `BrowseRoutes.kt:200` | Carry: "Add a setting to turn off playing the next episode by itself" |
| N-12 | Media3's HLS playlist parser has no size limit of the app's own; a hostile server can serve a huge playlist from its own `/Videos/` path | engine | Carry as a residual risk (recorded in the security check) |
| N-13 | The WebVTT parser's own grouping is quadratic in time (not memory) for overlapping cues; the cancellation flag stops it at the next group, so it is bounded by the 10 s wait | Media3 | Drop: bounded; record as unproven |

---


## 5. Triage of the deferred Minors

**Key:** Fix = fix before merge. Carry = to the to-do (one-line plain-English description). Drop = no action (reason).

| Source | Item | Decision | To-do line, or reason |
|---|---|---|---|
| Task 2 | M-3 `hls` only from `TranscodingSubProtocol` | Carry | "Treat a server's conversion address that ends in .m3u8 as a streaming playlist even if the server does not say so" |
| Task 2 | M-5 base path joined then split | Drop | Fails closed; only a saved address with an encoded `/` or `%` in its path could hit it |
| Task 3 | M2 one direct-play profile for every container | Carry | "Split the direct-play rules per container once real files show a pairing the phone cannot play" |
| Task 3 | M3 only the built-in display is read | Carry | "Read HDR support from the screen the player is on, not only the built-in one" |
| Task 3 | M4 Android 10 to 13 and 14 display paths not tested | Carry | "Add tests for the HDR screen check on Android 10, 13 and 14" |
| Task 3 | M5 HDR decoder check is cautious | Carry | "Note in Known limits that some HDR files are converted when the phone could have played them" |
| Task 3 | M6 HDR rule test gaps | Carry | "Add the missing Dolby Vision rows to the HDR rule tests" |
| Task 3 | M7 test lists copied by hand | Carry | "Check the device profile test against the Jellyfin API reference file" |
| Task 4 | A-M1 the no-`TranscodingUrl` fixture | Drop | Plan rule, checked on a real server in the unproven list |
| Task 4 | A-M2 `fallBack` does not refuse a transcode | Carry | "Refuse a second fallback to a conversion in the resolver itself, not only in the controller" |
| Task 4 | A-M3 `IllegalStateException` for another session's route | Drop | A programming error with a fixed message; unreachable from the controller |
| Task 4 | A-M4 transcode path not tied to the item | Carry | "Once a real server's conversion addresses are seen, check that they name the item being played" |
| Task 4 | A-M5 only the first media source | Carry | "Let the user pick a version when a film has several" |
| Task 4 | A-M6 direct play ignores the quality ceiling | Carry (with I-2) | "Do not direct play a file above the chosen quality, even if the server offers it" |
| Task 4 | A-M7 two weak decision tests | Carry | "Strengthen two playback decision tests so they fail if their rule is removed" |
| Task 4 | B-M2 stale-route tests use a fake selector | Carry | "Add a media route test with the real address selector and a network change" |
| Task 4 | B-M3 frame-rate and portrait limits | Carry | "Note in Known limits that 1080p60 and portrait videos may be converted on some phones" |
| Task 4 | B-M4 lax decoder sizes | Drop | Nothing to fix in code; device evidence only |
| Task 4 | B-M5 `clone()` drops per-call timeout and tags | Drop | Media3 sets neither; a refused clone fails closed |
| Task 5 | M-2 a resend can land after a newer stop | Carry (Milestone 5) | "The durable outbox must not let an old resent stop overwrite a newer position" |
| Task 5 | M-3 a finished stop while unreachable saves nothing | Carry (Milestone 5) | "When the server cannot be reached, still save 'watched' and the stop position on the phone" |
| Task 5 | M-4 local position vs the server's resume rules | Carry | "Refresh an item's saved position from the server after a stop" |
| Task 5 | M-5 "main thread only" not enforced | Carry | "Check in code that playback records and reporters are only used on the main thread" |
| Task 6 | M-4 re-resolve once per item | Carry | "Allow another network recovery after the film has played for a while" |
| Task 6 | M-6 the media session shows the item id | **Fix** | N-1 |
| Task 6 | M-7 reporter not told of some pauses | Carry | "Tell the server about pauses that happen while loading or at the end" |
| Task 6 | M-10 a reload jumps back a few seconds | Carry | "Changing quality resumes where the film is, not where it was when the change began" |
| Task 6 | M-11 pin the `WAKE_LOCK` removal in a unit test | Carry | "Add a unit test that the manifest still removes Media3's WAKE_LOCK" (the release manifest check already catches it) |
| Task 6 | M-12 4 Hz publish when paused; adjacent empty season; route cleared after release | Carry the first two, drop the third | "Stop the player's 4-times-a-second update while paused or ended"; "Next episode skips a season with no saved episodes". The third is safe as built |
| Task 7 | m-1 abandoned parses | Done | Fixed in `a4557b1` (cancellation flag) |
| Task 7 | m-2 Media3's own `IllegalArgumentException` message passes through `guarded` | Carry | "Wrap every subtitle parser error in the app's own fixed message" (never logged or shown today) |
| Task 7 | m-3 sleep due while loading | Done | Fixed in `ec65a96` |
| Task 7 | m-4 three missing tests | Done | Added in `4d80e4a` / `ec65a96` |
| Task 7 | m-5 the first play session is not stopped on the first re-ask | Carry | "Check on a real server that no conversion is left running after the first re-ask" |
| Task 7 | m-6 a text track with no file format is a dead tap | Carry | "Say 'These subtitles couldn't be read' instead of doing nothing" |
| Task 7 | m-7 next title over the old stream | Done | Fixed in `ec65a96` |
| Task 7 | m-8 `refreshCues` on every publish | Drop | Cheap and conflated; goes with Task 6 M-12 |
| Task 7 | m-9 audio mismatch does not set `audioForced` | Carry | "After a language mismatch forces a conversion, keep it when leaving burned-in subtitles" |
| Task 7 | m-10 boundary test accepts any `Call.Factory` | Carry | "Make the Media3 boundary test require the route's own call factory" |
| Task 8 | m-8 `onSession` reads Room before ending | Carry | "End playback before checking whether the old account is still signed in" (`ActivePlayback` already ends it first) |
| Task 8 | m-9 Up next announced again after rotation | Carry | "TalkBack: check the Up next count is not read out again after turning the phone" |
| Task 8 | m-10 `vm.video` read as a plain getter | Carry | "Give the player's picture its own state so it never depends on the 4-times-a-second update" |

**Count of deferred Minors:** 1 fix before merge (Task 6 M-6 = N-1), 30 carry, 8 drop, 5 already done.

---


## Status of the "fix before merge" lines above

All were fixed before the merge: N-1 (constant media id), N-2 (test), N-3 (wording), N-4 (headset play refused while the app is stopped), N-9 and N-10 (tidy). The subtitle findings were closed by replacing Media3's text parsers with the app's own (see `docs/ARCHITECTURE.md`, "Tracks and subtitles", and `docs/security/milestone-3-check.md`).

## Added by the last review rounds (15/11/2026) — all carried

| # | Point | Where | Decision |
|---|---|---|---|
| n-1 | Between two loads the picture-subtitle choice is applied to the old item's tracks; in that moment Media3 could pick a default text track, which fails that start safely ("Playback failed"); nothing is parsed | `engine/ExoPlayerEngine.kt` `load()` | **Fixed in Milestone 4** (before Task 9): text is turned off in `load()` before `setMediaItem` |
| n-2 | If the screen goes off in the few milliseconds before the player's controller exists, the stop is lost and the first load plays | `ui/player/PlayerViewModel.kt` | **Fixed in Milestone 4** (before Task 9): a stop before the controller is remembered; the first load starts paused |
| n-3 | The boundary test should allow only the four exact imports in `PictureSubtitles.kt` | `Media3BoundaryTest` | Carry (hardening) |
| n-4 | No reflection test that the HLS path receives the picture-only subtitle factory | engine tests | Carry (hardening) |
| n-5 | Past 32 style changes in one cue the rest of the cue takes the last style | `engine/SubtitleScanner.kt` | Carry: documented behaviour; revisit only if a real file shows it |
| n-6 | "No regular expression" in the documents should be worded more precisely | `docs/ARCHITECTURE.md` | Carry (wording) |
| m-4 | An ASS style override in the middle of a line applies to the whole line | `engine/AssFile.kt` | Carry: documented; per-run styling is a possible later improvement |
| — | The sleep timer and a film that has ended: the player stays on the last frame with a Play button | player screen | Carry: decide whether to leave the player or offer "Play again" |
| — | Not walked by hand on the emulator: TalkBack, switching account mid-film (the stand-in server has one user), tablet layout, headset buttons, mobile data default, WebVTT on screen, picture (PGS) subtitles | — | End-of-build test on Ben's phone and server |
