package app.shoalwatch.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.present.BannerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Layout and spoken-state fixes found by the reviews and the emulator walkthrough, proved where a JVM test can. Robolectric measures
 * text far narrower than a phone, so these assert HEIGHTS, positions and semantics, not text widths.
 */
abstract class BrowseFixesRenderBase : RenderBase() {
    private fun stateOf(label: String) = rule.onNodeWithContentDescription(label).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    // --- W1: the highlighted episode row --------------------------------------------------------------------------

    @Test fun aHighlightedEpisodeWithProgressIsAsTallAsAnOrdinaryOneAndKeepsAllItsText() {
        val episode = sampleEpisode("E2 · The Turning Tide", progress = 0.4f)
        show {
            Box(Modifier.width(360.dp)) {
                Column {
                    EpisodeRow(episode.copy(label = "plain row"), {}, {})
                    EpisodeRow(episode.copy(label = "lit row"), {}, {}, highlighted = true)
                }
            }
        }
        val plain = rule.onNodeWithContentDescription("plain row").getBoundsInRoot()
        val lit = rule.onNodeWithContentDescription("lit row").getBoundsInRoot()
        val limit = (if (fontScale > 1f) 400 else 200).dp
        assertTrue("plain row is ${plain.h} tall", plain.h < limit)
        assertTrue("highlighted row is ${lit.h} tall (the squeezed text made it ~420dp)", lit.h < limit)
        assertEquals(plain.h.value, lit.h.value, 1f)
        // The same texts, each really laid out. The bug was the progress bar filling the whole row, which left the text column NO width:
        // the texts were there in the tree but measured zero wide (and the merged row looked empty). Robolectric measures text narrow,
        // but never zero.
        for (shownText in listOf("E2 · The Turning Tide", "47 min · 40% watched", episode.overview)) {
            val nodes = rule.onAllNodesWithText(shownText, useUnmergedTree = true)
            assertEquals("text missing or repeated: $shownText", 2, nodes.fetchSemanticsNodes().size)
            for (i in 0..1) {
                val bounds = nodes[i].fetchSemanticsNode().boundsInRoot
                assertTrue("$shownText (row $i) has no width: $bounds", bounds.width > 4f)
            }
        }
    }

    @Test fun aHighlightedEpisodeSaysSoInWordsAndAWorkingOneSaysWorking() {
        show {
            Column {
                EpisodeRow(sampleEpisode("A").copy(label = "lit"), {}, {}, highlighted = true)
                EpisodeRow(sampleEpisode("B").copy(label = "busy"), {}, {}, working = true)
                EpisodeRow(sampleEpisode("C").copy(label = "plain"), {}, {})
            }
        }
        assertEquals("You were watching this", stateOf("lit"))
        assertEquals(text(R.string.cd_working), stateOf("busy"))
        assertEquals(null, stateOf("plain"))
    }

    @Test fun aWorkingCardSaysWorking() {
        show { Column { WideCard(sampleCard("Tidal", "S2 E5").copy(label = "wide"), {}, {}, working = true); PosterCard(sampleCard("Film").copy(label = "poster"), {}, {}, width = 128.dp, working = true) } }
        assertEquals(text(R.string.cd_working), stateOf("wide"))
        assertEquals(text(R.string.cd_working), stateOf("poster"))
    }

    @Test fun theProgressBarOfAWideCardIsInsideThePictureAndTheCardKeepsItsWidth() {
        show { WideCard(sampleCard("Tidal", "S2 E5 · The Turning Tide", progress = 0.42f).copy(label = "wide"), {}, {}) }
        val card = rule.onNodeWithContentDescription("wide").getBoundsInRoot()
        assertEquals(PhoneWideCardWidth.value, card.w.value, 1f)
    }

    // --- spoken states of controls -----------------------------------------------------------------------------

