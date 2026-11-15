package app.shoalwatch.ui

import android.content.Context
import android.view.WindowManager
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.shoalwatch.R
import app.shoalwatch.ShoalwatchApp
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.Session
import app.shoalwatch.net.ServerAddress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Server admin end to end on a device (the controller runs it; it is only compiled here). One server and two accounts are
 * seeded through the real `AppGraph` (plain http to an in-process `MockWebServer`, with consent): ben, whom the mock calls an
 * administrator, and kid, who is not. With ben active: Servers and accounts shows "Server admin for Test Den"; the dashboard
 * shows kid's playing card; the window is FLAG_SECURE; Stop, then "Stop playback", sends exactly one Stop with ben's token in
 * `Authorization` and nothing token-like in the query. Then the active account becomes kid: within 5 s the admin screen is gone,
 * the window is no longer secure, Servers shows no "Server admin", and no admin path but `/Users/Me` was asked with kid's token.
 * Clean-up runs even if seeding fails part way (as BrowseSmokeTest): it removes the seeded server and both accounts, then makes
 * whichever account was active before the test active again. For the emulator: it is never meant for a phone with real accounts,
 * but if it ever runs on one it leaves that phone's active profile as it found it.
 */
@RunWith(AndroidJUnit4::class)
class AdminSmokeTest {
    private val serverId = "5a3e1c0d9b7f4e2a8c6d0b1f3e5a7c9e"
    private val benId = "fedcba9876543210fedcba9876543211"
    private val kidId = "fedcba9876543210fedcba9876543212"
    private val benToken = "test-token-ben"
    private val kidToken = "test-token-kid"
    private val sessionId = "sess0kid0tv"
    private val mock = MockWebServer()
    private val seen: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    /** The account that was active before seeding (null: none), put back by the clean-up; [captured] once it was read. */
    private var previous: Session? = null
    private var captured = false

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private val sessionsJson = """[{"Id":"$sessionId","UserName":"kid","DeviceName":"Living room TV","DeviceId":"tv-device-1",
        "Client":"Jellyfin Android TV","ApplicationVersion":"0.17.0","SupportsMediaControl":true,"SupportedCommands":["DisplayMessage"],
        "NowPlayingItem":{"Name":"Title","SeriesName":"Harbour Lights","ParentIndexNumber":2,"IndexNumber":3,"Type":"Episode","RunTimeTicks":27000000000},
        "PlayState":{"PositionTicks":7500000000,"IsPaused":false,"PlayMethod":"Transcode"},
        "TranscodingInfo":{"IsVideoDirect":false,"IsAudioDirect":true,"TranscodeReasons":["VideoCodecNotSupported"]}}]"""

