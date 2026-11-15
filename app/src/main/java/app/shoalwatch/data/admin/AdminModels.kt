package app.shoalwatch.data.admin

import java.time.Instant

// What admin screens show, already validated (see "Server admin" in docs/ARCHITECTURE.md). Live data only: nothing here is
// written to Room, preferences, a file or a navigation argument. Every class overrides toString so no name, title, device or
// message can reach a log by accident.

/** GET /System/Info, the four fields shown. Addresses, paths and ports in that answer are never read. */
data class ServerStatus(val name: String, val version: String, val pendingRestart: Boolean, val shuttingDown: Boolean) {
    override fun toString() = "ServerStatus(<redacted>)"
}

/** The two `PlaystateCommand` values this app sends (12.4.0 enum: Stop, Pause, Unpause, NextTrack, PreviousTrack, Seek, Rewind, FastForward, PlayPause). */
enum class RemoteCommand(val wire: String) { PAUSE("Pause"), STOP("Stop") }

/** `PlayerStateInfo.PlayMethod` (12.4.0: Transcode, DirectStream, DirectPlay). */
enum class AdminPlayMethod { DIRECT_PLAY, DIRECT_STREAM, TRANSCODE }

/** `TranscodingInfo.TranscodeReasons` grouped into what can be said in plain words. */
enum class ConversionReason { VIDEO_FORMAT, AUDIO_FORMAT, FILE_TYPE, SUBTITLES, BITRATE, HDR, AUDIO_CHANNELS, DIRECT_PLAY_FAILED, OTHER }

enum class PlayingKind { FILM, EPISODE, OTHER }

data class NowPlaying(
    val kind: PlayingKind,
    val title: String,
    val seriesName: String?,
    val season: Int?,
    val episode: Int?,
    val positionMs: Long?,
    val runtimeMs: Long?,
    val paused: Boolean,
    val method: AdminPlayMethod?,
    val videoDirect: Boolean?,
    val audioDirect: Boolean?,
    /** Distinct, in the server's order, at most 8. Empty unless [method] is TRANSCODE. */
    val reasons: List<ConversionReason>,
) {
    override fun toString() = "NowPlaying(<redacted>)"
}

data class WatchSession(
    val id: String,
    val userName: String,
    val deviceName: String,
    val client: String,
    val clientVersion: String,
    val lastActivity: Instant?,
    val nowPlaying: NowPlaying?,
    /** `SupportsMediaControl`: Pause and Stop are offered. */
    val canControl: Boolean,
    /** `SupportedCommands` contains `DisplayMessage`: Message is offered. */
    val canMessage: Boolean,
    /** `DeviceId` equals this phone's `DeviceIdentity.deviceId`: no actions are offered. */
    val isThisDevice: Boolean,
) {
    override fun toString() = "WatchSession(<redacted>)"
}

/** From `CollectionTypeOptions` (12.4.0: movies, tvshows, music, musicvideos, homevideos, boxsets, books, mixed). */
enum class LibraryKind { FILMS, TV, MUSIC, COLLECTIONS, MIXED, OTHER }

data class LibraryFolder(val id: String, val name: String, val kind: LibraryKind, val scanning: Boolean, val progressPercent: Int?) {
    override fun toString() = "LibraryFolder(<redacted>)"
}

/** `TaskState` (12.4.0: Idle, Cancelling, Running). */
enum class TaskState { IDLE, RUNNING, CANCELLING }

/** `TaskCompletionStatus` (12.4.0: Completed, Failed, Cancelled, Aborted). */
enum class TaskOutcome { COMPLETED, FAILED, CANCELLED, ABORTED }

data class TaskRun(val outcome: TaskOutcome, val endedAt: Instant?, val errorMessage: String?) {
    override fun toString() = "TaskRun(<redacted>)"
}

data class ServerTask(
    val id: String,
    val key: String?,
    val name: String,
    val description: String,
    val category: String,
    val state: TaskState,
    val progressPercent: Int?,
    val lastRun: TaskRun?,
) {
    override fun toString() = "ServerTask(<redacted>)"
}

/** The `Key` of Jellyfin's "Scan Media Library" task, whose progress is the full scan's progress. */
const val FULL_SCAN_TASK_KEY = "RefreshLibrary"

data class ServerUser(
    val id: String,
    val name: String,
    val lastSeen: Instant?,
    /** False when the answer had no readable `Policy`: nothing below is known, and enable/disable is not offered. */
    val policyKnown: Boolean,
    val isAdministrator: Boolean,
    val isDisabled: Boolean,
    val canDownload: Boolean,
    val allLibraries: Boolean,
    val libraryIds: List<String>,
    /** The signed-in account itself: it cannot be disabled from here. */
    val isYou: Boolean,
) {
    override fun toString() = "ServerUser(<redacted>)"
}

/** `LogLevel` (12.4.0: Trace, Debug, Information, Warning, Error, Critical, None); Trace, Debug, None and unknown are INFORMATION. */
enum class Severity { INFORMATION, WARNING, ERROR, CRITICAL }

data class ActivityEntry(val id: Long, val text: String, val detail: String?, val at: Instant?, val severity: Severity) {
    override fun toString() = "ActivityEntry(<redacted>)"
}

/** [nextStartIndex] is startIndex plus every element the server sent (not just the kept ones). */
data class ActivityPage(val entries: List<ActivityEntry>, val nextStartIndex: Int, val endReached: Boolean) {
    override fun toString() = "ActivityPage(<redacted>)"
}

/** One row per device id; [userNames] are the distinct `LastUserName`s of the rows that share it. */
data class ServerDevice(
    val id: String,
    val name: String,
    val userNames: List<String>,
    val appName: String,
    val appVersion: String,
    val lastSeen: Instant?,
    val isThisDevice: Boolean,
    /**
     * The id is in the strict set a removal may name (see `AdminLimits.REMOVABLE_DEVICE_ID`): `DELETE /Devices` takes its `id` as
     * an array, so an id holding a separator could name a second device. Remove is offered only when true.
     */
    val removable: Boolean,
) {
    override fun toString() = "ServerDevice(<redacted>)"
}
