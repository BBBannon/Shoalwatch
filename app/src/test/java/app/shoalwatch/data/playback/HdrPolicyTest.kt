package app.shoalwatch.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which `VideoRangeType`s a codec may direct play: HDR only when the decoder can decode it AND the screen can show it. */
class HdrPolicyTest {
    private fun caps(display: Set<HdrType>, hevcHdr: Set<HdrType> = emptySet(), dv: Set<Int> = emptySet(), hevcMain10: Boolean = true) =
        DeviceCapabilities(setOf(VideoCodec.H264, VideoCodec.HEVC), setOf("aac"), 51, hevcMain10 = hevcMain10, vp9Profile2 = false,
            av1Main10 = false, decoderHdr = mapOf(VideoCodec.HEVC to hevcHdr), dolbyVisionProfiles = dv, display = display)

    @Test fun sdrScreenNeverDirectPlaysHdr() =
        assertEquals(setOf(VideoRange.SDR, VideoRange.DOVI_WITH_SDR), HdrPolicy.directPlayRanges(VideoCodec.HEVC, caps(display = emptySet(), hevcHdr = setOf(HdrType.HDR10))))

    @Test fun hdr10ScreenAndDecoderPlayHdr10AndDvProfile81BaseLayer() {
        val r = HdrPolicy.directPlayRanges(VideoCodec.HEVC, caps(setOf(HdrType.HDR10), setOf(HdrType.HDR10)))
        assertTrue(VideoRange.HDR10 in r && VideoRange.DOVI_WITH_HDR10 in r)
        assertFalse(VideoRange.DOVI in r)            // profile 5 has no HDR10 base layer
    }

    @Test fun profile7AndInvalidNeverDirectPlay() {
        val all = caps(HdrType.entries.toSet(), HdrType.entries.toSet(), setOf(5, 7, 8))
        val r = HdrPolicy.directPlayRanges(VideoCodec.HEVC, all)
        assertFalse(VideoRange.DOVI_WITH_EL in r || VideoRange.DOVI_WITH_EL_HDR10_PLUS in r || VideoRange.DOVI_INVALID in r)
        assertTrue(VideoRange.DOVI in r)
    }

    @Test fun h264IsAlwaysSdrOnly() = assertEquals(setOf(VideoRange.SDR), HdrPolicy.directPlayRanges(VideoCodec.H264, caps(HdrType.entries.toSet())))

    @Test fun hlgNeedsTenBitDecoderAndHlgScreen() {
        assertTrue(VideoRange.HLG in HdrPolicy.directPlayRanges(VideoCodec.HEVC, caps(setOf(HdrType.HLG))))
        assertFalse("no 10-bit decoder", VideoRange.HLG in HdrPolicy.directPlayRanges(VideoCodec.HEVC, caps(setOf(HdrType.HLG), hevcMain10 = false)))
        assertFalse("screen without HLG", VideoRange.HLG in HdrPolicy.directPlayRanges(VideoCodec.HEVC, caps(setOf(HdrType.HDR10), setOf(HdrType.HDR10))))
    }

    @Test fun hdr10NeedsBothTheDecoderAndTheScreen() {
        assertFalse("screen only", VideoRange.HDR10 in HdrPolicy.directPlayRanges(VideoCodec.HEVC, caps(setOf(HdrType.HDR10))))
        assertFalse("decoder only", VideoRange.HDR10 in HdrPolicy.directPlayRanges(VideoCodec.HEVC, caps(emptySet(), setOf(HdrType.HDR10))))
    }

    @Test fun hdr10PlusPlaysThroughItsHdr10BaseAndNeverWithoutAnHdr10Path() {
        val viaBase = HdrPolicy.directPlayRanges(VideoCodec.HEVC, caps(setOf(HdrType.HDR10), setOf(HdrType.HDR10)))
        assertTrue(VideoRange.HDR10_PLUS in viaBase && VideoRange.DOVI_WITH_HDR10_PLUS in viaBase)
        assertFalse(VideoRange.HDR10_PLUS in HdrPolicy.directPlayRanges(VideoCodec.HEVC, caps(setOf(HdrType.HDR10_PLUS))))
        val native = HdrPolicy.directPlayRanges(VideoCodec.HEVC, caps(setOf(HdrType.HDR10_PLUS), setOf(HdrType.HDR10_PLUS)))
        assertTrue(VideoRange.HDR10_PLUS in native)
        assertFalse("an HDR10+ screen is not assumed to show plain HDR10", VideoRange.HDR10 in native)
    }

    @Test fun dolbyVisionNeedsADvScreenAndADecoderForThatProfile() {
        val dvOnlyScreen = caps(setOf(HdrType.DOLBY_VISION), dv = setOf(5, 8))
        val r = HdrPolicy.directPlayRanges(VideoCodec.HEVC, dvOnlyScreen)
        assertTrue(VideoRange.DOVI in r && VideoRange.DOVI_WITH_HDR10 in r && VideoRange.DOVI_WITH_HLG in r && VideoRange.DOVI_WITH_HDR10_PLUS in r)
        assertFalse("a DV screen alone does not show HDR10", VideoRange.HDR10 in r)
        val noDecoder = HdrPolicy.directPlayRanges(VideoCodec.HEVC, caps(setOf(HdrType.DOLBY_VISION)))
        assertEquals(setOf(VideoRange.SDR, VideoRange.DOVI_WITH_SDR), noDecoder)
        val onlyProfile8 = HdrPolicy.directPlayRanges(VideoCodec.HEVC, caps(setOf(HdrType.DOLBY_VISION), dv = setOf(8)))
        assertFalse("profile 5 needs its own decoder support", VideoRange.DOVI in onlyProfile8)
        assertTrue(VideoRange.DOVI_WITH_HDR10 in onlyProfile8)
    }

    @Test fun profile84PlaysThroughItsHlgBaseLayer() {
        val r = HdrPolicy.directPlayRanges(VideoCodec.HEVC, caps(setOf(HdrType.HLG)))
        assertTrue(VideoRange.DOVI_WITH_HLG in r)
        assertFalse(VideoRange.DOVI_WITH_HDR10 in r)
    }

    @Test fun vp9AndAv1UseTheirOwnTenBitFlagAndDecoderHdrAndNeverDolbyVision() {
        val everything = HdrType.entries.toSet()
        val c = DeviceCapabilities(
            VideoCodec.entries.toSet(), setOf("aac"), 51, hevcMain10 = true, vp9Profile2 = true, av1Main10 = false,
            decoderHdr = mapOf(VideoCodec.VP9 to setOf(HdrType.HDR10), VideoCodec.AV1 to everything), dolbyVisionProfiles = setOf(5, 8),
            display = everything,
        )
        assertEquals(setOf(VideoRange.SDR, VideoRange.HDR10, VideoRange.HDR10_PLUS, VideoRange.HLG), HdrPolicy.directPlayRanges(VideoCodec.VP9, c))
        assertEquals("AV1 decoder says HDR10 and HDR10+, but HLG needs Main10", setOf(VideoRange.SDR, VideoRange.HDR10, VideoRange.HDR10_PLUS), HdrPolicy.directPlayRanges(VideoCodec.AV1, c))
    }
}
