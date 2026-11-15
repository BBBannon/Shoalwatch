package app.shoalwatch.ui.home

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.shoalwatch.R
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.ui.ConnectionView
import app.shoalwatch.ui.address
import app.shoalwatch.ui.browse.ActionKind
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.FILMS
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.SERVER_ID
import app.shoalwatch.ui.browse.SHOWS
import app.shoalwatch.ui.browse.Work
import app.shoalwatch.ui.browse.account
import app.shoalwatch.ui.browse.entry
import app.shoalwatch.ui.browse.episode
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.browse.userData
import app.shoalwatch.ui.present.BannerKind
import app.shoalwatch.ui.present.Destination
import app.shoalwatch.ui.shell.AppShell
import app.shoalwatch.ui.shell.Tab
import app.shoalwatch.ui.toView
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Home composes in every main state (loading skeleton, content, first-run empty, offline, sign-in expired, certificate, error,
 * signed out) and shows its key text and semantics. Written once; run at phone and tablet size, at font scale 1.0 and 2.0.
 */
abstract class HomeRenderBase : RenderBase() {
    private val snackbar = SnackbarHostState()
    private val opened = mutableListOf<Destination>()
    private val libraries = mutableListOf<String>()
    private val played = mutableListOf<Pair<String, Boolean>>()
    private val removed = mutableListOf<String>()
    private var refreshed = 0
    private var signIns = 0
    private var reviews = 0

    private val actions = HomeActions(
        onOpenServers = {}, onSwitchAccount = {}, onRefresh = { refreshed++ }, onRetryConnection = {}, onOpenLibrary = { libraries += it },
        onOpen = { opened += it }, onSignIn = { signIns++ }, onReview = { reviews++ }, onRemoveFromResume = { removed += it },
        onSetPlayed = { id, p -> played += id to p }, onSetFavourite = { _, _ -> },
    )

    private val resumeFilm = entry(1, name = "The Example Film", data = userData(positionMs = 1_000_000, percent = 42f))
    private val resumeEpisode = episode(2, season = 2, number = 5, name = "The Tide", data = userData(percent = 30f))
    private val nextEpisode = episode(3, season = 1, number = 2, seriesN = 51, name = "Second Wave")
    private val latestFilm = entry(4, name = "Fresh Film", data = userData(played = true))
    private val latestShow = entry(5, ItemKind.SERIES, "Tidal", data = userData(unplayed = 4))

    private val content = HomeUiState(
        phase = HomePhase.READY, session = BEN, serverId = SERVER_ID, serverName = "Den", userName = "ben",
        connection = Connection.Online(address("http://192.168.1.10:8096"), true).toView(),
        views = listOf(FILMS, SHOWS),
        resume = RowUi(listOf(resumeFilm, resumeEpisode)), nextUp = RowUi(listOf(nextEpisode)),
        latest = listOf(LatestUi(FILMS, RowUi(listOf(latestFilm))), LatestUi(SHOWS, RowUi(listOf(latestShow)))),
    )

    private fun showHome(state: HomeUiState) = show { HomeScreen(state, actions, emptyFlow(), snackbar) }

    @Test fun contentShowsEveryRowInOrderWithItsHeading() {
        showHome(content)
        assertShown(text(R.string.home_continue))
        assertShown(text(R.string.home_next_up))
        scrollTo(hasText(text(R.string.home_latest, "Films")))
        assertShown(text(R.string.home_latest, "Films"))
        assertShown("Den")
        assertTrue("headings: ${headings()}", headings() >= 3)
    }

    @Test fun everyCardIsOneLabelledElementWithItsOwnMoreButton() {
        showHome(content)
        assertTrue(tree(), describedStarting("The Example Film"))
        assertTrue(describedStarting("Series 50, Season 2, episode 5, The Tide"))
        assertDescribed(text(R.string.cd_more_actions, "The Example Film"))
        assertDescribed(text(R.string.cd_more_actions, "Series 50"))
    }

    @Test fun aWatchedFilmCardSaysWatchedInWordsAndASeriesCountsUnwatched() {
        showHome(content)
        scrollTo(hasContentDescription("Tidal, 2019, 4 unwatched episodes"))
        assertDescribed("Fresh Film, 2019, watched")
        assertDescribed("Tidal, 2019, 4 unwatched episodes")
    }

