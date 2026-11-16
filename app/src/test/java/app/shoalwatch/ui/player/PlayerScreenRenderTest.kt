package app.shoalwatch.ui.player

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
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

    // ---- watch-and-delete (Milestone 5)

    private val prompt = WatchedPromptUi(app.shoalwatch.ui.browse.BEN, id(101), film = false, together = 0, always = false)
    private val upNext = UpNextState(id(102), "The Turn", 8, cancelled = false)

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun root() = rule.onRoot().fetchSemanticsNode().boundsInRoot

    private fun assertInside(tag: String) {
        val b = bounds(tag); val r = root()
        assertTrue("$tag off screen: $b in $r", b.left >= r.left && b.top >= r.top && b.right <= r.right && b.bottom <= r.bottom)
    }

    @Test fun thePromptAndUpNextNeverOverlapAndBothStayOnScreen() {
        for (controls in listOf(true, false)) {
            showPlayerOnce(ui(playingEpisode.copy(upNext = upNext)).copy(watched = prompt.copy(together = 2), controlsVisible = controls))
            assertInside(WATCHED_PROMPT_TAG); assertInside(UP_NEXT_TAG)
            assertTrue("they overlap (controls $controls)", !bounds(WATCHED_PROMPT_TAG).overlaps(bounds(UP_NEXT_TAG)))
        }
    }

    @Test fun everyPromptButtonIsAtLeast48dpWithItsOwnLabel() {
        showPlayer(ui(playingEpisode.copy(upNext = upNext)).copy(watched = prompt.copy(together = 1)))
        val buttons = rule.onAllNodes(hasClickAction().and(hasAnyAncestor(hasTestTag(WATCHED_PROMPT_TAG)))).fetchSemanticsNodes()
        assertEquals(3, buttons.size)
        val density = rule.density
        for (b in buttons) assertTrue("${b.size} is under 48dp", with(density) { b.size.height.toDp() >= 48.dp && b.size.width.toDp() >= 48.dp })
        assertDescribed(text(R.string.watched_together_label))
        click(text(R.string.watched_delete)); click(text(R.string.watched_together)); click(text(R.string.watched_keep))
        assertEquals(listOf("watchedDelete", "watchedTogether", "keepDownload"), log.calls)
    }

    @Test fun watchedTogetherIsAbsentWhenNoOtherProfileShares() {
        showPlayer(ui().copy(watched = prompt))
        assertShown(text(R.string.watched_delete)); assertShown(text(R.string.watched_keep))
        assertNotShown(text(R.string.watched_together))
    }

    @Test fun theCardIsAPoliteLiveRegionSayingItsTextThenTheFirstButton() {
        showPlayer(ui().copy(watched = prompt.copy(film = true)))
        assertShown(text(R.string.watched_film))
        val spoken = "${text(R.string.watched_film)} ${text(R.string.watched_delete_button_spoken)}"
        rule.onNode(hasContentDescription(spoken).and(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))).assertExists()
    }

    @Test fun alwaysShowsOnlyTheInformationLine() {
        showPlayer(ui().copy(watched = prompt.copy(always = true)))
        assertShown(text(R.string.watched_episode)); assertShown(text(R.string.watched_always))
        assertNotShown(text(R.string.watched_delete)); assertNotShown(text(R.string.watched_keep))
    }

    @Test fun theCardIsHiddenWhileLocked() {
        showPlayer(ui().copy(watched = prompt, locked = true))
        assertNotShown(text(R.string.watched_episode))
    }

    @Test fun thePromptsTextIsAtLeast13sp() {
        showPlayer(ui(playingEpisode.copy(upNext = upNext)).copy(watched = prompt.copy(together = 1)))
        val texts = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text).and(hasAnyAncestor(hasTestTag(WATCHED_PROMPT_TAG)))).fetchSemanticsNodes()
        assertTrue(texts.isNotEmpty())
        for (node in texts) {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val size = layouts.single().layoutInput.style.fontSize
            assertTrue("$size is under 13sp", size.value >= 13f)
        }
    }

    @Test fun eachWatchedMessageIsSaidOnceInTheSnackbar() {
        showPlayer(ui().copy(watchedMessage = WatchedMessage.KEPT_UNTIL_EVERYONE))
        rule.waitUntil(5_000) { "dismissWatched" in log.calls || shown(text(R.string.watched_kept_until_everyone)) }
        assertTrue(shown(text(R.string.watched_kept_until_everyone)) || "dismissWatched" in log.calls)
    }

    /** [show] may run once per test: the second state goes through a holder instead. */
    private var holder: androidx.compose.runtime.MutableState<PlayerUiState>? = null
    private fun showPlayerOnce(state: PlayerUiState) {
        val h = holder
        if (h == null) {
            val created = androidx.compose.runtime.mutableStateOf(state)
            holder = created
            show { PlayerScreen(created.value, log.actions) }
        } else {
            h.value = state
        }
        rule.waitForIdle()
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
