package app.shoalwatch.data.playback

import app.shoalwatch.data.playback.api.CodecProfileDto
import app.shoalwatch.data.playback.api.DeviceProfileDto
import app.shoalwatch.data.playback.api.PlaybackJson
import app.shoalwatch.data.playback.api.ProfileConditionDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The DeviceProfile sent with PlaybackInfo: every name from the 12.4.0 schemas, every rule from the plan. */
class DeviceProfileBuilderTest {
    private val sdrPhone = DeviceCapabilities(
        video = setOf(VideoCodec.H264, VideoCodec.HEVC), audio = setOf("aac", "mp3", "opus", "flac"), h264MaxLevel = 42,
        hevcMain10 = true, vp9Profile2 = false, av1Main10 = false, decoderHdr = emptyMap(), dolbyVisionProfiles = emptySet(),
        display = emptySet(),
    )
    private val nothing = sdrPhone.copy(video = emptySet(), audio = emptySet(), hevcMain10 = false)

    private fun build(caps: DeviceCapabilities = sdrPhone, quality: Quality = Quality.MBPS_8, force: Boolean = false, burnIn: String? = null) =
        DeviceProfileBuilder.build(caps, quality, force, burnIn)

    private fun codec(p: DeviceProfileDto, name: String?): CodecProfileDto? = p.codecProfiles.singleOrNull { it.codec == name }
    private fun condition(p: CodecProfileDto?, property: String): ProfileConditionDto? = p?.conditions?.singleOrNull { it.property == property }

    @Test fun theJsonUsesExactlyTheReferencePropertyNames() {
        val json = PlaybackJson.encodeToJsonElement(DeviceProfileDto.serializer(), build(force = true, burnIn = "ass")).jsonObject
        assertEquals(setOf("Name", "MaxStreamingBitrate", "MaxStaticBitrate", "DirectPlayProfiles", "TranscodingProfiles", "ContainerProfiles", "CodecProfiles", "SubtitleProfiles"), json.keys)
        assertEquals(setOf("Container", "AudioCodec", "VideoCodec", "Type"), first(json, "DirectPlayProfiles").keys)
        val transcoding = first(json, "TranscodingProfiles")
        assertEquals(
            setOf("Container", "Type", "VideoCodec", "AudioCodec", "Protocol", "Context", "MaxAudioChannels", "CopyTimestamps", "EnableSubtitlesInManifest", "MinSegments", "SegmentLength", "Conditions"),
            transcoding.keys,
        )
        assertEquals(setOf("Condition", "Property", "Value", "IsRequired"), (transcoding["Conditions"] as JsonArray).first().jsonObject.keys)
        assertEquals(setOf("Type", "Codec", "Conditions", "ApplyConditions"), first(json, "CodecProfiles").keys)
        val widthOnly = json["CodecProfiles"]!!.jsonArray.map { it.jsonObject }.single { "Codec" !in it }
        assertEquals(setOf("Type", "Conditions", "ApplyConditions"), widthOnly.keys)
        assertEquals(setOf("Format", "Method"), first(json, "SubtitleProfiles").keys)
        assertEquals(JsonArray(emptyList()), json["ContainerProfiles"])
    }

    private fun first(json: JsonObject, key: String) = json[key]!!.jsonArray.first().jsonObject

    @Test fun enumValuesAreTheReferenceWireNames() {
        val p = build(force = true)
        assertEquals("Video", p.directPlayProfiles.single().type)
        val t = p.transcodingProfiles.single()
        assertEquals(listOf("Video", "hls", "Streaming"), listOf(t.type, t.protocol, t.context))
        assertTrue(p.codecProfiles.all { it.type == "Video" })
        val conditions = p.codecProfiles.flatMap { it.conditions } + t.conditions
        assertTrue(conditions.all { it.condition in setOf("LessThanEqual", "EqualsAny") && !it.isRequired })
        assertTrue(conditions.all { it.property in setOf("VideoBitDepth", "VideoLevel", "VideoRangeType", "Width", "Height", "VideoFramerate") })
        assertTrue(p.subtitleProfiles.all { it.method in setOf("External", "Embed", "Encode") })
    }

