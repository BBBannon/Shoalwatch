package app.shoalwatch.ui

import android.content.Context
import android.content.Intent
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.shoalwatch.ui.player.PipActionReceiver
import app.shoalwatch.ui.player.PipWish
import app.shoalwatch.ui.player.pipParams
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
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
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.Server
import app.shoalwatch.net.ServerAddress
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Picture-in-picture on a device (Milestone 7 Task 9; the controller runs it): playback as `PlayerSmokeTest` (the same
 * in-process `MockWebServer` and seeding), then into the small window with the player's own params. It checks the position
 * goes on there, that the window's play/pause broadcast (explicit, to this package) pauses (the pause is reported with the
 * position), and that closing the window (the activity stopping) leaves the player paused when the app is opened again.
 * The clip is 10 s long: 2 s before the window, 2 s in it, then the rest well inside the clip (review I1 of Tasks 9-10).
 */
@RunWith(AndroidJUnit4::class)
class PipSmokeTest {
    private val serverId = "5a3e1c0d9b7f4e2a8c6d0b1f3e5a7c9e"
    private val userId = "fedcba9876543210fedcba9876543211"
    private val clipId = "c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1"
    private val mock = MockWebServer()
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    private val item = """{"Name":"Test Clip","ServerId":"$serverId","Id":"$clipId","Type":"Movie","IsFolder":false,"MediaType":"Video",
        "RunTimeTicks":100000000,"ImageTags":{},"BackdropImageTags":[],"LocationType":"FileSystem",
        "UserData":{"PlaybackPositionTicks":0,"PlayCount":0,"IsFavorite":false,"Played":false,"Key":"$clipId"}}"""

