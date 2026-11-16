package app.shoalwatch.ui.downloads

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import app.shoalwatch.R
import app.shoalwatch.data.downloads.DownloadInfo
import app.shoalwatch.data.downloads.DownloadPlan
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadSettings
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.PlannedItem
import app.shoalwatch.data.downloads.SkipReason
import app.shoalwatch.data.downloads.Skipped
import app.shoalwatch.data.downloads.StorageChoice
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.PHONE
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.TABLET
import app.shoalwatch.ui.browse.episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** The download sheet, the settings screen and the browsing screens' controls, at both sizes and text scales. */
abstract class DownloadSheetRenderBase : RenderBase() {
    private val calls = mutableListOf<String>()
    private val sheetActions = DownloadSheetActions({ q, always -> calls += "choose:$q:$always" }, { calls += "confirm" }, { calls += "dismiss" })
    private val noAsk get() = NotificationAskState({ false }, { true }, {}, androidx.compose.runtime.mutableStateOf(null))

    private fun plan(items: Int = 12, skipped: List<Skipped> = emptyList(), converted: Int = 0, shared: Int = 0, enough: Boolean = true, mobile: Boolean = true, capped: Boolean = false) = DownloadPlan(
        BEN, DownloadQuality.ORIGINAL,
        List(items) { PlannedItem("$it".padStart(32, '0'), DownloadQuality.ORIGINAL, 1, shareOnly = it < shared) }, skipped,
        converted, 9_800_000_000, 41_000_000_000, enough, StorageKind.INTERNAL, null, mobile, capped,
    )

    private fun node(label: String) = rule.onAllNodes(hasText(label) or hasContentDescription(label)).fetchSemanticsNodes().firstOrNull() ?: error("not found: $label\n${tree()}")

    private fun atLeast48(label: String) {
        val n = node(label)
        val d = n.layoutInfo.density.density
        assertTrue("$label under 48dp: ${n.size}", n.size.height / d >= 47.5f)
    }

