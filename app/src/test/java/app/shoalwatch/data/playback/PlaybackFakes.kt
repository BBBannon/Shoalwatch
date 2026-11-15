package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.data.api.JsonBodies
import app.shoalwatch.data.api.MediaRoute
import app.shoalwatch.data.playback.api.PlaybackGateway
import app.shoalwatch.data.playback.api.PlaybackInfo
import app.shoalwatch.data.playback.api.PlaybackInfoBody
import app.shoalwatch.data.playback.api.PlaybackInfoResponseDto
import app.shoalwatch.data.playback.api.toPlaybackInfo
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.Session
import app.shoalwatch.net.ServerAddress
import kotlinx.coroutines.CompletableDeferred
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl

/** The playback fixtures (`app/src/test/resources/jellyfin/playback/`) decoded through the real mappers, as the gateway would. */
internal fun fixtureInfo(name: String): PlaybackInfo {
    val raw = PlaybackFakes::class.java.getResource("/jellyfin/playback/$name")!!.readText()
    return JsonBodies.JSON.decodeFromString(PlaybackInfoResponseDto.serializer(), raw).toPlaybackInfo()
}

internal object PlaybackFakes {
    const val ITEM = "0123456789abcdef0123456789abcdef"
    const val SOURCE = "0123456789abcdef0123456789abcdef"
    const val PSID = "5e55105e55105e55105e55105e551051"

    /** A route that is never used to send anything (its call factory refuses), for code that only passes routes along. */
    fun route(s: Session): MediaRoute {
        val address = ServerAddress.parse("https://media.example:8920")
        return MediaRoute(s, address.value.toHttpUrl(), Call.Factory { error("not in this test") }, Connection.Online(address, isHome = false))
    }
}

/** Records every call as text (never a position-free summary: tests read them); answers from [infos] in order, or throws [failWith]. */
internal class FakePlaybackGateway : PlaybackGateway {
    val calls = mutableListOf<String>()
    val bodies = mutableListOf<PlaybackInfoBody>()
    val reports = mutableListOf<Pair<Session, PlaybackReport>>()
    var infos = ArrayDeque<PlaybackInfo>()
    var failWith: Throwable? = null
    /** Per call name (`start`, `progress`, `stopped`, `ping`): throw this instead of answering. */
    val failOps = mutableMapOf<String, Throwable>()
    /** Holds every report call until completed. */
    var gate: CompletableDeferred<Unit>? = null

    fun answer(vararg info: PlaybackInfo) { infos.addAll(info) }

    override suspend fun playbackInfo(s: Session, itemId: String, body: PlaybackInfoBody): PlaybackInfo {
        calls += "playbackInfo:$itemId"
        bodies += body
        failWith?.let { throw it }
        return infos.removeFirstOrNull() ?: error("no PlaybackInfo answer queued")
    }

    private suspend fun report(op: String, s: Session, r: PlaybackReport) {
        calls += "$op@${r.positionMs}${if (r.paused) " paused" else ""}${if (r.playSessionId == null) " nosession" else ""}"
        reports += s to r
        gate?.await()
        failOps[op]?.let { throw it }
    }

    override suspend fun reportStart(s: Session, r: PlaybackReport) = report("start", s, r)
    override suspend fun reportProgress(s: Session, r: PlaybackReport) = report("progress", s, r)
    override suspend fun reportStopped(s: Session, r: PlaybackReport) = report("stopped", s, r)

    override suspend fun ping(s: Session, playSessionId: String) {
        calls += "ping"
        gate?.await()
        failOps["ping"]?.let { throw it }
    }

    override suspend fun segments(s: Session, itemId: String): List<app.shoalwatch.data.playback.MediaSegment> = emptyList()
    override suspend fun subtitleFile(s: Session, itemId: String, mediaSourceId: String, index: Int, format: String): ByteArray =
        throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)
}
