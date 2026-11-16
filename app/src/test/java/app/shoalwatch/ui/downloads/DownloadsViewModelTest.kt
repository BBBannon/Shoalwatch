package app.shoalwatch.ui.downloads

import app.shoalwatch.R
import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.downloads.RemoveOutcome
import app.shoalwatch.data.downloads.DownloadInfo
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.OfflineEntry
import app.shoalwatch.data.downloads.OfflineLibrary
import app.shoalwatch.data.downloads.OfflineSeason
import app.shoalwatch.data.downloads.OfflineSeries
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.address
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.FakeDownloads
import app.shoalwatch.ui.browse.SERVER_ID
import app.shoalwatch.ui.browse.account
import app.shoalwatch.ui.browse.entry
import app.shoalwatch.ui.testServer
import app.shoalwatch.ui.browse.FakeSync
import app.shoalwatch.data.sync.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Downloads tab over [FakeDownloads]: the session rule, the delete confirm, a failed read, offline, and the re-check. */
class DownloadsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val catalog = FakeCatalog(listOf(testServer(id = SERVER_ID)), listOf(account(BEN, "ben"), account(AMY, "amy")), account(BEN, "ben"))
    private val connections = FakeConnections()
    private val downloads = FakeDownloads()
    private val sync = FakeSync()
    private val reconfirmed = mutableListOf<Session>()
    private val online = Connection.Online(address("http://192.168.1.10:8096"), isHome = true)

    private fun vm() = DownloadsViewModel(catalog, connections, downloads, sync) { reconfirmed += it }

    private fun film(n: Int, name: String, owned: Boolean = true, state: DownloadState = DownloadState.COMPLETE) = OfflineEntry(
        entry(n, name = name),
        DownloadInfo(entry(n).item.id, state, null, DownloadQuality.ORIGINAL, 1f, 1_800_000_000, owned, StorageKind.INTERNAL, available = true),
    )

    private fun library(vararg films: OfflineEntry) = OfflineLibrary(emptyList(), films.toList(), emptyList())

    @Test fun theSyncLineIsTheActiveSessionsOwnAndFollowsASwitch() = runTest(UnconfinedTestDispatcher()) {
        sync.set(BEN, waiting = 3)
        sync.set(AMY, waiting = 1, needsSignIn = true)
        val vm = vm()
        assertEquals(3, vm.state.value.sync.waiting)
        sync.set(BEN, waiting = 0, lastSyncedAt = 5L)
        assertEquals(SyncStatus(0, 0, false, 5L), vm.state.value.sync)
        catalog.setActive(SERVER_ID, AMY.userId)
        assertEquals("Amy's own status, never Ben's", SyncStatus(1, 0, true, null), vm.state.value.sync)
        catalog.activeFlow.value = null
        assertEquals(SyncStatus.NONE, vm.state.value.sync)
    }

    @Test fun switchingAccountRestartsAndTheOldProfilesTitlesNeverAppear() = runTest(UnconfinedTestDispatcher()) {
        downloads.libraryFlow(BEN).value = library(film(1, "Ben's Film"))
        val vm = vm()
        assertEquals(listOf("Ben's Film"), vm.state.value.library.films.map { it.item.item.name })
        val seen = mutableListOf<DownloadsUiState>()
        val job = launch { vm.state.collect { seen += it } }
        catalog.activeFlow.value = account(AMY, "amy")
        val afterSwitch = seen.dropWhile { it.session != AMY }
        assertTrue("the new session's first state is empty", afterSwitch.first().library.films.isEmpty())
        assertTrue("nothing of Ben's under Amy", afterSwitch.none { s -> s.library.films.any { it.item.item.name == "Ben's Film" } })
        assertEquals(AMY, vm.state.value.session)
        job.cancel()
    }

    @Test fun beforeTheNewSessionsFirstAnswerItIsLoadingAndEmpty() = runTest(UnconfinedTestDispatcher()) {
        downloads.libraryFlow(BEN).value = library(film(1, "Ben's Film"))
        val hold = MutableStateFlow(false)
        val slow = object : app.shoalwatch.data.downloads.Downloads by downloads {
            override fun library(s: Session) = if (s == AMY) flow { hold.collect { if (it) emit(library(film(2, "Amy's Film"))) } } else downloads.library(s)
        }
        val vm = DownloadsViewModel(catalog, connections, slow, sync)
        catalog.activeFlow.value = account(AMY, "amy")
        assertTrue(vm.state.value.loading)
        assertTrue(vm.state.value.library.films.isEmpty())
        hold.value = true
        assertEquals(listOf("Amy's Film"), vm.state.value.library.films.map { it.item.item.name })
    }

    @Test fun removeAsksFirstAndSaysWhatHappened() = runTest(UnconfinedTestDispatcher()) {
        downloads.libraryFlow(BEN).value = library(film(1, "Film"))
        val vm = vm()
        val messages = mutableListOf<SessionMessage>()
        val job = launch { vm.messages.toList(messages) }
        val id = entry(1).item.id
        vm.askRemove(id)
        assertEquals(listOf(id), vm.state.value.confirmRemove?.itemIds)
        assertEquals(1_800_000_000L, vm.state.value.confirmRemove?.bytes)
        assertTrue("nothing removed before the yes", downloads.calls.isEmpty())
        vm.cancelRemove()
        assertNull(vm.state.value.confirmRemove)
        assertTrue(downloads.calls.isEmpty())
        downloads.removeOutcome = { _, _ -> RemoveOutcome.KeptForOthers }
        vm.askRemove(id); vm.confirmRemove()
        assertEquals(listOf("remove:$id"), downloads.calls)
        assertEquals(SessionMessage(BEN, R.string.dl_kept_for_others), messages.single())
        downloads.removeOutcome = { s, i -> RemoveOutcome.Deleted(app.shoalwatch.data.db.downloads.DownloadKey(s.serverId, i)) }
        vm.askRemove(id); vm.confirmRemove()
        assertEquals("deleted: no message", 1, messages.size)
        job.cancel()
    }

    @Test fun aConfirmLeftFromAnotherSessionDoesNothing() = runTest(UnconfinedTestDispatcher()) {
        val vm = vm()
        vm.askRemove(entry(1).item.id)
        catalog.activeFlow.value = account(AMY, "amy")
        assertNull(vm.state.value.confirmRemove)
        vm.confirmRemove()
        assertTrue(downloads.calls.isEmpty())
    }

    @Test fun aFailedActionSaysItsOwnWords() = runTest(UnconfinedTestDispatcher()) {
        val failing = object : app.shoalwatch.data.downloads.Downloads by downloads {
            override suspend fun pause(s: Session, itemId: String) = throw AppError.of(AppError.Kind.UNKNOWN)
        }
        val vm = DownloadsViewModel(catalog, connections, failing, sync)
        val messages = mutableListOf<SessionMessage>()
        val job = launch { vm.messages.toList(messages) }
        vm.pause(entry(1).item.id)
        assertEquals(BEN, messages.single().session)
        assertTrue(messages.single().text!!.isNotBlank())
        job.cancel()
    }

    @Test fun aFlowFailureIsALocalFailureWithTryAgain() = runTest(UnconfinedTestDispatcher()) {
        var fail = true
        val flaky = object : app.shoalwatch.data.downloads.Downloads by downloads {
            override fun library(s: Session) = flow { if (fail) throw IllegalStateException("disk"); emit(library(film(1, "Film"))) }
        }
        val vm = DownloadsViewModel(catalog, connections, flaky, sync)
        assertTrue(vm.state.value.localFailure)
        assertFalse(vm.state.value.loading)
        fail = false
        vm.tryAgain()
        assertFalse(vm.state.value.localFailure)
        assertEquals(1, vm.state.value.library.films.size)
    }

    @Test fun offlineStillShowsEverythingSavedAndAsksNothing() = runTest(UnconfinedTestDispatcher()) {
        connections.stateFor(SERVER_ID).value = Connection.Offline()
        downloads.libraryFlow(BEN).value = library(film(1, "Film"), film(2, "Shared", owned = false))
        val vm = vm()
        assertFalse(vm.state.value.online)
        assertEquals(2, vm.state.value.library.films.size)
        assertTrue("no re-check offline", reconfirmed.isEmpty())
        assertTrue("no call at all", downloads.calls.isEmpty())
    }

    @Test fun openingOnlineReChecksSharedCopiesForTheActiveSessionAndAgainWhenBackOnline() = runTest(UnconfinedTestDispatcher()) {
        connections.stateFor(SERVER_ID).value = online
        vm()
        assertEquals(listOf(BEN), reconfirmed)
        connections.stateFor(SERVER_ID).value = Connection.Offline()
        connections.stateFor(SERVER_ID).value = online
        assertEquals(listOf(BEN, BEN), reconfirmed)
        catalog.activeFlow.value = account(AMY, "amy")
        assertEquals(listOf(BEN, BEN, AMY), reconfirmed)
    }

    /** Walkthrough W-3: the view model outlives a tab switch, so coming back to the tab asks again (the re-check is rate limited). */
    @Test fun comingBackToTheTabReChecksAgainWhileOnlineAndNeverOffline() = runTest(UnconfinedTestDispatcher()) {
        connections.stateFor(SERVER_ID).value = online
        val vm = vm()
        vm.opened()
        assertEquals(listOf(BEN, BEN), reconfirmed)
        connections.stateFor(SERVER_ID).value = Connection.Offline()
        vm.opened()
        assertEquals("nothing asked offline", listOf(BEN, BEN), reconfirmed)
    }

    @Test fun theOfflineSeriesFollowsTheSessionAndAMalformedIdIsGone()= runTest(UnconfinedTestDispatcher()) {
        val series = entry(50).item.id
        downloads.seriesFlow(BEN, series).value = OfflineSeries(entry(50), emptyList())
        val vm = OfflineSeriesViewModel(series, catalog, downloads)
        assertFalse(vm.state.value.gone)
        catalog.activeFlow.value = account(AMY, "amy")
        assertEquals(AMY, vm.state.value.session)
        assertTrue("Amy has no such series", vm.state.value.gone)
        assertTrue(OfflineSeriesViewModel("../x", catalog, downloads).state.value.gone)
    }

    private fun ep(n: Int, owned: Boolean, bytes: Long = 1_000_000_000) = OfflineEntry(
        entry(n),
        DownloadInfo(entry(n).item.id, DownloadState.COMPLETE, null, DownloadQuality.ORIGINAL, 1f, bytes, owned, StorageKind.INTERNAL, available = true),
    )

    private val seriesId get() = entry(50).item.id
    private val season1 get() = entry(61)

    /** Season 1: Ben's episodes 101 and 102 and a shared 103; season 2: a shared 201 only. */
    private fun bensSeries() = OfflineSeries(entry(50), listOf(
        OfflineSeason(season1, 1, listOf(ep(101, true), ep(102, true, 2_000_000_000), ep(103, false))),
        OfflineSeason(entry(62), 2, listOf(ep(201, false))),
    ))

    @Test fun anOwnerRemovesAnEpisodeFromTheOfflineSeries() = runTest(UnconfinedTestDispatcher()) {
        downloads.seriesFlow(BEN, seriesId).value = bensSeries()
        val vm = OfflineSeriesViewModel(seriesId, catalog, downloads)
        val messages = mutableListOf<SessionMessage>()
        val job = launch { vm.messages.toList(messages) }
        vm.askRemove(entry(101).item.id)
        assertEquals(1_000_000_000L, vm.state.value.confirmRemove?.bytes)
        assertTrue("nothing before the yes", downloads.calls.isEmpty())
        downloads.removeOutcome = { _, _ -> RemoveOutcome.KeptForOthers }
        vm.confirmRemove()
        assertEquals(listOf("remove:${entry(101).item.id}"), downloads.calls)
        assertEquals(SessionMessage(BEN, R.string.dl_kept_for_others), messages.single())
        assertNull(vm.state.value.confirmRemove)
        job.cancel()
    }

    @Test fun anOwnerRemovesItsOwnEpisodesOfASeasonAndNeverAnotherProfiles() = runTest(UnconfinedTestDispatcher()) {
        downloads.seriesFlow(BEN, seriesId).value = bensSeries()
        val vm = OfflineSeriesViewModel(seriesId, catalog, downloads)
        vm.askRemoveSeason(season1.item.id)
        assertEquals(RemoveKind.SEASON, vm.state.value.confirmRemove?.kind)
        assertEquals(3_000_000_000L, vm.state.value.confirmRemove?.bytes)
        vm.confirmRemove()
        assertEquals(listOf("remove:${entry(101).item.id}", "remove:${entry(102).item.id}"), downloads.calls)
        vm.askRemoveSeason(entry(62).item.id)
        assertNull("it owns nothing in season 2", vm.state.value.confirmRemove)
    }

    @Test fun anOwnerRemovesItsOwnEpisodesOfASeriesFromTheDownloadsTab() = runTest(UnconfinedTestDispatcher()) {
        downloads.seriesFlow(BEN, seriesId).value = bensSeries()
        val vm = vm()
        vm.askRemoveSeries(seriesId)
        assertEquals(RemoveKind.SERIES, vm.state.value.confirmRemove?.kind)
        assertEquals(listOf(entry(101).item.id, entry(102).item.id), vm.state.value.confirmRemove?.itemIds)
        vm.confirmRemove()
        assertEquals(listOf("remove:${entry(101).item.id}", "remove:${entry(102).item.id}"), downloads.calls)
        downloads.calls.clear()
        downloads.seriesFlow(BEN, seriesId).value = OfflineSeries(entry(50), listOf(OfflineSeason(entry(62), 2, listOf(ep(201, false)))))
        vm.askRemoveSeries(seriesId)
        assertNull("a non-owner gets no remove", vm.state.value.confirmRemove)
    }

    @Test fun aSeasonConfirmLeftFromAnotherSessionDoesNothing() = runTest(UnconfinedTestDispatcher()) {
        downloads.seriesFlow(BEN, seriesId).value = bensSeries()
        val vm = OfflineSeriesViewModel(seriesId, catalog, downloads)
        vm.askRemoveSeason(season1.item.id)
        catalog.activeFlow.value = account(AMY, "amy")
        assertNull(vm.state.value.confirmRemove)
        vm.confirmRemove()
        assertTrue(downloads.calls.isEmpty())
    }
}
