# Milestone 3 security check (S13)

**Date:** 15/11/2026 · **Branch:** `milestone-3` at `7a76d29`; re-checked at `4243b5d` after the final review fixes, at `c22aca2` after the app's own subtitle readers, and finally at `fb93881` after the subtitle-parsers review fixes · **Scope:** SPEC §6, S1–S13 as they apply to the video player. The threat model is a shared family tablet with several Jellyfin profiles (adult and child), a hostile network, a hostile or compromised server, a crafted file in a Jellyfin library, another app on the device, a Bluetooth device, and a lost device.

This check was made by reading the code and tests. Where the app depends on Media3 1.9.2's behaviour, that was read too:

- **Earlier rounds, the source:** `SsaParser`, `SsaStyle`, `SubripParser`, `WebvttParser`, `WebvttCueParser`, `ParsableByteArray.readLine`, `MediaSessionLegacyStub`, `MediaSessionImpl` and `Util.handlePlayButtonAction`, plus Android's `android.text.Html`.
- **At `c22aca2`, the bytecode of the cached libraries:** `DefaultMediaSourceFactory`, `DefaultExtractorsFactory`, `Cue`, `SubtitleView` and `SubtitleViewUtils`, and Android's `SpannableStringInternal`.
- **At `fb93881`, the bytecode again:**
  - `DefaultMediaSourceFactory`'s `DelegateFactoryLoader`;
  - `HlsMediaSource.Factory`, `DefaultHlsExtractorFactory`, HLS's `WebvttExtractor` and `HlsMediaPeriod`;
  - `SubtitleTranscodingTrackOutput` and the Matroska, MP4 and TS extractors;
  - `TextRenderer`, `SubtitleDecoderFactory` and `DefaultTrackSelector`.

Nothing was built or run for it. The details of the last round are in `.superpowers/sdd/m3/parsers-fixes-review.md`.

The device results below are the controller's, not this check's:

- **At `2fcc504`,** on an emulator against the stand-in server, the on-device suite passed 21 of 21 (including `PlayerSmokeTest`). By hand:
  - Resume and Play from start, the landscape player with picture, title, controls and seek bar, and the return to portrait worked;
  - SRT and positioned/italic ASS subtitles were drawn;
  - **every stream, subtitle and progress request carried the `Authorization` header**, and a Stopped report was sent;
  - Skip intro, the Up next countdown and autoplay to the next episode worked.
- **At `7a76d29`:** the on-device suite passed 21 of 21. By hand on an emulator, these worked: playback, SRT and ASS subtitles, the lock (Back included), the sheets, font scale 2.0, the pause when the screen goes off, the offline Play message, Skip intro, Up next and autoplay. Not walked: TalkBack, an account switch mid-film, a tablet.
- **At `4243b5d`:** the on-device suite passed 21 of 21. By hand on an emulator against the stand-in server, these worked:
  - playback;
  - SRT and styled ASS subtitles;
  - the lock (Back included) and the sheets;
  - font scale 2.0;
  - the pause when the screen goes off;
  - the offline message, and Try again after an outage;
  - Skip intro, Up next and autoplay.
  - Not walked: TalkBack, an account switch mid-film, a tablet, headset buttons, mobile data.
- **At `c22aca2`:** the subtitle readers are new, so **the subtitle screen check must be repeated**: SRT (italic, `{\an8}`), WebVTT (line, position, align), and styled ASS (`\pos`, colours, layers, the effects notice).
  - **Controller's result at `c22aca2`:** the app's own readers drew the styled ASS (a positioned italic sign) and the SubRip cues correctly. WebVTT settings and the effects notice were not reported.
- **At `fb93881`:**
  - the full gate passed, with 2,656 unit tests and 0 failures;
  - the on-device suite passed 21 of 21.
  - On an emulator against the stand-in server, a 1.2 MB MKV was played. Its built-in ASS track holds one dialogue line of 1,000,000 `{` characters. The MKV played from start to end, and the app's CPU was at 0% afterwards. This is M3's input, run on a device.

---

## 1. Summary verdict

**Signed off for Milestone 3 at `fb93881`, on code reading.** No finding is open at Critical, High or Medium. What is left is two small Low residuals (under L2 and M3), the Informational items, and the device checks in section 5.

- **M1 is fixed:** no Media3 parser reads a subtitle file.
- **M2 is fixed:** each cue keeps at most 32 styled runs, so building and drawing its text costs a constant amount. The parse checks for cancellation every 50 cues as well as every 1,000 lines.
- **M3 is fixed:** no Media3 text parser can be built for anything the player loads, whether progressive files or HLS. Text tracks inside a film pass through unread. The controller's emulator run with a hostile MKV agrees.
- **L1 is fixed.**
- **L2 is fixed:** every load starts paused while the app is stopped, however it started.
  - **Residual:** a stop that comes in the first moments of opening the player, before its controller exists, is not yet passed on.
- **I7 is fixed:** an ASS header line is limited to 4,096 characters before it is split.
- **Still unproven:** a real phone and Ben's server (section 5).

