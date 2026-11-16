package app.shoalwatch.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which decoders count, and the limits they give (pure). */
class DecoderRulesTest {
    private fun dec(name: String, mime: String = "video/hevc", software: Boolean = false, alias: Boolean = false, w: Int? = null, h: Int? = null, fps: Int? = null, vararg levels: Int) =
        DecoderInfo(name, mime, software, alias, levels.map { 1 to it }, w, h, fps)

    @Test fun aSoftwareOnlyHevcDecoderDoesNotCount() =
        assertTrue(DecoderRules.counted(listOf(dec("c2.android.hevc.decoder", software = true)), "video/hevc").isEmpty())

    @Test fun aSecureDecoderIsIgnored() =
        assertTrue(DecoderRules.counted(listOf(dec("c2.qti.hevc.decoder.secure"), dec("OMX.X.HEVC.SECURE")), "video/hevc").isEmpty())

    @Test fun anAliasIsIgnored() =
        assertTrue(DecoderRules.counted(listOf(dec("OMX.qcom.video.decoder.hevc", alias = true)), "video/hevc").isEmpty())

    @Test fun aHardwareDecoderCountsAndOnlyForItsOwnType() {
        val hw = dec("c2.qti.hevc.decoder")
        assertEquals(listOf(hw), DecoderRules.counted(listOf(hw, dec("c2.android.hevc.decoder", software = true)), "video/hevc"))
        assertTrue(DecoderRules.counted(listOf(hw), "video/av01").isEmpty())
    }

    @Test fun h264FallsBackToASoftwareDecoderOnlyWhenThereIsNoHardwareOne() {
        val sw = dec("c2.android.avc.decoder", "video/avc", software = true)
        val hw = dec("c2.qti.avc.decoder", "video/avc")
        assertEquals(listOf(sw), DecoderRules.counted(listOf(sw), "video/avc"))
        assertEquals(listOf(hw), DecoderRules.counted(listOf(sw, hw), "video/avc"))
    }

    @Test fun a1080pDecoderGives1080pLimits() =
        assertEquals(VideoLimits(1920, 1088, 60), DecoderRules.limits(listOf(dec("hw", w = 1920, h = 1088, fps = 60)), { null }))

    @Test fun theLargestCountedDecoderGivesTheLimitsWithItsLevel() {
        val small = dec("hw1", w = 1920, h = 1080, fps = 120, levels = intArrayOf(5))
        val big = dec("hw2", w = 3840, h = 2160, fps = 60, levels = intArrayOf(7, 9))
        assertEquals(VideoLimits(3840, 2160, 60, level = 153), DecoderRules.limits(listOf(small, big), mapOf(7 to 150, 9 to 153)::get))
    }

    @Test fun missingSizeOrRateIsConservativeNotUnlimited() {
        assertEquals(VideoLimits(1920, 1080, 30), DecoderRules.limits(listOf(dec("hw")), { null }))
        assertEquals(VideoLimits(3840, 2160, 30), DecoderRules.limits(listOf(dec("hw", w = 3840, h = 2160)), { null }))
        assertEquals(VideoLimits(1920, 1080, 30), DecoderRules.limits(listOf(dec("hw", w = 3840)), { null }))
    }

    @Test fun noCountedDecoderHasNoLimits() = assertNull(DecoderRules.limits(emptyList(), { null }))
}
