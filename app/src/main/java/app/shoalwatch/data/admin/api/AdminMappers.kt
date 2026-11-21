package app.shoalwatch.data.admin.api

import app.shoalwatch.core.Ticks
import app.shoalwatch.core.displayText
import app.shoalwatch.core.serverInstant
import app.shoalwatch.data.admin.ActivityEntry
import app.shoalwatch.data.admin.ActivityPage
import app.shoalwatch.data.admin.AdminMe
import app.shoalwatch.data.admin.AdminPlayMethod
import app.shoalwatch.data.admin.ConversionReason
import app.shoalwatch.data.admin.LibraryFolder
import app.shoalwatch.data.admin.LibraryKind
import app.shoalwatch.data.admin.NowPlaying
import app.shoalwatch.data.admin.PlayingKind
import app.shoalwatch.data.admin.ServerDevice
import app.shoalwatch.data.admin.ServerStatus
import app.shoalwatch.data.admin.ServerTask
import app.shoalwatch.data.admin.ServerUser
import app.shoalwatch.data.admin.Severity
import app.shoalwatch.data.admin.TaskOutcome
import app.shoalwatch.data.admin.TaskRun
import app.shoalwatch.data.admin.TaskState
import app.shoalwatch.data.admin.WatchSession
import app.shoalwatch.data.api.JsonBodies
import app.shoalwatch.data.servers.JellyfinId
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.floor

// DTO -> model for every admin answer. This is the S11 boundary: bounded lists and strings, validated ids, display text made
// safe, numbers range-checked, one bad element costing only itself. See "Server admin" in docs/ARCHITECTURE.md.

internal object AdminLimits {
    const val SESSIONS = 100
    const val LIBRARIES = 100
    const val TASKS = 200
    const val USERS = 500
    const val DEVICES = 500
    const val LIBRARY_IDS_PER_USER = 500
    const val REASONS = 8
    const val ACTIVITY_PAGE_MAX = 100
    const val NAME = 80
    const val CLIENT = 60
    const val VERSION = 40
    const val TITLE = 200
    const val TASK_NAME = 120
    const val DESCRIPTION = 300
    const val CATEGORY = 60
    const val ACTIVITY_TEXT = 300
    const val ERROR_TEXT = 300
    const val MESSAGE = 200
    const val MAX_RUNTIME_MS = 48L * 60 * 60 * 1000
    val SESSION_ID = Regex("[A-Za-z0-9]{1,64}")
    val TASK_ID = Regex("[A-Za-z0-9]{1,64}")
    val TASK_KEY = Regex("[A-Za-z0-9_.-]{1,64}")
    /** A device id that is listed: printable ASCII, any length (one over [DEVICE_ID_MAX] is listed under a digest, below). */
    val DEVICE_ID = Regex("[\\x21-\\x7E]+")
    const val DEVICE_ID_MAX = 256
    /**
     * A device id a removal may name: no comma, semicolon or other separator, because `DELETE /Devices` takes `id` as an array
     * (12.4.0 reference) that the server may split. A listed device outside this set is shown but never offered for removal.
     */
    val REMOVABLE_DEVICE_ID = Regex("[A-Za-z0-9._~+/=:-]{1,256}")
    val VERSION_TEXT = Regex("[0-9A-Za-z.+-]{1,40}")

    /** Transcode reasons read per session (kept: at most [REASONS] distinct). */
    const val REASONS_READ = 32
    /** User names kept per merged device row. */
    const val USERS_PER_DEVICE = 10
    const val INDEX_MAX = 100_000L
}

private fun <T : Any> JsonElement.decodeAs(deserializer: DeserializationStrategy<T>): T? = try {
    JsonBodies.JSON.decodeFromJsonElement(deserializer, this)
} catch (_: IllegalArgumentException) { // SerializationException is one; the element is untrusted and never echoed
    null
}

/** Lenient in form, strict in range (1800-2200, security review M8 F3): anything else is null, never a crash. */
private fun instant(raw: String?): Instant? = serverInstant(raw)

private fun id(raw: String?): String? = JellyfinId.parseOrNull(raw)?.value

private fun ms(ticks: Long?): Long? = ticks?.takeIf { it >= 0 }?.let(Ticks::toMs)?.takeIf { it <= AdminLimits.MAX_RUNTIME_MS }

/** A JSON number in 0..100, rounded down; a string, NaN or anything outside is null. */
private fun percent(raw: JsonElement?): Int? {
    val p = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
    return if (p.isFinite() && p >= 0.0 && p <= 100.0) floor(p).toInt() else null
}

private fun JsonElement.isString(value: String) = this is JsonPrimitive && isString && content == value

