package app.shoalwatch.data.playback

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import app.shoalwatch.core.AppLog

enum class HdrType { HDR10, HDR10_PLUS, HLG, DOLBY_VISION }

enum class VideoCodec(val jellyfin: String, val mime: String) {
    H264("h264", "video/avc"), HEVC("hevc", "video/hevc"), VP9("vp9", "video/x-vnd.on2.vp9"), AV1("av1", "video/av01")
}

/** What this phone can decode and show. Read once per player opening (a display can change, e.g. an external screen). */
data class DeviceCapabilities(
    val video: Set<VideoCodec>,                       // a decoder exists
    val audio: Set<String>,                           // Jellyfin names: aac, mp3, opus, flac, vorbis, ac3, eac3, dts, truehd
    val h264MaxLevel: Int,                            // Jellyfin level number, e.g. 41 for 4.1
    val hevcMain10: Boolean, val vp9Profile2: Boolean, val av1Main10: Boolean,
    val decoderHdr: Map<VideoCodec, Set<HdrType>>,    // e.g. HEVCProfileMain10HDR10 -> HDR10, ...HDR10Plus -> HDR10_PLUS
    val dolbyVisionProfiles: Set<Int>,                // from video/dolby-vision decoders: 5, 8 (dvhe.05, dvhe.08), 9 (dvav.09)
    val display: Set<HdrType>,                        // what the screen can show
)

fun interface CodecSupport { fun read(): DeviceCapabilities }

/**
 * [CodecSupport] from the platform: the decoders in `MediaCodecList(REGULAR_CODECS)` (encoders ignored) and the default
 * display's HDR types (`Display.getHdrCapabilities()`, and on API 34+ also the current mode's `supportedHdrTypes`).
 * Never throws: any platform failure gives [MINIMAL] (h264 + aac, SDR), logged by exception class only.
 */
class AndroidDeviceCapabilities(private val context: Context) : CodecSupport {

    override fun read(): DeviceCapabilities = try {
        readDecoders().copy(display = readDisplay())
    } catch (e: Exception) {
        AppLog.w(TAG, "device capabilities unreadable, using the minimal profile", e)
        MINIMAL
    }

