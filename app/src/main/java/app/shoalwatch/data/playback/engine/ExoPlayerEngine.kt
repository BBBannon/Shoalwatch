package app.shoalwatch.data.playback.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.SurfaceHolder
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommands
import app.shoalwatch.data.api.MediaRoute
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call

/**
 * The one place ExoPlayer is touched; main thread only, as thin as it can be (the behaviour is in `PlaybackController`, the
 * rules in [MediaItems]). Covered on a device by the player smoke test.
 *
 * - **Bytes:** every request goes through the current item's `MediaRoute` call factory (the account's own guarded client) via
 *   `OkHttpDataSource`; there is no other data source. After [release] there is no route, so nothing more can be fetched.
 * - **Audio:** usage media / content movie, ExoPlayer handles audio focus and pauses when headphones go (reported as
 *   [EngineEvent.BecameNoisy]). No wake mode (the manifest has no `WAKE_LOCK`: the player screen keeps itself on).
 * - **Media session:** built at the first load (after any earlier player was ended), a unique id per engine; activity-scoped, no service, so no notification and no exported component. Controllers (headset,
 *   Bluetooth, Assistant) get play/pause, seeking and the title only: they cannot set media items, change speed or tracks.
 * - **Picture:** to the screen's `SurfaceView` through [video] (a SurfaceView, not a TextureView, so HDR output works); let go on
 *   [release].
 * - **Subtitles in the stream:** only PGS, VobSub and DVB are parsed ([PictureSubtitles]); text tracks pass through unparsed,
 *   and the text renderer is off unless a picture-subtitle track is selected (text subtitles are the app's own files).
 */
@OptIn(UnstableApi::class)
class ExoPlayerEngine(context: Context) : PlayerEngine {
    private val appContext = context.applicationContext
    private val flow = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    override val events: Flow<EngineEvent> = flow

    /** Read on Media3's loading threads. */
    @Volatile private var route: MediaRoute? = null
    private val calls = Call.Factory { request -> checkNotNull(route) { "No media route." }.callFactory.newCall(request) }

    /**
     * What builds every media source and, through it, every extractor: with [PictureSubtitles] as its only subtitle parsers, so
     * no text track inside a file is ever parsed by Media3 (subtitle-parsers review I-1). A test reads it.
     */
    internal val mediaSources = DefaultMediaSourceFactory(OkHttpDataSource.Factory(calls)).setSubtitleParserFactory(PictureSubtitles)

    private val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(mediaSources)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(SEEK_MS)
        .setSeekForwardIncrementMs(SEEK_MS)
        .build()

    /**
     * Built at the first [load], so only after `ActivePlayback.begin` has ended (and so released) any earlier player; its id is
     * unique per engine as well, because Media3 refuses a second live session with the same id (Task 6 review I-1).
     */
    private var session: MediaSession? = null

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

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            aspect.value = MediaItems.aspectOf(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
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

    private val aspect = MutableStateFlow<Float?>(null)
    private var surface: SurfaceHolder? = null

    override val video: VideoOutput = object : VideoOutput {
        override val aspectRatio: StateFlow<Float?> = aspect.asStateFlow()
        override val attached: Boolean get() = surface != null

        override fun attach(holder: SurfaceHolder) {
            if (released) return
            surface = holder
            player.setVideoSurfaceHolder(holder)
        }

        override fun detach(holder: SurfaceHolder) {
            if (surface !== holder) return
            surface = null
            if (!released) player.clearVideoSurfaceHolder(holder)
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
    override val playWhenReady: Boolean get() = player.playWhenReady
    override val audioLanguages: List<String?>
        get() = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.map { it.getTrackFormat(0).language }

    override fun load(item: EngineItem, startMs: Long, playWhenReady: Boolean) {
        check(!released) { "Engine released." }
        route = item.route
        if (session == null) {
            session = MediaSession.Builder(appContext, sessionPlayer).setId(SESSION_ID + "-" + sessions.incrementAndGet()).setCallback(ControllerRules)
                .setBitmapLoader(NoArtwork).build()
        }
        textOff() // before the new item: Media3 must never pick one of its text tracks by default (Milestone 3 carried n-1)
        player.setMediaItem(MediaItems.build(item, title, subtitle), startMs)
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    @Volatile private var hostStopped = false

    /**
     * What the media session drives, so every controller command (a headset, Bluetooth, the Assistant, a media button) goes
     * through it: `play` and `playWhenReady = true` are refused while the host is stopped (final review N-4), and so are
     * `prepare` and `seekToDefaultPosition`, which the session's play action runs first on a failed or ended player (m-3); the
     * app's own calls go to [player] directly.
     */
    internal val sessionPlayer: Player = object : ForwardingPlayer(player) {
        override fun play() { if (!hostStopped) super.play() }
        override fun setPlayWhenReady(playWhenReady: Boolean) { if (!playWhenReady || !hostStopped) super.setPlayWhenReady(playWhenReady) }
        override fun prepare() { if (!hostStopped) super.prepare() }
        override fun seekToDefaultPosition() { if (!hostStopped) super.seekToDefaultPosition() }
    }

    override fun setHostStopped(stopped: Boolean) { hostStopped = stopped }

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
        session?.release()
        session = null
        player.removeListener(listener)
        surface?.let(player::clearVideoSurfaceHolder)
        surface = null
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

    /** Text off and no text override; [pictureOrdinal] is kept, and applied to the new item's tracks once they are known. */
    private fun textOff() {
        val parameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        if (parameters != player.trackSelectionParameters) player.trackSelectionParameters = parameters
    }

    /** Media3 parses subtitles while extracting, so the original type can be in `codecs` rather than `sampleMimeType`. */
    private fun isPicture(format: Format) = format.sampleMimeType in PICTURE_TYPES || format.codecs in PICTURE_TYPES

    private fun emit(event: EngineEvent) { flow.tryEmit(event) }

    /**
     * The media session's artwork loader: it loads nothing (Task 6 review M-5). Media3's own one fetches artwork URIs over a
     * default data source, outside the account's guarded client; no artwork is set today, and any added later must come as data.
     */
    internal object NoArtwork : BitmapLoader {
        override fun supportsMimeType(mimeType: String) = false
        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = refused()
        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = refused()
        override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? = null
        private fun refused(): ListenableFuture<Bitmap> = Futures.immediateFailedFuture(UnsupportedOperationException("No artwork."))
    }

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
        val sessions = AtomicLong()
        const val SEEK_MS = 10_000L
        val PICTURE_TYPES = setOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB, MimeTypes.APPLICATION_DVBSUBS)
    }
}
