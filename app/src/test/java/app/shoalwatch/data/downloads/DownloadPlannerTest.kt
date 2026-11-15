package app.shoalwatch.data.downloads

import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.S1
import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.DownloadTestBase
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemFilter
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.library.ListShape
import app.shoalwatch.data.library.RefreshTarget
import app.shoalwatch.data.library.Sort
import app.shoalwatch.data.playback.api.PlaybackInfo
import app.shoalwatch.data.playback.fixtureInfo
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What one press plans: fake gateway, check and volumes; a fake library writing what "the server" shows into real Room. */
class DownloadPlannerTest : DownloadTestBase() {
    @get:Rule val tmp = TemporaryFolder()

    private val a get() = s1u1
    private val gateway = FakeDownloadGateway()
    private lateinit var fake: FakeVolumes
    private lateinit var prefs: DownloadPrefs

    /** What the server would return for each refresh, written to Room for the session (with visibility), as the real one does. */
    private val server = mutableMapOf<RefreshTarget, List<ItemWithUserData>>()
    private val refreshed = mutableListOf<RefreshTarget>()
    private val fakeLibrary = object : Library {
        override suspend fun refresh(s: Session, target: RefreshTarget) {
            refreshed += target
            server[target]?.let { rows -> library.writeItems(s, rows, t0, requestedAtSeq = 0) }
        }
        override fun home(s: Session) = error("not used")
        override fun children(s: Session, parentId: String, shape: ListShape, sort: Sort, filter: ItemFilter) = error("not used")
        override suspend fun loadMore(s: Session, parentId: String, shape: ListShape, sort: Sort, filter: ItemFilter) = error("not used")
        override fun series(s: Session, seriesId: String, seasonId: String?) = error("not used")
        override fun item(s: Session, itemId: String) = error("not used")
        override suspend fun search(s: Session, term: String) = error("not used")
    }

    /** checkOriginal: per item id, else direct play; [inFlight]/[maxInFlight] count overlapping calls (each takes 1 s of virtual time). */
    private val originals = mutableMapOf<String, () -> PlaybackInfo>()
    private val checked = mutableListOf<String>()
    private var inFlight = 0
    private var maxInFlight = 0
    private val check: suspend (Session, String) -> PlaybackInfo = { _, id ->
        checked += id
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        try {
            delay(1_000)
            (originals[id] ?: { fixtureInfo("direct-play.json") })()
        } finally {
            inFlight--
        }
    }

    @Before fun setUpPlanner() {
        fake = FakeVolumes(tmp.root).also { it.free[null] = 1L shl 40 }
        prefs = DownloadPrefs(ApplicationProvider.getApplicationContext<android.content.Context>().getSharedPreferences("planner-test", 0).also { it.edit().clear().commit() })
    }

    private fun TestScope.planner() = DownloadPlanner(fakeLibrary, db.itemDao(), gateway, check, db.downloadDao(), fake.volumes, prefs, StandardTestDispatcher(testScheduler))

    private val specials get() = item("season0", ItemKind.SEASON, "Specials", seriesId = "show", parentId = "show", indexNumber = 0)

    /** The server shows [s1] the series and its seasons for every series refresh, and each season's episodes for its own. */
    private fun serverSeries(episodes: List<Item>, watchedIds: Set<String> = emptySet(), seasons: List<Item> = listOf(season1, season2, specials)) {
        val rows = { list: List<Item> -> list.map { row(it, if (it.id in watchedIds) watched(played = true) else watched()) } }
        val head = rows(listOf(series) + seasons)
        server[RefreshTarget.Series(series.id)] = head
        for (season in seasons) server[RefreshTarget.Series(series.id, season.id)] = head + rows(episodes.filter { it.seasonId == season.id })
        episodes.forEach { gateway.allow(it.id) }
    }

    private suspend fun failure(block: suspend () -> Unit): AppError {
        try {
            block()
        } catch (e: AppError) {
            return e
        }
        fail("expected an AppError")
        error("unreachable")
    }

    @Test fun aSeasonOfSixWithOneNotAllowedPlansFiveAndSkipsOne() = runTest {
        val eps = (1..6).map { ep("e$it", 1, it) }
        serverSeries(eps)
        gateway.allow(eps[2].id, canDownload = false)
        val plan = planner().plan(a, DownloadTarget.Season(series.id, season1.id), DownloadQuality.ORIGINAL)
        assertEquals(eps.map { it.id } - eps[2].id, plan.items.map { it.itemId })
        assertEquals(listOf(Skipped(eps[2].id, SkipReason.NOT_ALLOWED)), plan.skipped)
        assertTrue(plan.items.all { it.quality == DownloadQuality.ORIGINAL && !it.shareOnly })
        assertFalse("a refused item is not checked", eps[2].id in checked)
        assertEquals(listOf<RefreshTarget>(RefreshTarget.Series(series.id, season1.id)), refreshed)
    }

