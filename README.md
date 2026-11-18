# Shoalwatch

**An Android phone and tablet app for Jellyfin (films and TV) whose offline library looks and works exactly like the online one.**

Download whole seasons before a trip and browse them by series → season → episode, with artwork, details and watched state, with no connection.

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

**Not in v1:** music, live TV, books, SyncPlay, Android TV, Chromecast.

---

## Security in brief

- **Sign-ins encrypted on the phone** with a key in the Android Keystore. Passwords are never stored.
- **HTTPS by default.** Plain http only for a server you allow after a warning. Self-signed servers are pinned on first use; a changed certificate is blocked.
- **No analytics, crash reporting or third-party services.** The app talks only to your servers.
- **No backups of app data**, no exported components except the launcher, minimum permissions.
- **Every milestone** ended with a security check (`docs/security/`), and a full security-team review ran before the first release (`docs/security/security-team-report-2026-11-18.md`).

Full requirements: `docs/SPEC.md` §6. Design: `docs/ARCHITECTURE.md`.

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
| `docs/security/` | Security check per milestone, and the security-team report |
| `tools/mockjelly/` | Stand-in Jellyfin server for tests |
| `ChangeLog.txt` | Every change, newest first |

---

## Licence

**GPL-3.0** — see `LICENSE`.

All bundled libraries (AndroidX incl. Jetpack Compose, Media3, Room, WorkManager, DataStore and Navigation; OkHttp; Coil; Kotlin coroutines and serialisation) are Apache-2.0, which is compatible with GPL-3.0.

**Source offer:** if you received a Shoalwatch APK, you are entitled to its complete corresponding source code under GPL-3.0. Ask the person who gave you the APK.

Jellyfin is a trademark of the Jellyfin project. Shoalwatch is not affiliated with or endorsed by it.
