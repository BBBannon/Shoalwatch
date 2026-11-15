# mockjelly: a Jellyfin stand-in for testing Shoalwatch

**This is a test stand-in, not Jellyfin.** It answers just enough of the Jellyfin API (shapes from the official 12.4.0 OpenAPI reference in `docs/reference/`) for emulator walkthroughs of browsing and playback. It has no TLS and no real security. Never expose it beyond your own machine or LAN.

## Run

```
python -I tools/mockjelly/mock_jellyfin.py 8096
```

- Python 3.11, standard library only.
- Stop it with Ctrl+C.

| Flag | Effect |
|---|---|
| `port` (first argument, default 8096) | Port to listen on |
| `--host H` | Address to bind (default `127.0.0.1`, this PC only; an emulator reaches it as `10.0.2.2`) |
| `--latency-ms N` | Delay every response by N ms |
| `--refuse` | Answer every `PlaybackInfo` request with `ErrorCode` `NotAllowed` (to see the app's refusal state) |
| `--fail-rate P` | Answer a fraction P (0 to 1) of signed-in requests with a 500, to see error states. Sign-in and `/System/Info/Public` are never failed |
| Download flags | One per way a real server might behave; see "Download routes and flags" below |

## Connect from the app

| Item | Value |
|---|---|
| Address from the Android emulator | `http://10.0.2.2:8096` (plain http: tick the unencrypted-connection consent) |
| Address from this PC | `http://127.0.0.1:8096` |
| User | `ben` |
| Password | `test-password` |
| Quick Connect | Reported as switched off |

Each sign-in gets a fresh random token. `POST /Sessions/Logout` revokes it, after which its requests get 401. Restarting the server forgets every token and resets all watched and favourite state.

## Catalogue

- **Films** (movies): 40 films with years, runtimes, overviews and ratings. Three are part-watched (Continue Watching), three are played, three are favourites.
- **TV** (tvshows): 3 series, 2 seasons each, 6 episodes per season. Harbour Lights is part-watched: season 1 and S2E1 played, S2E2 half-way, so Continue Watching has TV content. The app asks Next Up with `enableResumable=false`, as real Jellyfin honours it, so S2E2 is not repeated there until you mark it watched (then S2E3 is next up).
- Ids are fixed 32-hex GUIDs, the same every run. Only the `Primary` image exists for each item (a plain solid colour from the item id, sized from `maxWidth`/`width`/`maxHeight`/`height`, with `ETag` and `Cache-Control`), except: three films and the series Night Shift have no image at all (empty `ImageTags`, image request 404) to exercise placeholders, and one film also has a `Backdrop` (`BackdropImageTags` set; `/Images/Backdrop[/0]` serves a 16:9 colour).
- Mutations (played, favourite, user data) change in-memory state, so marking an episode played moves Next Up.

## Routes

Public: `GET /System/Info/Public`, `GET /QuickConnect/Enabled`, `POST /Users/AuthenticateByName`.

Need `Authorization: MediaBrowser ..., Token="..."` with a token this server issued (otherwise 401):
`POST /Sessions/Logout`, `GET /Users/Me`, `GET /UserViews`, `GET /Items` (`parentId`, `startIndex`, `limit`, `sortBy`, `sortOrder`, `recursive`, `includeItemTypes`, `searchTerm`, `filters=IsUnplayed|IsPlayed|IsFavorite`, `ids`), `GET /Items/{id}`, `GET /Items/Latest`, `GET /Shows/{id}/Seasons`, `GET /Shows/{id}/Episodes` (`seasonId`), `GET /UserItems/Resume`, `GET /Shows/NextUp` (`enableResumable`), `POST`/`DELETE /UserPlayedItems/{id}`, `POST`/`DELETE /UserFavoriteItems/{id}`, `GET`/`POST /UserItems/{id}/UserData`, `GET /Items/{id}/Images/Primary`.

- Query parameter names are matched case-insensitively, as Jellyfin does.
- Like real Jellyfin, artwork (`GET /Items/{id}/Images/...`) needs no token.
- Query parameter names, path segments and the values of `sortBy`, `sortOrder`, `filters` and `includeItemTypes` are matched case-insensitively, and ids may have dashes. An invalid `limit`, `startIndex` or image size is a 400.
- Each request logs one line: method, path (no query), `auth=yes|no`, `token=yes|no`, `status=N`. Tokens and passwords are never printed.

## Playback routes

All need a token (401 otherwise). Playable items are every film and every episode; anything else is 404. The committed test clip is a 10-second, 320x180 H.264/AAC file made by `media/make_media.ps1` (ffmpeg built-in test sources only; checksums in `media/CHECKSUMS.txt`). Note the catalogue still reports film and episode runtimes in minutes, while the clip is 10 s long: playback ends at 10 s.

| Route | Behaviour |
|---|---|
| `POST /Items/{id}/PlaybackInfo` | One `MediaSourceInfo` (`Id` = item id, mp4, `RunTimeTicks` 100000000, h264 SDR video, aac `eng` audio at index 1, external SubRip `eng` at index 2, external ASS `jpn` at index 3, `HasSegments`). `SupportsDirectPlay`/`SupportsDirectStream` follow `EnableDirectPlay`/`EnableDirectStream` from the body (the 12.4.0 reference also lists them as query values, so those are read too; default true). With direct play off, `TranscodingUrl` is `/videos/{id}/master.m3u8?...&api_key=MOCKLEAK` (the `api_key` is **deliberate**: the app must strip it) and `TranscodingSubProtocol` is `hls`. `PlaySessionId` is fresh 32-hex each time. |
| `GET /Videos/{id}/stream?static=true&mediaSourceId=&tag=` | The clip with `Accept-Ranges: bytes`; `Range` gets `206` with `Content-Range` (open-ended and suffix ranges work, an unsatisfiable one is 416). **400** if any `api_key` or `ApiKey` query name is present, which proves the app never sends one. |
| `GET /videos/{id}/master.m3u8`, `/videos/{id}/hls/main.m3u8`, `/videos/{id}/hls/seg{n}.ts` | Pre-cut HLS (five 2-second segments). These paths are the mock's own, not a documented Jellyfin route. When `master.m3u8` carries the `PlaySessionId` of a PlaybackInfo answer, it points at that play session's own playlist instead (see "Download routes and flags"). |
| `GET /Videos/{id}/{mediaSourceId}/Subtitles/{index}/Stream.{format}` | Index 2 is `sample.srt` (`text/plain`), index 3 is `styled.ass`; any other index is 404. |
| `POST /Sessions/Playing`, `/Sessions/Playing/Progress`, `/Sessions/Playing/Stopped`, `/Sessions/Playing/Ping?playSessionId=` | 204. Each report is kept in memory. A `Stopped` with `PositionTicks` past 90% of the item's runtime marks it played; otherwise it sets the resume position (so Continue Watching shows it). `Failed: true` records the report but moves nothing. A body that is not a JSON object is 400. |
| `GET /MediaSegments/{id}` | An `Intro` (1 s to 3 s) and an `Outro` (8 s to 10 s), in ticks. |
| `GET /mock/reports` | **Not a Jellyfin route; test only.** The recorded reports as JSON (`Kind`, `ItemId`, `PositionTicks`, `IsPaused`, `PlayMethod`, `Failed`, `PlaySessionId`). No tokens are stored. Needs a token. |

## Download routes and flags (Milestone 4)

All need a token (401 otherwise). Why each flag exists, and what Jellyfin's own source says, is in `research/2026-11-downloads-spike.md`.

| Route | Behaviour (no flags) |
|---|---|
| `GET /Items?ids=&fields=CanDownload,MediaSources&userId=` | The listed items, wherever they are in the catalogue. Only when `fields` asks for them: `CanDownload` (true for every film and episode, false for anything else) and `MediaSources` (films and episodes: one source, `Id` = item id, `Container` `mp4`, `Size` = the exact byte count of `clip.mp4`, `ETag` `a1b2c3`) |
| `GET /Items/{id}/Download` | `clip.mp4` as `video/mp4` with `Accept-Ranges: bytes`, `ETag: "a1b2c3"` and `Last-Modified`. `Range: bytes=N-` gets `206` with an exact `Content-Range`; an `If-Range` that matches neither validator gets the whole file as `200`; an unsatisfiable range is `416`. 400 if any `api_key`-like query name is present (`api_key`, `ApiKey`, `X-Emby-Token`, `X-MediaBrowser-Token`, `X-Emby-Authorization`, any case). Not a film or episode: 404 |
| `POST /Items/{id}/PlaybackInfo` with `EnableTranscoding: false` | As above, with no `TranscodingUrl` |
| `GET /videos/{id}/hls/{playSessionId}/main.m3u8`, `.../seg{n}.ts` | The play session's own media playlist (`#EXT-X-PLAYLIST-TYPE:VOD`, five 2 s segments). Each segment URI is relative and ends `?api_key=MOCKLEAK`, which the app must strip (a request that keeps it is a 400). The same 400 rule now covers every media path (`/Download`, `/Videos/{id}/stream` and the HLS files) |
| `GET /mock/downloads` | **Not a Jellyfin route; test only.** Each media request (`/Download`, `/stream`, HLS files), newest last: `Path` (no query), `Range`, `IfRange`, `PlaySessionId` (from the path, else the query), `Token` and `ApiKey` (`yes`/`no`). No token or query value is stored |

| Flag | Behaviour (each changes one thing; any combination is allowed) |
|---|---|
| `--drop-after=BYTES` | Closes the connection after BYTES body bytes, on the FIRST media response with a body only (`/Download`, `/stream` or a segment) |
| `--no-range` | `/Download` ignores `Range` and `If-Range`, sends no `Accept-Ranges` and always answers 200 with the whole file |
| `--etag-changes` | Every `/Download` response has a new `ETag` and `Last-Modified`, so every `If-Range` mismatches |
| `--octet-stream` | `/Download` answers `application/octet-stream` |
| `--no-download` | `CanDownload: false`, and `/Download` is 403 |
| `--candownload-missing` | `CanDownload` left out of `/Items`; `/Download` still allowed |
| `--download-forbidden` | `CanDownload: true`, but `/Download` is 403 (the server's own policy disagrees) |
| `--legacy-auth-only` | `/Download` and HLS segments are 401 unless an `api_key`-like query holds a token this server issued (the header is ignored for them); `/Items`, PlaybackInfo and the playlists accept the header. An `api_key` query is then not a 400 |
| `--hls-recut` | Each PlaybackInfo gets a media playlist of a different shape (5, 4, 3, 5... segments of 10 s ÷ count) |
| `--hls-idle-kill=SECONDS` | A play session's segments are 404 (for good) once none of them was requested for SECONDS, counted from its PlaybackInfo |

By hand (set `T` and `ID` as under "Playback"):

```
python -I tools/mockjelly/mock_jellyfin.py 8096 --no-range        # add any of the flags above
curl -H "$H" "http://127.0.0.1:8096/Items?ids=$ID&fields=CanDownload,MediaSources&userId=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
curl -H "$H" -D - -o media.mp4 http://127.0.0.1:8096/Items/$ID/Download
curl -H "$H" -H "Range: bytes=100-" -H 'If-Range: "a1b2c3"' -D - -o rest.bin http://127.0.0.1:8096/Items/$ID/Download
curl -H "$H" -X POST -H "Content-Type: application/json" -d "{\"EnableDirectPlay\":false,\"EnableTranscoding\":false}" http://127.0.0.1:8096/Items/$ID/PlaybackInfo
curl -H "$H" "http://127.0.0.1:8096/videos/$ID/master.m3u8?PlaySessionId=<PlaySessionId>"      # names hls/<PlaySessionId>/main.m3u8
curl -H "$H" http://127.0.0.1:8096/videos/$ID/hls/<PlaySessionId>/main.m3u8
curl -H "$H" -o seg0.ts http://127.0.0.1:8096/videos/$ID/hls/<PlaySessionId>/seg0.ts
curl -H "$H" http://127.0.0.1:8096/mock/downloads
```

Flag by flag (start the server with the flag, then):

```
--drop-after=1000      curl -H "$H" -o cut.mp4 http://127.0.0.1:8096/Items/$ID/Download          # curl: (18) transfer closed; the next one is whole
--no-range             curl -H "$H" -H "Range: bytes=100-" -D - -o all.mp4 http://127.0.0.1:8096/Items/$ID/Download   # 200, no Accept-Ranges
--etag-changes         curl -H "$H" -D - -o /dev/null http://127.0.0.1:8096/Items/$ID/Download   # run twice: a different ETag each time
--octet-stream         curl -H "$H" -D - -o /dev/null http://127.0.0.1:8096/Items/$ID/Download   # Content-Type: application/octet-stream
--no-download          curl -H "$H" -D - -o /dev/null http://127.0.0.1:8096/Items/$ID/Download   # 403; /Items says CanDownload false
--candownload-missing  curl -H "$H" "http://127.0.0.1:8096/Items?ids=$ID&fields=CanDownload"     # no CanDownload field
--download-forbidden   curl -H "$H" -D - -o /dev/null http://127.0.0.1:8096/Items/$ID/Download   # 403 while /Items says true
--legacy-auth-only     curl -H "$H" -D - -o /dev/null http://127.0.0.1:8096/Items/$ID/Download   # 401 (only ?api_key=$T works)
--hls-recut            curl -H "$H" http://127.0.0.1:8096/videos/$ID/hls/<PlaySessionId>/main.m3u8   # new PlaybackInfo, new shape
--hls-idle-kill=2      curl -H "$H" -o seg1.ts http://127.0.0.1:8096/videos/$ID/hls/<PlaySessionId>/seg1.ts   # 404 after 2 s idle
```

Run the tests with `python -I tools/mockjelly/test_mock_jellyfin.py`. They start the server in-process on a free local port (127.0.0.1).

## Try it by hand

```
curl http://127.0.0.1:8096/System/Info/Public
curl -s -X POST http://127.0.0.1:8096/Users/AuthenticateByName -H "Content-Type: application/json" \
     -d "{\"Username\":\"ben\",\"Pw\":\"test-password\"}"          # note AccessToken in the reply
curl -H "Authorization: MediaBrowser Token=\"<AccessToken>\"" http://127.0.0.1:8096/UserViews
curl -H "Authorization: MediaBrowser Token=\"<AccessToken>\"" "http://127.0.0.1:8096/Items?parentId=<Films id>&limit=5"
curl -H "Authorization: MediaBrowser Token=\"<AccessToken>\"" http://127.0.0.1:8096/UserItems/Resume
curl -H "Authorization: MediaBrowser Token=\"<AccessToken>\"" http://127.0.0.1:8096/Shows/NextUp
curl -H "Authorization: MediaBrowser Token=\"<AccessToken>\"" -o poster.png "http://127.0.0.1:8096/Items/<item id>/Images/Primary?maxWidth=300"
```

Playback (set `T` to the token, `ID` to a film id from `/Items`):

```
H="Authorization: MediaBrowser Token=\"$T\""
curl -H "$H" -X POST -H "Content-Type: application/json" -d "{\"EnableDirectPlay\":false}" http://127.0.0.1:8096/Items/$ID/PlaybackInfo
curl -H "$H" -H "Range: bytes=100-199" -o part.bin "http://127.0.0.1:8096/Videos/$ID/stream?static=true&mediaSourceId=$ID&tag=x"
curl -H "$H" http://127.0.0.1:8096/videos/$ID/master.m3u8
curl -H "$H" http://127.0.0.1:8096/videos/$ID/hls/main.m3u8
curl -H "$H" -o seg0.ts http://127.0.0.1:8096/videos/$ID/hls/seg0.ts
curl -H "$H" http://127.0.0.1:8096/Videos/$ID/$ID/Subtitles/2/Stream.srt
curl -H "$H" -X POST -H "Content-Type: application/json" -d "{\"ItemId\":\"$ID\",\"PositionTicks\":30000000}" http://127.0.0.1:8096/Sessions/Playing/Stopped
curl -H "$H" -X POST -H "Content-Type: application/json" -d "{\"ItemId\":\"$ID\",\"PositionTicks\":10000000}" http://127.0.0.1:8096/Sessions/Playing/Progress
curl -H "$H" -X POST -H "Content-Type: application/json" -d "{\"ItemId\":\"$ID\"}" http://127.0.0.1:8096/Sessions/Playing
curl -H "$H" -X POST "http://127.0.0.1:8096/Sessions/Playing/Ping?playSessionId=abc"
curl -H "$H" http://127.0.0.1:8096/MediaSegments/$ID
curl -H "$H" http://127.0.0.1:8096/mock/reports
```
