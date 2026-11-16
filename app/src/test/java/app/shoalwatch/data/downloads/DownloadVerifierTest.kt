package app.shoalwatch.data.downloads

import app.shoalwatch.data.playback.AndroidDeviceCapabilities
import app.shoalwatch.data.playback.CodecSupport
import app.shoalwatch.data.playback.VideoCodec
import app.shoalwatch.data.playback.engine.PlayabilityProbe
import app.shoalwatch.data.playback.engine.ProbeResult
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A probe that answers what it is told, and counts its calls. */
internal class FakeProbe(var answer: ProbeResult? = ProbeResult(listOf(AVC), listOf(AAC), 10_000)) : PlayabilityProbe {
    var calls = 0
    override suspend fun probe(media: SavedMedia): ProbeResult? { calls++; return answer }

    companion object {
        const val AVC = "video/avc"
        const val HEVC = "video/hevc"
        const val AAC = "audio/mp4a-latm"
    }
}

class DownloadVerifierTest {
    @get:Rule val tmp = TemporaryFolder()
    private val probe = FakeProbe()
    private var device = AndroidDeviceCapabilities.MINIMAL // h264 + aac
    private val codecs = CodecSupport { device }

    private fun TestScope.verifier() = DownloadVerifier(probe, codecs, StandardTestDispatcher(testScheduler))

    private val folder by lazy { tmp.newFolder("server", "item") }