    private val seed = object : ExternalResource() {
        override fun before() {
            try {
                seedEverything()
            } catch (t: Throwable) {
                cleanUp()
                throw t
            }
        }

        private fun me(id: String, admin: Boolean) = json("""{"Id":"$id","Name":"x","Policy":{"IsAdministrator":$admin,"IsDisabled":false}}""")

        private fun seedEverything() {
            mock.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seen += request
                    val auth = request.getHeader("Authorization").orEmpty()
                    val ben = benToken in auth
                    return when (request.method to request.path.orEmpty().substringBefore('?')) {
                        "GET" to "/System/Info/Public" -> json("""{"Id":"$serverId","ServerName":"Test Den","Version":"10.11.0"}""")
                        "GET" to "/Users/Me" -> me(if (ben) benId else kidId, admin = ben)
                        "GET" to "/System/Info" -> if (ben) json("""{"ServerName":"Test Den","Version":"10.11.0","HasPendingRestart":false}""") else forbidden()
                        "GET" to "/Sessions" -> if (ben) json(sessionsJson) else forbidden()
                        "POST" to "/Sessions/$sessionId/Playing/Stop" -> if (ben) MockResponse().setResponseCode(204) else forbidden()
                        "GET" to "/UserViews", "GET" to "/UserItems/Resume", "GET" to "/Shows/NextUp", "GET" to "/Items" ->
                            json("""{"Items":[],"TotalRecordCount":0}""")
                        "GET" to "/Items/Latest" -> json("[]")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            mock.start()
            val graph = ShoalwatchApp.graph(ApplicationProvider.getApplicationContext<Context>())
            runBlocking {
                graph.tokensWarmed.join()
                previous = graph.serverCatalog.activeSession().first()
                captured = true
                graph.serverStore.upsertServer(
                    Server(
                        id = serverId, name = "Test Den", version = "10.11.0", homeAddress = ServerAddress.parse("http://127.0.0.1:${mock.port}"),
                        awayAddress = null, allowCleartext = true, pinnedSpkiSha256 = null, clientCertAlias = null, customHeaders = emptyMap(),
                    ),
                )
                graph.serverStore.upsertAccount(Account(serverId, kidId, "kid", kidToken), makeActive = false)
                graph.serverStore.upsertAccount(Account(serverId, benId, "ben", benToken), makeActive = true)
            }
        }

        private fun forbidden() = MockResponse().setResponseCode(403)

        override fun after() = cleanUp()

        /** Each step on its own, so one failing never skips the rest. Safe if nothing was seeded or the mock never started. */
        private fun cleanUp() {
            runCatching {
                val graph = ShoalwatchApp.graph(ApplicationProvider.getApplicationContext<Context>())
                runBlocking { graph.serverStore.removeServer(serverId) } // the server row, with both seeded accounts and their tokens
            }
            runCatching {
                val back = previous
                if (captured && back != null && back.serverId != serverId) {
                    val graph = ShoalwatchApp.graph(ApplicationProvider.getApplicationContext<Context>())
                    runBlocking { graph.serverCatalog.setActive(back.serverId, back.userId) }
                }
            }
            runCatching { mock.shutdown() }
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitFor(timeoutMs: Long = 45_000, condition: () -> Boolean) = compose.waitUntil(timeoutMs, condition)
    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun described(label: String) = compose.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty()
    private fun shown(label: String) = compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
    private fun secure(): Boolean {
        var flags = 0
        compose.runOnUiThread { flags = compose.activity.window.attributes.flags }
        return flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }

    @Test fun anAdministratorStopsPlaybackAndASwitchEndsEverything() {
        val entry = string(R.string.admin_entry_cd, "Test Den")
        // Home -> Servers and accounts -> Server admin.
        openServersFromHome()
        waitFor { described(entry) }
        compose.onAllNodes(hasContentDescription(entry)).onFirst().performClick()

        // The dashboard shows who is watching, and the window is secure.
        waitFor { shown(string(R.string.admin_watching_heading)) || run { scrollTo(string(R.string.admin_watching_heading)); false } }
        waitFor { shown("kid") }
        check(secure()) { "the admin window must be FLAG_SECURE" }

        // Stop kid's playback, after confirming.
        val stop = string(R.string.admin_session_stop_cd, "kid", "Living room TV")
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasContentDescription(stop))
        compose.onAllNodes(hasContentDescription(stop)).onFirst().performClick()
        waitFor { shown(string(R.string.admin_confirm_stop_action)) }
        compose.onAllNodesWithText(string(R.string.admin_confirm_stop_action)).onFirst().performClick()
        waitFor { shown(string(R.string.admin_stopped_done, "kid")) }
        val stops = seen.filter { it.method == "POST" && it.path.orEmpty().startsWith("/Sessions/$sessionId/Playing/Stop") }
        check(stops.size == 1) { "exactly one Stop, got ${stops.size}" }
        check(benToken in stops.single().getHeader("Authorization").orEmpty())
        val query = stops.single().requestUrl?.queryParameterNames.orEmpty().map { it.lowercase() }
        check(query.none { "api" in it || "token" in it || "key" in it }) { "a token-like query name was sent" }

        // Switch to kid: everything admin goes at once.
        val before = seen.size
        val graph = ShoalwatchApp.graph(ApplicationProvider.getApplicationContext<Context>())
        runBlocking { graph.serverCatalog.setActive(serverId, kidId) }
        waitFor(5_000) { !shown(string(R.string.admin_watching_heading)) && !shown(string(R.string.admin_title)) && !secure() }
        waitFor { shown(string(R.string.servers_title)) || described(string(R.string.account_menu_cd)) || described(string(R.string.home_manage)) }
        if (!shown(string(R.string.servers_title))) {
            openServersFromHome()
            waitFor { shown(string(R.string.servers_title)) }
        }
        compose.waitForIdle()
        check(!described(entry)) { "Servers and accounts still offers Server admin to kid" }
        val adminPaths = listOf("/System", "/Sessions", "/Users", "/Library", "/ScheduledTasks", "/Devices")
        val kidAdmin = seen.drop(before).filter { r ->
            kidToken in r.getHeader("Authorization").orEmpty() && adminPaths.any { r.path.orEmpty().startsWith(it) } &&
                r.path.orEmpty().substringBefore('?') != "/Users/Me" && !r.path.orEmpty().startsWith("/System/Info/Public")
        }
        check(kidAdmin.isEmpty()) { "admin requests were sent with kid's token: ${kidAdmin.size}" }
    }

    /**
     * Home's account button: with one account it IS "Servers and accounts"; with several (this test seeds two) it opens a menu
     * whose last entry is "Servers and accounts".
     */
    private fun openServersFromHome() {
        val manage = string(R.string.home_manage)
        val menu = string(R.string.account_menu_cd)
        waitFor { described(manage) || described(menu) }
        if (described(manage)) {
            compose.onAllNodes(hasContentDescription(manage)).onFirst().performClick()
        } else {
            compose.onAllNodes(hasContentDescription(menu)).onFirst().performClick()
            waitFor { shown(manage) }
            compose.onAllNodesWithText(manage).onFirst().performClick()
        }
    }

    private fun scrollTo(label: String) {
        runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(label)) }
    }
}