| Area | Verdict |
|---|---|
| Tokens on the wire, in URLs, logs and errors | **Strong.** Every media, playlist, segment, key and subtitle request goes through the account's own client, to the verified address only, with the token in `Authorization` only. Server paths are cleaned of credentials. Media3's logging is off |
| The media route guard | **Strong.** GET or HEAD only, same scheme and authority, under the address's own base path and `/Videos/`, checked again when each request is sent; no redirects; stale after a sign-out, a network change or a settings change |
| Untrusted server data | **Good.** PlaybackInfo and segments are bounded and validated. Subtitle files are read by the app's own SubRip, WebVTT and ASS readers, which are linear, with every per-cue cost capped (M1, M2 fixed). Text tracks inside a film are passed through unread, never given to Media3's text parsers (M3 fixed). Media3 still parses containers, picture subtitles and HLS playlists (I2), and HLS playlists have no size limit of the app's own (I3) |
| The shared-tablet session rule | **Strong.** Every exit path reaches one `end()`, which releases the engine and the media session at once and drops the title, queue and position. A stop is sent for the old profile only if it is still signed in, and only as itself |
| What other apps and devices see | **Good.** No notification, no service, no exported component. Bluetooth devices and notification-listener apps see the title and series line while a film is open, and a fixed media id, never the item id (L1 fixed). A headset cannot start, re-open or rewind the film while the app is stopped, and every load then starts paused (L2 fixed; one narrow residual while the player is opening) |
| Local storage, backup, removal | **Good.** One new Room table (per profile, cascaded) and one device-settings file; both excluded from backup |
| Build and supply chain | **Good.** Five first-party Media3 artefacts and their declared dependencies, sha256 only; `WAKE_LOCK` removed from the merged manifest |

---

## 2. S1–S13 (as they apply to Milestone 3)

| ID | Requirement (short) | Status | Evidence (code at `7a76d29`; S11 at `fb93881`) | Proved by |
|---|---|---|---|---|
| **S1** | Tokens and headers encrypted at rest | **Met (unchanged)** | No new secret is stored. `track_choices` holds languages and flags; `playback_prefs` holds subtitle appearance and the default quality; `SavedStateHandle` holds server id, user id, item id and position | `TrackPreferencesTest`, `PlayerViewModelTest.theOwnerAndThePositionAreSavedButNoTitle` |
| **S2** | No backup | **Met (unchanged)** | `allowBackup="false"`; both rule files exclude every domain, so `track_choices` (database) and `playback_prefs` (sharedpref) are covered | `ManifestSecurityTest`, `verifyReleaseManifest`. **Not done:** a real backup attempt |
| **S3** | HTTPS by default; http only by consent | **Met (unchanged)** | The media route uses the account's own client, so `CleartextGuard` applies to every media request | `MediaRouteTest.aPlainHttpServerWithoutConsentIsCleartextBlocked` |
| **S4** | Trust on first use; pins | **Met** | Same client (`ServerClients.clientFor`), so the pin applies to media | `MediaRouteTest.thePinnedSelfSignedServersMediaRequestUsesThePinAndAChangedCertificateFails` |
| **S5** | Client certificates | **Met (device unproven)** | Same client path | M1 tests |
| **S6** | Token only in `Authorization`, only to its server; never in a URL, log, error or `toString()` | **Met** | `ServerApi.mediaRoute` (`ServerApi.kt:119-156`); `GuardedCallFactory` (`MediaRoute.kt:55-149`); `MediaPaths.checkServerPath` strips `api_key`, `ApiKey`, `X-Emby-Token`, `X-MediaBrowser-Token`, `X-Emby-Authorization` in any case or encoding; `AuthInterceptor` strips them again. `MediaRoute`, `StreamSource`, `CueTimeline`, `PlayerUiState` print nothing | `MediaRouteTest` (`aMediaGetCarriesTheTokenAndTheServersCustomHeaderOnTheVerifiedAddress`, `theRequestUrlNeverCarriesACredentialEvenIfTheSourceQueryHadOne`, `aRedirectIsReturnedToTheCallerAndNeverFollowed`, `nothingInARoutePrintsAnAddress`), `MediaRouteRaceTest`, `MediaPathsTest`, `PlayerSmokeTest` (no `api_key`/`ApiKey` in any URL; header on every `/Videos/` request) |
| **S7** | No third-party hosts | **Met** | Only `OkHttpDataSource` over the route; the media session's artwork loader loads nothing; no `DefaultDataSource`, `DefaultHttpDataSource` or `DataSourceBitmapLoader` | `Media3BoundaryTest`, `ExoPlayerEngineTest.theMediaSessionLoadsNoArtwork` |
| **S8** | App lock, recents hiding | **N/A until M7 (decision kept)** | `FLAG_SECURE` is not set on the player (plan). The recents preview and screenshots can show the film's picture | — |
| **S9** | Minimum permissions | **Met** | Media3's `WAKE_LOCK` is removed (`tools:node="remove"`); no `FOREGROUND_SERVICE`; the screen stays on through `View.keepScreenOn` | `verifyReleaseManifest` (run by the controller) |
| **S10** | Only the launcher exported | **Met** | No service, receiver or provider added. Media3's session registers its media-button receiver at run time as **not exported** (`Util.registerReceiverNotExported`, read in `MediaSessionLegacyStub` 1.9.2) | `verifyReleaseManifest`; source reading |
| **S11** | Responses untrusted | **Met (device check of M3 on an emulator only)** | See section 3, check 5 | `PlaybackGatewayHostileTest`, `MediaPathsTest`, `SubtitleCuesTest`, `SubtitleHostileTest`, `SubtitleFormatsTest`, `Media3BoundaryTest`, `PictureSubtitlesTest`, `PlaybackDecisionTest`; the controller's hostile-MKV run at `fb93881` |
| **S12** | Pinned, checksum-verified dependencies | **Met** | `media3 = "1.9.2"`. 51 new sha256 entries, no trusted keys, no `trust` rules. New groups: `androidx.media3` (11 artefacts), and the declared dependencies `guava` 33.3.1-android, `androidx.media:media` 1.7.0, `recyclerview` 1.3.0, `customview` 1.0.0, plus POM-only `collection`, `exifinterface`, `kotlin-bom`, `kotlin-stdlib-common` used for version resolution. Recorded under "Version checks" | Diff of `gradle/verification-metadata.xml` |
| **S13** | Security check each milestone | **Met** | This document | — |

