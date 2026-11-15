# Downloads desk spike (Milestone 4, Task 1)

**Done at the desk on 15/11/2026.** No Jellyfin server was contacted. Sources:

- the project's `docs/reference/jellyfin-openapi-12.4.0.json`;
- Jellyfin's published source at tags **`v10.11.11`** (latest 10.11) and **`v12.4`** (latest 12.x), read on GitHub;
- ASP.NET Core's published source for how `PhysicalFile` serves ranges.

**Labels:** **[S]** means read in source. **[I]** means inference. Line anchors are the same at both tags unless two are given.

Tag roots:

- A = `https://github.com/jellyfin/jellyfin/blob/v10.11.11/`
- B = `https://github.com/jellyfin/jellyfin/blob/v12.4/`

**Bottom line.** The plan does not depend on any answer below. Every behaviour is handled by a path that is correct either way (see "Correct-either-way rules"), and each one has a `tools/mockjelly` flag so both answers are tested. Two findings sharpen later tasks (see "For later tasks"):

- the real server sends **no ETag** on `/Download`;
- a non-downloadable item answers **400**, not 403.

## 1. The 12.4.0 reference [S]

**`GET /Items/{itemId}/Download`** (`GetDownload`):

- **Security:** `CustomAuthentication: [Download, DefaultAuthorization]`, which is an api key in the `Authorization` header.
- **Parameters:** only `itemId`. **No `Range` header or parameter is documented.**
- **Responses:**
  - 200: `video/*` or `audio/*`, binary;
  - 401 and 403;
  - 404: ProblemDetails;
  - 503: `Retry-After`, `text/html`.
- **400 is not documented** (see section 2).

**Schemas:**

- `BaseItemDto.CanDownload` is a nullable boolean.
- `MediaSourceInfo` has `Size` (int64, nullable), and `ETag`, `Container` and `Id` (nullable strings).
- The `ItemFields` enum includes `CanDownload` and `MediaSources`.

**No HLS route is documented.** There is no `master.m3u8`, `main.m3u8` or `hls1` path. The source still serves them (section 5), so the app only follows what `TranscodingUrl` and the playlists name.

## 2. `LibraryController.GetDownload` [S]

Location: A `Jellyfin.Api/Controllers/LibraryController.cs#L664-L716`, B `#L669-L721`. The code is identical at both tags.

**Attributes:** `[HttpGet("Items/{itemId}/Download")]`, `[Authorize(Policy = Policies.Download)]`, `[ProducesFile("video/*", "audio/*")]`.

**What it does:**

1. **Looks up the item for the user.** Missing gives **404**.
2. **Checks the item can be downloaded.** `!item.CanDownload(user)` throws `ArgumentException("Item does not support downloading")`. `Jellyfin.Api/Middleware/ExceptionMiddleware.cs#L127` maps `ArgumentException` to **400**.
3. **Serves the file** with `return PhysicalFile(filePath, MimeTypes.GetMimeType(filePath), filename, true);`. The last argument is `enableRangeProcessing: true`.
   - **Content-Type** comes from the file extension. An unknown extension is `application/octet-stream` (B `MediaBrowser.Model/Net/MimeTypes.cs#L150`).
   - **Symlinks** are resolved first.

**How ASP.NET Core serves it.** Sources: `dotnet/aspnetcore` `src/Mvc/Mvc.Core/src/Infrastructure/PhysicalFileResultExecutor.cs#L40-L47` and `src/Shared/ResultsHelpers/FileResultHelper.cs`. Jellyfin targets net9.0 at 10.11.11 and net10.0 at 12.4. `IfRangeValid` is the same in both.