/** Decodes each element on its own, keeps the first of each key, stops at [cap] kept. */
private inline fun <D : Any, M : Any> List<JsonElement>.mapTolerant(
    deserializer: DeserializationStrategy<D>,
    cap: Int,
    key: (M) -> String,
    map: (D) -> M?,
): List<M> {
    val seen = HashSet<String>()
    val out = ArrayList<M>()
    for (element in this) {
        if (out.size >= cap) break
        val model = element.decodeAs(deserializer)?.let(map) ?: continue
        if (seen.add(key(model))) out += model
    }
    return out
}

// ---- system and me

internal fun SystemInfoDto.toStatus() = ServerStatus(
    name = displayText(serverName, AdminLimits.NAME),
    version = version?.takeIf { AdminLimits.VERSION_TEXT.matches(it) } ?: "",
    pendingRestart = hasPendingRestart ?: false,
    shuttingDown = isShuttingDown ?: false,
)

internal fun UserMeDto.toMe() = AdminMe(
    id(id), policy?.isAdministrator ?: false, policy?.isDisabled ?: false,
    canDelete = policy?.enableContentDeletion == true ||
        (policy?.enableContentDeletionFromFolders as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true,
)

// ---- sessions

internal fun List<JsonElement>.toSessions(thisDeviceId: String): List<WatchSession> =
    mapTolerant(SessionDto.serializer(), AdminLimits.SESSIONS, WatchSession::id) { dto ->
        val sessionId = dto.id?.takeIf { AdminLimits.SESSION_ID.matches(it) } ?: return@mapTolerant null
        WatchSession(
            id = sessionId,
            userName = displayText(dto.userName, AdminLimits.NAME),
            deviceName = displayText(dto.deviceName, AdminLimits.NAME),
            client = displayText(dto.client, AdminLimits.CLIENT),
            clientVersion = displayText(dto.applicationVersion, AdminLimits.VERSION),
            lastActivity = instant(dto.lastActivityDate),
            nowPlaying = dto.nowPlaying(),
            canControl = dto.supportsMediaControl ?: false,
            canMessage = dto.supportedCommands.orEmpty().any { it.isString("DisplayMessage") },
            isThisDevice = dto.deviceId == thisDeviceId,
        )
    }.sortedWith(
        compareBy<WatchSession> { it.nowPlaying == null }
            .then(Comparator { a, b -> compareValues(b.lastActivity, a.lastActivity) })
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.userName },
    )

private fun SessionDto.nowPlaying(): NowPlaying? {
    val item = nowPlayingItem?.decodeAs(NowPlayingItemDto.serializer()) ?: return null
    val state = playState?.decodeAs(PlayStateDto.serializer())
    val kind = when (item.type) {
        "Movie" -> PlayingKind.FILM
        "Episode" -> PlayingKind.EPISODE
        else -> PlayingKind.OTHER
    }
    val method = when (state?.playMethod) {
        "DirectPlay" -> AdminPlayMethod.DIRECT_PLAY
        "DirectStream" -> AdminPlayMethod.DIRECT_STREAM
        "Transcode" -> AdminPlayMethod.TRANSCODE
        else -> null
    }
    val runtime = ms(item.runTimeTicks)
    val position = ms(state?.positionTicks)?.takeIf { runtime == null || it <= runtime }
    val transcoding = if (method == AdminPlayMethod.TRANSCODE) transcodingInfo?.decodeAs(TranscodingDto.serializer()) else null
    val index = { n: Long? -> n?.takeIf { it in 0..AdminLimits.INDEX_MAX }?.toInt() }
    return NowPlaying(
        kind = kind,
        title = displayText(item.name, AdminLimits.TITLE),
        seriesName = if (kind == PlayingKind.EPISODE) displayText(item.seriesName, AdminLimits.TITLE).ifEmpty { null } else null,
        season = index(item.parentIndexNumber),
        episode = index(item.indexNumber),
        positionMs = position,
        runtimeMs = runtime,
        paused = state?.isPaused ?: false,
        method = method,
        videoDirect = transcoding?.isVideoDirect,
        audioDirect = transcoding?.isAudioDirect,
        reasons = transcoding?.transcodeReasons.orEmpty().asSequence().take(AdminLimits.REASONS_READ)
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(::conversionReason) }
            .distinct().take(AdminLimits.REASONS).toList(),
        itemId = JellyfinId.parseOrNull(item.id)?.value,
    )
}

