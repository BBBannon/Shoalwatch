package app.shoalwatch.data.playback

import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.playback.SubtitleChoice.Off
import app.shoalwatch.data.playback.SubtitleChoice.On
import app.shoalwatch.data.playback.engine.PlayerEngine
import app.shoalwatch.data.playback.engine.SubtitleFrame
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What a track change needs: the request to play with, whether the server must be asked again for it, and a burn-in's format. */
internal data class TrackStep(val request: PlaybackRequest, val reask: Boolean, val burnInFormat: String?)

/** The pure rules for switching tracks (see "Tracks and subtitles" in docs/ARCHITECTURE.md). */
internal object TrackRules {
    /**
     * The request for [audioIndex] (null: keep the current one) and [subtitle] (null: off), drawn by the server when [burnIn].
     * In the player, without asking the server: an audio track during DIRECT_PLAY whose position among the stream's tracks
     * agrees with [engineLanguages] (when known), a picture track during DIRECT_PLAY, and every text track (fetched as a file
     * whatever the play method). Everything else asks again: audio during DIRECT_STREAM or TRANSCODE (or a DIRECT_PLAY track the
     * stream does not match: then as a conversion, since a direct stream cannot change its audio), a picture track to be burned
     * in, an external picture track (always burned in), a burn-in, and leaving a burn-in ([keepConversion]: the item had to be
     * converted anyway, after a decoder failure or an audio mismatch).
     */
    fun step(
        plan: PlaybackPlan, audioIndex: Int?, subtitle: SubtitleTrack?, burnIn: Boolean, engineLanguages: List<String?>, keepConversion: Boolean,
        r: PlaybackRequest = plan.request, pending: Boolean = false,
    ): TrackStep {
        val audio = audioIndex ?: r.audioIndex ?: plan.audioIndex
        val direct = plan.method == PlayMethod.DIRECT_PLAY
        val burned = subtitle?.takeIf { burnIn || (it.kind == SubtitleKind.PICTURE && it.external) }
        val picture = subtitle?.takeIf { it.kind == SubtitleKind.PICTURE && burned == null }
        val audioInPlayer = audio == plan.audioIndex || (direct && audio != null && audioOrdinal(plan, audio, engineLanguages) != null)
        val forced = when {
            burned != null || (direct && !audioInPlayer) -> true
            r.burnInSubtitleIndex != null -> keepConversion
            else -> r.forceTranscode
        }
        val wanted = r.copy(audioIndex = audio, pictureSubtitleIndex = picture?.index, burnInSubtitleIndex = burned?.index, forceTranscode = forced)
        // While the server is still being asked ([pending]), the plan playing is not the one that will: ask again with everything.
        val reask = pending || !audioInPlayer || wanted.forceTranscode != r.forceTranscode || wanted.burnInSubtitleIndex != r.burnInSubtitleIndex ||
            (!direct && wanted.pictureSubtitleIndex != r.pictureSubtitleIndex)
        return TrackStep(wanted, reask, burned?.format)
    }

    /** Whether [plan]'s own request asks for picture track [index] (in the stream or burned in), so [plan] can show it. */
    fun carries(plan: PlaybackPlan, index: Int): Boolean =
        plan.request.pictureSubtitleIndex == index || plan.request.burnInSubtitleIndex == index

    /** The format of the subtitle [plan] burns in, for asking again with the same burn-in. */
    fun burnInFormat(plan: PlaybackPlan): String? =
        plan.request.burnInSubtitleIndex?.let { i -> plan.subtitleTracks.firstOrNull { it.index == i }?.format }

    /** The plan with the tracks of [request] chosen in the player (no new play session). */
    fun withRequest(plan: PlaybackPlan, request: PlaybackRequest): PlaybackPlan =
        plan.copy(request = request, audioIndex = request.audioIndex?.takeIf { i -> plan.audioTracks.any { it.index == i } } ?: plan.audioIndex)

    fun deliveryOf(plan: PlaybackPlan, track: SubtitleTrack, burnIn: Boolean): SubtitleDelivery = when {
        burnIn -> SubtitleDelivery.BURNED_IN
        track.kind != SubtitleKind.PICTURE -> SubtitleDelivery.FILE
        plan.method == PlayMethod.DIRECT_PLAY && !track.external -> SubtitleDelivery.IN_STREAM
        else -> SubtitleDelivery.BURNED_IN
    }

