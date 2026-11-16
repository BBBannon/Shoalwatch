package app.shoalwatch.data.library

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.core.AppError
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/** A series page: the series, its seasons, and the episodes of the selected season, each with its own refresh. */
class LibraryRepositorySeriesTest : LibraryRepositoryTestBase() {
    private val show = id("show")

    private fun season(label: String, number: Int?) = row(item(label, ItemKind.SEASON, "Season $label", seriesId = "show", indexNumber = number))

    private fun ep(label: String, seasonNumber: Int?, number: Int, played: Boolean = false) =
        row(episode(label, seasonNumber, number), watched(played = played))

    private val season1 = season("season1", 1)
    private val season2 = season("season2", 2)
    private val specials = season("season0", 0)

    private fun serve(vararg seasons: ItemWithUserData, episodes: (String?) -> List<ItemWithUserData> = { emptyList() }) {
        gateway.itemFor = { row(item("show", ItemKind.SERIES, "The Show", overview = "About")) }
        gateway.seasonsFor = { seasons.toList() }
        gateway.episodesFor = { _, seasonId -> episodes(seasonId) }
    }

    private fun detail(seasonId: String? = null) = repo.series(s1u1, show, seasonId)

    private fun settled(seasonId: String? = null, test: (SeriesDetail) -> Boolean = { true }) =
        detail(seasonId).await { it.freshness == Freshness.Fresh && it.selectedSeasonId != null && it.series != null && test(it) }

    private fun saveEpisodes(vararg rows: ItemWithUserData) = runBlocking { library.writeItems(s1u1, rows.toList(), t0, requestedAtSeq = 0) }

    private fun saveSeasons(vararg rows: ItemWithUserData) =
        runBlocking { library.writePage(s1u1, ListKey.seasons(show), 0, rows.toList(), rows.size, t0, requestedAtSeq = 0) }

    @Test fun withNothingKnownItOpensOnTheFirstNumberedSeason() {
        serve(season1, season2, specials) { id -> if (id == season1.item.id) listOf(ep("e11", 1, 1), ep("e12", 1, 2)) else emptyList() }
        val d = settled { it.episodes.size == 2 }
        assertEquals(season1.item.id, d.selectedSeasonId)
        assertEquals(listOf("Season season1", "Season season2", "Season season0"), d.seasons.map { it.item.name })
        assertEquals(listOf("Episode e11", "Episode e12"), names(d.episodes))
        assertEquals("The Show", d.series!!.item.name)
        assertEquals("episodes:${season1.item.id}", gateway.calls.single { it.startsWith("episodes") })
    }

    @Test fun itOpensOnTheSeasonWithTheNextUnwatchedEpisodeWhenTheSavedCopyShowsIt() {
        saveSeasons(season1, season2)
        saveEpisodes(ep("e11", 1, 1, played = true), ep("e12", 1, 2, played = true), ep("e21", 2, 1, played = true), ep("e22", 2, 2))
        gateway.failAll = AppError.Kind.OFFLINE // nothing from the network: this is all from the phone
        val d = detail().await { it.selectedSeasonId != null }
        assertEquals(season2.item.id, d.selectedSeasonId)
    }

    @Test fun anUnwatchedSpecialDoesNotDecideTheSeason() {
        saveSeasons(season1, specials)
        saveEpisodes(ep("e11", 1, 1, played = true), ep("sp1", 0, 1))
        gateway.failAll = AppError.Kind.OFFLINE
        assertEquals(season1.item.id, detail().await { it.selectedSeasonId != null }.selectedSeasonId)
    }

    @Test fun withOnlySpecialsItOpensOnSpecials() {
        serve(specials)
        assertEquals(specials.item.id, settled().selectedSeasonId)
    }

    @Test fun aRequestedSeasonIsHonoured() {
        serve(season1, season2) { id -> if (id == season2.item.id) listOf(ep("e21", 2, 1)) else emptyList() }
        val d = settled(season2.item.id) { it.episodes.isNotEmpty() }
        assertEquals(season2.item.id, d.selectedSeasonId)
        assertEquals(listOf("Episode e21"), names(d.episodes))
    }