    private fun mp4(bytes: Int = 4_000): ByteArray = ByteArray(bytes) { (it * 31).toByte() }.also { b ->
        byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70).copyInto(b)
    }

    private fun original(bytes: ByteArray, name: String = "media.mp4"): SavedMedia {
        val entry = File(folder, name).apply { writeBytes(bytes) }
        return SavedMedia(SERVER, ITEM, folder, entry, hls = false, quality = DownloadQuality.ORIGINAL, mediaSourceId = ITEM)
    }

    private fun ts(bytes: Int = 1_000): ByteArray = ByteArray(bytes) { if (it % 188 == 0) 0x47 else (it * 7).toByte() }

    private fun converted(segments: List<ByteArray>, playlist: String? = null): SavedMedia {
        val hls = File(folder, "hls").apply { deleteRecursively(); mkdirs() }
        segments.forEachIndexed { i, b -> File(hls, "seg-%05d.ts".format(i)).writeBytes(b) }
        val entry = File(hls, "local.m3u8")
        entry.writeText(playlist ?: HlsPlaylists.writeLocal(segments.map { 2_000L }))
        return SavedMedia(SERVER, ITEM, folder, entry, hls = true, quality = DownloadQuality.MEDIUM, mediaSourceId = ITEM)
    }

    private fun sha(vararg parts: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256")
        parts.forEach(d::update)
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun aGoodOriginalPassesWithTheSha256OfItsBytes() = runTest {
        val bytes = mp4()
        val v = verifier().verify(original(bytes), bytes.size.toLong(), 10_000, null)
        assertEquals(Verdict.Good(sha(bytes)), v)
        assertEquals("stable", v, verifier().verify(original(bytes), bytes.size.toLong(), 10_000, null))
    }

    @Test fun aCopyWithNoMediaSourceIsNeverGood() = runTest {
        val bytes = mp4()
        val good = original(bytes)
        val unnamed = SavedMedia(SERVER, ITEM, good.folder, good.entry, hls = false, quality = DownloadQuality.ORIGINAL, mediaSourceId = "")
        assertEquals(Verdict.Bad("record"), verifier().verify(unnamed, bytes.size.toLong(), 10_000, null))
    }

    @Test fun check1AShortFileIsIncomplete() = runTest {
        val bytes = mp4()
        assertEquals(Verdict.Bad("incomplete"), verifier().verify(original(bytes.copyOf(3_999)), bytes.size.toLong(), 10_000, null))
        assertEquals("an unknown length is never complete", Verdict.Bad("incomplete"), verifier().verify(original(bytes), null, 10_000, null))
        assertEquals("the probe is not asked", 0, probe.calls)
    }

    @Test fun check2HtmlRenamedMp4IsTheWrongKindOfFile() = runTest {
        val html = "<!DOCTYPE html><html><body>Sign in</body></html>".toByteArray()
        assertEquals(Verdict.Bad("signature"), verifier().verify(original(html), html.size.toLong(), 10_000, null))
        assertEquals(0, probe.calls)
    }

    @Test fun check2SignaturesForEachContainer() = runTest {
        val mkv = ByteArray(100).also { byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()).copyInto(it) }
        assertTrue(verifier().verify(original(mkv, "media.mkv"), 100, 10_000, null) is Verdict.Good)
        assertEquals(Verdict.Bad("signature"), verifier().verify(original(mp4(100), "media.webm"), 100, 10_000, null))
        assertTrue(verifier().verify(original(ts(), "media.ts"), 1_000, 10_000, null) is Verdict.Good)
        val badTs = ts().also { it[376] = 0 }
        assertEquals(Verdict.Bad("signature"), verifier().verify(original(badTs, "media.ts"), 1_000, 10_000, null))
        assertTrue(verifier().verify(original(mp4(100), "media.mov"), 100, 10_000, null) is Verdict.Good)
    }

    @Test fun check3NoVideoTrackIsUnreadableOrNoVideo() = runTest {
        val bytes = mp4()
        probe.answer = ProbeResult(emptyList(), listOf(FakeProbe.AAC), 10_000)
        assertEquals(Verdict.Bad("no-video"), verifier().verify(original(bytes), bytes.size.toLong(), 10_000, null))
        probe.answer = null
        assertEquals(Verdict.Bad("unreadable"), verifier().verify(original(bytes), bytes.size.toLong(), 10_000, null))
        probe.answer = ProbeResult(listOf(FakeProbe.AVC), emptyList(), 10_000)
        assertEquals(Verdict.Bad("no-audio"), verifier().verify(original(bytes), bytes.size.toLong(), 10_000, null))
        assertTrue("no audio listed: none needed", verifier().verify(original(bytes), bytes.size.toLong(), 10_000, null, audioListed = false) is Verdict.Good)
    }

    @Test fun check4HevcWithNoCountedHevcDecoderIsNotDecodable() = runTest {
        val bytes = mp4()
        probe.answer = ProbeResult(listOf(FakeProbe.HEVC), listOf(FakeProbe.AAC), 10_000)
        assertEquals(Verdict.Bad("video-codec"), verifier().verify(original(bytes), bytes.size.toLong(), 10_000, null))
        device = device.copy(video = setOf(VideoCodec.H264, VideoCodec.HEVC))
        assertTrue(verifier().verify(original(bytes), bytes.size.toLong(), 10_000, null) is Verdict.Good)
        probe.answer = ProbeResult(listOf(FakeProbe.AVC, "video/mpeg2"), listOf(FakeProbe.AAC), 10_000)
        assertEquals("every video type must count", Verdict.Bad("video-codec"), verifier().verify(original(bytes), bytes.size.toLong(), 10_000, null))
        probe.answer = ProbeResult(listOf(FakeProbe.AVC), listOf("audio/true-hd"), 10_000)
        assertEquals(Verdict.Bad("audio-codec"), verifier().verify(original(bytes), bytes.size.toLong(), 10_000, null))
        probe.answer = ProbeResult(listOf(FakeProbe.AVC), listOf("audio/true-hd", FakeProbe.AAC), 10_000)
        assertTrue("one decodable audio is enough", verifier().verify(original(bytes), bytes.size.toLong(), 10_000, null) is Verdict.Good)
    }

    /**
     * Tasks 9-10 review I-4. Media3 1.9.2's Matroska and MP4 extractors give a Dolby Vision track the sample type
     * `video/dolby-vision` (checked in the 1.9.2 bytecode: `MatroskaExtractor.Track` and `BoxParser` set it when a dvcC/dvvC
     * configuration is present). The planner lets HEVC Dolby Vision direct play (HdrPolicy), so the check must accept it too.
     */
    @Test fun aDolbyVisionOriginalIsDecodableWhenThePlannerWouldDirectPlayIt() = runTest {
        val bytes = mp4()
        probe.answer = ProbeResult(listOf("video/dolby-vision"), listOf(FakeProbe.AAC), 10_000)
        assertEquals("no HEVC decoder: not direct played, not accepted", Verdict.Bad("video-codec"), verifier().verify(original(bytes), bytes.size.toLong(), 10_000, null))
        device = device.copy(video = setOf(VideoCodec.H264, VideoCodec.HEVC))
        assertTrue(verifier().verify(original(bytes), bytes.size.toLong(), 10_000, null) is Verdict.Good)
    }

    /** One list for both: the verifier accepts exactly the video types the device profile offers the server for direct play. */
    @Test fun theVerifierAndTheDeviceProfileShareOneListOfVideoTypes() = runTest {
        val all = listOf("video/avc", "video/hevc", "video/x-vnd.on2.vp9", "video/av01", "video/dolby-vision", "video/mpeg2", "video/x-vnd.on2.vp8")
        for (codecs in listOf(emptySet(), setOf(VideoCodec.HEVC), setOf(VideoCodec.VP9, VideoCodec.AV1), VideoCodec.entries.toSet())) {
            device = device.copy(video = codecs)
            val accepted = all.filter { mime ->
                val bytes = mp4()
                probe.answer = ProbeResult(listOf(mime), listOf(FakeProbe.AAC), 10_000)
                verifier().verify(original(bytes), bytes.size.toLong(), 10_000, null) is Verdict.Good
            }.toSet()
            assertEquals(app.shoalwatch.data.playback.DirectPlayVideo.media3Types(device), accepted)
            val profile = app.shoalwatch.data.playback.DeviceProfileBuilder.build(device, app.shoalwatch.data.playback.Quality.MBPS_8, false)
            assertEquals(app.shoalwatch.data.playback.DirectPlayVideo.codecs(device).joinToString(",") { it.jellyfin }, profile.directPlayProfiles.single().videoCodec)
        }
    }

    @Test fun h264CountsEvenWhenTheDeviceListsNoVideo() = runTest {
        device = device.copy(video = emptySet())
        val bytes = mp4()
        assertTrue(verifier().verify(original(bytes), bytes.size.toLong(), 10_000, null) is Verdict.Good)
    }

    @Test fun check5AHalfLengthDurationIsTheWrongLength() = runTest {
        val bytes = mp4()
        probe.answer = ProbeResult(listOf(FakeProbe.AVC), listOf(FakeProbe.AAC), 5_000)
        assertEquals(Verdict.Bad("length"), verifier().verify(original(bytes), bytes.size.toLong(), 10_000, null))
        probe.answer = ProbeResult(listOf(FakeProbe.AVC), listOf(FakeProbe.AAC), 10_500)
        assertTrue("within 5%", verifier().verify(original(bytes), bytes.size.toLong(), 10_000, null) is Verdict.Good)
        probe.answer = ProbeResult(listOf(FakeProbe.AVC), listOf(FakeProbe.AAC), 10_600)
        assertEquals(Verdict.Bad("length"), verifier().verify(original(bytes), bytes.size.toLong(), 10_000, null))
        assertTrue("runtime unknown", verifier().verify(original(bytes), bytes.size.toLong(), null, null) is Verdict.Good)
        probe.answer = ProbeResult(listOf(FakeProbe.AVC), listOf(FakeProbe.AAC), 10_900)
        assertTrue("a conversion has 10%", verifier().verify(converted(listOf(ts(), ts())), null, 10_000, null) is Verdict.Good)
    }

    @Test fun check1AMissingSegmentIsIncomplete() = runTest {
        val m = converted(List(3) { ts() })
        File(m.entry.parentFile, "seg-00001.ts").delete()
        assertEquals(Verdict.Bad("incomplete"), verifier().verify(m, null, 6_000, null))
        assertEquals(0, probe.calls)
    }

    @Test fun aConversionMustHaveExactlyTheSegmentsItsPlaylistNames() = runTest {
        assertEquals("an empty segment", Verdict.Bad("incomplete"), verifier().verify(converted(listOf(ts(), ByteArray(0))), null, null, null))
        val extra = converted(listOf(ts(), ts()))
        File(extra.entry.parentFile, "seg-00002.ts").writeBytes(ts())
        assertEquals("a segment not in the playlist", Verdict.Bad("incomplete"), verifier().verify(extra, null, null, null))
        File(extra.entry.parentFile, "seg-00002.ts").delete()
        File(extra.entry.parentFile, "seg-00001.ts.part").writeBytes(ts())
        assertEquals("a part left over", Verdict.Bad("incomplete"), verifier().verify(extra, null, null, null))
        File(extra.entry.parentFile, "seg-00001.ts.part").delete()
        assertEquals("a shape that does not match", Verdict.Bad("incomplete"), verifier().verify(extra, null, null, listOf(2_000L, 2_000L, 2_000L)))
        assertTrue(verifier().verify(extra, null, null, listOf(2_000L, 2_000L)) is Verdict.Good)
        extra.entry.writeText("#EXTM3U\n#EXTINF:2.000,\nseg-00001.ts\n#EXTINF:2.000,\nseg-00000.ts\n#EXT-X-ENDLIST\n")
        assertEquals("out of order", Verdict.Bad("incomplete"), verifier().verify(extra, null, null, null))
        extra.entry.writeText("#EXTM3U\n#EXTINF:2.000,\n../../other/media.mp4\n#EXT-X-ENDLIST\n")
        assertEquals("a name that is not a segment", Verdict.Bad("incomplete"), verifier().verify(extra, null, null, null))
    }

    @Test fun everySegmentMustBeTransportStream() = runTest {
        val bad = ts().also { it[188] = 0x48 }
        assertEquals(Verdict.Bad("signature"), verifier().verify(converted(listOf(ts(), bad)), null, null, null))
    }

    @Test fun aConversionsFingerprintIsItsSegmentsInOrder() = runTest {
        val a = ts(1_000)
        val b = ts(1_200).also { it[5] = 9 }
        assertEquals(Verdict.Good(sha(a, b)), verifier().verify(converted(listOf(a, b)), null, 10_000, listOf(2_000L, 2_000L)))
    }

    @Test fun recheckIsFalseAfterOneByteIsFlipped() = runTest {
        val bytes = mp4()
        val m = original(bytes)
        val good = verifier().verify(m, bytes.size.toLong(), 10_000, null) as Verdict.Good
        assertTrue(verifier().recheck(m, good.sha256))
        m.entry.writeBytes(bytes.copyOf().also { it[2_000] = (it[2_000] + 1).toByte() })
        assertFalse(verifier().recheck(m, good.sha256))
        m.entry.delete()
        assertFalse("gone", verifier().recheck(m, good.sha256))
        val c = converted(listOf(ts(), ts()))
        val hlsGood = verifier().verify(c, null, null, null) as Verdict.Good
        assertTrue(verifier().recheck(c, hlsGood.sha256))
        File(c.entry.parentFile, "seg-00001.ts").writeBytes(ts().also { it[500] = 1 })
        assertFalse(verifier().recheck(c, hlsGood.sha256))
    }

    @Test fun anEntryThatALinkTakesOutsideTheFolderIsRefused() = runTest {
        val away = tmp.newFolder("away")
        File(away, "seg-00000.ts").writeBytes(ts())
        File(away, "local.m3u8").writeText(HlsPlaylists.writeLocal(listOf(2_000L)))
        assumeTrue("this machine cannot make a link", DownloadPathsTest.link(File(folder, "hls"), away))
        val m = SavedMedia(SERVER, ITEM, folder, File(File(folder, "hls"), "local.m3u8"), hls = true, quality = DownloadQuality.LOW, mediaSourceId = ITEM)
        assertEquals(Verdict.Bad("incomplete"), verifier().verify(m, null, null, null))
    }

    private companion object {
        const val SERVER = "0123456789abcdef0123456789abcdef"
        const val ITEM = "fedcba9876543210fedcba9876543210"
    }
}
