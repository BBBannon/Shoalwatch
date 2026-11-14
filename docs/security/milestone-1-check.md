# Milestone 1 security check (S13)

**Date:** 14/11/2026 · **Branch:** `milestone-1`, first checked at `7b6a098`, updated after the hardening fixes (`3baf21b`..`12f5811`) and the closing fixes F1 to F11 (section 6) · **Scope:** SPEC §6, S1–S13, threat model end to end

---

## 1. Summary verdict

**Ready to sign off, subject to the on-device checklist in section 4.** The six security fixes the first check required are done and tested, and the final re-review's corrections (section 6) are closed. Nothing critical remains. One residual risk is accepted and stated plainly: on a plain-http address, nothing can prove the server is genuine (section 5).

| Area | Verdict |
|---|---|
| Tokens at rest, logs, URLs, `toString()` | **Strong.** Encrypted, never logged, never in a URL; screens never decrypt them |
| TLS trust, pins, user CAs | **Strong.** Fails closed, constant-time pin check, damaged pin blocks, re-trust re-checks the server |
| First contact before credentials | **Good.** Credentials go only to a verified address, https preferred; a route that turns out unencrypted at submit waits for the user to see the notice; Quick Connect stops if the network changes. **On http only**, the identity check can be faked (accepted residual, warned) |
| Sign-out / remove server | **Fixed.** Token only to an address that reports this server's id (verified, https preferred), through its own one-off client; remove server revokes; the user is told when the server was not reached **or the saved token could not be read** |
| Client certificates | **Fixed.** Detected structurally on TLS 1.2 and 1.3; only a handshake failure, or a first 400/403 or first-read failure on a fresh connection that was asked, is blamed on the certificate; the "Choose certificate" step is reachable |
| Build and supply chain | **Good.** Wrapper checksum-pinned, merged release manifest checked by the build (APK and bundle; no `networkSecurityConfig`; each rule proved to fail on a fixture), repositories filtered |

**Unit suite:** 487 tests, 0 failures (`testDebugUnitTest`, after the closing fixes; was 448 after the hardening fixes, 383 at the first check).
**Release build:** `assembleRelease` and `bundleRelease` run `verifyReleaseManifest` on the merged release manifest; `check` also runs `verifyManifestRuleFixtures`.

### What changed since the first check

| First-check item | Now | Commit |
|---|---|---|
| 1. Client-certificate servers cannot be added (S5) | **Fixed** | `4af25a4` |
| 2. Credentials to the home http address on any network (S3) | **Fixed** as far as http allows: https preferred, notice shown, warning reworded, residual recorded | `5fda96a` |
| 3. Sign-out sends the token to unchecked addresses (S6) | **Fixed** | `5fda96a` |
| 4. Remove server never revokes | **Fixed** | `5fda96a` |
| 5. Custom headers follow cross-host redirects (S6) | **Fixed** | `3baf21b` |
| 6. Merged manifest exports `ProfileInstallReceiver`; wrapper not pinned (S10, S12) | **Fixed** | `7f9f317` |

---

## 2. S1–S13

