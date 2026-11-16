package app.shoalwatch.ui.downloads

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.shoalwatch.R
import app.shoalwatch.data.downloads.AfterFinish
import app.shoalwatch.data.downloads.DownloadSettings
import app.shoalwatch.data.downloads.SharedDelete
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.PHONE
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.TABLET
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

/** Download settings' watch-and-delete groups: "When a download finishes playing" and, only while sharing is on, "Delete shared downloads". */
abstract class DownloadSettingsRenderBase : RenderBase() {
    private val calls = mutableListOf<String>()
    private val actions = DownloadSettingsActions(
        {}, {}, { _, _ -> }, {}, {}, onAfterFinish = { calls += "after:$it" }, onDeleteShared = { calls += "shared:$it" },
        onAutoNext = { calls += "next:$it" },
    )

    private fun showSettings(sharing: Boolean, after: AfterFinish = AfterFinish.ASK, deleteShared: SharedDelete = SharedDelete.ANYONE, autoNext: Int = 0) = show {
        DownloadSettingsScreen(DownloadSettingsUiState(BEN, "Den", DownloadSettings(), emptyList(), sharing, after, deleteShared, autoNext), actions, notificationsOn = true)
    }

    /** The last radio with [label] ("Ask each time" is also the quality group's first choice, above). */
    private fun radio(label: String) = rule.onAllNodes(hasText(label).and(isSelectable())).let { it[it.fetchSemanticsNodes().size - 1] }.performScrollTo()

    @Test fun whenADownloadFinishesPlayingIsThreeRadiosThatSayWhichIsSelected() {
        showSettings(sharing = false, after = AfterFinish.ALWAYS)
        rule.onNode(hasText(text(R.string.dl_after_finish))).performScrollTo()
        radio(text(R.string.dl_after_finish_ask)).assertIsNotSelected()
        radio(text(R.string.dl_after_finish_always)).assertIsSelected()
        radio(text(R.string.dl_after_finish_never)).assertIsNotSelected()
        rule.onNode(hasText(text(R.string.dl_after_finish_help))).performScrollTo()
        radio(text(R.string.dl_after_finish_never)).performClick()
        assertEquals(listOf("after:NEVER"), calls)
    }

    @Test fun downloadNextEpisodesIsFiveRadiosOfAtLeast48dpThatSayWhichIsSelected() {
        showSettings(sharing = false, autoNext = 3)
        rule.onNode(hasText(text(R.string.dl_auto_next))).performScrollTo().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        val labels = listOf(
            0 to R.string.dl_auto_next_off, 1 to R.string.dl_auto_next_1, 2 to R.string.dl_auto_next_2, 3 to R.string.dl_auto_next_3, 5 to R.string.dl_auto_next_5,
        )
        for ((count, label) in labels) {
            val node = radio(text(label)).assertHeightIsAtLeast(48.dp)
            if (count == 3) node.assertIsSelected() else node.assertIsNotSelected()
        }
        rule.onNode(hasText(text(R.string.dl_auto_next_help))).performScrollTo()
        radio(text(R.string.dl_auto_next_5)).performClick()
        radio(text(R.string.dl_auto_next_off)).performClick()
        assertEquals(listOf("next:5", "next:0"), calls)
    }

    @Test fun deleteSharedDownloadsIsAbsentWhileSharingIsOff() {
        showSettings(sharing = false)
        assertNotShown(text(R.string.dl_delete_shared))
        assertNotShown(text(R.string.dl_delete_shared_anyone))
    }

    @Test fun deleteSharedDownloadsShowsWhileSharingIsOnAndSaysWhichIsSelected() {
        showSettings(sharing = true, deleteShared = SharedDelete.EVERYONE)
        rule.onNode(hasText(text(R.string.dl_delete_shared))).performScrollTo()
        radio(text(R.string.dl_delete_shared_anyone)).assertIsNotSelected()
        radio(text(R.string.dl_delete_shared_everyone)).assertIsSelected()
        rule.onNode(hasText(text(R.string.dl_delete_shared_help, "Den"))).performScrollTo()
        radio(text(R.string.dl_delete_shared_anyone)).performClick()
        assertEquals(listOf("shared:ANYONE"), calls)
        val radios = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).fetchSemanticsNodes()
        assertEquals("every radio states whether it is selected", radios.size, radios.count { SemanticsProperties.Selected in it.config })
    }
}

@Config(qualifiers = PHONE) class DownloadSettingsPhoneRenderTest : DownloadSettingsRenderBase() { override val fontScale = 1f }
@Config(qualifiers = PHONE) class DownloadSettingsPhoneLargeTextRenderTest : DownloadSettingsRenderBase() { override val fontScale = 2f }
@Config(qualifiers = TABLET) class DownloadSettingsTabletRenderTest : DownloadSettingsRenderBase() { override val fontScale = 1f }
@Config(qualifiers = TABLET) class DownloadSettingsTabletLargeTextRenderTest : DownloadSettingsRenderBase() { override val fontScale = 2f }
