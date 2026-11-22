package app.shoalwatch.ui.lists

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import app.shoalwatch.R
import app.shoalwatch.core.AppError
import app.shoalwatch.data.downloads.DownloadPlan
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.PlannedItem
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.lists.Playlist
import app.shoalwatch.data.lists.PlaylistEntry
import app.shoalwatch.data.playback.PlayQueues
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeLists
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.BulkItem
import app.shoalwatch.ui.browse.FakeDownloads
import app.shoalwatch.ui.browse.account
import app.shoalwatch.ui.browse.entry
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.browse.item
import app.shoalwatch.data.library.ItemWithUserData
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

private fun listItem(n: Int, name: String = "List $n") = ItemWithUserData(item(n, ItemKind.PLAYLIST, name), null)

private fun playlistOf(vararg entries: PlaylistEntry, canDelete: Boolean = true, name: String = "Weekend") =
    Playlist(item(99, ItemKind.PLAYLIST, name), canDelete, entries.toList())

private fun place(n: Int, kind: ItemKind = ItemKind.MOVIE) = PlaylistEntry("e$n", ItemWithUserData(item(n, kind, "Film $n"), null))

private val FAKE_PLAN = { s: Session, enough: Boolean ->
    DownloadPlan(
        s, DownloadQuality.ORIGINAL, listOf(PlannedItem(id(1), DownloadQuality.ORIGINAL, 10, shareOnly = false)), emptyList(), 0, 10, 100,
        enoughSpace = enough, storage = StorageKind.INTERNAL, volumeId = null, mobileData = false,
    )
}

/** Milestone 11 Task 7: the Playlists screen's ViewModel. */
class PlaylistsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val catalog = FakeCatalog(accounts = listOf(account(BEN, "ben"), account(AMY, "amy")), active = account(BEN, "ben"))
    private val lists = FakeLists()
    private fun vm() = PlaylistsViewModel(catalog, lists)

    @Test fun readsThePlaylistsLive() = runTest(main.dispatcher) {
        lists.playlists = listOf(listItem(1), listItem(2))
        val vm = vm()
        assertEquals(ListsPhase.READY, vm.state.value.phase)
        assertEquals(2, vm.state.value.playlists.size)
        assertEquals(listOf("playlists"), lists.calls)
    }

    @Test fun offlineSaysSoAndTryAgainReadsAgain() = runTest(main.dispatcher) {
        lists.failure = AppError.of(AppError.Kind.OFFLINE)
        val vm = vm()
        assertEquals(ListsPhase.OFFLINE, vm.state.value.phase)
        lists.failure = null
        lists.playlists = listOf(listItem(1))
        vm.retry()
        assertEquals(ListsPhase.READY, vm.state.value.phase)
        assertEquals(1, vm.state.value.playlists.size)
    }

    @Test fun aBlankOrTooLongNameIsRefusedWithSpecificWordsAndNothingIsSent() = runTest(main.dispatcher) {
        val vm = vm()
        vm.startCreate()
        vm.name.setTextAndPlaceCursorAtEnd("   ​  ")
        vm.create()
        assertEquals(R.string.lists_name_empty, vm.state.value.nameError)
        vm.name.setTextAndPlaceCursorAtEnd("x".repeat(201))
        vm.create()
        assertEquals(R.string.lists_name_too_long, vm.state.value.nameError)
        assertEquals(listOf("playlists"), lists.calls)
        assertTrue(vm.state.value.creating)
    }

    @Test fun aGoodNameCreatesAnEmptyPlaylistThenReadsTheListAgain() = runTest(main.dispatcher) {
        val vm = vm()
        val notices = mutableListOf<ListNotice>()
        backgroundScope.launch { vm.notices.toList(notices) }
        vm.startCreate()
        vm.name.setTextAndPlaceCursorAtEnd("  Friday   night ")
        lists.playlists = listOf(listItem(1, "Friday night"))
        vm.create()
        assertEquals(listOf("playlists", "createPlaylist:Friday night:", "playlists"), lists.calls)
        assertFalse(vm.state.value.creating)
        assertEquals("Friday night", vm.state.value.playlists.single().item.name)
        assertEquals(R.string.lists_created, notices.single().text)
    }

    @Test fun aRefusalStaysInTheWindowInTheSentenceFromThePlan() = runTest(main.dispatcher) {
        val vm = vm()
        vm.startCreate()
        vm.name.setTextAndPlaceCursorAtEnd("Friday")
        lists.failure = AppError.of(AppError.Kind.FORBIDDEN)
        vm.create()
        assertEquals(R.string.lists_refused, vm.state.value.nameError)
        assertTrue(vm.state.value.creating)
        assertFalse(vm.state.value.busy)
    }

    @Test fun comingBackReadsAgainSoADeletedOrRenamedPlaylistShowsRightWithoutASpinner() = runTest(main.dispatcher) {
        lists.playlists = listOf(listItem(1, "Weekend"), listItem(2, "Bedtime"))
        val vm = vm()
        lists.playlists = listOf(listItem(1, "Midweek"))
        vm.refresh()
        assertEquals(listOf("Midweek"), vm.state.value.playlists.map { it.item.name })
        assertEquals(ListsPhase.READY, vm.state.value.phase)
        assertEquals(2, lists.calls.count { it == "playlists" })
    }

    @Test fun anotherProfileStartsFresh() = runTest(main.dispatcher) {
        lists.playlists = listOf(listItem(1))
        val vm = vm()
        vm.startCreate()
        catalog.activeFlow.value = account(AMY, "amy")
        assertEquals(AMY, vm.state.value.session)
        assertFalse(vm.state.value.creating)
    }
}