| ID | Requirement (short) | Status | Evidence (current code) | Proved by |
|---|---|---|---|---|
| **S1** | Tokens and headers encrypted with a Keystore AES-256-GCM key; passwords never stored | **Met** | `ServerStore` seals headers and tokens (on the IO dispatcher); `SecretBox`; `KeystoreKeyProvider` (AES-256, GCM, randomised IV, StrongBox then TEE); password only ever as `CharArray`, wiped in `AuthRepository` and `SignInViewModel`. **New:** screens read only non-secret columns, so tokens are not decrypted into memory for lists; account lists wait for the first warm and show only accounts whose token loaded, so an unreadable account is never listed or active (F10) | `ServerStoreTest.tokenAndHeadersAreStoredOnlyAsCiphertext`; `SecretBoxTest`; **new** `TokenFreeReadsTest` (no key use during UI reads; every key use on IO); `WarmGateTest` (lists wait for the warm; unreadable or failed-warm accounts hidden everywhere); **on device** `KeystoreSecretBoxTest` (3/3). **Not done:** the `adb run-as` check that the real DB holds no plaintext token |
| **S2** | `allowBackup=false`; extraction rules exclude everything | **Met** | `AndroidManifest.xml`; `data_extraction_rules.xml`; `backup_rules.xml`. **New:** checked on the merged release manifest | `ManifestSecurityTest`; **new** `verifyReleaseManifest` Gradle task. **Not done:** a real `adb backup` / device-transfer attempt |
| **S3** | HTTPS by default; http only after a per-server warning; enforced by an interceptor | **Met, with an accepted residual** | `CleartextGuard` at both interceptor levels; consent per address. **New:** `AuthRepository` sends a credential over http only when the server has no https address or it does not answer as the server (`credentialAddress`); the Sign in screen shows "This connection isn't encrypted" whenever that happens, **and a route that turns out to be unencrypted at submit is not used until the user has seen the notice and pressed Sign in again** (the password stays in the field; Quick Connect waits the same way, F1); Quick Connect also stops, sending no further secret, if the verified connection changes while it polls (F2); the http warning now says a device on another network could pose as the server. **Residual:** on http, the identity check cannot be authenticated (section 5) | `CleartextGuardTest`; `HttpClientFactoryTest`; **new** `SignInRouteTest` (both answer → https; https down → http, flagged; http-only → flagged), `SignInViewModelTest` route/notice cases (https at open, http at submit: no credential call, notice set, password kept; second press sends; steady http sends first time), `QuickConnectConnectionChangeTest`, `ScreenRenderTest` notice cases, `RevocationOverNetworkTest` |
| **S4** | No user CAs; trust on first use by fingerprint; a later change blocks | **Met** | No `networkSecurityConfig` (**new:** the release build fails if the merged manifest sets one, F7); `PinnedTrustManager`; `PinAwareHostnameVerifier`; constant-time compare; damaged pin fails closed. **New:** re-trusting a changed certificate pins it only after the address answers, with the new pin, as the same server | `HttpClientFactoryTest`; `PinnedTrustManagerTest`; **on device** `TrustOnDeviceTest` (5/5, Conscrypt); `ServerStoreTest.corruptStoredPin…`; **new** `ServerEditViewModelTest.reTrustingChecksItIsStillTheSameServer…`; **new** `verifyManifestRuleFixtures` (`bad-network-security-config.xml` fails) |
| **S5** | Client certs from KeyChain; app never handles the private key | **Met (JVM); device unproven** | `KeyChainKeyManager` offers only the stored alias. **New:** `ClientCertWatch` records whether the server sent a CertificateRequest (the key manager is asked for an alias) and, when no alias is set, maps only (a) a failure during the TLS handshake of a call that was asked, and (b) a 400/403 or first-read failure on the first response of a fresh connection that was asked, to `CLIENT_CERT_REQUIRED`; a later 403 or a timeout on a connection that has already answered keeps its own meaning (F5) | **New** `ClientCertificateTest` (MockWebServer `requireClientAuth`, TLS 1.3 and 1.2 pinned server-side, optional request served normally, nginx-style 400, chosen certificate accepted; **new:** optional auth + 200 then 403 is `FORBIDDEN`, + 200 then a read timeout is `TIMEOUT`); **new** `ClientCertFlowTest` (real add-server flow; self-signed + client cert: trust first, then choose). The fake-only ViewModel test was removed. **Unproven on device** (Conscrypt, real KeyChain) |
| **S6** | Token only in `Authorization`, only to its server; never in URL, log, crash text, notification or `toString()` | **Met** | `AuthInterceptor` (authority-exact; strips `api_key` and legacy headers). **New:** no factory client follows redirects; `CustomHeaderInterceptor` removes configured headers for any other authority; sign-out and remove server send the token only to an address that reported this server's id on the current network, https preferred, never to the raw saved list; with none verified, nothing is sent. **New (F3):** the token being revoked travels through its own one-off client, never the shared per-account cache. On plain http the id check can still be repeated by an impostor (see Residual risks) | `AuthInterceptorTest`; `AccountTokenTest`; **new** `RedirectPolicyTest` (302 and 307 to a second server: 0 requests there; with redirects forced on, no token or header there), `RevocationTest`, `RevocationOverNetworkTest` (a server reporting a different id on the home address receives 0 token-bearing requests), **new** `RevocationClientCacheTest` (a live client holding token A is untouched; the revoked token B is sent; no client holding B stays cached) |
| **S7** | No analytics, crash reporting or third-party calls | **Met** | No SDKs; only user-typed addresses are contacted. **New:** the EmojiCompat initialiser (could ask Google Play services for a font) is removed from the manifest | **New** `verifyReleaseManifest` fails if it returns |
| **S8** | Optional app lock; optional recents hiding | **N/A until M7** | Partial already: `FLAG_SECURE` on Sign in, Connection settings and dialogs | Controller: screencap blocked on Sign in |
| **S9** | Minimum permissions | **Met** | Merged release manifest: `INTERNET`, `ACCESS_NETWORK_STATE`, androidx-core's signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | `ManifestSecurityTest` (source); **new** `verifyReleaseManifest` (merged release: exactly these three) |
| **S10** | App-specific storage; only the launcher exported; immutable PendingIntents | **Met** | **New:** `ProfileInstallReceiver` and its initialiser removed (`tools:node="remove"`); the merged release manifest exports only `MainActivity`. No PendingIntents. Downloads N/A until M4 | **New** `verifyReleaseManifest` (RED before the fix: it named the exported receiver) |
| **S11** | Responses untrusted: no server file names, sizes bounded, HTML as text | **Met (for M1)** | 1 MiB cap; `ignoreUnknownKeys`; errors never echo the body; any 3xx is an error. **New:** server and user ids must be GUIDs and are stored normalised (`JellyfinId`); server name, user name, version and certificate names are sanitised and capped (`displayText`: every Unicode format character removed, an empty result replaced by a neutral name; F6); the Quick Connect code must be 4 to 12 ASCII letters or digits; the store refuses any id that is not already normalised (F9). HTML N/A until M2; file names N/A until M4 | `JellyfinGatewayTest`; **new** `GatewayBoundaryTest` (dashed/upper-case ids → one key; non-GUID rejected; bidi/control/overlong text cleaned; invisible-only names; code validation), `DisplayTextTest`, `ServerStoreTest` (ids) |
| **S12** | Pinned, checksum-verified dependencies; release minified, non-debuggable, signed locally | **Met** | No dynamic versions; sha256 verification; `FAIL_ON_PROJECT_REPOS`; R8 + shrink; signing from git-ignored `keystore.properties`. **New:** `distributionSha256Sum` for `gradle-8.11.1-bin.zip` (official `.sha256`, confirmed against the downloaded bytes); `google()` limited to `androidx.*`, `com.android.*`, `com.google.*`; checksum-only verification written down as a decision in `ARCHITECTURE.md`. Still open (minor, M8): stale `org.jellyfin.sdk` checksums; unused `datastore`/`lifecycle-process` | Full build `clean testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease`; `./gradlew --version` with the pinned wrapper |
| **S13** | Security check each milestone | **Met** | This document | — |