private val VIDEO_FORMAT = setOf(
    "VideoCodecNotSupported", "VideoProfileNotSupported", "VideoLevelNotSupported", "VideoBitDepthNotSupported",
    "VideoFramerateNotSupported", "RefFramesNotSupported", "AnamorphicVideoNotSupported", "InterlacedVideoNotSupported",
    "VideoCodecTagNotSupported", "VideoRotationNotSupported",
)
private val AUDIO_FORMAT = setOf(
    "AudioCodecNotSupported", "AudioProfileNotSupported", "AudioSampleRateNotSupported", "AudioBitDepthNotSupported",
    "AudioIsExternal", "SecondaryAudioNotSupported",
)
private val BITRATE = setOf("ContainerBitrateExceedsLimit", "VideoBitrateNotSupported", "AudioBitrateNotSupported", "VideoResolutionNotSupported")

/** One 12.4.0 `TranscodeReason` name (exact) in plain-words groups; anything else is OTHER. */
internal fun conversionReason(wire: String): ConversionReason = when (wire) {
    in VIDEO_FORMAT -> ConversionReason.VIDEO_FORMAT
    in AUDIO_FORMAT -> ConversionReason.AUDIO_FORMAT
    in BITRATE -> ConversionReason.BITRATE
    "ContainerNotSupported" -> ConversionReason.FILE_TYPE
    "SubtitleCodecNotSupported" -> ConversionReason.SUBTITLES
    "VideoRangeTypeNotSupported" -> ConversionReason.HDR
    "AudioChannelsNotSupported" -> ConversionReason.AUDIO_CHANNELS
    "DirectPlayError" -> ConversionReason.DIRECT_PLAY_FAILED
    else -> ConversionReason.OTHER
}

// ---- libraries and tasks

internal fun List<JsonElement>.toLibraries(): List<LibraryFolder> =
    mapTolerant(VirtualFolderDto.serializer(), AdminLimits.LIBRARIES, LibraryFolder::id) { dto ->
        val libraryId = id(dto.itemId) ?: return@mapTolerant null
        val progress = percent(dto.refreshProgress)
        LibraryFolder(
            id = libraryId,
            name = displayText(dto.name, AdminLimits.NAME),
            kind = when (dto.collectionType) {
                "movies" -> LibraryKind.FILMS
                "tvshows" -> LibraryKind.TV
                "music", "musicvideos" -> LibraryKind.MUSIC
                "boxsets" -> LibraryKind.COLLECTIONS
                "mixed" -> LibraryKind.MIXED
                else -> LibraryKind.OTHER
            },
            scanning = (dto.refreshStatus != null && !dto.refreshStatus.equals("Idle", ignoreCase = true)) || (progress != null && progress < 100),
            progressPercent = progress,
        )
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, LibraryFolder::name).thenBy(LibraryFolder::id))

internal fun List<JsonElement>.toTasks(): List<ServerTask> =
    mapTolerant(TaskDto.serializer(), AdminLimits.TASKS, ServerTask::id) { dto ->
        val taskId = dto.id?.takeIf { AdminLimits.TASK_ID.matches(it) } ?: return@mapTolerant null
        if (dto.isHidden == true) return@mapTolerant null
        val state = when (dto.state) {
            "Running" -> TaskState.RUNNING
            "Cancelling" -> TaskState.CANCELLING
            else -> TaskState.IDLE
        }
        ServerTask(
            id = taskId,
            key = dto.key?.takeIf { AdminLimits.TASK_KEY.matches(it) },
            name = displayText(dto.name, AdminLimits.TASK_NAME),
            description = displayText(dto.description, AdminLimits.DESCRIPTION),
            category = displayText(dto.category, AdminLimits.CATEGORY),
            state = state,
            progressPercent = if (state == TaskState.RUNNING) percent(dto.progress) else null,
            lastRun = dto.lastExecutionResult?.decodeAs(TaskResultDto.serializer())?.toRun(),
        )
    }.sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER, ServerTask::category).thenBy(String.CASE_INSENSITIVE_ORDER, ServerTask::name).thenBy(ServerTask::id),
    )

private fun TaskResultDto.toRun(): TaskRun? {
    val outcome = when (status) {
        "Completed" -> TaskOutcome.COMPLETED
        "Failed" -> TaskOutcome.FAILED
        "Cancelled" -> TaskOutcome.CANCELLED
        "Aborted" -> TaskOutcome.ABORTED
        else -> return null
    }
    val keepsError = outcome == TaskOutcome.FAILED || outcome == TaskOutcome.ABORTED
    return TaskRun(outcome, instant(endTimeUtc), if (keepsError) displayText(errorMessage, AdminLimits.ERROR_TEXT).ifEmpty { null } else null)
}

// ---- users

