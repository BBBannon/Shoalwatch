# Milestone 3 — Video Player Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Signed in and online, the user presses Play on a film or episode and it plays in a native Media3 player: resume from the saved position, direct play whenever the phone can really show the file (HDR included), otherwise a server conversion explained in plain words, a quality choice, audio and subtitle choice remembered per series, subtitles that look right (size, colour, background, delay; ASS styling as far as the phone can draw it, with an honest "let the server draw them" option), skip intro and credits, next and previous episode with a cancellable "Up next" countdown, speed, sleep timer, and headset controls. Progress goes back to the server without ever stopping the film, and nothing from one signed-in profile's playback is ever shown or sent as another.

**Architecture:** One new package, `data/playback`, owns every playback decision and is plain Kotlin wherever it can be (device profile, HDR policy, decision, URL rules, track choice, reporter cadence, controller state machine), with thin Android adapters behind small interfaces (`CodecSupport`, `DisplayHdr`, `PlayerEngine`). Playback starts with `POST /Items/{itemId}/PlaybackInfo` through `ServerApi` (the one authenticated call path) and the app never builds a transcode URL itself: it uses the server's `TranscodingUrl` after validating it. Media bytes go through a `MediaRoute` that `ServerApi` hands out: the account's own pinned, header-carrying OkHttp client (from `ServerClients`, so `CleartextGuard`, `AuthInterceptor`, `CustomHeaderInterceptor`, the pin and the client certificate all apply), wrapped in a `GuardedCallFactory` that refuses any URL outside that verified address's `/Videos/` path. Text subtitles are fetched as files through `ServerApi.getBytes` and drawn by the app's own cue timeline (so delay works both ways and styling can be overridden); picture subtitles (PGS, VobSub) are drawn by Media3 from the container, or burned in by the server when transcoding. Progress reports go through a new `PlaybackReports` seam next to `Playstate`, best-effort, with the final position also written to Room through `Playstate.recordStop`, so Milestone 5's outbox can replace the "not sent" path without touching the player. `ActivePlayback` ends playback the moment the active account changes or goes.

