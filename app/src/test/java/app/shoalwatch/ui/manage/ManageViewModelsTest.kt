package app.shoalwatch.ui.manage

import android.net.Uri
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import app.shoalwatch.R
import app.shoalwatch.data.admin.AdminStatus
import app.shoalwatch.data.downloads.DownloadInfo
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.ItemDetail
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.RefreshTarget
import app.shoalwatch.data.manage.ImageKind
import app.shoalwatch.data.manage.LockableField
import app.shoalwatch.data.manage.PictureRefused
import app.shoalwatch.data.manage.PreparedImage
import app.shoalwatch.data.manage.RefreshMode
import app.shoalwatch.data.manage.asEdit
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.address
import app.shoalwatch.ui.admin.AdminGate
import app.shoalwatch.ui.admin.FakeAdminRights
import app.shoalwatch.ui.admin.ModelsInTest
import app.shoalwatch.ui.admin.adminTest
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.FakeDownloads
import app.shoalwatch.ui.browse.FakeLibrary
import app.shoalwatch.ui.browse.entry
import app.shoalwatch.ui.browse.id
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Milestone 10: the management hub, Identify, Pictures and Edit details, with fakes at the seams. */
@RunWith(RobolectricTestRunner::class)
class ManageViewModelsTest {
    @get:Rule val main = MainDispatcherRule()
    private val ben = AccountSummary(BEN.serverId, BEN.userId, "ben")
    private val amy = AccountSummary(AMY.serverId, AMY.userId, "amy")
    private val catalog = FakeCatalog(accounts = listOf(ben, amy), active = ben)
    private val rights = FakeAdminRights()
    private val connections = FakeConnections().apply { stateFor(BEN.serverId).value = Connection.Online(address("http://192.168.1.10:8096"), isHome = true) }
    private val manage = FakeManage()
    private val library = FakeLibrary()
    private val downloads = FakeDownloads()
    private val evicted = mutableListOf<String>()
    private val after = AfterChange(library) { _, item -> evicted += item }
    private val models = ModelsInTest()
    private val film = entry(1, ItemKind.MOVIE, name = "The Silent Harbour", year = 1999)
    private val itemId = film.item.id

    init {
        library.itemFlow(BEN, itemId).value = ItemDetail(film, Freshness.Fresh)
    }

    private fun hub() = models.add(ManageViewModel(itemId, catalog, rights, connections, manage, library, downloads, after)).also { it.setVisible(true) }
    private fun identify() = models.add(IdentifyViewModel(itemId, catalog, rights, connections, manage, library, after)).also { it.setVisible(true) }
    private var prepared: suspend (Uri, ImageKind) -> PreparedImage = { _, _ -> PreparedImage(byteArrayOf(-1, -40, -1, 0), 10, 10) }
    private fun images() = models.add(ImagesViewModel(itemId, catalog, rights, connections, manage, library, { u, k -> prepared(u, k) }, after)).also { it.setVisible(true) }
    private fun edit() = models.add(EditViewModel(itemId, catalog, rights, connections, manage, after)).also { it.setVisible(true) }

    // ---- hub
    @Test fun theHubShowsTheItemAndRefreshesWithTheChosenMode() = main.adminTest(models) {
        val vm = hub()
        assertEquals("The Silent Harbour", vm.state.value.item?.title)
        vm.askRefresh()
        assertEquals(RefreshAsk(RefreshMode.MISSING, false), vm.state.value.refreshAsk)
        vm.setRefresh(RefreshAsk(RefreshMode.REPLACE_ALL, true))
        vm.confirmRefresh()
        assertEquals(listOf("refresh:$itemId:REPLACE_ALL:true"), manage.calls)
        assertEquals(R.string.manage_refresh_started, vm.state.value.frame.notice?.text)
        vm.setVisible(false)
    }