/** Milestone 11 Task 7: one playlist's ViewModel. */
class PlaylistViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val catalog = FakeCatalog(accounts = listOf(account(BEN, "ben"), account(AMY, "amy")), active = account(BEN, "ben"))
    private val lists = FakeLists()
    private val queues = PlayQueues()
    private val downloads = FakeDownloads()
    private val pl = id(99)
    private fun vm(d: FakeDownloads? = downloads) = PlaylistViewModel(pl, catalog, lists, queues, d)
    private fun reads() = lists.calls.count { it == "playlist:$pl" }

    /** T7 review R2: entry, its page, Add to playlist, Back shows the playlist as it now is, without a spinner. */
    @Test fun comingBackReadsThePlaylistAgainWithoutASpinner() = runTest(main.dispatcher) {
        lists.playlist = playlistOf(place(1))
        val vm = vm()
        assertEquals(1, reads())
        lists.playlist = playlistOf(place(1), place(2))
        vm.refresh()
        assertEquals(ListsPhase.READY, vm.state.value.phase)
        assertEquals(listOf(id(1), id(2)), vm.state.value.playlist!!.entries.map { it.item.item.id })
        assertEquals(2, reads())
    }

    @Test fun playOpensTheFirstEntryWithAQueueInOrderAndShuffleKeepsTheSameSet() = runTest(main.dispatcher) {
        lists.playlist = playlistOf(place(1), place(2), place(3), place(4, ItemKind.SERIES))
        val vm = vm()
        val launches = mutableListOf<PlayLaunch>()
        backgroundScope.launch { vm.launches.toList(launches) }
        vm.play(shuffle = false)
        assertEquals(id(1), launches.single().itemId)
        assertEquals(listOf(id(1), id(2), id(3)), queues.get(BEN, launches.single().token)!!.ids)
        assertNull("another profile cannot use it", queues.get(AMY, launches.single().token))
        vm.play(shuffle = true)
        assertEquals(setOf(id(1), id(2), id(3)), queues.get(BEN, launches.last().token)!!.ids.toSet())
        assertEquals(launches.last().itemId, queues.get(BEN, launches.last().token)!!.ids.first())
    }

    @Test fun nothingPlayableSaysSoAndOpensNothing() = runTest(main.dispatcher) {
        lists.playlist = playlistOf(place(4, ItemKind.SERIES))
        val vm = vm()
        val launches = mutableListOf<PlayLaunch>()
        val notices = mutableListOf<ListNotice>()
        backgroundScope.launch { vm.launches.toList(launches) }
        backgroundScope.launch { vm.notices.toList(notices) }
        vm.play(shuffle = false)
        assertTrue(launches.isEmpty())
        assertEquals(R.string.lists_nothing_to_play, notices.single().text)
    }

    @Test fun downloadAllStartsEachFilmAndEpisodeAndSumsItUpInOneLine() = runTest(main.dispatcher) {
        lists.playlist = playlistOf(place(1), place(2, ItemKind.EPISODE), place(3, ItemKind.SERIES))
        downloads.plans = { s, _, _ -> FAKE_PLAN(s, true) }
        val vm = vm()
        val notices = mutableListOf<ListNotice>()
        backgroundScope.launch { vm.notices.toList(notices) }
        vm.downloadAll()
        assertEquals(listOf("prepare", "start:1", "prepare", "start:1"), downloads.calls)
        assertEquals(R.plurals.lists_downloading to 2, notices.single().let { it.plural to it.count })
        assertFalse(vm.state.value.busy)
    }

    @Test fun downloadAllSaysWhatCouldNotBeStarted() = runTest(main.dispatcher) {
        lists.playlist = playlistOf(place(1), place(2))
        downloads.plans = { s, t, _ -> FAKE_PLAN(s, t.toString().contains(id(1))) }
        val vm = vm()
        val notices = mutableListOf<ListNotice>()
        backgroundScope.launch { vm.notices.toList(notices) }
        vm.downloadAll()
        assertEquals(listOf(R.plurals.lists_downloading_some_room to 1), notices.map { it.plural to it.count })
        assertEquals("2", notices.single().arg)
    }

    /** T7 review R4: the line gives the true reason for the rest: the connection, room, or (mixed or anything else) none guessed. */
    @Test fun downloadAllGivesTheTrueReasonForTheRest() = runTest(main.dispatcher) {
        lists.playlist = playlistOf(place(1), place(2), place(3))
        val vm = vm()
        val notices = mutableListOf<ListNotice>()
        backgroundScope.launch { vm.notices.toList(notices) }
        fun downloadWith(rest: (Int) -> Unit) {
            downloads.plans = { s, t, _ ->
                val n = (1..3).first { t.toString().contains(id(it)) }
                if (n == 1) FAKE_PLAN(s, true) else { rest(n); FAKE_PLAN(s, false) }
            }
            vm.downloadAll()
        }
        downloadWith { throw AppError.of(AppError.Kind.NETWORK) }
        downloadWith { n -> if (n == 2) throw AppError.of(AppError.Kind.OFFLINE) }
        downloadWith { throw AppError.of(AppError.Kind.SERVER_ERROR) }
        assertEquals(
            listOf(R.plurals.lists_downloading_some_offline, R.plurals.lists_downloading_some, R.plurals.lists_downloading_some),
            notices.map { it.plural },
        )
        assertTrue(notices.all { it.count == 1 && it.arg == "3" })
    }

    @Test fun moveSendsTheNeighbouringPlaceThenReadsAgainAndDoesNothingAtTheEnds() = runTest(main.dispatcher) {
        lists.playlist = playlistOf(place(1), place(2), place(3))
        val vm = vm()
        assertEquals(1, reads())
        vm.move("e2", -1)
        vm.move("e2", 1)
        vm.move("e1", -1)
        vm.move("e3", 1)
        assertEquals(listOf("moveInPlaylist:$pl:e2:0", "moveInPlaylist:$pl:e2:2"), lists.calls.filter { it.startsWith("move") })
        assertEquals(3, reads())
    }

    @Test fun aRefusedMoveSaysTheSentenceFromThePlanAndStillReadsAgain() = runTest(main.dispatcher) {
        lists.playlist = playlistOf(place(1), place(2))
        val vm = vm()
        val notices = mutableListOf<ListNotice>()
        backgroundScope.launch { vm.notices.toList(notices) }
        lists.failure = AppError.of(AppError.Kind.FORBIDDEN)
        vm.move("e2", -1)
        assertEquals(R.string.lists_refused, notices.first().text)
        assertEquals(BEN, vm.state.value.session)
        assertFalse(vm.state.value.busy)
    }

    @Test fun removeSendsTheEntryIdNotTheItemId() = runTest(main.dispatcher) {
        lists.playlist = playlistOf(place(1), place(2))
        val vm = vm()
        vm.remove("e2")
        assertTrue("removeFromPlaylist:$pl:e2" in lists.calls)
        assertEquals(2, reads())
    }

    @Test fun renameChecksTheNameFirstThenRenamesOnce() = runTest(main.dispatcher) {
        lists.playlist = playlistOf(place(1), name = "Weekend")
        val vm = vm()
        vm.startRename()
        assertEquals("Weekend", vm.name.text.toString())
        vm.name.setTextAndPlaceCursorAtEnd("")
        vm.rename()
        assertEquals(R.string.lists_name_empty, vm.state.value.nameError)
        assertTrue(lists.calls.none { it.startsWith("rename") })
        vm.name.setTextAndPlaceCursorAtEnd("Midweek")
        vm.rename()
        assertEquals(listOf("renamePlaylist:$pl:Midweek"), lists.calls.filter { it.startsWith("rename") })
        assertFalse(vm.state.value.renaming)
        assertEquals(2, reads())
    }

    @Test fun deleteAsksFirstAndOnlyWhenTheServerSaysTheProfileMay() = runTest(main.dispatcher) {
        lists.playlist = playlistOf(place(1), canDelete = false)
        val vm = vm()
        vm.askDelete()
        assertFalse(vm.state.value.deleteAsk)
        lists.playlist = playlistOf(place(1), canDelete = true)
        vm.retry()
        vm.askDelete()
        assertTrue(vm.state.value.deleteAsk)
        assertTrue(lists.calls.none { it.startsWith("delete") })
        vm.confirmDelete()
        assertEquals(listOf("deletePlaylist:$pl"), lists.calls.filter { it.startsWith("delete") })
        assertTrue(vm.state.value.deleted)
    }

    @Test fun aRefusedDeleteSaysSoAndStays() = runTest(main.dispatcher) {
        lists.playlist = playlistOf(place(1))
        val vm = vm()
        val notices = mutableListOf<ListNotice>()
        backgroundScope.launch { vm.notices.toList(notices) }
        vm.askDelete()
        lists.failure = AppError.of(AppError.Kind.FORBIDDEN)
        vm.confirmDelete()
        assertFalse(vm.state.value.deleted)
        assertFalse(vm.state.value.deleteAsk)
        assertEquals(R.string.lists_refused, notices.single().text)
    }

    @Test fun aRefusedDeleteReadsThePlaylistAgainSoCanDeleteIsCurrent() = runTest(main.dispatcher) {
        lists.playlist = playlistOf(place(1), canDelete = true)
        val vm = vm()
        vm.askDelete()
        lists.failure = AppError.of(AppError.Kind.FORBIDDEN)
        val before = reads()
        vm.confirmDelete()
        assertEquals(before + 1, reads())
        assertFalse(vm.state.value.deleting)
    }

    @Test fun deleteCannotBeAskedWhileAnotherChangeRuns() = runTest(main.dispatcher) {
        lists.playlist = playlistOf(place(1), place(2))
        val vm = vm()
        // busy is set by a change in flight; with the test dispatcher a change finishes at once, so check the guard directly
        vm.downloadAll()
        assertFalse(vm.state.value.busy)
        vm.askDelete()
        assertTrue(vm.state.value.deleteAsk)
        assertFalse(vm.state.value.deleting)
    }

    @Test fun aDoubleTapOnPlayOpensThePlayerOnceUntilTheScreenIsBack() = runTest(main.dispatcher) {
        lists.playlist = playlistOf(place(1), place(2))
        val vm = vm()
        val launches = mutableListOf<PlayLaunch>()
        backgroundScope.launch { vm.launches.toList(launches) }
        vm.play(false)
        vm.play(true)
        assertEquals(1, launches.size)
        vm.onResumed()
        vm.play(true)
        assertEquals(2, launches.size)
    }

    @Test fun anUnknownPlaylistIsGoneAndOfflineOffersTryAgain() = runTest(main.dispatcher) {
        val vm = vm()
        assertEquals(ListsPhase.GONE, vm.state.value.phase)
        lists.failure = AppError.of(AppError.Kind.OFFLINE)
        vm.retry()
        assertEquals(ListsPhase.OFFLINE, vm.state.value.phase)
        assertNull(vm.state.value.playlist)
    }
}

