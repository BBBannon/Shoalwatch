package app.shoalwatch.ui.home

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.ui.ConnectionView
import app.shoalwatch.ui.address
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.FILMS
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.SERVER_ID
import app.shoalwatch.ui.browse.SHOWS
import app.shoalwatch.ui.browse.account
import app.shoalwatch.ui.browse.entry
import app.shoalwatch.ui.browse.episode
import app.shoalwatch.ui.browse.userData
import app.shoalwatch.ui.components.PhoneWideCardWidth
import app.shoalwatch.ui.components.TabletWideCardWidth
import app.shoalwatch.ui.toView
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * What only layout can break on Home: the top bar at a large text size, the account menu, opening at the top, scrolling when
 * the text is big, and the card width. Phone and tablet, both text sizes. (What a real window's insets do needs eyes.)
 */
abstract class HomeLayoutRenderBase : RenderBase() {
    private val snackbar = SnackbarHostState()
    private var refreshed = 0
    private var servers = 0
    private val switched = mutableListOf<String>()

    private val actions = HomeActions(
        onOpenServers = { servers++ }, onSwitchAccount = { switched += it }, onRefresh = { refreshed++ }, onRetryConnection = {},
        onOpenLibrary = {}, onOpen = {}, onSignIn = {}, onReview = {}, onRemoveFromResume = {}, onSetPlayed = { _, _ -> }, onSetFavourite = { _, _ -> },
    )

    private val resumeFilm = entry(1, name = "The Example Film", data = userData(positionMs = 1_000_000, percent = 42f))
    private val nextEpisode = episode(3, season = 1, number = 2, seriesN = 51, name = "Second Wave")

    private val content = HomeUiState(
        phase = HomePhase.READY, session = BEN, serverId = SERVER_ID, serverName = "Home server", userName = "ben",
        connection = Connection.Online(address("http://192.168.1.10:8096"), true).toView(),
        accounts = listOf(account(BEN, "ben")), views = listOf(FILMS, SHOWS),
        resume = RowUi(listOf(resumeFilm)), nextUp = RowUi(listOf(nextEpisode)),
        latest = listOf(LatestUi(FILMS, RowUi(listOf(entry(4, name = "Fresh Film"))))),
    )

    private val checking = ConnectionView(ConnectionView.Kind.CHECKING, previous = ConnectionView.Kind.HOME)
    private val wide get() = context.resources.configuration.screenWidthDp >= 600

    private fun showHome(state: HomeUiState) = show { HomeScreen(state, actions, emptyFlow(), snackbar) }

    private fun serverNameWidth() = rule.onAllNodes(hasText("Home server")).onFirst().getBoundsInRoot().let { it.right - it.left }

    // --- the top bar ---------------------------------------------------------------------------------------------

    // Robolectric measures text far narrower than a phone does, so how much room a long chip takes is proved on the rule itself
    // (below); what is proved here, with real placement, is the large-text case and that nothing overlaps.

    @Test fun theNameAndTheChipNeverOverlap() {
        showHome(content.copy(connection = checking))
        val name = rule.onAllNodes(hasText("Home server")).onFirst().getBoundsInRoot()
        val chip = rule.onAllNodes(hasContentDescription(text(R.string.conn_cd, text(R.string.conn_checking_was, text(R.string.conn_home))))).onFirst().getBoundsInRoot()
        assertTrue("name=$name chip=$chip", chip.left >= name.right || chip.top >= name.bottom - 1.dp)
    }

    @Test fun atLargeTextTheChipIsAlwaysOnItsOwnLineUnderTheName() {
        showHome(content)
        if (fontScale > 1.3f) {
            val name = rule.onAllNodes(hasText("Home server")).onFirst().getBoundsInRoot()
            val chip = rule.onAllNodes(hasContentDescription(text(R.string.conn_cd, text(R.string.conn_home)))).onFirst().getBoundsInRoot()
            assertTrue("name=$name chip=$chip", chip.top >= name.bottom - 1.dp)
        }
    }

    @Test fun theChipLeavesTheNamesLineWhenTheNameWouldBeSqueezedOrTheTextIsLarge() {
        val gap = 8
        val minTitle = 120
        // A short chip beside a name with room: stays on the line.
        assertEquals(false, chipGoesUnderTitle(stackedText = false, room = 284, chipWidth = 81, gap = gap, minTitle = minTitle))
        // "Checking… (was Home network)" is about 235 wide: the name would be left with 41, so the chip goes under it.
        assertEquals(true, chipGoesUnderTitle(stackedText = false, room = 284, chipWidth = 235, gap = gap, minTitle = minTitle))
        // Exactly enough room for the minimum stays on the line; one less does not.
        assertEquals(false, chipGoesUnderTitle(false, room = 120 + 8 + 100, chipWidth = 100, gap = gap, minTitle = minTitle))
        assertEquals(true, chipGoesUnderTitle(false, room = 120 + 8 + 100 - 1, chipWidth = 100, gap = gap, minTitle = minTitle))
        // Large text always stacks; no chip never does.
        assertEquals(true, chipGoesUnderTitle(true, room = 900, chipWidth = 50, gap = gap, minTitle = minTitle))
        assertEquals(false, chipGoesUnderTitle(true, room = 900, chipWidth = 0, gap = gap, minTitle = minTitle))
    }

    @Test fun theServerNameIsAHeadingWithARefreshAction() {
        showHome(content)
        val heading = rule.onAllNodes(hasText("Home server").and(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))).onFirst()
        val custom = heading.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf(text(R.string.action_refresh)), custom.map { it.label })
        rule.runOnIdle { custom.single().action() }
        assertEquals(1, refreshed)
    }

    // --- the account menu ----------------------------------------------------------------------------------------

    @Test fun withOneAccountTheButtonStillOpensAMenuWithRefreshAndServersAndAccounts() {
        showHome(content)
        rule.onNodeWithContentDescription(text(R.string.account_menu_cd)).performClick()
        rule.waitForIdle()
        assertEquals(0, servers)
        assertShown(text(R.string.action_refresh))
        assertShown(text(R.string.home_manage))
        assertNotShown(text(R.string.account_switch_title))
    }

    @Test fun refreshInTheMenuAsksForAFreshHome() {
        showHome(content)
        rule.onNodeWithContentDescription(text(R.string.account_menu_cd)).performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasText(text(R.string.action_refresh))).onFirst().performClick()
        assertEquals(1, refreshed)
    }

    @Test fun serversAndAccountsInTheMenuOpensThatScreen() {
        showHome(content)
        rule.onNodeWithContentDescription(text(R.string.account_menu_cd)).performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasText(text(R.string.home_manage))).onFirst().performClick()
        assertEquals(1, servers)
    }

    @Test fun withSeveralAccountsTheMenuListsThemAboveRefreshAndServers() {
        showHome(content.copy(accounts = listOf(account(BEN, "ben"), account(AMY, "amy"))))
        rule.onNodeWithContentDescription(text(R.string.account_menu_cd)).performClick()
        rule.waitForIdle()
        assertShown(text(R.string.account_switch_title))
        assertShown(text(R.string.action_refresh))
        assertShown(text(R.string.home_manage))
        rule.onAllNodes(hasText("amy")).onFirst().performClick()
        assertEquals(listOf(AMY.userId), switched)
    }

    @Test fun theButtonSaysWhetherItsMenuIsOpen() {
        showHome(content)
        fun state() = rule.onNodeWithContentDescription(text(R.string.account_menu_cd)).fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertEquals(text(R.string.state_menu_closed), state())
        rule.onNodeWithContentDescription(text(R.string.account_menu_cd)).performClick()
        rule.waitForIdle()
        assertEquals(text(R.string.state_menu_open), state())
    }

    // --- opening at the top, and scrolling -----------------------------------------------------------------------

    @Test fun homeStaysAtTheTopWhenRowsArriveAboveTheOneItIsShowing() {
        // First only the libraries are known; then Continue Watching and Next Up arrive ABOVE them.
        var state by mutableStateOf(content.copy(resume = RowUi(), nextUp = RowUi(), latest = emptyList()))
        show { HomeScreen(state, actions, emptyFlow(), snackbar) }
        rule.waitForIdle()
        state = content
        rule.waitForIdle()
        val list = rule.onAllNodes(hasScrollAction()).onFirst().fetchSemanticsNode()
        val range: ScrollAxisRange = list.config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("the list was left scrolled down by ${range.value()}", 0f, range.value(), 0.5f)
        assertTrue("Continue Watching is off the top\n${tree()}", rule.onAllNodes(hasText(text(R.string.home_continue))).onFirst().getBoundsInRoot().top >= 0.dp)
    }

    @Test fun theSignedOutAndCouldNotLoadScreensScrollSoTheButtonIsAlwaysReachable() {
        showHome(HomeUiState(phase = HomePhase.LOAD_FAILED))
        assertTrue(rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun signedOutScrollsToo() {
        showHome(HomeUiState(phase = HomePhase.SIGNED_OUT))
        assertTrue(rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun theSignedOutButtonIsLabelledWithWhereItGoes() {
        showHome(HomeUiState(phase = HomePhase.SIGNED_OUT))
        rule.onNodeWithContentDescription(text(R.string.home_manage)).performClick()
        assertEquals(1, servers)
    }

    // --- cards ---------------------------------------------------------------------------------------------------

    @Test fun aWideCardIsNarrowEnoughOnAPhoneForTheNextOneToShowBesideIt() {
        showHome(content)
        val card = rule.onAllNodes(SemanticsMatcher("starts with The Example Film") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("The Example Film") } == true
        }).onFirst().getBoundsInRoot()
        val expected = if (wide) TabletWideCardWidth else PhoneWideCardWidth
        assertEquals(expected.value, (card.right - card.left).value, 1f)
        assertEquals(220f, PhoneWideCardWidth.value, 0f)
    }

    @Test fun aLibraryCardWhoseNameIsItsKindIsReadOnce() {
        showHome(content.copy(views = listOf(app.shoalwatch.data.library.LibraryView(FILMS.id, "Films", FILMS.kind))))
        scrollTo(hasText(text(R.string.home_libraries)))
        rule.onAllNodes(hasContentDescription("Films")).fetchSemanticsNodes().let { assertTrue(tree(), it.isNotEmpty()) }
        assertTrue(!described("Films, Films"))
    }
}

@Config(qualifiers = "w360dp-h640dp") class HomeLayoutPhoneRenderTest : HomeLayoutRenderBase() { override val fontScale = 1f }
@Config(qualifiers = "w360dp-h640dp") class HomeLayoutPhoneLargeTextRenderTest : HomeLayoutRenderBase() { override val fontScale = 2f }
@Config(qualifiers = "w840dp-h1200dp") class HomeLayoutTabletRenderTest : HomeLayoutRenderBase() { override val fontScale = 1f }
@Config(qualifiers = "w840dp-h1200dp") class HomeLayoutTabletLargeTextRenderTest : HomeLayoutRenderBase() { override val fontScale = 2f }