    @Test fun deleteFromTheHubAsksKnowsTheDownloadAndLeaves() = main.adminTest(models) {
        downloads.statusFlow(BEN, itemId).value = DownloadInfo(itemId, DownloadState.COMPLETE, null, DownloadQuality.ORIGINAL, 1f, 1, true, StorageKind.INTERNAL, true)
        val vm = hub()
        vm.askDelete()
        val ask = vm.state.value.deleteAsk!!
        assertEquals(mapOf(itemId to listOf(itemId)), ask.downloaded)
        vm.setKeepCopy(true)
        vm.confirmDelete()
        assertEquals(listOf("delete:$itemId"), manage.calls)
        assertTrue("the copy was kept", downloads.calls.isEmpty())
        assertEquals(listOf(Triple(BEN, itemId, true)), library.forgets)
        assertTrue(vm.state.value.deleted)
        vm.setVisible(false)
    }

    @Test fun theHubAsksTheServerFirstAndSaysWhenThisProfileMayNotDelete() = main.adminTest(models) {
        manage.deletable = { false }
        val vm = hub()
        vm.askDelete()
        assertNull("nothing asked", vm.state.value.deleteAsk)
        assertEquals(R.string.manage_delete_not_allowed, vm.state.value.frame.notice?.text)
        assertTrue(manage.calls.isEmpty())
        vm.setVisible(false)
    }

    @Test fun theHubOffersDeleteOnlyWithTheDeletionPolicy() = main.adminTest(models) {
        rights.mayDelete = false
        val vm = hub()
        assertFalse(vm.state.value.canDelete)
        vm.askDelete()
        assertNull(vm.state.value.deleteAsk)
        vm.setVisible(false)
    }

    @Test fun aHubDeleteStillLeavesWhenThePhoneSideFails() = main.adminTest(models) {
        downloads.statusFlow(BEN, itemId).value = DownloadInfo(itemId, DownloadState.COMPLETE, null, DownloadQuality.ORIGINAL, 1f, 1, true, StorageKind.INTERNAL, true)
        downloads.removeOutcome = { _, _ -> throw IllegalStateException("disk") }
        val vm = hub()
        vm.askDelete()
        vm.confirmDelete()
        assertEquals(listOf("delete:$itemId"), manage.calls)
        assertEquals("the lists are still updated", listOf(Triple(BEN, itemId, false)), library.forgets)
        assertTrue(vm.state.value.deleted)
        assertEquals(R.string.manage_deleted, vm.state.value.frame.notice?.text)
        vm.setVisible(false)
    }

    @Test fun aNonAdministratorNeverOpensTheHub() = main.adminTest(models) {
        rights.answer = AdminStatus.NOT_ADMIN
        val vm = hub()
        assertEquals(AdminGate.Closed, vm.state.value.frame.gate)
        vm.askRefresh()
        vm.confirmRefresh()
        assertTrue(manage.calls.isEmpty())
    }

    @Test fun aProfileChangeClosesTheHubForGood() = main.adminTest(models) {
        val vm = hub()
        vm.askRefresh()
        catalog.activeFlow.value = amy
        assertEquals(AdminGate.Closed, vm.state.value.frame.gate)
        assertNull(vm.state.value.refreshAsk)
        vm.confirmRefresh()
        assertTrue(manage.calls.isEmpty())
    }

    // ---- identify
    @Test fun identifyStartsFromTheItemSearchesAndAppliesAfterAYes() = main.adminTest(models) {
        val vm = identify()
        assertEquals("The Silent Harbour", vm.name.text.toString())
        assertEquals("1999", vm.year.text.toString())
        vm.search()
        assertEquals("identify:$itemId:MOVIE:The Silent Harbour:1999", manage.calls.single())
        val match = vm.state.value.results!!.first()
        vm.askApply(match)
        assertTrue(vm.state.value.apply!!.replaceImages)
        vm.setReplaceImages(false)
        vm.confirmApply()
        assertEquals("apply:$itemId:The Silent Harbour:false", manage.calls.last())
        assertEquals(listOf(itemId), evicted)
        assertEquals(listOf(BEN to RefreshTarget.Item(itemId)), library.refreshes)
        assertEquals(R.string.identify_applied, vm.state.value.frame.notice?.text)
        vm.setVisible(false)
    }

