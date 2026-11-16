package app.shoalwatch.ui.player

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.shoalwatch.R
import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.MediaSegment
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.data.playback.SegmentKind
import app.shoalwatch.data.playback.UpNextState
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.id
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** The player screen's states: Up next, skip, an error, locked, loading, and the sheets' choices. */
abstract class PlayerScreenRenderBase : RenderBase() {
    private val log = ActionLog()

    private fun showPlayer(state: PlayerUiState) = show { PlayerScreen(state, log.actions) }
    /** Scrolled to first when it is inside something that scrolls (a sheet, the error), then pressed. */
    private fun click(label: String) {
        val node = rule.onAllNodes(hasText(label)).onFirst()
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    @Test fun upNextShowsTheCountdownWithPlayNowAndCancel() {
        showPlayer(ui(playingEpisode.copy(upNext = UpNextState(id(102), "The Turn", 8, cancelled = false))).copy(controlsVisible = false))
        assertShown("Up next: The Turn")
        assertDescribed("Playing in 8 seconds") // the count is told as one announcement, not read out every second
        click(text(R.string.up_next_play))
        click(text(R.string.action_cancel))
        assertEquals(listOf("playUpNext", "cancelUpNext"), log.calls)
    }

    @Test fun oneSecondLeftIsSaidInTheSingular() {
        showPlayer(ui(playingEpisode.copy(upNext = UpNextState(id(102), "The Turn", 1, cancelled = false))).copy(controlsVisible = false))
        assertDescribed("Playing in 1 second")
    }

    @Test fun whileLockedTheSystemBackStaysInThePlayerAndShowsTheLock() {
        var back: androidx.activity.OnBackPressedDispatcher? = null
        show {
            back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PlayerScreen(ui().copy(locked = true, controlsVisible = false), log.actions)
        }
        rule.runOnIdle { back!!.onBackPressed() }
        assertEquals("shown the lock and its hint, not left", listOf("tap"), log.calls)
    }

    @Test fun unlockedTheSystemBackIsNotTaken() {
        var back: androidx.activity.OnBackPressedDispatcher? = null
        show {
            back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PlayerScreen(ui(), log.actions)
        }
        rule.runOnIdle { assertTrue(back!!.hasEnabledCallbacks().not()) }
    }

    @Test fun upNextWithoutAutoplayOffersPlayNextEpisodeAndClose() {
        showPlayer(ui(playingEpisode.copy(upNext = UpNextState(id(102), null, null, cancelled = false))))
        assertShown(text(R.string.up_next_untitled))
        assertShown(text(R.string.up_next_play_manual))
        assertShown(text(R.string.up_next_close))
        assertNotShown(text(R.string.up_next_play))
    }

    @Test fun aCancelledUpNextIsGone() {
        showPlayer(ui(playingEpisode.copy(upNext = UpNextState(id(102), "The Turn", null, cancelled = true))))
        assertNotShown("Up next: The Turn")
    }

    @Test fun theSkipButtonShowsOnlyInsideASegmentAndSaysWhich() {
        showPlayer(ui(playingEpisode.copy(segmentNow = MediaSegment(SegmentKind.INTRO, 0, 60_000))))
        assertShown(text(R.string.skip_intro))
        click(text(R.string.skip_intro))
        assertEquals(listOf("skipSegment"), log.calls)
    }

    @Test fun noSegmentNoSkipButton() {
        showPlayer(ui())
        SegmentKind.entries.forEach { assertNotShown(text(app.shoalwatch.ui.present.PlayerText.skipLabel(it))) }
    }

    @Test fun creditsSaySkipCredits() {
        showPlayer(ui(playingEpisode.copy(segmentNow = MediaSegment(SegmentKind.OUTRO, 2_600_000, 2_700_000))))
        assertShown(text(R.string.skip_credits))
        assertNotShown(text(R.string.skip_intro))
    }

    @Test fun aFailureShowsItsMessageWithTryAgainAndBack() {
        showPlayer(ui(playingEpisode.copy(phase = Phase.FAILED, error = AppError.Kind.OFFLINE)))
        assertShown(AppError.of(AppError.Kind.OFFLINE).userMessage)
        click(text(R.string.action_retry))
        click(text(R.string.cd_back))
        assertEquals(listOf("retry", "back"), log.calls)
    }

    @Test fun aSignInFailureOffersSignIn() {
        showPlayer(ui(playingEpisode.copy(phase = Phase.FAILED, error = AppError.Kind.UNAUTHORISED)))
        click(text(R.string.action_sign_in))
        assertEquals(listOf("signIn"), log.calls)
        assertNotShown(text(R.string.action_retry))
    }

    @Test fun lockedLeavesOnlyTheUnlockButtonToPress() {
        showPlayer(ui(playingEpisode.copy(segmentNow = MediaSegment(SegmentKind.INTRO, 0, 60_000))).copy(locked = true))
        val clickable = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertEquals(tree(), listOf(listOf(text(R.string.player_unlock))), clickable.map { it.config.getOrNull(SemanticsProperties.ContentDescription) })
        assertShown(text(R.string.player_locked_hint))
    }

    @Test fun loadingSaysSo() {
        showPlayer(ui(playingEpisode.copy(phase = Phase.RESOLVING)).copy(controlsVisible = false))
        assertDescribed(text(R.string.player_loading))
    }

    @Test fun theSubtitleStyleChoicesAreRadioButtonsThatSayWhichIsSelected() {
        showPlayer(ui().copy(sheet = PlayerSheet.SUBTITLE_STYLE))
        rule.onNode(hasText("100%").and(isSelectable())).performScrollTo().assertIsSelected()
        rule.onNode(hasText("150%").and(isSelectable())).performScrollTo().assertIsNotSelected()
        rule.onNode(hasText(text(R.string.subtitle_colour_white)).and(isSelectable())).performScrollTo().assertIsSelected()
        val radios = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).fetchSemanticsNodes()
        assertEquals("4 sizes, 4 colours, 3 backgrounds, 3 edges", 14, radios.size)
        rule.onNode(hasText("150%").and(isSelectable())).performScrollTo().performClick()
        assertEquals(listOf("appearance:150"), log.calls)
    }

