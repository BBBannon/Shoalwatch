package app.shoalwatch.data.admin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Wire types for the admin endpoints (12.4.0 reference). Only the fields below are declared, so nothing else is ever decoded.
// NOT declared on purpose: the device access token and icon address, the session's remote end point and play queue, the
// server's local address and every path field, library folder locations and options, user configuration, image tags, the
// long task error text and the activity overview. AdminBoundaryTest scans this file for those names.
// Every field is nullable with a default; a wrong type costs only the element (see AdminMappers.kt).

@Serializable internal class SystemInfoDto(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("HasPendingRestart") val hasPendingRestart: Boolean? = null,
    @SerialName("IsShuttingDown") val isShuttingDown: Boolean? = null,
)

@Serializable internal class UserMeDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Policy") val policy: PolicyFlagsDto? = null,
)

@Serializable internal class PolicyFlagsDto(
    @SerialName("IsAdministrator") val isAdministrator: Boolean? = null,
    @SerialName("IsDisabled") val isDisabled: Boolean? = null,
    @SerialName("EnableContentDownloading") val enableContentDownloading: Boolean? = null,
    @SerialName("EnableAllFolders") val enableAllFolders: Boolean? = null,
    @SerialName("EnabledFolders") val enabledFolders: List<JsonElement>? = null,
)

@Serializable internal class UserDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("LastActivityDate") val lastActivityDate: String? = null,
    @SerialName("LastLoginDate") val lastLoginDate: String? = null,
    @SerialName("Policy") val policy: JsonElement? = null,
)

@Serializable internal class SessionDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("UserName") val userName: String? = null,
    @SerialName("DeviceName") val deviceName: String? = null,
    @SerialName("DeviceId") val deviceId: String? = null,
    @SerialName("Client") val client: String? = null,
    @SerialName("ApplicationVersion") val applicationVersion: String? = null,
    @SerialName("LastActivityDate") val lastActivityDate: String? = null,
    @SerialName("SupportsMediaControl") val supportsMediaControl: Boolean? = null,
    @SerialName("SupportedCommands") val supportedCommands: List<JsonElement>? = null,
    @SerialName("NowPlayingItem") val nowPlayingItem: JsonElement? = null,
    @SerialName("PlayState") val playState: JsonElement? = null,
    @SerialName("TranscodingInfo") val transcodingInfo: JsonElement? = null,
)

@Serializable internal class NowPlayingItemDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Long? = null,
    @SerialName("IndexNumber") val indexNumber: Long? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
)

@Serializable internal class PlayStateDto(
    @SerialName("PositionTicks") val positionTicks: Long? = null,
    @SerialName("IsPaused") val isPaused: Boolean? = null,
    @SerialName("PlayMethod") val playMethod: String? = null,
)

@Serializable internal class TranscodingDto(
    @SerialName("IsVideoDirect") val isVideoDirect: Boolean? = null,
    @SerialName("IsAudioDirect") val isAudioDirect: Boolean? = null,
    @SerialName("TranscodeReasons") val transcodeReasons: List<JsonElement>? = null,
)

/** Progress is read as a raw element so a string or out-of-range value costs only the progress, not the library. */
@Serializable internal class VirtualFolderDto(
    @SerialName("Name") val name: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("ItemId") val itemId: String? = null,
    @SerialName("RefreshProgress") val refreshProgress: JsonElement? = null,
    @SerialName("RefreshStatus") val refreshStatus: String? = null,
)

/** Progress is read as a raw element so a string or out-of-range value costs only the progress, not the task. */
@Serializable internal class TaskDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Key") val key: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Description") val description: String? = null,
    @SerialName("Category") val category: String? = null,
    @SerialName("State") val state: String? = null,
    @SerialName("CurrentProgressPercentage") val progress: JsonElement? = null,
    @SerialName("IsHidden") val isHidden: Boolean? = null,
    @SerialName("LastExecutionResult") val lastExecutionResult: JsonElement? = null,
)

@Serializable internal class TaskResultDto(
    @SerialName("Status") val status: String? = null,
    @SerialName("EndTimeUtc") val endTimeUtc: String? = null,
    @SerialName("ErrorMessage") val errorMessage: String? = null,
)

@Serializable internal class ActivityResultDto(
    @SerialName("Items") val items: List<JsonElement>? = null,
    @SerialName("TotalRecordCount") val total: Long? = null,
)

@Serializable internal class ActivityEntryDto(
    @SerialName("Id") val id: Long? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("ShortOverview") val shortOverview: String? = null,
    @SerialName("Date") val date: String? = null,
    @SerialName("Severity") val severity: String? = null,
)

@Serializable internal class DeviceResultDto(@SerialName("Items") val items: List<JsonElement>? = null)

@Serializable internal class DeviceDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("CustomName") val customName: String? = null,
    @SerialName("LastUserName") val lastUserName: String? = null,
    @SerialName("AppName") val appName: String? = null,
    @SerialName("AppVersion") val appVersion: String? = null,
    @SerialName("DateLastActivity") val dateLastActivity: String? = null,
)

/** The body of POST /Sessions/{sessionId}/Message (`MessageCommand`; `Text` is required by the schema). */
@Serializable internal class MessageBody(
    @SerialName("Header") val header: String,
    @SerialName("Text") val text: String,
    @SerialName("TimeoutMs") val timeoutMs: Long,
)