    @Test fun identifyIsNotOfferedForAnEpisode() = main.adminTest(models) {
        library.itemFlow(BEN, itemId).value = ItemDetail(entry(1, ItemKind.EPISODE), Freshness.Fresh)
        val vm = identify()
        assertTrue(vm.state.value.known)
        assertNull(vm.state.value.kind)
        vm.search()
        assertTrue(manage.calls.isEmpty())
        vm.setVisible(false)
    }

    @Test fun aMatchNotInTheResultsCannotBeApplied() = main.adminTest(models) {
        val vm = identify()
        vm.askApply(FakeManage.sampleMatches().first())
        assertNull(vm.state.value.apply)
        vm.setVisible(false)
    }

    // ---- pictures
    @Test fun picturesListFindOnlineAndUseOne() = main.adminTest(models) {
        val vm = images()
        assertEquals(manage.images, vm.state.value.images)
        vm.findOnline()
        val choice = vm.state.value.remote!!.images.single()
        vm.use(choice)
        assertEquals(listOf("images:$itemId", "remote:$itemId:PRIMARY:false", "use:$itemId:PRIMARY:TheMovieDb", "images:$itemId"), manage.calls)
        assertEquals(listOf(itemId), evicted)
        assertEquals(R.string.images_saved, vm.state.value.frame.notice?.text)
        vm.chooseKind(ImageKind.LOGO)
        assertNull("another kind's choices are not kept", vm.state.value.remote)
        vm.setVisible(false)
    }

    @Test fun anUploadIsPreparedThenSentForTheKindShown() = main.adminTest(models) {
        val vm = images()
        vm.chooseKind(ImageKind.BACKDROP)
        vm.upload(Uri.parse("content://media/picker/0/1"))
        assertTrue(manage.calls.contains("upload:$itemId:BACKDROP"))
        assertEquals(10, manage.lastUpload!!.width)
        vm.setVisible(false)
    }

    @Test fun aRefusedPictureIsSaidAndNothingIsSent() = main.adminTest(models) {
        prepared = { _, _ -> throw PictureRefused(PictureRefused.Reason.TOO_LARGE) }
        val vm = images()
        vm.upload(Uri.parse("content://media/picker/0/1"))
        assertEquals(R.string.images_too_big, vm.state.value.pickProblem)
        assertFalse(manage.calls.any { it.startsWith("upload") })
        vm.setVisible(false)
    }

    @Test fun removingAPictureAsksFirst() = main.adminTest(models) {
        val vm = images()
        val backdrop = manage.images[1]
        vm.askRemove(backdrop)
        assertEquals(backdrop, vm.state.value.removeAsk)
        vm.confirmRemove()
        assertTrue(manage.calls.contains("deleteImage:$itemId:BACKDROP:0"))
        vm.setVisible(false)
    }

    // ---- edit
    @Test fun editFillsTheFormAndSavesTypedValues() = main.adminTest(models) {
        val vm = edit()
        assertTrue(vm.state.value.loaded)
        assertEquals("Old name", vm.name.text.toString())
        assertEquals(listOf("Drama"), vm.state.value.lists[EditList.GENRES])
        assertEquals("6.5", vm.communityRating.text.toString())
        vm.name.setTextAndPlaceCursorAtEnd("New name")
        vm.inputs.getValue(EditList.GENRES).setTextAndPlaceCursorAtEnd("Mystery")
        vm.addEntry(EditList.GENRES)
        vm.premiere.setTextAndPlaceCursorAtEnd("2001-02-03")
        vm.communityRating.setTextAndPlaceCursorAtEnd("7,5")
        vm.setLocked(LockableField.NAME, true)
        vm.save()
        val sent = manage.lastEdit!!
        assertEquals("New name", sent.name)
        assertEquals(listOf("Drama", "Mystery"), sent.genres)
        assertEquals(LocalDate.of(2001, 2, 3), sent.premiereDate)
        assertEquals(7.5, sent.communityRating!!, 0.0001)
        assertEquals(setOf(LockableField.NAME), sent.locked)
        assertEquals(R.string.edit_saved, vm.state.value.frame.notice?.text)
        assertEquals("the form as first shown goes with it", manage.form, manage.lastShown)
        vm.setVisible(false)
    }