/** Milestone 11 Task 7: the add-to picker's ViewModel. */
class ListPickerViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val catalog = FakeCatalog(accounts = listOf(account(BEN, "ben")), active = account(BEN, "ben"))
    private val lists = FakeLists()
    private fun vm() = ListPickerViewModel(catalog, lists)
    private fun picks(vararg n: Int) = n.map { BulkItem(id(it), "Film $it", ItemKind.MOVIE) }

    @Test fun choosingAPlaylistAddsTheItemsOnceAndClosesWithOneLine() = runTest(main.dispatcher) {
        lists.playlists = listOf(listItem(7, "Weekend"))
        val vm = vm()
        val notices = mutableListOf<ListNotice>()
        backgroundScope.launch { vm.notices.toList(notices) }
        vm.open(picks(1, 2, 1), ListKind.PLAYLIST)
        assertEquals(ListsPhase.READY, vm.state.value.phase)
        assertEquals(2, vm.state.value.itemCount)
        assertEquals("Weekend", vm.state.value.choices.single().name)
        vm.choose(vm.state.value.choices.single())
        assertEquals(listOf("playlists", "addToPlaylist:${id(7)}:${id(1)},${id(2)}"), lists.calls)
        assertFalse(vm.state.value.open)
        assertEquals(Triple(R.plurals.lists_added_playlist, 2, "Weekend"), notices.single().let { Triple(it.plural, it.count, it.arg) })
    }

    @Test fun collectionsUseTheCollectionCalls() = runTest(main.dispatcher) {
        lists.collections = listOf(listItem(8, "Trilogy"))
        val vm = vm()
        vm.open(picks(1), ListKind.COLLECTION)
        vm.choose(vm.state.value.choices.single())
        vm.open(picks(2), ListKind.COLLECTION)
        vm.startCreate()
        vm.name.setTextAndPlaceCursorAtEnd("Sequels")
        vm.create()
        assertEquals(
            listOf("collections", "addToCollection:${id(8)}:${id(1)}", "collections", "createCollection:Sequels:${id(2)}"),
            lists.calls,
        )
    }

    @Test fun aNewPlaylistNeedsAGoodNameFirst() = runTest(main.dispatcher) {
        val vm = vm()
        vm.open(picks(1), ListKind.PLAYLIST)
        vm.startCreate()
        vm.create()
        assertEquals(R.string.lists_name_empty, vm.state.value.error)
        vm.name.setTextAndPlaceCursorAtEnd("y".repeat(201))
        vm.create()
        assertEquals(R.string.lists_name_too_long, vm.state.value.error)
        assertEquals(listOf("playlists"), lists.calls)
        vm.name.setTextAndPlaceCursorAtEnd("y".repeat(200))
        vm.create()
        assertEquals("createPlaylist:${"y".repeat(200)}:${id(1)}", lists.calls.last())
        assertFalse(vm.state.value.open)
    }

    @Test fun aRefusalStaysInTheDialogAndCanBeTriedAgain() = runTest(main.dispatcher) {
        lists.playlists = listOf(listItem(7, "Weekend"))
        val vm = vm()
        vm.open(picks(1), ListKind.PLAYLIST)
        lists.failure = AppError.of(AppError.Kind.FORBIDDEN)
        vm.choose(vm.state.value.choices.single())
        assertTrue(vm.state.value.open)
        assertEquals(R.string.lists_refused, vm.state.value.error)
        assertFalse(vm.state.value.busy)
        lists.failure = null
        vm.choose(vm.state.value.choices.single())
        assertFalse(vm.state.value.open)
    }

    @Test fun moreThanOneCallCanCarryOpensNothingAndSaysSo() = runTest(main.dispatcher) {
        val vm = vm()
        val notices = mutableListOf<ListNotice>()
        backgroundScope.launch { vm.notices.toList(notices) }
        vm.open((1..501).map { BulkItem(id(it), "F$it", ItemKind.MOVIE) }, ListKind.PLAYLIST)
        assertFalse(vm.state.value.open)
        assertEquals(R.string.lists_too_many, notices.single().text)
        assertTrue(lists.calls.isEmpty())
        vm.open(emptyList(), ListKind.PLAYLIST)
        assertFalse(vm.state.value.open)
    }

    @Test fun offlineSaysSoWithTryAgainAndClosingForgetsTheItems() = runTest(main.dispatcher) {
        lists.failure = AppError.of(AppError.Kind.OFFLINE)
        val vm = vm()
        vm.open(picks(1), ListKind.PLAYLIST)
        assertEquals(ListsPhase.OFFLINE, vm.state.value.phase)
        lists.failure = null
        lists.playlists = listOf(listItem(7))
        vm.retry()
        assertEquals(ListsPhase.READY, vm.state.value.phase)
        vm.dismiss()
        assertFalse(vm.state.value.open)
        vm.choose(ListChoice(id(7), "x"))
        assertTrue("nothing to add after closing", lists.calls.none { it.startsWith("addTo") })
        assertNotNull(vm.state.value.session)
    }
}
