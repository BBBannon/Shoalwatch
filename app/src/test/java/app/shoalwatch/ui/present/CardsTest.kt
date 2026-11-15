package app.shoalwatch.ui.present

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.R
import app.shoalwatch.core.AppError
import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.LibraryKind
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.FILMS
import app.shoalwatch.ui.browse.entry
import app.shoalwatch.ui.browse.episode
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.browse.item
import app.shoalwatch.ui.browse.userData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CardsTest {
    private val strings = Strings(ApplicationProvider.getApplicationContext<Context>().resources)

    // --- durations ---------------------------------------------------------------------------------------------

    @Test fun durationsReadPlainly() {
        assertEquals("1 hr 42 min", formatDuration(strings, 102 * 60_000L))
        assertEquals("45 min", formatDuration(strings, 45 * 60_000L))
        assertEquals("2 hr", formatDuration(strings, 120 * 60_000L))
        assertEquals("Under 1 min", formatDuration(strings, 10_000))
        assertEquals("Under 1 min", formatDuration(strings, -5))
        assertEquals("1 min", formatDuration(strings, 31_000))
    }

    @Test fun remainingTimeNeedsALengthAndARealPosition() {
        assertEquals("23 min left", remainingLabel(strings, 100 * 60_000L, 77 * 60_000L))
        assertNull(remainingLabel(strings, null, 5))
        assertNull(remainingLabel(strings, 100_000, 0))
        assertNull(remainingLabel(strings, 100_000, 100_000))
        assertNull(remainingLabel(strings, 0, 5))
    }

    @Test fun resumeLabelOnlyWhenStarted() {
        assertEquals("Resume from 23 min", resumeLabel(strings, 23 * 60_000L))
        assertNull(resumeLabel(strings, 0))
    }

    @Test fun percentComesFromTheServerThenFromThePosition() {
        val film = item(1, runtimeMs = 1_000_000)
        assertEquals(42, percentWatched(film, userData(percent = 42.4f)))
        assertEquals(25, percentWatched(film, userData(positionMs = 250_000)))
        assertEquals(1, percentWatched(film, userData(percent = 0.2f)))
        assertEquals(99, percentWatched(film, userData(percent = 99.9f)))
        assertNull(percentWatched(film, userData(played = true, percent = 50f)))
        assertNull(percentWatched(film, userData()))
        assertNull(percentWatched(film, null))
        assertNull(percentWatched(film, userData(percent = Float.NaN)))
    }

    @Test fun episodeCodesAreLocaleIndependent() {
        val ep = episode(5, season = 2, number = 5).item
        assertEquals("S2 E5", episodeCode(strings, ep))
        assertEquals("E5", episodeCode(strings, ep.copy(parentIndexNumber = null)))
        assertEquals("S2", episodeCode(strings, ep.copy(indexNumber = null)))
        assertNull(episodeCode(strings, ep.copy(indexNumber = null, parentIndexNumber = null)))
        assertNull(episodeCode(strings, item(1)))
    }

    // --- poster card -------------------------------------------------------------------------------------------

    @Test fun aWatchedFilmCardIsLabelledWithItsState() {
        val card = posterCard(entry(1, name = "The Example Film", data = userData(played = true)), BEN, strings)
        assertEquals("The Example Film, 2019, watched", card.label)
        assertTrue(card.watched)
        assertEquals("More actions for The Example Film", card.menuLabel)
        assertEquals("2019", card.subtitle)
        assertNotNull(card.artwork)
    }

    @Test fun anUnwatchedSeriesCardShowsTheCountAndSpeaksIt() {
        val series = entry(2, ItemKind.SERIES, "Tidal", data = userData(unplayed = 7))
        val card = posterCard(series, BEN, strings)
        assertEquals(7, card.unwatchedCount)
        assertEquals("Tidal, 2019, 7 unwatched episodes", card.label)
        assertEquals("1 unwatched episode", strings.plural(R.plurals.unwatched_episodes, 1))
    }

    @Test fun aFinishedSeriesHasATickAndNoCount() {
        val card = posterCard(entry(2, ItemKind.SERIES, "Tidal", data = userData(played = true, unplayed = 0)), BEN, strings)
        assertNull(card.unwatchedCount)
        assertTrue(card.watched)
    }

    @Test fun aFavouriteIsSpokenNotJustShown() {
        assertTrue(posterCard(entry(1, name = "X", data = userData(favourite = true)), BEN, strings).label.endsWith("favourite"))
    }

    @Test fun aNamelessItemIsUntitledAndAnItemWithoutDataStillWorks() {
        val card = posterCard(ItemWithUserData(item(3, name = ""), null), BEN, strings)
        assertEquals("Untitled", card.title)
        assertFalse(card.watched)
        assertEquals("Untitled, 2019", card.label)
    }

    @Test fun noYearMeansNoYearInTheLabel() {
        assertEquals("Item 1", posterCard(entry(1, year = null), BEN, strings).label)
    }

    @Test fun noPictureMeansNoRef() {
        val noArt = ItemWithUserData(item(4, art = false), null)
        assertNull(posterCard(noArt, BEN, strings).artwork)
        assertNull(wideCard(noArt, BEN, strings).artwork)
    }

    // --- wide card ---------------------------------------------------------------------------------------------

    @Test fun anEpisodeWideCardShowsTheSeriesAboveTheEpisodeCode() {
        val card = wideCard(episode(7, season = 2, number = 5, name = "The Tide", data = userData(percent = 42f)), BEN, strings)
        assertEquals("Series 50", card.title)
        assertEquals("S2 E5 · The Tide", card.subtitle)
        assertEquals("42% watched", card.percentText)
        assertEquals(0.42f, card.progress!!, 0.001f)
        assertEquals("Series 50, S2 E5 · The Tide, 42% watched", card.label)
    }

    @Test fun aFilmWideCardShowsTimeLeft() {
        val film = entry(8, name = "Long Film", data = userData(positionMs = 77 * 60_000L))
            .let { it.copy(item = it.item.copy(runtimeMs = 100 * 60_000L)) }
        val card = wideCard(film, BEN, strings)
        assertEquals("Long Film", card.title)
        assertEquals("23 min left", card.subtitle)
        assertEquals(77, card.percentText!!.removeSuffix("% watched").toInt())
    }

    @Test fun aFilmNotStartedShowsItsYear() {
        assertEquals("2019", wideCard(entry(8), BEN, strings).subtitle)
    }

    @Test fun anEpisodeWithoutASeriesNameUsesItsOwnTitle() {
        val ep = episode(9, name = "Solo").let { it.copy(item = it.item.copy(seriesName = null)) }
        assertEquals("Solo", wideCard(ep, BEN, strings).title)
    }

    // --- episode row -------------------------------------------------------------------------------------------

    @Test fun anEpisodeRowHasNumberTitleRuntimeAndPlainOverview() {
        val ep = episode(10, number = 5, name = "The Tide").let { it.copy(item = it.item.copy(overview = "Waves<br>and <b>tides</b> &amp; more", runtimeMs = 47 * 60_000L)) }
        val row = episodeRow(ep, BEN, strings)
        assertEquals("E5 · The Tide", row.title)
        assertEquals("47 min", row.runtime)
        assertEquals("Waves\nand tides & more", row.overview)
        assertEquals("E5 · The Tide, 47 min", row.label)
        assertEquals("More actions for The Tide", row.menuLabel)
    }

    @Test fun aWatchedEpisodeIsSpoken() {
        assertTrue(episodeRow(episode(10, data = userData(played = true)), BEN, strings).label.endsWith("watched"))
    }

    // --- search rows and labels ---------------------------------------------------------------------------------

    @Test fun searchRowsSayWhatKindOfResultTheyAre() {
        assertEquals("2019", searchRow(entry(1), BEN, strings).subtitle)
        assertEquals("Series 50 · S1 E3", searchRow(episode(3, number = 3), BEN, strings).subtitle)
        assertEquals("Series · 2019", searchRow(entry(2, ItemKind.SERIES), BEN, strings).subtitle)
        assertEquals("Item 1, 2019", searchRow(entry(1), BEN, strings).label)
    }

    @Test fun libraryKindsAndTotalsReadPlainly() {
        assertEquals("Films", LibraryKind.MOVIES.label(strings))
        assertEquals("TV shows", LibraryKind.TV.label(strings))
        assertEquals("412 films", totalLabel(strings, LibraryKind.MOVIES, 412))
        assertEquals("1 film", totalLabel(strings, LibraryKind.MOVIES, 1))
        assertEquals("12 series", totalLabel(strings, LibraryKind.TV, 12))
        assertEquals("3 items", totalLabel(strings, LibraryKind.MIXED, 3))
        assertNull(totalLabel(strings, LibraryKind.MOVIES, null))
        assertEquals(id(1), FILMS.id)
    }

    // --- destinations and freshness ---------------------------------------------------------------------------

    @Test fun tappingGoesToTheRightPlace() {
        assertEquals(Destination.Series(id(2)), entry(2, ItemKind.SERIES).destination())
        assertEquals(Destination.Detail(id(1)), entry(1).destination())
        val ep = episode(7, season = 2)
        assertEquals(Destination.Series(id(50), id(62), id(7)), ep.destination())
        assertEquals(Destination.Detail(id(7)), ep.copy(item = ep.item.copy(seriesId = null)).destination())
        assertEquals(Destination.Library(id(5)), entry(5, ItemKind.BOX_SET).destination())
        assertEquals(Destination.Library(id(5)), entry(5, ItemKind.FOLDER).destination())
        val season = entry(6, ItemKind.SEASON).let { it.copy(item = it.item.copy(seriesId = id(50))) }
        assertEquals(Destination.Series(id(50), id(6)), season.destination())
    }

    @Test fun everyFreshnessMapsToABanner() {
        assertEquals(FreshnessUi(), Freshness.Fresh.ui())
        assertEquals(FreshnessUi(refreshing = true), Freshness.Refreshing.ui())
        fun banner(k: AppError.Kind) = Freshness.Stale(k).ui().banner
        listOf(AppError.Kind.OFFLINE, AppError.Kind.NETWORK, AppError.Kind.TIMEOUT).forEach { assertEquals(BannerKind.OFFLINE, banner(it)) }
        listOf(AppError.Kind.UNAUTHORISED, AppError.Kind.NOT_SIGNED_IN).forEach { assertEquals(BannerKind.SIGN_IN, banner(it)) }
        listOf(AppError.Kind.TLS_UNTRUSTED, AppError.Kind.TLS_PIN_CHANGED).forEach { assertEquals(BannerKind.CERTIFICATE, banner(it)) }
        listOf(AppError.Kind.SERVER_ERROR, AppError.Kind.UNKNOWN, AppError.Kind.SERVER_NOT_FOUND, AppError.Kind.FORBIDDEN)
            .forEach { assertEquals(BannerKind.GENERIC, banner(it)) }
    }

    @Test fun goneMeansNoItemAndNotFound() {
        assertTrue(Freshness.Stale(AppError.Kind.SERVER_NOT_FOUND).isGone(hasItem = false))
        assertFalse(Freshness.Stale(AppError.Kind.SERVER_NOT_FOUND).isGone(hasItem = true))
        assertFalse(Freshness.Stale(AppError.Kind.OFFLINE).isGone(hasItem = false))
        assertFalse(Freshness.Refreshing.isGone(hasItem = false))
    }
}
