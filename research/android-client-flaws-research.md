# Android Jellyfin client flaws — research

**Researched:** 14/11/2026. **Scope:** everything except offline downloads (covered in `offline-downloads-research.md`; noted briefly here only).

---

## Summary (BLUF)

- **No Android phone client does everything well.** Each has one glaring gap: the official app is still a web-view shell with long-running player bugs; **Findroid** is native but **direct-play only** and has no casting, music or live TV; **Streamyfin** is feature-rich but has Android-specific lag and crash reports and leans on Google Play Services.
- **All three are actively maintained** (each had a release in Sept–Oct 2026).
- **The official app closed a lot of gaps in v2.7.0 (02/08/2026):** native player by default, trickplay, media-segment skip, seek gestures, rewritten downloads and Android Auto. Several multi-year complaints were closed by it.
- **Biggest unfixed complaint clusters** (by engagement):
  1. **Transcoding decisions** — wrong/unnecessary transcodes (official) or none at all (Findroid).
  2. **Connectivity** — no LAN/WAN address switching or multi-server in the official app; **mTLS / custom headers / SSO** missing nearly everywhere.
  3. **Chromecast** — unreliable (official), absent (Findroid, Fladder), Play-Services-only (Streamyfin).
  4. **Picture-in-picture and background audio** — broken on Android 14+ gesture navigation (official); no background audio (Findroid).
  5. **Subtitles** — styling, timing offset, ASS/PGS handling, track memory.
  6. **HDR / Dolby Vision** — black screens (DV profile 7), washed-out HDR.
- **Security posture is weak across the board:** cleartext HTTP permitted, tokens stored unencrypted in app databases, cloud backup enabled, no app lock, no client certificates. **No published security advisories exist for any of the three client repos**; the server and jellyfin-web have many.
- **Evidence gaps:** Reddit and Play Store pages could not be fetched. See "Not verified" at the end.

**How to read engagement figures:** "+1" = thumbs-up reactions on the GitHub issue; "c" = comment count; "votes" = features.jellyfin.org votes. All captured 14/11/2026 from the GitHub search API and the features.jellyfin.org API unless marked otherwise.

---

## Client landscape