    /**
     * The audio track's position among the stream's audio tracks (in Jellyfin's index order), or null when the stream does
     * not have it there: [engineLanguages] known and either shorter or naming a different language at that position.
     */
    fun audioOrdinal(plan: PlaybackPlan, index: Int, engineLanguages: List<String?>): Int? {
        val sorted = plan.audioTracks.sortedBy { it.index }
        val at = sorted.indexOfFirst { it.index == index }.takeIf { it >= 0 } ?: return null
        if (engineLanguages.isEmpty()) return at
        if (at >= engineLanguages.size || !Languages.agree(sorted[at].language, engineLanguages[at])) return null
        return at
    }

    /** The picture track's position among the stream's own (not external) picture tracks. */
    fun pictureOrdinal(plan: PlaybackPlan, index: Int): Int? =
        plan.subtitleTracks.filter { it.kind == SubtitleKind.PICTURE && !it.external }.sortedBy { it.index }.indexOfFirst { it.index == index }.takeIf { it >= 0 }
}

/**
 * The controller's audio and subtitle behaviour, kept apart so `PlaybackController` reads as one state machine. Everything it
 * keeps per item lives in [PlayingItem] (so a new item or the end of playback drops it); [cues] is what to draw now. Main
 * thread only, like the controller.
 */
