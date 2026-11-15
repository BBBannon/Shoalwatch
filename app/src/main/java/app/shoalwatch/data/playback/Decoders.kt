package app.shoalwatch.data.playback

/**
 * One platform decoder as `AndroidDeviceCapabilities` read it: plain data, so which decoders count is decided (and tested)
 * without Android. [profileLevels] are the platform's `CodecProfileLevel` (profile, level) constants.
 */
data class DecoderInfo(
    val name: String,
    val mime: String,
    val softwareOnly: Boolean,
    val alias: Boolean,
    val profileLevels: List<Pair<Int, Int>> = emptyList(),
)

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
}
