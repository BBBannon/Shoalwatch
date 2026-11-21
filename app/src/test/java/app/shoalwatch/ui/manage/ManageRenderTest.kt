package app.shoalwatch.ui.manage

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.manage.ImageKind
import app.shoalwatch.data.manage.ItemImage
import app.shoalwatch.ui.admin.AdminFrame
import app.shoalwatch.ui.admin.AdminGate
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.BulkAction
import app.shoalwatch.ui.browse.BulkItem
import app.shoalwatch.ui.browse.DeleteAsk
import app.shoalwatch.ui.browse.PHONE
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.Selection
import app.shoalwatch.ui.browse.TABLET
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.browse.rememberCardSelect
import app.shoalwatch.ui.components.DeleteFromServerDialog
import app.shoalwatch.ui.components.PosterCard
import app.shoalwatch.ui.components.SelectionBar
import app.shoalwatch.ui.components.SelectionControls
import app.shoalwatch.ui.present.CardUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** Milestone 10: the selection bar, a selected card, the delete confirmation and each management screen, at every size. */
abstract class ManageRenderBase : RenderBase() {
    private val open = AdminFrame(gate = AdminGate.Open(BEN, online = true), loading = false)
    private val done = mutableListOf<String>()

    @Test fun theBarCountsAndNamesEveryAction() {
        val controls = SelectionControls(3, BulkAction.forBrowsing, enabled = true, onAction = { done += it.name }, onClose = { done += "close" })
        show { SelectionBar(controls) }
        assertShown(rule.activity().resources.getQuantityString(R.plurals.select_count, 3, 3))
        for (label in listOf(R.string.select_close, R.string.select_mark_watched, R.string.select_favourite, R.string.select_download, R.string.select_more_options)) {
            rule.onNode(hasContentDescription(text(label))).assertHeightIsAtLeast(48.dp).assertIsEnabled()
        }
        rule.onNode(hasContentDescription(text(R.string.select_mark_watched))).performClick()
        rule.onNode(hasContentDescription(text(R.string.select_more_options))).performClick()
        rule.onNode(hasText(text(R.string.select_delete_server))).performClick()
        rule.onNode(hasContentDescription(text(R.string.select_close))).performClick()
        assertEquals(listOf("MARK_WATCHED", "DELETE_FROM_SERVER", "close"), done)
    }

    @Test fun aLongPressSelectsACardAndSaysSo() {
        var picked: Selection? = null
        show {
            val s = app.shoalwatch.ui.browse.rememberSelection(BEN)
            picked = s
            val card = CardUi(
                id(1), "The Silent Harbour", "1999", null, null, null, watched = false, favourite = false, unwatchedCount = null,
                label = "The Silent Harbour, 1999", menuLabel = "More actions for The Silent Harbour",
            )
            PosterCard(card, onClick = { done += "open" }, onMenu = { done += "menu" }, select = rememberCardSelect(s, id(1), "The Silent Harbour"))
        }
        rule.onNode(hasContentDescription("The Silent Harbour, 1999")).performTouchInput { longClick() }
        rule.waitForIdle()
        assertTrue(id(1) in picked!!)
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertExists()
        rule.onNode(hasContentDescription("The Silent Harbour, 1999")).performClick()
        rule.waitForIdle()
        assertTrue("a tap while selecting removes it, and opens nothing", !picked!!.active && "open" !in done)
    }

    @Test fun theDeleteDialogNamesTheItemsAndOffersToKeepTheCopy() {
        val items = (1..5).map { BulkItem(id(it), "Film $it", ItemKind.MOVIE) }
        var keep = false
        show { DeleteFromServerDialog(DeleteAsk(BEN, items, mapOf(id(2) to listOf(id(2)))), { keep = it }, {}, {}) }
        assertShown(rule.activity().resources.getQuantityString(R.plurals.delete_server_title_many, 2, "“Film 1”, “Film 2”, “Film 3”", 2))
        assertShown(text(R.string.delete_server_body))
        rule.onNode(hasText(rule.activity().resources.getQuantityString(R.plurals.delete_server_keep_copy, 1))).performClick()
        assertTrue(keep)
    }

    @Test fun theHubOffersEachTool() {
        val state = ManageUiState(open, ManageItem(id(1), "The Silent Harbour", ItemKind.MOVIE, null), canDelete = true)
        show { ManageScreen(state, actions()) }
        assertShown("The Silent Harbour")
        assertShown(text(R.string.manage_identify))
        assertShown(text(R.string.manage_images))
        assertShown(text(R.string.manage_edit))
        scrollTo(hasText(text(R.string.select_delete_server)))
        rule.onNode(hasText(text(R.string.select_delete_server)) and hasClickAction()).assertIsEnabled().performClick()
        assertEquals("delete", done.last())
    }

