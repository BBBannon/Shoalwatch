package app.shoalwatch.data.playback.engine

import app.shoalwatch.data.api.MediaRoute
import app.shoalwatch.data.playback.StreamSource
import kotlinx.coroutines.flow.Flow

/**
 * The only player surface the controller sees (`ExoPlayerEngine` in the app, `FakePlayerEngine` in tests). Main thread only.
 * Events arrive in the order they happened; [positionMs], [durationMs], [bufferedMs] and [isPlaying] are read when needed.
 */
interface PlayerEngine {
    val events: Flow<EngineEvent>
    val positionMs: Long
    /** Null while not known. */
    val durationMs: Long?
    /** How far ahead is loaded. */
    val bufferedMs: Long
    val isPlaying: Boolean
    /** The languages of the stream's audio tracks in stream order, as the stream names them; empty until they are known. */
    val audioLanguages: List<String?>
    fun load(item: EngineItem, startMs: Long, playWhenReady: Boolean)
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun setSpeed(speed: Float)
    /** The Nth audio track in the stream (DIRECT_PLAY only), null = the player's default. */
    fun selectAudio(ordinal: Int?)
    /** The Nth picture-subtitle track in the stream, null = the text renderer off. */
    fun selectPictureSubtitle(ordinal: Int?)
    /** For the media session (headset, Bluetooth, Assistant); null clears. */
    fun setMediaMetadata(title: String?, subtitle: String?)
    /** At once: stops every load, releases the media session. The engine is not used again. */
    fun release()
}

/** What to load: the URL is built inside the engine module from the route; it never leaves `data/playback`. Never printed. */
class EngineItem internal constructor(internal val route: MediaRoute, internal val source: StreamSource, val mediaId: String) {
    override fun toString() = "EngineItem(<redacted>)"
}

sealed interface EngineEvent {
    data object Ready : EngineEvent
    data object Buffering : EngineEvent
    data class PlayingChanged(val playing: Boolean) : EngineEvent
    data object Ended : EngineEvent
    data class Failed(val category: EngineFailure) : EngineEvent
    /** A seek (by the app or a media-session controller) landed at [toMs]. */
    data class PositionDiscontinuity(val toMs: Long) : EngineEvent
    /** Only while a PICTURE subtitle track is on: what to draw now. */
    data class PictureCues(val frame: SubtitleFrame) : EngineEvent
    /** Headphones out: ExoPlayer has paused; the controller reports the position. */
    data object BecameNoisy : EngineEvent
}

/**
 * From `PlaybackException.errorCode` only (never its message), except the two route refusals, which are found by type
 * (`BlockedRequestException` in the cause chain): [ROUTE_STALE] (`OFFLINE`: ask for a new route) and [NOT_SIGNED_IN].
 */
enum class EngineFailure { DECODER, FORMAT_UNSUPPORTED, NETWORK, HTTP_UNAUTHORISED, HTTP_OTHER, BEHIND_LIVE_WINDOW, ROUTE_STALE, NOT_SIGNED_IN, OTHER }
