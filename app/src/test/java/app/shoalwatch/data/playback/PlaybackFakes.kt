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
import app.shoalwatch.data.playback.engine.EngineEvent
import app.shoalwatch.data.playback.engine.EngineItem
import app.shoalwatch.data.playback.engine.PlayerEngine
import app.shoalwatch.data.playback.engine.VideoOutput
import android.view.SurfaceHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
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
    /** Holds every PlaybackInfo call (after it is recorded) until completed. */
    var infoGate: CompletableDeferred<Unit>? = null
    /** What [segments] answers, or throws [segmentsFail]. */
    var segmentAnswer: List<MediaSegment> = emptyList()
    var segmentsFail: Throwable? = null

    fun answer(vararg info: PlaybackInfo) { infos.addAll(info) }

    override suspend fun playbackInfo(s: Session, itemId: String, body: PlaybackInfoBody): PlaybackInfo {
        calls += "playbackInfo:$itemId"
        bodies += body
        infoGate?.await()
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

    override suspend fun segments(s: Session, itemId: String): List<MediaSegment> {
        calls += "segments:$itemId"
        segmentsFail?.let { throw it }
        return segmentAnswer
    }
    /** Subtitle files by stream index (none: SERVER_NOT_FOUND); each call is recorded as `subtitle:<index>.<format>`. */
    val subtitleFiles = mutableMapOf<Int, ByteArray>()
    /** Holds every subtitle call (after it is recorded) until completed. */
    var subtitleGate: CompletableDeferred<Unit>? = null

    override suspend fun subtitleFile(s: Session, itemId: String, mediaSourceId: String, index: Int, format: String): ByteArray {
        calls += "subtitle:$index.$format"
        subtitleGate?.await()
        return subtitleFiles[index] ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)
    }
}

/**
 * A [PlayerEngine] that behaves like ExoPlayer where the controller can tell: [pause] and [play] change [isPlaying] and say so,
 * [seekTo] lands at once and says so. A test drives everything else with [emit] and by setting [positionMs] and [durationMs].
 */
internal class FakePlayerEngine : PlayerEngine {
    private val flow = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    override val events = flow
    override var positionMs = 0L
    override var durationMs: Long? = null
    override var bufferedMs = 0L
    override var isPlaying = false
    override var playWhenReady = false
    override var audioLanguages: List<String?> = emptyList()
    override val video = FakeVideoOutput()
    val loads = mutableListOf<Pair<EngineItem, Long>>()
    /** Shared between engines to see their order: "<name> load", "<name> release". */
    var log: MutableList<String>? = null
    var name = "engine"
    val loadedAt: Long? get() = loads.lastOrNull()?.second
    val seeks = mutableListOf<Long>()
    var speedSet = 1f
    var released = false
    var metadataTitle: String? = null
    var metadataSubtitle: String? = null
    var audioOrdinal: Int? = null
    var pictureOrdinal: Int? = null
    /** [setHostStopped]: what the real engine's session player refuses a controller while true. */
    var stoppedHost = false

    /** A headset or Bluetooth "play" through the media session, refused while the host is stopped (as `SessionPlayer` does). */
    fun controllerPlay() { if (!stoppedHost) play() }

    fun emit(event: EngineEvent) = check(flow.tryEmit(event)) { "event buffer full" }

    /** Ready and playing, as after a load with playWhenReady. */
    fun startPlaying() { emit(EngineEvent.Ready); isPlaying = true; playWhenReady = true; emit(EngineEvent.PlayingChanged(true)) }

    override fun load(item: EngineItem, startMs: Long, playWhenReady: Boolean) {
        check(!released) { "loaded after release" }
        log?.add("$name load")
        loads += item to startMs
        positionMs = startMs
        isPlaying = false
        this.playWhenReady = playWhenReady
    }
    override fun play() { playWhenReady = true; if (!isPlaying) { isPlaying = true; emit(EngineEvent.PlayingChanged(true)) } }
    override fun pause() { playWhenReady = false; if (isPlaying) { isPlaying = false; emit(EngineEvent.PlayingChanged(false)) } }
    override fun seekTo(ms: Long) { seeks += ms; positionMs = ms; emit(EngineEvent.PositionDiscontinuity(ms)) }
    override fun setSpeed(speed: Float) { speedSet = speed }
    override fun selectAudio(ordinal: Int?) { audioOrdinal = ordinal }
    override fun selectPictureSubtitle(ordinal: Int?) { pictureOrdinal = ordinal }
    override fun setMediaMetadata(title: String?, subtitle: String?) { metadataTitle = title; metadataSubtitle = subtitle }
    override fun setHostStopped(stopped: Boolean) { stoppedHost = stopped }
    override fun release() { if (!released) log?.add("$name release"); released = true; isPlaying = false; video.holder = null; video.released = true }
}

/** The engine's video output as the real one behaves: one surface at a time, let go on release, [attach] ignored after it. */
internal class FakeVideoOutput : VideoOutput {
    override val aspectRatio = MutableStateFlow<Float?>(null)
    var holder: SurfaceHolder? = null
    var released = false
    override val attached: Boolean get() = holder != null
    override fun attach(holder: SurfaceHolder) { if (!released) this.holder = holder }
    override fun detach(holder: SurfaceHolder) { if (this.holder === holder) this.holder = null }

    companion object {
        /** A holder that does nothing, for JVM tests (no Robolectric needed). */
        fun holder(): SurfaceHolder = java.lang.reflect.Proxy.newProxyInstance(
            SurfaceHolder::class.java.classLoader, arrayOf(SurfaceHolder::class.java),
        ) { _, method, _ -> if (method.returnType == java.lang.Boolean.TYPE) false else null } as SurfaceHolder
    }
}
