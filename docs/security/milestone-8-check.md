# Milestone 8 security check (S13)

**Date:** 18/11/2026 · **Branch:** `milestone-8` (from `main` 49b5069) · **Scope:** SPEC §7 Milestone 8 — security-team review, fixes, signed first release, README. SPEC S13 asks for a full security-team review before the first release; this is it.

---

## 1. Summary verdict

**Ready for the first release, with the Ben-to-decide and device-only items in section 6 still open.**

| | Result |
|---|---|
| **Security-team review** (5 static lenses, consolidated) | 26 findings: **0 Critical, 0 High, 2 Medium, 12 Low, 12 Info**; one **High chain** (CH-A). Detailed report kept private (not in this repository) |
| **Fixed in this milestone** | Both Mediums, the High chain, 9 Lows/Infos, and **SEC-03** (Ben asked for it on 18/11) |
| **Fix review** | Round 1 review: "ready after fixes" (1 Important, 9 Minor). Round 2 fixed the Important and 7 Minors. Round 2 re-review: see section 4 |
| **Unit tests** (full gate after round 2) | **4,636**, 0 failures, 1 skipped; `assembleDebug`, `assembleDebugAndroidTest`, `assembleRelease` built; **lint 0 errors** |
| **On-device tests** (emulator-5556, Android 14) | **28 / 28** after round 1 and again after round 2 |
| **Signed release** | `apksigner verify`: v2 signature, one signer `CN=Shoalwatch, O=Shoalwatch`, SHA-256 `ad06cc7b…cc07` (the key made 17/11). Not debuggable. `versionName 1.0`, `versionCode 1` |
| **README** | Written: what it does, security in brief, building, signing, dependency checks, testing, licence and GPL-3.0 source offer |

---

## 2. How the review was run

- **Tiers run:** static only. Five specialist lenses read the whole app (about 43,000 lines) at `49b5069`: untrusted input, auth and transport, business logic and races, config and supply chain, data protection.
- **Not run:** Tier 0 (live) — no live target was authorised, and Ben's server is off limits without his go-ahead. The local active testbed is built for web apps; active checks for this app are the emulator walkthroughs in every milestone check.
- **Lead step:** duplicates merged, chains traced in code (CH-A to CH-D), everything re-ranked, and a disposition given to each finding.

---

## 3. What was fixed