    private fun playbackInfo(size: Int) = """{"MediaSources":[{"Protocol":"File","Id":"$clipId","Type":"Default","Container":"mp4","Name":"Test Clip",
        "RunTimeTicks":100000000,"Size":$size,"Bitrate":1000000,"IsRemote":false,"SupportsTranscoding":true,"SupportsDirectPlay":true,
        "SupportsDirectStream":true,"HasSegments":true,"DefaultAudioStreamIndex":1,"MediaStreams":[
        {"Index":0,"Type":"Video","Codec":"h264","Profile":"Constrained Baseline","Level":30,"Width":320,"Height":180,"BitDepth":8,"VideoRange":"SDR",
         "VideoRangeType":"SDR","IsDefault":true,"IsExternal":false},
        {"Index":1,"Type":"Audio","Codec":"aac","Language":"eng","Channels":1,"SampleRate":48000,"IsDefault":true,"IsExternal":false}]}],
        "PlaySessionId":"5e55105e55105e55105e55105e551052"}"""

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
            val clip = InstrumentationRegistry.getInstrumentation().context.assets.open("media/clip.mp4").use { it.readBytes() }
            fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
            val empty = """{"Items":[],"TotalRecordCount":0,"StartIndex":0}"""
            mock.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val path = request.path.orEmpty().substringBefore('?')
                    return when {
                        path == "/System/Info/Public" -> json("""{"Id":"$serverId","ServerName":"Test Den","Version":"10.10.3"}""")
                        path == "/UserViews" || path == "/Shows/NextUp" -> json(empty)
                        path == "/UserItems/Resume" -> json("""{"Items":[$item],"TotalRecordCount":1,"StartIndex":0}""")
                        path == "/Items/Latest" -> json("[]")
                        path == "/Items/$clipId/PlaybackInfo" -> json(playbackInfo(clip.size))
                        path == "/Items/$clipId" -> json(item)
                        path == "/MediaSegments/$clipId" -> json(empty)
                        path.startsWith("/Sessions/Playing") -> MockResponse().setResponseCode(204)
                        path.equals("/Videos/$clipId/stream", ignoreCase = true) -> bytes(clip, request.getHeader("Range"))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            mock.start()
            val graph = ShoalwatchApp.graph(ApplicationProvider.getApplicationContext<Context>())
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
            runCatching {
                val graph = ShoalwatchApp.graph(ApplicationProvider.getApplicationContext<Context>())
                runBlocking { graph.serverStore.removeServer(serverId) }
            }
            runCatching { mock.shutdown() }
        }
    }

    /** The clip, whole or one `bytes=start-end` range of it (206), as a file server answers. */
    private fun bytes(clip: ByteArray, range: String?): MockResponse {
        val found = range?.let { Regex("""bytes=(\d+)-(\d*)""").matchEntire(it.trim()) }
            ?: return MockResponse().setResponseCode(200).setHeader("Content-Type", "video/mp4").setHeader("Accept-Ranges", "bytes").setBody(Buffer().write(clip))
        val start = found.groupValues[1].toInt()
        val end = found.groupValues[2].toIntOrNull()?.coerceAtMost(clip.size - 1) ?: (clip.size - 1)
        if (start >= clip.size || end < start) return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${clip.size}")
        return MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp4").setHeader("Accept-Ranges", "bytes")
            .setHeader("Content-Range", "bytes $start-$end/${clip.size}").setBody(Buffer().write(clip, start, end - start + 1))
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun describedStarting(prefix: String) = SemanticsMatcher("description starts with $prefix") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(prefix) } == true
    }

    private fun waitFor(timeoutMs: Long = 45_000, condition: () -> Boolean) = compose.waitUntil(timeoutMs, condition)

    private fun posts(path: String) = synchronized(requests) { requests.filter { it.method == "POST" && it.path.orEmpty().substringBefore('?') == path }.toList() }

    /** The elapsed time under the seek bar, in seconds, or null while the controls are hidden. */
    private fun elapsedSeconds(): Int? = compose.onAllNodes(SemanticsMatcher("an elapsed time") { n ->
        n.config.getOrNull(SemanticsProperties.Text)?.any { Regex("""0:0\d""").matches(it.text) } == true
    }).fetchSemanticsNodes().firstNotNullOfOrNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text?.takeLast(1)?.toIntOrNull() }

    /** The `/Sessions/Playing/Progress` reports so far: (paused, position in ticks), oldest first. */
    private fun progress(): List<Pair<Boolean, Long>> = posts("/Sessions/Playing/Progress").map { r ->
        val json = r.body.clone().readUtf8()
        val paused = Regex(""""IsPaused"\s*:\s*(true|false)""").find(json)?.groupValues?.get(1) == "true"
        val ticks = Regex(""""PositionTicks"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toLong() ?: 0L
        paused to ticks
    }

    private fun inPip(): Boolean {
        var shown = false
        compose.activityRule.scenario.onActivity { shown = it.isInPictureInPictureMode }
        return shown
    }

    /** The activity's lifecycle stage as the test runner sees it (read on the main thread). */
    private fun stage(): Stage? {
        val activity = compose.activity // fetched off the main thread (it goes through the scenario)
        var found: Stage? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity)
        }
        return found
    }

    private fun pressPipPlayPause() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.sendBroadcast(Intent(PipActionReceiver.ACTION).setPackage(context.packageName))
    }

    @Test fun playbackGoesOnInTheSmallWindowItsButtonPausesAndClosingItLeavesThePlayerPaused() {
        // As PlayerSmokeTest: Home -> the Continue Watching card -> the detail page -> Play, until at least 0:02 has played.
        waitFor { compose.onAllNodes(describedStarting("Test Clip")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(describedStarting("Test Clip")).onFirst().performClick()
        val play = compose.activity.getString(R.string.detail_play)
        waitFor { compose.onAllNodes(hasText(play)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(play)).onFirst().performScrollTo().performClick()
        var lastTap = 0L
        waitFor(timeoutMs = 20_000) {
            val shown = elapsedSeconds()
            if (shown == null && System.currentTimeMillis() - lastTap > 1_500 && posts("/Sessions/Playing").isNotEmpty()) {
                lastTap = System.currentTimeMillis()
                runCatching { compose.onRoot().performTouchInput { click(center) } }
            }
            (shown ?: 0) >= 2
        }

        // Into the small window, as the system does on the swipe home with the params the player set.
        compose.activityRule.scenario.onActivity { it.enterPictureInPictureMode(pipParams(it, PipWish(320, 180, playing = true, sourceRect = null))) }
        waitFor(timeoutMs = 10_000) { inPip() }
        Thread.sleep(2_000) // playing on in the small window

        // The window's own button: an explicit broadcast to this package. The pause is reported with the position reached.
        pressPipPlayPause()
        waitFor(timeoutMs = 10_000) { progress().any { it.first } }
        val pausedAt = progress().first { it.first }.second
        assertTrue("the position went on in the small window (ticks $pausedAt)", pausedAt >= 30_000_000L)

        // Play again, then close the window: the activity stops, and the player pauses as it always does when the app is left.
        pressPipPlayPause()
        waitFor(timeoutMs = 10_000) { progress().lastOrNull()?.first == false }
        // Closed as the system closes it: the pinned task goes (a full-screen activity in another task would not cover the window,
        // so ActivityScenario's own move to CREATED never sees a stop: review I1 of Tasks 9-10).
        compose.activityRule.scenario.onActivity { it.moveTaskToBack(true) }
        waitFor(timeoutMs = 10_000) { stage() == Stage.STOPPED }
        waitFor(timeoutMs = 10_000) { progress().lastOrNull()?.first == true }
        // Opened again: the same activity brought to the front, as the launcher does.
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        waitFor(timeoutMs = 10_000) { stage() == Stage.RESUMED }
        Thread.sleep(2_000)
        assertEquals("still paused once back", true, progress().last().first)
    }
}
