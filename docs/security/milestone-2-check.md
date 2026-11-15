# Milestone 2 security check (S13)

**Date:** 15/11/2026 · **Branch:** `milestone-2` at `89c1705` (the fix round on top of it is not covered) · **Scope:** SPEC §6, S1–S13 as they apply to browsing; the threat model of a shared family tablet with several Jellyfin profiles (one restricted), a hostile network, a hostile server, another app on the device and a lost device

This check was made by reading the code and tests. Nothing was built or run for it; test results are as the milestone ledger records them.

---

## 1. Summary verdict

**Ready to sign off once one Medium (M1) and one Low (L3) are fixed.** There is nothing Critical.

One High item (H1) is a design gap, not a defect in this milestone's code. Merging Milestone 2 does not make it worse. But it must be decided before shared downloads (Milestone 5), and must not reach a release undecided.

| Area | Verdict |
|---|---|
| Tokens on the wire, in logs, URLs, keys and the database | **Strong.** One call path (`ServerApi`) puts a token on the wire, only in `Authorization`, only to a verified address of that server. Nothing new is stored encrypted or in clear |
| Requests and artwork | **Strong.** The image loader can load an `ArtworkRef` and nothing else, through the same path; no redirects; no other host |
| Untrusted server data | **Strong.** Ids validated everywhere they become paths, keys, routes or SQL; sizes bounded; HTML shown as plain text; images checked before and while decoding |
| Isolation between profiles in the cache | **Strong.** Every read of a shared row joins to a per-profile "the server showed this to you" record |
| Isolation between profiles in the UI | **Gap (M1).** Switching account from the Servers screen re-scopes screens that were opened by the previous profile, and they ask the server for that profile's items |
| Switching profile on a shared tablet | **Design gap (H1).** Any signed-in profile can switch to any other with one tap; nothing protects a restricted profile from switching to an adult one |
| Local storage, backup, removal | **Good.** Excluded from backup; removed by cascade with the account or server; pictures cleared with the server (L2 is a small hole) |
| Build and supply chain | **Good.** One new dependency (Coil 3.3.0, Compose and core only), checksums recorded |

---

## 2. S1–S13 (as they apply to Milestone 2)