---

## 3. The checks

| # | Check | What was checked | Evidence | Verdict |
|---|---|---|---|---|
| 1 | **Where the token can travel: media** | Progressive files, HLS playlists, segments and keys; how Media3 builds requests | One data source: `DefaultMediaSourceFactory(OkHttpDataSource.Factory(calls))`, where `calls` hands each request to the current item's route (`ExoPlayerEngine.kt:61-68` at `fb93881`, the one `mediaSources`). A playlist line pointing at another host, scheme, port, user-info, path outside `/Videos/`, or a `data:` or `file:` URI is refused before anything is sent (OkHttp cannot open a non-http scheme) | Sound |
| 1 | **Where the token can travel: server-supplied paths** | `TranscodingUrl` | Must be relative, one leading `/`, first segment `videos`, at most 2,048 characters, no white space, control, backslash or `#`; every segment decoded once and unreserved; credential names removed. The app never builds a transcode URL | Sound. `MediaPathsTest.refusesEncodedAndDisguisedEscapes`, `PlaybackGatewayHostileTest.anAbsoluteTranscodingUrlIsRefusedAndItsReasonsWithIt` |
| 1 | **Where the token can travel: redirects** | Media, subtitle and report calls | No client follows redirects. A 3xx comes back to Media3 as a bad status and fails playback | Sound. `aRedirectIsReturnedToTheCallerAndNeverFollowed` |
| 1 | **Where the token can travel: subtitles and images** | Subtitle files; artwork in the player and media session | Subtitle files through `ServerApi.getBytes` on a path the app builds (`MediaPaths.subtitleFile`), 8 MiB. The player loads no images; the media session's bitmap loader refuses everything | Sound |
| 1 | **Where the token can travel: a route that goes stale** | Sign-out, network change, settings change, LRU | The guard re-runs at `execute`/`enqueue` and for every clone. A settings save while the route is made is caught by reading again (`ServerApi.kt:135-139`) | Sound. `MediaRouteRaceTest` (6 cases) |
| 2 | **Logs and error messages** | Tokens, URLs, hostnames, titles, positions | `Log.setLogLevel(LOG_LEVEL_OFF)` before anything plays (`ShoalwatchApp.kt:20`). `PlaybackException` is mapped by `errorCode` and the type of a route refusal only (`MediaItems.failureOf`). Four new log lines, all fixed words or an error kind; throwables are logged by class name only, in debug builds only. `require`/`check` messages are fixed ("Invalid media id.", "No media route.", "Engine released."). The subtitle readers throw only their own fixed messages ("Unreadable subtitles.", "Too many subtitles.", "Stopped.", "Unsupported subtitle format."), and any other failure becomes "Unreadable subtitles." (`SubtitleFiles.guarded`); nothing from a file is put in a message | Sound |
| 3 | **The media session and what leaves the app** | Lock screen, notifications, Bluetooth, Assistant, other apps | Activity-scoped `MediaSession`, no service, so **no notification and nothing on the lock screen**. Controllers may play, pause, seek and read the title only; they cannot set items, speed or tracks (`ControllerRules`). The session drives a forwarding player that refuses `play`, `playWhenReady = true`, `prepare` and `seekToDefaultPosition` while the app is stopped (`ExoPlayerEngine.sessionPlayer`). Every controller route to play reaches it (media button, legacy `onPlay`, Media3 controller), so a headset can neither start, re-open a failed film nor rewind an ended one. The media id is the fixed `shoalwatch-video` (`MediaItems.MEDIA_ID`). The legacy session publishes no media URI (`requestMetadata.mediaUri` is never set), and Media3 sends media items to other processes without their URI. The title and series name are visible to Bluetooth devices and notification-listener apps while a film is open, and released at once on `end()` | Sound. L1 fixed. **L2 fixed** at `fb93881`: the controller's one `engine.load` loads paused while the app is stopped, so a fallback or re-ask cannot start sound behind the lock screen; one narrow residual (see L2). `MediaItemsTest.theMediaSessionIsNeverToldTheItemId`, `ExoPlayerEngineTest.aMediaSessionControllerCannotStartPlaybackWhileTheHostIsStopped`, `HostStoppedTest` |
| 4 | **The shared-tablet session rule, every exit path** | Account switch, sign-out, server removed, process death and restore, Up next in flight, a sheet open, reports in flight | **Switch:** `ActivePlayback.sessionChanged` ends the controller synchronously on the main thread; the stop goes as the old profile only if it is still signed in. **Sign-out / account removed / server removed:** the store hooks call `activePlayback.end` and `playbackReports.forget`; nothing is sent. **Up next:** `end` cancels the countdown with `work`. **Sheet open:** `leave()` drops it and replaces the state with `CLOSED`. **Reports in flight:** a stop whose send overlaps a forget of its own account is neither kept nor saved. **Process death:** saved state holds ids only; restored under another profile, nothing plays and the screen leaves. **Two players at once:** the first is ended before the second registers. **A screen holding an old session:** `begin` refuses anything but the active session, and refuses before the active session is known | Sound. `ActivePlaybackTest` (13), `PlaybackControllerTeardownTest`, `ServerPlaybackReportsTest`, `PlayerViewModelTest.restoredForAnotherSessionPlaysNothingAndLeaves`, `SessionChangeNavigationTest` |
| 5 | **Untrusted server data** | Ids, sizes, numbers, segments, playlists, subtitle files, text tracks in the stream | **Ids:** item ids `JellyfinId`; media source, play session and ETag `[A-Za-z0-9]{1,64}`. **Sizes:** at most 50 sources, 200 streams, 200 segments, 32 reasons; labels 120 characters through `displayText`. **Numbers:** range-checked before narrowing: runtime and segment ticks at most 48 hours, bitrate at most 10⁹, sides at most 16,384, indexes at most 10,000, channels 1-32. **Subtitle files** (at `fb93881`): 8 MiB, parsed off the main thread and waited for at most 10 s. They are read by the app's own readers (`engine/SrtFile`, `VttFile`, `AssFile`, `SubtitleScanner`), never by Media3's text parsers or `Html.fromHtml`; `Media3BoundaryTest` bans both in `src/main`. The file is decoded once, with control characters removed. Each line is read once, with no regular expression of the app's own and no recursion, and every token is at most 64 characters. Caps: 50,000 cues (refused over it); 2,000 characters and 16 lines per cue (the rest of that cue dropped); 32 styled runs per cue (later style changes ignored, closes M2); 4,096 characters per ASS header line, checked before it is split (closes I7); 256 ASS styles; 16 cues and 4,000 characters at an instant; 48 hours. An ASS events `Format:` must end with Text, so no other field can hold a comma (closes M1). The parse asks whether it has been given up every 1,000 lines, every 50 cues and every 1,000 timeline steps, so the 10 s limit stops the work, not just the waiting. Running out of memory, a stack overflow and any other failure on the parsing thread become "unreadable". **Text tracks inside a film** (closes M3): the engine's one media source factory is given `engine/PictureSubtitles`, which supports only PGS, VobSub and DVB. Media3 hands it on to the progressive extractors and to HLS (its extractor factory, WebVTT segments included), so a text track gets no parser and is passed through unread under its own type. The text renderer is off unless a picture track is chosen (`ExoPlayerEngine.applyTracks`); if it were given a text track, Media3's legacy decoding is off, so it would fail rather than parse. **Playlists:** parsed by Media3, with no limit of the app's own (I3) | Sound |
| 6 | **Manifest, permissions, `FLAG_SECURE`** | Merged manifest rules against what Media3 brings | `verifyReleaseManifest` requires exactly `INTERNET`, `ACCESS_NETWORK_STATE` and the androidx dynamic-receiver permission, and only the launcher exported. Media3's `WAKE_LOCK` is removed in the source manifest; no service, receiver or provider is declared by the five artefacts used. `FLAG_SECURE` not set, as decided | Sound. The release build and its manifest check are run by the controller |
| 7 | **Local storage** | What is stored, who can read it, removal | **Room `track_choices`:** (server, user, series) → languages and flags, foreign key to `accounts` `ON DELETE CASCADE`; only the session's own row is read; a write after the account went is refused by the key and dropped. **`playback_prefs`:** subtitle appearance and the default quality (`default_quality`), both device settings. **Pending stop:** memory only. **`SavedStateHandle`:** ids and a position, kept by the system for the task, not written by the app | Sound. `TrackPreferencesTest`, `MigrationTest` (2 to 3) |
| 8 | **Release build (R8)** | What differs when minified | Minify and resource shrinking are on. At `c22aca2` the subtitle code no longer filters spans by class name (`SubtitleFiles.allowed` is gone): it builds only Android's own bold, italic, underline and colour spans. No name-based check of the app's is left | Informational. A minified film with subtitles is still to be walked |
| 9 | **Stand-in server `tools/mockjelly`** | Bind address, secrets, files | Listens on `127.0.0.1` by default (Milestone 2 L3, fixed). Serves the committed test media with checksums; logs only whether a token was present | Sound |