    /** Decoder MIME type (lower case) to every profile it reports, encoders left out. */
    private fun decoderProfiles(): Map<String, List<CodecProfileLevel>> {
        val found = HashMap<String, MutableList<CodecProfileLevel>>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder) continue
            for (type in info.supportedTypes) {
                val levels = try {
                    info.getCapabilitiesForType(type).profileLevels.toList()
                } catch (_: IllegalArgumentException) {
                    emptyList()
                }
                found.getOrPut(type.lowercase()) { ArrayList() } += levels
            }
        }
        return found
    }

    private fun readDecoders(): DeviceCapabilities {
        val decoders = decoderProfiles()
        fun profiles(mime: String): Set<Int> = decoders[mime]?.mapTo(HashSet()) { it.profile }.orEmpty()
        val hevc = profiles(VideoCodec.HEVC.mime)
        val vp9 = profiles(VideoCodec.VP9.mime)
        val av1 = profiles(VideoCodec.AV1.mime)
        val audio = AUDIO_MIMES.filterValues { it in decoders }.keys
        val avcLevels = decoders[VideoCodec.H264.mime].orEmpty().mapNotNull { AVC_LEVELS[it.level] }
        return DeviceCapabilities(
            video = VideoCodec.entries.filterTo(LinkedHashSet()) { it.mime in decoders },
            audio = audio,
            h264MaxLevel = avcLevels.maxOrNull() ?: DEFAULT_H264_LEVEL,
            hevcMain10 = hevc.any { it in HEVC_10_BIT },
            vp9Profile2 = vp9.any { it in VP9_10_BIT },
            av1Main10 = av1.any { it in AV1_10_BIT },
            decoderHdr = mapOf(VideoCodec.HEVC to hdrOf(hevc, HEVC_HDR), VideoCodec.VP9 to hdrOf(vp9, VP9_HDR), VideoCodec.AV1 to hdrOf(av1, AV1_HDR))
                .filterValues { it.isNotEmpty() },
            dolbyVisionProfiles = profiles(DOLBY_VISION_MIME).mapNotNullTo(LinkedHashSet()) { DV_PROFILES[it] },
            display = emptySet(),
        )
    }

    private fun hdrOf(profiles: Set<Int>, table: Map<Int, HdrType>): Set<HdrType> = profiles.mapNotNullTo(LinkedHashSet()) { table[it] }

    @Suppress("DEPRECATION") // HdrCapabilities.getSupportedHdrTypes: deprecated on API 34 in favour of Mode, which is read as well
    private fun readDisplay(): Set<HdrType> {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptySet()
        val types = display.hdrCapabilities?.supportedHdrTypes?.toMutableSet() ?: mutableSetOf()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) types += display.mode.supportedHdrTypes.toList()
        return types.mapNotNullTo(LinkedHashSet()) { DISPLAY_HDR[it] }
    }

    companion object {
        private const val TAG = "DeviceCapabilities"
        private const val DOLBY_VISION_MIME = "video/dolby-vision"
        /** Level 4.1 when the AVC decoder reports no level: every phone this app supports plays it. */
        private const val DEFAULT_H264_LEVEL = 41

        /** What the app assumes when the platform cannot be read: h264 + aac, SDR, stereo. */
        val MINIMAL = DeviceCapabilities(
            video = setOf(VideoCodec.H264), audio = setOf("aac"), h264MaxLevel = DEFAULT_H264_LEVEL,
            hevcMain10 = false, vp9Profile2 = false, av1Main10 = false, decoderHdr = emptyMap(), dolbyVisionProfiles = emptySet(),
            display = emptySet(),
        )

        private val AUDIO_MIMES = linkedMapOf(
            "aac" to "audio/mp4a-latm", "mp3" to "audio/mpeg", "opus" to "audio/opus", "flac" to "audio/flac", "vorbis" to "audio/vorbis",
            "ac3" to "audio/ac3", "eac3" to "audio/eac3", "dts" to "audio/vnd.dts", "truehd" to "audio/true-hd",
        )

        private val AVC_LEVELS = mapOf(
            CodecProfileLevel.AVCLevel1 to 10, CodecProfileLevel.AVCLevel1b to 9, CodecProfileLevel.AVCLevel11 to 11,
            CodecProfileLevel.AVCLevel12 to 12, CodecProfileLevel.AVCLevel13 to 13, CodecProfileLevel.AVCLevel2 to 20,
            CodecProfileLevel.AVCLevel21 to 21, CodecProfileLevel.AVCLevel22 to 22, CodecProfileLevel.AVCLevel3 to 30,
            CodecProfileLevel.AVCLevel31 to 31, CodecProfileLevel.AVCLevel32 to 32, CodecProfileLevel.AVCLevel4 to 40,
            CodecProfileLevel.AVCLevel41 to 41, CodecProfileLevel.AVCLevel42 to 42, CodecProfileLevel.AVCLevel5 to 50,
            CodecProfileLevel.AVCLevel51 to 51, CodecProfileLevel.AVCLevel52 to 52, CodecProfileLevel.AVCLevel6 to 60,
            CodecProfileLevel.AVCLevel61 to 61, CodecProfileLevel.AVCLevel62 to 62,
        )

        private val HEVC_10_BIT = setOf(CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain10HDR10, CodecProfileLevel.HEVCProfileMain10HDR10Plus)
        private val VP9_10_BIT = setOf(
            CodecProfileLevel.VP9Profile2, CodecProfileLevel.VP9Profile2HDR, CodecProfileLevel.VP9Profile2HDR10Plus,
            CodecProfileLevel.VP9Profile3, CodecProfileLevel.VP9Profile3HDR, CodecProfileLevel.VP9Profile3HDR10Plus,
        )
        private val AV1_10_BIT = setOf(CodecProfileLevel.AV1ProfileMain10, CodecProfileLevel.AV1ProfileMain10HDR10, CodecProfileLevel.AV1ProfileMain10HDR10Plus)

        private val HEVC_HDR = mapOf(CodecProfileLevel.HEVCProfileMain10HDR10 to HdrType.HDR10, CodecProfileLevel.HEVCProfileMain10HDR10Plus to HdrType.HDR10_PLUS)
        private val VP9_HDR = mapOf(
            CodecProfileLevel.VP9Profile2HDR to HdrType.HDR10, CodecProfileLevel.VP9Profile3HDR to HdrType.HDR10,
            CodecProfileLevel.VP9Profile2HDR10Plus to HdrType.HDR10_PLUS, CodecProfileLevel.VP9Profile3HDR10Plus to HdrType.HDR10_PLUS,
        )
        private val AV1_HDR = mapOf(CodecProfileLevel.AV1ProfileMain10HDR10 to HdrType.HDR10, CodecProfileLevel.AV1ProfileMain10HDR10Plus to HdrType.HDR10_PLUS)

        /** `DolbyVisionProfile*` constants to the profile number (dvhe.04, .05, .07, .08; dvav.09). */
        private val DV_PROFILES = mapOf(
            CodecProfileLevel.DolbyVisionProfileDvheDtr to 4, CodecProfileLevel.DolbyVisionProfileDvheStn to 5,
            CodecProfileLevel.DolbyVisionProfileDvheDtb to 7, CodecProfileLevel.DolbyVisionProfileDvheSt to 8,
            CodecProfileLevel.DolbyVisionProfileDvavSe to 9,
        )

        private val DISPLAY_HDR = mapOf(
            Display.HdrCapabilities.HDR_TYPE_HDR10 to HdrType.HDR10, Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS to HdrType.HDR10_PLUS,
            Display.HdrCapabilities.HDR_TYPE_HLG to HdrType.HLG, Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION to HdrType.DOLBY_VISION,
        )
    }
}