| ID | Requirement (short) | Status | Evidence (code at `89c1705`) | Proved by |
|---|---|---|---|---|
| **S1** | Tokens and headers encrypted at rest | **Met (unchanged)** | No new secret column. The library tables hold metadata and watch state only. Opened custom headers are memoised by a SHA-256 of the **ciphertext**, never the plaintext (`data/servers/OpenedHeaders.kt`) | `HeaderMemoTest` |
| **S2** | No backup | **Met (unchanged)** | `allowBackup="false"`; `data_extraction_rules.xml` and `backup_rules.xml` exclude every domain, so the new tables, the preferences file `library_maintenance` and `cacheDir/artwork` are covered | M1 `ManifestSecurityTest`, `verifyReleaseManifest`. **Not done:** a real backup attempt |
| **S3** | HTTPS by default; http only by per-server consent | **Met, with the M1 residual widened (M2 below)** | `CleartextGuard` unchanged. Ordinary requests use whichever address `awaitOnline` verified, including a consented plain-http home address (`ARCHITECTURE.md:104`, a recorded decision) | `ServerApiRealSelectorTest` |
| **S4** | Trust on first use; pins | **Met (unchanged)** | `ServerApi` gets its client from `ServerClients.clientFor(ServerNet.authenticated(...))`, so pins and the trust policy apply to every library and artwork call | M1 tests |
| **S5** | Client certificates from KeyChain | **Met (unchanged; device unproven)** | Same client path | M1 tests |
| **S6** | Token only in `Authorization`, only to its server; never in URL, log, crash text or `toString()` | **Met** | `ServerApi.kt:202-206`: address from `awaitOnline`, and refused if no longer in the server's saved list. The query carries `userId`, never a token. `Session`, `ListId` and `ArtworkRef` print nothing. Five new log lines, none with an id, URL or token | `ServerApiTest` (`getDecodesJsonAndSendsTheCurrentTokenInTheAuthorizationHeaderOnly`, `nothingIsSentUntilAnAddressIsVerified`, `aVerifiedAddressThatIsNotTheServersOwnIsNeverSentTheToken`, `aRedirectIsNotFollowedAndNothingReachesItsTarget`, `aSessionPrintsNothing`); `ArtworkFetcherTest.requestsThePrimaryImage…TokenInTheHeaderOnly`; `ArtworkRefTest.toStringCarriesNoIdsOrTags` |
| **S7** | No third-party hosts | **Met** | Coil's network modules are not dependencies; `serviceLoaderEnabled(false)`; Coil's own disk cache is off (`ArtworkLoader.kt:27-34`) | `ArtworkLoaderTest` (a plain https string, a `Uri`, an `HttpUrl` and a `File` are refused with nothing requested), `ShoalwatchAppImageLoaderTest`, `ArtworkBoundaryTest` |
| **S8** | App lock, recents hiding | **N/A until M7** | No `FLAG_SECURE` on browsing screens, as the plan says: no secret is shown. The recents preview can show what was being browsed. See H1 | — |
| **S9** | Minimum permissions | **Met** | Manifest unchanged in this milestone | `verifyReleaseManifest` (run by the controller, per the ledger) |
| **S10** | Only the launcher exported | **Met** | Manifest unchanged; no deep links (`navDeepLink` not used), no intents read, no `PendingIntent` | Source search; `verifyReleaseManifest` |
| **S11** | Responses untrusted | **Met** | See section 3, check 4 | `LibraryGatewayHostileTest`, `LibraryGatewayRobustnessTest`, `ArtworkHostileInputTest`, `ArtworkLimitsTest`, `ArtworkPixelBudgetTest`, `HtmlTextTest`, `DisplayParagraphsTest`, `ServerApiBodiesTest` |
| **S12** | Pinned, checksum-verified dependencies | **Met** | `coil = "3.3.0"`, `coil-compose` only. `verification-metadata.xml` gains sha256 entries only (no trusted keys, no `trust` rules). Version recorded under "Version checks" | Diff of `gradle/verification-metadata.xml` |
| **S13** | Security check each milestone | **Met** | This document | — |

---

## 3. The checks