    @Test fun unknownCanDownloadAllowsTheOriginalOnly() = runTest {
        val eps = (1..3).map { ep("e$it", 1, it) }
        serverSeries(eps)
        eps.forEach { gateway.allow(it.id, canDownload = null) }
        originals[eps[2].id] = { fixtureInfo("transcode.json") }
        val original = planner().plan(a, DownloadTarget.Season(series.id, season1.id), DownloadQuality.ORIGINAL)
        assertEquals(eps.take(2).map { it.id }, original.items.map { it.itemId })
        assertEquals("an original that cannot play here is not converted", listOf(Skipped(eps[2].id, SkipReason.CONVERSION_UNCONFIRMED)), original.skipped)
        assertEquals(0, original.convertedInstead)
        val high = planner().plan(a, DownloadTarget.Season(series.id, season1.id), DownloadQuality.HIGH)
        assertEquals(emptyList<PlannedItem>(), high.items)
        assertEquals(eps.map { Skipped(it.id, SkipReason.CONVERSION_UNCONFIRMED) }, high.skipped)
    }

    @Test fun seriesLeavesSpecialsOutAndUnwatchedAlsoLeavesWatchedOut() = runTest {
        val eps = listOf(ep("e1", 1, 1), ep("e2", 1, 2), ep("f1", 2, 1), ep("sp1", 0, 1))
        serverSeries(eps, watchedIds = setOf(eps[1].id))
        val all = planner().plan(a, DownloadTarget.Series(series.id), DownloadQuality.HIGH)
        assertEquals(listOf(eps[0], eps[1], eps[2]).map { it.id }, all.items.map { it.itemId })
        val unwatched = planner().plan(a, DownloadTarget.Unwatched(series.id), DownloadQuality.HIGH)
        assertEquals(listOf(eps[0], eps[2]).map { it.id }, unwatched.items.map { it.itemId })
        assertTrue("the specials season is never refreshed for a series", RefreshTarget.Series(series.id, specials.id) !in refreshed)
        assertTrue(RefreshTarget.Series(series.id) in refreshed)
    }

    @Test fun anOriginalThatCannotPlayHereIsPlannedAsHigh() = runTest {
        val film = item("film")
        server[RefreshTarget.Item(film.id)] = listOf(row(film))
        gateway.allow(film.id, sizeBytes = 999)
        originals[film.id] = { fixtureInfo("transcode.json") }
        val plan = planner().plan(a, DownloadTarget.Item(film.id), DownloadQuality.ORIGINAL)
        assertEquals(listOf(DownloadQuality.HIGH), plan.items.map { it.quality })
        assertEquals(1, plan.convertedInstead)
        assertEquals("a conversion's size is the estimate, not the original's", 8_000_000L / 8 * 5_400, plan.items.single().estimatedBytes)
    }

    @Test fun sizesComeFromTheServerElseTheCheckElseAnEstimate() = runTest {
        val sized = item("sized")
        val checkOnly = item("checkonly")
        val neither = item("neither")
        val noRuntime = item("noruntime").copy(runtimeMs = null)
        for (it in listOf(sized, checkOnly, neither, noRuntime)) server[RefreshTarget.Item(it.id)] = listOf(row(it))
        gateway.allow(sized.id, sizeBytes = 1_000)
        gateway.allow(checkOnly.id)
        gateway.allow(neither.id)
        gateway.allow(noRuntime.id)
        val unsized = fixtureInfo("direct-play.json").let { info -> { info.copy(sources = info.sources.map { it.copy(sizeBytes = null) }) } }
        originals[neither.id] = unsized
        originals[noRuntime.id] = unsized
        suspend fun original(it: Item) = planner().plan(a, DownloadTarget.Item(it.id), DownloadQuality.ORIGINAL).items.single().estimatedBytes
        assertEquals(1_000L, original(sized))
        assertEquals(1_234_567_890L, original(checkOnly))
        assertEquals("90 min at 25 Mbps", 25_000_000L / 8 * 5_400, original(neither))
        assertEquals("unknown runtime counts as 45 min", 25_000_000L / 8 * 2_700, original(noRuntime))
        val low = planner().plan(a, DownloadTarget.Item(noRuntime.id), DownloadQuality.LOW)
        assertEquals(1_500_000L / 8 * 2_700, low.items.single().estimatedBytes)
        assertEquals(low.items.single().estimatedBytes, low.totalBytes)
    }