---

## 4. Findings

### Critical

None.

### High

None.

### Medium

#### M1 — An ASS file could make Media3 do unbounded work — fixed at `c22aca2`

- **Was (at `7a76d29` and `4243b5d`):**
  - The app checked or re-wrote ASS files and then handed them to Media3's `SsaParser`.
  - Four ways round the work budget were fixed at `4243b5d`.
  - One field (`Style`, when the `Format:` put it after Text) still carried commas and unclosed braces into Media3's text. That was about 10¹² pattern steps from one 1 MB line.
- **Now:** no Media3 parser reads a subtitle file.
  - The app's own `AssFile` reads ASS and SSA in one pass with no pattern matching.
  - An events `Format:` must have Start, End and Text, with Text last, or the file is refused. So the Style field is always one comma-free token.
- **Tests:** `SubtitleHostileTest.anAssFormatWithStyleAfterTextIsRefused` and `aMegabyteOfBracesInTheAssStyleFieldIsOneTokenAndTheTextStillShows`. Each also reads a normal file of the same shape.

#### M2 — SubRip and WebVTT files could make the parser do unbounded work — fixed at `fb93881`

- **Was (at `4243b5d`):**
  - SubRip went through `Html.fromHtml`, quadratic in unclosed tags.
  - WebVTT was handed to Media3 as sent: its settings pattern, nested `<ruby>` and `STYLE` rules were each quadratic.
  - Nothing could stop either once started.
