package app.shoalwatch.ui.downloads

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.downloads.DownloadFailure
import app.shoalwatch.data.downloads.DownloadInfo
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.OfflineEntry
import app.shoalwatch.data.downloads.OfflineLibrary
import app.shoalwatch.data.downloads.QueueStatus
import app.shoalwatch.data.downloads.SeriesSummary
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.PHONE
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.TABLET
import app.shoalwatch.ui.browse.entry
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** The Downloads tab at phone and tablet size, at text sizes 1.0 and 2.0. */
abstract class DownloadsRenderBase : RenderBase() {
    private val calls = mutableListOf<String>()
    private val actions = DownloadsActions(
        onOpenItem = { calls += "open:$it" }, onOpenSeries = { calls += "series:$it" }, onSignIn = { calls += "signIn:$it" },
        onPause = { calls += "pause:$it" }, onResume = { calls += "resume:$it" }, onRetry = { calls += "retry:$it" },
        onAskRemove = { calls += "ask:$it" }, onConfirmRemove = { calls += "confirm" }, onCancelRemove = { calls += "cancel" },
        onPauseAll = { calls += "pauseAll" }, onResumeAll = { calls += "resumeAll" }, onTryAgain = { calls += "tryAgain" },
    )

    private fun e(n: Int, name: String, state: DownloadState = DownloadState.COMPLETE, progress: Float? = 1f, failure: DownloadFailure? = null,
                  owned: Boolean = true, available: Boolean = true, kind: ItemKind = ItemKind.MOVIE) = OfflineEntry(
        entry(n, kind, name),
        DownloadInfo(entry(n).item.id, state, failure, DownloadQuality.ORIGINAL, progress, 1_800_000_000, owned, StorageKind.INTERNAL, available),
    )

    private fun screen(lib: OfflineLibrary, queue: QueueStatus = QueueStatus.Idle, confirm: String? = null, loading: Boolean = false, failed: Boolean = false) =
        show { DownloadsScreen(DownloadsUiState(BEN, lib, queue, online = false, loading = loading, localFailure = failed, confirmRemove = confirm), actions, emptyFlow(), SnackbarHostState(), onOpenSettings = { calls += "settings" }) }

    private val running = e(1, "Running Film", DownloadState.RUNNING, 0.45f)

