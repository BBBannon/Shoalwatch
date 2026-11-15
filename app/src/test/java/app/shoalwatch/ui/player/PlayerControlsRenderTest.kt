package app.shoalwatch.ui.player

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.ui.browse.RenderBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** The controls: every one labelled, 48dp, reachable at large text; the seek bar in words with a SetProgress action. */
abstract class PlayerControlsRenderBase : RenderBase() {
    private val log = ActionLog()

    private fun showPlayer(state: PlayerUiState = ui()) = show { PlayerScreen(state, log.actions) }

    private val topRow get() = listOf(
        R.string.cd_back, R.string.player_tracks, R.string.player_quality, R.string.player_speed, R.string.player_sleep,
        R.string.player_subtitle_style, R.string.player_stream_info, R.string.player_lock,
    ).map { text(it) }

    private val centre get() = listOf(R.string.player_previous, R.string.player_back_10, R.string.player_pause, R.string.player_forward_10, R.string.player_next).map { text(it) }

    @Test fun everyControlHasItsLabel() {
        showPlayer()
        (topRow + centre + text(R.string.player_seek)).forEach { assertDescribed(it) }
        assertShown("The Tide"); assertShown("Series 50")
    }

    @Test fun everyClickableIsAtLeast48dpEachWay() {
        showPlayer()
        val min = with(rule.density) { 48.dp.roundToPx() }
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        nodes.forEach { n ->
            val what = n.config.getOrNull(SemanticsProperties.ContentDescription)
            assertTrue("$what is ${n.size}", n.size.width >= min && n.size.height >= min)
        }
    }

    @Test fun everyTopRowButtonCanBeReachedAndPressed() {
        showPlayer()
        topRow.forEach { rule.onNodeWithContentDescription(it).performScrollTo().assertIsDisplayed() }
        rule.onNodeWithContentDescription(text(R.string.player_speed)).performScrollTo().performClick()
        rule.onNodeWithContentDescription(text(R.string.player_lock)).performScrollTo().performClick()
        assertEquals(listOf("sheet:SPEED", "lock"), log.calls)
    }

    @Test fun theSeekBarSaysWhereItIsAndCanBeSet() {
        showPlayer()
        val bar = rule.onNodeWithContentDescription(text(R.string.player_seek))
        val node = bar.fetchSemanticsNode()
        assertNotNull(node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo))
        assertNotNull(node.config.getOrNull(SemanticsActions.SetProgress))
        assertEquals("12:30 of 45:00", node.config.getOrNull(SemanticsProperties.StateDescription))
        bar.performSemanticsAction(SemanticsActions.SetProgress) { it(600_000f) }
        rule.waitForIdle()
        assertEquals(listOf("seek:600000"), log.calls)
    }

    @Test fun theTimesShowElapsedAndRemaining() {
        showPlayer()
        assertShown("12:30"); assertShown("−32:30")
    }

    @Test fun anEpisodeWithNoEarlierOneSaysSoOnPrevious() {
        showPlayer()
        val previous = rule.onNodeWithContentDescription(text(R.string.player_previous)).fetchSemanticsNode()
        assertEquals(text(R.string.player_no_previous), previous.config.getOrNull(SemanticsProperties.StateDescription))
        assertTrue(previous.config.contains(SemanticsProperties.Disabled))
        rule.onNodeWithContentDescription(text(R.string.player_next)).performClick()
        assertEquals(listOf("next"), log.calls)
    }

    @Test fun aFilmHasNoPreviousOrNext() {
        showPlayer(ui(playingEpisode.copy(episodeLine = null, next = null)))
        assertTrue(!described(text(R.string.player_previous)) && !described(text(R.string.player_next)))
    }

    @Test fun pausedOffersPlayAndTheCentreButtonsDoWhatTheySay() {
        showPlayer(ui(playingEpisode.copy(phase = Phase.PAUSED)))
        rule.onNodeWithContentDescription(text(R.string.player_play)).performClick()
        rule.onNodeWithContentDescription(text(R.string.player_back_10)).performClick()
        rule.onNodeWithContentDescription(text(R.string.player_forward_10)).performClick()
        assertEquals(listOf("playPause", "skipBack", "skipForward"), log.calls)
    }

    @Test fun hiddenControlsShowNone() {
        showPlayer(ui().copy(controlsVisible = false))
        assertTrue(rule.onAllNodes(hasContentDescription(text(R.string.player_back_10))).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).fetchSemanticsNodes().isEmpty())
    }

    @Test fun theStreamLineShowsUnderTheTitleOnlyAtTheStart() {
        showPlayer(ui().copy(streamInfoShown = true))
        assertShown(text(R.string.stream_direct_play))
    }
}

@Config(qualifiers = PLAYER_PHONE) class PlayerControlsPhoneRenderTest : PlayerControlsRenderBase() { override val fontScale = 1f }
@Config(qualifiers = PLAYER_PHONE) class PlayerControlsPhoneLargeTextRenderTest : PlayerControlsRenderBase() { override val fontScale = 2f }
@Config(qualifiers = PLAYER_TABLET) class PlayerControlsTabletRenderTest : PlayerControlsRenderBase() { override val fontScale = 1f }
@Config(qualifiers = PLAYER_TABLET) class PlayerControlsTabletLargeTextRenderTest : PlayerControlsRenderBase() { override val fontScale = 2f }
