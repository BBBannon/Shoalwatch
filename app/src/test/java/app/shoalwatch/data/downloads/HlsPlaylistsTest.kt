package app.shoalwatch.data.downloads

import app.shoalwatch.data.downloads.HlsPlaylists.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bounded HLS parser: Jellyfin's own shapes parse; every refusal is its own case. */
class HlsPlaylistsTest {
    private val item = "0123456789abcdef0123456789abcdef"
    private val masterPath = "/videos/$item/master.m3u8"
    private val mediaPath = "/videos/$item/main.m3u8"

    private fun fixture(name: String) = javaClass.getResource("/jellyfin/downloads/$name")!!.readText()

    private fun media(text: String, path: String = mediaPath): Parsed.Media = HlsPlaylists.parse(text, path) as Parsed.Media

    private fun refused(text: String, path: String = mediaPath) {
        val parsed = HlsPlaylists.parse(text, path)
        assertTrue("expected a refusal, got $parsed", parsed is Parsed.Refused)
        // A fixed word, never anything from the playlist.
        assertTrue((parsed as Parsed.Refused).reason.matches(Regex("[a-z]{1,16}")))
    }

    private fun playlist(vararg lines: String) = (listOf("#EXTM3U", "#EXT-X-PLAYLIST-TYPE:VOD", "#EXT-X-TARGETDURATION:6") + lines + "#EXT-X-ENDLIST").joinToString("\n")

    @Test fun jellyfinsMasterPlaylistGivesItsVariantWithTheKeyStripped() {
        val parsed = HlsPlaylists.parse(fixture("master.m3u8"), masterPath) as Parsed.Master
        assertEquals("/videos/$item/main.m3u8", parsed.variant.path)
        assertTrue(parsed.variant.hls)
        assertTrue(parsed.variant.query.none { it.first.equals("api_key", ignoreCase = true) })
        assertEquals("5e55105e55105e55105e55105e551051", parsed.variant.query.toMap()["PlaySessionId"])
    }

    @Test fun jellyfinsMediaPlaylistGivesEverySegmentWithItsDurationAndTheKeyStripped() {
        val parsed = media(fixture("media.m3u8"))
        assertEquals(6, parsed.targetSeconds)
        assertEquals(listOf(6000L, 6000L, 6000L, 6000L, 3500L), parsed.segments.map { it.durationMs })
        assertEquals((0..4).map { "/videos/$item/hls1/main/$it.ts" }, parsed.segments.map { it.source.path })
        parsed.segments.forEach { s ->
            assertFalse(s.source.hls)
            assertTrue(s.source.query.none { it.first.equals("api_key", ignoreCase = true) })
            assertEquals("0123456789abcdef0123456789abcdef", s.source.query.toMap()["MediaSourceId"])
        }
    }

    @Test fun aBasePathIsNotPartOfThePlaylistPath() {
        // Paths are relative to the verified address (which holds any base path), so the parser only ever sees /videos/...
        refused(playlist("#EXTINF:6.0,", "0.ts"), path = "/jellyfin/videos/$item/main.m3u8")
    }

    @Test fun anEncryptionKeyIsRefusedButMethodNoneIsAllowed() {
        refused(fixture("hostile-key.m3u8"))
        assertEquals(1, media(playlist("#EXT-X-KEY:METHOD=NONE", "#EXTINF:6.0,", "0.ts")).segments.size)
    }

    @Test fun anAbsoluteUriIsRefused() {
        refused(fixture("hostile-absolute.m3u8"))
        refused(playlist("#EXTINF:6.0,", "//evil.example/videos/x/0.ts"))
        refused(playlist("#EXTINF:6.0,", "/videos/$item/0.ts"))
        refused(playlist("#EXTINF:6.0,", "file:///data/0.ts"))
    }

    @Test fun aPathThatEscapesVideosIsRefused() {
        refused(fixture("hostile-escape.m3u8"))
        refused(playlist("#EXTINF:6.0,", "%2e%2e/%2e%2e/System/Info.ts"))
        refused(playlist("#EXTINF:6.0,", "a/../../../Users/x.ts"))
    }

    @Test fun aSegmentThatIsNotTsIsRefused() {
        refused(playlist("#EXTINF:6.0,", "hls1/main/0.mp4"))
        refused(playlist("#EXTINF:6.0,", "hls1/main/0.ts.m3u8"))
    }