| Client | Tech approach | Maintained? | Latest release (verified) | Strengths | Main weaknesses |
|---|---|---|---|---|---|
| **Jellyfin for Android** (official) [repo](https://github.com/jellyfin/jellyfin-android) | Kotlin shell around **jellyfin-web in a WebView**; native ExoPlayer/Media3 player (default since 2.7.0); external player option | **Yes** — pushed 14/11/2026; 2,869 stars | **v2.7.3 — 14/09/2026** (2.7.0 on 02/08/2026) | Full feature parity with web (admin, live TV, music, books, SyncPlay); Chromecast; Android Auto; on Play, F-Droid, GitHub | Web-view UI; wrong transcoding decisions; PiP broken on Android 14+; Chromecast control bugs; no multi-address/multi-server; no mTLS |
| **Findroid** [repo](https://github.com/jarnedemeulemeester/findroid) | Fully native Kotlin + Jetpack Compose (reworked in v1.0.0); ExoPlayer and mpv | **Yes** — pushed 14/11/2026; 4,309 stars; single main maintainer | **v1.2.0 — 25/10/2026** (needs server 12.0+, min Android 10) | Native feel; mpv; trickplay; segment skip; PiP; chapters; downloads | **Direct play only**; no Chromecast, SyncPlay, music, live TV; no background audio; requires newest server; slow maintainer response on issues |
| **Streamyfin** [repo](https://github.com/streamyfin/streamyfin) | React Native / Expo (TypeScript); MPV-based player, native player opt-in on Android | **Yes** — pushed 14/11/2026; 5,362 stars | **v0.55.2 — 16/10/2026** | Broadest feature set of the third-party apps: Seerr, Chromecast, custom auth headers, local-network auto-switch, sleep timer, subtitle styling | Android UI lag and crash reports; iOS-first; Chromecast needs Play Services; not on F-Droid; many requests closed "not planned" by stale bot |
| **Fladder** [repo](https://github.com/DonutWare/Fladder) | Flutter (Dart), cross-platform | **Yes** — v0.11.1; nightly 03/11/2026; 2,741 stars | **v0.11.1 — 08/09/2026** | Multi-user and server switching; transcoded playback; comics; adaptive layouts | No Chromecast (planned); ASS subtitles missing when transcoding; resume bugs (now fixed) |
| **Finamp** (music) [repo](https://github.com/jmshrv/finamp) | Flutter | **Yes** — 4,582 stars | **1.0.2 — 29/10/2026** | Dedicated music client; reached 1.0 in 2026 | Music only |
| **Jellyfin Android TV** [repo](https://github.com/jellyfin/jellyfin-androidtv) | Native Kotlin (TV) | **Yes** — 4,739 stars | **v0.20.0 — 10/11/2026** | — | TV only; shares subtitle-offset complaint ([182 votes](https://features.jellyfin.org/posts/3036/subtile-offset-on-android-tv)) |
| **Wholphin** [repo](https://github.com/damontecres/Wholphin) | Native Kotlin | **Yes** — 3,337 stars | **v1.1.1 — 06/11/2026** | — | Believed TV-only (not checked — see Not verified) |
| **Symfonium / Yatse** | Closed-source, paid | Symfonium appears active; Yatse **unconfirmed** | Not confirmed | — | Symfonium beta had a Jellyfin 12 RC compatibility error ([support thread](https://support.symfonium.app/t/jellyfin-12-rc1-symfonium-15-0-0-b7-returns-your-media-providersoftware-is-too-old-and-is-no-longer-supported/14336), second-hand via search) |

**Sources for release data:** GitHub REST API (`/repos/{repo}/releases`), queried 14/11/2026. Release notes: [official 2.7.0](https://github.com/jellyfin/jellyfin-android/releases/tag/v2.7.0), [official blog post](https://jellyfin.org/posts/android-v2.7/), [Findroid releases](https://github.com/jarnedemeulemeester/findroid/releases), [Streamyfin releases](https://github.com/streamyfin/streamyfin/releases).

### What v2.7.0 of the official app fixed (context for the table below)

- Native player **default** ([#2050](https://github.com/jellyfin/jellyfin-android/releases/tag/v2.7.0)); trickplay on the seek bar; media-segment skip button; horizontal seek gesture; long-press speed-up; chapter skipping.
- Android Auto rewritten; downloads rewritten and playable in-app.
- **Last release supporting Android 5 / 5.1** ([blog](https://jellyfin.org/posts/android-v2.7/)).
- One forum user reported the web player was **still their default after updating** ([forum thread](https://forum.jellyfin.org/t-jellyfin-for-android-2-7)) — likely an existing setting carried over (inference).

---

## Top complaints, ranked by engagement

| # | Complaint | Clients affected | Evidence | Engagement | Status |
|---|---|---|---|---|---|
| 1 | **Offline mode / downloads** (brief — see other file) | All | [features #218](https://features.jellyfin.org/posts/218/support-offline-mode-on-android-mobile); [official #139](https://github.com/jellyfin/jellyfin-android/issues/139); [features #1341](https://features.jellyfin.org/posts/1341/offline-sync-feature) | **1,827 votes** (top request on the whole board); #139 +1=53; #1341 821 votes | Open since 2019. Marked "started"; partly delivered in 2.7.0. Offline watch-state sync still planned |
| 2 | **"Continue Watching" / Next Up cannot be curated or is inaccurate** | All (server + client) | [features #517](https://features.jellyfin.org/posts/517/add-an-option-to-remove-an-item-from-continue-watching); [features #1055](https://features.jellyfin.org/posts/1055/give-the-option-to-combine-next-up-and-continue-watching); [official #1384](https://github.com/jellyfin/jellyfin-android/issues/1384); [Streamyfin #573](https://github.com/streamyfin/streamyfin/issues/573) | **1,741 votes**; 209 votes; #1384 +1=10 c=20 | #517 "planned" since 2020. Autoplay-next bugs fixed in official (2025–26) and Streamyfin (06/2026) |
| 3 | **Library browsing: pagination instead of lazy loading; web-view feel** | Official (web UI) | [features #216](https://features.jellyfin.org/posts/216/remove-pagination-use-lazy-loading-for-library-view); back button loses scroll position [official #2157](https://github.com/jellyfin/jellyfin-android/issues/2157); [touch-friendly controls #1026](https://github.com/jellyfin/jellyfin-android/issues/1026) | **1,186 votes**; #1026 +1=9 | "Started" since 2019, not done. Web-view sluggishness itself is second-hand only (see Not verified) |
| 4 | **Transcoding decisions wrong — or no transcoding at all** | Official, Findroid | [official #28](https://github.com/jellyfin/jellyfin-android/issues/28) (pinned, confirmed); [official #1612 H.264 hard-coded target](https://github.com/jellyfin/jellyfin-android/issues/1612); [Findroid #43](https://github.com/jarnedemeulemeester/findroid/issues/43); [Findroid #917](https://github.com/jarnedemeulemeester/findroid/issues/917) | #28 c=37; #1612 c=24; Findroid #43 **+1=53**; #917 +1=22 | #28 open **since 08/2020**. Findroid #43 open since 10/2021, no maintainer reply; README still says "Direct play only" |
| 5 | **mTLS / client certificates / custom headers / SSO** | Official, Findroid, Streamyfin (mTLS) | [jellyfin-meta discussion #96](https://github.com/jellyfin/jellyfin-meta/discussions/96); [features #1461](https://features.jellyfin.org/posts/1461/capability-to-specify-client-certificate-for-android-client); [official #1222](https://github.com/jellyfin/jellyfin-android/issues/1222); [Findroid #888](https://github.com/jarnedemeulemeester/findroid/issues/888); [Findroid #1016](https://github.com/jarnedemeulemeester/findroid/issues/1016); [Streamyfin #354](https://github.com/streamyfin/streamyfin/issues/354); [official #123 basic auth](https://github.com/jellyfin/jellyfin-android/issues/123); [features #230 OIDC](https://features.jellyfin.org/posts/230/support-for-oidc-oauth-sso) | Discussion **98 upvotes**; 56 votes; +1=24; **+1=28**; +1=9; +1=8 c=13; c=35; OIDC **1,214 votes** | No maintainer decision on #96 (opened 05/2025). Official #123 closed "not planned". Streamyfin mTLS closed "not planned"; **custom headers shipped in Streamyfin 0.55.0** (04/10/2026) |
| 6 | **No LAN/WAN address switching; weak multi-server / multi-user** | Official, Findroid | [official #291](https://github.com/jellyfin/jellyfin-android/issues/291); [official #1938 SSID-based switching](https://github.com/jellyfin/jellyfin-android/issues/1938); [features #47](https://features.jellyfin.org/posts/47/multiple-servers-support); [features #2348](https://features.jellyfin.org/posts/2348/multiple-account-switch); [Findroid #1171](https://github.com/jarnedemeulemeester/findroid/issues/1171); [official #544](https://github.com/jellyfin/jellyfin-android/issues/544) | #291 **+1=35** c=15; #1938 +1=8; 179 votes; 157 votes; #544 c=36 | #291 open **since 01/2021**. Streamyfin added local-network auto-switch in 0.54.1 (06/2026). Fladder has server switching |
| 7 | **Chromecast unreliable or missing** | Official (buggy); Findroid, Fladder (absent); Streamyfin (experimental) | [official #459 controls disappear](https://github.com/jellyfin/jellyfin-android/issues/459); [official #1745](https://github.com/jellyfin/jellyfin-android/issues/1745); [official #1573 crash](https://github.com/jellyfin/jellyfin-android/issues/1573); [official #234 cast quality](https://github.com/jellyfin/jellyfin-android/issues/234); [Streamyfin #131](https://github.com/streamyfin/streamyfin/issues/131); [Findroid #423](https://github.com/jarnedemeulemeester/findroid/issues/423) | #459 +1=12 **c=32**; #1745 **+1=34**; #1573 c=10; Streamyfin #131 c=11 | #459 open **since 07/2021**, still reported 07/2026; community fix commits (05/2026) not merged. #1745 fixed 12/2025. Findroid/Fladder: "planned" in READMEs |
| 8 | **Picture-in-picture broken on Android 14+** | Official; Streamyfin (earlier) | [official #1266](https://github.com/jellyfin/jellyfin-android/issues/1266) | +1=11 **c=44** | Open since 12/2023; still reported on Android 17 (26/10/2026). 2.7.0 "Fix PiP behavior" did not resolve it for reporters |
| 9 | **Background audio / music stutter in background** | Official, Findroid | [official #39](https://github.com/jellyfin/jellyfin-android/issues/39); [official #1378](https://github.com/jellyfin/jellyfin-android/issues/1378); [Findroid #867](https://github.com/jarnedemeulemeester/findroid/issues/867); [Findroid #528](https://github.com/jarnedemeulemeester/findroid/issues/528); [features #680](https://features.jellyfin.org/posts/680/background-playing-on-mobile) | #39 **+1=34 c=63**; Findroid #867 +1=18; #528 +1=8 | #39 took **six years** to close (08/2020 → 04/08/2026). Findroid requests open; audio muting after screen lock still reported on official 2.7.2 (in #1266 thread) |
| 10 | **Subtitles: styling, timing offset, ASS/PGS, wrong track** | All | [features #161](https://features.jellyfin.org/posts/161/improve-subtitle-appearance-options); [Findroid #739 timing](https://github.com/jarnedemeulemeester/findroid/issues/739); [Findroid #1115 styling ignored](https://github.com/jarnedemeulemeester/findroid/issues/1115); [official #1833 ASS](https://github.com/jellyfin/jellyfin-android/issues/1833); [official #944 wrong subtitle](https://github.com/jellyfin/jellyfin-android/issues/944); [Streamyfin #954](https://github.com/streamyfin/streamyfin/issues/954); [Fladder #912](https://github.com/DonutWare/Fladder/issues/912) | 189 votes; Findroid #739 **+1=17**; official #1833 +1=10; Streamyfin #954 c=37 | Mixed. Official #1833 closed 03/2026. Streamyfin offset + styling shipped 0.55.0. Findroid offset open since 04/2024. PGS in official ExoPlayer flagged in #28 |
| 11 | **Audio/subtitle track choice not remembered** | Findroid, Streamyfin (fixed), official (fixed) | [features #194](https://features.jellyfin.org/posts/194/remember-subtitle-and-audio-track-choice-between-episodes); [Findroid #891](https://github.com/jarnedemeulemeester/findroid/issues/891); [Streamyfin #618](https://github.com/streamyfin/streamyfin/issues/618); [official #1502](https://github.com/jellyfin/jellyfin-android/issues/1502) | **335 votes**; +1=9; c=21; c=27 | Server feature "completed". Findroid open since 11/2024. Streamyfin fixed 10/2026 (tracks now selected by identity). Official fixed 08/2026 |
| 12 | **HDR / Dolby Vision failures** | Official, Findroid, Streamyfin | [official #1868 DV7 black screen](https://github.com/jellyfin/jellyfin-android/issues/1868); [official #1810](https://github.com/jellyfin/jellyfin-android/issues/1810); [official #1867 tablets](https://github.com/jellyfin/jellyfin-android/issues/1867); [Findroid #582](https://github.com/jarnedemeulemeester/findroid/issues/582); [Findroid #868](https://github.com/jarnedemeulemeester/findroid/issues/868); [Streamyfin #342](https://github.com/streamyfin/streamyfin/issues/342) | #1868 +1=7 c=17; Findroid #582 +1=13; Streamyfin #342 +1=11 c=23 | Official and Findroid open. Streamyfin washed-out HDR closed 06/2026. Findroid's mpv path does not do HDR well — users ask for an auto-switch to ExoPlayer |
| 13 | **Skip intro/credits and trickplay arrived late** | Official (was) | [features #45](https://features.jellyfin.org/posts/45/skip-intro-outro-credits-feature); [official #1465 trickplay](https://github.com/jellyfin/jellyfin-android/issues/1465) | **1,089 votes**; +1=12 | **Fixed.** Findroid had both from v0.15.0 (08/2024); official native player only from 2.7.0 (08/2026). Remaining ask: button visibility time ([49 votes](https://features.jellyfin.org/posts/3035/intro-outro-button-visibility-time)) |
| 14 | **Player gestures and touch controls** | Official | [official #1517](https://github.com/jellyfin/jellyfin-android/issues/1517); [#1886](https://github.com/jellyfin/jellyfin-android/issues/1886); [#1940 double-tap](https://github.com/jellyfin/jellyfin-android/issues/1940); [#1654 orientation toggle](https://github.com/jellyfin/jellyfin-android/issues/1654) | +1=16; +1=3; +1=2; +1=7 | #1517 closed 07/2026 (seek gesture in 2.7.0). Double-tap and orientation lock still open |
| 15 | **Bitrate limit ignored; no auto quality on mobile data** | Official, Streamyfin | [official #564](https://github.com/jellyfin/jellyfin-android/issues/564); [official #718](https://github.com/jellyfin/jellyfin-android/issues/718); [Streamyfin #2039 auto quality](https://github.com/streamyfin/streamyfin/issues/2039); [Streamyfin #2037 jittery cellular](https://github.com/streamyfin/streamyfin/issues/2037) | #564 +1=11 c=16; #718 c=30 | #564 open **since 11/2021**. #718 closed "not planned" 10/2026 |
| 16 | **Sleep timer missing** | Official | [official #632](https://github.com/jellyfin/jellyfin-android/issues/632); [Streamyfin #683](https://github.com/streamyfin/streamyfin/issues/683) | **+1=23**; +1=6 | Official open since 04/2022. Streamyfin shipped 0.55.0 |
| 17 | **Music gaps: gapless, pre-buffering, offline cache** | Official; Findroid (no music at all) | [features #181 gapless](https://features.jellyfin.org/posts/181/gapless-playback); [official #981](https://github.com/jellyfin/jellyfin-android/issues/981); [official #357](https://github.com/jellyfin/jellyfin-android/issues/357); [Findroid #516](https://github.com/jarnedemeulemeester/findroid/issues/516) | **655 votes**; +1=12; +1=8; +1=12 | All open. Users typically move to Finamp or Symfonium for music (inference from landscape) |
| 18 | **Server-version coupling breaks clients** | Official, Findroid | [official #1382 stuck at login after 10.9](https://github.com/jellyfin/jellyfin-android/issues/1382); [Findroid #1134 support older servers](https://github.com/jarnedemeulemeester/findroid/issues/1134); [Findroid releases](https://github.com/jarnedemeulemeester/findroid/releases) | #1382 **c=61**; Findroid #1134 +1=8 | #1382 fixed in a week (05/2024). Findroid 1.0.0 required server 10.11; **1.2.0 requires server 12.0** — open complaint |
| 19 | **Android UI lag / slow UI (Streamyfin)** | Streamyfin | [Streamyfin #379](https://github.com/streamyfin/streamyfin/issues/379); [#973](https://github.com/streamyfin/streamyfin/issues/973); [#764](https://github.com/streamyfin/streamyfin/issues/764) | #379 c=20; #973 c=32; #764 c=18 | #379 open since 01/2025; maintainers cannot reproduce. #973 closed 12/2025 |
| 20 | **Crashes and regressions after updates** | All | Official: [#2209 crash since 2.7.3](https://github.com/jellyfin/jellyfin-android/issues/2209), [#853 video stuck until touch](https://github.com/jellyfin/jellyfin-android/issues/853), [#1495](https://github.com/jellyfin/jellyfin-android/issues/1495). Findroid: [#1246 mpv freeze after seek](https://github.com/jarnedemeulemeester/findroid/issues/1246), [#686 purple screen](https://github.com/jarnedemeulemeester/findroid/issues/686). Streamyfin: [#1738](https://github.com/streamyfin/streamyfin/issues/1738), [#974](https://github.com/streamyfin/streamyfin/issues/974), [#2390 settings reset](https://github.com/streamyfin/streamyfin/issues/2390), [#526 half-black screen](https://github.com/streamyfin/streamyfin/issues/526) | #853 +1=11 c=31; #1495 c=27; Findroid #1246 +1=10; Streamyfin #1738 c=18; #526 +1=16 | #853 open since 10/2022 (reopened). #2209 open. Findroid #1246 open. Streamyfin #526 closed 10/2026 after ~20 months |
| 21 | **Missing content types in third-party clients** (live TV, SyncPlay, extras, home videos) | Findroid, Streamyfin | [Findroid #117 SyncPlay](https://github.com/jarnedemeulemeester/findroid/issues/117); [Findroid #184 live TV](https://github.com/jarnedemeulemeester/findroid/issues/184); [Findroid #292 extras](https://github.com/jarnedemeulemeester/findroid/issues/292); [Findroid #884 home videos](https://github.com/jarnedemeulemeester/findroid/issues/884); [Streamyfin #184 SyncPlay](https://github.com/streamyfin/streamyfin/issues/184); [Streamyfin #121 live TV](https://github.com/streamyfin/streamyfin/issues/121) | +1=13; c=14; +1=12; +1=9 c=14; +1=5; +1=7 | Findroid all open. Streamyfin SyncPlay and live TV closed "not planned" |
| 22 | **Distribution: Play Services dependence, update lag, no signing fingerprints** | Streamyfin, Findroid, official | [Streamyfin #519 run without Play Services](https://github.com/streamyfin/streamyfin/issues/519); signing-hash requests: [official #1570](https://github.com/jellyfin/jellyfin-android/issues/1570), [Findroid #944](https://github.com/jarnedemeulemeester/findroid/issues/944), [Streamyfin #1485](https://github.com/streamyfin/streamyfin/issues/1485); [official #380 own F-Droid repo](https://github.com/jellyfin/jellyfin-android/issues/380); F-Droid pages: [official](https://f-droid.org/en/packages/org.jellyfin.mobile/), [Findroid](https://f-droid.org/en/packages/dev.jdtech.jellyfin/) | #519 +1=7; #380 +1=10 | Streamyfin #519 closed "not planned"; Streamyfin is **not on F-Droid** (README lists Play, GitHub, Obtainium). **Findroid 1.2.0 (25/10) not on F-Droid as of 14/11** — still 1.1.0. Official F-Droid lag is ~2 days |

### Smaller but recurring (not ranked)

- **Android App Links** (open server URLs in the app): [official #903](https://github.com/jellyfin/jellyfin-android/issues/903) — +1=22, open since 11/2022.
- **Battery drain:** [Findroid #559 "Awful battery life"](https://github.com/jarnedemeulemeester/findroid/issues/559) — c=8, open since 10/2023.
- **Subtitles missing in external players:** [official #587](https://github.com/jellyfin/jellyfin-android/issues/587) — c=19, open since 2021.
- **Shuffle:** [Findroid #547](https://github.com/jarnedemeulemeester/findroid/issues/547) — +1=11.
- **Hide watched / list view / filter by language:** [Findroid #1175](https://github.com/jarnedemeulemeester/findroid/issues/1175), [Findroid #1135](https://github.com/jarnedemeulemeester/findroid/issues/1135), [Streamyfin #1940](https://github.com/streamyfin/streamyfin/issues/1940), [features #39](https://features.jellyfin.org/posts/39/filter-videos-by-audio-stream-language) (172 votes).
- **Search regressions:** [Findroid #1153 search no longer shows episodes](https://github.com/jarnedemeulemeester/findroid/issues/1153).
- **Home-screen widget:** [official #1223](https://github.com/jellyfin/jellyfin-android/issues/1223) — +1=1 (low demand).
- **Accessibility:** [Streamyfin #2330 header icons cut off at large text sizes](https://github.com/streamyfin/streamyfin/issues/2330).
- **Watchlist:** [features #576](https://features.jellyfin.org/posts/576/watchlist-like-netflix) — 1,306 votes, planned (server-side).
- **Playback stop position sent as 0 on Bluetooth disconnect:** [official #2084](https://github.com/jellyfin/jellyfin-android/issues/2084) — a resume-position bug, low engagement.

### Maintainer responsiveness (observed)

- **Findroid:** top requests (#43, #888) have **no maintainer reply** beyond a label, across 2–5 years.
- **Streamyfin:** many well-supported requests were closed **"not planned"** (OIDC, mTLS, multi-server, SyncPlay, live TV, PiP auto-enter) — pattern consistent with a stale bot (inference from closure reasons and a stale-bot mention in #379).
- **Official:** confirmed bugs stay open for years (#28 since 2020, #459 since 2021, #564 since 2021).

---

## Security and privacy

### Verified directly from source / advisories

| Area | Finding | Source |
|---|---|---|
| **Cleartext HTTP** | Official app permits cleartext traffic for all hosts (`cleartextTrafficPermitted="true"`) | [network_security_config.xml](https://github.com/jellyfin/jellyfin-android/blob/master/app/src/main/res/xml/network_security_config.xml) |
| **User-installed CAs** | Official app trusts **user-added CAs** as well as system CAs — helps self-signed setups, widens interception risk | Same file |
| **Cleartext (Streamyfin)** | `usesCleartextTraffic: true` on Android; `NSAllowsArbitraryLoads: true` on iOS | [app.json](https://github.com/streamyfin/streamyfin/blob/develop/app.json) |
| **Token storage (official)** | Access token is a plain `String` column in a Room database (`UserEntity.accessToken`) | [UserEntity.kt](https://github.com/jellyfin/jellyfin-android/blob/master/app/src/main/java/org/jellyfin/mobile/data/entity/UserEntity.kt) |
| **Cloud backup (official)** | `allowBackup="true"`; backup rules **include the database and WebView data** — tokens can leave the device via Android backup | [AndroidManifest.xml](https://github.com/jellyfin/jellyfin-android/blob/master/app/src/main/AndroidManifest.xml), [backup_content.xml](https://github.com/jellyfin/jellyfin-android/blob/master/app/src/main/res/xml/backup_content.xml) |
| **Token storage (Findroid)** | `accessToken` is a plain field on a Room entity; `allowBackup="true"` with `fullBackupOnly="true"` | [User.kt](https://github.com/jarnedemeulemeester/findroid/blob/main/data/src/main/java/dev/jdtech/jellyfin/models/User.kt), [phone manifest](https://github.com/jarnedemeulemeester/findroid/blob/main/app/phone/src/main/AndroidManifest.xml) |
| **Tokens in logs (Streamyfin)** | v0.55.2 notes say access tokens are now **redacted from the app log and exported logs**, including older entries — so they were logged before | [Streamyfin releases](https://github.com/streamyfin/streamyfin/releases) |
| **Crash reporting (Streamyfin)** | Sentry Expo plugin is configured in the build (`organization: streamyfin`, sentry.io); README has no telemetry/privacy statement | [app.json](https://github.com/streamyfin/streamyfin/blob/develop/app.json) |
| **Permissions (Streamyfin)** | Requests `WRITE_SETTINGS` and `ACCESS_FINE_LOCATION` | [app.json](https://github.com/streamyfin/streamyfin/blob/develop/app.json) |
| **Certificate bypass requests** | Users ask to skip certificate verification entirely ([official #1614](https://github.com/jellyfin/jellyfin-android/issues/1614), +1=10, open) | Issue |
| **Self-signed cert trouble** | [official #1225](https://github.com/jellyfin/jellyfin-android/issues/1225) (c=25, closed "not planned" 01/2026); [#301](https://github.com/jellyfin/jellyfin-android/issues/301) | Issues |
| **Client advisories** | **None published** for [jellyfin-android](https://github.com/jellyfin/jellyfin-android/security/advisories), [Findroid](https://github.com/jarnedemeulemeester/findroid/security/advisories), [Streamyfin](https://github.com/streamyfin/streamyfin/security/advisories) | Advisory pages |

### Server and web advisories relevant to clients (verified)

- **jellyfin-web** — [GHSA-mqq3-m779-w9qm](https://github.com/jellyfin/jellyfin-web/security/advisories/GHSA-mqq3-m779-w9qm): **stored XSS** via external URLs on the item details page. High (CVSS 7.6). Fixed in 12.2. Published 18/10/2026. No CVE.
  - **Inference:** the official Android app renders jellyfin-web in a WebView, so server-served web XSS runs inside the app session. The advisory itself does not say this.
- **jellyfin-web** — [GHSA-89hp-h43h-r5pq](https://github.com/jellyfin/jellyfin-web/security/advisories): stored XSS in devices.js. Critical. 23/04/2023.
- **Server** — [GHSA-4vx8-xhc9-qg6x](https://github.com/jellyfin/jellyfin/security/advisories/GHSA-4vx8-xhc9-qg6x): **broken access control in the session remote-control API**. Any authenticated user could control another user's client session; session IDs are an MD5 of client name + device ID. Moderate (5.4). Fixed in 12.0. Published 22/09/2026. **Directly client-facing.**
- **Server, 2026 batch** ([page 1](https://github.com/jellyfin/jellyfin/security/advisories), [page 2](https://github.com/jellyfin/jellyfin/security/advisories?page=2)):
  - 22/09/2026 — path traversal in virtual-folder endpoints (**Critical**, GHSA-6828-c7cx-hvqm); SVG memory exhaustion + SSRF (High); playlist IDOR (Moderate); HLS segment and plugins path traversal.
  - 10/06/2026 — FFmpeg argument injection via subtitle path (High); `/ClientLog/Document` path traversal (Moderate); XSS in user management (Moderate).
  - 14/04/2026 — RCE via subtitle upload path traversal + .strm (**Critical**, GHSA-j2hf-x4q5-47j3); SSRF/file read via stream argument injection (High); SyncPlay group-name DoS (Moderate).
  - Earlier — FFmpeg argument injection (04/2025, 12/2023); SVG-upload privilege escalation (09/2024).
- None of the listed advisories showed a CVE ID on the pages fetched.

### Gaps that follow from the above (design implications — inference)

- **No client stores tokens in the Android Keystore / encrypted storage** (true for official and Findroid from the source read; Streamyfin not checked).
- **No app lock / biometric gate** found in any client's top issues; a features.jellyfin.org search for "biometric" returned nothing — so it is **absent and not loudly requested**.
- **No on-device parental profile lock** evidence found either way.
- **2FA** ([1,109 votes](https://features.jellyfin.org/posts/26/add-support-for-two-factor-authentication-2fa)) and **OIDC/SSO** ([1,214 votes](https://features.jellyfin.org/posts/230/support-for-oidc-oauth-sso)) are server-side gaps; clients inherit them. **Quick Connect with QR code** is planned ([227 votes](https://features.jellyfin.org/posts/2637/quick-connect-with-qr-code)).

---

## Not verified

- **Reddit (r/jellyfin, r/selfhosted):** not accessible to the fetch or search tools. **No Reddit evidence is included.** Thread counts and sentiment there are unmeasured.
- **Play Store ratings and review themes:** the listing pages returned no content. Third-party trackers give roughly **4.0–4.3 stars from ~6,000 ratings, ~1M installs** for the official app, but they disagree and some are stale ([App Pricing Lab](https://apppricinglab.com/app/google_play/org.jellyfin.mobile), [AppGoblin](https://appgoblin.info/apps/org.jellyfin.mobile)). Treat as second-hand. No ratings obtained for Findroid or Streamyfin.
- **"Web-view is sluggish / non-native" as a mass complaint:** seen only second-hand (forum and Lemmy snippets via search, e.g. [forum thread](https://forum.jellyfin.org/t-android-app-for-movies-and-tv)). No single high-engagement primary issue found; the closest proxies are features #216 and official #1026 / #2157.
- **Comparison blogs** (jellywatch.app) surfaced in search were **not used** — the publisher sells a competing client.
- **Streamyfin token storage** (SecureStore vs plain storage) and whether **Sentry is active at runtime / opt-in** — not read from source.
- **Findroid network security config** — file not found at the guessed path; cleartext and CA trust settings for Findroid are unconfirmed.
- **Tokens in stream URLs** (`api_key` query parameter) — commonly stated, **not checked** here.
- **Wholphin** — assumed Android-TV-only; README not read.
- **Finamp top issues** — the search API returned an error; only release data confirmed.
- **Symfonium / Yatse** — current versions and Yatse's Jellyfin support status not confirmed.
- **Fladder player backend and F-Droid presence** — not stated in the README content fetched.
- **Whether official 2.7.x actually resolves #28** (unnecessary transcoding) — 2.7.0 added codec-profile conditions and device H.264 level detection, but the issue is still open and nobody has confirmed either way.
- **Official #459 fix:** community commits exist (05/2026) but no merge was visible.
- **Material You / dynamic colour, tablet/foldable layout, TalkBack accessibility:** no high-engagement issues found in the top lists; **absence of evidence, not evidence of absence**.
- **Audio passthrough on phones:** no notable phone-side issues found (Streamyfin added Atmos passthrough for Android TV only).
- **Battery drain** beyond Findroid #559 — no measurements found.
- **Jellyfin 12 release date** — inferred to be before 25/10/2026 from Findroid 1.2.0 and advisory patch versions; not looked up.
- **Engagement counts** are a snapshot from 14/11/2026; "comments" include bot and off-topic comments.