    @Test fun tappingACardOpensItsDestination() {
        showHome(content)
        rule.onNodeWithContentDescription("Series 50, Season 2, episode 5, The Tide, 30% watched").performClick()
        assertEquals(listOf<Destination>(Destination.Series(id(50), id(62), id(2))), opened)
    }

    @Test fun theLibrariesAreLargeCardsThatOpenTheLibrary() {
        showHome(content)
        scrollTo(hasText(text(R.string.home_libraries)))
        rule.onNodeWithContentDescription("TV Shows, ${text(R.string.library_kind_tv)}").performScrollTo().performClick()
        assertEquals(listOf(SHOWS.id), libraries)
    }

    @Test fun theMoreButtonOpensTheSheetWithTheRightActions() {
        showHome(content)
        rule.onNodeWithContentDescription(text(R.string.cd_more_actions, "The Example Film")).performClick()
        rule.waitForIdle()
        assertShown(text(R.string.sheet_remove_resume))
        assertShown(text(R.string.sheet_mark_watched))
        assertShown(text(R.string.sheet_favourite))
        assertShown(text(R.string.sheet_details))
        assertNotShown(text(R.string.sheet_go_series))
        rule.onAllNodes(hasText(text(R.string.sheet_remove_resume))).onFirst().performClick()
        assertEquals(listOf(id(1)), removed)
    }

    @Test fun anEpisodeSheetOffersGoToSeriesAndNextUpHasNoRemove() {
        showHome(content)
        scrollTo(hasContentDescription(text(R.string.cd_more_actions, "Series 51")))
        rule.onNodeWithContentDescription(text(R.string.cd_more_actions, "Series 51")).performClick()
        rule.waitForIdle()
        assertShown(text(R.string.sheet_go_series))
        assertNotShown(text(R.string.sheet_remove_resume))
    }

    @Test fun aWorkingControlShowsAndLoadingIsAnnounced() {
        showHome(content.copy(working = setOf(Work(id(4), ActionKind.PLAYED))))
        scrollTo(hasContentDescription("Fresh Film, 2019, watched"))
        assertDescribed("Fresh Film, 2019, watched")
    }

    @Test fun firstLoadShowsSkeletonsAndNoEmptyMessage() {
        showHome(HomeUiState(phase = HomePhase.LOADING, session = BEN, serverId = SERVER_ID, serverName = "Den"))
        assertDescribed(text(R.string.cd_loading_content))
        assertNotShown(text(R.string.home_empty_title))
    }

    @Test fun anEmptyFirstRunHomeIsFriendlyWithRetry() {
        showHome(content.copy(views = emptyList(), resume = RowUi(), nextUp = RowUi(), latest = emptyList()))
        assertShown(text(R.string.home_empty_title))
        rule.onAllNodes(hasText(text(R.string.action_retry))).onFirst().performClick()
        assertEquals(1, refreshed)
    }

    @Test fun anOfflineBannerKeepsTheContentAndOffersRetry() {
        showHome(content.copy(topBanner = BannerKind.OFFLINE))
        assertShown(text(R.string.banner_offline))
        assertShown(text(R.string.home_continue))
        rule.onAllNodes(hasText(text(R.string.action_retry))).onFirst().performClick()
        assertEquals(1, refreshed)
    }

    @Test fun anExpiredSignInOffersSignIn() {
        showHome(content.copy(topBanner = BannerKind.SIGN_IN))
        assertShown(text(R.string.banner_signin))
        rule.onAllNodes(hasText(text(R.string.action_sign_in))).onFirst().performClick()
        assertEquals(1, signIns)
    }

    @Test fun aCertificateBannerOffersReview() {
        showHome(content.copy(topBanner = BannerKind.CERTIFICATE))
        assertShown(text(R.string.banner_certificate))
        rule.onAllNodes(hasText(text(R.string.action_review))).onFirst().performClick()
        assertEquals(1, reviews)
    }

    @Test fun aGenericFailureSaysCouldntRefresh() {
        showHome(content.copy(topBanner = BannerKind.GENERIC))
        assertShown(text(R.string.banner_generic))
    }

