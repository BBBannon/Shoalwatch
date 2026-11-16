package app.shoalwatch.data.playback

import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.SeriesDetail
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.FakeLibrary
import app.shoalwatch.ui.browse.entry
import app.shoalwatch.ui.browse.episode
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.browse.item
import kotlinx.coroutines.async
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Previous and next episode from what the Library shows this user: across numbered seasons, in the app's season order; Specials stay apart. */
class EpisodeNavigatorTest {
    private val library = FakeLibrary()
    private val navigator = EpisodeNavigator(library)
    private val series = id(50)

    private fun season(n: Int, index: Int) = ItemWithUserData(item(60 + n, ItemKind.SEASON, "Season $index", seriesId = series, index = index), null)
    private val s1 = season(1, 1)
    private val s2 = season(2, 2)
    private val specials = season(0, 0)
    private val seasons = listOf(s1, s2, specials) // the app's order: Specials last
    private val s1e1 = episode(101, season = 1, number = 1)
    private val s1e2 = episode(102, season = 1, number = 2)
    private val s2e1 = episode(201, season = 2, number = 1)
    private val s2e2 = episode(202, season = 2, number = 2)
    private val sp1 = episode(301, season = 0, number = 1)
    private val sp2 = episode(302, season = 0, number = 2)

    private fun saved(season: ItemWithUserData, vararg episodes: ItemWithUserData, freshness: Freshness = Freshness.Fresh) {
        library.seriesFlow(BEN, series, season.item.id).value = SeriesDetail(null, seasons, episodes.toList(), season.item.id, freshness)
    }

    init {
        saved(s1, s1e1, s1e2)
        saved(s2, s2e1, s2e2)
        saved(specials, sp1)
    }

    @Test fun withinASeasonItIsTheNeighbours() = runTest {
        assertEquals(EpisodeNavigator.Around(null, s1e2.item), navigator.around(BEN, s1e1.item))
        assertEquals(s1e1.item, navigator.around(BEN, s1e2.item).previous)
    }

    @Test fun nextCrossesIntoTheNextSeasonsFirstEpisodeAndPreviousBack() = runTest {
        assertEquals(s2e1.item, navigator.around(BEN, s1e2.item).next)
        assertEquals(s1e2.item, navigator.around(BEN, s2e1.item).previous)
    }

    @Test fun theLastEpisodeOfTheLastNumberedSeasonHasNoNextEvenWithSpecials() = runTest {
        assertEquals(EpisodeNavigator.Around(s2e1.item, null), navigator.around(BEN, s2e2.item))
    }

    @Test fun withinSpecialsNextAndPreviousStayInSpecials() = runTest {
        saved(specials, sp1, sp2)
        assertEquals(EpisodeNavigator.Around(null, sp2.item), navigator.around(BEN, sp1.item))
        assertEquals(EpisodeNavigator.Around(sp1.item, null), navigator.around(BEN, sp2.item))
    }

    @Test fun aRefreshingEmissionIsWaitedPastAndTheSavedAnswerUsed() = runTest {
        saved(s1, s1e1, s1e2, freshness = Freshness.Refreshing)
        val answer = backgroundScope.async { navigator.around(BEN, s1e1.item) }
        runCurrent()
        saved(s1, s1e1, s1e2)
        assertEquals(s1e2.item, answer.await().next)
    }

    @Test fun aSeriesNeverCachedAndOfflineGivesNothingWithinFiveSeconds() = runTest {
        val other = episode(401, seriesN = 70)
        val started = currentTime
        assertEquals(EpisodeNavigator.Around(null, null), navigator.around(BEN, other.item))
        assertTrue(currentTime - started <= 5_000)
    }

    @Test fun anEpisodeTheLibraryDoesNotShowThisUserHasNoNeighbours() = runTest {
        assertEquals(EpisodeNavigator.Around(null, null), navigator.around(BEN, episode(999, season = 1).item))
    }

    @Test fun aFilmHasNoNeighbours() = runTest {
        assertEquals(EpisodeNavigator.Around(null, null), navigator.around(BEN, entry(5).item))
    }
}
