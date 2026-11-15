package app.shoalwatch.ui.series

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.shoalwatch.R
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.ui.browse.ActionKind
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.Work
import app.shoalwatch.ui.browse.entry
import app.shoalwatch.ui.browse.episode
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.browse.userData
import app.shoalwatch.ui.present.BannerKind
import app.shoalwatch.ui.present.Destination
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** The series screen composes in each main state at phone and tablet size and both text sizes. */
abstract class SeriesRenderBase : RenderBase() {
    private val snackbar = SnackbarHostState()
    private val opened = mutableListOf<Destination>()
    private val picked = mutableListOf<String>()
    private val seriesPlayed = mutableListOf<Boolean>()
    private val seriesFavourite = mutableListOf<Boolean>()
    private val seasonPlayed = mutableListOf<Pair<String, Boolean>>()
    private val episodePlayed = mutableListOf<Pair<String, Boolean>>()
    private var refreshed = 0
    private var backs = 0
    private var signIns = 0
    private var reviews = 0
    private var servers = 0

    private val actions = SeriesActions(
        onBack = { backs++ }, onPickSeason = { picked += it }, onRefresh = { refreshed++ }, onOpen = { opened += it },
        onSignIn = { signIns++ }, onReview = { reviews++ }, onOpenServers = { servers++ },
        onSetSeriesPlayed = { seriesPlayed += it }, onSetSeriesFavourite = { seriesFavourite += it },
        onSetSeasonPlayed = { s, p -> seasonPlayed += s to p }, onSetPlayed = { i, p -> episodePlayed += i to p }, onSetFavourite = { _, _ -> },
    )

    private fun season(n: Int, number: Int, played: Boolean = false): ItemWithUserData =
        entry(60 + n, ItemKind.SEASON, "Season $number", data = userData(played = played)).let { it.copy(item = it.item.copy(indexNumber = number, seriesId = id(50))) }

    private val tidal = entry(50, ItemKind.SERIES, "Tidal", data = userData(unplayed = 5))
        .let { it.copy(item = it.item.copy(overview = "A crew <b>follows</b> the tide.<br>Then it turns.", officialRating = "TV-14")) }

    private val episodes = listOf(episode(1, 1, 1, name = "The Beginning", data = userData(played = true)), episode(2, 1, 2, name = "The Turning Tide", data = userData(percent = 40f)))

    private val ready = SeriesUiState(
        phase = SeriesPhase.READY, session = BEN, series = tidal, seasons = listOf(season(1, 1), season(2, 2)), episodes = episodes,
        selectedSeasonId = id(61),
    )

    private fun showSeries(state: SeriesUiState) = show { SeriesScreen(state, actions, emptyFlow(), snackbar) }

    @Test fun showsTheTitleFactsAndAPlainTextOverview() {
        showSeries(ready)
        assertShown("Tidal")
        assertShown("2019 · TV-14 · 2 seasons")
        assertShown("A crew follows the tide.\nThen it turns.")
        assertTrue(headings() >= 2)
    }

    @Test fun seasonsAreChipsAndTheSelectedOneIsHeadedAndPickable() {
        showSeries(ready)
        assertShown(text(R.string.season_number, "1"))
        assertShown(text(R.string.season_number, "2"))
        rule.onAllNodes(hasText(text(R.string.season_number, "2"))).onFirst().performClick()
        assertEquals(listOf(id(62)), picked)
    }

    @Test fun specialsComeAfterTheNumberedSeasons() {
        showSeries(ready.copy(seasons = listOf(season(1, 1), season(2, 2), season(3, 0))))
        assertShown(text(R.string.season_specials))
    }

    @Test fun eachEpisodeIsOneLabelledRowWithItsOwnMoreButton() {
        showSeries(ready)
        scrollTo(hasContentDescription("E1 · The Beginning, 1 hr 40 min, watched"))
        assertDescribed("E1 · The Beginning, 1 hr 40 min, watched")
        scrollTo(hasContentDescription(text(R.string.cd_more_actions, "The Turning Tide")))
        assertTrue(describedStarting("E2 · The Turning Tide"))
        assertDescribed(text(R.string.cd_more_actions, "The Turning Tide"))
    }

    @Test fun tappingAnEpisodeOpensItsDetail() {
        showSeries(ready)
        scrollTo(hasContentDescription("E1 · The Beginning, 1 hr 40 min, watched"))
        rule.onNodeWithContentDescription("E1 · The Beginning, 1 hr 40 min, watched").performClick()
        assertEquals(listOf<Destination>(Destination.Detail(id(1))), opened)
    }

    @Test fun theMoreButtonOnAnEpisodeOpensTheSheet() {
        showSeries(ready)
        scrollTo(hasContentDescription(text(R.string.cd_more_actions, "The Turning Tide")))
        rule.onNodeWithContentDescription(text(R.string.cd_more_actions, "The Turning Tide")).performClick()
        rule.waitForIdle()
        assertShown(text(R.string.sheet_mark_watched))
        assertShown(text(R.string.sheet_details))
        assertNotShown(text(R.string.sheet_go_series))
        rule.onAllNodes(hasText(text(R.string.sheet_mark_watched))).onFirst().performClick()
        assertEquals(listOf(id(2) to true), episodePlayed)
    }

