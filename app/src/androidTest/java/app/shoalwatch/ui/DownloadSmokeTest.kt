package app.shoalwatch.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.shoalwatch.R
import app.shoalwatch.ShoalwatchApp
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.downloads.ArtworkSlot
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.Server
import app.shoalwatch.net.ServerAddress
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Downloads end to end on a device (Milestone 4 Task 16): Continue Watching -> the film's detail page -> Download -> Original ->
 * Download, against an in-process `MockWebServer` serving the item, `/Items?ids=` (CanDownload true, Size), PlaybackInfo (direct
 * play), `/MediaSegments/{id}` (one intro), one SRT subtitle, one WebP poster and `/Items/{id}/Download` from the bundled clip with
 * Range support. The FIRST media response is cut after 64 KiB, so the download must resume with `Range: bytes=65536-` and
 * `If-Range`. WorkManager runs for real. Then the server is shut down and the film is found in the Downloads tab, played from the
 * phone, and its offline position kept; finally removing the account deletes its folder. Seeding as `PlayerSmokeTest`, with the
 * cleanup also run when seeding fails part way.
 */
@RunWith(AndroidJUnit4::class)
class DownloadSmokeTest {
    private val serverId = "6b4f2d1e0a8c5f3b9d7e1c2a4f6b8d0e"
    private val userId = "fedcba9876543210fedcba9876543212"
    private val clipId = "d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2"
    private val cut = 64 * 1024
    private val mock = MockWebServer()
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    /**
     * Once set, the server is "gone": every request is answered 503 (so the app sees it as unreachable) and recorded here, so
     * the offline-play step can prove nothing asked it for the film (Tasks 13-16 review M-1: a stopped server records nothing).
     */
    @Volatile private var serverGone = false
    private val afterGone: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var clip: ByteArray
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = ShoalwatchApp.graph(context)
    @Volatile private var downloadsServed = 0
    private var mobileDataBefore = false

    private val item = """{"Name":"Test Clip","ServerId":"$serverId","Id":"$clipId","Type":"Movie","IsFolder":false,"MediaType":"Video",
        "RunTimeTicks":100000000,"ImageTags":{"Primary":"tag1"},"BackdropImageTags":[],"LocationType":"FileSystem","CanDownload":true,
        "UserData":{"PlaybackPositionTicks":0,"PlayCount":0,"IsFavorite":false,"Played":false,"Key":"$clipId"}}"""

    private fun source(size: Int) = """{"Protocol":"File","Id":"$clipId","Type":"Default","Container":"mp4","Name":"Test Clip",
        "RunTimeTicks":100000000,"Size":$size,"Bitrate":1000000,"IsRemote":false,"SupportsTranscoding":true,"SupportsDirectPlay":true,
        "SupportsDirectStream":true,"HasSegments":true,"DefaultAudioStreamIndex":1,"MediaStreams":[
        {"Index":0,"Type":"Video","Codec":"h264","Profile":"Constrained Baseline","Level":30,"Width":320,"Height":180,"BitDepth":8,"VideoRange":"SDR",
         "VideoRangeType":"SDR","IsDefault":true,"IsExternal":false},
        {"Index":1,"Type":"Audio","Codec":"aac","Language":"eng","Channels":1,"SampleRate":48000,"IsDefault":true,"IsExternal":false},
        {"Index":2,"Type":"Subtitle","Codec":"subrip","Language":"eng","IsDefault":true,"IsForced":true,"IsExternal":true,"DeliveryMethod":"External"}]}"""

    private fun downloadItem(size: Int) = item.removeSuffix("}") + ""","MediaSources":[${source(size)}]}"""

    private val srt = "1\n00:00:00,500 --> 00:00:09,000\nHello from the phone\n"