---

## 3. Threat-model findings

### T1 — Sign-out could hand the token to an impostor — **Fixed**

- **Was:** `signOut` tried the saved addresses in order, home (http) first, with no identity check, and stopped at the first 2xx.
- **Now:** the token goes only to an address the selector has verified on the current network (`awaitOnline`), upgraded to the server's https address when that also answers as the server, with the identity checked again immediately before. If nothing is verified, the token is sent nowhere. The account is removed here regardless, and the Servers screen says: "Signed out on this phone. Shoalwatch couldn't sign you out on the server, so that sign-in may still be active there — you can remove it from the server's dashboard." The same notice appears when the saved token cannot be read (lost Keystore key), which is no longer reported as a clean sign-out (F4).
- **Limit:** An address that reports a different server id never receives the token. On plain http, a device that repeats the server's public id can still pass the check and receive the token being revoked (see Residual risks); https is preferred whenever it answers.
- **Tests:** `RevocationTest` (an address reporting a different server id never receives the token; https preferred; offline sends nothing; bounded at 12 s), `RevocationOverNetworkTest` (real OkHttp, two MockWebServers).

### T2 — On http, the server-id check is not authentication — **Reduced; residual accepted**

- **Was:** sign-in used whatever address the selector chose, home first, on any network.
- **Now:**
  - credentials (password, Quick Connect, revocation) use an https address of the server whenever one answers as the server; http only when it is the only address or the https one is unreachable
  - the Sign in screen shows a persistent "This connection isn't encrypted" notice whenever the route is http; a route that turns out unencrypted at submit is not used until the user has seen that notice and pressed Sign in again (F1)
  - the http consent warning adds: "On another network that happens to use the same address, a different device could pose as your server and capture your sign-in."