- **`Accept-Ranges: bytes`** is sent. A single range gets 206; an unsatisfiable one gets 416.
- **`Last-Modified`** is sent: the file's modified time, to the second.
- **No `ETag` is sent.** It is set only from `result.EntityTag`, and Jellyfin never sets it.
- **`If-Range` with a date works.** If the file is newer than that date, the range is ignored and a 200 with the whole file is sent (`FileResultHelper.cs#L176-L182`).
- **`If-Range` with an ETag is ignored** when there is no ETag, so the range is served anyway (`#L184`). [S] for the code; that this is safe for a file nobody changed is [I].
- **Response compression** is registered with default options (B `Jellyfin.Server/Startup.cs#L66`, `#L191`). The defaults do not cover `video/*` or `audio/*`. [I]

**Answers:**

- **Range is honoured by Jellyfin itself.** A reverse proxy in front of it may still strip `Range`.
- **The usable validator on a real server is `Last-Modified`**, not an ETag.
- **The plan does not depend on this:** resume with `Range` + `If-Range` when honoured, otherwise restart from zero.

## 3. The `Download` policy [S]

There is **no `Jellyfin.Api/Auth/DownloadPolicy/` folder** at either tag.

- **How the policy is built:** `options.AddPolicy(Policies.Download, new UserPermissionRequirement(PermissionKind.EnableContentDownloading))`. Location: A `Jellyfin.Server/Extensions/ApiServiceCollectionExtensions.cs#L75`, B `#L72`.
- **What the handler checks:** `Jellyfin.Api/Auth/UserPermissionPolicy/UserPermissionHandler.cs#L31-L50`.
  - It succeeds only when `user.HasPermission(EnableContentDownloading)`.
  - An API key always passes.
  - A user who does not exist gives 404.
- **Default checks also run:** `UserPermissionRequirement : DefaultAuthorizationRequirement`, so the defaults (parental schedule and so on) apply as well.
- **On failure:** a signed-in user without the permission gets **403**, and no token gets **401**. [I] This is the ASP.NET Forbid/Challenge default, and it matches the reference.

**Answers:**

- `/Download` enforces the account's download permission (403).
- It refuses items that cannot be downloaded at all (400).
- **The plan does not depend on this:** any refusal of `/Download` is `NOT_ALLOWED`, and the server's answer wins.

## 4. When `CanDownload` is filled [S]

**Only when asked for.** Location: A `Emby.Server.Implementations/Dto/DtoService.cs#L280-L285`, B `#L431-L436`.

- `if (options.ContainsField(ItemFields.CanDownload)) dto.CanDownload = user is null ? item.CanDownload() : item.CanDownload(user);`
- Otherwise the field is left out (null).
- Recordings are forced to false.

**The base rule:** A `MediaBrowser.Controller/Entities/BaseItem.cs#L875-L888`, B `#L923-L936`.

- `CanDownload()` is false by default.
- `CanDownload(User)` is `CanDownload() && user.HasPermission(EnableContentDownloading)`.

**Types that override it:**

| Type | Location | Returns |
|---|---|---|
| `Video` (so `Movie` and `Episode`) | A `Video.cs#L337`, B `#L444` | `IsFileProtocol`, but false for DVD or BluRay folders |
| `Audio`, `AudioBook`, `Book` | their own files | `IsFileProtocol` |
| `Photo` | `Photo.cs` | true |

**Everything else is false:** series, seasons, folders, box sets.

**Answers:**

- `CanDownload` is filled only when `fields=CanDownload` is sent.
- When filled, it matches `/Download`'s own check: permission plus downloadable item.
- A strm/remote item or a disc folder is false.
- **The plan does not depend on this:** true allows both, false allows neither, absent allows the original only.

## 5. `DynamicHlsController`: cutting, restarting and idle kill [S]

**Authorisation and routes.** There is a class-level `[Authorize]`, with no anonymous route (A `#L40`, B `#L39`).

| Route | B (v12.4) | A (v10.11.11) |
|---|---|---|
| `Videos/{itemId}/master.m3u8` | `#L404` | `#L408` |
| `Videos/{itemId}/main.m3u8` | `#L745` | `#L755` |
| `Videos/{itemId}/hls1/{playlistId}/{segmentId}.{container}` (needs `runtimeTicks`) | `#L1086` | `#L1102` |

