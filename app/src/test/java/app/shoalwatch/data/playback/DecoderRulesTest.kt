package app.shoalwatch.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which decoders count (pure). */
class DecoderRulesTest {
    private fun dec(name: String, mime: String = "video/hevc", software: Boolean = false, alias: Boolean = false) =
        DecoderInfo(name, mime, software, alias)

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
}