- **Residual:** see section 5. Before M2: decide whether token-bearing library calls may use http while an https address answers (the credential rule above is the starting point).

### T3 — Client certificates could not be set up — **Fixed**

- `ClientCertWatch` installs a recording key manager on every client. The TLS layer asks it for a client alias only when the server sent a CertificateRequest, so the app knows structurally that a certificate was asked for.
- With no alias configured, only these become `CLIENT_CERT_REQUIRED`: a failure during the TLS handshake of a call that was asked (call marked via an `EventListener`), and a 400/403, or for TLS 1.3 an IOException on the first read, on the first response of a freshly handshaken connection that was asked (socket marked). A connection that has answered once is proven: a later 403, timeout or reset on it is never remapped. A server that asks optionally and then serves the request is unaffected. Known limit: a genuine 403 as the very first answer on a fresh connection to a proxy that asks optionally is still read as "needs a certificate".
- Order for a self-signed server that also needs a client certificate: "trust this certificate" first (the probe refuses the server certificate before any CertificateRequest), then "choose a certificate".
- **Unproven on a real device** (Conscrypt passes its own socket to the key manager; believed identical, not yet observed).

### T4 — Remove server left valid tokens on the server — **Fixed**

- `AuthRepository.removeServer` revokes every account on the server in parallel by the T1 rules (6 s overall), then deletes regardless. The confirmation dialog says so; the user is told if any revocation did not reach the server. `ServerCatalog` no longer offers a removal that skips this.
- **Tests:** `RevocationTest` (all revoked via the verified address; parallel within the limit; offline sends nothing), `RevocationOverNetworkTest`, `ServersAndHomeViewModelTest`.

### T5 — Custom headers followed redirects to other hosts — **Fixed**

- No client from `HttpClientFactory` follows redirects (structural: there is no way to ask for one). `CustomHeaderInterceptor` also removes every configured header name for other authorities, so even a caller that turns redirects back on leaks nothing.
- **Tests:** `RedirectPolicyTest` (302 and 307; second server receives 0 requests; with redirects forced on, no `Authorization` and no custom header there).

### Token lifecycle (Q1): where a token exists

| Place | Plaintext? | Can it leak? |
|---|---|---|
| Server response → `AuthResultDto` → `AuthResult` | Memory | `toString` redacted |
| `Account` (data class) | Memory | `toString` redacted. Never handed to the UI: the seams return `AccountSummary` |
| `ServerStore.tokens` map | Memory | Written only by sign-in/warm. Removal runs in `NonCancellable` |
| DB `accounts.accessToken` | **Ciphertext** | Root can make the app decrypt it (see Q5). **Lists never decrypt it** |
| `Authorization` header | Wire | Only to the server's own authorities, only via a verified address. **TLS protects it only on https** (section 5) |
| URL / logs / crash text | **Never** | `api_key`-style params stripped. `AppLog` is debug-only and gone in release. `AppError` messages are fixed text |
| Saved instance state / nav args | **Never** | Only `serverId` in routes (the normalised GUID the store holds; an unknown id just shows 'server missing') |

### First contact (Q2): network calls in order