    @Test fun theChosenSeasonSticksWhenEpisodesAreMarkedWatched() = runBlocking {
        saveSeasons(season1, season2)
        saveEpisodes(ep("e11", 1, 1), ep("e21", 2, 1))
        gateway.failAll = AppError.Kind.OFFLINE
        val flow = detail()
        val seen = mutableListOf<String?>()
        val collector = launch { flow.collect { seen += it.selectedSeasonId } }
        until { seen.contains(season1.item.id) }
        // Season 1 is now fully watched, so "next unwatched" would be in season 2: the open screen must not jump.
        db.itemDao().setPlayed(S1, U1, id("e11"), true, 0, now)
        delay(200)
        collector.cancelAndJoin()
        assertEquals(setOf(season1.item.id), seen.filterNotNull().toSet())
    }

    @Test fun seasonsAndEpisodesEachRefreshOnTheirOwnAndAreSavedUnderTheirOwnKeys() {
        serve(season1) { listOf(ep("e11", 1, 1)) }
        settled { it.episodes.isNotEmpty() }
        assertEquals(1, gateway.count("seasons"))
        assertEquals(1, gateway.count("item"))
        assertEquals(1, count("list_state", "listKey = '${ListKey.seasons(show)}'"))
        assertEquals(1, count("list_state", "listKey = '${ListKey.episodes(show, season1.item.id)}'"))
        assertEquals(0, count("list_state", "listKey = '${ListKey.episodes(show, null)}'"))
    }

    @Test fun anEpisodeRemovedOnTheServerDisappearsAfterARefresh() = runBlocking {
        val live = mutableListOf(ep("e11", 1, 1), ep("e12", 1, 2))
        serve(season1) { live.toList() }
        detail().first5s { it.freshness == Freshness.Fresh && it.episodes.size == 2 }
        live.removeAt(1)
        repo.refresh(s1u1, RefreshTarget.Series(show))
        assertEquals(listOf("Episode e11"), names(detail().first5s { it.episodes.size == 1 }.episodes))
    }

    @Test fun oneFailingPartMakesTheWholeStaleAndKeepsTheRest() {
        serve(season1) { listOf(ep("e11", 1, 1)) }
        settled { it.episodes.isNotEmpty() }
        advance(Duration.ofMinutes(30))
        gateway.failOps["episodes"] = AppError.Kind.TIMEOUT
        val d = detail().await { it.freshness == Freshness.Stale(AppError.Kind.TIMEOUT) }
        assertEquals(listOf("Episode e11"), names(d.episodes))
        assertEquals(1, d.seasons.size)
        assertEquals("The Show", d.series!!.item.name)
    }

    @Test fun neverLoadedAndOfflineIsEmptyAndStale() {
        gateway.failAll = AppError.Kind.OFFLINE
        val d = detail().await { it.freshness is Freshness.Stale }
        assertEquals(Freshness.Stale(AppError.Kind.OFFLINE), d.freshness)
        assertEquals(emptyList<ItemWithUserData>(), d.seasons)
        assertNull(d.series)
        assertNull(d.selectedSeasonId)
    }

    @Test fun pullToRefreshRefreshesTheSeriesItsSeasonsAndTheSelectedSeasonsEpisodes() = runBlocking {
        serve(season1, season2) { listOf(ep("e11", 1, 1)) }
        repo.refresh(s1u1, RefreshTarget.Series(show))
        assertEquals(1, gateway.count("item"))
        assertEquals(1, gateway.count("seasons"))
        assertEquals("episodes:${season1.item.id}", gateway.calls.single { it.startsWith("episodes") })
        repo.refresh(s1u1, RefreshTarget.Series(show, season2.item.id))
        assertEquals("episodes:${season2.item.id}", gateway.calls.last { it.startsWith("episodes") })
    }

    @Test fun twoUsersKeepTheirOwnWatchedStateInTheSameSeries() {
        saveSeasons(season1)
        runBlocking { library.writePage(s1u2, ListKey.seasons(show), 0, listOf(season1), 1, t0, requestedAtSeq = 0) }
        saveEpisodes(ep("e11", 1, 1, played = true))
        gateway.failAll = AppError.Kind.OFFLINE
        runBlocking { library.writePage(s1u1, ListKey.episodes(show, season1.item.id), 0, listOf(ep("e11", 1, 1, played = true)), 1, t0, requestedAtSeq = 0) }
        val ann = detail().await { it.episodes.isNotEmpty() }
        val bob = repo.series(s1u2, show).await { it.selectedSeasonId != null }
        assertTrue(ann.episodes.single().userData!!.played)
        assertEquals(emptyList<ItemWithUserData>(), bob.episodes) // Bob has loaded nothing of his own
    }
}
