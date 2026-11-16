package app.shoalwatch.ui

import android.content.Context
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import app.shoalwatch.R
import app.shoalwatch.ShoalwatchApp
import app.shoalwatch.data.db.downloads.DownloadStore
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadScheduler
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.RefreshTarget
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.sync.ChangeKind
import app.shoalwatch.data.sync.SyncScheduler
import app.shoalwatch.data.sync.SyncStatus
import app.shoalwatch.net.ServerAddress
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Milestone 5 on a device (Task 15): ticks are instant offline, each profile's changes reach the server in order and only as
 * itself, watch-and-delete sends one play with the phone's finish time, and signing out drops only that profile's changes.
 *
 * One server and TWO accounts (A "ann", active; B "bee"), seeded through the real `AppGraph` as `PlayerSmokeTest` does, with
 * the clean-up also run when seeding fails part way (as `AdminSmokeTest`, which also puts back the profile that was active).
 * An in-process `MockWebServer` serves `/System/Info/Public`, a series (one season, three episodes) and a film, and the
 * user-data routes with the server's own copy per account (keyed by the token), recording every request. "Down" refuses
 * every connection (`DISCONNECT_AT_START`; a connection already open is closed after the request is read).
 */
@RunWith(AndroidJUnit4::class)
class SyncSmokeTest {
    private val serverId = "7c1e5a3f9b2d4e6a8c0f1b3d5e7a9c1f"
    private val userA = "aaaa0000000000000000000000000a01"
    private val userB = "bbbb0000000000000000000000000b02"
    private val tokenA = "tok-alpha-1111"
    private val tokenB = "tok-bravo-2222"
    private val seriesId = "5e1e5000000000000000000000000001"
    private val seasonId = "5ea50000000000000000000000000001"
    private val episodes = listOf("e0000000000000000000000000000e01", "e0000000000000000000000000000e02", "e0000000000000000000000000000e03")
    private val episodeNames = listOf("High Water", "Slack Tide", "Low Tide")
    private val filmId = "f1100000000000000000000000000f01"
    private val seriesName = "Tideway"
    private val sA = Session(serverId, userA)
    private val sB = Session(serverId, userB)

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = ShoalwatchApp.graph(context)
    private val mock = MockWebServer()