    @Test fun theTotalIsSummedAndTooLittleSpaceIsSaid() = runTest {
        val eps = (1..3).map { ep("e$it", 1, it) }
        serverSeries(eps)
        eps.forEach { gateway.allow(it.id, sizeBytes = 100L shl 20) }
        fake.free[null] = (300L shl 20) + (1L shl 30) // exactly the total plus the 1 GiB internal reserve
        val fits = planner().plan(a, DownloadTarget.Season(series.id, season1.id), DownloadQuality.ORIGINAL)
        assertEquals(300L shl 20, fits.totalBytes)
        assertEquals(fake.free[null], fits.freeBytes)
        assertTrue(fits.enoughSpace)
        assertEquals(StorageKind.INTERNAL, fits.storage)
        assertFalse("Wi-Fi only by default", fits.mobileData)
        fake.free[null] = fake.free[null]!! - 1
        assertFalse(planner().plan(a, DownloadTarget.Season(series.id, season1.id), DownloadQuality.ORIGINAL).enoughSpace)
    }

    @Test fun anEpisodeAlreadyDownloadedByAnotherProfileIsShareOnly() = runTest {
        val eps = (1..2).map { ep("e$it", 1, it) }
        serverSeries(eps)
        seen(s1u2, series, season1, *eps.toTypedArray())
        store.add(s1u2, listOf(newDownload(eps[0])))
        val plan = planner().plan(a, DownloadTarget.Season(series.id, season1.id), DownloadQuality.ORIGINAL)
        assertEquals(listOf(PlannedItem(eps[0].id, DownloadQuality.ORIGINAL, 0, shareOnly = true, confirmed = true)), plan.items.filter { it.shareOnly })
        assertFalse("nothing to check for a copy already here", eps[0].id in checked)
        assertEquals(1_234_567_890L, plan.totalBytes)
        gateway.allow(eps[0].id, canDownload = false)
        assertEquals("this profile's own permission counts", listOf(Skipped(eps[0].id, SkipReason.NOT_ALLOWED)),
            planner().plan(a, DownloadTarget.Season(series.id, season1.id), DownloadQuality.ORIGINAL).skipped)
    }

    /**
     * Tasks 5-8 review I-1 (the shared-tablet rule): a parent's copy is shared with another profile only once the server has
     * confirmed THAT profile's own CanDownload. Unknown is not a confirmation: sharing never calls /Download, so the server
     * never gets to refuse it. The child here has the server's download permission off, and the server leaves the field out.
     */
    @Test fun aCopyAlreadyHereIsNotSharedWithAProfileWhosePermissionTheServerDidNotConfirm() = runTest {
        val parent = s1u2
        val child = a
        val eps = (1..2).map { ep("e$it", 1, it) }
        serverSeries(eps)
        seen(parent, series, season1, *eps.toTypedArray())
        store.add(parent, listOf(newDownload(eps[0])))
        gateway.allow(eps[0].id, canDownload = null)
        val plan = planner().plan(child, DownloadTarget.Season(series.id, season1.id), DownloadQuality.ORIGINAL)
        assertTrue("no share without a confirmed yes", plan.items.none { it.shareOnly })
        assertEquals(listOf(Skipped(eps[0].id, SkipReason.PERMISSION_UNCONFIRMED)), plan.skipped)
        assertFalse("nothing is checked or planned for it", eps[0].id in checked || plan.items.any { it.itemId == eps[0].id })
        gateway.allow(eps[0].id, canDownload = true)
        assertEquals("a confirmed yes shares it", listOf(eps[0].id),
            planner().plan(child, DownloadTarget.Season(series.id, season1.id), DownloadQuality.ORIGINAL).items.filter { it.shareOnly }.map { it.itemId })
    }

    /** Tasks 5-8 review Minor 7: a runtime of 0 is unknown (45 minutes), never a 0-byte estimate that passes the space check. */
    @Test fun aZeroRuntimeIsAnUnknownRuntime() = runTest {
        val film = item("zero").copy(runtimeMs = 0)
        server[RefreshTarget.Item(film.id)] = listOf(row(film))
        gateway.allow(film.id)
        assertEquals(1_500_000L / 8 * 2_700, planner().plan(a, DownloadTarget.Item(film.id), DownloadQuality.LOW).items.single().estimatedBytes)
        originals[film.id] = fixtureInfo("direct-play.json").let { info -> { info.copy(sources = info.sources.map { it.copy(sizeBytes = null) }) } }
        assertEquals(25_000_000L / 8 * 2_700, planner().plan(a, DownloadTarget.Item(film.id), DownloadQuality.ORIGINAL).items.single().estimatedBytes)
    }