    @Test fun tooManySegmentsOrTooMuchTextIsRefused() {
        val huge = (0..20_000).flatMap { listOf("#EXTINF:1.0,", "s$it.ts") }
        refused(playlist(*huge.toTypedArray()))
        val ok = (0 until 20_000).flatMap { listOf("#EXTINF:1.0,", "s$it.ts") }
        assertEquals(20_000, media(playlist(*ok.toTypedArray())).segments.size)
        refused(playlist("#EXTINF:6.0,", "0.ts") + "\n#" + "x".repeat(2 * 1024 * 1024))
    }

    @Test fun aLivePlaylistIsRefusedAndEitherVodMarkerIsEnough() {
        refused(fixture("hostile-live.m3u8"))
        assertEquals(1, media("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\n0.ts\n#EXT-X-ENDLIST").segments.size)
        assertEquals(1, media("#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\n0.ts").segments.size)
        refused("#EXTM3U\n#EXT-X-PLAYLIST-TYPE:EVENT\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\n0.ts")
    }

    @Test fun byteRangesMapsAndDiscontinuitiesAreRefused() {
        refused(fixture("hostile-byterange.m3u8"))
        refused(playlist("#EXT-X-MAP:URI=\"init.mp4\"", "#EXTINF:6.0,", "0.ts"))
        refused(playlist("#EXTINF:6.0,", "0.ts", "#EXT-X-DISCONTINUITY", "#EXTINF:6.0,", "1.ts"))
    }

    @Test fun aDurationOutsideZeroToSixtySecondsIsRefused() {
        refused(playlist("#EXTINF:0,", "0.ts"))
        refused(playlist("#EXTINF:60.001,", "0.ts"))
        refused(playlist("#EXTINF:-1,", "0.ts"))
        refused(playlist("#EXTINF:NaN,", "0.ts"))
        refused(playlist("#EXTINF:abc,", "0.ts"))
        assertEquals(60_000L, media(playlist("#EXTINF:60,", "0.ts")).segments.single().durationMs)
    }

    @Test fun theHeaderAControlCharacterOrAUriWithoutDurationIsRefused() {
        refused("#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\n0.ts\n#EXT-X-ENDLIST")
        refused(playlist("#EXTINF:6.0,", "0\u0000.ts"))
        refused(playlist("#EXTINF:6.0,\u001b[31m", "0.ts"))
        refused(playlist("0.ts"))
        refused(playlist())
    }

    @Test fun windowsLineEndsAndABomAreRead() {
        assertEquals(1, media("﻿#EXTM3U\r\n#EXT-X-PLAYLIST-TYPE:VOD\r\n#EXT-X-TARGETDURATION:6\r\n#EXTINF:6.0,\r\n0.ts\r\n").segments.size)
    }

    @Test fun aLongLineIsCutTo4096Characters() {
        // The cut line is no longer a .ts path, so the playlist is refused rather than a truncated URI being fetched.
        refused(playlist("#EXTINF:6.0,", "a".repeat(5_000) + ".ts"))
    }

    @Test fun aMasterWhoseFirstVariantIsUnusableIsRefused() {
        refused("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://evil.example/videos/main.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=1\nmain.m3u8", masterPath)
        refused("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n", masterPath)
        refused("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n../../System/main.m3u8", masterPath)
        refused("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nmain.ts", masterPath)
    }

    @Test fun aPlaylistPathThatIsNotUnderVideosIsRefused() {
        refused(playlist("#EXTINF:6.0,", "0.ts"), path = "/Items/$item/main.m3u8")
        refused(playlist("#EXTINF:6.0,", "0.ts"), path = "main.m3u8")
    }

    @Test fun theLocalPlaylistParsesBackWithTheSameDurationsAndNamesSegmentsByIndexOnly() {
        val durations = listOf(6000L, 6000L, 5999L, 1L, 3500L)
        val text = HlsPlaylists.writeLocal(durations)
        assertTrue(text.startsWith("#EXTM3U\n"))
        assertTrue(text.trimEnd().endsWith("#EXT-X-ENDLIST"))
        val back = media(text, path = "/videos/local/local.m3u8")
        assertEquals(durations, back.segments.map { it.durationMs })
        assertEquals((0..4).map { "/videos/local/seg-%05d.ts".format(it) }, back.segments.map { it.source.path })
        assertEquals(6, back.targetSeconds)
    }
}
