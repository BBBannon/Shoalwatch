package app.shoalwatch.ui.downloads

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.shoalwatch.R
import app.shoalwatch.data.downloads.FilmUse
import app.shoalwatch.data.downloads.SeriesUse
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.downloads.StorageView
import app.shoalwatch.data.downloads.VolumeUse
import app.shoalwatch.data.downloads.WatchedSummary
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.PHONE
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.TABLET
import app.shoalwatch.ui.browse.entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** Storage: headings, one element per row with its whole meaning, 48dp deletes with their own labels, and the confirms. */
abstract class StorageRenderBase : RenderBase() {
    private val calls = mutableListOf<String>()
    private val actions = StorageActions(
        onBack = { calls += "back" }, onDeleteWatched = { calls += "watched" }, onDeleteSeries = { calls += "series:$it" },
        onDeleteFilm = { calls += "film:$it" }, onConfirm = { calls += "confirm" }, onCancel = { calls += "cancel" }, onTryAgain = { calls += "again" },
    )

    private val show = SeriesUse(entry(10, ItemKind.SERIES, "Ben's Show"), episodes = 3, watched = 1, bytes = 1_800_000_000, deletableBytes = 1_200_000_000, ownedEpisodes = 2)
    private val shared = SeriesUse(entry(11, ItemKind.SERIES, "Shared Show"), episodes = 1, watched = 0, bytes = 640_000_000, deletableBytes = 0, ownedEpisodes = 0)
    private val film = FilmUse(entry(20, name = "Ben's Film"), bytes = 4_000_000_000, watched = true, ownedByMe = true)
    private val theirs = FilmUse(entry(21, name = "Their Film"), bytes = 2_000_000_000, watched = false, ownedByMe = false)
    private val volumes = listOf(
        VolumeUse(StorageKind.INTERNAL, null, 12_000_000_000, 6_000_000_000, true),
        VolumeUse(StorageKind.REMOVABLE, "CARD-1", 0, 3_000_000_000, false),
    )
    private val full = StorageView(volumes, listOf(show, shared), listOf(film, theirs), otherProfilesBytes = 5_000_000_000, autoNextBlocked = true)

    private fun showStorage(view: StorageView? = full, confirm: StorageConfirm? = null) =
        show { StorageScreen(StorageUiState(BEN, view, loading = false, localFailure = false, confirm = confirm), actions) }

    private fun list() = rule.onAllNodes(hasScrollAction())[0]

    private fun reach(label: String, description: Boolean = false) =
        list().performScrollToNode(if (description) hasContentDescription(label) else hasText(label, substring = true))