    @Test fun nameAndBothBitratesComeFromTheQuality() {
        val p = build(quality = Quality.MBPS_4)
        assertEquals("Shoalwatch", p.name)
        assertEquals(4_000_000, p.maxStreamingBitrate)
        assertEquals(4_000_000, p.maxStaticBitrate)
        assertEquals(200_000_000, build(quality = Quality.MAXIMUM).maxStreamingBitrate)
    }

    @Test fun h264AndAacAreAlwaysPresentEvenWithNoDecodersReported() {
        val d = build(nothing).directPlayProfiles.single()
        assertEquals("h264", d.videoCodec)
        assertEquals("aac", d.audioCodec)
        assertEquals(DeviceProfileBuilder.DIRECT_PLAY_CONTAINERS, d.container)
    }

    @Test fun aCodecWithoutADecoderIsAbsentFromDirectPlay() {
        val d = build().directPlayProfiles.single()
        assertEquals("h264,hevc", d.videoCodec)
        assertEquals("aac,mp3,opus,flac", d.audioCodec)
        assertNull(codec(build(), "vp9"))
        assertNull(codec(build(), "av1"))
    }

    @Test fun theVideoRangeConditionForAnSdrPhoneIsSdrAndDvWithSdr() {
        assertEquals("SDR|DOVIWithSDR", condition(codec(build(), "hevc"), "VideoRangeType")!!.value)
        assertEquals("SDR", condition(codec(build(), "h264"), "VideoRangeType")!!.value)
        assertEquals("EqualsAny", condition(codec(build(), "hevc"), "VideoRangeType")!!.condition)
    }

    @Test fun anHdrPhoneListsWhatHdrPolicyAllows() {
        val hdr = sdrPhone.copy(decoderHdr = mapOf(VideoCodec.HEVC to setOf(HdrType.HDR10)), display = setOf(HdrType.HDR10))
        val expected = HdrPolicy.directPlayRanges(VideoCodec.HEVC, hdr)
        val wire = VideoRange.entries.filter { it in expected }.joinToString("|") { it.wire }
        assertEquals(wire, condition(codec(build(hdr), "hevc"), "VideoRangeType")!!.value)
    }

    @Test fun bitDepthAndLevelConditions() {
        assertEquals("8", condition(codec(build(), "h264"), "VideoBitDepth")!!.value)
        assertEquals("42", condition(codec(build(), "h264"), "VideoLevel")!!.value)
        assertEquals("10", condition(codec(build(), "hevc"), "VideoBitDepth")!!.value)
        assertEquals("8", condition(codec(build(sdrPhone.copy(hevcMain10 = false)), "hevc"), "VideoBitDepth")!!.value)
        val all = sdrPhone.copy(video = VideoCodec.entries.toSet(), vp9Profile2 = true, av1Main10 = false)
        assertEquals("10", condition(codec(build(all), "vp9"), "VideoBitDepth")!!.value)
        assertEquals("8", condition(codec(build(all), "av1"), "VideoBitDepth")!!.value)
    }

    private fun limits(p: DeviceProfileDto, codec: String) =
        listOf("Width", "Height", "VideoFramerate", "VideoLevel").associateWith { condition(codec(p, codec), it)?.value }

    @Test fun a1080pHevcDecoderLimitsHevcTo1080p() {
        val p = build(sdrPhone.copy(videoLimits = mapOf(VideoCodec.HEVC to VideoLimits(1920, 1080, 60))))
        assertEquals(mapOf("Width" to "1920", "Height" to "1080", "VideoFramerate" to "60", "VideoLevel" to null), limits(p, "hevc"))
        assertTrue(codec(p, "hevc")!!.conditions.filter { it.property in setOf("Width", "Height", "VideoFramerate") }.all { it.condition == "LessThanEqual" })
    }

