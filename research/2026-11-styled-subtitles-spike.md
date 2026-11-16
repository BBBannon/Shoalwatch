# Styled subtitles spike (Milestone 3, Task 1)

**Status:** desk part written; **on-device part is owed** (see the last section). Nothing here was run on a phone or emulator.

## Recommendation (provisional until the on-device results are in)

- **Option (c):** Media3's built-in SSA/ASS parser **plus server burn-in** for files that need more. No native code in Milestone 3.
- **Revisit option (a) built from source** in Milestone 8's security review with Ben: it is the only native option whose provenance we could own.
- Say plainly in the app which effects are not drawn (Task 7's "effects" notice).
- **Not yet proven:** that (c) draws the repo's own `styled.ass` and Ben's real anime files acceptably. The recommendation stands unless the on-device section contradicts it.

## Criteria (SPEC section 3.5)

Correctness on real files, app size, maintenance, provenance of any native binary, dependency-verification fit.

## The three options

| Criterion | (a) libass in Media3 | (b) libmpv as second engine | (c) Media3 SSA/ASS + server burn-in |
|---|---|---|---|
| Correctness on real files | Highest for ASS (the reference renderer) | Highest overall | Partial: see feature list below |
| App size | Adds native libs per ABI (size unmeasured: **unverified**) | Largest: FFmpeg inside; plan estimate 20-30 MB per ABI (**unverified**) | None |
| Maintenance | Native build or binary upgrades each release; JNI glue to Media3 | A second player kept in step with every Milestone 3 feature | Nothing extra |
| Provenance | Prebuilt AAR: third-party binaries; sha256 pins the bytes, not their build inputs. From source: ours, but needs the NDK | Same problem, bigger surface | All Kotlin/Java from first-party AndroidX artifacts (Media3 is not in the project yet: its checksums are added to the verification metadata in Task 6) |
| Dependency-verification fit | AAR fits Gradle verification, but the binaries inside are opaque | Same | Fits: ordinary Maven artifacts, checksums added to the verification metadata when the dependency is added |
| Attack surface | libass, FreeType, HarfBuzz, FriBidi parse untrusted server files in our process | FFmpeg and more | Managed-code parser only |
| Needs Ben's yes | **Yes** (NDK install, or a third-party binary) | **Yes** | No |

(a) from source: the Android NDK is **not installed**; installing it needs Ben's yes. It adds a native build to every release.

## Facts about (c), with sources

Checked against current Media3 documentation and source (Context7, source `androidx/media` release notes and `SsaParser.java`). The version these were checked against is the documentation's latest; the milestone pins 1.9.2.

| Claim | Status |
|---|---|
| `DefaultSubtitleParserFactory` supports SSA/ASS (`SsaParser`), WebVTT, SubRip, TX3G, TTML, **PGS**, **VobSub** and DVB parsers | Verified in source |
| `OutlineColour` sets the cue background when `BorderStyle == 3` | Verified (release notes, 1.0.0-beta01) |
| `layer` (z-order of simultaneous cues) | Verified (release notes, 1.8.0) |
| Matroska `S_TEXT/SSA` accepted as well as `S_TEXT/ASS` | Verified (release notes, 1.8.0) |
| Only `[V4+ Styles]` is parsed; legacy `[V4 Styles]` (old SSA) is ignored with a log line | Verified in source |
| **`MarginL`, `MarginR`, `MarginV` from Style and Dialogue lines are NOT read** (source has a TODO; cues get default positions) | Verified in source. **This is a gap the plan did not list.** |
| Colours, bold/italic/underline, alignment, `\pos` | From the plan; **unverified** here (to be seen on device) |
| No karaoke `\k`, no `\t` animation, no `\move`/`\fad`, no `\p` drawing, no embedded fonts | From the plan; **unverified** here (to be seen on device) |
| VobSub supported since Media3 1.6.0 | **Unverified** (the parser exists in the factory; the version was not checked) |

Consequences for the app:

- Bottom-margin and per-line margin tweaks that real fansub files use will not apply; positions fall back to alignment defaults unless `\pos` is honoured (to be confirmed on device).
- Old-style `[V4 Styles]` files draw unstyled. Task 7 should treat them as plain text.
- Picture subtitles (PGS, VobSub) need no native code. When the server transcodes, it burns them in.

## Fixtures for the spike

In `app/src/test/resources/subtitles/` (copies of `tools/mockjelly/media/`):

- `styled.ass`: two styles, `\pos(160,20)`, `\c&H0000FF&`, a `Layer: 1` line, `BorderStyle=3` in one style.
- `effects.ass`: `\k`, `\t`, `\move`, `\p1`.
- Served by the stand-in server: subtitle index 2 is `sample.srt`, index 3 is `styled.ass` (see `tools/mockjelly/README.md`). `effects.ass` is not served by the mock; load it directly on the device (see below).

## On-device results (owed: the controller runs this)

Nothing in this section has been run. Record each result as words plus a screenshot description.

**Setup**

1. Start the stand-in server: `python -I tools/mockjelly/mock_jellyfin.py 8096`.
2. Play any film in the app with the Task 8 player (or, before that exists, an androidTest that builds an ExoPlayer on `app/src/androidTest/assets/media/clip.mp4` and attaches each subtitle file as a side-loaded `MediaItem.SubtitleConfiguration`). Use MIME `text/x-ssa` for `.ass`, `application/x-subrip` for `.srt`.

**Run and look for**

| File | Look for | Result |
|---|---|---|
| `styled.ass` at 1-2 s | White 18 pt line at the bottom, outlined | |
| `styled.ass` at 3-4 s | Bold italic yellow line, **at the top centre** (`\pos(160,20)` and alignment 8), boxed background (`BorderStyle=3`) | |
| `styled.ass` at 5-6 s | Red text and, above or below it, the layer-1 line: are both visible, and is layer 1 on top? | |
| `effects.ass` at 1-3 s | `Karaoke` shown as plain text with no sweep: no stray `{\k50}` text on screen | |
| `effects.ass` at 3-5 s | `Growing text` static, no resize; no stray tag text | |
| `effects.ass` at 5-7 s | `Moving text` static (position?); no stray tag text | |
| `effects.ass` at 7-9 s | `\p1` drawing: nothing drawn, **and no garbage coordinate text** (`m 0 0 l 10 0 ...`). If the coordinates show as text, record it: it is a bug the app must filter | |
| `sample.srt`, `bom-crlf.srt` | Three cues at the right times; BOM and CRLF cause no stray characters | |
| Margins | In a real file with `MarginV` set, does the line sit where the file says or at the default? (confirms the margin finding above) | |
| Old `[V4 Styles]` file (if Ben has one) | Draws as plain text | |

**On Ben's server (milestone-end check)**

- A real anime file with styled ASS: note signs, karaoke, typesetting that is lost.
- A PGS Blu-ray rip and, if available, a VobSub (DVD) rip: do they draw from the container with direct play? Record size and legibility.
- With "Let the server draw these subtitles" chosen: does the burn-in look right, and what does the re-encode do to battery and start-up time?

**Decision rule:** if `effects.ass` shows stray tag or coordinate text, or `styled.ass` draws wrongly, the recommendation is **contradicted** for this milestone and Ben must be asked before any native option is pursued.