    @Test fun sixHundredEpisodesPlansTheFirstFiveHundred() = runTest {
        val eps = (1..600).map { ep("e$it", 1, it) }
        serverSeries(eps, seasons = listOf(season1))
        val plan = planner().plan(a, DownloadTarget.Season(series.id, season1.id), DownloadQuality.MEDIUM)
        assertEquals(eps.take(500).map { it.id }, plan.items.map { it.itemId })
        assertTrue(plan.capped)
        assertEquals(10, gateway.calls.size)
    }

    @Test fun offlineThrowsOfflineAndQueuesNothing() = runTest {
        val eps = (1..2).map { ep("e$it", 1, it) }
        serverSeries(eps)
        seen(a, series, season1, *eps.toTypedArray()) // saved from an earlier visit
        gateway.failWith = AppError.of(AppError.Kind.OFFLINE)
        assertEquals(AppError.Kind.OFFLINE, failure { planner().plan(a, DownloadTarget.Season(series.id, season1.id), DownloadQuality.ORIGINAL) }.kind)
        assertEquals(0, count("downloads"))
        server.clear()
        assertEquals("with nothing saved either, it is still OFFLINE", AppError.Kind.OFFLINE,
            failure { planner().plan(a, DownloadTarget.Series(testIdOf("other")), DownloadQuality.ORIGINAL) }.kind)
    }

    @Test fun atMostFourChecksAreInFlight() = runTest {
        val eps = (1..12).map { ep("e$it", 1, it) }
        serverSeries(eps)
        val plan = planner().plan(a, DownloadTarget.Season(series.id, season1.id), DownloadQuality.ORIGINAL)
        assertEquals(12, plan.items.size)
        assertEquals(4, maxInFlight)
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test fun everyPlannedItemIsVisibleToTheSessionAfterwardsAndAnEpisodeRefreshesItsSeries() = runTest {
        val e1 = ep("e1", 1, 1)
        serverSeries(listOf(e1))
        server[RefreshTarget.Item(e1.id)] = listOf(row(e1))
        val plan = planner().plan(a, DownloadTarget.Item(e1.id), DownloadQuality.ORIGINAL)
        assertEquals(listOf(RefreshTarget.Item(e1.id), RefreshTarget.Series(series.id, season1.id)), refreshed)
        for (id in plan.items.map { it.itemId } + series.id + season1.id) assertNotNull(db.itemDao().getItem(S1, a.userId, id))
        assertEquals(0, count("downloads"))
    }

    @Test fun anItemTheServerDidNotReturnIsNotFound() = runTest {
        val film = item("film")
        server[RefreshTarget.Item(film.id)] = listOf(row(film))
        val plan = planner().plan(a, DownloadTarget.Item(film.id), DownloadQuality.ORIGINAL)
        assertEquals(listOf(Skipped(film.id, SkipReason.NOT_FOUND)), plan.skipped)
    }

    @Test fun aRefusalFromTheCheckIsNotAllowed() = runTest {
        val film = item("film")
        server[RefreshTarget.Item(film.id)] = listOf(row(film))
        gateway.allow(film.id)
        originals[film.id] = { throw AppError.of(AppError.Kind.FORBIDDEN) }
        assertEquals(listOf(Skipped(film.id, SkipReason.NOT_ALLOWED)), planner().plan(a, DownloadTarget.Item(film.id), DownloadQuality.ORIGINAL).skipped)
        originals[film.id] = { fixtureInfo("refused.json") }
        assertEquals(listOf(Skipped(film.id, SkipReason.NOT_ALLOWED)), planner().plan(a, DownloadTarget.Item(film.id), DownloadQuality.ORIGINAL).skipped)
    }

    @Test fun thePlanNeverPrintsItsContents() = runTest {
        val film = item("film")
        server[RefreshTarget.Item(film.id)] = listOf(row(film))
        gateway.allow(film.id)
        assertEquals("DownloadPlan(items=1)", planner().plan(a, DownloadTarget.Item(film.id), DownloadQuality.ORIGINAL).toString())
    }

    private fun testIdOf(label: String) = app.shoalwatch.testId(label)
}