    @Test fun a4k60DecoderGivesTheFourKLimitsAndItsLevel() {
        val p = build(sdrPhone.copy(videoLimits = mapOf(VideoCodec.HEVC to VideoLimits(3840, 2160, 60, level = 153))))
        assertEquals(mapOf("Width" to "3840", "Height" to "2160", "VideoFramerate" to "60", "VideoLevel" to "153"), limits(p, "hevc"))
    }

    @Test fun missingCapabilityDataIsAConservative1080p30NotNoLimit() {
        val all = sdrPhone.copy(video = VideoCodec.entries.toSet())
        for (c in listOf("hevc", "vp9", "av1")) {
            assertEquals(c, mapOf("Width" to "1920", "Height" to "1080", "VideoFramerate" to "30", "VideoLevel" to null), limits(build(all), c))
        }
    }

    @Test fun h264KeepsItsLevelConditionAndGetsNoSizeCondition() {
        val p = build(sdrPhone.copy(videoLimits = mapOf(VideoCodec.H264 to VideoLimits(1920, 1080, 30))))
        assertEquals(mapOf("Width" to null, "Height" to null, "VideoFramerate" to null, "VideoLevel" to "42"), limits(p, "h264"))
    }

    @Test fun mbps4PutsAWidthAndHeightCeilingOnTheTranscodingProfile() {
        val t = build(quality = Quality.MBPS_4).transcodingProfiles.single()
        assertEquals(listOf("Width" to "1280", "Height" to "720"), t.conditions.map { it.property to it.value })
        assertEquals(listOf("ts", "h264", "aac", "2"), listOf(t.container, t.videoCodec, t.audioCodec, t.maxAudioChannels))
        assertFalse(t.copyTimestamps)
        assertFalse(t.enableSubtitlesInManifest)
        assertEquals(1, t.minSegments)
        assertEquals(6, t.segmentLength)
        assertTrue(build(quality = Quality.MAXIMUM).transcodingProfiles.single().conditions.isEmpty())
        assertEquals(listOf("854", "480"), build(quality = Quality.MBPS_1_5).transcodingProfiles.single().conditions.map { it.value })
    }

    @Test fun forceTranscodeAddsTheWidthCeilingAsACodecCondition() {
        assertNull(codec(build(quality = Quality.MBPS_4), null))
        val ceiling = codec(build(quality = Quality.MBPS_4, force = true), null)!!
        assertEquals(listOf("Width" to "1280", "Height" to "720"), ceiling.conditions.map { it.property to it.value })
        assertNull("no ceiling at maximum quality", codec(build(quality = Quality.MAXIMUM, force = true), null))
    }

    @Test fun burnInAssListsAssAsEncodeOnlyAndLeavesSrtExternal() {
        val subs = build(burnIn = "ass").subtitleProfiles.groupBy({ it.format }, { it.method })
        assertEquals(listOf("Encode"), subs["ass"])
        assertEquals(listOf("External"), subs["srt"])
        assertEquals(listOf("External"), subs["ssa"])
    }

    @Test fun textIsExternalAndPgsIsEmbedAndEncode() {
        val subs = build().subtitleProfiles.groupBy({ it.format }, { it.method })
        DeviceProfileBuilder.TEXT_SUBTITLES.forEach { assertEquals(it, listOf("External"), subs[it]) }
        DeviceProfileBuilder.PICTURE_SUBTITLES.forEach { assertEquals(it, listOf("Embed", "Encode"), subs[it]) }
        assertEquals(listOf("Embed", "Encode"), subs["pgs"])
        assertEquals(DeviceProfileBuilder.TEXT_SUBTITLES.size + DeviceProfileBuilder.PICTURE_SUBTITLES.size, subs.size)
    }

    @Test fun burningInAPictureFormatOrAnUnlistedOneIsEncodeOnly() {
        assertEquals(listOf("Encode"), build(burnIn = "PGSSUB").subtitleProfiles.filter { it.format == "pgssub" }.map { it.method })
        assertEquals(listOf("Encode"), build(burnIn = "mov_text").subtitleProfiles.filter { it.format == "mov_text" }.map { it.method })
    }
}
