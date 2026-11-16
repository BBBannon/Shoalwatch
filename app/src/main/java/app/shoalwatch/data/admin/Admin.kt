package app.shoalwatch.data.admin

import app.shoalwatch.data.servers.Session

/**
 * The server's admin actions as the UI uses them: token-free [Session] in, validated models out. Implemented by
 * `api/AdminGateway` only. Every call fails `FORBIDDEN` with nothing sent unless the session is a server-confirmed administrator
 * (`AdminRights.allows`). Ids passed in must come from models this seam returned; anything else is `SERVER_NOT_FOUND` with
 * nothing sent. Actions (everything that is not a read) are sent once and never retried.
 */
interface Admin {
    suspend fun serverStatus(s: Session): ServerStatus
    suspend fun sessions(s: Session): List<WatchSession>
    suspend fun sendMessage(s: Session, sessionId: String, header: String, text: String)
    suspend fun command(s: Session, sessionId: String, command: RemoteCommand)
    suspend fun libraries(s: Session): List<LibraryFolder>
    suspend fun scanAll(s: Session)
    suspend fun scanLibrary(s: Session, libraryId: String)
    suspend fun tasks(s: Session): List<ServerTask>
    suspend fun startTask(s: Session, taskId: String)
    suspend fun stopTask(s: Session, taskId: String)
    suspend fun users(s: Session): List<ServerUser>
    suspend fun setUserDisabled(s: Session, userId: String, disabled: Boolean)
    suspend fun activity(s: Session, startIndex: Int, limit: Int): ActivityPage
    suspend fun devices(s: Session): List<ServerDevice>
    suspend fun removeDevice(s: Session, deviceId: String)
    suspend fun restart(s: Session)
    suspend fun shutDown(s: Session)
}