| # | Call | Sends | Verified before next step |
|---|---|---|---|
| 1 | `GET https://host/System/Info/Public` | `Authorization: MediaBrowser Client, Device=<model>, DeviceId=<uuid>, Version` (**no token**); no custom headers (draft empty) | System TLS trust + hostname; a CertificateRequest is noted |
| 2 | *(only on `TLS_UNTRUSTED`)* raw TLS handshake (`probeCertificate`) | ClientHello + SNI only; **no HTTP bytes** | Leaf captured, handshake deliberately failed; 10 s hard deadline |
| 3 | User taps **Trust**, then call 1 again with the pin | Same as 1 | Pin match (constant time) |
| 3a | *(only on `CLIENT_CERT_REQUIRED`)* user chooses a KeyChain certificate, then call 1 again | Same as 1, plus the client certificate in the handshake | Server accepts the certificate |
| 4 | *(optional)* second address: same as 1–3a | Same | Must report the **same server id** |
| 5 | Sign in: route from `AuthRepository` (verified address, https preferred), then `GET /System/Info/Public` on it | Same as 1 | Id == saved id |
| 6 | `POST /Users/AuthenticateByName` (password in body) **or** the Quick Connect calls | Credential | `AuthResult.ServerId` == saved id before saving |

- **On https:** no credential leaves before TLS identity (system trust or pin) and the server-id match. **Good.**
- **On http:** steps 1 and 5 can be answered by anyone holding the address, so the credential in step 6 can go to an impostor (section 5). Now only when the server has no reachable https address, the screen says so, and a route that became http after the user last looked waits for them to see the notice and press again.
- **Hostile network:** can block https to push a scheme-less address towards the "offer http" warning (needs consent). Can no longer make a trusted client-certificate server look "untrusted": the certificate request is recognised.

### Trust decisions (Q3)

- **Cleartext:** only `allowHttp` / `confirmCleartext` after a warning screen. A new address always starts with `allowCleartext=false`. **Fixed:** `confirmCleartext` can no longer leave the flag on with no http address.
- **Pin:** set only by explicit Trust buttons. **It cannot be replaced silently.**
- **When a pinned certificate changes:** `TLS_PIN_CHANGED` → Home chip → Review → "Trust the new certificate". **Fixed:** the server id is re-checked with the new pin before it is saved. Still open (minor, M2): the review probes the first https address only (fails when that one is unreachable), and settings saves are read-modify-write with no lock (m1).
- **Client cert alias:** set only via the system KeyChain picker.
- **By design:** a pin is checked only when system trust fails.

### Downgrade and redirect (Q4)

| Case | Result |
|---|---|
| https → http | Blocked: no redirects followed; `CleartextGuard` network interceptor (tested) |
| Cross-host redirect | Not followed by any client (structural); token **and custom headers** stripped anyway (tested) |
| 307/308 with body | Never followed: any 3xx is an error (tested) |
| Mixed home http / away https, one flag | The flag allows http to **any** saved authority. Safe today only because URLs come solely from saved addresses. Make it authority-aware before server-supplied URLs arrive (M2/M3) |

### On-device attacker (Q5)

| Vector | Status |
|---|---|
| Exported components | **Launcher only, in the merged release manifest** (checked by the build) |
| Intents / deep links | None read; no deep links |
| PendingIntents / providers | None (androidx-startup provider is not exported) |
| Task hijacking | Default `taskAffinity`. Consider `taskAffinity=""` (minor, M7) |
| Tapjacking on Trust / Use-anyway buttons and confirmations | **Fixed:** the hosting window drops touches flagged obscured or partly obscured and shows "Another app is drawing over the screen, so this button is switched off. Close screen filters or bubbles and try again."; `filterTouchesWhenObscured` stays on as the backstop (`FilterObscuredTouches`, `ObscuredTouchTest`: both screens, the confirmation and the header editor). Proving it against a real overlay on Android 10/11 is on the device checklist |
| Backups | Excluded (S2) |
| Accessibility readers | Password and header value masked in the semantics tree (proved on device). Quick Connect code is read aloud (it has to be) |
| Screenshots / recents | `FLAG_SECURE` on Sign in, Connection settings and dialogs. Not on Add server warnings (no secret there, by design) |
| Clipboard | Secure field / password transformation block copy and cut while hidden |
| Rooted device | **Protected:** the Keystore key cannot be extracted. **Not protected:** root can run code as the app and decrypt; the in-memory token map; addresses, names, usernames, pins and device id are plaintext in the DB/prefs (not secrets). The key has no unlocked-device binding |

