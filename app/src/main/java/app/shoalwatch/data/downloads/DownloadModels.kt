package app.shoalwatch.data.downloads

import app.shoalwatch.data.library.ItemWithUserData
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

/** What a screen shows about one download, for the session user. */
data class DownloadInfo(
    val itemId: String, val state: DownloadState, val failure: DownloadFailure?, val quality: DownloadQuality,
    /** 0..1 when known (bytes, or segments for a conversion), else null. */
    val progress: Float?, val totalBytes: Long?,
    val ownedByMe: Boolean, val storage: StorageKind,
    /** False when it was saved to an SD card that is not in the phone now. */
    val available: Boolean,
)

data class OfflineEntry(val item: ItemWithUserData, val download: DownloadInfo)

data class OfflineSeason(val season: ItemWithUserData?, val seasonNumber: Int?, val episodes: List<OfflineEntry>)

data class OfflineSeries(val series: ItemWithUserData?, val seasons: List<OfflineSeason>)

data class SeriesSummary(val series: ItemWithUserData, val episodeCount: Int, val bytes: Long)

data class OfflineLibrary(val inProgress: List<OfflineEntry>, val films: List<OfflineEntry>, val series: List<SeriesSummary>)
