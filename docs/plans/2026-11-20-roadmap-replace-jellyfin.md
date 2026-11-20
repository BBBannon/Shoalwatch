# Roadmap: replace the Jellyfin app (Milestones 10 to 15)

Raised by Ben on 20/11/2026: "Fix all the others". This covers every item under "Replace the Jellyfin app" in `TODO.md`, including the
things SPEC §2 leaves out (music, Live TV, books, SyncPlay, casting).

Each milestone keeps the usual pattern. First a detailed plan of its own in `docs/plans/`, then red tests before code, then a security
check against SPEC §6, then an emulator run, and last a test on Ben's phone (only with his yes). The order puts what Ben uses most first.

## Decisions (defaults; Ben to overrule)

| # | Decision | Default |
|---|---|---|
| D1 | SPEC §2 says music, Live TV, books and SyncPlay are out | Ben's request of 20/11/2026 brings them in. Recorded in ARCHITECTURE; **Ben to update the SPEC wording** (agents never edit SPEC.md) |
| D2 | Library management (identify, images, metadata, refresh, delete) | Shown only to a server admin, as the admin screens are. Deleting from the server always asks first and names the item |
| D3 | Uploading your own picture | Android's photo picker, so **no storage permission** (S9 holds). The picture is size-checked and re-encoded on the phone before upload |
| D4 | Background music and audiobooks | Need one new permission, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (S9 change, Ben to confirm) |
| D5 | Books (epub, pdf, comics) | Downloaded and opened in a reader app of Ben's choice. **No in-app reader.** Audiobooks play in-app |
| D6 | Chromecast | A second build, "Shoalwatch with Cast", for Ben's phone. The normal GitHub/F-Droid build stays Google-free (S7). Cast hands the Chromecast a server token, as the Jellyfin app does. Settles the open "Milestone 7 choices" item 2 |
| D7 | Online subtitles and identify searches | These go through Ben's server (it asks OpenSubtitles or TMDb), so the phone still contacts only the server (S7 holds) |

## Milestone 10: manage the library from the phone (version 1.2.0)

- **Multi-select.** Press and hold starts selecting; tap to add more; a bar at the top shows the count and offers watched or unwatched,
  favourite, download, add to playlist or collection (after M11) and delete (admin). Back clears the selection. Works in libraries,
  Home rows, search, seasons and Downloads.
- **Identify.** Search by title and year (`POST /Items/RemoteSearch/{Movie|Series}`), list the matches with pictures, then apply one
  (`POST /Items/RemoteSearch/Apply/{id}`), with "replace all pictures" offered.
- **Images.** For each picture type (poster, backdrop, logo, thumb), choose from online sources (`GET /Items/{id}/RemoteImages`,
  `POST .../RemoteImages/Download`), upload from the phone (`POST /Items/{id}/Images/{type}`) or remove one. The cached artwork updates
  straight away.
- **Edit metadata.** Title, sort title, overview, year, genres, tags, rating, and "lock this field" (`GET /Items/{id}/MetadataEditor`,
  `POST /Items/{id}`).
- **Refresh one item** (`POST /Items/{id}/Refresh`), with the same choices as the web dashboard.
- **Delete from server** (`DELETE /Items/{id}`), with a confirmation.
- **Approve Quick Connect** from Settings: type the code shown on the TV or laptop (`POST /QuickConnect/Authorize`).

## Milestone 11: playlists, collections, richer browsing, subtitles (version 1.3.0)

- **Playlists:** create, rename, delete, add and remove items, reorder, and play in order or shuffled (`/Playlists`). They can be
  downloaded for offline viewing.
- **Collections:** create one, and add items to it or remove them (`/Collections`). A film shows a "part of this collection" link.
- **Detail pages:** cast and crew with person pages (their films and shows), trailers (local trailers play in-app; online ones open
  YouTube or the browser), "more like this" (`/Items/{id}/Similar`), extras and special features, and a choice when a film has more than
  one version.
- **Libraries:** filter by genre, year, rating, studio and tag; jump by letter; browse pages for genres and studios. **Search** also
  finds people, genres and collections.
- **Subtitles from the internet:** search in the player or on the detail page (`GET /Items/{id}/RemoteSearch/Subtitles/{lang}`),
  then download (`POST .../RemoteSearch/Subtitles/{subtitleId}`). The default language comes from the profile.
- **Photos and home videos** libraries: a grid, a full-screen viewer and video playback.

## Milestone 12: full admin on the phone (version 1.4.0)

- **Users:** create, delete, rename and set a password; library access, parental rating limit and schedule, and what they may do
  (download, delete, transcode).
- **Libraries:** create, rename and delete them; add or remove folders (from the server's folder browser); library options (metadata
  language, fetchers, real-time monitoring).
- **Plugins:** list, enable, disable, uninstall, settings where the plugin has a simple form, install from the catalogue, updates.
  Restart prompt.
- **Server settings:** general (name, language), playback and transcoding (hardware acceleration, limits), network (ports, addresses,
  remote access), and Live TV tuners (after M14).
- **Logs** (list and read), **API keys**, **backups** (Jellyfin 10.11+).
- The "open the dashboard in the browser" button stays as a fallback.

## Milestone 13: music and audiobooks (version 1.5.0)

- Music library: artists, albums, songs, genres, and playlists from M11. The player plays in the background with lock-screen and
  notification controls (Media3 `MediaSessionService`, D4). It has a queue, shuffle, repeat, gapless playback, lyrics and an instant mix.
- Music downloads for offline listening, reusing the M4 download engine.
- Audiobooks: chapters, resume, speed, sleep timer and offline listening.
- Books: download and open in a reader app (D5).
- Music and audiobooks in the Android Auto parked app (M9).

## Milestone 14: Live TV and recordings (version 1.6.0)

- Channels, a programme guide (grid by time), play live with pause and rewind where the tuner allows, favourite channels.
- Recordings: view, play, delete; schedule a programme or a series; series-recording settings.

## Milestone 15: casting and watching together (version 1.7.0)

- **Play on another device:** pick a Jellyfin TV or web session and send it play, pause, seek and stop (`/Sessions/{id}/Playing`,
  `/Sessions/{id}/Playstate`). This works in every build.
- **Chromecast** in the "with Cast" build (D6).
- **SyncPlay:** create or join a group and stay in step with the others.

## Security impact (summary; each milestone checks in detail)

- **S7:** unchanged in the normal build. The Cast build contacts Google (D6).
- **S9:** one new permission in M13 (D4). The photo picker needs none (D3).
- **S11:** uploaded and downloaded pictures are size-capped and decoded safely. Metadata edits are sent as typed fields, never as raw
  JSON from the server. Server log text is shown as plain text.
- **New risk:** a stolen unlocked phone signed in as an admin can now delete media and change users. Mitigation: the admin and delete
  actions sit behind the app lock (S8) when it is on, and deleting always asks first.

## Not doing

- An in-app ebook reader (D5).
- Android TV layout (still out per SPEC §2).