| # | Check | What was checked | Evidence | Verdict |
|---|---|---|---|---|
| 1 | **Session isolation: database** | Every read of shared item rows for a profile; keys of per-profile tables; what removal deletes | `ListDao.kt:66` and `ItemDao.kt:223` join `visible_items` on (server, **user**, item) for lists, single items, episodes and offline search. `user_data`, `views`, `list_entries`, `list_state` and `visible_items` are keyed by (server, user) with `ON DELETE CASCADE` to `accounts`; `items` cascade from `servers` | Sound. `LibraryVisibilityTest` (15 cases: absent for Bob everywhere, list entries cannot reveal, episodes filtered, per server, eviction), `LibraryCascadeTest`, `LibraryAccountGoneTest` |
| 1 | **Session isolation: memory** | Refresh state, change log, action queues, working markers, sort memory | `Refresher` keys by `ListId(server, user, key)` and forgets on sign-in and account loss (`AppGraph.kt:54-55`). `LocalChangeLog` keys by (server, user, item). Action queues per `Session`. `ItemActions` work is `OwnedWork(session, …)`. `BrowseMemory` keys by (server, user, library) | Sound. Minor: `BrowseMemory` is never cleared (review m6) |
| 1 | **Session isolation: artwork** | Cache keys; who can read a saved picture | Key `art:<server>:<item>:<TYPE>:<tag>:<bucket>` (`ArtworkLimits.kt:96`), **per server, not per user**, by design: a picture is the same for every profile. A screen can only build a ref from a row the profile may see. The loader's first interceptor refuses a ref whose session has no loaded token (`ArtworkGate.kt:50`) | Sound. `ArtworkResilienceTest.aSignedOutSessionGetsNoPictureFromMemoryOrDisk`, `ArtworkFetcherTest.anotherUserOfTheSameServerReusesTheEntry`. See I2 |
| 1 | **Session isolation: screens** | What each ViewModel shows and acts on when the active account changes | Every browsing ViewModel follows `activeSession()` with `flatMapLatest`, emits a fresh LOADING state for the new session, and acts as the session in its state. Home drops content whose session is not the current one | Sound, apart from items in the fix round now in flight (a failure message from one account shown under the next; grid paging and scroll carried across a switch) |
| 1 | **Session isolation: back stack and saved state** | What survives a switch | A switch from Home is safe (Home is the root). A switch from the Servers screen leaves Library, Series and Detail entries opened by the previous profile on the stack, and they re-scope to the new one (`ServersViewModel.kt:91`, `Routes.kt:91`). Saved state holds ids only (`SeriesViewModel` keeps the picked season with an owner tag) | **M1** |
| 2 | **Tokens and custom headers** | URLs, logs, exceptions, crash text, cache keys, database | Token only via `AuthInterceptor` (authority-exact, unchanged); no token in any query or path; cache keys and list keys from ids only; `AppError` messages are fixed text; new `require`/`check` messages are fixed ("Invalid API path.", "Not a server id."); nothing new written to the database is secret | Sound. Unchanged from M1: an OkHttp `IOException` kept as an `AppError` cause can name a host; it is never shown or logged in release |
| 3 | **Every request through the pinned, authenticated client, to the verified address only** | API and artwork; redirects; other hosts; cleartext | `ServerApi` is the only new token path. `ArtworkFetcher` reaches the network only through `ServerApi.getBytes`. No client follows redirects (a 3xx is `SERVER_NOT_FOUND`). The address must still be one of the server's saved addresses at send time (`ServerApi.kt:206`). One retry, through a freshly verified address. A guard refusal is never retried | Sound. `ServerApiRetryTest` (13), `ServerApiRealSelectorTest`. Cleartext: see M2 |
| 4 | **Untrusted server data** | Ids, sizes, markup, images, paths, SQL | **Ids:** `JellyfinId` at the mapper, the gateway (`idOf`), list keys, nav arguments (`wellFormed`), artwork refs (`ArtworkRef.kt:23`, tag `[A-Za-z0-9]{1,64}`); `ApiRequest.checkPath` rejects `..`, `//`, `?`, `#`, `\` and controls. **Sizes:** JSON 4 MiB on decoded bytes; names 200, overview 4,000 code points, views 100, seasons 1,000, episodes 10,000 in at most 50 requests, totals at most 1,000,000. **Markup:** `displayParagraphs` when stored, `plainOverview` when shown (tags dropped, entities decoded once, format characters removed, linear time), drawn by `Text` with no links. **Images:** 8 MiB; Content-Type and first bytes must agree (JPEG, PNG, WebP only); complete; animated refused; at most 8,192 a side and 40 MP declared; decoded to at most 4 MP and twice the width bucket; 2 decodes at once. **Paths:** cache folders named by a validated server id; Coil hashes keys to file names. **SQL:** Room bound parameters; LIKE escaped | Sound |
| 5 | **Local storage** | What is stored, backup, removal, cross-profile reach | **Room** (`shoalwatch.db`, app-private, not encrypted): item metadata per server; watch state, favourites, libraries, home rows and visibility per profile. **Pictures:** `cacheDir/artwork/<serverId>`, at most 250 MB per server. **Preferences:** `library_maintenance` (one timestamp). **Backup:** excluded. **Sign-out / account removal:** that profile's rows go by cascade; pictures go when it was the server's last account. **Server removal:** everything for it goes; pictures are cleared asynchronously. **Signed out by the server (401):** the account row and its cached rows stay (hidden; a new sign-in reuses them). **Cross-profile:** a restricted profile cannot read another's titles (visibility join) or pictures (refs only from visible rows). But see M1 and H1 | Sound, with L2 and L4 |
| 6 | **Exported components, intents, deep links, permissions, `FLAG_SECURE`** | Manifest and navigation | No manifest change; launcher only; no deep links; no new permission; no `PendingIntent`; no secret on browsing screens, so no `FLAG_SECURE` there (plan) | Sound |
| 7 | **Dependencies** | Versions, verification metadata | `io.coil-kt.coil3:coil-compose` 3.3.0 (brings `coil` and `coil-core`), plus their transitive AndroidX and Accompanist artefacts. Every one has sha256 entries; no `coil-network-*`; recorded under "Version checks" | Sound. Release manifest check (which also catches any new exported component) is run by the controller |
| 8 | **Stand-in server `tools/mockjelly`** | Bind address; secrets; file access | Fixed test credentials (`ben` / `test-password`) and random `mock-…` tokens: no real secret. It serves generated PNGs and in-memory JSON only (no file reads) and logs whether a token was present, never the token. **It binds to `0.0.0.0` by default** (`mock_jellyfin.py:427`, `README.md:17`) | **L3** |

---

## 4. Findings

### Critical

None.

### High

#### H1 — Any profile can switch to any other profile with one tap — design gap, decide before Milestone 5

- **Where:**
  - Home account menu (`ui/home/HomeTopBar.kt:161`)
  - Servers screen (`ui/servers/ServersScreen.kt:139`, `ServersViewModel.kt:91`)
- **What:**
  - On a shared tablet where a parent and a child are both signed in, the child can pick the parent's profile and browse everything the parent can.
  - No credential, PIN or device lock is asked for.
  - The cache's isolation between profiles holds, but it protects nothing if switching is free.
- **Why High:** the threat model for this check names exactly this device. It is not a defect in this milestone's code: switching was in Milestone 1, and the plan asks for a switcher. It matters more from Milestone 5, when shared downloads arrive.
- **Fix (a decision for Ben):** a per-profile "Protect this profile" setting. Switching to a protected profile asks for the device credential or biometric (`BiometricPrompt`, as S8 plans), or a short app PIN. Optionally, hide protected profiles from the quick switcher.

  Record the decision in SPEC §3.3 or S8. Build it no later than Milestone 7 (app lock), and before Milestone 5 if shared downloads go ahead first.

### Medium

#### M1 — After a switch from the Servers screen, the previous profile's screens fetch its items as the new profile

- **Where:**
  - `ServersViewModel.kt:91` and `Routes.kt:91`: a switch does not reset navigation
  - `LibraryRepository.kt:91-99`: an item fetch records visibility
  - `LibraryGridViewModel.kt:80`: an unknown library id is treated as a folder
- **What happens:**
  1. Profile A opens a library, a series or a film, then opens Servers from that screen and switches to profile B.
  2. Back returns to A's screen, now running as B.
  3. It requests A's ids with B's token: `GET /Items/{id}?userId=B`, `/Shows/{id}/Seasons`, `/Items?parentId=<A's library>`.
  4. If the server answers, the item is recorded as shown to B, appears on screen and stays in B's offline search.