    @Test fun anEpisodeHasNoIdentify() {
        show { ManageScreen(ManageUiState(open, ManageItem(id(1), "Episode 1", ItemKind.EPISODE, id(9))), actions()) }
        assertNotShown(text(R.string.manage_identify))
    }

    @Test fun theRefreshDialogHasTheDashboardsChoices() {
        show { ManageScreen(ManageUiState(open, ManageItem(id(1), "Film", ItemKind.MOVIE, null), refreshAsk = RefreshAsk()), actions()) }
        assertShown(text(R.string.manage_refresh_scan))
        assertShown(text(R.string.manage_refresh_missing))
        assertShown(text(R.string.manage_refresh_all))
        assertShown(text(R.string.manage_refresh_images))
    }

    @Test fun identifyListsMatchesInWordsOnly() {
        val state = IdentifyUiState(open, app.shoalwatch.data.manage.IdentifyKind.MOVIE, known = true, results = FakeManage.sampleMatches())
        show { IdentifyScreen(state, TextFieldState("The Silent Harbour"), TextFieldState("1999"), identifyActions()) }
        assertShown(text(R.string.identify_no_pictures_note))
        scrollTo(hasText("The Silent Harbour Returns (2003)"))
        assertShown("The Silent Harbour Returns (2003)")
        assertDescribed(text(R.string.identify_use_cd, "The Silent Harbour Returns"))
    }

    @Test fun picturesShowWhatIsThereAndTheOnlineChoicesAsText() {
        val state = ImagesUiState(open, BEN, ImageKind.PRIMARY, listOf(ItemImage(ImageKind.PRIMARY, 0, 1000, 1500, 2000)), remote = FakeManage.sampleRemote())
        show { ImagesScreen(state, imagesActions()) }
        assertShown(text(R.string.images_size, 1000, 1500))
        assertShown(text(R.string.images_upload))
        scrollTo(hasText("TheMovieDb · 1000 × 1500 · English · rated 7.4 · 12 votes"))
        assertShown("TheMovieDb · 1000 × 1500 · English · rated 7.4 · 12 votes")
    }

    @Test fun editShowsEveryFieldAndTheLocks() {
        val f = EditFields(TextFieldState("Old"), TextFieldState(), TextFieldState(), TextFieldState("Overview."), TextFieldState("1999"), TextFieldState(),
            TextFieldState("PG"), TextFieldState("6.5"), EditList.entries.associateWith { TextFieldState() })
        val lists = mapOf(EditList.STUDIOS to listOf("Lions Gate Films, Inc."))
        val actions = EditActions({}, {}, { _, _ -> }, {}, { done += "save" }, onRemoveEntry = { l, i -> done += "remove:$l:$i" })
        show { EditScreen(EditUiState(open, loaded = true, ratingOptions = listOf("U", "PG"), problems = setOf(EditProblem.YEAR), lists = lists), f, actions) }
        assertShown(text(R.string.edit_name))
        scrollTo(hasText(text(R.string.edit_bad_year)))
        assertShown(text(R.string.edit_bad_year))
        // A list entry is one chip, commas and all, removed by its own "Remove …".
        scrollTo(hasContentDescription(text(R.string.edit_list_remove, "Lions Gate Films, Inc.")))
        rule.onNode(hasContentDescription(text(R.string.edit_list_remove, "Lions Gate Films, Inc."))).performClick()
        assertEquals("remove:STUDIOS:0", done.last())
        scrollTo(hasText(text(R.string.edit_save)))
        rule.onNode(hasText(text(R.string.edit_save)) and hasClickAction()).performClick()
        assertEquals("save", done.last())
    }

    private fun actions() = ManageActions(
        onBack = {}, onReload = {}, onIdentify = { done += "identify" }, onImages = {}, onEdit = {}, onAskRefresh = {}, onSetRefresh = {},
        onConfirmRefresh = {}, onDismissRefresh = {}, onAskDelete = { done += "delete" }, onKeepCopy = {}, onConfirmDelete = {}, onDismissDelete = {},
    )

    private fun identifyActions() = IdentifyActions({}, {}, {}, {}, {}, {})

    private fun imagesActions() = ImagesActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.activity() = context
}

@Config(qualifiers = PHONE) class ManagePhoneRenderTest : ManageRenderBase() { override val fontScale = 1f }
@Config(qualifiers = PHONE) class ManagePhoneLargeTextRenderTest : ManageRenderBase() { override val fontScale = 2f }
@Config(qualifiers = TABLET) class ManageTabletRenderTest : ManageRenderBase() { override val fontScale = 1f }
@Config(qualifiers = TABLET) class ManageTabletLargeTextRenderTest : ManageRenderBase() { override val fontScale = 2f }
