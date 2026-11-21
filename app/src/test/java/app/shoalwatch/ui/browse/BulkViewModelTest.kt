package app.shoalwatch.ui.browse

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import app.shoalwatch.R
import app.shoalwatch.core.AppError
import app.shoalwatch.data.admin.AdminStatus
import app.shoalwatch.data.downloads.DownloadInfo
import app.shoalwatch.data.downloads.DownloadPlan
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.DownloadTarget
import app.shoalwatch.data.downloads.PlannedItem
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.admin.FakeAdminRights
import app.shoalwatch.ui.manage.FakeManage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Milestone 10: the selection bar's actions (plan decisions 2 to 6). */
class BulkViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val ben = AccountSummary(BEN.serverId, BEN.userId, "ben")
    private val amy = AccountSummary(AMY.serverId, AMY.userId, "amy")
    private val catalog = FakeCatalog(accounts = listOf(ben, amy), active = ben)
    private val playstate = FakePlaystate()
    private val downloads = FakeDownloads()
    private val manage = FakeManage()
    private val rights = FakeAdminRights(AdminStatus.ADMIN)
    private val library = FakeLibrary()
    private val evicted = mutableListOf<String>()
    private fun vm() = BulkViewModel(catalog, playstate, downloads, manage, rights, library) { _, id -> evicted += id }

    private val one = BulkItem(id(1), "Film One", ItemKind.MOVIE)
    private val two = BulkItem(id(2), "Film Two", ItemKind.MOVIE)

    @Test fun watchedAndFavouriteGoThroughPlaystateOneItemAtATimeWithOneLine() = runTest(main.dispatcher) {
        val vm = vm()
        val notices = mutableListOf<BulkNotice>()
        backgroundScope.launch { vm.notices.toList(notices) }
        vm.apply(listOf(one, two), BulkAction.MARK_WATCHED)
        vm.apply(listOf(one), BulkAction.UNFAVOURITE)
        assertEquals(listOf("played:ben:${id(1)}:true", "played:ben:${id(2)}:true", "favourite:ben:${id(1)}:false"), playstate.calls)
        assertEquals(listOf(R.plurals.bulk_marked_watched to 2, R.plurals.bulk_unfavourited to 1), notices.map { it.plural to it.count })
    }

    @Test fun aFailureSaysWhyAndCountsWhatWasNotDone() = runTest(main.dispatcher) {
        val vm = vm()
        val notices = mutableListOf<BulkNotice>()
        backgroundScope.launch { vm.notices.toList(notices) }
        playstate.failWith = AppError.of(AppError.Kind.SERVER_NOT_FOUND)
        vm.apply(listOf(one, two), BulkAction.FAVOURITE)
        assertEquals(R.string.bulk_gone, notices.single().text)
    }

    @Test fun anAdministratorMayManageAndDelete() = runTest(main.dispatcher) {
        val vm = vm()
        assertTrue(vm.state.value.canManage)
        assertTrue(vm.state.value.canDelete)
    }

    @Test fun someoneElseMayDoNeither() = runTest(main.dispatcher) {
        rights.answer = AdminStatus.NOT_ADMIN
        val vm = vm()
        assertFalse(vm.state.value.canManage)
        assertFalse(vm.state.value.canDelete)
        vm.apply(listOf(one), BulkAction.DELETE_FROM_SERVER)
        assertNull("nothing asked", vm.state.value.deleteAsk)
    }

    @Test fun deleteAsksFirstNamesTheItemsAndKnowsWhichAreOnThePhone() = runTest(main.dispatcher) {
        downloads.statusFlow(BEN, id(2)).value = info(id(2))
        val vm = vm()
        vm.apply(listOf(one, two), BulkAction.DELETE_FROM_SERVER)
        val ask = vm.state.value.deleteAsk!!
        assertEquals(listOf("Film One", "Film Two"), ask.items.map { it.title })
        assertEquals(mapOf(id(2) to listOf(id(2))), ask.downloaded)
        assertFalse(ask.keepCopy)
        assertTrue("nothing sent before the yes", manage.calls.isEmpty())
        vm.confirmDelete()
        assertEquals(listOf("delete:${id(1)}", "delete:${id(2)}"), manage.calls)
        assertEquals(listOf("remove:${id(2)}"), downloads.calls)
        assertEquals(listOf(Triple(BEN, id(1), false), Triple(BEN, id(2), false)), library.forgets)
        assertEquals(listOf(id(1), id(2)), evicted)
        assertNull(vm.state.value.deleteAsk)
    }

    /** Emulator walkthrough, 21/11/2026: the deleted film's own page stayed on "Loading"; it now hears the delete and leaves. */
    @Test fun eachItemTheServerDeletesIsAnnouncedSoItsOwnPageCanLeave() = runTest(main.dispatcher) {
        val vm = vm()
        vm.apply(listOf(one, two), BulkAction.DELETE_FROM_SERVER)
        vm.confirmDelete()
        assertEquals(listOf(id(1), id(2)), vm.deleted.take(2).toList())
    }

    @Test fun aRefusedDeleteIsNotAnnounced() = runTest(main.dispatcher) {
        val vm = vm()
        vm.apply(listOf(one), BulkAction.DELETE_FROM_SERVER)
        assertNotNull("asked first", vm.state.value.deleteAsk)
        manage.failWith = AppError.of(AppError.Kind.FORBIDDEN)
        vm.confirmDelete()
        assertNull(withTimeoutOrNull(100) { vm.deleted.first() })
    }

    @Test fun keepingThePhoneCopyKeepsTheDownloadAndItsRecord() = runTest(main.dispatcher) {
        downloads.statusFlow(BEN, id(2)).value = info(id(2))
        val vm = vm()
        vm.apply(listOf(two), BulkAction.DELETE_FROM_SERVER)
        vm.setKeepCopy(true)
        vm.confirmDelete()
        assertTrue(downloads.calls.isEmpty())
        assertEquals(listOf(Triple(BEN, id(2), true)), library.forgets)
    }

    @Test fun aDeleteAskedByOneProfileIsNeverSentForTheNext() = runTest(main.dispatcher) {
        val vm = vm()
        vm.apply(listOf(one), BulkAction.DELETE_FROM_SERVER)
        catalog.activeFlow.value = amy
        assertNull(vm.state.value.deleteAsk)
        vm.confirmDelete()
        assertTrue(manage.calls.isEmpty())
    }

    // ---- review findings 1a, 2, 3, 7, 8

    @Test fun onlyItemsTheServerLetsThisProfileDeleteAreOffered() = runTest(main.dispatcher) {
        manage.deletable = { it != id(2) }
        val vm = vm()
        val notices = mutableListOf<BulkNotice>()
        backgroundScope.launch { vm.notices.toList(notices) }
        vm.apply(listOf(one, two), BulkAction.DELETE_FROM_SERVER)
        assertEquals(listOf("Film One"), vm.state.value.deleteAsk!!.items.map { it.title })
        assertEquals(listOf(R.plurals.bulk_delete_not_allowed to 1), notices.map { it.plural to it.count })
        vm.dismissDelete()
        manage.deletable = { false }
        vm.apply(listOf(one, two), BulkAction.DELETE_FROM_SERVER)
        assertNull("none may be deleted: nothing is asked", vm.state.value.deleteAsk)
        assertEquals(R.plurals.bulk_delete_not_allowed to 2, notices.last().let { it.plural to it.count })
        assertTrue("nothing sent", manage.calls.isEmpty())
    }

    @Test fun anAdministratorWithoutTheDeletionPolicyIsNotOfferedDelete() = runTest(main.dispatcher) {
        rights.mayDelete = false
        val vm = vm()
        assertTrue(vm.state.value.canManage)
        assertFalse(vm.state.value.canDelete)
    }

    @Test fun aChangeOfTheDeleteRightAloneIsHeard() = runTest(main.dispatcher) {
        val vm = vm()
        assertTrue(vm.state.value.canDelete)
        rights.held.value = rights.held.value!!.copy(mayDelete = false)
        assertFalse(vm.state.value.canDelete)
        assertTrue(vm.state.value.canManage)
    }

    private fun episode(n: Int) = app.shoalwatch.data.downloads.OfflineEntry(entry(n, ItemKind.EPISODE), info(id(n)))

    private fun downloadedSeries(seriesId: String, seasonA: String, seasonB: String) {
        downloads.seriesFlow(BEN, seriesId).value = app.shoalwatch.data.downloads.OfflineSeries(
            entry(3, ItemKind.SERIES),
            listOf(
                app.shoalwatch.data.downloads.OfflineSeason(entry(4, ItemKind.SEASON), 1, listOf(episode(10), episode(11))),
                app.shoalwatch.data.downloads.OfflineSeason(entry(5, ItemKind.SEASON), 2, listOf(episode(12))),
            ),
        )
    }

    @Test fun deletingASeriesOffersToKeepItsDownloadedEpisodesAndKeepsWhatTheyNeed() = runTest(main.dispatcher) {
        downloadedSeries(id(3), id(4), id(5))
        val vm = vm()
        vm.apply(listOf(BulkItem(id(3), "Show", ItemKind.SERIES)), BulkAction.DELETE_FROM_SERVER)
        val ask = vm.state.value.deleteAsk!!
        assertEquals(mapOf(id(3) to listOf(id(10), id(11), id(12))), ask.downloaded)
        assertEquals(3, ask.savedCount)
        vm.setKeepCopy(true)
        vm.confirmDelete()
        assertEquals(listOf("delete:${id(3)}"), manage.calls)
        assertTrue("the episodes stay on the phone", downloads.calls.isEmpty())
        // The series record stays (Downloads lists the episodes under it); everything only leaves the lists.
        assertEquals(listOf(id(10), id(11), id(12), id(3)).map { Triple(BEN, it, true) }, library.forgets)
    }

    @Test fun deletingASeasonWithoutKeepingRemovesJustItsEpisodes() = runTest(main.dispatcher) {
        downloadedSeries(id(3), id(4), id(5))
        val vm = vm()
        vm.apply(listOf(BulkItem(id(4), "Season 1", ItemKind.SEASON, seriesId = id(3))), BulkAction.DELETE_FROM_SERVER)
        assertEquals(mapOf(id(4) to listOf(id(10), id(11))), vm.state.value.deleteAsk!!.downloaded)
        vm.confirmDelete()
        assertEquals(listOf("remove:${id(10)}", "remove:${id(11)}"), downloads.calls)
        assertEquals(listOf(id(10), id(11), id(4)).map { Triple(BEN, it, false) }, library.forgets)
    }

    @Test fun aPhoneSideFailureAfterTheServerDeleteStillCountsAndStillTidiesUp() = runTest(main.dispatcher) {
        downloads.statusFlow(BEN, id(1)).value = info(id(1))
        downloads.removeOutcome = { _, _ -> throw IllegalStateException("disk") }
        val vm = vm()
        val notices = mutableListOf<BulkNotice>()
        backgroundScope.launch { vm.notices.toList(notices) }
        vm.apply(listOf(one), BulkAction.DELETE_FROM_SERVER)
        vm.confirmDelete()
        assertEquals(listOf(R.plurals.bulk_deleted to 1), notices.map { it.plural to it.count })
        assertEquals("the lists are still updated", listOf(Triple(BEN, id(1), false)), library.forgets)
        assertEquals(listOf(id(1)), evicted)
    }

    @Test fun aConfirmWhileAnotherActionRunsIsKeptNotDropped() = runTest(main.dispatcher) {
        playstate.gate = kotlinx.coroutines.CompletableDeferred()
        val vm = vm()
        vm.apply(listOf(two), BulkAction.MARK_WATCHED)
        assertTrue(vm.state.value.running)
        vm.apply(listOf(one), BulkAction.DELETE_FROM_SERVER)
        vm.confirmDelete()
        assertTrue("not sent while the other runs", manage.calls.isEmpty())
        assertEquals("still asking", listOf("Film One"), vm.state.value.deleteAsk?.items?.map { it.title })
        playstate.gate!!.complete(Unit)
        vm.confirmDelete()
        assertEquals(listOf("delete:${id(1)}"), manage.calls)
    }

    @Test fun downloadPreparesEachAtTheDefaultQualityAndStartsWhatFits() = runTest(main.dispatcher) {
        val targets = mutableListOf<Pair<DownloadTarget, DownloadQuality>>()
        downloads.plans = { s, t, q -> targets += t to q; plan(s, enough = t != DownloadTarget.Series(id(3))) }
        val vm = vm()
        val notices = mutableListOf<BulkNotice>()
        backgroundScope.launch { vm.notices.toList(notices) }
        vm.apply(listOf(one, BulkItem(id(3), "Show", ItemKind.SERIES)), BulkAction.DOWNLOAD)
        assertEquals(listOf(DownloadTarget.Item(id(1)) to DownloadQuality.ORIGINAL, DownloadTarget.Series(id(3)) to DownloadQuality.ORIGINAL), targets)
        assertEquals(listOf("prepare", "start:1", "prepare"), downloads.calls)
        assertEquals(listOf(R.plurals.bulk_downloading to 1, R.plurals.bulk_not_done to 1), notices.map { it.plural to it.count })
    }

    @Test fun aNoticeForOneProfileIsNotShownToTheNext() = runTest(main.dispatcher) {
        val vm = vm()
        val notices = mutableListOf<BulkNotice>()
        playstate.gate = kotlinx.coroutines.CompletableDeferred()
        vm.apply(listOf(one), BulkAction.MARK_WATCHED)
        catalog.activeFlow.value = amy
        backgroundScope.launch { vm.notices.toList(notices) }
        playstate.gate!!.complete(Unit)
        assertTrue(notices.isEmpty())
    }

    private fun info(itemId: String) = DownloadInfo(
        itemId = itemId, state = DownloadState.COMPLETE, failure = null, quality = DownloadQuality.ORIGINAL, progress = 1f, totalBytes = 1,
        ownedByMe = true, storage = StorageKind.INTERNAL, available = true,
    )

    private fun plan(s: app.shoalwatch.data.servers.Session, enough: Boolean) = DownloadPlan(
        s, DownloadQuality.ORIGINAL, listOf(PlannedItem(id(1), DownloadQuality.ORIGINAL, 10, shareOnly = false)), emptyList(), 0, 10, 100,
        enoughSpace = enough, storage = StorageKind.INTERNAL, volumeId = null, mobileData = false,
    )
}
