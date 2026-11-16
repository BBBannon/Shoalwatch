package app.shoalwatch.data.playback

/**
 * The ONE list of video this phone may direct play, shared by the device profile the server plans with
 * ([DeviceProfileBuilder]) and the check every finished download passes (`DownloadVerifier`), so the two can never disagree.
 * h264 always; then each codec with a counted decoder. Dolby Vision is HEVC here ([HdrPolicy] lets HEVC DV direct play,
 * through its base layer when there is no DV decoder), and Media3 1.9.2's Matroska and MP4 extractors name a DV track
 * `video/dolby-vision` (with a dvcC/dvvC configuration), so that type comes with HEVC.
 */
internal object DirectPlayVideo {
    const val DOLBY_VISION = "video/dolby-vision"

    fun codecs(caps: DeviceCapabilities): List<VideoCodec> = VideoCodec.entries.filter { it == VideoCodec.H264 || it in caps.video }

    /** The sample MIME types Media3 gives these codecs' tracks. */
    fun media3Types(caps: DeviceCapabilities): Set<String> {
        val codecs = codecs(caps)
        return codecs.map { it.mime }.toSet() + if (VideoCodec.HEVC in codecs) setOf(DOLBY_VISION) else emptySet()
    }
}