**Tech Stack:** as Milestone 2, plus AndroidX Media3 **1.9.2** (AAFin's proven version against Kotlin 2.2.21 / AGP 8.10.1 / compileSdk 35): `media3-exoplayer`, `media3-exoplayer-hls`, `media3-datasource-okhttp`, `media3-ui` (for `SubtitleView`, `CaptionStyleCompat` and `AspectRatioFrameLayout` only; no `PlayerView` controller), `media3-session` (an activity-scoped `MediaSession`, no service). **No native code** is added in this milestone unless the Task 1 spike recommends it and Ben approves (see "Decisions made"). Test media is generated once with the ffmpeg already installed on Ben's PC (`ffmpeg 8.1.2`, built-in test sources only) and committed with its commands and checksums.

## Global Constraints

- **Read first:** `docs/SPEC.md` (§3 rows 4, 5 and 9, §3.1 "Finished", §3.2, §3.5, §4, §6, §7 row 3), `docs/ARCHITECTURE.md` (all of it — layer rules, "Authenticated requests", "Network security model", token rules, "Tidy-up actions", UI conventions, "Browsing screens"), `docs/security/milestone-1-check.md` (and `milestone-2-check.md` once it exists), `research/android-client-flaws-research.md` (complaints 4, 10, 11, 12, 13, 14, 15, 16 and "Playback stop position sent as 0 on Bluetooth disconnect"), AAFin's `app/src/main/java/app/aafin/data/playback/` (`DeviceProfileBuilder`, `PlaybackDecision`, `PlaybackResolver`, `ProgressReporter`: SPEC §2 says port these by copying and adapting; no shared library).
- **API reference:** `docs/reference/jellyfin-openapi-12.4.0.json`. Every endpoint, parameter, field and enum value used MUST be checked against it; do not rely on memory of the Jellyfin API. Decode with `ignoreUnknownKeys`; treat every field as optional unless the reference marks it required. **The 12.4.0 reference documents no `/Videos/{itemId}/master.m3u8` (or any dynamic HLS) route**: the app must never construct one; it plays `MediaSourceInfo.TranscodingUrl` exactly as the server gave it, after `MediaPaths.checkServerPath` (Task 2).
- Package root `app.shoalwatch`; `minSdk 29`, `compileSdk`/`targetSdk 35`. Build with `JAVA_HOME=/path/to/jdk-17`. Verify with `./gradlew.bat clean testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease` (the release build runs `verifyReleaseManifest`). Gradle dependency verification is ON: after any dependency change run `./gradlew.bat --write-verification-metadata sha256 help testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease`, review the diff (only the expected `androidx.media3` group and its declared transitive dependencies may appear) and commit the metadata; record new versions under "Version checks" in ARCHITECTURE.md (must suit Kotlin 2.2.21, AGP 8.10.1, compileSdk 35: check each AAR's `aar-metadata.properties` `minCompileSdk <= 35`).
- **Security rules that bind every task:** only `HttpClientFactory` builds OkHttp clients (via `ServerClients`; `OkHttpClient.newBuilder()` counts as building one and is forbidden — wrap a `Call.Factory` instead); no client follows redirects; a token goes only to an address that `AddressSelector.awaitOnline(serverId)` has verified, and only in the `Authorization` header (never `api_key`/`ApiKey` in a URL: `AuthInterceptor` already strips them, and `MediaPaths` strips them from server-supplied paths too); every media, subtitle and image request uses the account's authenticated client, so custom headers, the client certificate and the pin apply; plain http only for a server the user explicitly allowed (`CleartextGuard`); never log, print or put in an exception message a token, header value, URL, hostname, item title or position; Media3's own logging is switched off (`androidx.media3.common.util.Log.setLogLevel(Log.LOG_LEVEL_OFF)` in `ShoalwatchApp.onCreate`) because its messages carry URIs; `PlaybackException` is mapped by `errorCode` only, never by message; the UI layer cannot reach tokens, URLs, `MediaRoute`, OkHttp or Media3 data-source types; server responses are untrusted (bounded sizes, ids validated with `JellyfinId`, media source and play session ids `[A-Za-z0-9]{1,64}`, display strings through `displayText`); no new permissions (no `WAKE_LOCK`, no `FOREGROUND_SERVICE`, no media service); no third-party hosts; the merged release manifest check must keep passing unchanged. `FLAG_SECURE` is NOT set on the player.
- **Session rule (shared tablet):** every playback object is owned by one `Session`. When `ServerCatalog.activeSession()` stops being that session, or `ServerStore` reports the account or server gone, playback stops and the engine is released at once, the `MediaSession` (and so its title and artwork metadata) is released, the queue, position and title are dropped from memory, and the screen leaves. Nothing from the old session (position, title, queue, pending report, error message) may surface under the new one. A final stop report for the old session is sent only if that account is still signed in (an account switch) and only with that account's own session.
- **Data rules:** track choices are per (server, user, series) in Room with a foreign key to `accounts` ON DELETE CASCADE (schema v3, explicit tested migration, no destructive fallback, schema exported, SQLite 3.22 rules from ARCHITECTURE "No SQL newer than SQLite 3.22"). Subtitle appearance and default quality are device settings (not secrets, not per user) in a private `SharedPreferences` file, as `DeviceIdentity` does.
- TDD with behavioural RED. Real OkHttp + MockWebServer for network code; in-memory Room for storage; virtual time for coroutines; fakes at the seams (`FakePlayerEngine`, `FakePlaybackGateway`, `FakePlaybackReports`, the existing `FakeLibrary`/`FakePlaystate`); Robolectric + Compose test for screens. No file over ~400 lines (`SourceShapeTest`). UK English, plain wording, all user-facing text in `strings.xml`. Typography and layout conventions from ARCHITECTURE.md "UI" (dyslexia-friendly: nothing under 13sp, left-aligned paragraphs); every control has a TalkBack label, a 48dp target, and works at font scale 2.0.
- Commits: conventional, each ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. ChangeLog line per user-visible change (`vX.Y — DD/MM/YYYY — plain English`, minor bump). Keep ARCHITECTURE.md accurate. Never edit `docs/SPEC.md`. **Never start an emulator, run connected tests or touch a device** — the controller does that. **Nothing is installed on Ben's phone without his explicit yes.**

---

## File structure (new and changed)

```
app/src/main/java/app/shoalwatch/
├── ShoalwatchApp.kt                         (modify: Media3 logging off)
├── AppGraph.kt                              (modify: playback singletons, teardown hooks)
├── core/AppError.kt                         (modify: PLAYBACK_NOT_ALLOWED, NO_COMPATIBLE_STREAM, TOO_MANY_STREAMS, PLAYBACK_FAILED)
├── data/api/
│   ├── ServerApi.kt                         (modify: postForJson, mediaRoute)
│   └── MediaRoute.kt                        (new: MediaRoute, GuardedCallFactory)
├── data/db/
│   ├── AppDatabase.kt                       (modify: v3, TrackChoiceDao)
│   ├── Migration2To3.kt                     (new)
│   └── playback/TrackChoiceEntity.kt, TrackChoiceDao.kt   (new)
├── data/library/
│   ├── Playstate.kt                         (modify: recordStop)
│   ├── PlaystateRepository.kt, PlaystateLocal.kt          (modify: recordStop)
└── data/playback/                           (new package)
    ├── PlaybackModels.kt                    PlayMethod, Quality, VideoRange, TranscodeReason, AudioTrack, SubtitleTrack,
    │                                        SubtitleKind, SubtitleDelivery, StreamSource, PlaybackRequest, PlaybackPlan,
    │                                        MediaSegment, SegmentKind
    ├── MediaPaths.kt                        direct-stream and subtitle paths, server-path validation (pure)
    ├── api/PlaybackDtos.kt                  internal DTOs (request body, response, device profile, reports, segments)
    ├── api/PlaybackGateway.kt (+Impl)       PlaybackInfo, reports, ping, segments, subtitle file
    ├── api/PlaybackMappers.kt               internal: response DTO -> validated models (S11 boundary)
    ├── DeviceCapabilities.kt                DeviceCapabilities, CodecSupport, DisplayHdr, AndroidDeviceCapabilities
    ├── HdrPolicy.kt                         which VideoRangeTypes may direct play, per codec (pure)
    ├── DeviceProfileBuilder.kt              the DeviceProfile sent to the server (pure)
    ├── PlaybackDecision.kt                  PlaybackInfo -> PlaybackPlan (pure)
    ├── PlaybackResolver.kt                  gateway + profile + decision + forced-transcode fallback
    ├── PlaybackReports.kt                   seam: PlaybackReports, PlaybackReport, ReportOutcome
    ├── ServerPlaybackReports.kt             seam implementation + PendingStops (in memory until M5)
    ├── ProgressReporter.kt                  when to report (start, every 10 s, pause, seek, stop, ping)
    ├── ActivePlayback.kt                    the one owner record; ends playback on session change
    ├── EpisodeNavigator.kt                  previous / next episode from the Library seam
    ├── TrackChooser.kt                      which audio / subtitle track to start with (pure)
    ├── TrackPreferences.kt                  remembered per series (Room)
    ├── SubtitleSettings.kt                  size, colour, background, edge, delay defaults (+ CaptionDefaults)
    ├── SubtitleCues.kt                      CueTimeline + SubtitleLoader (parse files with Media3's parsers)
    ├── SleepTimer.kt, UpNext.kt             small pure state machines
    ├── PlaybackController.kt                the player's state machine over PlayerEngine
    ├── PlaybackState.kt                     what the controller publishes (no URL, no token)
    └── engine/PlayerEngine.kt, ExoPlayerEngine.kt, MediaItems.kt   the Media3 adapter
└── ui/
    ├── nav/AppNav.kt, BrowseRoutes.kt       (modify: Routes.PLAYER, PlayerRoute)
    ├── detail/DetailScreen.kt, DetailViewModel.kt   (modify: Resume / Play from start)
    ├── present/PlayerText.kt                (new: plain-words mappers, pure)
    └── player/                              PlayerViewModel, PlayerUiState, PlayerScreen, PlayerControls, PlayerGestures,
                                             PlayerSheets, UpNextCard, SkipSegmentButton, VideoSurface, PlayerWindow
app/src/test/resources/jellyfin/playback/    PlaybackInfo fixtures (direct play, direct stream, transcode, refused, hostile)
app/src/test/resources/subtitles/            sample.srt, sample.vtt, styled.ass, effects.ass, bom-crlf.srt, hostile.srt
app/src/androidTest/assets/media/            clip.mp4 + CHECKSUMS.txt
app/src/androidTest/java/app/shoalwatch/ui/PlayerSmokeTest.kt
tools/mockjelly/mock_jellyfin.py, README.md, test_mock_jellyfin.py, media/ (clip.mp4, hls/, subtitles, CHECKSUMS.txt, make_media.ps1)
research/2026-11-styled-subtitles-spike.md
docs/security/milestone-3-check.md
```

---

### Task 1: Styled-subtitle spike, test media, and the stand-in server learns to play

SPEC §3.5 says the milestone begins with a spike. It decides the ASS/PGS path before any subtitle code is written, and it produces the media every later test uses.

**Files:**
- Create: `research/2026-11-styled-subtitles-spike.md`; `tools/mockjelly/media/make_media.ps1`, `tools/mockjelly/media/CHECKSUMS.txt`, `tools/mockjelly/media/clip.mp4`, `tools/mockjelly/media/hls/main.m3u8` + `seg0.ts`…`seg4.ts`, `tools/mockjelly/media/sample.srt`, `styled.ass`; `app/src/androidTest/assets/media/clip.mp4` (+ `CHECKSUMS.txt`); `app/src/test/resources/subtitles/*`.
- Modify: `tools/mockjelly/mock_jellyfin.py`, `tools/mockjelly/README.md`, `tools/mockjelly/test_mock_jellyfin.py`.

**Interfaces:** Consumes nothing in the app. Produces the stand-in routes below (paths exactly as the 12.4.0 reference, except the HLS files, which are served at whatever path the mock puts in its own `TranscodingUrl`):

| Route | Mock behaviour |
|---|---|
| `POST /Items/{itemId}/PlaybackInfo` | 401 without a token. Body `PlaybackInfoDto`. For the 3 playable mock items returns one `MediaSourceInfo` (`Id` = item id, `Container` `mp4`, `RunTimeTicks` 100000000, `SupportsDirectPlay`/`SupportsDirectStream` = the body's `EnableDirectPlay`/`EnableDirectStream`, `MediaStreams`: h264 video `VideoRangeType` `SDR`, aac audio index 1 `Language` `eng`, external SubRip index 2 `eng` (`IsExternal`, `IsTextSubtitleStream`, `SupportsExternalStream`), external ASS index 3 `jpn`, `HasSegments` true). When `EnableDirectPlay` is false: `TranscodingUrl` `/videos/{id}/master.m3u8?MediaSourceId={id}&PlaySessionId={psid}&TranscodeReasons=ContainerBitrateExceedsLimit&api_key=MOCKLEAK` (the `api_key` is deliberate: the app must strip it), `TranscodingSubProtocol` `hls`. `PlaySessionId` a fresh 32-hex string. Item `--refuse` mode answers `ErrorCode` `NotAllowed` |
| `GET /Videos/{itemId}/stream?static=true&mediaSourceId=&tag=` | serves `clip.mp4` with `Accept-Ranges: bytes` and correct `206` for `Range` (ExoPlayer seeks with ranges); 401 without a token; 400 if any `api_key`-like query name is present (proves the app never sends one) |
| `GET /videos/{itemId}/master.m3u8`, `/videos/{itemId}/hls/main.m3u8`, `/videos/{itemId}/hls/seg{n}.ts` | the pre-cut HLS files |
| `GET /Videos/{itemId}/{mediaSourceId}/Subtitles/{index}/Stream.{format}` | index 2 → `sample.srt` (`text/plain`), index 3 → `styled.ass`; other → 404 |
| `POST /Sessions/Playing`, `/Sessions/Playing/Progress`, `/Sessions/Playing/Stopped`, `POST /Sessions/Playing/Ping?playSessionId=` | 204; each body's `ItemId`, `PositionTicks`, `IsPaused`, `PlayMethod`, `Failed` kept in an in-memory list; `Stopped` with `PositionTicks` past 90% marks the item played, otherwise sets its resume position (so Continue Watching reflects it) |
| `GET /MediaSegments/{itemId}` | `{"Items":[{"Id":…,"ItemId":…,"Type":"Intro","StartTicks":10000000,"EndTicks":30000000},{…"Type":"Outro","StartTicks":80000000,"EndTicks":100000000}],"TotalRecordCount":2,"StartIndex":0}` |
| `GET /mock/reports` | test-only: the recorded report list as JSON (no tokens); documented in the README as not a Jellyfin route |

- [ ] **Spike (desk + emulator, no installs).** Write `research/2026-11-styled-subtitles-spike.md` comparing, on the SPEC §3.5 criteria (correctness on real files, app size, maintenance, provenance of the native binary, dependency-verification fit):
  - (a) **libass plugged into Media3**: candidates are a published AAR of prebuilt libass/FreeType/HarfBuzz/FriBidi `.so` files (provenance: third-party binaries we cannot reproduce; sha256 pins the bytes but not what they were built from) or building libass from source ourselves (needs the Android NDK, which is NOT installed: installing it needs Ben's yes; adds a native build to every release; four C libraries parsing untrusted server files in our process).
  - (b) **libmpv as a second engine** for files that need it: larger still (FFmpeg inside, ~20–30 MB per ABI), a second player to keep in step with every feature of this milestone, and the same provenance problem.
  - (c) **Media3's built-in SSA/ASS parser** (colours, bold/italic/underline, alignment, `\pos`, outline-as-background `BorderStyle=3`, `layer` z-order since 1.8.0; no karaoke `\k`, no `\t` animation, no `\move`/`\fad`, no `\p` drawing, no embedded fonts) **plus server burn-in** for files that need the rest (the user chooses "Let the server draw these subtitles"; the server re-encodes the video).
  - PGS and VobSub: Media3 already renders both from the container (PGS parser; VobSub since 1.6.0), so they need no native code; when the server transcodes they are burned in.
  - Run the repo's own corpus through (c) on the emulator (the controller runs it): `styled.ass` (positions, colours, two layers), `effects.ass` (karaoke, `\t`, `\move`, `\p1`), and Ben's real anime files on his server at the milestone-end check; record what draws correctly with screenshots described in words.
  - **Recommendation to record (unless the evidence contradicts it):** (c) for this milestone; (a)-from-source revisited in Milestone 8's security review with Ben, because it is the only native option whose provenance we could own. Say so plainly in the app (Task 7's "effects" notice).
- [ ] **Test media.** `tools/mockjelly/media/make_media.ps1` (PowerShell, records every command; uses only ffmpeg's built-in `testsrc2` and `sine` sources, so no third-party content):
  ```powershell
  $ff = (Get-Command ffmpeg).Source
  & $ff -hide_banner -y -f lavfi -i "testsrc2=size=320x180:rate=24" -f lavfi -i "sine=frequency=440:sample_rate=48000" `
        -t 10 -c:v libx264 -profile:v baseline -level 3.0 -pix_fmt yuv420p -g 24 -c:a aac -b:a 64k -movflags +faststart clip.mp4
  New-Item -ItemType Directory -Force hls | Out-Null
  & $ff -hide_banner -y -i clip.mp4 -c copy -f hls -hls_time 2 -hls_playlist_type vod -hls_segment_filename "hls/seg%d.ts" hls/main.m3u8
  Get-FileHash clip.mp4, hls/* -Algorithm SHA256 | ForEach-Object { "$($_.Hash.ToLower())  $([IO.Path]::GetFileName($_.Path))" } | Set-Content CHECKSUMS.txt
  ```
  Expected: `clip.mp4` under 200 KB. Copy it to `app/src/androidTest/assets/media/` with its checksum line. Hand-write the subtitle fixtures (UTF-8): `sample.srt` (3 cues at 1–2 s, 3–4 s, 5–6 s), `sample.vtt` (same cues), `styled.ass` (`[Script Info]`, `[V4+ Styles]` with two styles, `{\pos(160,20)}` and `{\c&H0000FF&}` overrides, `Layer: 1` dialogue), `effects.ass` (`{\k50}`, `{\t(0,500,\fs40)}`, `{\move(0,0,100,100)}`, `{\p1}m 0 0 l 10 0`), `bom-crlf.srt` (UTF-8 BOM and CRLF line ends), `hostile.srt` (a 9 MiB file is NOT committed: the test generates it; this file holds out-of-order cues, a negative timestamp and 10,000-character line).
- [ ] **Mock server, tests first.** Extend `test_mock_jellyfin.py` (it already runs the server in-process): PlaybackInfo without a token is 401; with `EnableDirectPlay:false` the `TranscodingUrl` points at the HLS master and contains the deliberate `api_key`; `GET /Videos/{id}/stream` with `Range: bytes=100-199` answers `206` with exactly 100 bytes and `Content-Range`; a stream request carrying `api_key` is 400; a `Stopped` report at 95% marks the item played and at 30% sets its resume position; `/MediaSegments/{id}` returns the two segments. Then implement in `mock_jellyfin.py` (standard library only; `python -I`), keep logging method + path + token yes/no only, and document every route and `--refuse` in the README with one `curl` per route.
- [ ] Run: `python -I tools/mockjelly/test_mock_jellyfin.py` → all tests pass.
- [ ] Commit: `test(mockjelly): playback routes, test media and the styled-subtitle spike`.

---

### Task 2: The playback call path (`ServerApi.postForJson`, `MediaRoute`, `PlaybackGateway`, `MediaPaths`)

**Files:**
- Create: `data/playback/PlaybackModels.kt`, `data/playback/MediaPaths.kt`, `data/api/MediaRoute.kt`, `data/playback/api/PlaybackDtos.kt`, `data/playback/api/PlaybackGateway.kt`, `data/playback/api/PlaybackGatewayImpl.kt`, `data/playback/api/PlaybackMappers.kt`.
- Modify: `data/api/ServerApi.kt`, `core/AppError.kt` (4 kinds + messages), `AppGraph.kt`.
- Test: `data/api/ServerApiPostForJsonTest.kt`, `data/api/MediaRouteTest.kt`, `data/playback/MediaPathsTest.kt`, `data/playback/PlaybackGatewayTest.kt`, `data/playback/PlaybackGatewayHostileTest.kt`; fixtures `app/src/test/resources/jellyfin/playback/{direct-play,direct-stream,transcode,refused,hostile}.json` written from the reference schemas `PlaybackInfoResponse`, `MediaSourceInfo`, `MediaStream`, `MediaSegmentDtoQueryResult`.

**Interfaces — Consumes:** `ServerApi` internals (`store`, `selector`, `clients`, `ServerNet.authenticated`, `ApiRequest`), `JellyfinId`, `displayText`, `Ticks`, `Session`.

**Produces:**
```kotlin
// data/playback/PlaybackModels.kt
enum class PlayMethod { DIRECT_PLAY, DIRECT_STREAM, TRANSCODE }

/** Streaming caps. MAXIMUM still sends a ceiling, because the server treats a missing one as its own default. */
enum class Quality(val maxBitrate: Int, val maxWidth: Int?) {
    MAXIMUM(200_000_000, null), MBPS_20(20_000_000, 1920), MBPS_8(8_000_000, 1920), MBPS_4(4_000_000, 1280), MBPS_1_5(1_500_000, 854)
}

/** From `MediaStream.VideoRangeType` (12.4.0 enum: Unknown, SDR, HDR10, HLG, DOVI, DOVIWithHDR10, DOVIWithHLG, DOVIWithSDR, DOVIWithEL, DOVIWithHDR10Plus, DOVIWithELHDR10Plus, DOVIInvalid, HDR10Plus). */
enum class VideoRange(val wire: String) {
    SDR("SDR"), HDR10("HDR10"), HDR10_PLUS("HDR10Plus"), HLG("HLG"), DOVI("DOVI"), DOVI_WITH_HDR10("DOVIWithHDR10"),
    DOVI_WITH_HLG("DOVIWithHLG"), DOVI_WITH_SDR("DOVIWithSDR"), DOVI_WITH_EL("DOVIWithEL"), DOVI_WITH_HDR10_PLUS("DOVIWithHDR10Plus"),
    DOVI_WITH_EL_HDR10_PLUS("DOVIWithELHDR10Plus"), DOVI_INVALID("DOVIInvalid"), UNKNOWN("Unknown");
    companion object { fun fromWire(s: String?): VideoRange = entries.firstOrNull { it.wire == s } ?: UNKNOWN }
}

/** The 12.4.0 `TranscodeReason` names this app explains; anything else is OTHER. */
enum class TranscodeReason { CONTAINER, VIDEO_CODEC, AUDIO_CODEC, SUBTITLE, BITRATE, VIDEO_RANGE, RESOLUTION, AUDIO_CHANNELS, DIRECT_PLAY_ERROR, OTHER }

data class AudioTrack(val index: Int, val label: String, val language: String?, val codec: String?, val channels: Int?, val isDefault: Boolean)

/** TEXT: SubRip/WebVTT. STYLED_TEXT: ASS/SSA. PICTURE: PGS, VobSub, DVB. */
enum class SubtitleKind { TEXT, STYLED_TEXT, PICTURE }

/** FILE: fetched as a file and drawn by the app. IN_STREAM: drawn by Media3 from the container. BURNED_IN: drawn into the picture by the server. */
enum class SubtitleDelivery { FILE, IN_STREAM, BURNED_IN }

data class SubtitleTrack(
    val index: Int, val label: String, val language: String?, val format: String, val kind: SubtitleKind,
    val forced: Boolean, val hearingImpaired: Boolean, val isDefault: Boolean, val external: Boolean,
)

/** A server path plus query, relative to the verified address. Never absolute, never carries a credential. */
data class StreamSource(val path: String, val query: List<Pair<String, String>>, val hls: Boolean) {
    override fun toString() = "StreamSource(<redacted>)"
}

data class PlaybackRequest(
    val itemId: String,
    val quality: Quality,
    val audioIndex: Int? = null,
    /** A PICTURE track to show in the stream or burn in. Text tracks are never sent: the app fetches them itself. */
    val pictureSubtitleIndex: Int? = null,
    /** The user asked the server to draw this subtitle (any kind). */
    val burnInSubtitleIndex: Int? = null,
    val forceTranscode: Boolean = false,
)

data class PlaybackPlan(
    val request: PlaybackRequest,
    val mediaSourceId: String,
    val playSessionId: String?,
    val method: PlayMethod,
    val source: StreamSource,
    val audioTracks: List<AudioTrack>,
    val subtitleTracks: List<SubtitleTrack>,
    val audioIndex: Int?,
    val runtimeMs: Long?,
    val videoRange: VideoRange,
    val transcodeReasons: Set<TranscodeReason>,
    val hasSegments: Boolean,
    val eTag: String?,
) {
    val itemId: String get() = request.itemId
    override fun toString() = "PlaybackPlan(method=$method, hls=${source.hls})"
}

enum class SegmentKind { INTRO, OUTRO, RECAP, PREVIEW, COMMERCIAL }
data class MediaSegment(val kind: SegmentKind, val startMs: Long, val endMs: Long)

// data/playback/MediaPaths.kt (pure)
object MediaPaths {
    /** GET /Videos/{itemId}/stream with static=true, mediaSourceId and tag (all three in the 12.4.0 parameter list). */
    fun directStream(itemId: String, mediaSourceId: String, eTag: String?): StreamSource
    /**
     * A path the server gave us (`TranscodingUrl`, `DeliveryUrl`): accepted only if it is relative (starts with one '/',
     * no scheme, no authority), its first segment is `videos` (any case), it passes `ApiRequest.checkPath`, and it is at most
     * 2,048 characters; credential query names (api_key, ApiKey, X-Emby-Token, X-MediaBrowser-Token, X-Emby-Authorization,
     * any case, percent-encoded or not) are removed. Null means refuse.
     */
    fun checkServerPath(raw: String?, hls: Boolean): StreamSource?
    /** GET /Videos/{routeItemId}/{routeMediaSourceId}/Subtitles/{routeIndex}/Stream.{routeFormat}; format in srt, vtt, ass, ssa. */
    fun subtitleFile(itemId: String, mediaSourceId: String, index: Int, format: String): String
}

// data/api/MediaRoute.kt
/** Where this session's media bytes come from: the verified address and the account's own authenticated client, guarded. */
class MediaRoute internal constructor(
    val session: Session,
    internal val base: HttpUrl,
    internal val callFactory: Call.Factory,
    internal val connection: Connection.Online,
) {
    internal fun url(source: StreamSource): HttpUrl = ApiRequest.url(connection.address, source.path, source.query)
    override fun toString() = "MediaRoute(<redacted>)"
}

/** Refuses (a failed Call carrying BlockedRequestException(AppError(FORBIDDEN))) any request outside [authority] + [videosPrefix], case-insensitive on the segment, and anything not GET/HEAD. */
internal class GuardedCallFactory(private val delegate: Call.Factory, private val authority: String, private val videosPrefix: String) : Call.Factory

// ServerApi additions
/** POST with a JSON body, decoded as JSON (PlaybackInfo). Same rules as get/post; not retried unless asked. */
suspend fun <T> postForJson(session: Session, path: String, query: List<Pair<String, String>> = emptyList(), jsonBody: String,
                            deserializer: DeserializationStrategy<T>, maxBytes: Long = DEFAULT_MAX_BYTES, retry: Boolean = false): T
/** Same steps 1–3 as every call (warm gate, awaitOnline, server read, address still saved, authenticated client); no request is sent. */
internal suspend fun mediaRoute(session: Session): MediaRoute

// data/playback/api/PlaybackGateway.kt
interface PlaybackGateway {
    /** POST /Items/{itemId}/PlaybackInfo, body PlaybackInfoDto (UserId, MaxStreamingBitrate, AudioStreamIndex, SubtitleStreamIndex, DeviceProfile, EnableDirectPlay, EnableDirectStream, EnableTranscoding, AllowVideoStreamCopy, AllowAudioStreamCopy, AlwaysBurnInSubtitleWhenTranscoding). */
    suspend fun playbackInfo(s: Session, body: PlaybackInfoBody): PlaybackInfo
    suspend fun reportStart(s: Session, r: PlaybackReport)      // POST /Sessions/Playing        (PlaybackStartInfo)
    suspend fun reportProgress(s: Session, r: PlaybackReport)   // POST /Sessions/Playing/Progress (PlaybackProgressInfo)
    suspend fun reportStopped(s: Session, r: PlaybackReport)    // POST /Sessions/Playing/Stopped  (PlaybackStopInfo)
    suspend fun ping(s: Session, playSessionId: String)         // POST /Sessions/Playing/Ping?playSessionId= (required)
    suspend fun segments(s: Session, itemId: String): List<MediaSegment> // GET /MediaSegments/{itemId}?includeSegmentTypes=Intro,Outro,Recap,Preview,Commercial
    /** GET the subtitle file through ServerApi.getBytes: at most 8 MiB, Content-Type text/* or application/x-subrip, else SERVER_ERROR. */
    suspend fun subtitleFile(s: Session, itemId: String, mediaSourceId: String, index: Int, format: String): ByteArray
}

/** Validated PlaybackInfoResponse. [refusal] set when ErrorCode was NotAllowed / NoCompatibleStream / RateLimitExceeded. */
data class PlaybackInfo(val playSessionId: String?, val sources: List<SourceInfo>, val refusal: AppError.Kind?)
data class SourceInfo(
    val id: String, val container: String?, val eTag: String?, val runtimeMs: Long?, val bitrate: Int?,
    val supportsDirectPlay: Boolean, val supportsDirectStream: Boolean, val supportsTranscoding: Boolean,
    val transcoding: StreamSource?, val transcodeReasons: Set<TranscodeReason>,
    val video: VideoInfo?, val audio: List<AudioTrack>, val subtitles: List<SubtitleTrack>,
    val defaultAudioIndex: Int?, val defaultSubtitleIndex: Int?, val hasSegments: Boolean,
)
data class VideoInfo(val codec: String?, val range: VideoRange, val bitDepth: Int?, val width: Int?, val height: Int?, val dvProfile: Int?)
```
`PlaybackReport` is defined in Task 5's `PlaybackReports.kt`; the gateway maps it to the three report DTOs (fields from the reference: `ItemId`, `MediaSourceId`, `PlaySessionId`, `PositionTicks`, `IsPaused`, `IsMuted=false`, `CanSeek=true`, `AudioStreamIndex`, `SubtitleStreamIndex`, `PlayMethod` (`Transcode`/`DirectStream`/`DirectPlay`), `RepeatMode=RepeatNone`, `PlaybackOrder=Default`; stop: `ItemId`, `MediaSourceId`, `PlaySessionId`, `PositionTicks`, `Failed`). Report bodies are encoded with `PlaybackJson = Json { explicitNulls = false; encodeDefaults = true }` (a separate encoder; `JsonBodies` stays the one decoder).

**Untrusted-input rules at this boundary (`PlaybackMappers`):** item ids `JellyfinId`; `MediaSourceInfo.Id`, `PlaySessionId` and `ETag` must match `[A-Za-z0-9]{1,64}` (else the source, or the session id, is dropped); stream `Index` 0..10,000 and unique per source (first wins); `Codec` `[a-z0-9_.-]{1,32}` lower-cased; `Language` `[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})?` else null; `DisplayTitle`/`Title` through `displayText`, 120 characters; `RunTimeTicks` 0..`Ticks.fromMs(48 h)`; `Bitrate` 0..1,000,000,000; `Width`/`Height` 1..16,384; at most 50 sources, 200 streams per source, 200 segments; `TranscodeReasons` read from the `TranscodingUrl` query (comma-separated names) and mapped; `TranscodingUrl` and `DeliveryUrl` only through `MediaPaths.checkServerPath`; segments with `EndTicks <= StartTicks` or beyond 48 h dropped; `Type` `Unknown` dropped.

- [ ] **Tests first — `MediaPathsTest`:**
  ```kotlin
  @Test fun transcodingUrlKeepsItsQueryButLosesCredentials() {
      val src = MediaPaths.checkServerPath("/videos/$ID/master.m3u8?MediaSourceId=$ID&api_key=SECRET&API%5Fkey=X&ApiKey=Y&PlaySessionId=abc", hls = true)!!
      assertEquals("/videos/$ID/master.m3u8", src.path)
      assertEquals(listOf("MediaSourceId" to ID, "PlaySessionId" to "abc"), src.query)
      assertTrue(src.hls)
  }
  @Test fun refusesAnythingThatIsNotARelativeVideosPath() {
      listOf("https://evil.example/videos/x", "//evil.example/videos/x", "/Items/x", "/videos/../System/Info", "/videos/x/%2e%2e/y",
             "videos/x", "/videos/x\\y", "/videos/x#frag", "", null, "/videos/" + "a".repeat(2100))
          .forEach { assertNull(it, MediaPaths.checkServerPath(it, hls = true)) }
  }
  @Test fun directStreamIsStaticWithSourceAndTag() {
      val src = MediaPaths.directStream(ID, "abc123", "etag9")
      assertEquals("/Videos/$ID/stream", src.path)
      assertEquals(listOf("static" to "true", "mediaSourceId" to "abc123", "tag" to "etag9"), src.query)
      assertFalse(src.hls)
  }
  ```
- [ ] **Tests first — `MediaRouteTest`** (MockWebServer with the real `ServerApi`, `ServerClients`, `HttpClientFactory`, as `ServerApiTestBase`): a media GET through `route.callFactory` carries `Authorization` with the token and the server's custom header; a request to another authority, to `/Items/x`, or a POST, fails before anything is sent (`server.requestCount` unchanged) with `AppError(FORBIDDEN)` in the cause chain; `mediaRoute` for a signed-out account fails `NOT_SIGNED_IN` with zero requests; offline ⇒ `OFFLINE`, zero requests; a pinned self-signed server's media request succeeds on the pinned client and a different certificate fails `TLS_PIN_CHANGED`; a plain-http server without consent is `CLEARTEXT_BLOCKED`; the request URL never contains `api_key` even when the source query had one; a 3xx is returned to the caller, never followed.
- [ ] **Tests first — `ServerApiPostForJsonTest`:** body sent as `application/json`; decoded response; 401 invalidates the token exactly as `get` does; over-limit body ⇒ `SERVER_ERROR`; not retried by default after an IO failure; retried once when `retry = true`.
- [ ] **Tests first — `PlaybackGatewayTest` / `PlaybackGatewayHostileTest`:** request method, path and JSON body field names exactly as the reference (assert the parsed request JSON keys); each fixture maps to the expected `PlaybackInfo`; `ErrorCode` `NotAllowed` ⇒ `refusal = PLAYBACK_NOT_ALLOWED`, `NoCompatibleStream` ⇒ `NO_COMPATIBLE_STREAM`, `RateLimitExceeded` ⇒ `TOO_MANY_STREAMS`; hostile fixture: absolute `TranscodingUrl` ⇒ `transcoding = null`, non-alphanumeric media source id ⇒ source dropped, duplicate stream index ⇒ first kept, bidi in a track title stripped, 500 streams ⇒ 200 kept; reports post the right path and body and a 204 is success; `segments` maps types and drops bad ranges; `subtitleFile` refuses `application/octet-stream`, `text/html` and anything over 8 MiB, and accepts `text/plain; charset=utf-8`.
- [ ] Implement. Add the four `AppError` kinds with messages: `PLAYBACK_NOT_ALLOWED` "Your account isn't allowed to play this. Ask the server's owner.", `NO_COMPATIBLE_STREAM` "This can't be played on this phone, and the server couldn't convert it.", `TOO_MANY_STREAMS` "Too many people are watching on this server right now. Try again later.", `PLAYBACK_FAILED` "Playback stopped because of a problem with this video." Wire `AppGraph.playbackGateway`.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.playback.*" --tests "app.shoalwatch.data.api.*"` → all pass, including the existing `ServerApi*` tests unchanged.
- [ ] Commit: `feat(playback): PlaybackInfo, reports and media routes on the authenticated path`.

---

### Task 3: Device capabilities, HDR policy and the device profile

**Files:**
- Create: `data/playback/DeviceCapabilities.kt`, `data/playback/HdrPolicy.kt`, `data/playback/DeviceProfileBuilder.kt` (it fills the internal `DeviceProfileDto` family in `api/PlaybackDtos.kt`).
- Test: `data/playback/HdrPolicyTest.kt`, `data/playback/DeviceProfileBuilderTest.kt`, `data/playback/AndroidDeviceCapabilitiesTest.kt` (Robolectric, shadowed `MediaCodecList` and `Display`).

**Interfaces — Consumes:** `Quality`, `VideoRange` (Task 2); `android.media.MediaCodecList`, `MediaCodecInfo.CodecProfileLevel`, `Display.getHdrCapabilities()` (API 24; on API 34+ also `Display.Mode.getSupportedHdrTypes()`).

**Produces:**
```kotlin
enum class HdrType { HDR10, HDR10_PLUS, HLG, DOLBY_VISION }
enum class VideoCodec(val jellyfin: String, val mime: String) {
    H264("h264", "video/avc"), HEVC("hevc", "video/hevc"), VP9("vp9", "video/x-vnd.on2.vp9"), AV1("av1", "video/av01")
}

/** What this phone can decode and show. Read once per player opening (a display can change, e.g. an external screen). */
data class DeviceCapabilities(
    val video: Set<VideoCodec>,                       // a decoder exists
    val audio: Set<String>,                           // Jellyfin names: aac, mp3, opus, flac, vorbis, ac3, eac3, dts, truehd
    val h264MaxLevel: Int,                            // Jellyfin level number, e.g. 41 for 4.1
    val hevcMain10: Boolean, val vp9Profile2: Boolean, val av1Main10: Boolean,
    val decoderHdr: Map<VideoCodec, Set<HdrType>>,    // e.g. HEVCProfileMain10HDR10 -> HDR10, ...HDR10Plus -> HDR10_PLUS
    val dolbyVisionProfiles: Set<Int>,                // from video/dolby-vision decoders: 5, 8 (dvhe.05, dvhe.08), 9 (dvav.09)
    val display: Set<HdrType>,                        // what the screen can show
    val maxAudioChannels: Int,                        // 2 on a phone speaker; 6 or 8 only if a decoder for ac3/eac3 exists
)

fun interface CodecSupport { fun read(): DeviceCapabilities }
class AndroidDeviceCapabilities(private val context: Context) : CodecSupport

object HdrPolicy {
    /**
     * The VideoRangeType values a codec may direct play. Rule: HDR is direct played only when the decoder can decode that HDR
     * format AND the screen can show it; otherwise only SDR is listed and the server converts (tone-maps) the rest.
     * Dolby Vision: a profile the DV decoder handles AND a DV screen plays as DV; profile 8.1/8.4 also plays on an HDR10/HLG
     * path through its base layer when DV is not available but HDR10/HLG is; 8.2 (SDR base) plays as SDR; profile 7 (DOVIWithEL,
     * DOVIWithELHDR10Plus) and DOVIInvalid never direct play (black screens, research complaint 12).
     */
    fun directPlayRanges(codec: VideoCodec, caps: DeviceCapabilities): Set<VideoRange>
}

object DeviceProfileBuilder {
    const val DIRECT_PLAY_CONTAINERS = "mp4,m4v,mov,mkv,webm,ts,mpegts"
    val TEXT_SUBTITLES = listOf("srt", "subrip", "vtt", "webvtt", "ass", "ssa")
    val PICTURE_SUBTITLES = listOf("pgs", "pgssub", "dvdsub", "vobsub", "dvbsub")
    /** [burnInFormat]: the format of a subtitle the user asked the server to draw; it is listed as Encode only. */
    internal fun build(caps: DeviceCapabilities, quality: Quality, forceTranscode: Boolean, burnInFormat: String? = null): DeviceProfileDto
}
```
Profile contents (ported from AAFin's `DeviceProfileBuilder`, every name checked against the 12.4.0 schemas `DeviceProfile`, `DirectPlayProfile`, `TranscodingProfile`, `CodecProfile`, `ProfileCondition`, `SubtitleProfile`):
- `Name` "Shoalwatch"; `MaxStreamingBitrate` and `MaxStaticBitrate` = `quality.maxBitrate`.
- `DirectPlayProfiles`: one `Video` profile, `Container` = `DIRECT_PLAY_CONTAINERS`, `VideoCodec` = decodable codecs (h264 always), `AudioCodec` = decodable audio (aac always).
- `TranscodingProfiles`: one `Video`, `Container` `ts`, `VideoCodec` `h264`, `AudioCodec` `aac`, `Protocol` `hls`, `Context` `Streaming`, `MaxAudioChannels` `"2"`, `CopyTimestamps` false, `EnableSubtitlesInManifest` false, `Conditions` Width/Height `LessThanEqual` from `quality.maxWidth` (the request has no MaxWidth field; AAFin's note), `MinSegments` 1, `SegmentLength` 6. The target is 8-bit SDR h264, so any HDR source that is converted is tone-mapped by the server.
- `CodecProfiles`: h264 `VideoBitDepth LessThanEqual 8` and `VideoLevel LessThanEqual h264MaxLevel`; hevc/vp9/av1 `VideoBitDepth LessThanEqual 10` only when Main10/Profile 2 is decodable, else 8; for EVERY decodable codec a `VideoRangeType EqualsAny` condition listing `HdrPolicy.directPlayRanges` wire names joined with `|` (`IsRequired` false); with `forceTranscode`, the width ceiling also as a codec condition (AAFin's rule).
- `SubtitleProfiles`: every TEXT format `External`; every PICTURE format `Embed` and `Encode` (the server embeds when direct playing, encodes when transcoding); `burnInFormat` (if set) `Encode` only, replacing its other entries.
- `ContainerProfiles`: none.

- [ ] **Tests first — `HdrPolicyTest`** (each a named row):
  ```kotlin
  private fun caps(display: Set<HdrType>, hevcHdr: Set<HdrType> = emptySet(), dv: Set<Int> = emptySet()) =
      DeviceCapabilities(setOf(VideoCodec.H264, VideoCodec.HEVC), setOf("aac"), 51, hevcMain10 = true, vp9Profile2 = false,
          av1Main10 = false, decoderHdr = mapOf(VideoCodec.HEVC to hevcHdr), dolbyVisionProfiles = dv, display = display, maxAudioChannels = 2)

  @Test fun sdrScreenNeverDirectPlaysHdr() =
      assertEquals(setOf(VideoRange.SDR, VideoRange.DOVI_WITH_SDR), HdrPolicy.directPlayRanges(VideoCodec.HEVC, caps(display = emptySet(), hevcHdr = setOf(HdrType.HDR10))))
  @Test fun hdr10ScreenAndDecoderPlayHdr10AndDvProfile81BaseLayer() {
      val r = HdrPolicy.directPlayRanges(VideoCodec.HEVC, caps(setOf(HdrType.HDR10), setOf(HdrType.HDR10)))
      assertTrue(VideoRange.HDR10 in r && VideoRange.DOVI_WITH_HDR10 in r)
      assertFalse(VideoRange.DOVI in r)            // profile 5 has no HDR10 base layer
  }
  @Test fun profile7AndInvalidNeverDirectPlay() {
      val all = caps(HdrType.entries.toSet(), HdrType.entries.toSet(), setOf(5, 7, 8))
      val r = HdrPolicy.directPlayRanges(VideoCodec.HEVC, all)
      assertFalse(VideoRange.DOVI_WITH_EL in r || VideoRange.DOVI_WITH_EL_HDR10_PLUS in r || VideoRange.DOVI_INVALID in r)
      assertTrue(VideoRange.DOVI in r)
  }
  @Test fun h264IsAlwaysSdrOnly() = assertEquals(setOf(VideoRange.SDR), HdrPolicy.directPlayRanges(VideoCodec.H264, caps(HdrType.entries.toSet())))
  @Test fun hlgNeedsTenBitDecoderAndHlgScreen() { /* hevcMain10=false or display without HLG -> no HLG */ }
  ```
- [ ] **Tests first — `DeviceProfileBuilderTest`:** JSON of the built profile (encoded with `PlaybackJson`) has exactly the reference's property names; h264 and aac always present even with no decoders reported; a codec without a decoder is absent from direct play; the `VideoRangeType` condition value for an SDR phone is `SDR|DOVIWithSDR`; `Quality.MBPS_4` puts `1280` Width conditions on the transcoding profile and `4000000` in both bitrates; `forceTranscode` adds the width codec condition; `burnInFormat = "ass"` lists `ass` as `Encode` only and leaves `srt` `External`; PGS is `Embed` and `Encode`.
- [ ] **Tests first — `AndroidDeviceCapabilitiesTest`** (Robolectric `ShadowMediaCodecList.addCodec`, `ShadowDisplay`): HEVC Main10HDR10 decoder + HDR10 display ⇒ `decoderHdr[HEVC] == {HDR10}` and `display == {HDR10}`; a `video/dolby-vision` decoder with `DolbyVisionProfileDvheSt` ⇒ profile 8; encoders ignored; no HDR capabilities object ⇒ empty `display`; h264 level 5.1 ⇒ 51.
- [ ] Implement (pure parts first, then the adapter). The adapter never throws: any platform failure yields the minimal profile (h264 + aac, SDR), logged by kind only.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.playback.HdrPolicyTest" --tests "app.shoalwatch.data.playback.DeviceProfileBuilderTest" --tests "app.shoalwatch.data.playback.AndroidDeviceCapabilitiesTest"` → pass.
- [ ] Commit: `feat(playback): device profile with HDR and Dolby Vision rules`.

---

### Task 4: The playback decision and the resolver (direct play → direct stream → transcode, one fallback)

**Files:**
- Create: `data/playback/PlaybackDecision.kt`, `data/playback/PlaybackResolver.kt`.
- Test: `data/playback/PlaybackDecisionTest.kt`, `data/playback/PlaybackResolverTest.kt` (with `FakePlaybackGateway` in `app/src/test/java/app/shoalwatch/data/playback/PlaybackFakes.kt`).

**Interfaces — Consumes:** `PlaybackGateway`, `PlaybackInfo`, `SourceInfo`, `DeviceProfileBuilder`, `CodecSupport`, `MediaPaths`.

**Produces:**
```kotlin
object PlaybackDecision {
    /**
     * 1. refusal set -> throw AppError.of(refusal).
     * 2. First source (the server orders them); none -> NO_COMPATIBLE_STREAM.
     * 3. DIRECT_PLAY when supportsDirectPlay and nothing forces a transcode (forceTranscode, a burn-in, a PICTURE subtitle that the
     *    server says it will encode): source = MediaPaths.directStream(itemId, source.id, source.eTag).
     * 4. Else the server's transcoding path: DIRECT_STREAM when supportsDirectStream and nothing forces a transcode, else TRANSCODE.
     * 5. Else NO_COMPATIBLE_STREAM. (No static-stream guess for old servers: 10.10+ always sends TranscodingUrl when it can.)
     */
    fun decide(request: PlaybackRequest, info: PlaybackInfo): PlaybackPlan

    /** True when the answer would burn in a subtitle but offers no transcoding path: ask again with direct play and direct stream off. */
    fun needsForcedTranscode(request: PlaybackRequest, info: PlaybackInfo): Boolean
}

/** [routes] is `serverApi::mediaRoute` in production and a fake in tests. */
class PlaybackResolver(private val gateway: PlaybackGateway, private val capabilities: CodecSupport, private val routes: suspend (Session) -> MediaRoute) {
    /** Ask, decide, and (when [needsForcedTranscode]) ask once more as a forced transcode. Throws AppError. */
    suspend fun resolve(s: Session, request: PlaybackRequest): Resolved
    /** After the player failed to decode a direct play / direct stream: the same request as a forced transcode. Called at most once per item by the controller. */
    suspend fun fallBack(s: Session, failed: PlaybackPlan): Resolved
}

/** A plan with the route its bytes come from. Never logged; both halves redact themselves. */
class Resolved(val plan: PlaybackPlan, val route: MediaRoute)
```
Body sent (`PlaybackInfoBody`, internal): `UserId` = session user, `MaxStreamingBitrate` = quality, `AudioStreamIndex` = request, `SubtitleStreamIndex` = `burnInSubtitleIndex ?: pictureSubtitleIndex ?: -1` (-1 = none: leaving it unset lets the server pick one, which can force a burn-in transcode; AAFin's finding), `DeviceProfile` = `DeviceProfileBuilder.build(capabilities.read(), quality, forceTranscode, burnInFormat)`, `EnableDirectPlay`/`EnableDirectStream` = `!forceTranscode && burnIn == null`, `EnableTranscoding` true, `AllowVideoStreamCopy`/`AllowAudioStreamCopy` true, `AlwaysBurnInSubtitleWhenTranscoding` = `burnIn != null`. `StartTimeTicks` is NOT sent: every stream starts at zero and the player seeks (HLS playlists cover the whole runtime; AAFin's rule). `resolve` calls `routes(s)` (`ServerApi.mediaRoute`) after the decision, so the route is to the address verified now.

- [ ] **Tests first — `PlaybackDecisionTest`** (fixtures from Task 2 decoded through the real mappers):
  ```kotlin
  @Test fun directPlayWhenAllowed() {
      val plan = PlaybackDecision.decide(request(), info("direct-play.json"))
      assertEquals(PlayMethod.DIRECT_PLAY, plan.method)
      assertEquals("/Videos/$ID/stream", plan.source.path)
      assertFalse(plan.source.hls)
  }
  @Test fun directStreamUsesTheServersPath() {
      val plan = PlaybackDecision.decide(request(), info("direct-stream.json"))
      assertEquals(PlayMethod.DIRECT_STREAM, plan.method)
      assertTrue(plan.source.path.startsWith("/videos/") && plan.source.hls)
  }
  @Test fun transcodeCarriesItsReasons() {
      val plan = PlaybackDecision.decide(request(), info("transcode.json"))
      assertEquals(PlayMethod.TRANSCODE, plan.method)
      assertEquals(setOf(TranscodeReason.VIDEO_RANGE), plan.transcodeReasons)
  }
  @Test fun forcedTranscodeNeverDirectPlays() =
      assertEquals(PlayMethod.TRANSCODE, PlaybackDecision.decide(request(forceTranscode = true), info("transcode.json")).method)
  @Test fun refusalsThrowTheirKind() {
      val e = assertThrows(AppError::class.java) { PlaybackDecision.decide(request(), info("refused.json")) }
      assertEquals(AppError.Kind.PLAYBACK_NOT_ALLOWED, e.kind)
  }
  @Test fun noUsablePathIsNoCompatibleStream() { /* supportsDirectPlay false and transcoding null -> NO_COMPATIBLE_STREAM */ }
  @Test fun defaultAudioIsTheServersWhenNoneAsked() { /* audioIndex = defaultAudioIndex, else first audio track */ }
  ```
- [ ] **Tests first — `PlaybackResolverTest`:** the body's `SubtitleStreamIndex` is -1 when only a text track is wanted; a PICTURE index is sent as itself; a burn-in sends `AlwaysBurnInSubtitleWhenTranscoding = true` and direct play off; `needsForcedTranscode` makes exactly one second request with both direct options off, never a third; `fallBack` sends exactly one request with `EnableDirectPlay = false` and `EnableDirectStream = false` and returns a TRANSCODE plan; `Quality.MBPS_1_5` reaches the body; an `OFFLINE` from the gateway propagates unchanged and no route is made.
- [ ] Implement, wire `AppGraph.playbackResolver` (with `AndroidDeviceCapabilities(appContext)`), pass.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.playback.PlaybackDecisionTest" --tests "app.shoalwatch.data.playback.PlaybackResolverTest"` → pass.
- [ ] Commit: `feat(playback): direct play first, then direct stream, then transcode, with one fallback`.

---

### Task 5: Progress reporting through the playstate seam, and teardown on session change

**Files:**
- Create: `data/playback/PlaybackReports.kt`, `data/playback/ServerPlaybackReports.kt`, `data/playback/ProgressReporter.kt`, `data/playback/ActivePlayback.kt`.
- Modify: `data/library/Playstate.kt` (`recordStop`), `data/library/PlaystateRepository.kt`, `data/library/PlaystateLocal.kt`, `AppGraph.kt` (`onAccountGone`/`onServerRemoved` also call `activePlayback.end`), `app/src/test/java/app/shoalwatch/ui/browse/BrowseFakes.kt` (`FakePlaystate.recordStop`).
- Test: `data/playback/ProgressReporterTest.kt`, `data/playback/ServerPlaybackReportsTest.kt`, `data/playback/ActivePlaybackTest.kt`, `data/library/PlaystateRecordStopTest.kt`.

**Interfaces — Consumes:** `PlaybackGateway` reports and `ping`, `Playstate`, `ServerConnections.connection(serverId)` (to retry a pending stop when the server is back), `MonotonicClock`, the app scope.

**Produces:**
```kotlin
// data/playback/PlaybackReports.kt
data class PlaybackReport(
    val itemId: String, val mediaSourceId: String, val playSessionId: String?, val method: PlayMethod,
    val positionMs: Long, val paused: Boolean, val audioIndex: Int?, val subtitleIndex: Int?, val failed: Boolean = false,
) { override fun toString() = "PlaybackReport(<redacted>)" }

sealed interface ReportOutcome { data object Sent : ReportOutcome; data class NotSent(val kind: AppError.Kind) : ReportOutcome }

/**
 * What the player tells the server (and the saved library) as it plays. NEVER throws except CancellationException: a failed
 * report must not interrupt the film. Milestone 5 replaces the NotSent path of [stopped] with the durable outbox; the player
 * does not change.
 */
interface PlaybackReports {
    suspend fun started(s: Session, r: PlaybackReport): ReportOutcome
    suspend fun progress(s: Session, r: PlaybackReport): ReportOutcome
    suspend fun ping(s: Session, playSessionId: String): ReportOutcome
    /** [finished]: reached the credits (Outro start) or 90% of the runtime, by playback (SPEC 3.1). */
    suspend fun stopped(s: Session, r: PlaybackReport, finished: Boolean): ReportOutcome
}

// data/library/Playstate.kt (added to the existing interface)
/**
 * Saves where playback stopped, on this phone, for this user. finished = true is setPlayed(true) (local change, server call,
 * rollback on failure, as today). finished = false sets the saved position only (no server call: the stop report carried it),
 * noted in LocalChangeLog so an older refresh cannot undo it, then refreshes resume and nextUp. Same per-user queue and write
 * gate as the other actions. Never called for an item this user was not shown (SERVER_NOT_FOUND, nothing changed).
 */
suspend fun recordStop(s: Session, itemId: String, positionMs: Long, finished: Boolean)

// data/playback/ServerPlaybackReports.kt
class ServerPlaybackReports(
    private val gateway: PlaybackGateway, private val playstate: Playstate, private val connections: ServerConnections,
    private val scope: CoroutineScope,
) : PlaybackReports {
    /** Forget everything held for this account (its pending stop). Called by ActivePlayback.end. */
    fun forget(serverId: String, userId: String? = null)
}

// data/playback/ProgressReporter.kt
/** Decides WHEN to report; one per playing item. All calls on the controller's (main) dispatcher; sends run in [scope]. */
class ProgressReporter(
    private val reports: PlaybackReports, private val session: Session, private val scope: CoroutineScope,
    private val clock: MonotonicClock, private val intervalMs: Long = 10_000, private val pingMs: Long = 30_000,
) {
    fun onPlaying(snapshot: PlaybackReport)            // first call -> started; later calls after a pause -> progress
    fun onPaused(snapshot: PlaybackReport)             // progress(paused = true) at once
    fun onSeeked(snapshot: PlaybackReport)             // progress at once
    fun onTracksChanged(snapshot: PlaybackReport)      // progress at once
    fun tick(snapshot: PlaybackReport)                 // progress if intervalMs has passed while playing; ping if paused, TRANSCODE, pingMs passed
    fun stop(snapshot: PlaybackReport, finished: Boolean)   // exactly once; later calls ignored
}

// data/playback/ActivePlayback.kt
/** The one record of who is playing. The controller registers; the graph's store hooks and the session flow end it. */
class ActivePlayback {
    fun begin(session: Session, onEnd: (EndReason) -> Unit): Token
    fun release(token: Token)
    /** Account or server gone: ends synchronously on the main thread if it is that session's. */
    fun end(serverId: String, userId: String? = null)
    class Token internal constructor(internal val session: Session)
}
enum class EndReason { SESSION_CHANGED, SIGNED_OUT }
```
Rules:
- Reports carry the session they were made for and are sent with that session only. `ProgressReporter.stop` builds the final report from the last known position (never 0 because the output changed: research "stop position sent as 0 on Bluetooth disconnect"; a becoming-noisy pause reports the real position).
- `ServerPlaybackReports.stopped`: send `reportStopped`; then `playstate.recordStop(...)` whatever the outcome (so Continue Watching on this phone is right at once); a NotSent stop is kept in memory as the one `PendingStop` per (session, item), latest wins, and resent ONCE (as a stop without `PlaySessionId`, so it cannot end a newer transcode: AAFin's trick) when that server's `connection` next becomes `Online`; dropped on `forget`, and lost if the process dies (Milestone 5 makes it durable — record in ARCHITECTURE "Known limits").
- `started`/`progress`/`ping` failures are logged by kind only and dropped (the next progress carries the newer position).
- A report for a session whose account is no longer signed in fails at `ServerApi` as `NOT_SIGNED_IN` with nothing sent; that is `NotSent` and is NOT kept.

- [ ] **Tests first — `ProgressReporterTest`** (virtual time, `FakePlaybackReports` recording calls):
  ```kotlin
  @Test fun startsOnceThenEveryTenSecondsWhilePlaying() = runTest {
      val r = reporter(); r.onPlaying(at(0)); advanceTimeBy(1); r.tick(at(5_000)); clock.advance(10_000); r.tick(at(10_000)); runCurrent()
      assertEquals(listOf("started@0", "progress@10000"), fake.calls)
  }
  @Test fun pauseReportsTheRealPositionAtOnce() = runTest {
      val r = reporter(); r.onPlaying(at(0)); r.onPaused(at(42_000).copy(paused = true)); runCurrent()
      assertEquals("progress@42000 paused", fake.calls.last())
  }
  @Test fun stopIsSentExactlyOnceWithTheLastPosition() = runTest { /* stop twice -> one stopped@last */ }
  @Test fun pausedTranscodeIsPingedEveryThirtySeconds() = runTest { /* TRANSCODE + paused -> ping(playSessionId) at 30 s, not for DIRECT_PLAY */ }
  @Test fun aFailingReportNeverThrowsToTheCaller() = runTest { /* fake returns NotSent / throws IOException inside -> no exception, playback calls continue */ }
  ```
- [ ] **Tests first — `ServerPlaybackReportsTest`** (fake gateway, fake connections): stopped sends then records the stop locally even when sending failed; a NotSent stop is resent once when the connection goes `Offline` → `Online`, without `PlaySessionId`, and not again; a second stop for the same item replaces the pending one; `forget(server, user)` drops it and nothing is sent afterwards; a `NOT_SIGNED_IN` stop is not kept; nothing here throws.
- [ ] **Tests first — `PlaystateRecordStopTest`** (real `PlaystateRepository`, in-memory Room, fake gateway; as `PlaystateTestBase`): finished = false sets `positionMs` with no server call and the item appears in `resume` after the refresh; finished = true behaves as `setPlayed(true)` (one `UserPlayedItems` call, rollback on failure); a refresh asked before the stop cannot overwrite the new position (`LocalChangeLog`); an item the user was never shown is `SERVER_NOT_FOUND` with nothing written.
- [ ] **Tests first — `ActivePlaybackTest`:** `end(server, user)` for the registered session calls `onEnd(SIGNED_OUT)` synchronously and exactly once; for another session it does nothing; `end(server)` (server removed) ends any session of that server; `release` then `end` does nothing; registering a second session ends the first with `SESSION_CHANGED`.
- [ ] Implement; update `FakePlaystate`; wire `AppGraph.playbackReports` and `AppGraph.activePlayback`, and add `activePlayback.end(server, user)` + `playbackReports.forget(...)` to `ServerStore`'s `onAccountGone` and `onServerRemoved` callbacks (main-thread hop inside `end`).
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.playback.ProgressReporterTest" --tests "app.shoalwatch.data.playback.ServerPlaybackReportsTest" --tests "app.shoalwatch.data.playback.ActivePlaybackTest" --tests "app.shoalwatch.data.library.*"` → pass (all existing `Playstate*` tests unchanged).
- [ ] Commit: `feat(playback): progress reports that never stop the film, and teardown when the account changes`.

---

### Task 6: The engine and the controller (Media3, audio focus, MediaSession, resume, next/previous, Up next, skip, speed, sleep timer, fallback)

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `gradle/verification-metadata.xml` (regenerated), `ShoalwatchApp.kt` (Media3 log level off), `AppGraph.kt`.
- Create: `data/playback/engine/PlayerEngine.kt`, `engine/ExoPlayerEngine.kt`, `engine/MediaItems.kt`, `data/playback/PlaybackController.kt`, `data/playback/PlaybackState.kt`, `data/playback/EpisodeNavigator.kt`, `data/playback/UpNext.kt`, `data/playback/SleepTimer.kt`.
- Test: `data/playback/PlaybackControllerTest.kt`, `PlaybackControllerFallbackTest.kt`, `PlaybackControllerTeardownTest.kt`, `EpisodeNavigatorTest.kt`, `UpNextTest.kt`, `SleepTimerTest.kt`, `engine/MediaItemsTest.kt`; `FakePlayerEngine` in `PlaybackFakes.kt`.

**New dependencies** (all first-party AndroidX, version `media3 = "1.9.2"` in `[versions]`):
```toml
media3-exoplayer = { module = "androidx.media3:media3-exoplayer", version.ref = "media3" }
media3-exoplayer-hls = { module = "androidx.media3:media3-exoplayer-hls", version.ref = "media3" }
media3-datasource-okhttp = { module = "androidx.media3:media3-datasource-okhttp", version.ref = "media3" }
media3-ui = { module = "androidx.media3:media3-ui", version.ref = "media3" }
media3-session = { module = "androidx.media3:media3-session", version.ref = "media3" }
```
`implementation` for all five. Then regenerate verification metadata (command in Global Constraints), check every new AAR's `minCompileSdk <= 35`, and run `assembleRelease`: `verifyReleaseManifest` must pass UNCHANGED. If any Media3 AAR adds a permission, service, receiver or provider to the merged manifest, remove it in the source manifest with `tools:node="remove"` and say why in ARCHITECTURE "Version checks" (expected: none, since no `MediaSessionService` is declared and no wake mode is set). Do not add `media3-database`, `media3-exoplayer-dash`, `media3-ui-compose`, any extension decoder (`media3-decoder-*`, FFmpeg) or `media3-test-utils`.

**Interfaces — Consumes:** `PlaybackResolver`, `Resolved`, `MediaRoute` (its `callFactory` and `url`), `ProgressReporter`, `PlaybackReports`, `ActivePlayback`, `PlaybackGateway.segments`, `Library.series` and `Library.item` (resume position, next/previous), `MonotonicClock`.

**Produces:**
```kotlin
// engine/PlayerEngine.kt — the only Media3-facing surface the controller sees; FakePlayerEngine implements it in tests.
interface PlayerEngine {
    val events: Flow<EngineEvent>
    val positionMs: Long
    val durationMs: Long?
    val isPlaying: Boolean
    fun load(item: EngineItem, startMs: Long, playWhenReady: Boolean)
    fun play(); fun pause(); fun seekTo(ms: Long)
    fun setSpeed(speed: Float)
    fun selectAudio(ordinal: Int?)            // Nth audio track in the stream (DIRECT_PLAY only), null = default
    fun selectPictureSubtitle(ordinal: Int?)  // Nth picture-subtitle track in the stream, null = text renderer off
    fun setMediaMetadata(title: String?, subtitle: String?)   // for the MediaSession; null clears
    fun release()
}
/** What to load: the URL is built inside the engine module from the route; it never leaves data/playback. */
class EngineItem internal constructor(internal val route: MediaRoute, internal val source: StreamSource, val mediaId: String) {
    override fun toString() = "EngineItem(<redacted>)"
}
sealed interface EngineEvent {
    data object Ready : EngineEvent
    data object Buffering : EngineEvent
    data class PlayingChanged(val playing: Boolean) : EngineEvent
    data object Ended : EngineEvent
    data class Failed(val category: EngineFailure) : EngineEvent
    data class PositionDiscontinuity(val toMs: Long) : EngineEvent
    data class PictureCues(val cues: List<androidx.media3.common.text.Cue>) : EngineEvent   // only while a PICTURE track is on
    data object BecameNoisy : EngineEvent     // headphones out: ExoPlayer pauses; the controller reports the position
}
/** From PlaybackException.errorCode only. */
enum class EngineFailure { DECODER, FORMAT_UNSUPPORTED, NETWORK, HTTP_UNAUTHORISED, HTTP_OTHER, BEHIND_LIVE_WINDOW, OTHER }

// engine/ExoPlayerEngine.kt
class ExoPlayerEngine(context: Context) : PlayerEngine
```
`ExoPlayerEngine` builds `ExoPlayer.Builder(appContext)` with `setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), /* handleAudioFocus = */ true)`, `setHandleAudioBecomingNoisy(true)`, NO `setWakeMode` (no `WAKE_LOCK`; the screen is kept on by the window instead, Task 8), `setSeekBackIncrementMs(10_000)`, `setSeekForwardIncrementMs(10_000)`, a `DefaultMediaSourceFactory(OkHttpDataSource.Factory(route.callFactory))` per item (rebuilt when the route changes), and the text renderer disabled unless a PICTURE track is selected (`TrackSelectionParameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, …)`). `MediaItems.build(item)` sets the URI from `route.url(source)` and `MimeTypes.APPLICATION_M3U8` for HLS; no subtitle configurations (text subtitles are the app's own, Task 7). It creates `MediaSession.Builder(appContext, player).setId("shoalwatch-player").build()` (headset, Bluetooth and Assistant play/pause/seek while the player is on screen; no `MediaSessionService`, so no notification, no exported component, no new permission) and releases it in `release()`. `PlaybackException.errorCode` mapping: `ERROR_CODE_DECODER_INIT_FAILED`, `ERROR_CODE_DECODER_QUERY_FAILED`, `ERROR_CODE_DECODING_FAILED`, `ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES` → `DECODER`; `ERROR_CODE_DECODING_FORMAT_UNSUPPORTED`, `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`, `ERROR_CODE_PARSING_CONTAINER_MALFORMED`, `ERROR_CODE_PARSING_MANIFEST_MALFORMED` → `FORMAT_UNSUPPORTED`; `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`, `ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT` → `NETWORK`; `ERROR_CODE_IO_BAD_HTTP_STATUS` with `InvalidResponseCodeException.responseCode == 401` → `HTTP_UNAUTHORISED`, else `HTTP_OTHER`; `ERROR_CODE_BEHIND_LIVE_WINDOW` → `BEHIND_LIVE_WINDOW`.

```kotlin
// data/playback/PlaybackState.kt — what the screen sees; carries no URL, route or token.
data class PlaybackState(
    val session: Session?,
    val itemId: String?,
    val title: String?, val episodeLine: String?,       // from the Library seam, already displayText-safe
    val phase: Phase,
    val positionMs: Long, val durationMs: Long?, val bufferedMs: Long,
    val method: PlayMethod?, val transcodeReasons: Set<TranscodeReason>, val videoRange: VideoRange?,
    val quality: Quality, val speed: Float,
    val audioTracks: List<AudioTrack>, val audioIndex: Int?,
    val subtitleTracks: List<SubtitleTrack>, val subtitle: SubtitleChoice,
    val segmentNow: MediaSegment?,                       // set while the position is inside an Intro/Outro/Recap/Preview/Commercial
    val upNext: UpNextState?,
    val previous: String?, val next: String?,            // item ids from EpisodeNavigator
    val sleep: SleepState,
    val error: AppError.Kind?,
    val fellBackToTranscode: Boolean,
) { enum class Phase { IDLE, RESOLVING, BUFFERING, PLAYING, PAUSED, ENDED, FAILED } }

sealed interface SubtitleChoice { data object Off : SubtitleChoice; data class On(val index: Int, val delivery: SubtitleDelivery) : SubtitleChoice }
data class UpNextState(val itemId: String, val title: String?, val secondsLeft: Int?, val cancelled: Boolean)  // secondsLeft null = waiting for the user
sealed interface SleepState { data object Off : SleepState; data class Minutes(val endsAtMonoMs: Long) : SleepState; data object EndOfItem : SleepState }

// data/playback/PlaybackController.kt — main-thread state machine, one per player screen.
class PlaybackController(
    private val session: Session,
    private val engine: PlayerEngine,
    private val resolver: PlaybackResolver,
    private val reports: PlaybackReports,
    private val gateway: PlaybackGateway,
    private val library: Library,
    private val navigator: EpisodeNavigator,
    // Task 7 adds two parameters here: `tracks: TrackPreferences` and `subtitles: SubtitleLoader`. Task 6 is built and tested without them.
    private val activePlayback: ActivePlayback,
    private val scope: CoroutineScope,             // the ViewModel scope (main)
    private val appScope: CoroutineScope,          // final reports outlive the screen
    private val clock: MonotonicClock,
    private val autoplayNext: () -> Boolean,
) {
    val state: StateFlow<PlaybackState>
    fun open(itemId: String, from: StartFrom)
    fun playPause(); fun seekTo(ms: Long); fun skipBy(deltaMs: Long)
    fun skipSegment()                              // seek to segmentNow.endMs
    fun next(); fun previous()                     // previous: within 5 s of the start -> previous episode, else restart
    fun playUpNextNow(); fun cancelUpNext()
    fun setSpeed(speed: Float)                     // 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0 only
    fun setQuality(quality: Quality)               // re-resolve at the current position
    fun setSleep(choice: SleepChoice)              // OFF, MIN_15, MIN_30, MIN_45, MIN_60, END_OF_ITEM
    fun selectAudio(index: Int); fun selectSubtitle(index: Int?); fun burnInSubtitle(index: Int)   // Task 7 fills these
    fun onHostStopped()                            // activity stopped (screen off, app left): pause, report
    /** Ends at once: engine and MediaSession released, state emptied (session null, no title, no queue), final report only if [reason] allows. */
    fun end(reason: EndReason?)
}
enum class StartFrom { RESUME, BEGINNING }
```
Behaviour (all in the controller, each with a test):
- **Open:** `activePlayback.begin(session) { end(it) }`; read the item and its saved position from `library.item(s, itemId)` (first emission with an item, at most 5 s); `RESUME` starts at `positionMs` unless it is under 5% or over 90% of the runtime (then 0); resolve; load segments (`hasSegments` only; failure ignored); `engine.load(item, start, playWhenReady = true)`.
- **Fallback once:** `EngineFailure.DECODER` or `FORMAT_UNSUPPORTED` during DIRECT_PLAY or DIRECT_STREAM ⇒ report the failed session `stopped(failed = true, finished = false)`, `resolver.fallBack`, reload at the same position, `fellBackToTranscode = true`. A second decoder failure, or one during TRANSCODE ⇒ `FAILED` with `PLAYBACK_FAILED`. `NETWORK` or `HTTP_UNAUTHORISED` ⇒ re-resolve ONCE at the current position (new verified address; a dead token is invalidated by `ServerApi` there); a second ⇒ `FAILED` with the resolver's `AppError` kind (Retry offered).
- **Up next:** for an episode with `next`, when the position reaches the Outro segment start (or the item ends, if there is none), `upNext` appears; with `autoplayNext()` true it counts 10 → 0 and then plays `next` (`stopped(finished = true)` for this one first); `cancelUpNext` sets `cancelled` and nothing auto-plays; without autoplay `secondsLeft = null` and the user chooses. Pausing pauses the countdown.
- **Finished** (SPEC 3.1): position reached by playback at Outro start or 90% of the runtime. A seek past it does not count until playback itself crosses it.
- **Skip:** `segmentNow` is set while the position is inside a segment (re-evaluated on every 250 ms tick and after seeks); `skipSegment` seeks to its end (Outro: to the end, which triggers Up next). No automatic skipping.
- **Sleep timer:** `MIN_*` ends by the monotonic clock; `END_OF_ITEM` pauses at the end and suppresses Up next; when it fires: pause, report, `sleep = Off`.
- **Host stopped / becoming noisy:** pause and `onPaused` report with the real position.
- **End:** `end(SESSION_CHANGED)` sends `stopped` for the old session in `appScope` (that account is still signed in), `end(SIGNED_OUT)` sends nothing; both call `engine.setMediaMetadata(null, null)` then `engine.release()` synchronously, `activePlayback.release`, and publish an empty `PlaybackState(session = null, …)`.

`EpisodeNavigator(library: Library)`: `suspend fun around(s: Session, episode: Item): Around` where `data class Around(val previous: Item?, val next: Item?)`, using `library.series(s, seriesId, seasonId)` (first emission that is not `Refreshing`, at most 5 s), the season order from `SeriesDetail.seasons` (already the app's Specials-last order) and the next season's first episode; films return `Around(null, null)`. Only items the Library shows this user can appear (the visibility rule).

- [ ] **Tests first — `PlaybackControllerTest`** (`FakePlayerEngine`, `FakePlaybackGateway`, `FakeLibrary`, `FakePlaybackReports`, virtual time):
  ```kotlin
  @Test fun resumesFromTheSavedPosition() = runTest {
      library.itemFlow(session, EP1).value = ItemDetail(episode(EP1, runtimeMs = 1_200_000, positionMs = 300_000), Freshness.Fresh)
      controller.open(EP1, StartFrom.RESUME); runCurrent()
      assertEquals(300_000, engine.loadedAt)
  }
  @Test fun nearlyFinishedStartsFromTheBeginning() = runTest { /* position at 95% -> loadedAt 0 */ }
  @Test fun upNextCountsDownAndPlaysTheNextEpisode() = runTest {
      openAndPlay(EP1); engine.emit(EngineEvent.Ended); advanceTimeBy(10_000); runCurrent()
      assertEquals(EP2, controller.state.value.itemId)
      assertEquals(listOf("stopped EP1 finished"), reports.stops)
  }
  @Test fun cancelledUpNextNeverAutoPlays() = runTest { /* cancelUpNext at 3 s -> after 20 s still EP1, phase ENDED */ }
  @Test fun skipIntroSeeksToTheSegmentEnd() = runTest { /* segments Intro 1–3 s; position 1.5 s -> segmentNow Intro; skipSegment -> seekTo(3000) */ }
  @Test fun seekingPastNinetyPercentIsNotFinished() = runTest { /* seekTo(95%) then end(...) -> stopped finished=false */ }
  @Test fun becomingNoisyReportsTheRealPosition() = runTest { /* position 42 s, BecameNoisy -> paused report @42000, never 0 */ }
  @Test fun sleepTimerPausesAfterFifteenMinutes() = runTest { /* setSleep(MIN_15); clock +15 min; tick -> engine paused, sleep Off */ }
  @Test fun speedIsLimitedToTheOfferedValues() { /* setSpeed(3f) ignored; setSpeed(1.5f) -> engine 1.5 */ }
  ```
- [ ] **Tests first — `PlaybackControllerFallbackTest`:** DECODER during DIRECT_PLAY ⇒ one `fallBack`, a failed stop for the first play session, reload at the same position, `fellBackToTranscode`; a second DECODER ⇒ `FAILED`/`PLAYBACK_FAILED`, no third resolve; NETWORK ⇒ exactly one re-resolve; `HTTP_UNAUTHORISED` then a resolver `UNAUTHORISED` ⇒ `FAILED`/`UNAUTHORISED`.
- [ ] **Tests first — `PlaybackControllerTeardownTest`** (the shared-tablet rule):
  ```kotlin
  @Test fun signOutReleasesAtOnceAndSendsNothing() = runTest {
      openAndPlay(EP1)
      activePlayback.end(SERVER, USER_A); // what ServerStore's onAccountGone does
      assertTrue(engine.released); assertNull(engine.metadataTitle)
      val s = controller.state.value
      assertNull(s.session); assertNull(s.title); assertNull(s.itemId); assertNull(s.next); assertTrue(s.audioTracks.isEmpty())
      runCurrent(); assertTrue(reports.calls.none { it.startsWith("stopped") })
  }
  @Test fun accountSwitchSendsTheOldSessionsStopAsTheOldSessionOnly() = runTest {
      openAndPlay(EP1); controller.end(EndReason.SESSION_CHANGED); runCurrent()
      assertEquals(listOf(sessionA), reports.stopSessions)
  }
  @Test fun aLateResolveAfterEndIsDiscarded() = runTest { /* resolver suspends; end(); resolver completes -> engine.load never called */ }
  ```
- [ ] **Tests first — `EpisodeNavigatorTest`, `UpNextTest`, `SleepTimerTest`, `MediaItemsTest`:** next crosses into the next season's first episode; the last episode of the last season has no next; Specials are last; a series never cached and offline gives `Around(null, null)` within 5 s; `MediaItemsTest` (Robolectric) — HLS sets the m3u8 MIME type, the URI is the verified address + path + query, and `toString` of `EngineItem` holds no URL.
- [ ] Add the dependencies, regenerate verification metadata, implement, wire `AppGraph.playerEngineFactory: () -> PlayerEngine = { ExoPlayerEngine(appContext) }`, set `Log.setLogLevel(Log.LOG_LEVEL_OFF)` in `ShoalwatchApp.onCreate`.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.playback.*"` → pass; then `./gradlew.bat assembleRelease` → `verifyReleaseManifest` passes.
- [ ] Commit: `feat(playback): Media3 player engine and controller` (and `build: add Media3 1.9.2 with verified checksums` as its own commit before it).

---

### Task 7: Tracks and subtitles done properly

**Files:**
- Create: `data/playback/TrackChooser.kt`, `data/playback/TrackPreferences.kt`, `data/playback/SubtitleSettings.kt`, `data/playback/SubtitleCues.kt`, `data/db/playback/TrackChoiceEntity.kt`, `data/db/playback/TrackChoiceDao.kt`, `data/db/Migration2To3.kt`, `app/schemas/app.shoalwatch.data.db.AppDatabase/3.json` (generated).
- Modify: `data/db/AppDatabase.kt` (v3), `data/playback/PlaybackController.kt` (track and subtitle functions), `app/src/test/java/app/shoalwatch/data/db/MigrationTest.kt`, `SqlCompatibilityTest` inputs (automatic: it scans sources).
- Test: `data/playback/TrackChooserTest.kt`, `TrackPreferencesTest.kt`, `SubtitleCuesTest.kt`, `SubtitleSettingsTest.kt`, `PlaybackControllerTracksTest.kt`, `data/db/MigrationTest.kt` (2→3 case).

**Interfaces — Consumes:** `AudioTrack`, `SubtitleTrack`, `PlaybackGateway.subtitleFile`, Media3 `DefaultSubtitleParserFactory`, `SubtitleParser`, `SubtitleParser.OutputOptions.allCues()`, `CuesWithTiming`, `Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP | TEXT_VTT | TEXT_SSA)`, `CaptionStyleCompat.createFromCaptionStyle`, `android.view.accessibility.CaptioningManager`.

**Produces:**
```kotlin
// Room v3: track_choices(serverId, userId, seriesId) PK; FK (serverId, userId) -> accounts ON DELETE CASCADE
@Entity(tableName = "track_choices", primaryKeys = ["serverId", "userId", "seriesId"],
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["serverId", "userId"], childColumns = ["serverId", "userId"], onDelete = ForeignKey.CASCADE)])
data class TrackChoiceEntity(val serverId: String, val userId: String, val seriesId: String,
    val audioLanguage: String?, val subtitleLanguage: String?, val subtitlesOff: Boolean, val subtitleForced: Boolean, val subtitleHearingImpaired: Boolean, val updatedAt: Long)

data class TrackChoice(val audioLanguage: String?, val subtitleLanguage: String?, val subtitlesOff: Boolean, val forced: Boolean, val hearingImpaired: Boolean)
class TrackPreferences(private val dao: TrackChoiceDao, private val clock: () -> Long) {
    suspend fun get(s: Session, seriesId: String): TrackChoice?
    suspend fun remember(s: Session, seriesId: String, choice: TrackChoice)    // films (no seriesId) are not remembered
}

/** System caption settings as defaults (CaptioningManager). */
data class CaptionDefaults(val enabled: Boolean, val language: String?, val fontScale: Float)

object TrackChooser {
    /** Identity, not index: language, forced and hearing-impaired flags (research complaint 11: tracks selected by identity). */
    fun choose(audio: List<AudioTrack>, subs: List<SubtitleTrack>, remembered: TrackChoice?, captions: CaptionDefaults,
               serverDefaultAudio: Int?, serverDefaultSubtitle: Int?): Choice
    data class Choice(val audioIndex: Int?, val subtitleIndex: Int?)
}

data class SubtitleAppearance(val sizePercent: Int, val colour: SubtitleColour, val background: SubtitleBackground, val edge: SubtitleEdge,
                              val keepFileStyling: Boolean, val bottomMarginPercent: Int)
enum class SubtitleColour { WHITE, YELLOW, CYAN, GREEN }
enum class SubtitleBackground { NONE, SHADED, SOLID }
enum class SubtitleEdge { NONE, OUTLINE, DROP_SHADOW }
class SubtitleSettings(private val prefs: SharedPreferences, private val captions: () -> CaptionDefaults) {
    val appearance: StateFlow<SubtitleAppearance>   // defaults: size from CaptioningManager font scale, white, shaded, outline, keep styling on
    fun update(a: SubtitleAppearance)
    /** Delay is per playing item only (ms, -10,000..+10,000, steps of 100); not saved. */
}

/** All cues of one text subtitle file, in time order, for drawing at (position - delay). */
class CueTimeline internal constructor(private val groups: List<CuesWithTiming>) {
    fun cuesAt(positionUs: Long): List<Cue>
    val usesEffectsThePhoneCannotDraw: Boolean          // \k, \K, \kf, \ko, \t(, \move(, \fad(, \fade(, \p1..\p9, \clip(, \fn found in the raw ASS
}
class SubtitleLoader(private val gateway: PlaybackGateway, private val io: CoroutineDispatcher) {
    /** Fetches (8 MiB cap) and parses with Media3's own parser for the format. Throws AppError(SERVER_ERROR) for anything unreadable. At most 50,000 cue groups. */
    suspend fun load(s: Session, itemId: String, mediaSourceId: String, track: SubtitleTrack): CueTimeline
}
```
Rules:
- **Delivery:** TEXT and STYLED_TEXT tracks are always fetched as files (format `srt` for TEXT unless the track is `vtt`; `ass` for STYLED_TEXT) whatever the play method, embedded ones included (the server extracts them: `SupportsExternalStream`), and drawn by the app (`CueTimeline`), so switching is instant and delay works both ways. PICTURE tracks: `IN_STREAM` during DIRECT_PLAY (the engine's text renderer, by ordinal among picture tracks), else `BURNED_IN` (re-resolve with `pictureSubtitleIndex`). A track with `external` true and PICTURE kind (an external `.sup`) is always `BURNED_IN`.
- **Choice order** (`TrackChooser`): audio = remembered language (default track of that language first, else the first of it) → server default → first. Subtitles = remembered "off" (then only a forced track in the audio's language) → remembered language (matching forced/hearing-impaired flags first) → system captions enabled: a track in the caption language, else the phone's language → a forced track in the audio's language → the server's default (it applies the user's server subtitle mode) → off.
- **Remembering:** a user's change of audio or subtitles during an episode is saved for that series (`remember`) at once; it applies to the next episode (and the next time the series is played).
- **Audio switching:** DIRECT_PLAY switches in the player by ordinal among the stream's audio tracks when the ordinal's language agrees with Jellyfin's track; any disagreement, and every DIRECT_STREAM or TRANSCODE, re-resolves with `audioIndex` at the current position.
- **Styling:** `keepFileStyling` on ⇒ `SubtitleView.setApplyEmbeddedStyles(true)` (ASS colours and positions kept), user size still applies; off ⇒ the user's colour, background and edge replace the file's. With `usesEffectsThePhoneCannotDraw`, the subtitle menu shows the notice and the `burnInSubtitle(index)` action (re-resolve with `burnInSubtitleIndex`).
- **Delay:** offered for FILE delivery only; for IN_STREAM and BURNED_IN the control is disabled with its reason.
- **Hostile files:** BOM and CRLF accepted; out-of-order cues sorted by the timeline; negative timestamps clamped to 0; a line over 1,000 characters truncated; over 8 MiB refused before parsing; a parser exception becomes `SERVER_ERROR` and the screen shows "These subtitles couldn't be read." with subtitles off — playback continues.

- [ ] **Tests first — `TrackChooserTest`:**
  ```kotlin
  @Test fun rememberedLanguageWinsOverServerDefault() {
      val c = TrackChooser.choose(audio = listOf(aud(1, "jpn", default = true), aud(2, "eng")), subs = listOf(sub(3, "eng"), sub(4, "eng", forced = true)),
          remembered = TrackChoice("eng", "eng", subtitlesOff = false, forced = false, hearingImpaired = false),
          captions = CaptionDefaults(false, null, 1f), serverDefaultAudio = 1, serverDefaultSubtitle = null)
      assertEquals(TrackChooser.Choice(audioIndex = 2, subtitleIndex = 3), c)
  }
  @Test fun subtitlesOffStillShowsForcedInTheAudioLanguage() { /* off + forced eng track + eng audio -> forced track */ }
  @Test fun systemCaptionsOnPickTheCaptionLanguage() { /* captions enabled "fra" -> fra track */ }
  @Test fun nothingKnownFollowsTheServer() { /* no memory, captions off -> serverDefaultSubtitle */ }
  ```
- [ ] **Tests first — `SubtitleCuesTest`** (Robolectric, real Media3 parsers on the fixtures): `sample.srt` gives a cue at 1.5 s and none at 2.5 s; with a +1,000 ms delay the 1.5 s cue shows at 2.5 s, and with -1,000 ms at 0.5 s; `styled.ass` keeps a position override (`Cue.position` set) and two cues at once ordered by layer; `effects.ass` sets `usesEffectsThePhoneCannotDraw`; `bom-crlf.srt` parses; a generated 9 MiB body is refused with `SERVER_ERROR` before parsing; `hostile.srt` neither throws nor exceeds the caps.
- [ ] **Tests first — `TrackPreferencesTest` + `MigrationTest` (2→3):** remember then get for (server, user, series); another user on the same server sees nothing; removing the account (through the real `ServerStore.removeAccount`) cascades the rows; migration 2→3 keeps every v2 table and its rows byte-for-byte, creates `track_choices` with Room's generated SQL copied from `3.json`, and a migrated database equals a fresh one (`sqlite_master` SQL), as the existing 1→2 test does; `SqlCompatibilityTest` passes.
- [ ] **Tests first — `PlaybackControllerTracksTest`:** a text subtitle change during TRANSCODE does NOT re-resolve; an audio change during TRANSCODE re-resolves once at the current position; a PICTURE subtitle during DIRECT_PLAY selects by ordinal with no re-resolve and during TRANSCODE re-resolves with `pictureSubtitleIndex`; `burnInSubtitle` re-resolves with `burnInSubtitleIndex` and `forceTranscode`; changes are remembered for the series and applied when Up next plays the next episode; a subtitle file that fails to load leaves playback running with subtitles off and the message.
- [ ] Implement; `AppGraph.trackPreferences`, `AppGraph.subtitleSettings` (prefs file `playback_prefs`, `CaptioningManager` defaults), `SubtitleLoader`.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.data.playback.*" --tests "app.shoalwatch.data.db.*"` → pass.
- [ ] Commit: `feat(playback): remembered tracks per series and subtitles drawn on the phone with size, colour and delay`.

---

### Task 8: The player screen, its route, and Play on the detail screen

**Files:**
- Create: `ui/player/PlayerViewModel.kt`, `PlayerUiState.kt`, `PlayerScreen.kt`, `PlayerControls.kt`, `PlayerGestures.kt`, `PlayerSheets.kt`, `UpNextCard.kt`, `SkipSegmentButton.kt`, `VideoSurface.kt`, `PlayerWindow.kt`, `PlayerPreviews.kt`; `ui/present/PlayerText.kt`.
- Modify: `ui/nav/AppNav.kt` (`Routes.PLAYER`, `Routes.player(itemId, from)`, destination), `ui/nav/BrowseRoutes.kt` (`PlayerRoute`, Detail's `onPlay`), `ui/detail/DetailScreen.kt` + `DetailViewModel.kt` (Resume / Play from start; remove the "next update" snackbar), `res/values/strings.xml` (remove `detail_playback_later`, add the strings below), `ui/components/CollectMessages.kt` (comment only).
- Test: `ui/player/PlayerViewModelTest.kt`, `ui/player/PlayerControlsRenderTest.kt`, `ui/player/PlayerScreenRenderTest.kt`, `ui/present/PlayerTextTest.kt`, `ui/detail/DetailRenderTest.kt` (update), `ui/nav/BrowseNavigationTest.kt` (player destination); on-device `app/src/androidTest/java/app/shoalwatch/ui/PlayerSmokeTest.kt`.

**Interfaces — Consumes:** `PlaybackController` (built by the ViewModel from `AppGraph`: `playerEngineFactory`, `playbackResolver`, `playbackReports`, `playbackGateway`, `library`, `trackPreferences`, `subtitleSettings`, `activePlayback`), `ServerCatalog.activeSession()`, `SubtitleSettings.appearance`.

**Produces:**
```kotlin
// ui/nav/AppNav.kt
const val ARG_FROM = "from"
const val PLAYER = "play/{$ARG_ITEM}?$ARG_FROM={$ARG_FROM}"
/** [from] is "resume" or "start"; anything else arriving in a route is treated as "start". */
fun player(itemId: String, from: StartFrom) = "play/${Uri.encode(itemId)}?$ARG_FROM=${if (from == StartFrom.RESUME) "resume" else "start"}"

// ui/player/PlayerViewModel.kt
class PlayerViewModel(
    itemId: String, from: StartFrom, saved: SavedStateHandle,
    catalog: ServerCatalog, controllerFactory: (Session, CoroutineScope) -> PlaybackController, subtitleSettings: SubtitleSettings,
) : ViewModel() {
    val state: StateFlow<PlayerUiState>
    val actions: PlayerActions
    /** The engine lives as long as the ViewModel, so rotation does not rebuffer. */
    internal val engineForSurface: PlayerEngine?          // ui/player/VideoSurface only; never exposed past the player package
    fun onHostStopped(); fun onHostStarted()
}
data class PlayerUiState(
    val session: Session?, val playback: PlaybackState, val controlsVisible: Boolean, val locked: Boolean,
    val sheet: PlayerSheet?, val appearance: SubtitleAppearance, val subtitleDelayMs: Long,
    val touchExploration: Boolean, val leave: Boolean,           // leave = the session ended: the route pops at once
)
enum class PlayerSheet { TRACKS, QUALITY, SPEED, SLEEP, SUBTITLE_STYLE, STREAM_INFO }
class PlayerActions(
    val onBack: () -> Unit, val onTapSurface: () -> Unit, val onPlayPause: () -> Unit, val onSeek: (Long) -> Unit,
    val onSkipBack: () -> Unit, val onSkipForward: () -> Unit, val onDoubleTap: (forward: Boolean) -> Unit,
    val onPrevious: () -> Unit, val onNext: () -> Unit, val onSkipSegment: () -> Unit,
    val onPlayUpNext: () -> Unit, val onCancelUpNext: () -> Unit, val onToggleLock: () -> Unit, val onOpenSheet: (PlayerSheet) -> Unit,
    val onCloseSheet: () -> Unit, val onSelectAudio: (Int) -> Unit, val onSelectSubtitle: (Int?) -> Unit, val onBurnIn: (Int) -> Unit,
    val onQuality: (Quality) -> Unit, val onSpeed: (Float) -> Unit, val onSleep: (SleepChoice) -> Unit,
    val onAppearance: (SubtitleAppearance) -> Unit, val onDelay: (Long) -> Unit, val onRetry: () -> Unit,
)
```
Behaviour:
- **Session:** the ViewModel collects `catalog.activeSession()`; the first value is the owner; any later different value (or null) calls `controller.end(SESSION_CHANGED or SIGNED_OUT)` and sets `leave = true`; the route pops back immediately (to Home if the back stack holds the old session's screens, which restart on the new session as today). `SavedStateHandle` keeps only the position and the owner's (serverId, userId) — never a title; restored with a different active session ⇒ no playback, `leave`.
- **Window (`PlayerWindow`):** while the player is shown: `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`, the lock button pins the current orientation (`SCREEN_ORIENTATION_LOCKED`) and also locks touches except the lock button itself ("Screen locked. Tap the lock to unlock."); system bars hidden (`WindowInsetsControllerCompat`, `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`); `view.keepScreenOn = phase == PLAYING || BUFFERING`; everything restored on leaving. `onStop` of the host ⇒ `onHostStopped` (pause and report; no background playback in this milestone).
- **Surface (`VideoSurface`):** `AndroidView` of a `SurfaceView` (HDR output needs a SurfaceView, not a TextureView) sized by `Modifier.aspectRatio` from the video size, and a media3-ui `SubtitleView` above it fed ONLY by the controller (text: `CueTimeline.cuesAt(position - delay)` on every frame while playing; picture: `EngineEvent.PictureCues`), styled from `SubtitleAppearance` via `CaptionStyleCompat` and `setFractionalTextSize`, with the bottom margin above the controls when they show.
- **Controls:** centre play/pause (64dp), back 10 s / forward 10 s, previous / next (episodes only; disabled with reason when there is none), a seek bar with elapsed and remaining time, title and episode line, top-row buttons for Audio and subtitles, Quality, Speed, Sleep, Subtitle style, Lock, Back. Auto-hide after 4 s while playing — never while TalkBack touch exploration is on, a sheet is open, or the player is paused. Every target is at least 48dp; at font scale 2.0 the top row wraps into a second row and the sheets scroll.
- **Gestures (`PlayerGestures`):** single tap shows/hides controls; double tap on the left or right third skips 10 s back or forward (repeated taps add up: "−30 s" bubble); horizontal drag scrubs with the target time shown. **No brightness or volume swipes** (see Decisions). Every gesture has a button equivalent.
- **Up next card:** bottom-right card "Up next: S1 E3 · Title", a line "Playing in 8 seconds" (announced once when it appears and again at 3 s, not every second), buttons "Play now" and "Cancel". Without auto-play: "Play next episode" and "Close".
- **Skip button:** "Skip intro", "Skip credits", "Skip recap", "Skip preview", "Skip advert" at the bottom right while inside the segment; 56dp; gets focus once when it appears (for switch users) but does not steal TalkBack focus repeatedly.
- **Stream info (plain words, `PlayerText`):** DIRECT_PLAY "Playing the original file."; DIRECT_STREAM "Playing the original video. Your server is repackaging it for this phone."; TRANSCODE "Your server is converting this video for this phone" + the first reason: VIDEO_CODEC "because this phone can't play its video format", AUDIO_CODEC "because this phone can't play its sound format", CONTAINER "because this phone can't open its file type", BITRATE "to fit the quality you chose", RESOLUTION "to fit the quality you chose", VIDEO_RANGE "so its HDR colours look right on this screen", SUBTITLE "so it can draw the subtitles into the picture", AUDIO_CHANNELS "to mix the sound down for this phone", DIRECT_PLAY_ERROR / fallback "because this phone couldn't play the original", OTHER "." — shown for 4 s at the start under the title and always in the Stream info sheet, with the HDR line "HDR video, shown in HDR." / "HDR video. This screen can't show HDR, so your server is converting the colours." / "Dolby Vision video, shown as HDR10."
- **Errors:** `FAILED` shows the `AppError.userMessage` with "Try again" (re-open at the last position) and "Back"; `UNAUTHORISED`/`NOT_SIGNED_IN` show "Sign in" (to `sign_in/{serverId}`); text-subtitle load failure is a snackbar only.
- **Detail screen:** with a saved position, the primary button reads "Resume from 23 min" (existing `resumeLabel`) and a secondary text button "Play from start"; without one, "Play". Both navigate to `Routes.player(...)`. Offline, the button still opens the player, which shows the `OFFLINE` message ("You're offline…") with Try again — downloads are Milestone 4.

New strings (UK English, in `strings.xml`): `player_play` "Play", `player_pause` "Pause", `player_back_10` "Back 10 seconds", `player_forward_10` "Forward 10 seconds", `player_previous` "Previous episode", `player_next` "Next episode", `player_no_previous` "There's no earlier episode", `player_no_next` "This is the last episode", `player_seek` "Position", `player_seek_state` "%1$s of %2$s", `player_tracks` "Audio and subtitles", `player_subtitles_off` "Subtitles off", `player_quality` "Quality", `quality_maximum` "Best available", `quality_20` "20 Mbps (1080p)", `quality_8` "8 Mbps (1080p)", `quality_4` "4 Mbps (720p)", `quality_1_5` "1.5 Mbps (480p)", `player_speed` "Speed", `player_speed_value` "%1$s×", `player_sleep` "Sleep timer", `sleep_off` "Off", `sleep_minutes` "%1$d minutes", `sleep_end` "End of this episode", `sleep_fired` "Paused by the sleep timer", `player_subtitle_style` "Subtitle style", `subtitle_size` "Text size", `subtitle_colour` "Text colour", `subtitle_background` "Background", `subtitle_edge` "Text edge", `subtitle_keep_styling` "Use the file's own styling", `subtitle_delay` "Subtitle timing", `subtitle_delay_value` "%1$s seconds", `subtitle_delay_unavailable` "Timing can't be changed for picture subtitles", `subtitle_effects` "These subtitles use effects this phone can't draw.", `subtitle_burn_in` "Let the server draw them (the video will be converted)", `subtitle_unreadable` "These subtitles couldn't be read.", `player_lock` "Lock screen", `player_unlock` "Unlock screen", `player_locked_hint` "Screen locked. Tap the lock to unlock.", `player_stream_info` "About this video", `up_next` "Up next: %1$s", `up_next_in` "Playing in %1$d seconds", `up_next_play` "Play now", `up_next_cancel` "Cancel", `up_next_play_manual` "Play next episode", `skip_intro` "Skip intro", `skip_credits` "Skip credits", `skip_recap` "Skip recap", `skip_preview` "Skip preview", `skip_advert` "Skip advert", `player_try_again` "Try again", `detail_resume` uses the existing resume label, `detail_play_from_start` "Play from start", and the stream-info lines above.

- [ ] **Tests first — `PlayerViewModelTest`** (fake controller factory over `FakePlayerEngine`): the first session owns playback; switching account ends it (`end(SESSION_CHANGED)`), sets `leave`, and the new session's state never contains the old title, position or queue; signing out ends with `SIGNED_OUT`; controls auto-hide after 4 s only when playing and touch exploration is off; a restored `SavedStateHandle` for another session does not play.
- [ ] **Tests first — `PlayerControlsRenderTest` / `PlayerScreenRenderTest`** (Robolectric + Compose, 640x360dp and 1200x840dp, font scale 1.0 and 2.0, as `RenderBase`): every control has its label (`onNodeWithContentDescription("Back 10 seconds")`…); each clickable node is at least 48dp high and wide; at 2.0 every top-row button is reachable (scroll or wrap; `assertIsDisplayed` after `performScrollTo`); the seek bar exposes `ProgressBarRangeInfo` and a `SetProgress` action and its state "12:30 of 45:00" in words; Up next shows "Play now" and "Cancel"; the skip button shows "Skip intro" only while `segmentNow` is an Intro; the FAILED state shows the message and "Try again"; the locked state leaves only the unlock control clickable; the subtitle style sheet's choices are radio buttons that say which is selected.
- [ ] **Tests first — `PlayerTextTest`:** every `PlayMethod` and reason maps to its sentence; HDR lines for SDR screen vs HDR screen; times use digits only (no locale digit change), "1:02:03" past an hour.
- [ ] **Update `DetailRenderTest` / `BrowseNavigationTest`:** with a resume position the button says "Resume from 23 min" and "Play from start" exists; pressing either navigates to `play/{id}?from=resume|start`; a `from` value that is neither arrives as `StartFrom.BEGINNING`.
- [ ] **On-device smoke test `PlayerSmokeTest`** (compile here; the controller runs it): seeds a server and account through the real `AppGraph` like `BrowseSmokeTest`; an in-process `MockWebServer` serves `/System/Info/Public`, the item, `POST /Items/{id}/PlaybackInfo` (direct play of the bundled `media/clip.mp4`, with Range support from the asset bytes), `/MediaSegments/{id}` and 204 for `/Sessions/Playing*`; opens `Routes.player(id, StartFrom.BEGINNING)`; waits up to 10 s until the controller's `positionMs >= 2_000`; asserts a `POST /Sessions/Playing` body arrived with this `ItemId`, every stream request carried `Authorization` and none had `api_key` in its URL; then backs out and asserts a `POST /Sessions/Playing/Stopped` with `PositionTicks > 0`.
- [ ] Implement; update `CollectMessages` comment; remove the "next update" string and its use.
- [ ] Run: `./gradlew.bat testDebugUnitTest --tests "app.shoalwatch.ui.player.*" --tests "app.shoalwatch.ui.present.PlayerTextTest" --tests "app.shoalwatch.ui.detail.*" --tests "app.shoalwatch.ui.nav.*"` → pass; then `./gradlew.bat assembleDebugAndroidTest` → builds.
- [ ] Commit: `feat(player): player screen with gestures, sheets, Up next and skip, and Play on the detail screen`.

---

### Task 9: Milestone close

- [ ] Full verification: `./gradlew.bat clean testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease` → all green, `verifyReleaseManifest` unchanged, `SourceShapeTest` (no main file over 400 lines), `SqlCompatibilityTest`, `ArtworkBoundaryTest` pass. Add `PlayerBoundaryTest` (source scan, as `ArtworkBoundaryTest`): nothing under `ui/` references `MediaRoute`, `GuardedCallFactory`, `okhttp3`, `androidx.media3.datasource` or `androidx.media3.exoplayer` (only `ui/player/VideoSurface.kt` may reference `androidx.media3.ui.SubtitleView` and `CaptionStyleCompat`); nothing outside `data/playback/engine` references `ExoPlayer`; no source calls `OkHttpClient.Builder` or `newBuilder()` outside `net/HttpClientFactory`.
- [ ] Controller: emulator run of every instrumented test including `PlayerSmokeTest`; walkthrough against `tools/mockjelly` (`python -I mock_jellyfin.py 8096`): play a film from the start and from a resume point; watch the mock log show `/Sessions/Playing`, progress every 10 s, pause, stop; switch quality to 1.5 Mbps (mock answers with the HLS transcode; the stream line says "to fit the quality you chose"); subtitles: English SRT on, delay +1 s, size and colour changed; Japanese ASS keeps its position; double-tap skips; Skip intro at 1 s; the episode ends and Up next plays episode 2 after 10 s; cancel Up next on episode 2; sleep timer 15 min set and cancelled; airplane mode mid-play (the film keeps playing what is buffered; reports fail silently; reconnecting sends the pending stop once); **switch account mid-play** (player closes at once, Home shows the other profile, nothing of the first title anywhere: Home, recents preview, notification shade, headset play does nothing); sign out mid-play (same). Screenshots checked by eye at font scale 1.0 and 2.0, TalkBack pass over every control.
- [ ] Whole-milestone review (code + security against SPEC §6 and the threat model, focused on: the media route and its guard; `TranscodingUrl`/`DeliveryUrl` validation; no token in any URL, log or exception; Media3 logging off; subtitle files and PlaybackInfo as hostile input; session teardown and the shared-tablet rule; the merged manifest; the new Room table's cascade); fix wave; re-review.
- [ ] `docs/security/milestone-3-check.md`: one row per S1–S13 with evidence (test names) and the residual risks below (pending stop lost on process death until M5; a hostile server can still make the player fetch large media from its own `/Videos/` paths; plain-http residual risk unchanged; Media3's parsers run on server-supplied bytes in-process — PGS/VobSub/SSA/SubRip/WebVTT/HLS/MP4/Matroska — kept current by pinning and updating Media3; no native code added).
- [ ] ARCHITECTURE.md: new sections "Playback (`data/playback`)" (call path, media route and guard, decision table, HDR policy table, subtitle delivery table, reports and the M5 hook, teardown), "Player screen", package map, "Version checks" row for Media3 1.9.2 (AAR `minCompileSdk` values, resolved transitive list, manifest result), "Known limits" (lock-screen and notification controls, background audio and PiP are Milestone 7; pending stop in memory; no trickplay; Dolby Vision as checked only on Ben's devices).
- [ ] ChangeLog: one plain-English line (minor bump), e.g. "v1.77 — DD/MM/YYYY — Films and episodes now play: resume where you left off, pick the quality, audio and subtitles (remembered for each series), change subtitle size, colour and timing, skip intros and credits, and the next episode starts by itself unless you cancel it."
- [ ] TODO.md (sync script) and the Obsidian project note: tick Milestone 3, add the follow-ups (libass-from-source decision for Milestone 8; trickplay; brightness/volume gestures if Ben asks; real-device HDR/DV results).
- [ ] Ben's phone test (only with his explicit yes): his server, an HDR10 film, a Dolby Vision 8.1 film, a DV profile 7 film (must transcode, never black), an anime episode with styled ASS, a PGS film; record results in the security check and ARCHITECTURE.

---

## Decisions made

| Decision | Alternative | Why |
|---|---|---|
| Media3 1.9.2 (AAFin's proven version) | The newest Media3 (1.10/1.11 exist) | Proven with Kotlin 2.2.21 / AGP 8.10.1 / compileSdk 35; newer lines may need compileSdk 36, which is not installed. Bump in Milestone 8 after an AAR check |
| Five first-party Media3 artifacts; no `media3-ui-compose`, no `PlayerView` controller, no extension decoders | Media3's stock `PlayerView` UI; FFmpeg extension for more codecs | Our own Compose controls are needed for accessibility and the session rule; the FFmpeg extension is native code with its own supply chain, and the server converts what the phone cannot decode |
| **No native subtitle renderer this milestone** (Media3's SSA parser + server burn-in on request, plainly labelled) | (a) libass in Media3, (b) libmpv second engine | Native C parsers of server files in our process and prebuilt binaries we cannot reproduce; building from source needs the NDK installed (Ben's yes) and a native build per release. SPEC §3.5 allows "keep server-side rendering and say so plainly". Revisited with Ben at Milestone 8 (libass from source is the only option whose provenance we could own) |
| Text subtitles always fetched as files and drawn by the app's own cue timeline | Let ExoPlayer draw side-loaded and embedded text | Only way to give a true two-way delay and to override file styling; switching is instant; the file goes through `ServerApi.getBytes` with an 8 MiB cap |
| Picture subtitles: in-stream on direct play, burned in on transcode | Always burn in | Burn-in forces a transcode; Media3 already renders PGS and VobSub safely from the container |
| Never build a transcode URL; use `TranscodingUrl` after validation | Build `/Videos/{id}/master.m3u8` like older clients | The 12.4.0 reference has no such route; the server's own URL is what it will serve; validation keeps it relative, under `/Videos/`, and credential-free |
| `MediaRoute` from `ServerApi` + `GuardedCallFactory` wrapper | A separate media OkHttp client; `newBuilder()` with an extra interceptor | Keeps `HttpClientFactory` the only builder and `ServerApi` the only place a token-carrying client is obtained; the guard stops a hostile playlist sending the player to any other host or path (S7) |
| HDR direct play only when decoder AND screen can show it; otherwise SDR-only profile so the server tone-maps | Let the phone tone-map HDR on an SDR screen | Washed-out HDR is a top complaint; the server's tone mapping is predictable; DV profile 7 is never direct played (black screens) |
| Start position not sent to the server; the player seeks | `StartTimeTicks` in PlaybackInfo | AAFin's finding: HLS playlists cover the whole runtime, so one rule for every method |
| `SubtitleStreamIndex = -1` unless a picture subtitle or burn-in is wanted | Leave it unset | Unset lets the server pick a subtitle and possibly burn it in, forcing a needless transcode (AAFin) |
| Reports through a new `PlaybackReports` seam; final position also through `Playstate.recordStop`; pending stop in memory, resent once on reconnect | Write a durable queue now | Milestone 5 owns the outbox; the seam's NotSent path is where it slots in, and the player does not change |
| "Finished" = playback reached the credits marker or 90% (SPEC 3.1); a seek past it does not count | Server's own rule only | SPEC 3.1; also feeds Milestone 5's watch-and-delete prompt |
| Activity-scoped `MediaSession`, no `MediaSessionService`, pause when the app is left | A media service with a notification for lock-screen controls | A service needs `FOREGROUND_SERVICE`(+media type) and an exported component, which breaks S9/S10 and the manifest rule; background playback and PiP are Milestone 7, which will take that decision with Ben. Headset and Bluetooth buttons work while the player is shown; the screen locking pauses the video |
| No `setWakeMode` | `WAKE_MODE_NETWORK` | Needs `WAKE_LOCK` (S9); the window keeps the screen on while playing |
| No brightness or volume swipe gestures | Swipe up/down on each side | YAGNI: the phone's own buttons and quick settings do it; they conflict with the system-bar swipe and are hard for switch and TalkBack users. Double-tap skip, drag scrub and buttons are built |
| Landscape by default with a lock button | Follow the phone's rotation | Research complaint 14 (orientation toggle); lock also blocks accidental touches |
| Track memory per (server, user, series) in Room v3 with cascade; matched by language and flags, not index | Per-item memory; DataStore | SPEC §3 row 5; cascade removes it with the account (session rule); index differs between episodes |
| Subtitle appearance and default quality are device settings in private prefs, defaulting from the system caption settings | Per-user settings | Not personal data and not secret; respects the user's system caption choices (accessibility) |
| Quality default: "Best available" on unmetered networks, 8 Mbps on metered | Always best | Research complaint 15 (no sensible mobile-data default) |
| Media3 logging off in every build | Off in release only | Its messages carry URIs (S6), and a debug log on a shared phone is still a leak |
| No trickplay previews this milestone | Trickplay on the seek bar | Not in SPEC §7 row 3; adds a tile-image path to secure and test. On the to-do list |
| Ported, not shared: AAFin's profile, decision, resolver and reporter adapted | A shared library | SPEC §2 |

## Open risks (only a real phone and a real server can prove)

- **Dolby Vision:** whether Ben's phone exposes a `video/dolby-vision` decoder, whether Media3 1.9.2 plays profile 8.1 through it or through the HEVC HDR10 base layer, and that profile 7 is converted rather than black. Unit tests prove only the rules.
- **Real HDR panels:** `Display.getHdrCapabilities()` honesty on Ben's devices (some report HDR in low-power modes they cannot sustain); brightness of HDR versus the SDR UI overlay; whether the server's tone mapping is enabled on Ben's server (if not, a converted HDR file can still look washed out — the stream line will say the server is converting).
- **`VideoRangeType EqualsAny` with `|` separators** in a codec profile is how jellyfin-web expresses it; confirm the 12.4 server honours it (watch the TranscodeReasons for an HDR file on an SDR screen).
- **Jellyfin 12 authentication for streams:** the reference lists no security requirement on `GET /Videos/{itemId}/stream` and the subtitle route; the app always sends the header anyway. Confirm streams and HLS segments accept header auth without legacy query keys (SPEC §9, research note on `EnableLegacyAuthorization`).
- **`TranscodingUrl` shape on 12.4:** that it is relative and under `/videos/`; if Ben's server sits behind a reverse proxy with a base path, that the path is relative to the base (`ApiRequest.url` keeps the base path).
- **Reverse proxies that redirect media** (e.g. to a CDN or another path) will fail: no client follows redirects (by design). Record it if Ben's setup does this.
- **Audio track ordinal mapping** between Jellyfin indices and the container's tracks on real MKV files (the controller re-resolves on any language mismatch).
- **Styled ASS on real anime files:** how much Media3's parser gets right (fonts are always the phone's; karaoke and animation are dropped); the burn-in path's quality on Ben's server.
- **Media segments:** whether Ben's server has an intro/credits detector plugin providing segments; without one the skip button simply never appears and Up next starts at the end.
- **Audio focus and becoming-noisy** with Ben's Bluetooth headphones (the stop/pause position must be the real one, never 0).
- **Font scale 2.0 in landscape on a small phone:** Robolectric's stand-in font does not scale, so clipping is only provable on a device.