- **Fixed at `c22aca2`:**
  - The app's own `SrtFile` and `VttFile` read both formats.
  - Tags are depth counters, and a tag counts only when its closer is on the same line, so each character is read at most twice. Settings are split at spaces, with tokens of at most 64 characters. `STYLE`, `NOTE` and `REGION` blocks are skipped unread.
  - The parse checks every 1,000 lines whether it has been given up.
  - Each input from the last round is a test that must finish within 2 s, together with a normal file of the same shape (`SubtitleHostileTest`).
- **Was still open at `c22aca2` (bounded):** building a cue's styled text was quadratic in its styled runs.
  - Android's `SpannableString.setSpan` looks through every span already set for each new one.
  - Tags like `<u>a</u>b`, repeated, gave one cue up to 2,000 runs (about 5,000 spans).
  - About 900 such cues fit in 8 MiB.
  - Cancellation was asked only every few hundred such cues.
  - With "Use the file's own styling" off, `SubtitleView` removed those spans one by one on the main thread.
- **Fixed at `fb93881` (`01e4ad3`):**
  - **Fewer spans:** at most 32 styled runs per cue are kept. Later style changes in that cue are ignored and the text is kept. Bold and italic share one span. So a cue has at most about 70 spans, and building or clearing them costs a constant amount.
  - **More frequent checks:** the parse also asks whether it has been given up every 50 cues.
  - **Less per frame:** at most 4,000 characters are handed to the view in one frame.
  - **Range:** every span is clamped to the text, and set only when it is not empty, so none can be out of range.
- **Tests:**
  - `SubtitleHostileTest.nineHundredCuesOfAlternatingStylesToTheSizeLimitAreQuickAndCancellable` took 16,310 ms before the fix and 689 ms after, by the implementer's run;
  - `oneFrameHandsTheViewAtMostFourThousandCharacters`;
  - `aNormalStyledCueKeepsItsSpansExactly`.
- **Left (cosmetic):** in a cue with more than 32 style changes, the rest of the cue takes the last kept style.

#### M3 — Text tracks inside a directly played film were parsed by Media3 — fixed at `fb93881`

- **What it was (found at `c22aca2`):**
  - The engine builds `DefaultMediaSourceFactory` with its defaults. In Media3 1.9.2 those parse subtitles while the file is being read: `parseSubtitlesDuringExtraction = true`, and `DefaultExtractorsFactory` has a `DefaultSubtitleParserFactory` with text-track transcoding on. Both were read in the bytecode.
  - So every text track inside a directly played MKV, MP4 or WebM goes through Media3's `SsaParser`, `SubripParser` + `Html.fromHtml` or `WebvttParser` on the loading thread. This happens whether or not the track is selected.
  - `ExoPlayerEngine.applyTracks` only ever gives ExoPlayer's text renderer a picture track, but by then the parse has already happened.
  - ARCHITECTURE's sentence that in-stream text tracks are never selected is true, but it does not make them safe.
  - Subtitle **files** are not affected: no side-loaded subtitle is given to Media3.
- **Input:** a directly played MKV whose ASS track has one event of about 1 MB of `{` with no `}`. That is the M1 input again, now inside the film. The other quadratic inputs from M2 work in the same way, inside SubRip and WebVTT tracks.
- **Who can do it:** as M2. A hostile server can label any file as direct play. MKVs with ASS tracks are common in honest libraries too.
- **Effect (then):**
  - The loading thread would run for hours, so the film stops buffering.
  - The battery drains and the phone heats.
  - Closing the player does not stop the thread.
  - No data leaks.
