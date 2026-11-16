package app.shoalwatch.data.playback

/**
 * Which audio and subtitle track an item starts with (pure). Tracks are matched by identity, never by index: language and
 * the forced and hearing-impaired flags (research complaint 11), so a choice carries over to the next episode.
 *
 * - Audio: the remembered language (that language's default track first, else its first) -> the server's default -> the first.
 * - Subtitles: remembered "off" (then only a forced track in the audio's language) -> the remembered language (matching the
 *   remembered forced and hearing-impaired flags first) -> system captions on: a track in the caption language, else in the
 *   phone's language (not forced; hearing-impaired first) -> a forced track in the audio's language -> the server's default
 *   (it applies the user's subtitle mode on the server) -> off.
 */
object TrackChooser {
    data class Choice(val audioIndex: Int?, val subtitleIndex: Int?)

    fun choose(
        audio: List<AudioTrack>,
        subs: List<SubtitleTrack>,
        remembered: TrackChoice?,
        captions: CaptionDefaults,
        serverDefaultAudio: Int?,
        serverDefaultSubtitle: Int?,
    ): Choice {
        val chosenAudio = remembered?.audioLanguage?.let { lang -> audio.filter { Languages.same(it.language, lang) } }
            ?.let { same -> same.firstOrNull { it.isDefault } ?: same.firstOrNull() }
            ?: audio.firstOrNull { it.index == serverDefaultAudio }
            ?: audio.firstOrNull()
        val audioLanguage = chosenAudio?.language
        fun forced() = subs.firstOrNull { it.forced && Languages.same(it.language, audioLanguage) }
        if (remembered?.subtitlesOff == true) return Choice(chosenAudio?.index, forced()?.index)
        val subtitle = remembered?.subtitleLanguage?.let { lang -> rememberedTrack(subs, lang, remembered) }
            ?: captionTrack(subs, captions)
            ?: forced()
            ?: subs.firstOrNull { it.index == serverDefaultSubtitle }
        return Choice(chosenAudio?.index, subtitle?.index)
    }

    private fun rememberedTrack(subs: List<SubtitleTrack>, language: String, r: TrackChoice): SubtitleTrack? {
        val same = subs.filter { Languages.same(it.language, language) }
        return same.firstOrNull { it.forced == r.forced && it.hearingImpaired == r.hearingImpaired }
            ?: same.firstOrNull { it.forced == r.forced }
            ?: same.firstOrNull()
    }

    private fun captionTrack(subs: List<SubtitleTrack>, captions: CaptionDefaults): SubtitleTrack? {
        if (!captions.enabled) return null
        fun inLanguage(lang: String?): SubtitleTrack? {
            val full = subs.filter { !it.forced && Languages.same(it.language, lang) }
            return full.firstOrNull { it.hearingImpaired } ?: full.firstOrNull()
        }
        return inLanguage(captions.language) ?: inLanguage(captions.phoneLanguage)
    }
}
