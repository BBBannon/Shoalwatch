package app.shoalwatch.data.playback.engine

import androidx.media3.common.text.Cue

/**
 * The subtitles to draw now: text cues from a [CueTimeline] or picture cues from the stream. Opaque outside the engine (the
 * Media3 `Cue`s stay here), so `PlaybackState` and the screens never hold a Media3 type; `SubtitleSurface` draws it. Holds
 * words from a file, so it is never printed.
 */
class SubtitleFrame internal constructor(internal val cues: List<Cue>) {
    val isEmpty: Boolean get() = cues.isEmpty()

    override fun toString() = "SubtitleFrame(<redacted>)"

    companion object {
        val NONE = SubtitleFrame(emptyList())
    }
}
