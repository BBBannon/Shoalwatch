# Milestone 9 — Android Auto parked app

**Goal (Ben, 18/11/2026):** Shoalwatch shows on the car's Android Auto screen as a **parked app** (only while parked; Android Auto
itself stops it when the car moves), installed from the normal GitHub APK with no USB trick. Version **1.1.0 (build 3)**.

## What we know (proven in AAFin, Ben's car, 12/11/2026)

- A non-Play app is allowed in a real car only with Android Auto's "Unknown sources" developer setting on AND as a **parked
  (native) app**: an activity with `MAIN` + `android.intent.category.CAR_LAUNCHER`.
- A parked app needs an allowed `ApplicationInfo.category`: **`android:appCategory="game"` is always allowed** (video needs a
  Google flag and a newer head unit). So Shoalwatch shows as a game.
- AAFin's working parked build has no Car App Library service (it was left out because Android Auto reported "a conflicting driving optimized app"); the reason is unproven. Shoalwatch has none, the same as that working build; a manifest rule keeps it so.
- **`FLAG_SECURE` windows are blanked on the car display**: the car activity must never set it. DRM is out of scope.
- The Desktop Head Unit is more lenient than the car; only the car proves it (Ben's device check).

## Decisions

1. **A separate activity, `ui/car/ParkedCarActivity`**: own `taskAffinity="app.shoalwatch.car"`, `singleTask`, exported with ONLY
   the `MAIN` + `CAR_LAUNCHER` filter, AAFin's `configChanges`. Compose with Shoalwatch's own theme, `AppGraph`, data layer and
   `PlaybackController` (nothing ported from AAFin's data or player). No Navigation library in the car (it would act on an
   intent's data as a deep link): the activity's own state picks list or player, and it reads nothing from its intent.
2. **Small car UI:** large touch targets; sections **Continue watching**, **Next up**, **Downloads** (complete and on a mounted
   volume, played through the existing offline path: the resolver prefers the saved copy, so they play with no connection);
   a full-screen player: tap shows the controls, Pause/Play, −10 s, +30 s, Back stops and returns to the list.
3. **Profiles and locks:** the car shows the profile active on the phone, and only while `LockGate` is `Open` and `openFor` is
   exactly that profile (the same rule as the phone's `LockCover`). Otherwise a plain message and nothing of the profile:
   "Sign in on your phone first" (nobody active), "Unlock Shoalwatch on your phone to watch here" (app lock, a protected profile
   locked, or a change not yet judged). It updates live. No PIN entry on the car. When the car stops being open for its
   profile while playing, the car's player ends at once (its stop is sent as that profile, as the phone's player does on a lock).
4. **Leaving and returning with two screens (`AppFront`):** the phone and the car each tell one shared `AppFront`, which tells
   `LockGate` "left" only when the LAST of them leaves and "returned" only when the FIRST comes back. So the "Lock after" clock
   runs only while neither screen shows Shoalwatch, and a car started after "Lock after" locks (the car is a return). The phone
   is unchanged (`ActivityPip` still decides its trips: picture-in-picture is leaving). The car counts as shown between
   `onResume` and `onPause` (Android Auto's "not while driving" cover pauses it), so a long drive is time away.
5. **One playback at a time:** unchanged `ActivePlayback`: the car's controller registers like the phone's, so starting on one
   ends the other (its stop is sent; the phone's player leaves, and its picture-in-picture window closes by the existing rule).
   The car pauses when it loses focus (`onPause`) and never resumes by itself.
6. **`FLAG_SECURE`:** the car activity never applies `RecentsPrivacy`, `SecureWindowFlag` or any `Secure*` component (a source rule
   and a Robolectric test while `hideRecents` is true).
7. **Manifest rules** (`ManifestRules`, fixtures, `ManifestSecurityTest`): the only exported components are the launcher activity,
   the car activity (exactly one filter: `MAIN` + `CAR_LAUNCHER`, task affinity `app.shoalwatch.car`) and the job service;
   `appCategory="game"`; no Car App Library or media-browser service, category or meta-data.

8. **Ben to confirm (review M4):** while the car screen shows the app, the phone's "Lock after" does not run (the app counts as
   in use). So with the car parked and showing Shoalwatch, the phone opens unlocked for as long as the car shows it. Left as is;
   a cap (the car holding the clock for at most some hours) is possible if Ben wants one.
9. **Round 2 (review I1, I2, M1-M3):** on the phone's own display the car activity shows only "Open Shoalwatch on your car's screen"
   and never counts as shown (it does not finish, so a wrong display is visible); Android 13+ keeps no recents picture of the car task
   (`setRecentsScreenshotEnabled(false)`, never `FLAG_SECURE`); the car's lists carry the profile they were read for and are drawn
   only for it; `allowEmbedded` is banned on the car activity; a car-app category alone fails the rule.

**Ben to confirm SPEC wording:** S10 says "No exported components except the launcher activity". There are now two exported
activities (the launcher and the car activity, Ben-approved 18/11/2026). Recorded in ARCHITECTURE; agents do not edit SPEC.md.

## Tasks

- [x] T1 Manifest rules first (red fixtures), then the manifest: `appCategory="game"`, the car activity.
- [x] T2 `AppFront` (red tests), wired between `ActivityPip` and `LockGate`.
- [x] T3 `CarViewModel`: lock and message states, rows, offline downloads, the player, one playback at a time (red tests).
- [x] T4 `ParkedCarActivity` and its Compose screens; render tests at 1280x720 and 800x480; no `FLAG_SECURE` (Robolectric).
- [x] T5 Version 1.1.0 (build 3); ARCHITECTURE "Android Auto (parked)"; README feature row and "In the car"; ChangeLog.

## Tests

Car ViewModel lock/message states; offline downloads listed and playable with no connection (real controller and resolver,
gateway offline, saved copy); one playback at a time between phone and car; `AppFront`; manifest rules each with a bad fixture;
no `FLAG_SECURE` on the car activity; render tests of the car list and player at 1280x720 and 800x480 landscape.

## Security impact

- **S10:** one more exported activity. It reads nothing from its intent, shows nothing of a profile unless the gate is open for
  it, and has no PIN entry. Any app on the phone could start it (as it can the launcher); it then shows only what the phone shows.
- **S8:** the car display is not a hidden window: whoever sits in the car sees what plays there, as on a TV. The car never shows a
  locked profile. Recents hiding is not applied to the car (`FLAG_SECURE` would blank the car screen).
- **No new permission, no Car App Library, no new dependency.**