- **Fixed at `fb93881` (`80d3b62`):** the engine's one `DefaultMediaSourceFactory` is given `engine/PictureSubtitles` as its subtitle parser factory. It supports PGS, VobSub and DVB only, and builds those three parsers directly. The 1.9.2 bytecode shows where it reaches:
  - **Progressive files (MKV, MP4, WebM, TS):** `DelegateFactoryLoader` hands the factory to the extractors factory, and from there to the Matroska, MP4 and TS extractors.
  - **No parser for text:** `SubtitleTranscodingTrackOutput` builds a parser only when the factory supports the format. A text track gets none, and its samples pass through unread under their own type.
  - **HLS (transcodes):** `DelegateFactoryLoader` hands the factory to every media source factory it loads, HLS included. `HlsMediaSource.Factory` passes it to `DefaultHlsExtractorFactory`, which uses it for TS, fragmented MP4 and WebVTT segments.
  - **WebVTT renditions declared in a playlist:** they keep `text/vtt` and are never fetched while text is off (only enabled renditions load). If one were fetched, its cues would also pass through.
  - **Downstream:** the text renderer is off unless a picture track is chosen. If it were given a text track, Media3's legacy decoding is off, so `TextRenderer` throws rather than builds a decoder: playback fails, nothing is parsed.
- **Tests:**
  - `PictureSubtitlesTest`: every text type is refused and no parser can be made for it; the three picture types are parsed; the real engine's factories hold `PictureSubtitles`, read by reflection.
  - `Media3BoundaryTest`: it also bans `DefaultSubtitleParserFactory`, `DefaultExtractorsFactory`, the parse-during-extraction and transcoding switches, legacy decoding and the other text parsers. `PictureSubtitles.kt` is its one, narrow exception. Removing `.setSubtitleParserFactory(PictureSubtitles)` fails it.