    @Test fun theDelayIsOffForSubtitlesItCannotMoveAndSaysWhy() {
        showPlayer(ui(playingEpisode.copy(subtitle = app.shoalwatch.data.playback.SubtitleChoice.On(3, app.shoalwatch.data.playback.SubtitleDelivery.IN_STREAM))).copy(sheet = PlayerSheet.SUBTITLE_STYLE))
        rule.onNodeWithText(text(R.string.subtitle_delay_not_in_stream)).performScrollTo()
        assertShown(text(R.string.subtitle_delay_not_in_stream))
    }

    @Test fun theSpeedSheetMarksTheCurrentSpeed() {
        showPlayer(ui().copy(sheet = PlayerSheet.SPEED))
        rule.onNode(hasText("1×").and(isSelectable())).performScrollTo().assertIsSelected()
        rule.onNode(hasText("1.5×").and(isSelectable())).performScrollTo().performClick()
        assertEquals(listOf("speed:1.5"), log.calls)
    }

    @Test fun theStreamInfoSheetSaysHowItPlays() {
        showPlayer(ui().copy(sheet = PlayerSheet.STREAM_INFO))
        assertShown(text(R.string.stream_direct_play))
        assertTrue(shown(text(R.string.quality_maximum)))
    }
}

@Config(qualifiers = PLAYER_PHONE) class PlayerScreenPhoneRenderTest : PlayerScreenRenderBase() { override val fontScale = 1f }
@Config(qualifiers = PLAYER_PHONE) class PlayerScreenPhoneLargeTextRenderTest : PlayerScreenRenderBase() { override val fontScale = 2f }
@Config(qualifiers = PLAYER_TABLET) class PlayerScreenTabletRenderTest : PlayerScreenRenderBase() { override val fontScale = 1f }
@Config(qualifiers = PLAYER_TABLET) class PlayerScreenTabletLargeTextRenderTest : PlayerScreenRenderBase() { override val fontScale = 2f }
