package app.shoalwatch.data.playback

/**
 * One platform decoder as `AndroidDeviceCapabilities` read it: plain data, so which decoders count is decided (and tested)
 * without Android. [profileLevels] are the platform's `CodecProfileLevel` (profile, level) constants. [maxWidth], [maxHeight] and
 * [maxFrameRate] (the rate at that largest size) are null when the platform did not say.
 */
data class DecoderInfo(
    val name: String,
    val mime: String,
    val softwareOnly: Boolean,
    val alias: Boolean,
    val profileLevels: List<Pair<Int, Int>> = emptyList(),
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
    val maxFrameRate: Int? = null,
)

/** The largest picture a codec's counted decoder plays, and its level in Jellyfin's numbering (null: none sent). */
data class VideoLimits(val maxWidth: Int, val maxHeight: Int, val maxFrameRate: Int, val level: Int? = null) {
    companion object {
        /** What is assumed when the platform does not say: 1080p at 30 frames a second, which any decoder this app meets plays. */
        val CONSERVATIVE = VideoLimits(1920, 1080, 30)
    }
}

/** Which decoders count (pure; see "Device profile" in docs/ARCHITECTURE.md). */
object DecoderRules {
    private const val AVC = "video/avc"

    /**
     * The decoders that count for [mime]: not an alias of another (it would be counted twice), not a `.secure` one (only for
     * protected content, which this app never plays), and hardware. AOSP's software decoders for HEVC, VP9 and AV1 exist on
     * practically every phone and cannot play high-resolution or 10-bit video smoothly, so they never make a codec "supported".
     * The one exception is h264, which every Android device must decode: its software decoders count, but only when no hardware
     * one exists (so a software decoder cannot raise the level a hardware one reports).
     */
    fun counted(decoders: List<DecoderInfo>, mime: String): List<DecoderInfo> {
        val usable = decoders.filter { it.mime.equals(mime, ignoreCase = true) && !it.alias && !it.name.lowercase().endsWith(".secure") }
        val hardware = usable.filterNot { it.softwareOnly }
        return if (hardware.isEmpty() && mime.equals(AVC, ignoreCase = true)) usable else hardware
    }

    /**
     * The limits of the counted decoder with the largest picture. A size or frame rate the platform did not give is the
     * [VideoLimits.CONSERVATIVE] one, never "no limit". [levelOf] maps a platform level constant to Jellyfin's number (null: unknown).
     */
    fun limits(counted: List<DecoderInfo>, levelOf: (Int) -> Int?): VideoLimits? {
        if (counted.isEmpty()) return null
        val best = counted.maxBy { (it.maxWidth ?: 0).toLong() * (it.maxHeight ?: 0) }
        val c = VideoLimits.CONSERVATIVE
        val sized = best.maxWidth != null && best.maxHeight != null
        return VideoLimits(
            maxWidth = if (sized) best.maxWidth!! else c.maxWidth,
            maxHeight = if (sized) best.maxHeight!! else c.maxHeight,
            maxFrameRate = best.maxFrameRate?.takeIf { it > 0 } ?: c.maxFrameRate,
            level = best.profileLevels.mapNotNull { levelOf(it.second) }.maxOrNull(),
        )
    }
}
