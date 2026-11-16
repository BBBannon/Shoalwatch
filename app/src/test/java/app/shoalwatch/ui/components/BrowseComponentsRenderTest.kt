package app.shoalwatch.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.present.BannerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** The shared pieces compose, carry the expected labels and meet the size rules, on a phone and a tablet at both text sizes. */
abstract class BrowseComponentsRenderBase : RenderBase() {
    private val card = sampleCard("The Example Film", "2019", watched = true)

    @Test fun aPosterCardIsOneLabelledElementWithASeparateMoreButtonOfAtLeast48dp() {
        var opened = 0
        var menus = 0
        show { PosterCard(card.copy(label = "The Example Film, 2019, watched"), { opened++ }, { menus++ }, width = 128.dp) }
        assertDescribed("The Example Film, 2019, watched")
        rule.onNodeWithContentDescription("The Example Film, 2019, watched").performClick()
        assertEquals(1, opened)
        rule.onNodeWithContentDescription(card.menuLabel).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, menus)
        assertEquals(1, opened)
    }

    @Test fun aLongPressOrTheCustomActionOpensTheMenu() {
        var menus = 0
        show { PosterCard(card.copy(label = "Film"), {}, { menus++ }, width = 128.dp) }
        rule.onNodeWithContentDescription("Film").performTouchInput { longClick() }
        assertEquals(1, menus)
        val custom = rule.onNodeWithContentDescription("Film").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        rule.runOnIdle { custom.first { it.label == card.menuLabel }.action() }
        assertEquals(2, menus)
    }

    @Test fun aPosterCardKeepsItsWidth() {
        show { PosterCard(card.copy(label = "Film"), {}, {}, width = 128.dp) }
        rule.onNodeWithContentDescription("Film").assertWidthIsEqualTo(128.dp)
    }

    @Test fun aLongTitleWrapsAndNeverDisappears() {
        val long = sampleCard("A Very Long Title For A Small Poster Card", "2021").copy(label = "long")
        show { PosterCard(long, {}, {}, width = 128.dp) }
        assertShown(long.title)
    }

    @Test fun aWideCardShowsItsTitleSubtitleAndPercent() {
        val wide = sampleCard("Tidal", "S2 E5 · The Turning Tide", progress = 0.42f)
        show { WideCard(wide, {}, {}) }
        assertShown("Tidal")
        assertShown("S2 E5 · The Turning Tide")
        assertShown("42% watched")
    }

    @Test fun anEpisodeRowShowsItsPartsAndHasTwoControls() {
        val episode = sampleEpisode("E2 · The Turning Tide", progress = 0.4f)
        var opened = 0
        var menus = 0
        show { EpisodeRow(episode.copy(label = "E2 row"), { opened++ }, { menus++ }) }
        assertShown("E2 · The Turning Tide")
        assertShown("47 min · 40% watched")
        rule.onNodeWithContentDescription("E2 row").performClick()
        rule.onNodeWithContentDescription(episode.menuLabel).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, opened)
        assertEquals(1, menus)
    }

    @Test fun everyBannerKindShowsItsWordsAndItsOneButton() {
        val clicks = mutableListOf<String>()
        val actions = BannerActions({ clicks += "retry" }, { clicks += "signin" }, { clicks += "review" })
        val expected = mapOf(
            BannerKind.OFFLINE to (R.string.banner_offline to R.string.action_retry),
            BannerKind.SIGN_IN to (R.string.banner_signin to R.string.action_sign_in),
            BannerKind.CERTIFICATE to (R.string.banner_certificate to R.string.action_review),
            BannerKind.GENERIC to (R.string.banner_generic to R.string.action_retry),
        )
        show { Column { BannerKind.entries.forEach { FreshnessBanner(it, actions) } } }
        for ((_, pair) in expected) {
            assertShown(text(pair.first))
        }
        for (button in listOf(R.string.action_sign_in, R.string.action_review)) {
            rule.onAllNodes(hasText(text(button))).onFirst().performClick()
        }
        rule.onAllNodes(hasText(text(R.string.action_retry))).onFirst().performClick()
        assertEquals(listOf("signin", "review", "retry"), clicks)
    }

    @Test fun emptyStateShowsItsHeadingSentenceAndAction() {
        var taps = 0
        show { EmptyState("Nothing here", "Add something.", actionLabel = "Try again", onAction = { taps++ }) }
        assertShown("Nothing here")
        assertShown("Add something.")
        rule.onAllNodes(hasText("Try again")).onFirst().performClick()
        assertEquals(1, taps)
        assertTrue(headings() >= 1)
    }

    @Test fun skeletonsAreAnnouncedAsLoading() {
        show { Column { SkeletonRow(16f / 9f, 264.dp); SkeletonEpisodes() } }
        assertTrue(rule.onAllNodes(hasContentDescription(text(R.string.cd_loading_content))).fetchSemanticsNodes().size >= 2)
    }

    @Test fun badgesAndBarsCompose() {
        show { Row { WatchedBadge(); WatchedBadge(working = true); UnwatchedBadge(120); WorkingBadge(); ProgressBarThin(0.5f) } }
        assertTrue(rule.onAllNodesWithText("99+", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun theSheetListsThePlainActionsForAnEpisodeInContinueWatching() {
        var played: Pair<String, Boolean>? = null
        show {
            ItemActionsSheet(
                SheetTarget("e1", "The Tide", played = false, favourite = true, canRemoveFromResume = true, seriesId = "s1"),
                SheetActions({}, { id, p -> played = id to p }, { _, _ -> }, {}, {}),
                onDismiss = {},
            )
        }
        rule.waitForIdle()
        assertShown(text(R.string.sheet_remove_resume))
        assertShown(text(R.string.sheet_mark_watched))
        assertShown(text(R.string.sheet_unfavourite))
        assertShown(text(R.string.sheet_go_series))
        assertShown(text(R.string.sheet_details))
        rule.onAllNodes(hasText(text(R.string.sheet_mark_watched))).onFirst().performClick()
        assertEquals("e1" to true, played)
    }

    @Test fun aWatchedItemOffersMarkUnwatchedAndNoSeriesLinkForAFilm() {
        show {
            ItemActionsSheet(
                SheetTarget("f1", "A Film", played = true, favourite = false, canRemoveFromResume = false, seriesId = null),
                SheetActions({}, { _, _ -> }, { _, _ -> }, {}, {}),
                onDismiss = {},
            )
        }
        rule.waitForIdle()
        assertShown(text(R.string.sheet_mark_unwatched))
        assertShown(text(R.string.sheet_favourite))
        assertNotShown(text(R.string.sheet_remove_resume))
        assertNotShown(text(R.string.sheet_go_series))
    }
}

@Config(qualifiers = "w360dp-h640dp") class BrowseComponentsPhoneRenderTest : BrowseComponentsRenderBase() { override val fontScale = 1f }
@Config(qualifiers = "w360dp-h640dp") class BrowseComponentsPhoneLargeTextRenderTest : BrowseComponentsRenderBase() { override val fontScale = 2f }
@Config(qualifiers = "w840dp-h1200dp") class BrowseComponentsTabletRenderTest : BrowseComponentsRenderBase() { override val fontScale = 1f }
@Config(qualifiers = "w840dp-h1200dp") class BrowseComponentsTabletLargeTextRenderTest : BrowseComponentsRenderBase() { override val fontScale = 2f }