internal class TrackControl(
    private val session: Session,
    private val engine: PlayerEngine,
    private val resolver: PlaybackResolver,
    private val preferences: TrackPreferences,
    private val loader: SubtitleLoader,
    private val captions: () -> CaptionDefaults,
    private val work: CoroutineScope,
    private val appScope: CoroutineScope,
    private val host: Host,
) {
    interface Host {
        val current: PlayingItem?
        /** Stops this play session and asks again at the known position. */
        fun reload(c: PlayingItem, how: suspend () -> Resolved)
        /** Reports a change made in the player (no new play session) and publishes. */
        fun tracksChanged(c: PlayingItem)
        fun publish()
        /** The position to draw subtitles at, before the delay. */
        fun cuePositionMs(c: PlayingItem): Long
        /** The server is being asked again for the current item (a track change, quality, fallback, re-resolve): not yet loaded. */
        val reloading: Boolean
    }

    private val _cues = MutableStateFlow(SubtitleFrame.NONE)
    val cues: StateFlow<SubtitleFrame> = _cues.asStateFlow()

    /** The last choice made in this controller, so the next episode follows it even before the database write lands. */
    private var memory: Pair<String, TrackChoice>? = null

    suspend fun recall(c: PlayingItem) {
        val seriesId = seriesOf(c) ?: return
        c.remembered = memory?.takeIf { it.first == seriesId }?.second ?: preferences.get(session, seriesId)
        c.choice = c.remembered
    }

    /** The first answer for an item: its starting tracks ([TrackChooser]), asking once more only if they need another stream. */
    suspend fun initial(c: PlayingItem, resolved: Resolved): Resolved {
        val plan = resolved.plan
        val pick = TrackChooser.choose(plan.audioTracks, plan.subtitleTracks, c.remembered, captions(), plan.defaultAudioIndex, plan.defaultSubtitleIndex)
        val track = plan.subtitleTracks.firstOrNull { it.index == pick.subtitleIndex }
        c.subtitle = track?.let { On(it.index, TrackRules.deliveryOf(plan, it, burnIn = false)) } ?: Off
        val step = TrackRules.step(plan, pick.audioIndex, track, burnIn = false, emptyList(), keepConversion = false)
        c.wanted = step.request
        return if (step.reask) resolver.resolve(session, step.request, step.burnInFormat) else Resolved(TrackRules.withRequest(plan, step.request), resolved.route)
    }

    /** What the user's latest choices want ([PlayingItem.wanted]), or the playing plan's request before any. */
    fun wanted(c: PlayingItem): PlaybackRequest = c.wanted ?: checkNotNull(c.resolved).plan.request

    /** Changes [wanted] (a quality, a fallback's conversion) and returns it, so a change made before the answer keeps it. */
    fun want(c: PlayingItem, change: (PlaybackRequest) -> PlaybackRequest): PlaybackRequest = change(wanted(c)).also { c.wanted = it }

    /** Before each load, and after a change made in the player: the delivery [plan] gives the subtitle, the player's tracks, the file. */
    fun prepare(c: PlayingItem, plan: PlaybackPlan) {
        c.wanted = plan.request
        val track = (c.subtitle as? On)?.let { on -> plan.subtitleTracks.firstOrNull { it.index == on.index } }
        val delivery = track?.let { TrackRules.deliveryOf(plan, it, burnIn = plan.request.burnInSubtitleIndex == it.index) }
        c.subtitle = if (track != null && delivery != null) On(track.index, delivery) else Off
        // A picture track this stream would need burned in, but its request did not ask for (an answer built from an older
        // request): asked for once the load in hand is done, never just labelled as burned in (Task 7 review I-1).
        if (track != null && delivery == SubtitleDelivery.BURNED_IN && !TrackRules.carries(plan, track.index)) {
            val again = plan.request.copy(pictureSubtitleIndex = track.index)
            c.wanted = again
            work.launch { if (host.current === c) host.reload(c) { resolver.resolve(session, again, TrackRules.burnInFormat(plan)) } }
        }
        val direct = plan.method == PlayMethod.DIRECT_PLAY
        engine.selectAudio(plan.audioIndex?.takeIf { direct }?.let { TrackRules.audioOrdinal(plan, it, emptyList()) })
        engine.selectPictureSubtitle(track?.takeIf { delivery == SubtitleDelivery.IN_STREAM }?.let { TrackRules.pictureOrdinal(plan, it.index) })
        if (delivery != SubtitleDelivery.IN_STREAM) c.pictureFrame = SubtitleFrame.NONE
        if (track != null && delivery == SubtitleDelivery.FILE) {
            if (c.timeline == null && c.subtitleLoad?.isActive != true) loadFile(c, plan, track)
        } else dropFile(c)
    }

    fun selectAudio(index: Int) {
        val c = host.current ?: return
        val plan = c.resolved?.plan ?: return
        val track = plan.audioTracks.firstOrNull { it.index == index } ?: return
        if (index == (wanted(c).audioIndex ?: plan.audioIndex)) return
        remember(c) { it.copy(audioLanguage = track.language) }
        apply(c, plan, step(c, plan, index, subtitleTrack(c, plan), burnIn = isBurnedIn(c)))
    }

    /** Null: off. A forced track in the audio's language still shows on the next item (see [TrackChooser]). */
    fun selectSubtitle(index: Int?) {
        val c = host.current ?: return
        val plan = c.resolved?.plan ?: return
        val track = index?.let { i -> plan.subtitleTracks.firstOrNull { it.index == i } ?: return }
        if (track != null && track.kind != SubtitleKind.PICTURE && track.fileFormat == null) return
        if (track?.index == (c.subtitle as? On)?.index && !isBurnedIn(c)) return
        c.notice = null
        dropFile(c)
        c.subtitle = track?.let { On(it.index, TrackRules.deliveryOf(plan, it, burnIn = false)) } ?: Off
        remember(c) { it.copy(subtitleLanguage = track?.language, subtitlesOff = track == null, forced = track?.forced == true, hearingImpaired = track?.hearingImpaired == true) }
        apply(c, plan, step(c, plan, null, track, burnIn = false))
    }

    /** "Let the server draw these subtitles": the server burns [index] into the picture (a conversion). */
    fun burnInSubtitle(index: Int) {
        val c = host.current ?: return
        val plan = c.resolved?.plan ?: return
        val track = plan.subtitleTracks.firstOrNull { it.index == index } ?: return
        if (wanted(c).burnInSubtitleIndex == index) return
        c.notice = null
        dropFile(c)
        c.subtitle = On(index, SubtitleDelivery.BURNED_IN)
        remember(c) { it.copy(subtitleLanguage = track.language, subtitlesOff = false, forced = track.forced, hearingImpaired = track.hearingImpaired) }
        apply(c, plan, step(c, plan, null, track, burnIn = true))
    }

    /** For a FILE subtitle only (the others are drawn in the stream or the picture): later is positive. Kept for the item, across seeks. */
    fun setDelay(ms: Int) {
        val c = host.current ?: return
        if ((c.subtitle as? On)?.delivery != SubtitleDelivery.FILE) return
        c.delayMs = SubtitleSettings.delayOf(ms)
        host.publish()
    }

    fun dismissNotice() { host.current?.let { it.notice = null; host.publish() } }

    /**
     * Once the stream is ready in DIRECT_PLAY with an audio track other than its default: if the stream's track at that
     * position names another language, ask once for a conversion with the chosen track (identity, not index).
     */
    fun onReady(c: PlayingItem) {
        val plan = c.resolved?.plan ?: return
        val audio = plan.audioIndex ?: return
        if (plan.method != PlayMethod.DIRECT_PLAY || c.audioForced || audio == plan.defaultAudioIndex) return
        val languages = engine.audioLanguages
        if (languages.isEmpty() || TrackRules.audioOrdinal(plan, audio, languages) != null) return
        c.audioForced = true
        val r = want(c) { it.copy(forceTranscode = true) }
        host.reload(c) { resolver.resolve(session, r) }
    }

    fun refreshCues() {
        val c = host.current
        val on = c?.subtitle as? On
        _cues.value = when (on?.delivery) {
            SubtitleDelivery.FILE -> c.timeline?.frameAt(host.cuePositionMs(c) - c.delayMs) ?: SubtitleFrame.NONE
            SubtitleDelivery.IN_STREAM -> c.pictureFrame
            else -> SubtitleFrame.NONE
        }
    }

    /** Playback ended: nothing more is drawn. */
    fun clear() { _cues.value = SubtitleFrame.NONE }

    private fun step(c: PlayingItem, plan: PlaybackPlan, audioIndex: Int?, subtitle: SubtitleTrack?, burnIn: Boolean) =
        TrackRules.step(plan, audioIndex, subtitle, burnIn, engine.audioLanguages, keep(c), wanted(c), host.reloading)

    private fun apply(c: PlayingItem, plan: PlaybackPlan, step: TrackStep) {
        val resolved = c.resolved ?: return
        c.wanted = step.request
        if (step.reask) return host.reload(c) { resolver.resolve(session, step.request, step.burnInFormat) }
        val next = TrackRules.withRequest(plan, step.request)
        c.resolved = Resolved(next, resolved.route)
        prepare(c, next)
        host.tracksChanged(c)
    }

    private fun loadFile(c: PlayingItem, plan: PlaybackPlan, track: SubtitleTrack) {
        val wanted = On(track.index, SubtitleDelivery.FILE)
        c.subtitleLoad = work.launch {
            val timeline = try {
                loader.load(session, plan.itemId, plan.mediaSourceId, track)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (host.current !== c || c.subtitle != wanted) return@launch
            if (timeline == null) {
                c.subtitle = Off
                c.notice = SubtitleNotice.UNREADABLE
            } else {
                c.timeline = timeline
                if (timeline.usesEffectsThePhoneCannotDraw && !c.effectsNoticeGiven) {
                    c.effectsNoticeGiven = true
                    c.notice = SubtitleNotice.EFFECTS_NOT_DRAWN
                }
            }
            host.publish()
        }
    }

    private fun dropFile(c: PlayingItem) {
        c.subtitleLoad?.cancel()
        c.subtitleLoad = null
        c.timeline = null
    }

    private fun subtitleTrack(c: PlayingItem, plan: PlaybackPlan): SubtitleTrack? =
        (c.subtitle as? On)?.let { on -> plan.subtitleTracks.firstOrNull { it.index == on.index } }

    private fun isBurnedIn(c: PlayingItem) = wanted(c).burnInSubtitleIndex.let { it != null && it == (c.subtitle as? On)?.index }

    private fun keep(c: PlayingItem) = c.fellBack || c.audioForced

    /** Saved for the series at once (episodes only), and kept here for the next episode. */
    private fun remember(c: PlayingItem, change: (TrackChoice) -> TrackChoice) {
        val seriesId = seriesOf(c) ?: return
        val next = change(c.choice ?: current(c))
        c.choice = next
        memory = seriesId to next
        appScope.launch { preferences.remember(session, seriesId, next) }
    }

    private fun current(c: PlayingItem): TrackChoice {
        val plan = c.resolved?.plan
        val audio = plan?.audioTracks?.firstOrNull { it.index == plan.audioIndex }
        val sub = plan?.let { subtitleTrack(c, it) }
        return TrackChoice(audio?.language, sub?.language, subtitlesOff = sub == null, forced = sub?.forced == true, hearingImpaired = sub?.hearingImpaired == true)
    }

    private fun seriesOf(c: PlayingItem): String? = c.entry?.item?.takeIf { it.kind == ItemKind.EPISODE }?.seriesId
}
