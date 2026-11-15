package app.shoalwatch.data.playback.engine

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommands
import app.shoalwatch.data.api.MediaRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.Call

/**
 * The one place ExoPlayer is touched; main thread only, as thin as it can be (the behaviour is in `PlaybackController`, the
 * rules in [MediaItems]). Covered on a device by the player smoke test.
 *
 * - **Bytes:** every request goes through the current item's `MediaRoute` call factory (the account's own guarded client) via
 *   `OkHttpDataSource`; there is no other data source. After [release] there is no route, so nothing more can be fetched.
 * - **Audio:** usage media / content movie, ExoPlayer handles audio focus and pauses when headphones go (reported as
 *   [EngineEvent.BecameNoisy]). No wake mode (the manifest has no `WAKE_LOCK`: the player screen keeps itself on).
 * - **Media session:** activity-scoped, no service, so no notification and no exported component. Controllers (headset,
 *   Bluetooth, Assistant) get play/pause, seeking and the title only: they cannot set media items, change speed or tracks.
 * - **Text renderer** off unless a picture-subtitle track is selected (text subtitles are the app's own).
 */
@OptIn(UnstableApi::class)
class ExoPlayerEngine(context: Context) : PlayerEngine {
    private val appContext = context.applicationContext
    private val flow = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    override val events: Flow<EngineEvent> = flow

    /** Read on Media3's loading threads. */
    @Volatile private var route: MediaRoute? = null
    private val calls = Call.Factory { request -> checkNotNull(route) { "No media route." }.callFactory.newCall(request) }

    private val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(calls)))
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(SEEK_MS)
        .setSeekForwardIncrementMs(SEEK_MS)
        .build()

    private val session: MediaSession = MediaSession.Builder(appContext, player).setId(SESSION_ID).setCallback(ControllerRules).build()

    private var audioOrdinal: Int? = null
    private var pictureOrdinal: Int? = null
    private var title: String? = null
    private var subtitle: String? = null
    private var released = false

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) = emit(EngineEvent.Failed(MediaItems.failureOf(error)))

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) emit(EngineEvent.BecameNoisy)
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                emit(EngineEvent.PositionDiscontinuity(newPosition.positionMs))
            }
        }

        override fun onCues(cueGroup: CueGroup) {
            if (pictureOrdinal != null) emit(EngineEvent.PictureCues(SubtitleFrame(cueGroup.cues)))
        }

        /** After the single callbacks above: the state first, then playing, so a rebuffer reads as Buffering, not a pause. */
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                when (player.playbackState) {
                    Player.STATE_READY -> emit(EngineEvent.Ready)
                    Player.STATE_BUFFERING -> emit(EngineEvent.Buffering)
                    Player.STATE_ENDED -> emit(EngineEvent.Ended)
                    else -> Unit
                }
            }
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) emit(EngineEvent.PlayingChanged(player.isPlaying))
            if (events.contains(Player.EVENT_TRACKS_CHANGED)) applyTracks()
        }
    }

    init {
        player.addListener(listener)
        applyTracks()
    }

    override val positionMs: Long get() = player.currentPosition
    override val durationMs: Long? get() = player.duration.takeIf { it != C.TIME_UNSET }
    override val bufferedMs: Long get() = player.bufferedPosition
    override val isPlaying: Boolean get() = player.isPlaying
    override val audioLanguages: List<String?>
        get() = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.map { it.getTrackFormat(0).language }

    override fun load(item: EngineItem, startMs: Long, playWhenReady: Boolean) {
        check(!released) { "Engine released." }
        route = item.route
        player.setMediaItem(MediaItems.build(item, title, subtitle), startMs)
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(ms: Long) = player.seekTo(ms)
    override fun setSpeed(speed: Float) { player.playbackParameters = PlaybackParameters(speed) }

    override fun selectAudio(ordinal: Int?) { audioOrdinal = ordinal; applyTracks() }
    override fun selectPictureSubtitle(ordinal: Int?) { pictureOrdinal = ordinal; applyTracks() }

    override fun setMediaMetadata(title: String?, subtitle: String?) {
        this.title = title
        this.subtitle = subtitle
        val item: MediaItem = player.currentMediaItem ?: return
        if (!released) player.replaceMediaItem(0, item.buildUpon().setMediaMetadata(MediaItems.metadata(title, subtitle)).build())
    }

    override fun release() {
        if (released) return
        released = true
        session.release()
        player.removeListener(listener)
        player.release()
        route = null
    }

    /** The Nth audio group and the Nth picture-subtitle group of what the stream has; text off unless a picture track is chosen. */
    private fun applyTracks() {
        if (released) return
        val groups = player.currentTracks.groups
        val wanted = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        audioOrdinal?.let { n -> groups.filter { it.type == C.TRACK_TYPE_AUDIO }.getOrNull(n) }
            ?.let { wanted.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
        val picture = pictureOrdinal?.let { n -> groups.filter { it.type == C.TRACK_TYPE_TEXT && isPicture(it.getTrackFormat(0)) }.getOrNull(n) }
        if (picture != null) {
            wanted.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(picture.mediaTrackGroup, 0))
        } else {
            wanted.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }
        val parameters = wanted.build()
        if (parameters != player.trackSelectionParameters) player.trackSelectionParameters = parameters
    }

    /** Media3 parses subtitles while extracting, so the original type can be in `codecs` rather than `sampleMimeType`. */
    private fun isPicture(format: Format) = format.sampleMimeType in PICTURE_TYPES || format.codecs in PICTURE_TYPES

    private fun emit(event: EngineEvent) { flow.tryEmit(event) }

    /** What a media-session controller may do: play, pause and seek, and read the title. Nothing that changes what is played. */
    private object ControllerRules : MediaSession.Callback {
        private val allowed = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA,
        ).build()

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.accept(SessionCommands.Builder().build(), allowed)
    }

    private companion object {
        const val SESSION_ID = "shoalwatch-player"
        const val SEEK_MS = 10_000L
        val PICTURE_TYPES = setOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB, MimeTypes.APPLICATION_DVBSUBS)
    }
}
