package app.shoalwatch.data.playback

/** Which HDR formats may be direct played, per codec (pure). See "Device profile" in docs/ARCHITECTURE.md. */
object HdrPolicy {
    /**
     * The VideoRangeType values a codec may direct play. Rule: HDR is direct played only when the decoder can decode that HDR
     * format AND the screen can show it; otherwise only SDR is listed and the server converts (tone-maps) the rest.
     * Dolby Vision: a profile the DV decoder handles AND a DV screen plays as DV; profile 8.1/8.4 also plays on an HDR10/HLG
     * path through its base layer when DV is not available but HDR10/HLG is; 8.2 (SDR base) plays as SDR; profile 7 (DOVIWithEL,
     * DOVIWithELHDR10Plus) and DOVIInvalid never direct play (black screens, research complaint 12).
     *
     * In detail:
     * - h264 is SDR only (no phone decodes HDR h264).
     * - HDR10: the codec's decoder reports HDR10 and the screen shows HDR10.
     * - HDR10+: the HDR10 path above (an HDR10+ stream is HDR10 with dynamic metadata, which is then simply not used), or the
     *   decoder and the screen both report HDR10+.
     * - HLG: a 10-bit decoder for the codec (HEVC Main10, VP9 Profile 2, AV1 Main10; Android has no separate HLG profile) and an
     *   HLG screen.
     * - Dolby Vision is HEVC only here (profiles 5 and 8 are HEVC; 9 is h264 and 10 is AV1, which are not offered). DOVI
     *   (profile 5, no compatible base layer) needs a DV screen and profile 5 in the DV decoder. DOVIWithHDR10, DOVIWithHLG and
     *   DOVIWithHDR10Plus (8.1, 8.4) play as DV with a DV screen and profile 8, otherwise through their base layer on the HDR10,
     *   HLG or HDR10+ path. DOVIWithSDR (8.2) always plays, as SDR.
     */
    fun directPlayRanges(codec: VideoCodec, caps: DeviceCapabilities): Set<VideoRange> {
        if (codec == VideoCodec.H264) return setOf(VideoRange.SDR)
        val decoder = caps.decoderHdr[codec].orEmpty()
        val screen = caps.display
        val tenBit = when (codec) {
            VideoCodec.HEVC -> caps.hevcMain10
            VideoCodec.VP9 -> caps.vp9Profile2
            VideoCodec.AV1 -> caps.av1Main10
            VideoCodec.H264 -> false
        }
        val hdr10 = HdrType.HDR10 in decoder && HdrType.HDR10 in screen
        val hdr10Plus = hdr10 || (HdrType.HDR10_PLUS in decoder && HdrType.HDR10_PLUS in screen)
        val hlg = tenBit && HdrType.HLG in screen
        val ranges = linkedSetOf(VideoRange.SDR)
        if (hdr10) ranges += VideoRange.HDR10
        if (hdr10Plus) ranges += VideoRange.HDR10_PLUS
        if (hlg) ranges += VideoRange.HLG
        if (codec == VideoCodec.HEVC) {
            val dvScreen = HdrType.DOLBY_VISION in screen
            val dv5 = dvScreen && 5 in caps.dolbyVisionProfiles
            val dv8 = dvScreen && 8 in caps.dolbyVisionProfiles
            if (dv5) ranges += VideoRange.DOVI
            if (dv8 || hdr10) ranges += VideoRange.DOVI_WITH_HDR10
            if (dv8 || hlg) ranges += VideoRange.DOVI_WITH_HLG
            if (dv8 || hdr10Plus) ranges += VideoRange.DOVI_WITH_HDR10_PLUS
            ranges += VideoRange.DOVI_WITH_SDR
        }
        return ranges
    }
}
