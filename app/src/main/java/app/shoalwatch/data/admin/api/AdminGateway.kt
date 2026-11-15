package app.shoalwatch.data.admin.api

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.core.displayText
import app.shoalwatch.data.admin.ActivityPage
import app.shoalwatch.data.admin.Admin
import app.shoalwatch.data.admin.AdminMe
import app.shoalwatch.data.admin.LibraryFolder
import app.shoalwatch.data.admin.RemoteCommand
import app.shoalwatch.data.admin.ServerDevice
import app.shoalwatch.data.admin.ServerStatus
import app.shoalwatch.data.admin.ServerTask
import app.shoalwatch.data.admin.ServerUser
import app.shoalwatch.data.admin.WatchSession
import app.shoalwatch.data.api.JsonBodies
import app.shoalwatch.data.api.ServerApi
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * [Admin] on [ServerApi], the one authenticated call path (warm gate, verified address, the account's own client, the token only
 * in `Authorization`, 401 signs the account out locally). Every call except [me] is refused with nothing sent unless [allows]
 * says the session is a server-confirmed administrator; ids are validated before anything is sent and become path segments or a
 * query value only through `ApiRequest`'s builder. Reads use ServerApi's one retry; every action is sent once (`retry = false`).
 * Every path, parameter and field was checked against the 12.4.0 reference (see "Server admin" in docs/ARCHITECTURE.md).
 * Failures are logged by kind only.
 */