    @Test fun theWatchedAndFavouriteButtonsSaySoWhileTheyWork() {
        show { Column { WatchedButton("Mark as watched", false, working = true, onClick = {}); FavouriteButton(false, working = true, onClick = {}) } }
        val working = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.cd_working))).fetchSemanticsNodes()
        assertEquals(2, working.size)
    }

    @Test fun theButtonsSayNothingExtraWhenIdle() {
        show { Column { WatchedButton("Mark as watched", false, working = false, onClick = {}); FavouriteButton(false, working = false, onClick = {}) } }
        assertEquals(0, rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)).fetchSemanticsNodes().size)
    }

    // --- banners ----------------------------------------------------------------------------------------------

    @Test fun theBannerAnnouncesItsMessageAloneAndItsButtonStaysAControl() {
        show { FreshnessBanner(BannerKind.OFFLINE, BannerActions({}, {}, {})) }
        val live = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).fetchSemanticsNodes()
        assertEquals(1, live.size)
        assertEquals(listOf(text(R.string.banner_offline)), live.single().config[SemanticsProperties.Text].map { it.text })
        // The button is its own clickable element, not merged into the message.
        val button = rule.onAllNodes(hasText(text(R.string.action_retry))).onFirst().fetchSemanticsNode()
        assertTrue(button.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick))
    }

    @Test fun theCertificateBannerSaysCheckCertificateAndNoBannerUsesADash() {
        assertEquals("Check certificate", text(R.string.action_review))
        for (id in listOf(R.string.banner_offline, R.string.banner_signin, R.string.banner_certificate, R.string.banner_generic, R.string.search_offline)) {
            assertTrue(text(id), !text(id).contains("—") && !text(id).contains(" - ") && !text(id).contains("–"))
        }
        assertEquals("You’re offline. Showing what’s saved on this phone.", text(R.string.banner_offline))
        assertEquals("You’re offline. Searching what’s saved on this phone.", text(R.string.search_offline))
    }

    @Test fun anErrorStateGivesOneMessageAndTheButtonThatFitsTheFailure() {
        val clicks = mutableListOf<String>()
        show { ErrorState(BannerKind.CERTIFICATE, BannerActions({ clicks += "retry" }, { clicks += "signin" }, { clicks += "review" })) }
        assertEquals(1, rule.onAllNodes(hasText(text(R.string.action_review))).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(hasText(text(R.string.action_retry))).fetchSemanticsNodes().size)
        rule.onAllNodes(hasText(text(R.string.action_review))).onFirst().performClick()
        assertEquals(listOf("review"), clicks)
    }

    // --- expandable text ------------------------------------------------------------------------------------------

    @Test fun theExpandButtonSaysShowMoreThenShowLessAndWhetherItIsExpanded() {
        show { Box(Modifier.width(200.dp)) { ExpandableText((1..30).joinToString("\n") { "line $it" }) } }
        rule.waitForIdle()
        fun button(label: String) = rule.onAllNodes(hasText(label)).onFirst()
        assertEquals("Show more", text(R.string.action_more))
        assertEquals(text(R.string.state_collapsed), button("Show more").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        button("Show more").performClick()
        rule.waitForIdle()
        assertEquals("Show less", text(R.string.action_less))
        assertEquals(text(R.string.state_expanded), button("Show less").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }

    // --- the actions sheet -----------------------------------------------------------------------------------------

    @Test fun theSheetScrollsSoTheLastRowIsAlwaysReachable() {
        show {
            ItemActionsSheet(
                SheetTarget("e1", "Harbour Lights · S2 E2 · Episode 2", played = false, favourite = true, canRemoveFromResume = true, seriesId = "s1"),
                SheetActions({}, { _, _ -> }, { _, _ -> }, {}, {}),
                onDismiss = {},
            )
        }
        rule.waitForIdle()
        assertTrue("the sheet has no scroll container", rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty())
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text(R.string.sheet_details)))
        assertTrue(shown(text(R.string.sheet_details)))
        assertShown("Harbour Lights · S2 E2 · Episode 2")
    }

    // --- badges and counts ------------------------------------------------------------------------------------------

    @Test fun theBigUnwatchedCountComesFromAResource() {
        assertEquals("99+", text(R.string.unwatched_many))
        show { UnwatchedBadge(120) }
        assertTrue(rule.onAllNodesWithText(text(R.string.unwatched_many), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun someOtherLabelsAreSimpleWords() {
        assertEquals("Search", text(R.string.search_hint))
        assertEquals("Account menu", text(R.string.account_menu_cd))
        assertEquals("Season 2, episode 5", text(R.string.episode_spoken_full, "2", "5"))
    }
}

private val androidx.compose.ui.unit.DpRect.h get() = bottom - top
private val androidx.compose.ui.unit.DpRect.w get() = right - left

private fun androidx.compose.ui.semantics.SemanticsConfiguration.getOrNull(key: androidx.compose.ui.semantics.SemanticsPropertyKey<String>): String? =
    if (contains(key)) get(key) else null

@Config(qualifiers = "w360dp-h640dp") class BrowseFixesPhoneRenderTest : BrowseFixesRenderBase() { override val fontScale = 1f }
@Config(qualifiers = "w360dp-h640dp") class BrowseFixesPhoneLargeTextRenderTest : BrowseFixesRenderBase() { override val fontScale = 2f }
@Config(qualifiers = "w840dp-h1200dp") class BrowseFixesTabletRenderTest : BrowseFixesRenderBase() { override val fontScale = 1f }
@Config(qualifiers = "w840dp-h1200dp") class BrowseFixesTabletLargeTextRenderTest : BrowseFixesRenderBase() { override val fontScale = 2f }
