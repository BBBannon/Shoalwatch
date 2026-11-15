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
| `--ben-not-admin` | ben is not an administrator: the Server admin entry must not appear |
| `--pending-restart` | `/System/Info` says `HasPendingRestart` (the "Restart needed" notice) until a restart is requested |
| `--admin-forbidden` | Every admin-only route answers 403 while `/Users/Me` still says ben is an administrator: the app must say "refused" and stay |

## Connect from the app

| Item | Value |
|---|---|
| Address from the Android emulator | `http://10.0.2.2:8096` (plain http: tick the unencrypted-connection consent) |
| Address from this PC | `http://127.0.0.1:8096` |
| User | `ben` (administrator unless `--ben-not-admin`) |
| Password | `test-password` |
| Second user | `kid` / `kid-password` (not an administrator; cannot download; Films only) |
| Third user | `guest` (disabled; cannot sign in) |
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
| `GET /videos/{id}/master.m3u8`, `/videos/{id}/hls/main.m3u8`, `/videos/{id}/hls/seg{n}.ts` | Pre-cut HLS (five 2-second segments). These paths are the mock's own, not a documented Jellyfin route. |
| `GET /Videos/{id}/{mediaSourceId}/Subtitles/{index}/Stream.{format}` | Index 2 is `sample.srt` (`text/plain`), index 3 is `styled.ass`; any other index is 404. |
| `POST /Sessions/Playing`, `/Sessions/Playing/Progress`, `/Sessions/Playing/Stopped`, `/Sessions/Playing/Ping?playSessionId=` | 204. Each report is kept in memory. A `Stopped` with `PositionTicks` past 90% of the item's runtime marks it played; otherwise it sets the resume position (so Continue Watching shows it). `Failed: true` records the report but moves nothing. A body that is not a JSON object is 400. |
| `GET /MediaSegments/{id}` | An `Intro` (1 s to 3 s) and an `Outro` (8 s to 10 s), in ticks. |
| `GET /mock/reports` | **Not a Jellyfin route; test only.** The recorded reports as JSON (`Kind`, `ItemId`, `PositionTicks`, `IsPaused`, `PlayMethod`, `Failed`, `PlaySessionId`). No tokens are stored. Needs a token. |

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

## Admin routes

In `mock_admin.py`. All need a token this server issued (401 otherwise). Admin-only routes answer **403** to `kid` (and to ben under `--admin-forbidden` or after `/mock/demote`). A disabled user's tokens get 401 everywhere. Shapes follow the 12.4.0 reference. Some answers deliberately carry things the app must never show or keep: `RemoteEndPoint` on sessions, folder `Locations`, `LocalAddress` and paths in `/System/Info`, the IP address in sign-in activity entries, and an `AccessToken` on every device.

Set `H` as above. `SID`, `TID` and `UID` are ids from the lists.

