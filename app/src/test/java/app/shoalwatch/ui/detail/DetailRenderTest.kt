package app.shoalwatch.ui.detail

import app.shoalwatch.data.library.ItemImages
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.shoalwatch.R
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.playback.StartFrom
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
    private val plays = mutableListOf<StartFrom>()

    private val actions = DetailActions(
        onBack = { backs++ }, onRefresh = { refreshed++ }, onOpen = { opened += it }, onSignIn = { signIns++ }, onReview = { reviews++ },
        onOpenServers = { servers++ }, onSetPlayed = { played += it }, onSetFavourite = { favourites += it }, onPlay = { plays += it },
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

    @Test fun withNoSavedPositionPlayPlaysFromTheStart() {
        showDetail(ready())
        assertNotShown(text(R.string.detail_play_from_start))
        rule.onAllNodes(hasText(text(R.string.detail_play))).onFirst().performScrollTo().performClick()
        assertEquals(listOf(StartFrom.BEGINNING), plays)
    }

    @Test fun withASavedPositionTheButtonResumesAndPlayFromStartIsOffered() {
        showDetail(ready(film(userData(positionMs = 23 * 60_000L, percent = 22f))))
        rule.onAllNodes(hasText("Resume from 23 min")).onFirst().performScrollTo().performClick()
        rule.onAllNodes(hasText(text(R.string.detail_play_from_start))).onFirst().performScrollTo().performClick()
        assertEquals(listOf(StartFrom.RESUME, StartFrom.BEGINNING), plays)
    }

    @Test fun playButtonsAreAtLeast48dpHigh() {
        showDetail(ready(film(userData(positionMs = 23 * 60_000L, percent = 22f))))
        val node = rule.onAllNodes(hasText(text(R.string.detail_play_from_start))).onFirst().performScrollTo().fetchSemanticsNode()
        assertTrue(node.size.height >= with(rule.density) { 48.dp.roundToPx() })
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
        // ONE message with its button: the banner is left out.
        assertShown(text(R.string.error_title))
        assertNotShown(text(R.string.banner_generic))
        assertEquals(1, rule.onAllNodes(hasText(text(R.string.action_retry))).fetchSemanticsNodes().size)
        rule.onAllNodes(hasText(text(R.string.action_retry))).onFirst().performClick()
        assertEquals(1, refreshed)
    }

    @Test fun anExpiredSignInWithNothingSavedOffersSignInInTheOneMessage() {
        showDetail(DetailUiState(DetailPhase.ERROR, BEN, banner = BannerKind.SIGN_IN))
        assertShown(text(R.string.error_title))
        assertEquals(1, rule.onAllNodes(hasText(text(R.string.action_sign_in))).fetchSemanticsNodes().size)
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

    // --- the hero picture, and screens that scroll ---------------------------------------------------------------

    private fun titleTop() = rule.onAllNodes(hasText("The Example Film").and(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))).onFirst().getBoundsInRoot().top

    @Test fun aFilmWithABackdropStartsWithTheWidePictureAndTheTitleComesBelowIt() {
        showDetail(ready())
        assertTrue("title at ${titleTop()}", titleTop() > 150.dp)
    }

    @Test fun aFilmWithNoBackdropHasNoPictureAtTheTopJustABackBarAndThePosterBesideTheTitle() {
        showDetail(ready(film().let { it.copy(item = it.item.copy(images = ItemImages(null, null, null, null))) }))
        assertTrue("title at ${titleTop()}", titleTop() < 130.dp)
        rule.onNodeWithContentDescription(text(R.string.cd_back)).performClick()
        assertEquals(1, backs)
    }

    @Test fun theTitleHasARefreshAction() {
        showDetail(ready())
        val title = rule.onAllNodes(hasText("The Example Film").and(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))).onFirst()
        val custom = title.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf(text(R.string.action_refresh)), custom.map { it.label })
        rule.runOnIdle { custom.single().action() }
        assertEquals(1, refreshed)
    }

    @Test fun theErrorScreenScrollsSoItsButtonIsReachableAtLargeText() {
        showDetail(DetailUiState(DetailPhase.ERROR, BEN, banner = BannerKind.OFFLINE))
        assertTrue(rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun anItemThatIsGoneScrollsToo() {
        showDetail(DetailUiState(DetailPhase.GONE, BEN))
        assertTrue(rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun theEpisodeLineReadsSeriesThenCodeFromTheSharedJoin() {
        showDetail(ready(episode(7, season = 2, number = 5, name = "The Tide")))
        assertShown(text(R.string.line_join, "Series 50", "S2 E5"))
    }

}

@Config(qualifiers = "w360dp-h640dp") class DetailPhoneRenderTest : DetailRenderBase() { override val fontScale = 1f }
@Config(qualifiers = "w360dp-h640dp") class DetailPhoneLargeTextRenderTest : DetailRenderBase() { override val fontScale = 2f }
@Config(qualifiers = "w840dp-h1200dp") class DetailTabletRenderTest : DetailRenderBase() { override val fontScale = 1f }
@Config(qualifiers = "w840dp-h1200dp") class DetailTabletLargeTextRenderTest : DetailRenderBase() { override val fontScale = 2f }