    @Test fun everyQualityIsARadioThatSaysWhetherItIsSelectedAndIs48dp() {
        show { DownloadSheet(DownloadSheetState.Quality(BEN, app.shoalwatch.data.downloads.DownloadTarget.Series("5".repeat(32))), sheetActions, noAsk) }
        val radios = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).fetchSemanticsNodes()
        assertEquals(4, radios.size)
        assertEquals(listOf(true, false, false, false), radios.map { it.config.getOrNull(SemanticsProperties.Selected) })
        radios.forEach { n -> assertTrue(n.size.height / n.layoutInfo.density.density >= 47.5f) }
        assertTrue(shown(text(R.string.dl_original_body)))
        assertTrue(shown(text(R.string.dl_conversion_note)))
        rule.onAllNodesWithText(text(R.string.dl_quality_low))[0].performClick()
        rule.onAllNodesWithText(text(R.string.dl_use_every_time))[0].performClick()
        val box = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).fetchSemanticsNodes().single()
        assertEquals(ToggleableState.On, box.config.getOrNull(SemanticsProperties.ToggleableState))
        rule.onAllNodesWithText(text(R.string.action_continue))[0].performClick()
        assertEquals(listOf("choose:LOW:true"), calls)
    }

    @Test fun theConfirmLinesAppearOnlyWhenTheirCountsAreNotZero() {
        show { DownloadSheet(DownloadSheetState.Confirm(plan()), sheetActions, noAsk) }
        assertShown("Download 12 episodes?")
        assertShown("About 9.8 GB. 41 GB free on this phone.")
        listOf(R.plurals.dl_skip_not_allowed, R.plurals.dl_converted_instead, R.plurals.dl_already_here, R.plurals.dl_skip_permission_unconfirmed).forEach {
            assertTrue(!shown(context.resources.getQuantityString(it, 2, "2")))
        }
        assertNotShown(text(R.string.dl_capped))
        assertNotShown(text(R.string.dl_wifi_only))
    }

    @Test fun everyLineThatAppliesIsSaid() {
        val skipped = listOf(
            Skipped("a".repeat(32), SkipReason.NOT_ALLOWED), Skipped("b".repeat(32), SkipReason.NOT_ALLOWED), Skipped("c".repeat(32), SkipReason.NOT_ALLOWED),
            Skipped("d".repeat(32), SkipReason.CONVERSION_UNCONFIRMED), Skipped("e".repeat(32), SkipReason.PERMISSION_UNCONFIRMED),
        )
        show { DownloadSheet(DownloadSheetState.Confirm(plan(skipped = skipped, converted = 2, shared = 4, mobile = false, capped = true)), sheetActions, noAsk) }
        rule.waitForIdle()
        val strings = app.shoalwatch.ui.present.Strings(context.resources)
        confirmLines(strings, plan(skipped = skipped, converted = 2, shared = 4, mobile = false, capped = true)).forEach { assertShown(it) }
        assertShown("3 can't be downloaded: your server doesn't allow it.")
        assertShown("2 can't play on this phone as they are, so they'll be downloaded in High (1080p).")
        assertShown("4 are already on this phone and will be added to your downloads.")
        assertShown(text(R.string.dl_capped))
        assertShown("Downloads use Wi-Fi only. You can change this in Download settings.")
    }

    @Test fun theDisabledDownloadButtonExposesItsReason() {
        show { DownloadSheet(DownloadSheetState.Confirm(plan(enough = false), films = true), sheetActions, noAsk) }
        val reason = "There isn't enough space. You need about 9.8 GB and have 41 GB free."
        assertShown(reason)
        val button = rule.onAllNodes(hasText(text(R.string.dl_download)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled)).fetchSemanticsNodes()
        assertEquals(1, button.size)
        assertEquals(reason, button.single().config.getOrNull(SemanticsProperties.StateDescription))
        assertShown("Download 12 films?")
    }

    @Test fun anOfflineFailureSaysTheErrorsOwnWords() {
        val offline = app.shoalwatch.core.AppError.of(app.shoalwatch.core.AppError.Kind.OFFLINE).userMessage
        show { DownloadSheet(DownloadSheetState.Failed(BEN, offline), sheetActions, noAsk) }
        assertShown(offline)
        atLeast48(text(R.string.dl_close))
    }

    @Test fun theSettingsScreensSdOptionIsAbsentWithoutACardAndWarnsWithOne() {
        var cards by androidx.compose.runtime.mutableStateOf(emptyList<StorageChoice>())
        val actions = DownloadSettingsActions({}, {}, { k, v -> calls += "storage:$k:$v" }, {}, {})
        show { DownloadSettingsScreen(DownloadSettingsUiState(BEN, "Den", DownloadSettings(), cards, sharing = false), actions, notificationsOn = false) }
        assertNotShown(text(R.string.dl_save_card))
        assertShown(text(R.string.dl_mobile_data))
        assertShown(text(R.string.dl_share_body, "Den"))
        assertShown(text(R.string.dl_notifications_off))
        cards = listOf(StorageChoice(StorageKind.REMOVABLE, "CARD-1", 1, true))
        rule.waitForIdle()
        assertShown(text(R.string.dl_save_card))
        assertShown(text(R.string.dl_card_warning))
        rule.onAllNodesWithText(text(R.string.dl_save_card))[0].performClick()
        assertEquals(listOf("storage:REMOVABLE:CARD-1"), calls)
        val radios = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).fetchSemanticsNodes()
        assertTrue(radios.all { it.size.height / it.layoutInfo.density.density >= 47.5f })
    }

    private fun controls(statuses: Map<String, DownloadInfo>) = DownloadControls(
        statuses, onDownload = { calls += "download:$it" }, onRetry = { calls += "retry:$it" }, onOpenDownloads = { calls += "downloads" },
        onSeason = { calls += "season:$it" }, onSeries = { calls += "series" }, onUnwatched = { calls += "unwatched" },
    )

    private fun info(id: String, state: DownloadState, progress: Float? = null) =
        DownloadInfo(id, state, null, DownloadQuality.ORIGINAL, progress, null, true, StorageKind.INTERNAL, true)

    @Test fun theSeriesMenuHasItsThreeActionsAndTheEpisodeControlFollowsItsState() {
        val e5 = episode(105, season = 2, number = 5).item
        val e6 = episode(106, season = 2, number = 6).item
        val e7 = episode(107, season = 2, number = 7).item
        val c = controls(mapOf(e6.id to info(e6.id, DownloadState.RUNNING, 0.45f), e7.id to info(e7.id, DownloadState.COMPLETE)))
        show {
            androidx.compose.foundation.layout.Column {
                SeriesDownloadMenu("2".repeat(32), "2", c)
                EpisodeDownloadControl(e5, c); EpisodeDownloadControl(e6, c); EpisodeDownloadControl(e7, c)
            }
        }
        atLeast48("Download Season 2, episode 5")
        assertTrue(described("Downloading, 45 percent"))
        atLeast48(text(R.string.dl_downloaded))
        rule.onAllNodes(hasContentDescription("Download Season 2, episode 5"))[0].performClick()
        rule.onAllNodesWithText(text(R.string.dl_download))[0].performClick()
        listOf(text(R.string.dl_download_season, "2"), text(R.string.dl_download_series), text(R.string.dl_download_unwatched)).forEach {
            val n = node(it)
            assertTrue("$it under 56dp", n.size.height / n.layoutInfo.density.density >= 55.5f)
        }
        rule.onAllNodesWithText(text(R.string.dl_download_unwatched))[0].performClick()
        assertEquals(listOf("download:${e5.id}", "unwatched"), calls)
    }

    /** Tasks 13-16 review M-6: Specials, an unnumbered season and an episode with no numbers get their own words. */
    @Test fun specialsAndUnnumberedItemsAreNamedPlainly() {
        val special = episode(120, season = 0, number = 1, name = "Behind the Scenes").item
        val loose = episode(121, name = "Odd One").item.copy(parentIndexNumber = null, indexNumber = null)
        val c = controls(emptyMap())
        show {
            androidx.compose.foundation.layout.Column {
                SeriesDownloadMenu("2".repeat(32), "0", c)
                EpisodeDownloadControl(special, c); EpisodeDownloadControl(loose, c)
            }
        }
        assertTrue(described(text(R.string.dl_download_title, "Behind the Scenes")))
        assertTrue(described(text(R.string.dl_download_title, "Odd One")))
        rule.onAllNodesWithText(text(R.string.dl_download))[0].performClick()
        assertTrue(rule.onAllNodesWithText(text(R.string.dl_download_specials)).fetchSemanticsNodes().isNotEmpty())
        assertTrue("never season 0", rule.onAllNodesWithText(text(R.string.dl_download_season, "0")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun theDetailButtonFollowsItsState() {
        val id = "7".repeat(32)
        var statuses by androidx.compose.runtime.mutableStateOf(emptyMap<String, DownloadInfo>())
        show { DetailDownloadButton(id, controls(statuses)) }
        atLeast48(text(R.string.dl_download))
        statuses = mapOf(id to info(id, DownloadState.RUNNING, 0.45f)); rule.waitForIdle()
        assertShown("Downloading 45%")
        statuses = mapOf(id to info(id, DownloadState.COMPLETE)); rule.waitForIdle()
        assertShown(text(R.string.dl_downloaded))
        rule.onAllNodesWithText(text(R.string.dl_downloaded))[0].performClick()
        statuses = mapOf(id to info(id, DownloadState.FAILED).copy(failure = app.shoalwatch.data.downloads.DownloadFailure.SERVER_ERROR)); rule.waitForIdle()
        assertShown(text(R.string.dl_failure_server_error))
        rule.onAllNodesWithText(text(R.string.dl_retry))[0].performClick()
        assertEquals(listOf("downloads", "retry:$id"), calls)
    }
}

@Config(qualifiers = PHONE) class DownloadSheetPhoneRenderTest : DownloadSheetRenderBase() { override val fontScale = 1f }
@Config(qualifiers = PHONE) class DownloadSheetPhoneLargeTextRenderTest : DownloadSheetRenderBase() { override val fontScale = 2f }
@Config(qualifiers = TABLET) class DownloadSheetTabletRenderTest : DownloadSheetRenderBase() { override val fontScale = 1f }
@Config(qualifiers = TABLET) class DownloadSheetTabletLargeTextRenderTest : DownloadSheetRenderBase() { override val fontScale = 2f }