    /** Every request, and whether the server was down when it came (a refused connection is recorded as down too). */
    private class Seen(val request: RecordedRequest, val whileDown: Boolean)
    private val seen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())
    @Volatile private var down = false

    /** The server's own copy, per (user, item): what GET answers and what the POSTs change. */
    private class ServerCopy(var played: Boolean = false, var playCount: Int = 0, var favourite: Boolean = false, var lastPlayed: String? = null, var positionTicks: Long = 0)
    private val copies = ConcurrentHashMap<Pair<String, String>, ServerCopy>()

    /** The account that was active before seeding (null: none), put back by the clean-up; [captured] once it was read. */
    private var previous: Session? = null
    private var captured = false

    private fun copy(user: String, id: String) = copies.getOrPut(user to id) { ServerCopy() }

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private fun userOf(r: RecordedRequest): String? {
        val auth = r.getHeader("Authorization").orEmpty()
        return when {
            tokenA in auth -> userA
            tokenB in auth -> userB
            else -> null
        }
    }

    private fun userDataJson(user: String, id: String): String = synchronized(copies) {
        val c = copy(user, id)
        val last = c.lastPlayed?.let { ""","LastPlayedDate":"$it"""" }.orEmpty()
        """{"PlaybackPositionTicks":${c.positionTicks},"PlayCount":${c.playCount},"IsFavorite":${c.favourite},"Played":${c.played},"Key":"$id"$last}"""
    }

    private fun series(user: String) = """{"Name":"$seriesName","ServerId":"$serverId","Id":"$seriesId","Type":"Series","IsFolder":true,"ChildCount":1,
        "ImageTags":{},"BackdropImageTags":[],"UserData":${userDataJson(user, seriesId)}}"""

    private fun season(user: String) = """{"Name":"Season 1","ServerId":"$serverId","Id":"$seasonId","Type":"Season","ParentId":"$seriesId","SeriesId":"$seriesId",
        "SeriesName":"$seriesName","IndexNumber":1,"IsFolder":true,"ChildCount":3,"ImageTags":{},"UserData":${userDataJson(user, seasonId)}}"""

    private fun episode(user: String, n: Int) = """{"Name":"${episodeNames[n]}","ServerId":"$serverId","Id":"${episodes[n]}","Type":"Episode","ParentId":"$seasonId",
        "SeasonId":"$seasonId","SeasonName":"Season 1","SeriesId":"$seriesId","SeriesName":"$seriesName","IndexNumber":${n + 1},"ParentIndexNumber":1,
        "RunTimeTicks":100000000,"IsFolder":false,"MediaType":"Video","LocationType":"FileSystem","ImageTags":{},"BackdropImageTags":[],
        "UserData":${userDataJson(user, episodes[n])}}"""

    private fun film(user: String) = """{"Name":"Harbour Film","ServerId":"$serverId","Id":"$filmId","Type":"Movie","IsFolder":false,"MediaType":"Video",
        "RunTimeTicks":100000000,"ImageTags":{},"BackdropImageTags":[],"LocationType":"FileSystem","UserData":${userDataJson(user, filmId)}}"""

    private fun itemJson(user: String, id: String): String? = when (id) {
        seriesId -> series(user)
        seasonId -> season(user)
        filmId -> film(user)
        in episodes -> episode(user, episodes.indexOf(id))
        else -> null
    }

    private fun list(items: List<String>) = json("""{"Items":[${items.joinToString(",")}],"TotalRecordCount":${items.size},"StartIndex":0}""")

    /** The server, while it is up. */
    private fun answer(r: RecordedRequest): MockResponse {
        val path = r.path.orEmpty().substringBefore('?')
        if (path == "/System/Info/Public") return json("""{"Id":"$serverId","ServerName":"Test Den","Version":"10.10.3"}""")
        val user = userOf(r) ?: return MockResponse().setResponseCode(401)
        val id = path.split('/').getOrNull(2).orEmpty()
        return when {
            path == "/Users/Me" -> json("""{"Id":"$user","Name":"x","Policy":{"IsAdministrator":false,"IsDisabled":false}}""")
            path == "/UserViews" || path == "/UserItems/Resume" || path == "/Shows/NextUp" -> list(emptyList())
            path == "/Items/Latest" -> json("[]")
            path == "/Items" -> list(r.requestUrl?.queryParameter("ids").orEmpty().split(',').mapNotNull { itemJson(user, it.trim()) })
            path == "/Shows/$seriesId/Seasons" -> list(listOf(season(user)))
            path == "/Shows/$seriesId/Episodes" -> list(episodes.indices.map { episode(user, it) })
            path.startsWith("/UserItems/") && path.endsWith("/UserData") && itemJson(user, id) != null -> userData(r, user, id)
            path == "/UserFavoriteItems/$id" && itemJson(user, id) != null -> {
                synchronized(copies) { copy(user, id).favourite = r.method == "POST" }
                json(userDataJson(user, id))
            }
            path == "/UserPlayedItems/$id" && itemJson(user, id) != null -> {
                synchronized(copies) { copy(user, id).played = r.method == "POST" }
                json(userDataJson(user, id))
            }
            path.startsWith("/Items/") && path.count { it == '/' } == 2 -> itemJson(user, id)?.let(::json) ?: MockResponse().setResponseCode(404)
            path.startsWith("/Sessions/") -> MockResponse().setResponseCode(204)
            else -> MockResponse().setResponseCode(404)
        }
    }

    /** GET answers the copy; POST applies only the fields sent (absolute values), as the reference allows either way. */
    private fun userData(r: RecordedRequest, user: String, id: String): MockResponse {
        if (r.method == "POST") {
            val body = JSONObject(r.body.clone().readUtf8())
            synchronized(copies) {
                val c = copy(user, id)
                if (body.has("Played")) c.played = body.getBoolean("Played")
                if (body.has("PlayCount")) c.playCount = body.getInt("PlayCount")
                if (body.has("PlaybackPositionTicks")) c.positionTicks = body.getLong("PlaybackPositionTicks")
                if (body.has("LastPlayedDate")) c.lastPlayed = body.getString("LastPlayedDate")
            }
        }
        return json(userDataJson(user, id))
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
            mock.dispatcher = object : Dispatcher() {
                /** A new connection while down is refused before anything is read. */
                override fun peek(): MockResponse = if (down) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) else super.peek()

                override fun dispatch(request: RecordedRequest): MockResponse {
                    val isDown = down
                    seen += Seen(request, isDown)
                    // A connection opened while up and still pooled: closed without an answer.
                    return if (isDown) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else answer(request)
                }
            }
            mock.start()
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(SyncScheduler.WORK_NAME).result.get(10, TimeUnit.SECONDS) }
            runBlocking {
                graph.tokensWarmed.join()
                previous = graph.serverCatalog.activeSession().first()
                captured = true
                graph.serverStore.upsertServer(
                    Server(
                        id = serverId, name = "Test Den", version = "10.10.3", homeAddress = ServerAddress.parse("http://127.0.0.1:${mock.port}"),
                        awayAddress = null, allowCleartext = true, pinnedSpkiSha256 = null, clientCertAlias = null, customHeaders = emptyMap(),
                    ),
                )
                graph.serverStore.upsertAccount(Account(serverId, userB, "bee", tokenB), makeActive = false)
                graph.serverStore.upsertAccount(Account(serverId, userA, "ann", tokenA), makeActive = true)
            }
        }

        override fun after() = cleanUp()

        /** Each step on its own, so one failing never skips the rest. Safe if nothing was seeded or the mock never started. */
        private fun cleanUp() {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(SyncScheduler.WORK_NAME).result.get(10, TimeUnit.SECONDS) }
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(DownloadScheduler.NAME).result.get(10, TimeUnit.SECONDS) }
            runCatching { runBlocking { graph.serverStore.removeServer(serverId) } } // both accounts, their outbox rows and downloads
            runCatching {
                val back = previous
                if (captured && back != null && back.serverId != serverId) runBlocking { graph.serverCatalog.setActive(back.serverId, back.userId) }
            }
            runCatching { mock.shutdown() }
            val folder = File(File(context.filesDir, "downloads"), serverId)
            val until = System.currentTimeMillis() + 10_000
            while (folder.exists() && System.currentTimeMillis() < until) Thread.sleep(100) // the removal hook runs in the app scope
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(seed).around(compose)

    private fun waitFor(timeoutMs: Long = 45_000, condition: () -> Boolean) = compose.waitUntil(timeoutMs, condition)
    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun shown(text: String, substring: Boolean = false) = compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
    private fun described(label: String) = compose.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty()
    private fun back() = compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    /** Refuses every connection, then has the selector look again, so everything after this starts from Offline. */
    private fun goDown() {
        down = true
        graph.addressSelector.refresh()
        waitFor(30_000) { graph.addressSelector.connection(serverId).value is Connection.Offline }
    }

    private fun goUp() {
        down = false
        graph.addressSelector.refresh()
    }

    /** What the server saw while it was up. */
    private fun answered(): List<RecordedRequest> = synchronized(seen) { seen.filter { !it.whileDown }.map { it.request } }

    private fun pathOf(r: RecordedRequest) = r.path.orEmpty().substringBefore('?')

    private fun bodyOf(r: RecordedRequest) = JSONObject(r.body.clone().readUtf8())

    private fun browse() = runBlocking {
        withTimeout(30_000) {
            graph.library.refresh(sA, RefreshTarget.Series(seriesId, seasonId))
            graph.library.refresh(sB, RefreshTarget.Item(filmId))
        }
        val listed = withTimeout(30_000) { graph.library.series(sA, seriesId, seasonId).first { it.episodes.size == 3 } }
        assertEquals("A sees the three episodes", episodes, listed.episodes.map { it.item.id })
        assertNotNull("B sees the film", withTimeout(30_000) { graph.library.item(sB, filmId).first { it.item != null } }.item)
    }

    private fun played(s: Session, id: String): Boolean = runBlocking { graph.library.item(s, id).first().item?.userData?.played == true }

    private fun status(s: Session): SyncStatus = runBlocking { graph.sync.status(s).first() }

    /** One profile's outbox rows, oldest first: (kind, occurredAt). */
    private fun outbox(user: String): List<Pair<ChangeKind, Long>> =
        graph.database.openHelper.readableDatabase.query(
            "SELECT kind, occurredAt FROM sync_outbox WHERE serverId = ? AND userId = ? ORDER BY seq", arrayOf<Any?>(serverId, user),
        ).use { c -> buildList { while (c.moveToNext()) add(ChangeKind.valueOf(c.getString(0)) to c.getLong(1)) } }

    private fun timed(block: suspend () -> Unit): Long {
        val start = System.nanoTime()
        runBlocking { block() }
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test fun ticksAreInstantOfflineAndEachProfileSyncsInOrderAsItself() {
        browse()
        goDown()

        // Instant and offline: each tick returns within 500 ms and Room shows it at once.
        for (id in episodes) {
            val took = timed { graph.playstate.setPlayed(sA, id, true) }
            assertTrue("a tick returned in $took ms", took < 500)
            assertTrue("watched on the phone at once", played(sA, id))
        }
        val took = timed { graph.playstate.setFavourite(sB, filmId, true) }
        assertTrue("B's favourite returned in $took ms", took < 500)
        assertEquals(3, outbox(userA).size)
        assertEquals(listOf(ChangeKind.FAVOURITE), outbox(userB).map { it.first })

        // The job is there: one ENQUEUED request on any network, no input data. (Run at once on a connected emulator, it waits
        // again in ENQUEUED after its retry.)
        val workManager = WorkManager.getInstance(context)
        fun pending() = workManager.getWorkInfosForUniqueWork(SyncScheduler.WORK_NAME).get(10, TimeUnit.SECONDS).filter { !it.state.isFinished }
        waitFor(30_000) { pending().let { it.size == 1 && it.single().state == WorkInfo.State.ENQUEUED } }
        val job = pending().single()
        assertEquals(NetworkType.CONNECTED, job.constraints.requiredNetworkType)
        val spec = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao().getWorkSpec(job.id.toString())
        assertEquals("no input data", Data.EMPTY, spec?.input)

        // Back up: within 30 s both profiles are fully sent, each as itself.
        val before = answered().size
        goUp()
        waitFor(30_000) {
            listOf(status(sA), status(sB)).all { it.waiting == 0 && it.lastSyncedAt != null }
        }
        val after = answered().drop(before)
        val userData = after.filter { pathOf(it).startsWith("/UserItems/") && pathOf(it).endsWith("/UserData") }
        assertEquals(
            "A's rows in tick order, each a read then an update",
            episodes.flatMap { listOf("GET $it", "POST $it") },
            userData.map { "${it.method} ${pathOf(it).split('/')[2]}" },
        )
        for (r in userData) {
            assertEquals("as A", userA, userOf(r))
            assertEquals("for A", userA, r.requestUrl?.queryParameter("userId"))
            if (r.method == "POST") assertTrue("Played true", bodyOf(r).optBoolean("Played", false))
        }
        val favourite = after.single { pathOf(it) == "/UserFavoriteItems/$filmId" }
        assertEquals("POST", favourite.method)
        assertEquals("as B", userB, userOf(favourite))
        assertEquals("for B", userB, favourite.requestUrl?.queryParameter("userId"))
        assertTrue("B's favourite is on B's copy only", synchronized(copies) { copy(userB, filmId).favourite && !copy(userA, filmId).favourite })
        assertTrue("A's ticks are on A's copy only", synchronized(copies) { episodes.all { copy(userA, it).played && !copy(userB, it).played } })
        val names = synchronized(seen) { seen.flatMap { it.request.requestUrl?.queryParameterNames.orEmpty() }.map { it.lowercase() } }
        assertTrue("no datePlayed and no api_key in any URL", names.none { it == "dateplayed" || it == "api_key" || it == "apikey" })
    }

    @Test fun watchAndDeleteOfflineSendsOnePlayWithThePhonesFinishTime() {
        browse()
        goDown()
        val episode = episodes[2] // the last one: no Up next takes the player on
        val folder = seedDownload(episode)

        // Downloads -> the series -> the episode's detail -> Play.
        waitFor { shown(string(R.string.nav_downloads)) }
        compose.onAllNodesWithText(string(R.string.nav_downloads)).onFirst().performClick()
        waitFor { shown(seriesName) }
        compose.onAllNodesWithText(seriesName).onFirst().performClick()
        waitFor { shown(episodeNames[2], substring = true) }
        compose.onAllNodes(hasText(episodeNames[2], substring = true)).onFirst().performClick()
        val play = string(R.string.detail_play)
        waitFor { shown(play) }
        compose.onAllNodesWithText(play).onFirst().performScrollTo().performClick()

        // Really started (a cold emulator can take a while): the local play's own record is written at its start report.
        waitFor(90_000) { runBlocking { graph.database.offlinePlayDao().get(serverId, userA, episode) } != null }
        // Within the clip's length plus 10 s of that, the prompt.
        val delete = string(R.string.watched_delete)
        waitFor(20_000) { shown(delete) }
        val pressedAt = System.currentTimeMillis()
        compose.onAllNodesWithText(delete).onFirst().performClick()
        waitFor(15_000) { shown(string(R.string.watched_deleted)) } // the release has run before the player closes
        back()
        waitFor(30_000) { !folder.exists() }
        waitFor(15_000) { outbox(userA).isNotEmpty() }
        val backAt = System.currentTimeMillis()
        // Real code (Tasks 12-14): the press marks it watched on the phone only and the stop's one PLAY carries it, so the outbox
        // holds a single PLAY, not a PLAY and a PLAYED (a PLAYED as well would only be AlreadyThere on the server).
        val rows = outbox(userA)
        assertEquals("one PLAY for the one watch", listOf(ChangeKind.PLAY), rows.map { it.first })
        val finishedAt = rows.single().second
        assertTrue("the finish time is the phone's, at the stop", finishedAt in (pressedAt - 30_000)..(backAt + 1_000))

        // Still listed in the offline series under "Watched and deleted", with its tick.
        waitFor { !shown(delete) }
        back() // the detail page -> the offline series
        waitFor { shown(string(R.string.offline_released)) && shown(string(R.string.offline_released_line)) }
        assertTrue(shown(episodeNames[2], substring = true))
        assertTrue("its tick", shown(string(R.string.state_watched)))

        // Up: one update with PlayCount 1 and the phone's finish time, to the second, as A.
        goUp()
        fun updates() = answered().filter { it.method == "POST" && pathOf(it) == "/UserItems/$episode/UserData" }
        waitFor(30_000) { updates().isNotEmpty() && status(sA).waiting == 0 }
        val update = updates().single()
        assertEquals("as A", userA, userOf(update))
        assertEquals("for A", userA, update.requestUrl?.queryParameter("userId"))
        val body = bodyOf(update)
        assertEquals(1, body.getInt("PlayCount"))
        assertEquals(Instant.ofEpochMilli(finishedAt).truncatedTo(ChronoUnit.SECONDS), Instant.parse(body.getString("LastPlayedDate")))

        // A second, forced drain sends nothing more: the server's count is still 1.
        assertEquals(0, runBlocking { graph.sync.sendNow(sA, 20_000) })
        assertEquals("one update only", 1, updates().size)
        assertEquals(1, synchronized(copies) { copy(userA, episode).playCount })
    }

    @Test fun signingOutDropsOnlyThatProfilesWaitingChanges() {
        browse()
        goDown()
        runBlocking { graph.playstate.setPlayed(sA, episodes[0], true) }
        runBlocking { graph.playstate.setFavourite(sB, filmId, true) }
        assertEquals(1, outbox(userA).size)
        assertEquals(1, outbox(userB).size)

        // Servers and accounts -> Sign out bee: the dialog says one change has not reached the server.
        openServersFromHome()
        val signOutBee = string(R.string.sign_out_cd, "bee")
        waitFor { described(signOutBee) }
        compose.onAllNodes(hasContentDescription(signOutBee)).onFirst().also { runCatching { it.performScrollTo() } }.performClick()
        val waiting = compose.activity.resources.getQuantityString(R.plurals.sign_out_waiting, 1, "1")
        waitFor { shown(waiting) }
        compose.onAllNodes(hasText(string(R.string.sign_out)) and hasAnyAncestor(isDialog())).onFirst().performClick()

        // It tries to send first (the server is down), then signs out: B's rows are gone, A's untouched.
        waitFor(45_000) { outbox(userB).isEmpty() && graph.serverStore.cachedToken(serverId, userB) == null }
        assertEquals(listOf(ChangeKind.PLAYED), outbox(userA).map { it.first })
    }

    /**
     * A COMPLETE original of [itemId] for A, as the queue leaves one: `DownloadStore.add` (A's `visible_items` record is there
     * from browsing), then COMPLETE, then the clip copied to `DownloadPaths.mediaFile`. The row is COMPLETE before the file is
     * written, so the download queue (which takes only QUEUED rows) never touches it. Returns the item's folder.
     */
    private fun seedDownload(itemId: String): File {
        val clip = InstrumentationRegistry.getInstrumentation().context.assets.open("media/clip.mp4").use { it.readBytes() }
        val key = DownloadKey(serverId, itemId)
        runBlocking {
            val added = graph.downloadStore.add(
                sA,
                listOf(DownloadStore.NewDownload(itemId, ItemKind.EPISODE, seriesId, seasonId, DownloadQuality.ORIGINAL, StorageKind.INTERNAL, null, 10_000L, null, confirmed = true)),
            )
            check(added[itemId] == DownloadStore.Added.Queued) { "the download was not added" }
            val now = System.currentTimeMillis()
            check(
                graph.downloadStore.update(key) {
                    it.copy(
                        state = DownloadState.COMPLETE, mediaSourceId = itemId, container = "mp4", totalBytes = clip.size.toLong(), doneBytes = clip.size.toLong(),
                        completedAt = now, verifiedAt = now,
                    )
                },
            ) { "the download could not be completed" }
            graph.downloadStore.markFetcher(key, userA)
        }
        val folder = checkNotNull(graph.downloadPaths.itemFolder(key, StorageKind.INTERNAL, null)) { "no internal folder" }
        folder.mkdirs()
        graph.downloadPaths.mediaFile(folder, "mp4").writeBytes(clip)
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(DownloadScheduler.NAME).result.get(10, TimeUnit.SECONDS) }
        assertEquals(DownloadState.COMPLETE, runBlocking { graph.downloadStore.get(key) }?.state)
        return folder
    }

    /** Home's account button: with two accounts it opens a menu whose last entry is "Servers and accounts" (as AdminSmokeTest). */
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
        waitFor { shown(string(R.string.servers_title)) }
    }
}
