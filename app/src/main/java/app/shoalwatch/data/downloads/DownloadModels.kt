package app.shoalwatch.data.downloads

import app.shoalwatch.data.playback.Quality

/** ORIGINAL is the server's own file; the rest are conversions at Milestone 3's streaming qualities. */
enum class DownloadQuality(val quality: Quality?) { ORIGINAL(null), HIGH(Quality.MBPS_8), MEDIUM(Quality.MBPS_4), LOW(Quality.MBPS_1_5) }

enum class DownloadState { QUEUED, RUNNING, PAUSED, VERIFYING, COMPLETE, FAILED }

enum class DownloadFailure {
    NOT_ALLOWED, NOT_PLAYABLE, NO_SPACE, STORAGE_MISSING, CHANGED_ON_SERVER, SERVER_ERROR, SIGN_IN_NEEDED,
    CHECK_FAILED, DAMAGED, GONE_FROM_SERVER,
    /** The token works for ServerApi but the server refused it on a media request (e.g. Jellyfin 12's legacy-authorisation setting). */
    SERVER_AUTH_REFUSED,
    UNKNOWN,
}

/** INTERNAL is app-private internal storage (the default); REMOVABLE is the app-specific folder on an SD card, by explicit choice. */
enum class StorageKind { INTERNAL, REMOVABLE }

enum class ArtworkSlot { PRIMARY, BACKDROP, THUMB }

enum class TrackType { AUDIO, SUBTITLE }
