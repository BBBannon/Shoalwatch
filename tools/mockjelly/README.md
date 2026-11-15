# mockjelly: a Jellyfin stand-in for testing Shoalwatch

**This is a test stand-in, not Jellyfin.** It answers just enough of the Jellyfin API (shapes from the official 12.4.0 OpenAPI reference in `docs/reference/`) for emulator walkthroughs of browsing. It has no TLS and no real security. Never expose it beyond your own machine or LAN.

## Run

```
python -I tools/mockjelly/mock_jellyfin.py 8096
```

- Python 3.11, standard library only.
- Stop it with Ctrl+C.

| Flag | Effect |
|---|---|
| `port` (first argument, default 8096) | Port to listen on |
| `--host H` | Address to bind (default `0.0.0.0`) |
| `--latency-ms N` | Delay every response by N ms |
| `--fail-rate P` | Answer a fraction P (0 to 1) of signed-in requests with a 500, to see error states. Sign-in and `/System/Info/Public` are never failed |

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