### Hostile server (Q6)

| Attack | Result |
|---|---|
| Oversized body | Refused above 1 MiB (after gzip, by OkHttp) |
| Malformed JSON / wrong types | `AppError`, no echo |
| Server id changes | Probe = unreachable (fail closed); sign-in = `SERVER_MISMATCH`; re-trust = `SERVER_MISMATCH` |
| Redirect storm | Not followed |
| Slow-loris | Probes bounded (1.5 s / 5 s / 10 s); sign-out 12 s; remove server 6 s. **Sign-in calls have no overall deadline**, only a 30 s per-read timeout (minor, M2) |
| Header injection | Server strings never go into headers. The device name comes from `Build.MODEL`, sanitised |
| Display spoofing | **Fixed:** server name, user name, version and certificate names lose control characters and every Unicode format character (bidi, zero-width, soft hyphen, tags, invisible operators) and are capped; a name left empty becomes "Jellyfin" (server) or "Unnamed user"; the Quick Connect code, shown large and read aloud, must be 4 to 12 ASCII letters or digits or the response is a server error |
| Non-GUID / odd ids | **Fixed:** rejected at the gateway; dashed and upper-case ids normalise to one key; the store also refuses any id that is not already normalised |

### Supply chain and build (Q7)

- **Good:** sha256 verification on (checksum-only, by recorded decision); no dynamic versions; plugins pinned through the catalogue; repo mode `FAIL_ON_PROJECT_REPOS`; `google()` group-filtered everywhere; Gradle wrapper checksum-pinned; merged release manifest checked by the build; release minified, non-debuggable; logging stripped.
- **Still open (minor, M8):** stale `org.jellyfin.sdk` checksums remain trusted; `datastore` and `lifecycle-process` are unused but shipped; no signing → silently unsigned release.

### Privacy (Q8)

- **Hosts contacted:** only the user's addresses (EmojiCompat's font request removed).
- **Sent to the server:** `Client="Shoalwatch"`, `Device=<Build.MODEL>`, a random per-install `DeviceId`, and the version.
- **But:** they go with every anonymous probe to every address on every network change, including unverified hosts. Recommend sending no `Authorization` on `/System/Info/Public` (minor m5, M2).

### Cryptography (Q9)

| Point | Assessment |
|---|---|
| IV | Generated by Keystore (randomised encryption required); length checked |
| AAD | Version byte only. Bind `purpose + serverId + userId` in a v2 envelope (minor m9, later) |
| Versioning | Envelope byte `0x01` + key alias `.v1` |
| Errors | Fixed messages; raw cause kept (never logged by `AppLog`) |
| StrongBox | Tried first, silent fallback to TEE (M7) |
| User-auth binding | None, on purpose (background sync/downloads). Write the trade-off into SPEC S1 (M7) |
| Key loss | Accounts hidden → sign in again. Headers silently become empty and the next settings save overwrites them (minor m8) |
| Where decryption runs | **New:** only warm, saving, revocation and full settings reads, always on the IO dispatcher |

### Missing from the SPEC table (Q10)

Recommend adding rows (SPEC is not edited by this fix round):

- **S14** — Identity: on http the server id is not proof. Credentials prefer https; tokens are never sent to an unverified address *(implemented)*
- **S15** — Revocation: sign-out and remove-server revoke via a verified address and tell the user when they could not *(implemented)*
- **S16** — Redirects: no client follows them; token and custom headers are stripped off-authority *(implemented)*
- **S17** — Tapjacking: security prompts filter obscured touches *(implemented)*
- **S18** — Probes are anonymous and identifier-free *(open, M2)*
- **S19** — Build tool integrity: Gradle wrapper checksum pinned *(implemented)*
- Also a sentence on **multi-user on one DeviceId** before M5

---

## 4. Unproven until a real device or Ben's Jellyfin 12.4 server