**How the playlist is cut.** It is built from runtime and segment length, **before and independently of ffmpeg** (B `src/Jellyfin.MediaEncoding.Hls/Playlist/DynamicHlsPlaylistGenerator.cs#L37-L47`).

- **Equal-length segments** are the normal case (`ComputeEqualLengthSegments(DesiredSegmentLengthMs, TotalRuntimeTicks)`).
- **Remuxes cut at keyframes** when keyframe extraction is enabled.
- **Fractional frame rates** (e.g. 23.976) stretch the segment length to `ceil(fps)/fps` (B `DynamicHlsController.cs#L1404-L1411`).
- **So two sessions with the same parameters should get the same cut.** [I] A different quality, audio track, codec choice or keyframe data can change it.

**A segment far ahead of the encoder.** Handled under a lock (B `#L1460-L1525`, A `#L1488-L1499`).

- A segment that is already written is served straight away.
- ffmpeg is **killed and restarted with a seek** when any of these is true:
  - it is the init segment;
  - there is no current index;
  - `segmentId < current`;
  - `segmentId - current > 24 / SegmentLength`.
- So segment *n* after a gap is encoded fresh from its own start time.

**Idle kill.** Location: B `MediaBrowser.MediaEncoding/Transcoding/TranscodeManager.cs#L145-L190`.

- The ping timeout is **60,000 ms for HLS** (10,000 ms for progressive streams).
- When the timer fires without a recent ping, the job is killed and its files deleted.
- **What resets it:**
  - `PingTranscodingJob`, fed by progress reports and `POST /Sessions/Playing/Ping`;
  - the end of each segment request, through `OnTranscodeEndRequest`, which pings once the active request count reaches 0 (B `#L613-L619`, `DynamicHlsController.cs#L2003-L2011`).
- **So a download that keeps fetching segments keeps its job alive.** A download paused for more than about a minute finds the job gone. The next segment request then starts ffmpeg again for that session, or fails, depending on the job state. [I]

**Answers:**

- Cuts are probably identical across sessions, but not guaranteed.
- An idle session dies after about 60 s.
- **The plan does not depend on this:** segments from two play sessions are never mixed. A 404 or 5xx on a segment restarts the item from its first segment with a new session.

## 6. Legacy authorisation [S]

**The setting:** `EnableLegacyAuthorization` in `MediaBrowser.Model/Configuration/ServerConfiguration.cs#L290`.

- **v10.11.11:** `true` by default.
- **v12.4:** no initialiser, so **false by default**.

**Which token sources count:** `Jellyfin.Server.Implementations/Security/AuthorizationContext.cs#L84-L111` (headers `#L229-L263`). Identical at both tags.

| Token source | Setting off | Only with setting on |
|---|---|---|
| `Authorization: MediaBrowser ..., Token="..."` | **always accepted** | |
| `?ApiKey=` query | always accepted | |
| `Authorization: Emby ...` scheme | | yes |
| `X-Emby-Authorization`, `X-Emby-Token`, `X-MediaBrowser-Token` headers | | yes |
| `?api_key=` query | | yes |

`CustomAuthenticationHandler.cs` does not read the setting.

**Answers:**

- The header this app sends (`Authorization: MediaBrowser ... Token=`) is accepted whatever the setting. So a header-only `/Download` and HLS segment request should work on 12.4. [I] This matches the source, but SPEC §9 asks for it to be shown on a real server.
- A proxy or an older server that wants a query key is still possible.
- **The plan does not depend on this:**
  - header only, always;
  - a 401 on media is followed by one `ServerApi` call for the same item, which tells `SIGN_IN_NEEDED` apart from `SERVER_AUTH_REFUSED`;
  - no token is ever put in a URL (the app strips `api_key` and `ApiKey` alike).

## Correct-either-way rules

Tasks 5, 6, 7 and 10 implement these.

- **Range on `/Download`:**
  - Resume with `Range` + `If-Range` when the server honours them.
  - A 200 to a range request, or any response without a usable `Content-Range`, restarts that file from zero.
  - A restart only because the server ignores ranges is not "changed on server". It costs an attempt, never a `CHANGED_ON_SERVER`.