- **Why it matters:** this relies entirely on the server refusing a restricted profile's fetch of an item by id. Whether Jellyfin 12.4 does that for every endpoint used is **not proven** (section 5).
- **Fix:**
  - When the active session changes to a different non-null session, clear the back stack to Home, in one place (`AppRoot`).
  - Add a navigation test: Series → Servers → switch → only Home remains, and no request is made for the old id.

#### M2 — Every library and artwork request carries the token over a consented plain-http home address, even when the https address answers

- **Where:** `ServerApi.kt:202-206`; the decision is recorded at `ARCHITECTURE.md:104`.
- **What:** Milestone 1 accepted that, on plain http, nothing proves the server is genuine. It asked that Milestone 2 decide whether token-bearing calls may use http while https answers. They do. So from this milestone the token goes, in clear, with every list refresh and every picture, whenever the phone is on the home network and the home address is http. That is not only at sign-in, as before.
  - On another network that happens to use the same address, a device posing as the server can collect the token. Its id check is public.
- **Why Medium:** it is the accepted residual, now on far more traffic. It needs the user's explicit consent per server, after a warning.
- **Fix (either):**
  - prefer a verified https address for ordinary requests too (one extra probe when both answer), keeping http only for http-only servers
  - or confirm the decision with Ben and make the consent warning say that every request, not just sign-in, is unencrypted

### Low

#### L1 — No overall deadline on a request

