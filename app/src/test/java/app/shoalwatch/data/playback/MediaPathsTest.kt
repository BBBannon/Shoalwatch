package app.shoalwatch.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The URL rules for media: built paths and server-supplied paths (pure). */
class MediaPathsTest {
    private val id = "0123456789abcdef0123456789abcdef"

    @Test fun transcodingUrlKeepsItsQueryButLosesCredentials() {
        val src = MediaPaths.checkServerPath("/videos/$id/master.m3u8?MediaSourceId=$id&api_key=SECRET&API%5Fkey=X&ApiKey=Y&PlaySessionId=abc", hls = true)!!
        assertEquals("/videos/$id/master.m3u8", src.path)
        assertEquals(listOf("MediaSourceId" to id, "PlaySessionId" to "abc"), src.query)
        assertTrue(src.hls)
    }

    @Test fun everyCredentialNameIsRemovedInAnyCaseAndSpelling() {
        val names = listOf("api_key", "APIKEY", "apikey", "X-Emby-Token", "x-emby-token", "X-MediaBrowser-Token", "X-Emby-Authorization",
            "%61pi_key", "Api%4Bey", "X%2DEmby%2DToken", "%20api_key")
        for (name in names) {
            val src = MediaPaths.checkServerPath("/videos/$id/master.m3u8?$name=SECRET&Keep=1", hls = true)
            assertEquals(name, listOf("Keep" to "1"), src!!.query)
        }
    }

    @Test fun refusesAnythingThatIsNotARelativeVideosPath() {
        listOf("https://evil.example/videos/x", "//evil.example/videos/x", "/Items/x", "/videos/../System/Info", "/videos/x/%2e%2e/y",
               "videos/x", "/videos/x\\y", "/videos/x#frag", "", null, "/videos/" + "a".repeat(2100))
            .forEach { assertNull(it, MediaPaths.checkServerPath(it, hls = true)) }
    }

    @Test fun refusesEncodedAndDisguisedEscapes() {
        listOf(
            "/videos/x/%2E%2E/System", "/videos/x/%252e%252e/y", "/videos%2fx", "/videos/x%2F..%2FSystem", "/videos/x%5c..", "/videos/./x",
            "/videos/x/.", "/videos", "/videos/", "/videos//x", "/\\evil.example/videos/x", "\\\\evil.example/videos/x", "/@evil.example/videos/x",
            "/videos/x;jsessionid=1", "/videos/x y", "/videos/x\u0000", "/videos/x\r\nHost: evil", "/videos/ｘ", "/videos/x/%zz",
            "http:/videos/x", "/videos@evil.example/x", "/videos/x?a=%0d%0a", "/videos/x?a=%zz", " /videos/x", "/videos/x/",
        ).forEach { assertNull(it, MediaPaths.checkServerPath(it, hls = false)) }
    }

    @Test fun theVideosSegmentMatchesInAnyCaseAndEncodedSafeCharactersAreDecoded() {
        assertEquals("/Videos/$id/stream.mkv", MediaPaths.checkServerPath("/Videos/$id/stream.mkv", hls = false)!!.path)
        assertEquals("/vIdEoS/$id/main.m3u8", MediaPaths.checkServerPath("/vIdEoS/$id/main.m3u8", hls = true)!!.path)
        assertEquals("/videos/$id/main.m3u8", MediaPaths.checkServerPath("/videos/$id/main%2Em3u8", hls = true)!!.path)
        assertFalse(MediaPaths.checkServerPath("/videos/$id/stream", hls = false)!!.hls)
    }

    @Test fun queryValuesAreDecodedOnceAndAPairWithoutAValueIsKept() {
        val src = MediaPaths.checkServerPath("/videos/$id/master.m3u8?TranscodeReasons=ContainerNotSupported%2CVideoCodecNotSupported&flag&a=b%26c", hls = true)!!
        assertEquals(listOf("TranscodeReasons" to "ContainerNotSupported,VideoCodecNotSupported", "flag" to "", "a" to "b&c"), src.query)
    }

    @Test fun exactlyTheMaximumLengthIsAccepted() {
        val path = "/videos/" + "a".repeat(2048 - "/videos/".length)
        assertNotNull(MediaPaths.checkServerPath(path, hls = false))
        assertNull(MediaPaths.checkServerPath(path + "a", hls = false))
    }

    @Test fun directStreamIsStaticWithSourceAndTag() {
        val src = MediaPaths.directStream(id, "abc123", "etag9")
        assertEquals("/Videos/$id/stream", src.path)
        assertEquals(listOf("static" to "true", "mediaSourceId" to "abc123", "tag" to "etag9"), src.query)
        assertFalse(src.hls)
    }

    @Test fun directStreamWithoutATagSendsNone() =
        assertEquals(listOf("static" to "true", "mediaSourceId" to "abc123"), MediaPaths.directStream(id, "abc123", null).query)

    @Test fun directStreamRefusesIdsThatAreNotValidated() {
        for ((item, source, tag) in listOf(Triple("../x", "abc", null), Triple(id, "a/b", null), Triple(id, "abc", "t&x=1"), Triple(id.uppercase(), "abc", null))) {
            assertThrows { MediaPaths.directStream(item, source, tag) }
        }
    }

    @Test fun subtitleFilePathIsTheReferenceRouteForTheFourTextFormats() {
        assertEquals("/Videos/$id/abc123/Subtitles/2/Stream.srt", MediaPaths.subtitleFile(id, "abc123", 2, "srt"))
        for (f in listOf("vtt", "ass", "ssa")) assertEquals("/Videos/$id/abc123/Subtitles/3/Stream.$f", MediaPaths.subtitleFile(id, "abc123", 3, f))
        for (bad in listOf("html", "srt/../x", "SRT", "")) assertThrows { MediaPaths.subtitleFile(id, "abc123", 2, bad) }
        assertThrows { MediaPaths.subtitleFile(id, "abc123", -1, "srt") }
        assertThrows { MediaPaths.subtitleFile(id, "a.b", 2, "srt") }
    }

    @Test fun streamSourceNeverPrintsItsPath() =
        assertEquals("StreamSource(<redacted>)", MediaPaths.directStream(id, "abc123", "etag9").toString())

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