    private fun scrollToText(t: String) = rule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText(t, substring = true))

    private fun scrollToDescribed(t: String) = rule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasContentDescription(t, substring = true))

    private fun assertTarget(label: String) {
        val node = rule.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().firstOrNull() ?: error("no button: $label\n${tree()}")
        val density = node.layoutInfo.density.density
        assertTrue("$label is under 48dp", node.size.height / density >= 47.5f && node.size.width / density >= 47.5f)
        assertTrue("$label is clickable", node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) != null)
    }

    @Test fun everySectionHeadingIsAHeadingAndTheSettingsActionIsLabelled() {
        screen(OfflineLibrary(listOf(running), listOf(e(2, "A Film")), listOf(SeriesSummary(entry(50, ItemKind.SERIES, "A Show"), 12, 9_800_000_000))))
        val headings = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes().mapNotNull { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
        }
        assertTrue(headings.toString(), headings.containsAll(listOf(text(R.string.downloads_title), text(R.string.dl_section_downloading), text(R.string.dl_section_films))))
        assertTarget(text(R.string.dl_settings_action))
        scrollToText("12 episodes")
        assertShown(text(R.string.dl_section_tv))
        assertShown("12 episodes · 9.8 GB")
    }

    @Test fun aRunningRowIsOneElementWithItsWholeMeaningAndProgressInWords() {
        screen(OfflineLibrary(listOf(running), emptyList(), emptyList()))
        val row = rule.onAllNodes(hasContentDescription("Running Film, Downloading, 45 percent")).fetchSemanticsNodes().single()
        val range = row.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        assertEquals(0.45f, range!!.current, 0.001f)
        assertTarget(text(R.string.dl_pause_cd, "Running Film"))
        assertTarget(text(R.string.dl_cancel_cd, "Running Film"))
        assertShown(text(R.string.dl_pause_all))
    }

    @Test fun pausedRowsOfferResumeAndResumeAll() {
        screen(OfflineLibrary(listOf(e(1, "Paused Film", DownloadState.PAUSED, 0.2f)), emptyList(), emptyList()), queue = QueueStatus.WaitingForWifi)
        assertTarget(text(R.string.dl_resume_cd, "Paused Film"))
        assertShown(text(R.string.dl_resume_all))
        assertShown("Waiting for Wi-Fi")
        rule.onAllNodes(hasContentDescription(text(R.string.dl_resume_cd, "Paused Film")))[0].performClick()
        assertTrue(calls.contains("resume:${entry(1).item.id}"))
    }

    @Test fun everyFailureLineShowsWithItsButton() {
        val failures = DownloadFailure.entries
        screen(OfflineLibrary(failures.mapIndexed { i, f -> e(100 + i, "Failed $i", DownloadState.FAILED, null, f) }, emptyList(), emptyList()))
        failures.forEachIndexed { i, f ->
            val title = "Failed $i"
            scrollToDescribed(text(R.string.dl_cancel_cd, title))
            val spoken = "$title, " + app.shoalwatch.ui.present.DownloadText.failure(app.shoalwatch.ui.present.Strings(context.resources), f)
            assertTrue("no row: $spoken\n${tree()}", described(spoken))
            when {
                f == DownloadFailure.SIGN_IN_NEEDED -> assertTarget(text(R.string.dl_sign_in_cd, title))
                app.shoalwatch.ui.present.DownloadText.canRetry(f) -> assertTarget(text(R.string.dl_retry_cd, title))
                else -> assertTrue("no retry for $f", !described(text(R.string.dl_retry_cd, title)))
            }
            assertTarget(text(R.string.dl_cancel_cd, title))
        }
    }

    @Test fun theEmptyStateScrolls() {
        screen(OfflineLibrary(emptyList(), emptyList(), emptyList()))
        assertShown(text(R.string.dl_empty))
        assertTrue(rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun aSharedFilmSaysSoAndHasNoDelete() {
        screen(OfflineLibrary(emptyList(), listOf(e(2, "Shared Film", owned = false), e(3, "My Film", available = false)), emptyList()))
        assertShown(text(R.string.dl_shared))
        assertTrue("no menu for the non-owner", !described(text(R.string.cd_more_actions, "Shared Film")))
        assertTarget(text(R.string.cd_more_actions, "My Film"))
        assertShown(text(R.string.dl_unavailable))
    }

    @Test fun theDeleteConfirmSaysWhatItFrees() {
        screen(OfflineLibrary(emptyList(), listOf(e(2, "My Film")), emptyList()), confirm = entry(2).item.id)
        assertShown(text(R.string.dl_delete_confirm, "1.8 GB"))
        rule.onAllNodesWithText(text(R.string.dl_delete_action))[0].performClick()
        assertTrue(calls.contains("confirm"))
    }

    @Test fun aLocalFailureOffersTryAgain() {
        screen(OfflineLibrary(emptyList(), emptyList(), emptyList()), failed = true)
        assertShown(text(R.string.dl_failed_locally))
        rule.onAllNodesWithText(text(R.string.action_retry))[0].performClick()
        assertEquals(listOf("tryAgain"), calls)
    }
}

@Config(qualifiers = PHONE) class DownloadsPhoneRenderTest : DownloadsRenderBase() { override val fontScale = 1f }
@Config(qualifiers = PHONE) class DownloadsPhoneLargeTextRenderTest : DownloadsRenderBase() { override val fontScale = 2f }
@Config(qualifiers = TABLET) class DownloadsTabletRenderTest : DownloadsRenderBase() { override val fontScale = 1f }
@Config(qualifiers = TABLET) class DownloadsTabletLargeTextRenderTest : DownloadsRenderBase() { override val fontScale = 2f }