- [ ] **Client certificate (mTLS):** install a client cert; add a server behind `ssl_verify_client on`. Expect "needs a certificate", then the KeyChain picker, then success (JVM-proven; Conscrypt unobserved)
- [ ] **Client certificate on a self-signed server:** expect "trust this certificate" first, then "choose a certificate"
- [ ] **KeyChain picker** returns an alias; the handshake presents it; a revoked KeyChain grant falls back cleanly
- [ ] **Quick Connect on 12.4:** code shown, approved from web, token saved, `ServerId` matches
- [ ] **Password sign-in on 12.4**, including `AuthenticateByName` field names and a dashed/undashed `ServerId`
- [ ] **Sign-in route:** with home http and away https both reachable, sign-in goes to https and shows no notice; with https blocked, the "This connection isn't encrypted" notice shows
- [ ] **Two users on one server, same DeviceId:** does signing in user B revoke or merge user A's session on 12.4?
- [ ] **Sign-out** revokes: the device disappears from Dashboard → Devices; with the server unreachable, the "Signed out on this phone…" notice shows
- [ ] **Remove server** revokes each sign-in on the server
- [ ] **Home/away switch:** Wi-Fi → mobile data → Wi-Fi; chip changes; no request goes to home while away
- [ ] **Real reverse proxy:** base path (`/jellyfin`), and what a redirect does (today: "No Jellyfin server was found")
- [ ] **Self-signed LAN cert:** trust, then regenerate the cert. Expect "certificate changed", a review screen, and re-trust succeeding only for the same server
- [ ] **Password manager:** autofill fills **and offers to save**
- [ ] **TalkBack:** password and header value read as bullets; Quick Connect code read digit by digit
- [ ] **`adb shell run-as app.shoalwatch`:** `shoalwatch.db` holds no plaintext token or header value
- [ ] **`adb backup`** and device-to-device transfer carry no app data
- [ ] **Probe deadline on Conscrypt:** an unreachable https address releases within 10 s
- [ ] **Release APK (R8) smoke:** sign in and reach Home with the minified build (instrumented tests run debug only); emoji still render without the EmojiCompat initialiser
- [ ] **Tapjacking check on Android 10/11** (if a device is available): a touch through an overlay on "Use anyway" or "Trust" does nothing

---

## 5. Residual risks accepted, with reasons

| Risk | Why accepted |
|---|---|
| **On a plain-http address, nothing can authenticate the server** | Without TLS there is no way to prove who answers. The id from `/System/Info/Public` is public, so on another network a device holding the same LAN address can pass the identity check and capture a password, a Quick Connect secret, or the access token sent to revoke a sign-in (sign-out and remove server, now); from Milestone 2, every token-bearing request. Mitigations in place: credentials use the server's https address whenever it answers; http is used only for http-only servers or when https is unreachable; the Sign in screen shows "This connection isn't encrypted"; the consent warning says another device could pose as the server. **The real mitigation is HTTPS, which the app recommends.** |
| Plain http is possible after a warning | LAN servers without TLS are common. Per-server consent, warned per address, paired with the row above |
| A pin is checked only when system trust fails | A pin is for self-signed servers. Public-CA mis-issuance for a home server name is unlikely. Documented |
| The Quick Connect secret is in a query string | The Jellyfin API requires it. Short-lived, never logged, no URL logging; polling stops if the network changes |
| The Keystore key needs no user authentication or unlocked device | Background sync and downloads (M4/M5) must decrypt while locked. App lock comes in M7 (S8). Document in SPEC |
| Root can use the key | Out of reach of app code. Stated honestly above |
| Dependency signatures not verified | Decision recorded in `ARCHITECTURE.md`: checksums pin the exact reviewed bytes; signature coverage on Google Maven is patchy. Revisit at the M8 security-team review |
| A sign-out or removal may not reach the server | The account is always removed here and the user is told plainly; they can revoke the device from the server's dashboard |
| The password `String` handed to the gateway cannot be wiped | A JVM limit. Not retained. The `CharArray` is wiped |

---

## 6. Closing fixes (final re-review)

