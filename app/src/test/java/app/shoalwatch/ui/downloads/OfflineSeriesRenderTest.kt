package app.shoalwatch.ui.downloads

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.shoalwatch.R
import app.shoalwatch.data.downloads.DownloadInfo
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.OfflineEntry
import app.shoalwatch.data.downloads.OfflineSeason
import app.shoalwatch.data.downloads.OfflineSeries
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.PHONE
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.TABLET
import app.shoalwatch.ui.browse.entry
import app.shoalwatch.ui.browse.episode
import app.shoalwatch.ui.browse.userData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** One downloaded series: seasons in order with Specials last, episodes in order, each with its own Play. */
abstract class OfflineSeriesRenderBase : RenderBase() {
    private val calls = mutableListOf<String>()
    private val actions = OfflineSeriesActions(onBack = { calls += "back" }, onOpenItem = { calls += "open:$it" }, onPlay = { calls += "play:$it" })

    private fun ep(n: Int, season: Int, number: Int, name: String, watched: Boolean = false, positionMs: Long = 0) = OfflineEntry(
        episode(n, season, number, name = name, data = userData(played = watched, positionMs = positionMs)).let { it.copy(item = it.item.copy(runtimeMs = 2_700_000)) },
        DownloadInfo(episode(n).item.id, DownloadState.COMPLETE, null, DownloadQuality.ORIGINAL, 1f, 1_000_000_000, true, StorageKind.INTERNAL, true),
    )

    private fun season(n: Int, number: Int, vararg eps: OfflineEntry) =
        OfflineSeason(entry(60 + n, ItemKind.SEASON, "Season $number").let { it.copy(item = it.item.copy(indexNumber = number)) }, number, eps.toList())

    private fun show(series: OfflineSeries?) = show { OfflineSeriesScreen(OfflineSeriesUiState(BEN, series, loading = false), actions) }

    private val sample = OfflineSeries(
        entry(50, ItemKind.SERIES, "The Show"),
        // Deliberately out of order: Specials first, then Season 2, then Season 1.
        listOf(
            season(0, 0, ep(200, 0, 1, "Behind the Scenes")),
            season(2, 2, ep(210, 2, 1, "Second Start")),
            season(1, 1, ep(101, 1, 1, "Pilot", watched = true), ep(102, 1, 2, "Next One", positionMs = 1_320_000)),
        ),
    )

    private fun headingTexts(): List<String> = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes()
        .mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }

    private fun scrollTo(label: String) = rule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasContentDescription(label))

    @Test fun seasonsAreInOrderWithSpecialsLastAndEpisodesInOrder() {
        show(sample)
        val order = listOf("Play Season 1, episode 1, Pilot", "Play Season 1, episode 2, Next One", "Play Season 2, episode 1, Second Start", "Play Behind the Scenes")
        val seenHeadings = mutableListOf<String>()
        for (label in order) {
            scrollTo(label)
            seenHeadings += headingTexts()
            val node = rule.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().single()
            val density = node.layoutInfo.density.density
            assertTrue("$label is under 48dp", node.size.height / density >= 47.5f)
        }
        val distinct = seenHeadings.filter { it.startsWith("Season") || it == text(R.string.season_specials) }.distinct()
        assertEquals(listOf("Season 1", "Season 2", text(R.string.season_specials)), distinct)
    }

    @Test fun aRowShowsItsCodeRuntimeAndWhatIsLeftAndPlayPlaysIt() {
        show(sample)
        scrollTo("Play Season 1, episode 2, Next One")
        assertTrue(tree(), shown("S1 E2 · Next One") || rule.onAllNodes(androidx.compose.ui.test.hasText("S1 E2", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertTrue(tree(), rule.onAllNodes(androidx.compose.ui.test.hasText("left", substring = true)).fetchSemanticsNodes().isNotEmpty())
        rule.onAllNodes(hasContentDescription("Play Season 1, episode 2, Next One"))[0].performClick()
        assertEquals(listOf("play:${episode(102).item.id}"), calls)
    }

    @Test fun aMalformedOrUnknownSeriesIsNoLongerAvailable() {
        show(OfflineSeries(null, emptyList()))
        assertShown(text(R.string.gone_title))
    }
}

@Config(qualifiers = PHONE) class OfflineSeriesPhoneRenderTest : OfflineSeriesRenderBase() { override val fontScale = 1f }
@Config(qualifiers = PHONE) class OfflineSeriesPhoneLargeTextRenderTest : OfflineSeriesRenderBase() { override val fontScale = 2f }
@Config(qualifiers = TABLET) class OfflineSeriesTabletRenderTest : OfflineSeriesRenderBase() { override val fontScale = 1f }
@Config(qualifiers = TABLET) class OfflineSeriesTabletLargeTextRenderTest : OfflineSeriesRenderBase() { override val fontScale = 2f }