- **On a device (the controller's):** at `fb93881`, on an emulator, the 1.2 MB MKV with 1,000,000 `{` in its ASS track played from start to end with the app's CPU at 0% afterwards.
- **Left (Low, carry):**
  - **The window between two loads:** the picture-track choice is applied before a new item's tracks are known. If the old override does not match the new file, Media3 may pick a default text track for that moment. Playback would then fail safely ("Playback failed", Try again), with nothing parsed. It is probably closed by the empty track list that a new item reports first, but that has not been proved.
  - **Fix:** turn text off in `load()` before the new item is set.
  - **Not yet tested:** HLS has no test of its own.

### Low

#### L1 — The media session told Bluetooth devices and notification-listener apps the Jellyfin item id — fixed

- **Was:** `MediaItems.build` set `mediaId` to the item id.
- **Now:** every item has the fixed media id `shoalwatch-video` (`MediaItems.MEDIA_ID`). Neither the metadata bundle nor the request metadata holds the item id, and `requestMetadata.mediaUri` is unset.
- **Test:** `MediaItemsTest.theMediaSessionIsNeverToldTheItemId`.
- The title and series line stay; they are what a headset display is for.

#### L2 — The film could play in the background while the app is stopped — fixed at `fb93881`, one narrow residual

- **Fixed (final review N-4, `4243b5d`):** a headset, Bluetooth, the Assistant or any other media-session controller can no longer start playback while the app is stopped.
  - The session drives a forwarding player that refuses `play` and `playWhenReady = true` until the app is started again (`ExoPlayerEngine.sessionPlayer`, `PlaybackController.onHostStopped` and `onHostStarted`).
  - Every controller route to play reaches that player, and the refusal is cleared on every start, so nothing is left blocked.
- **Fixed at `c22aca2` (`4804c15`):** that player also refuses `prepare` and `seekToDefaultPosition` while the app is stopped. Media3 runs these before `play`, so a headset press could re-open a failed film or rewind an ended one. Test: `ExoPlayerEngineTest`.
- **Fixed at `c22aca2` (`6f679f8`):** a load still in progress when the app stops (an open, or a quality or track change) now starts paused, as the sleep timer already did. Tests: `HostStoppedTest.anOpenWhoseAnswerArrivesAfterTheHostStoppedStartsPaused` and `aQualityChangeWhoseAnswerArrivesAfterTheHostStoppedStaysPaused`.
- **Was still open at `c22aca2`:** a load that *started* after the app stopped still loaded with "play". Such loads are:
  - the fallback to a conversion after a playback error;
  - a re-ask after a network failure;
  - the audio check when the stream gets ready;
  - a burn-in re-ask.
- **Fixed at `fb93881` (`a29887e`):** the controller keeps whether the app is stopped. Its one `engine.load` (`PlaybackController.kt:256`) uses `playWhenReady = !(startPaused || hostStopped)`, so every load starts paused while the app is stopped, however it started.
  - The flag is cleared on every start of the app.
  - A rotation is neither a stop nor a start.
  - A new player screen gets a new controller with the flag clear.
  - After returning, the user's Play plays.
  - Tests:
    - `HostStoppedTest.aFallbackAfterTheHostStoppedLoadsPausedAndPlaysOnceTheUserSaysSo`;
    - `aReResolveAfterTheHostStoppedLoadsPaused`;
    - `aLoadAfterTheHostStartedAgainPlaysAsUsual`.
- **Residual (Low, carry):** the ViewModel passes a stop to the controller only once the controller exists.
  - **When:** if the screen goes off in the first moments of opening the player, before the signed-in profile has been read.
  - **Effect:** the first load would still play.
  - **Fix:** the ViewModel remembers the stop and passes it to the new controller.
- **Not done:** a check with a real headset, and screen off just before a fallback on a real phone.

### Informational

- **I1 — Recents preview and screenshots.** `FLAG_SECURE` is not set (decision kept). The app switcher can show a frame of the film last watched. S8 (Milestone 7) covers it.
- **I2 — Media3's parsers run on server bytes in the app's own process:** MP4, Matroska, MPEG-TS, HLS playlists, and PGS, VobSub and DVB pictures.
  - Since `c22aca2`, not on subtitle files.
  - Since `fb93881`, not on SubRip, WebVTT, SSA, tx3g or TTML tracks inside a film either (M3): those pass through unread.
  - Kept current by pinning Media3 and updating it (Milestone 8). No native code was added.
- **I3 — A hostile server can make the player fetch large media from its own `/Videos/` paths,** and serve a very large HLS playlist (the app sets no playlist limit of its own; unchanged at `c22aca2`). Same server, same profile; the token goes nowhere new.
- **I4 — The pending stop lives in memory** and is lost if the process dies (Milestone 5 makes it durable).
- **I5 — Process-death restore fails closed.** If the first active-profile value arrives before saved sign-ins are loaded, the restored player closes rather than resumes. Safe; a device check.
- **I6 — Plain-http residual risk unchanged** from Milestone 2 (M2 there): media to a consented http home address carries the token in the clear on that network.
- **I7 — Memory while reading a subtitle file (fixed at `fb93881`).**
  - The file is decoded once: at most about three times its size, with no re-encoding.
  - **Was:** an ASS `Format:` line with millions of fields was split into millions of small strings, roughly 150 MB for a few seconds.
  - **Now:** every ASS header line (`[Script Info]`, style lines, any `Format:`) over 4,096 characters is refused before it is split. A `Dialogue:` line is text and is split only into its format's fields. Test: `SubtitleFormatsTest.anOverLongAssFormatOrHeaderLineIsRefused`.
  - Running out of memory is still caught only on the parsing thread, but no header line can now cause that.

---

## 5. Unproven until a real device or Ben's Jellyfin 12.4 server

- [ ] **The new subtitle readers on a screen (`c22aca2`):**
  - Done on an emulator by the controller: styled ASS (a positioned italic sign) and SubRip cues.
  - Still to see:
    - SRT `{\an8}` at the top;
    - WebVTT `line`, `position` and `align`;
    - ASS colours and layer order;
    - the effects notice (now also for rotation);
    - a heavily styled cue (over 32 style changes);
    - the delay;
    - "Use the file's own styling" on and off;
    - a real phone.
- [ ] **Header-only auth on HLS:** Ben's server accepts `Authorization` on `TranscodingUrl`, every variant playlist and every segment, with no `api_key`/`ApiKey` in any URL (the stand-in server proves only that the app sends it)
- [ ] **Real transcoding and burn-in:** a quality change and a fallback produce a working HLS transcode; "Let the server draw these subtitles" burns in an ASS and a PGS track; no conversion job is left running after a stop or the first re-ask
- [ ] **`TranscodingUrl` shape on 12.4:** relative, under `/videos/`, behind a reverse proxy with a base path; no proxy redirects media
- [ ] **HDR and Dolby Vision:** an HDR10 film direct plays on an HDR screen and is converted on an SDR one; a Dolby Vision 8.1 film plays; a profile 7 film is converted, never black
- [ ] **`mov_text` to `.srt`:** an MP4's built-in text subtitles fetched as SubRip from the server
- [ ] **An MKV with built-in ASS and PGS tracks (M3, fixed at `fb93881`):**
  - Done on an emulator by the controller: a hostile ASS track (1,000,000 `{`) played to the end with the CPU at 0% afterwards.
  - Still to see:
    - on a real phone with Ben's server, an honest MKV direct plays;
    - its PGS track still draws (picture subtitles from the stream, after this change);
    - its ASS track is fetched and drawn as a file;
    - Up next from one such episode to another with a PGS track chosen starts cleanly (the window between two loads, M3);
    - a transcode (HLS) with subtitles plays.
- [ ] **Headset and Bluetooth media session:** play/pause and seek work; the title shown on a car or headset display is gone the moment the player closes or the profile changes; a press while the app is stopped does nothing (refused in code since `4243b5d`, re-open and rewind since `c22aca2`; not yet tried with a real headset)
- [ ] **Account switch with two real users** mid-play: the player closes at once; Home, recents, the notification shade and a headset show nothing of the first profile; the stop is sent as the first profile only
- [ ] **TalkBack** over every control, the Up next count (announced once, again at 3 s), the lock and its Back handling
- [ ] **Android 10** (API 29): playback, the track table migration and the `SQLite 3.22` rules
- [ ] **Font scale 2.0** in landscape on a small phone; the quality, speed and sleep sheets scroll (walked on an emulator by the controller at `7a76d29` and `4243b5d`; a small phone not yet)
- [ ] **Screen off and Home** pause playback; it comes back paused (screen off walked on an emulator by the controller at `7a76d29` and `4243b5d`). Not yet: Home, screen off while a film is still loading (L2, in code since `c22aca2`), and screen off just before a fallback (L2, in code since `fb93881`)
- [ ] **Offline Play** shows the offline message with Try again (walked on an emulator by the controller at `4243b5d`, including Try again after an outage; a real phone and a real server not yet)
- [ ] **Default quality:** on mobile data with nothing chosen, a film starts at 8 Mbps; a quality chosen in the sheet is the next film's start (not walked)
- [ ] **Process death** in the background (`adb shell am kill`): the player comes back on the right episode, or closes cleanly (I5)
- [ ] **Release (R8) build:** a film plays minified, with SubRip, WebVTT and ASS subtitles (check 8)
- [ ] **Backup:** neither `track_choices` nor `playback_prefs` leaves the phone
- [ ] **Carried:** Milestone 2 section 5 and Milestone 1 section 4

---

## 6. What changed since Milestone 2, in one line each

- **New:** a second token path, the media route: the account's own client, guarded to GET/HEAD under `/Videos/` on the verified address, checked again at send time.
- **New:** Media3 1.9.2 (five first-party artefacts) parsing server bytes in-process; its logging off.
- **New:** the app's own SubRip, WebVTT and ASS readers for subtitle files (`c22aca2`); Media3's text parsers and `Html.fromHtml` banned from the app's code.
- **New:** Media3 given only the picture-subtitle parsers (PGS, VobSub, DVB), so text tracks inside a film are never parsed by it (`fb93881`).
- **New:** an activity-scoped media session with play, pause and seek only, and no artwork.
- **New:** a per-profile Room table of audio and subtitle choices, cascaded with the account.
- **New:** progress reports and one pending stop in memory, never sent for a profile that has gone.
- **New:** `ActivePlayback`, which ends playback the moment the active profile changes or goes.
- **Unchanged:** the manifest's components and permissions (Media3's `WAKE_LOCK` removed).