    private fun poster(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 90, 140)) }
        @Suppress("DEPRECATION") val format = Bitmap.CompressFormat.WEBP
        return ByteArrayOutputStream().also { bitmap.compress(format, 80, it) }.toByteArray()
    }

    private val seed = object : ExternalResource() {
        override fun before() {
            try {
                seedEverything()
            } catch (t: Throwable) {
                cleanUp() // `after()` is not called when `before()` throws
                throw t
            }
        }

        private fun seedEverything() {
            clip = InstrumentationRegistry.getInstrumentation().context.assets.open("media/clip.mp4").use { it.readBytes() }
            check(clip.size > cut) { "the clip must be longer than the cut" }
            val picture = poster()
            fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
            val empty = """{"Items":[],"TotalRecordCount":0,"StartIndex":0}"""
            mock.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (serverGone) {
                        afterGone += request.path.orEmpty().substringBefore('?')
                        return MockResponse().setResponseCode(503)
                    }
                    requests += request
                    val path = request.path.orEmpty().substringBefore('?')
                    return when {
                        path == "/System/Info/Public" -> json("""{"Id":"$serverId","ServerName":"Test Den","Version":"10.10.3"}""")
                        path == "/UserViews" || path == "/Shows/NextUp" -> json(empty)
                        path == "/UserItems/Resume" -> json("""{"Items":[$item],"TotalRecordCount":1,"StartIndex":0}""")
                        path == "/Items/Latest" -> json("[]")
                        path == "/Items" && request.requestUrl?.queryParameter("ids") != null ->
                            json("""{"Items":[${downloadItem(clip.size)}],"TotalRecordCount":1,"StartIndex":0}""")
                        path == "/Items/$clipId/PlaybackInfo" -> json("""{"MediaSources":[${source(clip.size)}],"PlaySessionId":"5e55105e55105e55105e55105e551053"}""")
                        path == "/Items/$clipId" -> json(item)
                        path == "/Items/$clipId/Images/Primary" -> MockResponse().setResponseCode(200).setHeader("Content-Type", "image/webp").setBody(Buffer().write(picture))
                        path == "/MediaSegments/$clipId" -> json("""{"Items":[{"Type":"Intro","StartTicks":10000000,"EndTicks":30000000}],"TotalRecordCount":1,"StartIndex":0}""")
                        path.equals("/Videos/$clipId/$clipId/Subtitles/2/Stream.srt", ignoreCase = true) ->
                            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/x-subrip").setBody(srt)
                        path.startsWith("/Sessions/Playing") -> MockResponse().setResponseCode(204)
                        path == "/Items/$clipId/Download" -> download(request.getHeader("Range"))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            mock.start()
            mobileDataBefore = graph.downloadPrefs.settings.value.mobileData
            graph.downloadPrefs.setMobileData(true) // the emulator's network may count as metered: never wait for Wi-Fi here
            runBlocking {
                graph.tokensWarmed.join()
                graph.serverStore.upsertServer(
                    Server(
                        id = serverId, name = "Test Den", version = "10.10.3", homeAddress = ServerAddress.parse("http://127.0.0.1:${mock.port}"),
                        awayAddress = null, allowCleartext = true, pinnedSpkiSha256 = null, clientCertAlias = null, customHeaders = emptyMap(),
                    ),
                )
                graph.serverStore.upsertAccount(Account(serverId, userId, "ben", "test-token"), makeActive = true)
            }
        }

        override fun after() = cleanUp()

        private fun cleanUp() {
            runCatching { runBlocking { graph.serverStore.removeServer(serverId) } }
            runCatching { graph.downloadPrefs.setMobileData(mobileDataBefore) }
            runCatching { mock.shutdown() }
        }
    }

    /**
     * `/Items/{id}/Download`: the first answer claims the whole length but stops after [cut] bytes (the connection then closes); every
     * later one honours `bytes=N-` with a 206, as a file server does. An ETag lets the client send `If-Range`.
     */
    private fun download(range: String?): MockResponse {
        val first = downloadsServed++ == 0
        val start = range?.let { Regex("""bytes=(\d+)-""").find(it)?.groupValues?.get(1)?.toInt() } ?: 0
        val base = MockResponse().setHeader("Content-Type", "video/mp4").setHeader("Accept-Ranges", "bytes").setHeader("ETag", "\"clip-v1\"")
        if (first) {
            return base.setResponseCode(200).setBody(Buffer().write(clip, 0, cut)).setHeader("Content-Length", clip.size)
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        }
        if (start == 0) return base.setResponseCode(200).setBody(Buffer().write(clip))
        return base.setResponseCode(206).setHeader("Content-Range", "bytes $start-${clip.size - 1}/${clip.size}").setBody(Buffer().write(clip, start, clip.size - start))
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun describedStarting(prefix: String) = SemanticsMatcher("description starts with $prefix") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(prefix) } == true
    }

    private fun waitFor(timeoutMs: Long = 45_000, condition: () -> Boolean) = compose.waitUntil(timeoutMs, condition)

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun string(id: Int) = compose.activity.getString(id)

    private fun state(): DownloadState? = runBlocking { graph.downloadStore.get(DownloadKey(serverId, clipId))?.state }

    private val folder get() = File(File(File(context.filesDir, "downloads"), serverId), clipId)

    private fun elapsedSeconds(): Int? = compose.onAllNodes(SemanticsMatcher("an elapsed time") { n ->
        n.config.getOrNull(SemanticsProperties.Text)?.any { Regex("""0:0\d""").matches(it.text) } == true
    }).fetchSemanticsNodes().firstNotNullOfOrNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text?.takeLast(1)?.toIntOrNull() }

    private fun back() = compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    @Test fun downloadResumesVerifiesThenBrowsesAndPlaysOffline() {
        // Home -> the Continue Watching card -> the detail page -> Download -> Original (already selected) -> Continue -> Download.
        waitFor { compose.onAllNodes(describedStarting("Test Clip")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(describedStarting("Test Clip")).onFirst().performClick()
        val download = string(R.string.dl_download)
        waitFor { shown(download) }
        compose.onAllNodes(hasText(download)).onFirst().performScrollTo().performClick()
        waitFor { shown(string(R.string.action_continue)) }
        compose.onAllNodes(hasText(string(R.string.action_continue))).onFirst().performClick()
        waitFor { shown("Download 1 film?") }
        compose.onAllNodes(hasText(download)).onLast().performClick()
        // Android 13+ with notifications off asks once first; the download goes ahead either way.
        runCatching { waitFor(timeoutMs = 3_000) { shown(string(R.string.dl_not_now)) } }
        if (shown(string(R.string.dl_not_now))) compose.onAllNodes(hasText(string(R.string.dl_not_now))).onFirst().performClick()

        // COMPLETE within 60 s, WorkManager for real. Coming to the front kicks the work again (cutting a retry's backoff short).
        var lastKick = System.currentTimeMillis()
        waitFor(timeoutMs = 60_000) {
            if (System.currentTimeMillis() - lastKick > 5_000) { lastKick = System.currentTimeMillis(); graph.downloadsInFront() }
            state() == DownloadState.COMPLETE
        }

        // The file is the clip, byte for byte; it resumed from the cut with If-Range; the stored SHA-256 matches.
        val media = File(folder, "media.mp4")
        assertArrayEquals("the saved file is the clip", clip, media.readBytes())
        val fetches = synchronized(requests) { requests.filter { it.path.orEmpty().startsWith("/Items/$clipId/Download") }.toList() }
        assertTrue("at least two media requests (${fetches.size})", fetches.size >= 2)
        assertEquals("the second asks for the rest", "bytes=$cut-", fetches[1].getHeader("Range"))
        assertTrue("with If-Range", !fetches[1].getHeader("If-Range").isNullOrEmpty())
        synchronized(requests) {
            assertTrue("every request carried Authorization", requests.filter { it.path != "/System/Info/Public" }.all { !it.getHeader("Authorization").isNullOrEmpty() })
            assertTrue("no URL carried api_key", requests.none { r -> r.requestUrl?.queryParameterNames.orEmpty().any { it.equals("api_key", true) || it.equals("apikey", true) } })
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(clip).joinToString("") { "%02x".format(it) }
        assertEquals(sha, runBlocking { graph.downloadStore.get(DownloadKey(serverId, clipId)) }!!.sha256)
        assertTrue("the subtitle was saved", File(folder, "sub-2.srt").readText().contains("Hello from the phone"))
        assertTrue("the poster is pinned", runBlocking { graph.pinnedArtwork.has(serverId, clipId, ArtworkSlot.PRIMARY, "tag1") })

        // The server goes away. Downloads lists the film from the phone, its poster from the pinned copy.
        serverGone = true
        back() // the detail page -> Home
        waitFor { shown(string(R.string.nav_downloads)) }
        compose.onAllNodes(hasText(string(R.string.nav_downloads))).onFirst().performClick()
        waitFor { shown(string(R.string.dl_section_films)) && shown("Test Clip") }

        // Play it from the phone: at least 0:02 elapsed within 10 s.
        compose.onAllNodes(hasText("Test Clip")).onFirst().performClick()
        val play = string(R.string.detail_play)
        waitFor { shown(play) }
        compose.onAllNodes(hasText(play)).onFirst().performScrollTo().performClick()
        var lastTap = 0L
        waitFor(timeoutMs = 10_000) {
            val seconds = elapsedSeconds()
            if (seconds == null && System.currentTimeMillis() - lastTap > 1_500) {
                lastTap = System.currentTimeMillis()
                runCatching { compose.onRoot().performTouchInput { click(center) } }
            }
            (seconds ?: 0) >= 2
        }

        // Back out: the offline play is kept for this profile; nothing reached the (stopped) server.
        back()
        waitFor(timeoutMs = 10_000) { (runBlocking { graph.database.offlinePlayDao().get(serverId, userId, clipId) }?.positionMs ?: 0) > 0 }
        val asked = synchronized(afterGone) { afterGone.toList() }
        assertTrue(
            "nothing asked the gone server for the film, a playback session or a report: $asked",
            asked.none { p -> p.contains("/Download", true) || p.startsWith("/Videos/", true) || p.contains("PlaybackInfo", true) || p.startsWith("/Sessions/Playing", true) },
        )

        // Removing the account deletes its download and the folder with it.
        runBlocking { graph.serverStore.removeAccount(serverId, userId) }
        waitFor(timeoutMs = 10_000) { !folder.exists() }
        assertEquals(null, state())
    }
}