internal class AdminGateway(
    private val api: ServerApi,
    /** This phone's `DeviceIdentity.deviceId`, to mark its own session and device. */
    private val thisDeviceId: String,
    /** `AdminRights.allows`: checked before every call except [me]. */
    private val allows: (Session) -> Boolean,
) : Admin {

    /** GET /Users/Me. Used only by `AdminAccess`; not gated (it is how the gate is opened). */
    suspend fun me(s: Session): AdminMe = logged("me") {
        api.get(s, "/Users/Me", deserializer = UserMeDto.serializer(), maxBytes = ONE_MIB).toMe()
    }

    override suspend fun serverStatus(s: Session): ServerStatus = gated(s, "serverStatus") {
        api.get(s, "/System/Info", deserializer = SystemInfoDto.serializer(), maxBytes = ONE_MIB).toStatus()
    }

    override suspend fun sessions(s: Session): List<WatchSession> = gated(s, "sessions") {
        api.get(s, "/Sessions", listOf("activeWithinSeconds" to ACTIVE_WITHIN_SECONDS), ELEMENTS, maxBytes = 4 * ONE_MIB).toSessions(thisDeviceId)
    }

    override suspend fun sendMessage(s: Session, sessionId: String, header: String, text: String) = gated(s, "sendMessage") {
        val id = sessionId(sessionId)
        val cleaned = displayText(text, AdminLimits.MESSAGE)
        require(cleaned.isNotEmpty()) { "Empty message." }
        val body = MessageBody(displayText(header, AdminLimits.MESSAGE), cleaned, MESSAGE_TIMEOUT_MS)
        api.post(s, "/Sessions/$id/Message", jsonBody = JsonBodies.JSON.encodeToString(MessageBody.serializer(), body), retry = false, sendOnce = true)
    }

    override suspend fun command(s: Session, sessionId: String, command: RemoteCommand) = gated(s, "command") {
        api.post(s, "/Sessions/${sessionId(sessionId)}/Playing/${command.wire}", retry = false, sendOnce = true)
    }

    override suspend fun libraries(s: Session): List<LibraryFolder> = gated(s, "libraries") {
        api.get(s, "/Library/VirtualFolders", deserializer = ELEMENTS, maxBytes = ONE_MIB).toLibraries()
    }

    override suspend fun scanAll(s: Session) = gated(s, "scanAll") {
        api.post(s, "/Library/Refresh", retry = false, sendOnce = true)
    }

    override suspend fun scanLibrary(s: Session, libraryId: String) = gated(s, "scanLibrary") {
        api.post(s, "/Items/${jellyfinId(libraryId)}/Refresh", SCAN_QUERY, retry = false, sendOnce = true)
    }

    override suspend fun tasks(s: Session): List<ServerTask> = gated(s, "tasks") {
        api.get(s, "/ScheduledTasks", listOf("isHidden" to "false"), ELEMENTS, maxBytes = ONE_MIB).toTasks()
    }

    override suspend fun startTask(s: Session, taskId: String) = gated(s, "startTask") {
        api.post(s, "/ScheduledTasks/Running/${taskId(taskId)}", retry = false, sendOnce = true)
    }

    override suspend fun stopTask(s: Session, taskId: String) = gated(s, "stopTask") {
        api.delete(s, "/ScheduledTasks/Running/${taskId(taskId)}", retry = false, sendOnce = true)
    }

    override suspend fun users(s: Session): List<ServerUser> = gated(s, "users") {
        api.get(s, "/Users", deserializer = ELEMENTS, maxBytes = 4 * ONE_MIB).toUsers(s.userId)
    }

    override suspend fun setUserDisabled(s: Session, userId: String, disabled: Boolean) = gated(s, "setUserDisabled") {
        val id = jellyfinId(userId)
        val user = api.get(s, "/Users/$id", deserializer = JsonObject.serializer(), maxBytes = UserPolicyEdit.MAX_POLICY_BYTES)
        val policy = UserPolicyEdit.withDisabled(user, id, disabled) ?: throw AppError.of(AppError.Kind.SERVER_ERROR)
        api.post(s, "/Users/$id/Policy", jsonBody = policy.toString(), retry = false, sendOnce = true)
    }

    override suspend fun activity(s: Session, startIndex: Int, limit: Int): ActivityPage = gated(s, "activity") {
        require(startIndex in 0..MAX_START_INDEX) { "Invalid start index." }
        require(limit in 1..AdminLimits.ACTIVITY_PAGE_MAX) { "Invalid page size." }
        val query = listOf("startIndex" to startIndex.toString(), "limit" to limit.toString())
        api.get(s, "/System/ActivityLog/Entries", query, ActivityResultDto.serializer(), maxBytes = 2 * ONE_MIB).toPage(startIndex, limit)
    }

    override suspend fun devices(s: Session): List<ServerDevice> = gated(s, "devices") {
        api.get(s, "/Devices", deserializer = DeviceResultDto.serializer(), maxBytes = 4 * ONE_MIB).toDevices(thisDeviceId)
    }

    override suspend fun removeDevice(s: Session, deviceId: String) = gated(s, "removeDevice") {
        if (!AdminLimits.REMOVABLE_DEVICE_ID.matches(deviceId)) throw notFound()
        // A device id is a client-chosen string: only ever a query value, never a path segment.
        api.delete(s, "/Devices", listOf("id" to deviceId), retry = false, sendOnce = true)
    }

    override suspend fun restart(s: Session) = gated(s, "restart") {
        api.post(s, "/System/Restart", retry = false, sendOnce = true)
    }

    override suspend fun shutDown(s: Session) = gated(s, "shutDown") {
        api.post(s, "/System/Shutdown", retry = false, sendOnce = true)
    }

    // ---- rules

    private inline fun <T> gated(s: Session, call: String, block: () -> T): T = logged(call) {
        if (!allows(s)) throw AppError.of(AppError.Kind.FORBIDDEN)
        block()
    }

    private inline fun <T> logged(call: String, block: () -> T): T = try {
        block()
    } catch (e: AppError) {
        AppLog.d(TAG, "admin $call failed: ${e.kind}")
        throw e
    }

    private fun sessionId(raw: String) = raw.takeIf { AdminLimits.SESSION_ID.matches(it) } ?: throw notFound()

    private fun taskId(raw: String) = raw.takeIf { AdminLimits.TASK_ID.matches(it) } ?: throw notFound()

    /** Only an id already in the normalised form this seam returns; a dashed or upper-case one is refused, not repaired. */
    private fun jellyfinId(raw: String) = raw.takeIf { JellyfinId.parseOrNull(it)?.value == it } ?: throw notFound()

    private fun notFound() = AppError.of(AppError.Kind.SERVER_NOT_FOUND)

    private companion object {
        const val TAG = "Admin"
        const val ONE_MIB = 1024L * 1024
        /** What jellyfin-web's dashboard asks: without it every session the server remembers is listed. */
        const val ACTIVE_WITHIN_SECONDS = "960"
        const val MESSAGE_TIMEOUT_MS = 10_000L
        const val MAX_START_INDEX = 1_000_000
        val ELEMENTS = ListSerializer(JsonElement.serializer())
        /** "Scan for new and changed files", with only the parameters the 12.4.0 reference lists (no `Recursive`). */
        val SCAN_QUERY = listOf(
            "metadataRefreshMode" to "Default", "imageRefreshMode" to "Default", "replaceAllMetadata" to "false",
            "replaceAllImages" to "false", "regenerateTrickplay" to "false",
        )
    }
}