---

## 7. Status after the check (15/11/2026, branch `milestone-3` at `fb93881`)

**Milestone 3 is signed off on code reading.** Nothing is open at Critical, High or Medium. The items to carry and the device checks are listed below.

| Finding | Status |
|---|---|
| **M1** ASS file work through Media3 | **Fixed** (`c22aca2`). The app reads ASS itself; Text must be the last events field, so Style holds no comma (`AssFile`) |
| **M2** SubRip and WebVTT unbounded work | **Fixed** (`fb93881`). The app's own readers, with at most 32 styled runs per cue (a constant cost to build and to clear on the main thread), 4,000 characters per frame, and a cancellation check every 1,000 lines and every 50 cues |
| **M3** text tracks inside a film parsed by Media3 | **Fixed** (`fb93881`). Only the PGS, VobSub and DVB parsers are given to Media3, for progressive files and HLS alike; text tracks pass through unread, and the text renderer would fail rather than parse one. On an emulator (the controller's run), a hostile MKV played to the end with the CPU at 0% afterwards. **Carry (Low):** turn text off at each new load, so that no default text track can be picked in the moment before the new tracks are known (it would fail safely); add an HLS test |
| **L1** item id on the media session | **Fixed** (a fixed media id, final review N-1) |
| **L2** play while the app is stopped | **Fixed** (`fb93881`). Controllers are refused, including re-open and rewind, and every load starts paused while the app is stopped. **Carry (Low):** a stop in the first moments of opening the player, before the controller exists, should be passed on to it. Then a device check with a headset |
| Informational I1 to I7 | Recorded. **I7 fixed** (ASS header lines limited to 4,096 characters before splitting). I4 is Milestone 5's, I1 is Milestone 7's; I3 (no HLS playlist limit) unchanged |

**Residual risks, even after the fixes above:**

- Media3's container and picture-subtitle parsers, and its HLS playlist parser, still run on server bytes in the app's own process (I2, I3).
- Running out of memory is caught only on the parsing thread.
- Up to 16 cues and 4,000 characters can be drawn at once; a crafted file can make the subtitle view busy, though not unsafe.
- The app's readers draw less styling than Media3 did (no SubRip font colours, no WebVTT CSS, ASS overrides only from the first block, nothing past 32 style changes in a cue); this is a choice, recorded in ARCHITECTURE.

**What is proved, and how:**

- **By code reading (this check):**
  - every finding above;
  - the Media3 1.9.2 behaviour, read in the bytecode of the cached libraries, not run.
- **By the controller, at `fb93881`:**
  - the full gate passed, with 2,656 unit tests and 0 failures;
  - the on-device suite passed 21 of 21;
  - the hostile-MKV run on an emulator.
- **By the controller, at `c22aca2`:** styled ASS and SubRip drawn correctly on an emulator.
- **Unproven until a real phone and Ben's Jellyfin server:** section 5. In particular:
  - PGS still drawn from the stream after M3's fix;
  - WebVTT placement;
  - a real HLS transcode with header-only auth;
  - headset presses while the app is stopped;
  - an account switch with two real users;
  - TalkBack;
  - Android 10;
  - the minified release build.

Found during the milestone and fixed in its review rounds (each re-reviewed by reading the code):

- two players alive at once crashed over the media session;
- a call made before a sign-out or network change was still sent;
- a settings save while a route was made could keep old settings;
- the media session's default artwork loader used a default HTTP source;
- the sleep timer and Up next raced;
- track changes made before the server answered were lost;
- subtitle files could make Media3's parsers run for hours (M1, M2; now read by the app itself, with every per-cue cost capped);
- text tracks inside a film went through the same Media3 parsers (M3; now passed through unread);
- a configuration change paused the film;
- the restored player opened the wrong episode;
- the media session shared the item id (L1);
- a headset could play, re-open or rewind while the app was stopped, and a fallback could start sound behind the lock screen (L2).