internal fun List<JsonElement>.toUsers(you: String): List<ServerUser> {
    val self = id(you) ?: you
    return mapTolerant(UserDto.serializer(), AdminLimits.USERS, ServerUser::id) { dto ->
        val userId = id(dto.id) ?: return@mapTolerant null
        val flags = dto.policy?.decodeAs(PolicyFlagsDto.serializer())
        val seen = listOfNotNull(instant(dto.lastActivityDate), instant(dto.lastLoginDate)).maxOrNull()
        ServerUser(
            id = userId,
            name = displayText(dto.name, AdminLimits.NAME),
            lastSeen = seen,
            policyKnown = flags != null,
            isAdministrator = flags?.isAdministrator ?: false,
            isDisabled = flags?.isDisabled ?: false,
            canDownload = flags?.enableContentDownloading ?: false,
            allLibraries = flags?.enableAllFolders ?: false,
            libraryIds = flags?.enabledFolders.orEmpty().asSequence()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(::id) }
                .distinct().take(AdminLimits.LIBRARY_IDS_PER_USER).toList(),
            isYou = userId == self,
        )
    }.sortedWith(compareBy<ServerUser> { !it.isYou }.thenBy(String.CASE_INSENSITIVE_ORDER, ServerUser::name).thenBy(ServerUser::id))
}

// ---- activity

internal fun ActivityResultDto.toPage(startIndex: Int, limit: Int): ActivityPage {
    val sent = items.orEmpty()
    val entries = sent.mapTolerant(ActivityEntryDto.serializer(), AdminLimits.ACTIVITY_PAGE_MAX, { it.id.toString() }) { dto ->
        val entryId = dto.id?.takeIf { it >= 0 } ?: return@mapTolerant null
        val text = displayText(dto.name, AdminLimits.ACTIVITY_TEXT).ifEmpty { return@mapTolerant null }
        ActivityEntry(entryId, text, displayText(dto.shortOverview, AdminLimits.ACTIVITY_TEXT).ifEmpty { null }, instant(dto.date), severity(dto.severity))
    }.sortedWith(Comparator<ActivityEntry> { a, b -> compareValues(b.at, a.at) }.thenByDescending(ActivityEntry::id))
    val next = (startIndex.toLong() + sent.size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val usableTotal = total?.takeIf { it >= 0 && it >= next }
    val end = sent.isEmpty() || sent.size < limit || (usableTotal != null && next >= usableTotal)
    return ActivityPage(entries, next, end)
}

private fun severity(raw: String?): Severity = when (raw) {
    "Warning" -> Severity.WARNING
    "Error" -> Severity.ERROR
    "Critical" -> Severity.CRITICAL
    else -> Severity.INFORMATION
}

// ---- devices

internal fun DeviceResultDto.toDevices(thisDeviceId: String): List<ServerDevice> {
    val rows = LinkedHashMap<String, ServerDevice>()
    for (element in items.orEmpty()) {
        val dto = element.decodeAs(DeviceDto.serializer()) ?: continue
        val raw = dto.id?.takeIf { AdminLimits.DEVICE_ID.matches(it) } ?: continue
        // jellyfin-web's id (base64 of the user agent and a time) can pass the cap: such a device is still listed, under a short
        // digest that holds `#`, which no removal accepts (it is never removable, and the screen says why).
        val deviceId = if (raw.length <= AdminLimits.DEVICE_ID_MAX) raw else "#" + sha256(raw)
        val user = displayText(dto.lastUserName, AdminLimits.NAME)
        val row = ServerDevice(
            id = deviceId,
            name = displayText(dto.customName, AdminLimits.NAME).ifEmpty { displayText(dto.name, AdminLimits.NAME) },
            userNames = listOfNotNull(user.ifEmpty { null }),
            appName = displayText(dto.appName, AdminLimits.CLIENT),
            appVersion = displayText(dto.appVersion, AdminLimits.VERSION),
            lastSeen = instant(dto.dateLastActivity),
            isThisDevice = raw == thisDeviceId,
            removable = AdminLimits.REMOVABLE_DEVICE_ID.matches(raw),
        )
        val earlier = rows[deviceId]
        if (earlier == null) {
            if (rows.size < AdminLimits.DEVICES) rows[deviceId] = row
            continue
        }
        val names = (earlier.userNames + row.userNames).distinct().take(AdminLimits.USERS_PER_DEVICE)
        val newer = compareValues(row.lastSeen, earlier.lastSeen) > 0
        rows[deviceId] = (if (newer) row.copy(name = row.name.ifEmpty { earlier.name }) else earlier).copy(userNames = names)
    }
    return rows.values.sortedWith(Comparator<ServerDevice> { a, b -> compareValues(b.lastSeen, a.lastSeen) }.thenBy(String.CASE_INSENSITIVE_ORDER, ServerDevice::name))
}

private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }
