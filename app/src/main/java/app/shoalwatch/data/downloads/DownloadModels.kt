package app.shoalwatch.data.downloads

import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.playback.Quality
import app.shoalwatch.data.servers.Session

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

/** What happens when a downloaded item is finished in the player (SPEC 3.1), per profile. */
enum class AfterFinish { ASK, ALWAYS, NEVER }

/** "Delete shared downloads" (SPEC 3.3), per server. */
enum class SharedDelete { ANYONE, EVERYONE }

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

/** [ownedCount]: how many of the [episodeCount] the session's own profile downloaded (it may delete only those). */
data class SeriesSummary(val series: ItemWithUserData, val episodeCount: Int, val bytes: Long, val ownedCount: Int = 0)

data class OfflineLibrary(val inProgress: List<OfflineEntry>, val films: List<OfflineEntry>, val series: List<SeriesSummary>)

/** What one press downloads. */
sealed interface DownloadTarget {
    /** A film or one episode. */
    data class Item(val itemId: String) : DownloadTarget
    data class Season(val seriesId: String, val seasonId: String) : DownloadTarget
    /** Every numbered season and episodes with no season; Specials (season 0) are left out (they have their own season action). */
    data class Series(val seriesId: String) : DownloadTarget
    /** Every unwatched episode of the series for this user, Specials left out. */
    data class Unwatched(val seriesId: String) : DownloadTarget
}

/**
 * NOT_ALLOWED: CanDownload false, or a refusal (403, NotAllowed) from the original check. CONVERSION_UNCONFIRMED: CanDownload
 * unknown and a conversion was needed (asked for, or because the original cannot play here). NOT_FOUND: the server did not
 * return the item (or it is not a film or an episode).
 */
enum class SkipReason {
    NOT_ALLOWED, CONVERSION_UNCONFIRMED, NOT_FOUND,
    /**
     * A copy is already on this phone, but the server did not say whether THIS profile may download (CanDownload unknown):
     * it is not shared, because sharing never asks `/Download`, so the server could not refuse it (Tasks 5-8 review I-1).
     */
    PERMISSION_UNCONFIRMED,
}

data class Skipped(val itemId: String, val reason: SkipReason) { override fun toString() = "Skipped($reason)" }

/**
 * [shareOnly]: already on this phone; confirming only adds this profile as an owner ([estimatedBytes] 0). [confirmed]: the
 * server said this profile's own CanDownload is true (a share needs it; recorded as the profile's permission).
 */
data class PlannedItem(val itemId: String, val quality: DownloadQuality, val estimatedBytes: Long, val shareOnly: Boolean, val confirmed: Boolean = false) {
    override fun toString() = "PlannedItem(<redacted>)"
}

data class DownloadPlan(
    val session: Session, val requested: DownloadQuality, val items: List<PlannedItem>, val skipped: List<Skipped>,
    /** Originals this phone cannot play as they are: planned as HIGH instead (the user sees this before confirming). */
    val convertedInstead: Int,
    val totalBytes: Long, val freeBytes: Long, val enoughSpace: Boolean, val storage: StorageKind, val volumeId: String?,
    val mobileData: Boolean,
    /** More than the 500-item cap matched: the plan holds the first 500 in episode order ("Only the first 500 episodes are included."). */
    val capped: Boolean = false,
) { override fun toString() = "DownloadPlan(items=${items.size})" }


/** What the queue is doing, for the notification (Task 11). [position] of [of] counts this run's items; [progress] 0..1 when known. */
sealed interface QueueStatus {
    data object Idle : QueueStatus
    data class Running(val position: Int, val of: Int, val progress: Float?) : QueueStatus
    data object WaitingForWifi : QueueStatus
    data object WaitingForConnection : QueueStatus
    data object PausedByTimeLimit : QueueStatus
}