- **Where:** `ServerApi.kt:133-143`; `ARCHITECTURE.md:76` ("no call timeout").
- **What:** a server or proxy that sends one byte just inside the 30-second read timeout can hold a request indefinitely. Five such picture requests use up OkHttp's five slots for that host, and every refresh and tidy-up action to that server then waits. This is availability only: nothing leaks.
- **Fix:** a deadline inside `ServerApi` for API and picture calls (for example 60 s), leaving the future download client alone.

#### L2 — A removed server's pictures can stay on the phone

- **Where:** `ArtworkEviction.kt:24-28`; `ArtworkDiskCaches.kt:22-24`.
- **What:** pictures are cleared asynchronously after the server row is deleted. So two cases leave `cacheDir/artwork/<serverId>` behind:
  - the app dies in between
  - a fetch that had already passed the signed-in check writes after the clear

  Nothing later removes such a folder. The pictures cannot be shown (no account can build a ref for that server), but they stay on a lost device's storage.
- **Fix:**
  - at start-up, delete every artwork folder whose id is not in `servers`
  - refuse `forServer` for an id cleared in this process

#### L3 — The stand-in server listens on every network interface by default

- **Where:** `tools/mockjelly/mock_jellyfin.py:427`; `README.md:17`.
- **What:** run on a school or shared network, anyone on that network can sign in with the fixed test password. It holds no real data or secret.
- **Fix:** default to `127.0.0.1` (the emulator reaches it as `10.0.2.2`), keep `--host` for a LAN device test, and update the README.

#### L4 — What the server shows a profile is re-confirmed only on a detail fetch, or after 30 days

- **Where:**
  - `LibraryMaintenanceDao.kt:56` (30-day expiry)
  - `LibraryRepository.kt:91-99` (revocation on 403 or 404 for a single item)
- **What:** suppose a parent lowers a child profile's rating limit. Titles the child was already shown leave the child's lists on the next refresh, but stay findable in **offline** search for up to 30 days. They go at once if the child opens the title while online: the detail fetch is refused and the title is revoked.

  Two related effects:
  - A 401 from a verified address signs the profile out on this phone. On plain http an impostor or a captive portal could force that (denial of service only).
  - A misconfigured proxy answering 403 or 404 revokes an item until the server returns it again.
- **Fix:** accept and document (done here). Optionally shorten the expiry, or re-confirm visibility when a whole list is refreshed and an item has left every list of that profile.

### Informational

