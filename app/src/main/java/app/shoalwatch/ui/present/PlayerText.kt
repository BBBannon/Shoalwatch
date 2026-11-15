package app.shoalwatch.ui.present

import androidx.annotation.StringRes
import app.shoalwatch.R
import app.shoalwatch.data.playback.PlayMethod
import app.shoalwatch.data.playback.Quality
import app.shoalwatch.data.playback.SegmentKind
import app.shoalwatch.data.playback.SleepChoice
import app.shoalwatch.data.playback.TranscodeReason
import app.shoalwatch.data.playback.VideoRange
import java.math.BigDecimal

/** The player's words, pure apart from [Strings]: how the video is played, HDR, times (digits 0-9 in every locale), speeds. */
object PlayerText {
    /**
     * DIRECT_PLAY / DIRECT_STREAM in a sentence; TRANSCODE with its first reason (a fall back after this phone failed to play the
     * original always says so, whatever the server's reason). Null before anything is known.
     */
    fun streamInfo(strings: Strings, method: PlayMethod?, reasons: Set<TranscodeReason>, fellBack: Boolean): String? = when (method) {
        null -> null
        PlayMethod.DIRECT_PLAY -> strings.get(R.string.stream_direct_play)
        PlayMethod.DIRECT_STREAM -> strings.get(R.string.stream_direct_stream)
        PlayMethod.TRANSCODE -> {
            // The first reason with words: an unexplained one (OTHER) does not hide a real one after it.
            val reason = if (fellBack) R.string.stream_reason_fallback else reasons.firstNotNullOfOrNull(::reasonText)
            if (reason == null) strings.get(R.string.stream_transcode) else strings.get(R.string.stream_transcode_because, strings.get(reason))
        }
    }

    @StringRes private fun reasonText(r: TranscodeReason): Int? = when (r) {
        TranscodeReason.VIDEO_CODEC -> R.string.stream_reason_video_codec
        TranscodeReason.AUDIO_CODEC -> R.string.stream_reason_audio_codec
        TranscodeReason.CONTAINER -> R.string.stream_reason_container
        TranscodeReason.BITRATE, TranscodeReason.RESOLUTION -> R.string.stream_reason_quality
        TranscodeReason.VIDEO_RANGE -> R.string.stream_reason_video_range
        TranscodeReason.SUBTITLE -> R.string.stream_reason_subtitle
        TranscodeReason.AUDIO_CHANNELS -> R.string.stream_reason_audio_channels
        TranscodeReason.DIRECT_PLAY_ERROR -> R.string.stream_reason_fallback
        TranscodeReason.OTHER -> null
    }

    /**
     * For HDR video only: converted by the server for this screen (a VIDEO_RANGE transcode), Dolby Vision with an HDR10 base
     * layer on a screen without Dolby Vision (shown as HDR10), or shown in HDR. SDR, Dolby Vision with an SDR base (played as
     * SDR) and unknown say nothing.
     */
    fun hdrLine(strings: Strings, range: VideoRange?, method: PlayMethod?, reasons: Set<TranscodeReason>, dolbyVisionScreen: Boolean): String? {
        if (range == null || range == VideoRange.SDR || range == VideoRange.UNKNOWN || range == VideoRange.DOVI_WITH_SDR) return null
        return when {
            method == PlayMethod.TRANSCODE && TranscodeReason.VIDEO_RANGE in reasons -> strings.get(R.string.stream_hdr_converted)
            !dolbyVisionScreen && (range == VideoRange.DOVI_WITH_HDR10 || range == VideoRange.DOVI_WITH_HDR10_PLUS) ->
                strings.get(R.string.stream_dolby_vision_as_hdr10)
            else -> strings.get(R.string.stream_hdr_shown)
        }
    }

    /** "0:09", "12:30", "1:02:03": always the digits 0-9, whatever the locale. */
    fun clock(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        fun two(n: Long) = if (n < 10) "0$n" else n.toString()
        return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
    }

    /** What the seek bar says to a screen reader: "12:30 of 45:00". */
    fun seekState(strings: Strings, positionMs: Long, durationMs: Long): String = strings.get(R.string.player_seek_state, clock(positionMs), clock(durationMs))

    /** "1", "0.75", "1.5": no trailing zeros, a full stop, digits 0-9. */
    fun speedNumber(speed: Float): String = BigDecimal(speed.toString()).stripTrailingZeros().toPlainString()

    fun speed(strings: Strings, speed: Float): String = strings.get(R.string.player_speed_value, speedNumber(speed))

    /** "0 seconds", "+0.5 seconds", "−1.2 seconds" (later is positive). */
    fun delay(strings: Strings, ms: Int): String {
        val n = BigDecimal(ms).movePointLeft(3).abs().stripTrailingZeros().toPlainString()
        val sign = when { ms > 0 -> "+"; ms < 0 -> "−"; else -> "" }
        return strings.get(R.string.subtitle_delay_value, sign + n)
    }

    fun quality(strings: Strings, q: Quality): String = strings.get(
        when (q) {
            Quality.MAXIMUM -> R.string.quality_maximum
            Quality.MBPS_20 -> R.string.quality_20
            Quality.MBPS_8 -> R.string.quality_8
            Quality.MBPS_4 -> R.string.quality_4
            Quality.MBPS_1_5 -> R.string.quality_1_5
        },
    )

    fun sleep(strings: Strings, choice: SleepChoice): String = when (choice) {
        SleepChoice.OFF -> strings.get(R.string.sleep_off)
        SleepChoice.END_OF_ITEM -> strings.get(R.string.sleep_end)
        else -> strings.get(R.string.sleep_minutes, checkNotNull(choice.minutes).toInt())
    }

    @StringRes fun skipLabel(kind: SegmentKind): Int = when (kind) {
        SegmentKind.INTRO -> R.string.skip_intro
        SegmentKind.OUTRO -> R.string.skip_credits
        SegmentKind.RECAP -> R.string.skip_recap
        SegmentKind.PREVIEW -> R.string.skip_preview
        SegmentKind.COMMERCIAL -> R.string.skip_advert
    }
}