| Route | Behaviour | Example |
|---|---|---|
| `GET /Users/Me` | The token's user with `Policy` (`IsAdministrator`, `IsDisabled`, `EnableContentDownloading`, `EnableAllFolders`, `EnabledFolders`, both provider ids and `"MockFutureField": 7`) | `curl -H "$H" http://127.0.0.1:8096/Users/Me` |
| `GET /Users` | Admin: ben, kid and guest. Anyone else: only themselves | `curl -H "$H" http://127.0.0.1:8096/Users` |
| `GET /Users/{id}` | Admin: anyone. Anyone else: only themselves (403) | `curl -H "$H" http://127.0.0.1:8096/Users/$UID` |
| `POST /Users/{id}/Policy` | Admin only. 400 unless the body has both provider ids **and** `MockFutureField: 7` (so the app must post back what it read); applies `IsDisabled` only | `curl -H "$H" -H "Content-Type: application/json" -d @policy.json http://127.0.0.1:8096/Users/$UID/Policy` |
| `GET /Sessions` | `activeWithinSeconds` accepted. Admin: kid on Living room TV (transcoding Harbour Lights S2 E3), ben on Ben's laptop (a film, paused), kid on Kid's tablet (idle), plus one per signed-in token. Anyone else: only their own | `curl -H "$H" "http://127.0.0.1:8096/Sessions?activeWithinSeconds=960"` |
| `POST /Sessions/{id}/Message` | 400 without `Text`; 404 unknown; 403 for someone else's session unless admin. The text is kept for `/mock/admin-events` and never printed | `curl -H "$H" -H "Content-Type: application/json" -d "{\"Text\":\"Bed time\"}" http://127.0.0.1:8096/Sessions/$SID/Message` |
| `POST /Sessions/{id}/Playing/{command}` | `Pause` sets `IsPaused`; `Stop` ends what is playing; other `PlaystateCommand`s recorded | `curl -H "$H" -X POST http://127.0.0.1:8096/Sessions/$SID/Playing/Pause` |
| `GET /Library/VirtualFolders` | Admin only. Films and TV; `RefreshStatus` `Idle`/`Active`, `RefreshProgress` null or 25, 50, 75 (each GET while scanning adds 25) | `curl -H "$H" http://127.0.0.1:8096/Library/VirtualFolders` |
| `POST /Library/Refresh` | Admin only. Scans every library and runs the "Scan Media Library" task | `curl -H "$H" -X POST http://127.0.0.1:8096/Library/Refresh` |
| `POST /Items/{id}/Refresh` | Admin only. A library id scans that library; another known item 204; unknown 404 | `curl -H "$H" -X POST "http://127.0.0.1:8096/Items/$ID/Refresh?metadataRefreshMode=Default"` |
| `GET /ScheduledTasks` | Admin only. Four tasks (one last failed, one never run) and a hidden one left out by `isHidden=false`. Each GET adds 25 to a running task | `curl -H "$H" "http://127.0.0.1:8096/ScheduledTasks?isHidden=false"` |
| `POST`/`DELETE /ScheduledTasks/Running/{id}` | Admin only. Start (`Running` at 0) or stop (`Cancelling` on the next GET, then `Idle`/`Cancelled`); unknown 404 | `curl -H "$H" -X POST http://127.0.0.1:8096/ScheduledTasks/Running/$TID` |
| `GET /System/ActivityLog/Entries` | Admin only. 120 entries, newest first, `startIndex`/`limit` honoured; every 10th a `Warning`, every 25th an `Error` | `curl -H "$H" "http://127.0.0.1:8096/System/ActivityLog/Entries?startIndex=0&limit=50"` |
| `GET /Devices` | Admin only. Living room TV, Ben's laptop, Kid's tablet, plus one per signed-in token | `curl -H "$H" http://127.0.0.1:8096/Devices` |
| `DELETE /Devices?id=` | Admin only. 400 without `id`; 404 unknown; removes it and signs out every token issued to that device id | `curl -H "$H" -X DELETE "http://127.0.0.1:8096/Devices?id=tablet-kid"` |
| `GET /System/Info` | "Test Jellyfin", 12.4.0, `HasPendingRestart`, `IsShuttingDown` false | `curl -H "$H" http://127.0.0.1:8096/System/Info` |
| `POST /System/Restart`, `/System/Shutdown` | Admin only. 204 and recorded; restart clears `HasPendingRestart`. The mock never stops itself | `curl -H "$H" -X POST http://127.0.0.1:8096/System/Restart` |

**The `/mock/*` routes are not Jellyfin routes; they exist only for testing.**

| Route | Behaviour | Example |
|---|---|---|
| `POST /mock/demote`, `/mock/promote` | ben's token only (403 otherwise): ben stops or starts being an administrator at run time, to see the admin screens close mid-use | `curl -H "$H" -X POST http://127.0.0.1:8096/mock/demote` |
| `GET /mock/admin-events` | Every admin action received, newest last (`Kind`, `Target`, `Query`; a message's `Header` and `Text`) | `curl -H "$H" http://127.0.0.1:8096/mock/admin-events` |

Run the admin tests with `python -I tools/mockjelly/test_mock_admin.py`.
