package app.shoalwatch.data.playback

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList

/** The Android adapter, on Robolectric's codec list and display. */
@RunWith(RobolectricTestRunner::class)
class AndroidDeviceCapabilitiesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun codec(name: String, mime: String, vararg profiles: Pair<Int, Int>, encoder: Boolean = false): MediaCodecInfo {
        val format = MediaFormat().apply { setString(MediaFormat.KEY_MIME, mime) }
        val levels = profiles.map { (p, l) -> CodecProfileLevel().apply { profile = p; level = l } }.toTypedArray()
        val caps = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder().setMediaFormat(format).setIsEncoder(encoder).setProfileLevels(levels)
            .apply { if (mime.startsWith("video/")) setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)) }
            .build()
        return MediaCodecInfoBuilder.newBuilder().setName(name).setIsEncoder(encoder).setCapabilities(caps).build()
    }

    private fun add(vararg codecs: MediaCodecInfo) = codecs.forEach(ShadowMediaCodecList::addCodec)

    private fun screen(vararg types: Int) {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        shadowOf(display).setDisplayHdrCapabilities(Display.DEFAULT_DISPLAY, 1000f, 500f, 0.05f, *types)
    }

    private fun read() = AndroidDeviceCapabilities(context).read()

    @Test fun hevcMain10Hdr10DecoderAndHdr10DisplayAreReported() {
        add(codec("c2.hevc", "video/hevc", CodecProfileLevel.HEVCProfileMain10HDR10 to CodecProfileLevel.HEVCMainTierLevel51))
        screen(Display.HdrCapabilities.HDR_TYPE_HDR10)
        val caps = read()
        assertEquals(setOf(HdrType.HDR10), caps.decoderHdr[VideoCodec.HEVC])
        assertEquals(setOf(HdrType.HDR10), caps.display)
        assertTrue(VideoCodec.HEVC in caps.video)
        assertTrue(caps.hevcMain10)
    }

    @Test fun aDolbyVisionDecoderWithDvheStIsProfile8() {
        add(codec("c2.dv", "video/dolby-vision", CodecProfileLevel.DolbyVisionProfileDvheSt to CodecProfileLevel.DolbyVisionLevelUhd30))
        assertEquals(setOf(8), read().dolbyVisionProfiles)
    }

    @Test fun everyDolbyVisionProfileConstantMaps() {
        add(codec(
            "c2.dv", "video/dolby-vision",
            CodecProfileLevel.DolbyVisionProfileDvheStn to 1, CodecProfileLevel.DolbyVisionProfileDvheDtb to 1,
            CodecProfileLevel.DolbyVisionProfileDvheSt to 1, CodecProfileLevel.DolbyVisionProfileDvavSe to 1,
        ))
        assertEquals(setOf(5, 7, 8, 9), read().dolbyVisionProfiles)
    }

    @Test fun encodersAreIgnored() {
        add(
            codec("enc.hevc", "video/hevc", CodecProfileLevel.HEVCProfileMain10HDR10 to 1, encoder = true),
            codec("enc.ac3", "audio/ac3", encoder = true),
        )
        val caps = read()
        assertFalse(VideoCodec.HEVC in caps.video)
        assertFalse("ac3" in caps.audio)
        assertTrue(caps.decoderHdr[VideoCodec.HEVC].isNullOrEmpty())
    }

    @Test fun noHdrCapabilitiesMeansAnEmptyDisplay() = assertEquals(emptySet<HdrType>(), read().display)

    @Test fun everyDisplayHdrTypeMaps() {
        screen(
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION, Display.HdrCapabilities.HDR_TYPE_HDR10,
            Display.HdrCapabilities.HDR_TYPE_HLG, Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS,
        )
        assertEquals(HdrType.entries.toSet(), read().display)
    }

    @Test fun h264Level51Is51() {
        add(codec("c2.avc", "video/avc", CodecProfileLevel.AVCProfileHigh to CodecProfileLevel.AVCLevel51, CodecProfileLevel.AVCProfileMain to CodecProfileLevel.AVCLevel4))
        val caps = read()
        assertEquals(51, caps.h264MaxLevel)
        assertTrue(VideoCodec.H264 in caps.video)
    }

    @Test fun audioDecoders() {
        add(codec("c2.aac", "audio/mp4a-latm"), codec("c2.opus", "audio/opus"), codec("c2.flac", "audio/flac"), codec("c2.eac3", "audio/eac3"))
        assertEquals(setOf("aac", "opus", "flac", "eac3"), read().audio)
    }

    @Test fun vp9AndAv1TenBitAndHdrProfiles() {
        add(
            codec("c2.vp9", "video/x-vnd.on2.vp9", CodecProfileLevel.VP9Profile2HDR to 1, CodecProfileLevel.VP9Profile2HDR10Plus to 1),
            codec("c2.av1", "video/av01", CodecProfileLevel.AV1ProfileMain10 to 1),
        )
        val caps = read()
        assertTrue(caps.vp9Profile2)
        assertTrue(caps.av1Main10)
        assertEquals(setOf(HdrType.HDR10, HdrType.HDR10_PLUS), caps.decoderHdr[VideoCodec.VP9])
        assertTrue(caps.decoderHdr[VideoCodec.AV1].isNullOrEmpty())
        assertEquals(setOf(VideoCodec.VP9, VideoCodec.AV1), caps.video)
    }

    @Test fun anEmptyPhoneIsTheMinimalProfile() {
        val caps = read()
        assertEquals(emptySet<VideoCodec>(), caps.video)
        assertEquals(41, caps.h264MaxLevel)
    }

    @Test fun aPlatformFailureYieldsTheMinimalProfileAndNeverThrows() {
        val broken = object : android.content.ContextWrapper(context) {
            override fun getSystemService(name: String): Any? = throw IllegalStateException("boom")
        }
        add(codec("c2.hevc", "video/hevc", CodecProfileLevel.HEVCProfileMain10HDR10 to 1))
        val caps = AndroidDeviceCapabilities(broken).read()
        assertEquals(AndroidDeviceCapabilities.MINIMAL, caps)
    }
}
