package app.shoalwatch.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Browsing end to end on a device, against an in-process `MockWebServer` that serves a tiny library (the fixture JSON the unit
 * tests use, copied into this test's assets). Home must show a Continue Watching card and a library, and opening the library
 * must show a poster card with its label.
 *
 * Signing in through the screens is not repeated here (the Milestone 1 flow has its own tests): the test seeds ONE server
 * (plain http to the mock, with consent) and ONE signed-in account through the real `AppGraph` before the app starts, so the app
 * opens on Home exactly as it does for a returning user, and removes them afterwards so `WelcomeSmokeTest` still finds a clean
 * install. The cleanup also runs if seeding itself fails part way (a rule's `after()` does not). It removes the server through
 * `ServerStore.removeServer`: that deletes the server row, and the cached library rows (items, user data, views, lists) go with it
 * by ON DELETE CASCADE (`LibraryCascadeTest`), and it forgets the library's in-memory state and artwork for that server.
 * `AuthRepository.removeServer` ends in the same call, after trying to revoke the sign-in on the server, which a mock cannot answer.
 * Everything else is the real app: real store, real address probe, real library repository and Room database.
 *
 * Two tests: Home to the Films grid, and Home to the TV grid to a series page (a season chip and an episode row).
 */
@RunWith(AndroidJUnit4::class)
class BrowseSmokeTest {
    private val serverId = "5a3e1c0d9b7f4e2a8c6d0b1f3e5a7c9d"
    private val userId = "fedcba9876543210fedcba9876543210"
    private val tvLibraryId = "c0ffee00000000000000000000000002"
    private val seriesId = "aa000000000000000000000000000004"
    private val mock = MockWebServer()

    private val seed = object : ExternalResource() {
        override fun before() {
            try {
                seedEverything()
            } catch (t: Throwable) {
                cleanUp() // `after()` is not called when `before()` throws, and a leftover server would break WelcomeSmokeTest
                throw t
            }
        }

        private fun seedEverything() {
            val assets = InstrumentationRegistry.getInstrumentation().context.assets
            fun fixture(name: String) = assets.open("jellyfin/$name.json").bufferedReader().use { it.readText() }
            fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
            mock.dispatcher = object : Dispatcher() {
                // The paths are the ones LibraryGatewayImpl asks (checked against it): views, resume, next up, latest, a library's
                // children (/Items with parentId), one item, a series' seasons and its episodes.
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path.orEmpty().substringBefore('?')) {
                    "/System/Info/Public" -> json("""{"Id":"$serverId","ServerName":"Test Den","Version":"10.10.3"}""")
                    "/UserViews" -> json(fixture("views"))
                    "/UserItems/Resume" -> json(fixture("resume"))
                    "/Shows/NextUp" -> json(fixture("nextup"))
                    "/Items/Latest" -> json(fixture("latest"))
                    "/Items" -> json(fixture(if (request.requestUrl?.queryParameter("parentId") == tvLibraryId) "tv_page" else "items_page"))
                    "/Items/$seriesId" -> json(fixture("series_item"))
                    "/Shows/$seriesId/Seasons" -> json(fixture("seasons"))
                    "/Shows/$seriesId/Episodes" -> json(fixture("episodes"))
                    else -> MockResponse().setResponseCode(404) // pictures too: the cards show their placeholders
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

        /** Each step on its own, so one failing never skips the rest. Safe if nothing was seeded or the mock never started. */
        private fun cleanUp() {
            runCatching {
                val graph = ShoalwatchApp.graph(ApplicationProvider.getApplicationContext<Context>())
                runBlocking { graph.serverStore.removeServer(serverId) }
            }
            runCatching { mock.shutdown() }
        }
    }

    private val compose = createAndroidComposeRule<app.shoalwatch.ui.MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun describedStarting(prefix: String) = SemanticsMatcher("description starts with $prefix") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(prefix) } == true
    }

    private fun waitFor(timeoutMs: Long = 45_000, condition: () -> Boolean) = compose.waitUntil(timeoutMs, condition)

    @Test fun homeShowsContinueWatchingAndALibraryAndTheGridShowsAPoster() {
        val continueWatching = compose.activity.getString(R.string.home_continue)
        val libraries = compose.activity.getString(R.string.home_libraries)

        // The app opens on Home, loads from the mock and shows the resume row with a labelled card.
        waitFor { compose.onAllNodesWithText(continueWatching).fetchSemanticsNodes().isNotEmpty() }
        waitFor { compose.onAllNodes(describedStarting("Blade of Dawn")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(describedStarting("Blade of Dawn")).onFirst().assertIsDisplayed()

        // The libraries are further down the page: scroll to them and open Films.
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(libraries))
        // A library whose name is its kind ("Films") is read once, not "Films, Films".
        val films = "Films"
        waitFor { compose.onAllNodes(hasContentDescription(films)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasContentDescription(films)).onFirst().performClick()

        // The grid loads the first page from the mock and shows a poster card with its label.
        waitFor { compose.onAllNodes(describedStarting("Alien Harbour")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(describedStarting("Alien Harbour")).onFirst().assertIsDisplayed()
    }

    @Test fun aSeriesOpensFromTheTvLibraryWithASeasonChipAndAnEpisodeRow() {
        val continueWatching = compose.activity.getString(R.string.home_continue)
        val libraries = compose.activity.getString(R.string.home_libraries)
        waitFor { compose.onAllNodesWithText(continueWatching).fetchSemanticsNodes().isNotEmpty() }

        // Home -> the TV library -> its one series.
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(libraries))
        val tv = "TV Shows, ${compose.activity.getString(R.string.library_kind_tv)}"
        waitFor { compose.onAllNodes(hasContentDescription(tv)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasContentDescription(tv)).onFirst().performClick()
        waitFor { compose.onAllNodes(describedStarting("Harbour Lights")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(describedStarting("Harbour Lights")).onFirst().performClick()

        // The series page asked the server (no season was named) and shows a season chip and an episode row, not "no longer available".
        val season = compose.activity.getString(R.string.season_number, "1")
        waitFor { compose.onAllNodesWithText(season).fetchSemanticsNodes().isNotEmpty() }
        waitFor { compose.onAllNodes(describedStarting("Episode 1, ")).fetchSemanticsNodes().isNotEmpty() }
        check(compose.onAllNodesWithText(compose.activity.getString(R.string.gone_title)).fetchSemanticsNodes().isEmpty())
    }
}