- **I1 — The library cache is not encrypted.** Titles, watch history, favourites and resume positions per profile are personal data. They are app-private and excluded from backup, but readable on a rooted or forensically imaged device. That is consistent with SPEC S1, which covers secrets only. Worth one line in a privacy note before release (UK GDPR, children's profiles).
- **I2 — Picture access is gated by how refs are made, not checked at the loader.** The loader checks that the session is signed in, but not that the item is visible to that session. Today a ref can only come from a visible row (`ArtworkBoundaryTest` keeps screens to the helpers). A visibility check in `ArtworkOnlyInterceptor` would make it structural, at the cost of one indexed query per picture.
- **I3 — Recents preview.** It shows the last browsing screen (titles, posters) to anyone who opens the app switcher. That is acceptable under the plan; S8 (Milestone 7) covers it.
- **I4 — A token rejected by the server is reloaded after a restart.** The in-memory "rejected" set (`ServerStore.kt:89`) is not saved, so the dead token is sent once more after each restart before the account is hidden again. It is the server's own token going to the server; scheduled for Milestone 5.

---

## 5. Unproven until a real device or Ben's Jellyfin 12.4 server

- [ ] **Restricted profile on 12.4:** with a parental rating limit on profile B, confirm `GET /Items/{id}?userId=B`, `/Shows/{id}/Seasons`, `/Shows/{id}/Episodes` and `/Items?parentId=` refuse (403 or 404) or omit an item above B's limit. M1, L4 and the visibility rule depend on it
- [ ] **Two profiles, one tablet:** signed in as both, browse as the adult, switch to the child from Servers, press Back. Expect Home (after the M1 fix) and nothing of the adult's
- [ ] **Android 10 (SQLite 3.22):** the library cache and every query run on API 29 (only API 34 has been run)
- [ ] **Release (R8) build:** browse, search and tidy-up actions work minified (kotlinx.serialization DTOs, Room, Coil)
- [ ] **`adb shell run-as app.shoalwatch`:** the database holds no token in clear. After **sign-out** of the last profile and after **remove server**, `cache/artwork/<serverId>` is gone and that profile's rows are gone
- [ ] **Backup:** `adb backup` and device-to-device transfer carry neither the library database nor the pictures
- [ ] **Artwork from the real server:** `format=Webp` answered as `image/webp`; real image tags pass `[A-Za-z0-9]{1,64}`; behind a reverse proxy, no 3xx, 401 or 403 on image paths
- [ ] **Reverse proxy and captive portal:** what a proxy's 3xx, 401 or 403 does to lists (revocation) and to the sign-in (local sign-out)
- [ ] **Mark watched twice** (one network retry): the play count on 12.4 goes up by one, not two
- [ ] **Sort order pairing** (`sortBy` and `sortOrder` arrays) as the reference describes
- [ ] **Home/away with an http home address:** confirm where the token goes when both addresses answer (M2), and that nothing goes to the home address while away
- [ ] **Large library on a phone:** a 5,000-film grid stays smooth while tidy-up actions and refreshes write
- [ ] **Carried from Milestone 1:** the whole section 4 checklist of `docs/security/milestone-1-check.md` (client certificates, Quick Connect and password on 12.4, revocation, tapjacking, Conscrypt probe deadline, two users on one DeviceId)

---

## 6. What changed since Milestone 1, in one line each

- **New:** one authenticated call path (`ServerApi`) for every library and picture request.
- **New:** the library cache, with per-profile visibility.
- **New:** the picture loader, which can fetch nothing but an `ArtworkRef`.
- **New:** a server's 401 now signs that profile out on this phone.
- **Decided:** Milestone 1's open question on http. Ordinary requests use a verified, consented http home address (M2 asks Ben to confirm).
- **Still open from Milestone 1** (scheduled in the Task 8 review triage):
  - S18 (probes send the device id)
  - the host-agnostic cleartext flag (before any server-supplied URL)
  - redaction gaps
  - the on-device checklist

---

## 7. Status after the check (15/11/2026, branch `milestone-2` at `929340b`)

The check above was made at `89c1705`. Three fix rounds followed, each reviewed again by reading the code; the last review approved the merge with nothing Critical or Important left.

| Finding | Status |
|---|---|
| **M1** switching account kept the previous profile's screens | **Fixed.** Any change of the signed-in account clears every browsing screen back to Home (or Welcome); the account screens stay on top so their notices are seen. Tested with the real navigation graph; sign-out checked by hand on the emulator. Switching between two accounts was not checked by hand (the stand-in server has one user) |
| **L3** the stand-in server listened on every address by default | **Fixed** on the Milestone 3 branch (default `127.0.0.1`); arrives on `main` with Milestone 3 |
| **H1** any profile can switch to any other with one tap | **Open by decision.** On the to-do for Milestone 7 ("protect this profile" with the phone's PIN or fingerprint). Must be in before any release |
| **M2** token sent over a consented plain-http home address even when https answers | **Open, kept as built.** On the to-do for Ben to confirm |

Found after the check and fixed in the same rounds:

- A failure message from one profile could appear under the next: messages now carry their profile and are dropped or dismissed on a change.
- Once a server was offline the app never checked again until restarted: a refresh now re-checks, and the app re-checks by itself (5 s rising to 60 s) only while it is in the foreground and never when the cause is a certificate problem.
- A failure reading the phone's own database could close the app or leave a dead screen: it is now reported with a working Try again, and a failed read is never treated as a sign-out.

Carried forward (Minor; listed in the to-do): the automatic re-check sends the server's custom headers and the device id to a saved address before it is verified (as every probe does, S18); a database failure during the picture clean-up check can delete a server's cached pictures; the smaller points N1 to N8 of the last review.

On-device suite at `929340b`: 20 of 20 on an Android 14 emulator. Still unproven until a real phone and Ben's server: everything in section 5.