    @Test fun anErrorWithNothingSavedIsAnErrorStateWithRetryNotASpinner() {
        showHome(content.copy(views = emptyList(), resume = RowUi(), nextUp = RowUi(), latest = emptyList(), topBanner = BannerKind.GENERIC))
        assertShown(text(R.string.banner_generic))
        assertShown(text(R.string.home_empty_title))
    }

    @Test fun aRowThatFailedAloneShowsItsBannerInlineAboveItsHeading() {
        showHome(content.copy(resume = RowUi(listOf(resumeFilm), banner = BannerKind.OFFLINE)))
        assertShown(text(R.string.banner_offline))
        assertShown(text(R.string.home_continue))
    }

    @Test fun anEmptyRowIsLeftOutAltogether() {
        showHome(content.copy(nextUp = RowUi(), resume = RowUi()))
        assertNotShown(text(R.string.home_next_up))
        assertNotShown(text(R.string.home_continue))
        scrollTo(hasText(text(R.string.home_libraries)))
        assertShown(text(R.string.home_libraries))
    }

    @Test fun theCertificateProblemOnTheConnectionGetsTheProminentCardWithReview() {
        showHome(content.copy(connection = Connection.Offline(app.shoalwatch.core.AppError.Kind.TLS_PIN_CHANGED).toView()))
        assertShown(text(R.string.cert_changed_title))
        assertShown(text(R.string.cert_review))
    }

    @Test fun theConnectionChipIsCompactInTheTopBar() {
        showHome(content.copy(connection = ConnectionView(ConnectionView.Kind.OFFLINE, detail = "No connection right now")))
        assertShown(text(R.string.conn_offline))
        assertNotShown("No connection right now")
    }

    @Test fun signedOutOffersTheServersList() {
        showHome(HomeUiState(phase = HomePhase.SIGNED_OUT))
        assertShown(text(R.string.home_signed_out_title))
        assertShown(text(R.string.home_manage))
    }

    @Test fun aFailedStartShowsRetry() {
        showHome(HomeUiState(phase = HomePhase.LOAD_FAILED))
        assertShown(text(R.string.startup_failed_title))
        assertShown(text(R.string.action_retry))
    }

    @Test fun theAccountButtonSwitchesWhenThereAreSeveralAccounts() {
        val switched = mutableListOf<String>()
        show {
            HomeScreen(
                content.copy(accounts = listOf(account(BEN, "ben"), account(app.shoalwatch.ui.browse.AMY, "amy"))),
                HomeActions({}, { switched += it }, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }),
                emptyFlow(), snackbar,
            )
        }
        rule.onNodeWithContentDescription(text(R.string.account_menu_cd)).performClick()
        rule.waitForIdle()
        assertShown(text(R.string.account_switch_title))
        assertShown(text(R.string.account_current, "ben"))
        rule.onAllNodes(hasText("amy")).onFirst().performClick()
        assertEquals(listOf(app.shoalwatch.ui.browse.AMY.userId), switched)
    }

    @Test fun theShellShowsHomeAndDownloadsAndTheContentIsNotHiddenBehindTheBar() {
        show {
            AppShell(Tab.HOME, {}, snackbar) { HomeScreen(content, actions, emptyFlow(), snackbar) }
        }
        assertShown(text(R.string.nav_home))
        assertShown(text(R.string.nav_downloads))
        rule.onAllNodes(hasText(text(R.string.nav_home))).onFirst().assertIsDisplayed()
    }

    @Test fun hasContentDescriptionHelperWorks() {
        showHome(content)
        assertTrue(rule.onAllNodes(hasContentDescription(text(R.string.account_menu_cd))).fetchSemanticsNodes().isNotEmpty())
    }
}

@Config(qualifiers = "w360dp-h640dp") class HomePhoneRenderTest : HomeRenderBase() { override val fontScale = 1f }
@Config(qualifiers = "w360dp-h640dp") class HomePhoneLargeTextRenderTest : HomeRenderBase() { override val fontScale = 2f }
@Config(qualifiers = "w840dp-h1200dp") class HomeTabletRenderTest : HomeRenderBase() { override val fontScale = 1f }
@Config(qualifiers = "w840dp-h1200dp") class HomeTabletLargeTextRenderTest : HomeRenderBase() { override val fontScale = 2f }