The final re-review found no critical or important code defect. It required two corrections to this document (D1, D2: the impostor wording and the http row, now above) and recommended these fixes. All are done, test first.

| # | Fix | Covering test |
|---|---|---|
| **F1** | A route that turns unencrypted between opening Sign in and pressing the button sends nothing: the notice is set, the password stays in the field with an inline message, and only the next press sends. Same for starting Quick Connect | `SignInViewModelTest` (route https at open, http at submit: no credential call, notice set, password kept; second press sends; steady http sends first time; Quick Connect held back until the notice has been seen) |
| **F2** | Quick Connect polling stops at once, and sends no further secret, when the verified connection leaves its basis (checking, offline, other address): "Your connection changed. Start Quick Connect again." | `QuickConnectConnectionChangeTest` (virtual time: zero gateway calls after each kind of change; no secret after an approval that arrives late) |
| **F3** | Revocation uses `ServerNet.revocation` and a one-off client (`ServerClients.oneOff`, evicted in a `finally`, never cached); the rule in `ServerNet` stays true | `RevocationClientCacheTest` (real OkHttp: revoked token B sent while a live client holds A; cached client untouched; no client holding B left); `RevocationTest` |
| **F4** | An unreadable saved token is `StoredToken.Unreadable`, not "nothing to revoke": sign-out and remove server report that the server was not told, with the clearer wording | `RevocationTest` (sign-out; remove server, all unreadable and mixed) |
| **F5** | Client-certificate detection remaps only a handshake failure, or a first 400/403 or first-read failure on a fresh connection that was asked; a proven connection is never remapped | `ClientCertificateTest` (optional auth: 200 then 403 is `FORBIDDEN`; 200 then read timeout is `TIMEOUT`; first 403 is remapped; required-auth cases unchanged) |
| **F6** | All Unicode format characters stripped; empty results use a neutral name; the Quick Connect code must be 4 to 12 ASCII letters or digits | `DisplayTextTest`, `GatewayBoundaryTest` |
| **F7** | `verifyReleaseManifest` is wired to `bundleRelease` and fails on any `networkSecurityConfig`; each rule is proved to fail on a fixture manifest (the real manifest is never changed) | `verifyManifestRuleFixtures` over `app/src/test/manifest-fixtures` (extra exported component, extra permission, `allowBackup` true, `networkSecurityConfig`, debuggable, banned initialiser, foreign provider, no launcher); RED seen first: the missing rule was the one fixture that did not fail |
| **F8** | The selector's store watcher survives a failing flow: `Offline` for live servers, resubscribe after 1 s doubling to 30 s, re-probe on recovery | `AddressSelectorStoreTest` (fails once then recovers; keeps failing: bounded retries, no tight loop) |
| **F9** | `upsertServer` and `upsertAccount` refuse any id that is not its normalised form (fixed message, no echo) | `ServerStoreTest`; tests use `testId(label)` for valid ids |
| **F10** | Account lists, the active-account flow and `active()` wait for the first warm and show only accounts whose token loaded; a failed warm hides them everywhere (the simpler of the two options) | `WarmGateTest` |
| **F11** | Obscured and partly obscured touches on protected screens are dropped and a one-line hint says why; `filterTouchesWhenObscured` stays as the backstop | `ObscuredTouchTest` (both screens, confirmation, header editor, clean touches unaffected) |

### Status changes

| Requirement | Was | Now |
|---|---|---|
| S1 | Met | Met; lists can no longer show an account whose token cannot be read |
| S3 | Met, with an accepted residual | Met, with the same residual; the route can no longer flip to http unseen, and Quick Connect stops on a network change |
| S4 | Met | Met; the build now forbids a `networkSecurityConfig` |
| S5 | Met (JVM); device unproven | Same; false positives removed |
| S6 | Met | Met; revocation uses its own client |
| S11 | Met (for M1) | Met (for M1); format characters, code validation and id guard |
| S17 (tapjacking, proposed) | Implemented | Implemented, with feedback to the user |

Still open, unchanged: the on-device checklist (section 4), probe identifiers (S18), the minor items listed under supply chain.