- **If-Range and validators:**
  - A changed validator means a 200 and a restart from zero.
  - Only three restarts in a row with a *changed* validator are `CHANGED_ON_SERVER`.
- **Size:** use `MediaSourceInfo.Size` when present, else the header total. The verifier checks the result either way.
- **Content-Type:**
  - Refuse only types that are surely not media: `text/*`, any `html`, `application/json`, `application/xml`.
  - Accept anything else. The verifier's file-signature and Media3 checks decide.
- **`CanDownload`:**
  - `true` allows original and converted.
  - `false` allows neither.
  - **Absent or null allows the original only**, because `/Download` enforces the server's own download permission (a 403 there is `NOT_ALLOWED`).
  - Conversions come through `/videos/`, which has no download policy, so they are not offered then: "Your server didn't say whether this account may download converted copies, so only the original can be downloaded."
- **`/Download` refused while `CanDownload` is true:** `NOT_ALLOWED`. The server's answer wins.
- **Authentication:**
  - Header only, always.
  - A 401 on a media request is followed by ONE `ServerApi` call for the same item (`DownloadGateway.facts`).
  - If that call also gets 401, `ServerApi` signs the account out locally and the item is `SIGN_IN_NEEDED`.
  - If that call succeeds, the token is fine and the server refused header authentication for media. The item is `FAILED(SERVER_AUTH_REFUSED)`: "Your server wouldn't accept this app's sign-in for downloads. Check its setting for older apps, then press Retry."
  - No token is ever put in a URL to work around it.
- **HLS segment cuts across play sessions:**
  - Segments from two play sessions are **never mixed**.
  - A conversion resumes only inside the same play session, which the queue keeps in memory (Task 10).
  - After process death, a reboot, or an in-memory session older than 30 minutes, the app asks the server again and **restarts that item's conversion from the first segment**, deleting the old segments.
  - A recut server and a stable server are both correct.
- **Idle conversions killed by the server:** a 404 or 5xx on a segment in a live session drops the session. The item restarts from the first segment with a new one, counted as an attempt.

## For later tasks (findings that sharpen the rules, without changing them)

- **The validator to keep is `ETag` when sent, else `Last-Modified`** (Task 7).
  - Real Jellyfin sends only `Last-Modified`, so most real resumes use a date `If-Range`.
  - An `If-Range` naming an ETag the server never sent is ignored by ASP.NET, which serves the range anyway. That is safe only because the app never invents a validator.
- **`/Download` answers 400 for an item that cannot be downloaded** (`ArgumentException`), not 403 (Task 5).
  - Map a 400 on `/Download` to `NOT_ALLOWED` too.
  - Never read the 400 as a bad request built by the app.
- **`ApiKey` (capital K) is a current, always-accepted query token**, and `api_key` is the legacy one. Both are already stripped by `AuthInterceptor` and refused by `MediaPaths`. Keep it that way.
- **About 60 s of idle kills an HLS job.** A paused converted download must expect to restart from its first segment. That is already the rule.

## The stand-in server

Each behaviour above has a `tools/mockjelly` flag (see its README):

| Question | Flag |
|---|---|
| Range ignored | `--no-range` |
| Validators change | `--etag-changes` |
| A cut connection | `--drop-after` |
| Content type | `--octet-stream` |
| `CanDownload` false | `--no-download` |
| `CanDownload` absent | `--candownload-missing` |
| Policy disagrees with `CanDownload` | `--download-forbidden` |
| Header refused for media | `--legacy-auth-only` |
| Cuts differ between sessions | `--hls-recut` |
| Idle kill | `--hls-idle-kill` |

**Known gap:** the default mock sends an `ETag`, which real Jellyfin does not. The mock already sends `Last-Modified` and honours a matching date `If-Range` (tested). A `--no-etag` flag was not added, because the brief fixes the flag list. It is listed for the controller to decide.

The open questions are repeated in the plan's "Unproven until Ben's real server".
