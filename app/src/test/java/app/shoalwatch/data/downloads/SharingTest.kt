package app.shoalwatch.data.downloads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.S1
import app.shoalwatch.S2
import app.shoalwatch.data.playback.fixtureInfo
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The shared-tablet rule through the `Downloads` seam (OfflineLibraryVisibilityTest's cases a to g again): a restricted
 * profile never sees, counts or learns of a download outside its own server permissions, with sharing on or off.
 * A = U1 (the parent, owner), B = U2 (the child), both on S1.
 */
class SharingTest : DownloadHousekeepingTestBase() {
    private val a get() = s1u1
    private val b get() = s1u2
    private val film get() = item("film")
    private val e1 get() = ep("e1", 1, 1)
    private lateinit var repo: DownloadRepository

    @Before fun setUpRepository() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = DownloadPrefs(context.getSharedPreferences("sharing-test", 0).also { it.edit().clear().commit() })
        val planner = DownloadPlanner(app.shoalwatch.ui.browse.FakeLibrary(), db.itemDao(), facts, { _, _ -> fixtureInfo("direct-play.json") }, db.downloadDao(), fake.volumes, prefs, kotlinx.coroutines.Dispatchers.Unconfined)
        repo = DownloadRepository(store, db.offlineLibraryDao(), db.itemDao(), planner, scheduler, prefs, fake.volumes, paths, scope, kotlinx.coroutines.Dispatchers.Unconfined)
    }

    /** A downloads the film and episode 1 (with its season and series). */
    private fun aDownloads(complete: Boolean = true, serverId: String = S1) {
        val s = Session(serverId, a.userId)
        seen(s, film, series, season1, e1)
        queued(s, film, e1)
        if (complete) complete(film, e1, serverId = serverId)
    }

    /** Everything [s] can learn of the film and the episode through the seam. */
    private fun seenBy(s: Session): Set<String> = runBlocking {
        buildSet {
            val lib = repo.library(s).first()
            if (lib.films.any { it.item.item.id == film.id }) add("films")
            if (lib.series.any { it.series.item.id == series.id }) add("series")
            if (lib.inProgress.isNotEmpty()) add("inProgress")
            if (repo.series(s, series.id).first().seasons.any { season -> season.episodes.any { it.item.item.id == e1.id } }) add("episodes")
            if (repo.status(s, film.id).first() != null) add("filmStatus")
            if (repo.status(s, e1.id).first() != null) add("episodeStatus")
            if (repo.seriesStatus(s, series.id).first().isNotEmpty()) add("seriesStatus")
        }
    }

    private val everything = setOf("films", "series", "episodes", "filmStatus", "episodeStatus", "seriesStatus")

    @Test fun aSharingOffTheChildSeesNothingEvenWithAVisibilityRecord() {
        aDownloads()
        seen(b, film, series, season1, e1)
        assertEquals(emptySet<String>(), seenBy(b))
        assertEquals(everything, seenBy(a))
    }

    @Test fun bSharingOnTheChildSeesItOnlyOnceCompleteAndNeverAsItsOwn() {
        aDownloads(complete = false)
        confirm(b, film, e1) // the strict sharing rule: the child's own permission is confirmed
        seen(b, film, series, season1, e1)
        runBlocking { repo.setSharing(b, true) }
        assertEquals("not while it is being downloaded", emptySet<String>(), seenBy(b))
        complete(film, e1)
        assertEquals(everything, seenBy(b))
        assertEquals(false, runBlocking { repo.status(b, film.id).first() }!!.ownedByMe)
        assertEquals("never in the child's queue", emptyList<OfflineEntry>(), runBlocking { repo.library(b).first().inProgress })
    }

    @Test fun cSharingOnWithNoRecordTheChildNeverSeesIt() {
        aDownloads()
        runBlocking { repo.setSharing(a, true) }
        seen(b, series, season1) // the server showed B the series, never the film or the episode
        assertEquals(emptySet<String>(), seenBy(b))
        assertEquals("not counted either", 0, runBlocking { repo.library(b).first().series.sumOf { it.episodeCount } })
    }

    @Test fun dTheEpisodesRecordWithoutTheSeriessGivesNoSeries() {
        aDownloads()
        confirm(b, film, e1) // the strict sharing rule: the child's own permission is confirmed
        runBlocking { repo.setSharing(a, true) }
        seen(b, e1, season1)
        assertEquals(setOf("episodeStatus"), seenBy(b))
    }

    @Test fun eARevokedRecordHidesEvenTheOwnersOwnDownload() {
        aDownloads()
        runBlocking { library.revokeVisibility(a, film.id) }
        assertFalse("films" in seenBy(a) || "filmStatus" in seenBy(a))
    }

    @Test fun fAnotherServersDownloadWithTheSameIdNeverAppears() {
        aDownloads(serverId = S2)
        seen(a, film, series, season1, e1)
        assertEquals(emptySet<String>(), seenBy(a))
    }

    @Test fun gTheWatchedStateShownIsTheChildsOwn() = runBlocking {
        aDownloads()
        confirm(b, film, e1) // the strict sharing rule: the child's own permission is confirmed
        repo.setSharing(a, true)
        seen(b, film, series, season1, e1)
        db.itemDao().setPlayed(S1, a.userId, film.id, true, 0, 5)
        assertEquals(true, repo.library(a).first().films.single().item.userData?.played)
        assertNull(repo.library(b).first().films.single().item.userData)
    }

    /**
     * The strict sharing rule (controller decision on the Tasks 9-10 review): with sharing on, a child shown the item sees a
     * shared copy only once the server has confirmed the child's OWN CanDownload; reconfirm asks, and a later "no" hides it again.
     */
    @Test fun hSharingOnTheChildSeesNothingUntilItsOwnPermissionIsConfirmed() = runBlocking {
        aDownloads()
        repo.setSharing(a, true)
        seen(b, film, series, season1, e1)
        assertEquals("unknown is not shared", emptySet<String>(), seenBy(b))
        facts.allow(film.id, canDownload = true)
        facts.allow(e1.id, canDownload = null)
        housekeeping.reconfirm(b)
        assertEquals(setOf("films", "filmStatus"), seenBy(b))
        facts.allow(film.id, canDownload = false)
        repeat(8) { // the child browses every day, so its visibility record never ages (Tasks 11-12 review I-1 c)
            clock += DAY
            db.itemDao().markVisible(S1, b.userId, listOf(film.id, series.id, season1.id, e1.id), clock)
        }
        housekeeping.reconfirm(b) // asked again because its CONFIRMATION is a week old
        assertEquals("a later no hides it again", emptySet<String>(), seenBy(b))
        assertEquals("the owner always sees its own", everything, seenBy(a))
    }

    @Test fun aConfirmationIsAskedAgainOnlyOnceItIsAWeekOldAndAReconfirmationRestartsItsAge() = runBlocking {
        aDownloads()
        repo.setSharing(a, true)
        seen(b, film, series, season1, e1)
        confirm(b, film, e1)
        clock += 3 * DAY
        db.itemDao().markVisible(S1, b.userId, listOf(film.id, e1.id), clock)
        housekeeping.reconfirm(b)
        assertTrue("three days old: not asked", facts.calls.isEmpty())
        clock += 5 * DAY
        db.itemDao().markVisible(S1, b.userId, listOf(film.id, e1.id), clock)
        facts.allow(film.id, canDownload = true); facts.allow(e1.id, canDownload = true)
        housekeeping.reconfirm(b)
        assertEquals("eight days old: asked", 1, facts.calls.size)
        clock += 3 * DAY
        db.itemDao().markVisible(S1, b.userId, listOf(film.id, e1.id), clock)
        housekeeping.reconfirm(b)
        assertEquals("confirmed again three days ago: not asked", 1, facts.calls.size)
    }

    @Test fun aPlanWhoseFactsSayNoRemovesTheChildsPermissionAtOnce() = runBlocking {
        aDownloads()
        repo.setSharing(a, true)
        seen(b, film, series, season1, e1)
        confirm(b, film)
        assertTrue("films" in seenBy(b))
        facts.allow(film.id, canDownload = false)
        val plan = repo.prepare(b, DownloadTarget.Item(film.id), DownloadQuality.ORIGINAL)
        assertEquals(listOf(SkipReason.NOT_ALLOWED), plan.skipped.map { it.reason })
        assertFalse(store.isConfirmed(key(film), b.userId))
        assertFalse("hidden from the child at once", "films" in seenBy(b))
    }

    @Test fun aRefusedItemDoesNotStopReconfirmOrItsPermissionCheck() = runBlocking {
        val films = (1..3).map { item("r$it") }
        seen(a, *films.toTypedArray()); queued(a, *films.toTypedArray()); complete(*films.toTypedArray())
        repo.setSharing(a, true)
        seen(b, *films.toTypedArray())
        films.forEach { confirm(b, it) }
        clock += 8 * DAY
        refused += films[0].id // its rating went up: a 403, which the real Library revokes and then throws
        facts.allow(films[1].id, canDownload = false)
        facts.allow(films[2].id, canDownload = true)
        housekeeping.reconfirm(b)
        assertEquals("every item is refreshed", films.map { it.id }.toSet(), refreshedIds.toSet())
        assertEquals("the permission check still runs", 1, facts.calls.size)
        assertFalse(store.isConfirmed(key(films[1]), b.userId))
        assertTrue(store.isConfirmed(key(films[2]), b.userId))
        assertNull(repo.status(b, films[0].id).first())
        assertNull(repo.status(b, films[1].id).first())
        assertTrue(repo.status(b, films[2].id).first() != null)
    }

    @Test fun aTransportFailureStopsReconfirmAndChangesNothing() = runBlocking {
        aDownloads()
        repo.setSharing(a, true)
        seen(b, film, series, season1, e1)
        confirm(b, film)
        clock += 8 * DAY
        refreshFails = app.shoalwatch.core.AppError.of(app.shoalwatch.core.AppError.Kind.OFFLINE)
        housekeeping.reconfirm(b)
        assertTrue("no facts call", facts.calls.isEmpty())
        assertTrue(store.isConfirmed(key(film), b.userId))
    }

    @Test fun aConfirmationThatCannotBeReAskedOfflineStillCounts() = runBlocking {
        aDownloads()
        repo.setSharing(a, true)
        seen(b, film, series, season1, e1)
        confirm(b, film)
        clock += 30 * DAY
        facts.failWith = app.shoalwatch.core.AppError.of(app.shoalwatch.core.AppError.Kind.OFFLINE)
        housekeeping.reconfirm(b)
        assertTrue("on a plane, the child still watches", "films" in seenBy(b))
    }

    @Test fun setSharingEmits() = runBlocking {
        val values = mutableListOf<Boolean>()
        val first = kotlinx.coroutines.CompletableDeferred<Unit>()
        val seen = async { repo.sharing(S1).take(2).collect { values += it; first.complete(Unit) } }
        first.await() // the first value is read before anything changes
        repo.setSharing(b, true)
        kotlinx.coroutines.withTimeout(5_000) { seen.await() }
        assertEquals(listOf(false, true), values)
    }

    @Test fun reconfirmAsksAboutAtMostFiftyStaleSharedItemsAndARefusalHidesOnlyFromTheChild() = runBlocking {
        val films = (1..60).map { item("f$it") }
        seen(a, *films.toTypedArray())
        queued(a, *films.toTypedArray())
        complete(*films.toTypedArray())
        repo.setSharing(a, true)
        seen(b, *films.toTypedArray()) // confirmed at t0
        db.itemDao().markVisible(S1, b.userId, listOf(films[0].id), t0.toEpochMilli() - 1_000) // the oldest record is asked first
        val fresh = item("fresh")
        queued(a, fresh); complete(fresh)
        clock = t0.toEpochMilli() + 8L * 24 * 60 * 60 * 1_000 // eight days on
        db.itemDao().markVisible(S1, b.userId, listOf(fresh.id), clock) // confirmed today
        refused += films[0].id
        housekeeping.reconfirm(b)
        assertEquals(50, refreshedIds.size)
        assertFalse("a fresh record is not asked about", fresh.id in refreshedIds)
        assertNull("the refusal hides it from the child at once", repo.status(b, films[0].id).first())
        assertTrue("the owner still sees it", repo.status(a, films[0].id).first() != null)
        refreshedIds.clear()
        housekeeping.reconfirm(a)
        assertTrue("the owner's own downloads are not shared to it", refreshedIds.isEmpty())
    }

    private companion object { const val DAY = 24L * 60 * 60 * 1_000 }
}