    @Test fun theHighlightedEpisodeIsMarkedAndTheListIsStillThere() {
        showSeries(ready.copy(highlightEpisodeId = id(2)))
        rule.waitForIdle()
        assertTrue(tree(), describedStarting("E2 · The Turning Tide"))
    }

    @Test fun markSeriesWatchedAndFavouriteArePassedOn() {
        showSeries(ready)
        rule.onAllNodes(hasText(text(R.string.series_mark_watched))).onFirst().performClick()
        assertEquals(listOf(true), seriesPlayed)
        rule.onNodeWithContentDescription(text(R.string.sheet_favourite)).performClick()
        assertEquals(listOf(true), seriesFavourite)
    }

    @Test fun aWatchedSeriesOffersMarkUnwatched() {
        showSeries(ready.copy(series = tidal.copy(userData = userData(played = true, favourite = true))))
        assertShown(text(R.string.series_mark_unwatched))
        assertDescribed(text(R.string.sheet_unfavourite))
    }

    @Test fun theSeasonMenuMarksTheSeasonWatched() {
        showSeries(ready)
        rule.onNodeWithContentDescription(text(R.string.season_menu_cd)).performClick()
        rule.waitForIdle()
        assertShown(text(R.string.season_mark_watched))
        rule.onAllNodes(hasText(text(R.string.season_mark_watched))).onFirst().performClick()
        assertEquals(listOf(id(61) to true), seasonPlayed)
    }

    @Test fun aWorkingSeriesButtonIsSwitchedOff() {
        showSeries(ready.copy(working = setOf(Work(id(50), ActionKind.PLAYED))))
        assertShown(text(R.string.series_mark_watched))
    }

    @Test fun firstLoadShowsASkeletonOfTheSameShape() {
        showSeries(SeriesUiState(phase = SeriesPhase.LOADING, session = BEN, refreshing = true))
        assertDescribed(text(R.string.cd_loading_content))
        assertNotShown(text(R.string.episodes_empty))
    }

    @Test fun aSeasonWithNoEpisodesYetSaysSo() {
        showSeries(ready.copy(episodes = emptyList()))
        scrollTo(hasText(text(R.string.episodes_empty)))
        assertShown(text(R.string.episodes_empty))
    }

    @Test fun anOfflineBannerKeepsTheContent() {
        showSeries(ready.copy(banner = BannerKind.OFFLINE))
        assertShown(text(R.string.banner_offline))
        assertShown("Tidal")
        rule.onAllNodes(hasText(text(R.string.action_retry))).onFirst().performClick()
        assertEquals(1, refreshed)
    }

    @Test fun anExpiredSignInOffersSignIn() {
        showSeries(ready.copy(banner = BannerKind.SIGN_IN))
        rule.onAllNodes(hasText(text(R.string.action_sign_in))).onFirst().performClick()
        assertEquals(1, signIns)
    }

    @Test fun aCertificateProblemOffersReview() {
        showSeries(ready.copy(banner = BannerKind.CERTIFICATE))
        rule.onAllNodes(hasText(text(R.string.action_review))).onFirst().performClick()
        assertEquals(1, reviews)
    }

    @Test fun anErrorWithNothingSavedIsAnErrorStateWithRetry() {
        showSeries(SeriesUiState(phase = SeriesPhase.READY, session = BEN, banner = BannerKind.GENERIC))
        assertShown(text(R.string.banner_generic))
        assertShown(text(R.string.error_title))
    }

    @Test fun aSeriesThatIsGoneSaysSo() {
        showSeries(SeriesUiState(phase = SeriesPhase.GONE, session = BEN))
        assertShown(text(R.string.gone_title))
        rule.onAllNodes(hasText(text(R.string.cd_back))).onFirst().performClick()
        assertEquals(1, backs)
    }

    @Test fun nobodySignedInLinksToTheServersList() {
        showSeries(SeriesUiState(phase = SeriesPhase.SIGNED_OUT))
        assertShown(text(R.string.home_signed_out_title))
        rule.onAllNodes(hasText(text(R.string.home_manage))).onFirst().performClick()
        assertEquals(1, servers)
    }

    @Test fun theBackButtonGoesBack() {
        showSeries(ready)
        rule.onAllNodes(hasContentDescription(text(R.string.cd_back))).onFirst().performClick()
        assertEquals(1, backs)
    }
}

@Config(qualifiers = "w360dp-h640dp") class SeriesPhoneRenderTest : SeriesRenderBase() { override val fontScale = 1f }
@Config(qualifiers = "w360dp-h640dp") class SeriesPhoneLargeTextRenderTest : SeriesRenderBase() { override val fontScale = 2f }
@Config(qualifiers = "w840dp-h1200dp") class SeriesTabletRenderTest : SeriesRenderBase() { override val fontScale = 1f }
@Config(qualifiers = "w840dp-h1200dp") class SeriesTabletLargeTextRenderTest : SeriesRenderBase() { override val fontScale = 2f }