    private fun headingTexts(): List<String> = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes()
        .mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }

    private fun said(contains: String): String =
        rule.onAllNodes(hasText(contains, substring = true)).fetchSemanticsNodes().single().config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()

    @Test fun headingsAreHeadingsAndTheVolumesSayTheirSpace() {
        showStorage()
        val seen = mutableSetOf<String>().apply { addAll(headingTexts()) }
        reach(text(R.string.storage_films)); seen += headingTexts()
        assertTrue(seen.toString(), seen.containsAll(listOf(text(R.string.storage_title), text(R.string.storage_series), text(R.string.storage_films))))
        list().performScrollToNode(hasText(text(R.string.storage_phone_free, "12 GB")))
        assertTrue(said(text(R.string.storage_phone_free, "12 GB")).contains(text(R.string.storage_downloads_use, "6 GB")))
        reach(text(R.string.storage_card_out, "3 GB"))
        reach(text(R.string.storage_auto_next_blocked))
    }

    @Test fun eachRowIsOneElementWithItsWholeMeaning() {
        showStorage()
        reach("Ben's Show")
        val row = said("Ben's Show")
        assertTrue(row, row.contains("3 episodes · 1.8 GB") && row.contains("1 watched"))
        reach("Shared Show")
        assertTrue(said("Shared Show").contains(text(R.string.storage_not_owned)))
        reach("Their Film")
        val theirRow = said("Their Film")
        assertTrue(theirRow, theirRow.contains("2 GB") && theirRow.contains(text(R.string.storage_not_owned)))
        reach(text(R.string.storage_others, "5 GB"))
    }

    @Test fun everyDeleteIs48dpWithItsOwnLabelAndNoneOnARowItDoesNotOwn() {
        showStorage()
        for ((label, call) in listOf(
            text(R.string.storage_delete_series, "Ben's Show") to "series:${show.series.item.id}",
            text(R.string.storage_delete_film, "Ben's Film") to "film:${film.film.item.id}",
        )) {
            reach(label, description = true)
            val node = rule.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().single()
            assertTrue("$label under 48dp", node.size.height / node.layoutInfo.density.density >= 47.5f && node.size.width / node.layoutInfo.density.density >= 47.5f)
            rule.onAllNodes(hasContentDescription(label))[0].performClick()
            assertEquals(call, calls.last())
        }
        assertTrue(rule.onAllNodes(hasContentDescription(text(R.string.storage_delete_series, "Shared Show"))).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(hasContentDescription(text(R.string.storage_delete_film, "Their Film"))).fetchSemanticsNodes().isEmpty())
        reach(text(R.string.storage_delete_watched))
        rule.onAllNodes(hasText(text(R.string.storage_delete_watched)))[0].performClick()
        assertEquals("watched", calls.last())
        assertNoTextUnder13sp(rule)
    }

    @Test fun theWatchedConfirmSaysTheSharedLineOnlyWhenItApplies() {
        showStorage(confirm = StorageConfirm.Watched(WatchedSummary(3, 1_800_000_000, 0)))
        assertShown("Delete 3 watched downloads?")
        assertShown(text(R.string.delete_watched_body, "1.8 GB"))
        assertTrue(rule.onAllNodes(hasText("shared and are deleted", substring = true)).fetchSemanticsNodes().isEmpty())
        rule.onAllNodes(hasText(text(R.string.dl_delete_action)))[0].performClick()
        assertEquals("confirm", calls.last())
    }

    @Test fun theSharedLineShowsWhenSomeGoForEveryProfile() {
        showStorage(confirm = StorageConfirm.Watched(WatchedSummary(1, 640_000_000, 1)))
        assertShown("Delete 1 watched download?")
        assertTrue(rule.onAllNodes(hasText("1 of them is shared and is deleted for every profile.", substring = true)).fetchSemanticsNodes().isNotEmpty())
        val cancel = rule.onAllNodes(hasText(text(R.string.action_cancel))).fetchSemanticsNodes().single()
        assertTrue(cancel.size.height / cancel.layoutInfo.density.density >= 47.5f)
        rule.onAllNodes(hasText(text(R.string.action_cancel)))[0].performClick()
        assertEquals("cancel", calls.last())
    }

    @Test fun theSeriesAndFilmConfirmsSayWhatTheyFree() {
        showStorage(confirm = StorageConfirm.Series(show.series.item.id, "Ben's Show", 1_200_000_000))
        assertShown(text(R.string.storage_confirm_series_title, "Ben's Show"))
        assertShown(text(R.string.storage_confirm_series_body, "1.2 GB"))
    }

    @Test fun theFilmConfirmIsMilestone4s() {
        showStorage(confirm = StorageConfirm.Film(film.film.item.id, "Ben's Film", 4_000_000_000))
        assertShown(text(R.string.dl_delete_confirm, "4 GB"))
    }

    @Test fun theEmptyStateScrolls() {
        showStorage(StorageView(volumes.take(1), emptyList(), emptyList(), 0, false))
        reach(text(R.string.storage_empty))
        assertTrue(rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodes(hasText(text(R.string.storage_delete_watched))).fetchSemanticsNodes().isEmpty())
        assertNoTextUnder13sp(rule)
    }

    @Test fun aFailedReadOffersTryAgain() {
        show { StorageScreen(StorageUiState(BEN, null, loading = false, localFailure = true, confirm = null), actions) }
        rule.onAllNodes(hasText(text(R.string.action_retry)))[0].performClick()
        assertEquals("again", calls.last())
    }
}

@Config(qualifiers = PHONE) class StoragePhoneRenderTest : StorageRenderBase() { override val fontScale = 1f }
@Config(qualifiers = PHONE) class StoragePhoneLargeTextRenderTest : StorageRenderBase() { override val fontScale = 2f }
@Config(qualifiers = TABLET) class StorageTabletRenderTest : StorageRenderBase() { override val fontScale = 1f }
@Config(qualifiers = TABLET) class StorageTabletLargeTextRenderTest : StorageRenderBase() { override val fontScale = 2f }
