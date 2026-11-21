<p align="center">
  <img src="docs/assets/wordmark.svg" alt="Shoalwatch for Jellyfin" width="480">
</p>

<p align="center"><strong>An Android phone and tablet app for Jellyfin (films and TV) whose offline library looks and works exactly like the online one.</strong></p>

<div align="center">

[![Licence: GPL-3.0](https://img.shields.io/badge/licence-GPL--3.0-blue)](LICENSE)
![Jellyfin 10.10 to 12.x](https://img.shields.io/badge/Jellyfin-10.10%20to%2012.x-AA5CC3)
![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Android Auto (parked)](https://img.shields.io/badge/Android%20Auto-parked-4FD1C5)
![Not an official Jellyfin project](https://img.shields.io/badge/not%20an%20official-Jellyfin%20project-lightgrey)
[![Download the latest APK](https://img.shields.io/github/v/release/BBBannon/Shoalwatch?label=download%20APK&logo=android&logoColor=white)](https://github.com/BBBannon/Shoalwatch/releases/latest/download/Shoalwatch.apk)

</div>

Download whole seasons before a trip and browse them by series → season → episode, with artwork, details and watched state, with no connection.

> 🎮 **Also from the same maker: [Shoalcade](https://github.com/BBBannon/Shoalcade)** — play the games you own from your own Jellyfin server, on your phone, PC or browser, with safe saves. **[Take a look on GitHub →](https://github.com/BBBannon/Shoalcade)**

> **Not an official Jellyfin project.** Shoalwatch is an independent hobby project, not made or supported by the Jellyfin team — please don't ask them for help with it.

---

## Download and install

1. On your Android 10+ phone or tablet, download **[`Shoalwatch.apk`](https://github.com/BBBannon/Shoalwatch/releases/latest/download/Shoalwatch.apk)** (always the newest version). Each [release](https://github.com/BBBannon/Shoalwatch/releases) also has `Shoalwatch-<version>.apk` and its checksum.
2. Open the file and allow installs from your browser or file manager when asked.
3. Add your Jellyfin server's address and sign in (password or Quick Connect).

**Updating:** install the new APK over the top. Your sign-ins, settings and downloads are kept.

---

## Why Shoalwatch?

**Your films and TV already live on your Jellyfin server. Shoalwatch makes them easy to take anywhere, and safe to hand to the family.**

- ✈️ **Offline looks just like online.** Downloads sit in the same series → season → episode pages, with posters, descriptions and watched ticks. No hunting through a plain list of files.
- 📥 **Download a whole season or series in one tap.** Pick the original file or a smaller copy to save space. A dropped connection picks up where it left off, and every file is checked before it says "ready".
- 🔄 **Your progress follows you.** Watch on a plane, and when you're back online your watched marks and where-you-stopped reach the server, in the right order.
- 🧹 **It tidies up for you.** Finish an episode and it can be marked watched and deleted; the next ones download by themselves on Wi-Fi.
- 👨‍👩‍👧 **Made for families.** Several profiles on one phone, each with its own PIN if you like. Downloads are shared between profiles, so you only store a film once — but a child's profile only sees what the server says it may.
- 🔒 **Private by design.** Your sign-in is locked away in the phone's secure storage, no passwords are kept, it uses a secure connection by default, and there are **no adverts, no tracking and no data sent anywhere** except your own server.
- 🚗 **In the car, when parked.** Continue Watching, Next Up and your downloads on the Android Auto screen — handy for charging stops.
- 🛠️ **Look after your server from the sofa.** If you run the server, you can see who's watching, scan libraries, restart and more, straight from the phone.
- 💬 **Subtitles that look right.** Styled subtitles (the fancy coloured kind for anime) are drawn properly on the phone, with no server effort.

---

## What it does

| Area | Highlights |
|---|---|
| **Browsing** | Home with Continue Watching and Next Up, library grids, series and film pages, search; two panes on a tablet |
| **Player** | Direct play or server conversion, quality choice, resume, remembered audio and subtitle choice per series, styled subtitles drawn on the phone, skip intro, Up next, sleep timer, picture-in-picture |
| **Downloads** | Films, episodes, seasons or whole series; original file or a smaller size; resumes after a cut; every file checked playable; internal storage or SD card |
| **Housekeeping** | Mark watched and delete at the credits; download the next episodes by themselves on Wi-Fi; a Storage screen |
| **Sync back** | Watched marks, progress and favourites made offline reach the server in order when you reconnect |
| **Shared downloads** | One copy shared between profiles on the phone, only for titles the server confirms each profile may see |
| **Profiles** | Several Jellyfin accounts and servers; protect a profile with its own PIN; optional app lock with fingerprint or screen lock |
| **Server admin** | Who is watching, library scans, scheduled tasks, users, activity, devices, restart — for administrators only |
| **Pick several at once** | Press and hold to select films or episodes, then mark them watched, favourite them, download them or delete them in one go |
| **Manage the library** | For administrators: identify a film or series again, change its pictures (from online sources or your own photos), edit its details, refresh it or delete it |
| **Approve a TV** | Type the Quick Connect code a TV or laptop shows to sign it in, from Settings |
| **In the car** | Android Auto, while parked: Continue Watching, Next Up and your downloads (they play with no connection) on the car's screen |

**Not in v1:** music, live TV, books, SyncPlay, Android TV, Chromecast.

---

## Security in brief

- **Sign-ins encrypted on the phone** with a key in the Android Keystore. Passwords are never stored.
- **HTTPS by default.** Plain http only for a server you allow after a warning. Self-signed servers are pinned on first use; a changed certificate is blocked.
- **No analytics, crash reporting or third-party services.** The app talks only to your servers.
- **No backups of app data**, no exported components except the launcher and the Android Auto car screen, minimum permissions.
- **Every milestone** ended with a security check (`docs/security/`), and a full security-team review ran before the first release (summary in `docs/security/milestone-8-check.md`; the detailed report is kept private).

Full requirements: `docs/SPEC.md` §6. Design: `docs/ARCHITECTURE.md`.

---

## In the car (Android Auto, parked only)

Shoalwatch can show on your car's Android Auto screen **while the car is parked**. Android Auto itself stops it when the car moves. It uses the normal APK from GitHub: no USB or computer needed.

1. **Turn on Android Auto's developer settings** on the phone: open Android Auto's settings, tap **Version** 10 times, then **⋮ → Developer settings** and turn on **Unknown sources**.
2. **Install Shoalwatch** from its APK as usual ([`Shoalwatch.apk`](https://github.com/BBBannon/Shoalwatch/releases/latest/download/Shoalwatch.apk)).
3. **Add it to the car's launcher:** Android Auto → **Customise launcher**, and tick Shoalwatch. It is listed there only while a car (or a head unit) is connected.

What you get on the car screen: Continue Watching, Next Up and your downloads, and a full-screen player (tap for the controls: List, −10 s, Pause, +30 s). Downloads play with no connection.

- **It shows as a game.** Android Auto lets an app from outside the Play Store run on the car screen only as a parked app in an allowed category, and games are the one it always allows. Nothing else changes.
- **The phone decides who watches.** The car shows the profile active on the phone. If the app lock or a protected profile's PIN is waiting, the car says "Unlock Shoalwatch on your phone to watch here" and shows nothing until you unlock on the phone. No PIN is typed on the car.
- **One screen at a time.** Starting a video on the car stops the one on the phone, and the other way round.

---

## Requirements

| | |
|---|---|
| **Phone** | Android 10 (API 29) or later |
| **Server** | Jellyfin 10.10 to 12.x |
| **To build** | JDK 17, Android SDK with platform 35 |

---

## Building

```bash
# point Gradle at JDK 17
export JAVA_HOME=/path/to/jdk-17

./gradlew testDebugUnitTest    # unit tests (about 4,600)
./gradlew assembleDebug        # debug APK
./gradlew lintDebug            # lint
```

### Signed release

1. Create `keystore.properties` in the project root (it is git-ignored — **never commit it**):

   ```properties
   storeFile=/absolute/path/to/your-release.jks
   storePassword=...
   keyAlias=...
   keyPassword=...
   ```

2. Build: `./gradlew assembleRelease`
3. The APK is in `app/build/outputs/apk/release/`. Check it with `apksigner verify --print-certs`.

Without `keystore.properties` the release build is made but left unsigned. It is never signed with the debug key.

**Keep the keystore and its passwords backed up off the build machine.** Without them the app can never be updated in place.

### Dependency checks

Every dependency is version-pinned and its checksum verified (`gradle/verification-metadata.xml`). After changing a dependency, regenerate with `./gradlew --write-verification-metadata sha256 help testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease` and review the diff.

---

## Testing

- **Unit tests** — JUnit, Robolectric, MockWebServer, Turbine: `./gradlew testDebugUnitTest`
- **On-device tests** — `./gradlew connectedDebugAndroidTest` on an emulator, against the stand-in server
- **Stand-in Jellyfin server** for emulator runs (no real server needed):

  ```bash
  python -I -u tools/mockjelly/mock_jellyfin.py 8096 --host 0.0.0.0
  ```

  On the emulator the address is `10.0.2.2:8096`.

---

## Project layout

| Path | What |
|---|---|
| `app/src/main/java/app/shoalwatch/` | `core`, `net`, `security`, `data/*`, `ui/*` (package map in `docs/ARCHITECTURE.md`) |
| `docs/SPEC.md` | What the app must do |
| `docs/plans/` | One plan per milestone |
| `docs/security/` | Security check per milestone |
| `tools/mockjelly/` | Stand-in Jellyfin server for tests |
| `ChangeLog.txt` | Every change, newest first |

---

## More from the Shoal family

| Project | What it is |
|---|---|
| **[Shoalcade](https://github.com/BBBannon/Shoalcade)** | Retro games from your Jellyfin server — a Jellyfin plugin plus Android and Windows apps, playable offline, with saves you can't lose |

---

## Licence

**GPL-3.0** — see `LICENSE`.

All bundled libraries (AndroidX incl. Jetpack Compose, Media3, Room, WorkManager, DataStore and Navigation; OkHttp; Coil; Kotlin coroutines and serialisation) are Apache-2.0, which is compatible with GPL-3.0.

**Source offer:** if you received a Shoalwatch APK, you are entitled to its complete corresponding source code under GPL-3.0. Ask the person who gave you the APK.

Jellyfin is a trademark of the Jellyfin project. Shoalwatch is not affiliated with or endorsed by it.
