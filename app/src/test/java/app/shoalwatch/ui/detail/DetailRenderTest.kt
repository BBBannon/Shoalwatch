package app.shoalwatch.ui.detail

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.shoalwatch.R
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

/** The detail page composes in each main state at phone and tablet size and both text sizes. */
abstract class DetailRenderBase : RenderBase() {
    private val snackbar = SnackbarHostState()
    private val opened = mutableListOf<Destination>()
    private val played = mutableListOf<Boolean>()
    private val favourites = mutableListOf<Boolean>()
    private var refreshed = 0
    private var backs = 0
    private var signIns = 0
    private var reviews = 0
    private var servers = 0

    private val actions = DetailActions(
        onBack = { backs++ }, onRefresh = { refreshed++ }, onOpen = { opened += it }, onSignIn = { signIns++ }, onReview = { reviews++ },
        onOpenServers = { servers++ }, onSetPlayed = { played += it }, onSetFavourite = { favourites += it },
    )

    private fun film(data: app.shoalwatch.data.library.UserData? = userData()): ItemWithUserData =
        entry(7, name = "The Example Film", data = data).let {
            it.copy(item = it.item.copy(overview = "Slow <i>and</i> kind.<br>Two lines.", officialRating = "PG", communityRating = 7.84f, runtimeMs = 102 * 60_000L))
        }

    private fun ready(entry: ItemWithUserData = film()) = DetailUiState(DetailPhase.READY, BEN, entry)

    private fun showDetail(state: DetailUiState) = show { DetailScreen(state, actions, emptyFlow(), snackbar) }

    @Test fun showsTheTitleFactsAndPlainTextOverview() {
        showDetail(ready())
        assertShown("The Example Film")
        assertShown("2019 · 1 hr 42 min · PG · 7.8")
        assertDescribed("2019 · 1 hr 42 min · PG · Community rating 7.8 out of 10")
        assertShown("Slow and kind.\nTwo lines.")
        assertTrue(headings() >= 2)
    }

    @Test fun anEpisodeShowsItsSeriesAndCodeAboveItsTitleAndOffersGoToSeries() {
        showDetail(ready(episode(7, season = 2, number = 5, name = "The Tide")))
        assertShown("Series 50 · S2 E5")
        assertShown("The Tide")
        rule.onAllNodes(hasText(text(R.string.sheet_go_series))).onFirst().performScrollTo().performClick()
        assertEquals(listOf<Destination>(Destination.Series(id(50), id(62))), opened)
    }

    @Test fun aFilmHasNoGoToSeries() {
        showDetail(ready())
        assertNotShown(text(R.string.sheet_go_series))
    }

    @Test fun partlyWatchedSaysResumeFromAndTheWordsForTheBar() {
        showDetail(ready(film(userData(positionMs = 23 * 60_000L, percent = 22f))))
        assertShown("Resume from 23 min · 22% watched")
    }

    @Test fun watchedAndNotWatchedAreSaidInWords() {
        showDetail(ready(film(userData(played = true))))
        assertShown(text(R.string.detail_watched_state))
        assertShown(text(R.string.sheet_mark_unwatched))
    }

    @Test fun anUntouchedItemSaysNotWatchedYet() {
        showDetail(ready())
        assertShown(text(R.string.detail_unwatched_state))
        assertShown(text(R.string.sheet_mark_watched))
    }

    @Test fun playIsAlwaysThereAndSaysPlaybackArrivesLater() {
        showDetail(ready())
        rule.onAllNodes(hasText(text(R.string.detail_play))).onFirst().performScrollTo().performClick()
        rule.waitForIdle()
        assertShown(text(R.string.detail_playback_later))
    }

    @Test fun markWatchedAndFavouriteArePassedOn() {
        showDetail(ready())
        rule.onAllNodes(hasText(text(R.string.sheet_mark_watched))).onFirst().performScrollTo().performClick()
        assertEquals(listOf(true), played)
        rule.onNodeWithContentDescription(text(R.string.sheet_favourite)).performScrollTo().performClick()
        assertEquals(listOf(true), favourites)
    }

    @Test fun aFavouriteOffersToUnfavourite() {
        showDetail(ready(film(userData(favourite = true))))
        assertDescribed(text(R.string.sheet_unfavourite))
    }

    @Test fun aControlThatIsWorkingStillShowsItsLabel() {
        showDetail(ready().copy(working = setOf(Work(id(7), ActionKind.PLAYED), Work(id(7), ActionKind.FAVOURITE))))
        assertShown(text(R.string.sheet_mark_watched))
    }

    @Test fun firstLoadShowsASkeletonOfTheSameShape() {
        showDetail(DetailUiState(DetailPhase.LOADING, BEN, refreshing = true))
        assertDescribed(text(R.string.cd_loading_content))
    }

    @Test fun anOfflineBannerKeepsTheItem() {
        showDetail(ready().copy(banner = BannerKind.OFFLINE))
        assertShown(text(R.string.banner_offline))
        assertShown("The Example Film")
        rule.onAllNodes(hasText(text(R.string.action_retry))).onFirst().performClick()
        assertEquals(1, refreshed)
    }

    @Test fun anExpiredSignInOffersSignIn() {
        showDetail(ready().copy(banner = BannerKind.SIGN_IN))
        rule.onAllNodes(hasText(text(R.string.action_sign_in))).onFirst().performClick()
        assertEquals(1, signIns)
    }

    @Test fun aCertificateProblemOffersReview() {
        showDetail(ready().copy(banner = BannerKind.CERTIFICATE))
        rule.onAllNodes(hasText(text(R.string.action_review))).onFirst().performClick()
        assertEquals(1, reviews)
    }

    @Test fun aFailureWithNothingSavedIsAnErrorStateWithRetry() {
        showDetail(DetailUiState(DetailPhase.ERROR, BEN, banner = BannerKind.GENERIC))
        assertShown(text(R.string.banner_generic))
        assertShown(text(R.string.error_title))
    }

    @Test fun anItemThatIsGoneSaysSo() {
        showDetail(DetailUiState(DetailPhase.GONE, BEN))
        assertShown(text(R.string.gone_title))
        rule.onAllNodes(hasText(text(R.string.cd_back))).onFirst().performClick()
        assertEquals(1, backs)
    }

    @Test fun nobodySignedInLinksToTheServersList() {
        showDetail(DetailUiState(DetailPhase.SIGNED_OUT))
        assertShown(text(R.string.home_signed_out_title))
        rule.onAllNodes(hasText(text(R.string.home_manage))).onFirst().performClick()
        assertEquals(1, servers)
    }

    @Test fun theBackButtonGoesBack() {
        showDetail(ready())
        rule.onAllNodes(hasContentDescription(text(R.string.cd_back))).onFirst().performClick()
        assertEquals(1, backs)
    }

    @Test fun aNamelessItemShowsUntitled() {
        showDetail(ready(film().let { it.copy(item = it.item.copy(name = "")) }))
        assertShown(text(R.string.untitled))
    }
}

@Config(qualifiers = "w360dp-h640dp") class DetailPhoneRenderTest : DetailRenderBase() { override val fontScale = 1f }
@Config(qualifiers = "w360dp-h640dp") class DetailPhoneLargeTextRenderTest : DetailRenderBase() { override val fontScale = 2f }
@Config(qualifiers = "w840dp-h1200dp") class DetailTabletRenderTest : DetailRenderBase() { override val fontScale = 1f }
@Config(qualifiers = "w840dp-h1200dp") class DetailTabletLargeTextRenderTest : DetailRenderBase() { override val fontScale = 2f }