| Fix | Finding | Change | Commit |
|---|---|---|---|
| F1 | **SEC-01** (Medium) + **CH-A** (High chain) | "Forgot PIN" refuses an empty or blank password before anything is sent | `2b10d11` |
| F2 | **SEC-02** (Medium) + **CH-B** | Connection settings ask the grown-up check themselves, whatever route opens them | `8a2f913` |
| F3 | SEC-06 | Server dates outside 1800–2200 are treated as unknown (no failed list save, no admin crash) | `adfbb64` |
| F4 | SEC-05 | Every text field asks the keyboard not to learn; a source rule keeps it so | `3854dfc` |
| F5 | SEC-15 | "Forgot PIN" tries are forgotten when a profile or server goes | `8d407f1` |
| F6 | SEC-04 | The re-trust identity check sends no custom headers | `2008c35` |
| F7 | SEC-12 | The release manifest check covers `uses-permission-sdk-23`, `sharedUserId`, `testOnly` | `ff73f46` |
| F8 | SEC-07 | Picture folders of removed servers are swept at start-up | `8a4b968` |
| F9 | SEC-08 | `secure_delete` is on (confirmed `1` on the emulator's SQLite), and set explicitly at open | `f8e1769`, `ba0b57a` |
| F10 | SEC-17, SEC-18 | Item runtime capped at 48 h; Quick Connect secret checked | `7af24ec` |
| F11 | — | Version 1.0 | `7779508` |
| F12 | **SEC-03** | "Watched together" marks a protected profile only after its PIN, with a one-press proof | `3d544c7`, `885522b` |
| Round 2 | Review I-1, M-1–M-4, M-6, M-7, M-9, nit | PIN dialog safe against cancel/late answers; proof lasts one press; header values only through the grown-up check in the data layer; edit-address check sends no custom headers; ChangeLog wording | `e540b46..1cd2092` |

Every fix had a failing test first, except where none was possible: F9/M-6 (the test machine's SQLite already has `secure_delete` on), M-3 and the nit (compile-level).

---

## 4. Fix reviews

- **Round 1 review** (working notes, not in git: `.superpowers/sdd/m8/fix-wave-review.md`, with the lens files and the fix report): every finding closed on every path traced; **I-1 Important** — Cancel while the "Watched together" PIN was being checked could crash or leave a dialog that would not close. Nine Minors.
- **Round 2** fixed I-1, M-1, M-2, M-3, M-4, M-6, M-7, M-9 and the nit. **Not changed:** M-5 (fails safe, notes only), import-order nits, M-8 (Ben's call, section 6).
- **Round 2 re-review: ready to merge.** I-1 closed on every interleaving (cancel/back while busy, late answer, profile change, several sharers); proofs live for exactly one press; header values come out only through the grown-up check and saves keep a user's real values; the edit-address check sends no custom headers. Three new Minors, carried (TODO.md):
  - **R2-M1** — a profile change while "Watched together" is still loading its protected sharers can show the old sharer's PIN box to the new profile. Fails safe: nothing is marked without the PIN.
  - **R2-M2** — after M-9, changing the address of a server behind a header-gated proxy always fails; sending the headers only when the server already has a pinned certificate would remove that.
  - **R2-M3** — the test fake catalog does not hide header values, so the connection-settings tests never use the hidden path.

---

## 5. Device results

**Milestone 7 checks owed, run 18/11 on emulator-5556 against the stand-in server:**

| Check | Result |
|---|---|
| Lock screen in the dark theme (W7-1), font 1.0 and 2.0 | **Pass** — readable, wraps, nothing clipped |
| Dark-mode switch while in picture-in-picture (N1) | **Pass** — stayed in the small window through two switches |
| Expand from picture-in-picture after "Lock after" | **Pass** — PIN screen |
| "Don't keep activities" on a protected profile's player (M3) | **Pass** — lock on return; after unlock lands on Home; no start sent on return |

**On-device suite:** 28 / 28 after round 1. After round 2 (`1cd2092`): **28 / 28**.

**Not run by hand:** the F2 grown-up dialog on the certificate route (needs a server whose certificate changes) and the F12 "Watched together" PIN dialog (needs shared downloads between protected profiles); both are covered by unit tests.

**Noticed:** after a password sign-in again for a protected profile whose sign-in had expired, its PIN was asked twice in a row. Safe direction; carried as polish.

---

## 6. Still open

**Ben to decide** (kept as built unless Ben says otherwise):
- **SEC-13** — dependency verification is checksum-only (no PGP signatures).
- **SEC-16** — the app lock accepts Class 2 (weaker) biometrics, the phone's own standard.
- **M-8** — the security-team report describes open findings in detail; fine while the repo is private, decide before publishing it.

**Needs Ben's real phone or server:**
- **SEC-09** — Jellyfin web's "Remember me" after "Open the full dashboard in your browser".
- **SEC-10** — every profile on a server shares one device id (may sign each other out).
- **SEC-11** — when the client certificate is offered relative to the hostname check.
- **Header-gated proxies** (e.g. Cloudflare Access): after F6/M-9 the identity check sends no custom headers, so re-trust or an address edit fails behind such a proxy; remove the header, do the step, add it back. Documented in ARCHITECTURE.
- Every row of the earlier milestones' "Unproven until Ben's real device" lists.

**Accepted, with reasons in the report:** SEC-14, 19, 20, 21, 22, 23, 25. **SEC-22: Ben must back up `the release keystore (kept outside the repo, in the user folder's `.android-keys`)` and `keystore.properties` off this PC** — without them the app can never be updated.

**Carried small points:** `docs/reviews/milestone-3-carried.md`, `milestone-4-carried.md`, and the Milestone 2, 5, 6 and 7 checks' carry lists. None blocks use.

---

## 7. S1–S13 at release

| ID | Status |
|---|---|
| S1 | Met (Keystore AES-GCM; wording of "no unlock needed" still Ben to confirm) |
| S2 | Met |
| S3 | Met |
| S4 | Met; re-trust now sends no custom headers |
| S5 | Met; SEC-11 to confirm on a real server |
| S6 | Met |
| S7 | Met (no third-party hosts) |
| S8 | Met (Milestone 7) |
| S9 | Met with the Milestone 4 additions, Ben to confirm |
| S10 | Met (launcher plus the system-only job service) |
| S11 | Met; date and runtime bounds tightened (F3, F10) |
| S12 | Met; checksum-only verification, SEC-13 Ben to decide |
| S13 | **Met** — this check and the security-team report |