    @Test fun savingAnUntouchedFormSendsTheServersOwnValuesBack() = main.adminTest(models) {
        manage.form = manage.form.copy(communityRating = 6.54, studios = listOf("Lions Gate Films, Inc."), overview = "Old overview.")
        val vm = edit()
        assertEquals("6.5", vm.communityRating.text.toString())
        assertEquals("one entry, comma and all", listOf("Lions Gate Films, Inc."), vm.state.value.lists[EditList.STUDIOS])
        vm.save()
        assertEquals("unchanged, so the gateway writes nothing", manage.form.asEdit(), manage.lastEdit)
        vm.setVisible(false)
    }

    @Test fun listsAreEditedEntryByEntryNeverSplitOnCommas() = main.adminTest(models) {
        val vm = edit()
        val input = vm.inputs.getValue(EditList.STUDIOS)
        input.setTextAndPlaceCursorAtEnd("  Lions Gate Films, Inc. ")
        vm.addEntry(EditList.STUDIOS)
        assertEquals(listOf("Studio", "Lions Gate Films, Inc."), vm.state.value.lists[EditList.STUDIOS])
        assertEquals("", input.text.toString())
        input.setTextAndPlaceCursorAtEnd("studio")
        vm.addEntry(EditList.STUDIOS)
        assertEquals("a repeat is not added", 2, vm.state.value.lists[EditList.STUDIOS]!!.size)
        vm.removeEntry(EditList.STUDIOS, 0)
        vm.removeEntry(EditList.STUDIOS, 7)
        vm.inputs.getValue(EditList.TAGS).setTextAndPlaceCursorAtEnd("Typed, not added")
        vm.save()
        assertEquals(listOf("Lions Gate Films, Inc."), manage.lastEdit!!.studios)
        assertEquals("what was typed but not added counts as one entry", listOf("Typed, not added"), manage.lastEdit!!.tags)
        vm.setVisible(false)
    }

    @Test fun aRatingTypedAgainIsSentAsTyped() = main.adminTest(models) {
        manage.form = manage.form.copy(communityRating = 6.54)
        val vm = edit()
        vm.communityRating.setTextAndPlaceCursorAtEnd("6.6")
        vm.save()
        assertEquals(6.6, manage.lastEdit!!.communityRating!!, 0.0001)
        vm.setVisible(false)
    }

    @Test fun badValuesAreSaidAndNothingIsSent() = main.adminTest(models) {
        val vm = edit()
        vm.name.setTextAndPlaceCursorAtEnd("  ")
        vm.year.setTextAndPlaceCursorAtEnd("12")
        vm.premiere.setTextAndPlaceCursorAtEnd("3 Feb 2001")
        vm.communityRating.setTextAndPlaceCursorAtEnd("11")
        vm.save()
        assertEquals(setOf(EditProblem.NAME, EditProblem.YEAR, EditProblem.DATE, EditProblem.RATING), vm.state.value.problems)
        assertFalse(manage.calls.contains("save:$itemId"))
        vm.setVisible(false)
    }

    @Test fun aClosedGateDropsTheTypedForm() = main.adminTest(models) {
        val vm = edit()
        catalog.activeFlow.value = amy
        assertEquals("", vm.name.text.toString())
        assertFalse(vm.state.value.loaded)
    }
}
