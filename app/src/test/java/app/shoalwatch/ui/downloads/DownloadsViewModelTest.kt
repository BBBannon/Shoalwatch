package app.shoalwatch.ui.downloads

import app.shoalwatch.R
import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.downloads.RemoveOutcome
import app.shoalwatch.data.downloads.DownloadInfo
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.OfflineEntry
import app.shoalwatch.data.downloads.OfflineLibrary
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
    private val reconfirmed = mutableListOf<Session>()
    private val online = Connection.Online(address("http://192.168.1.10:8096"), isHome = true)

    private fun vm() = DownloadsViewModel(catalog, connections, downloads) { reconfirmed += it }

    private fun film(n: Int, name: String, owned: Boolean = true, state: DownloadState = DownloadState.COMPLETE) = OfflineEntry(
        entry(n, name = name),
        DownloadInfo(entry(n).item.id, state, null, DownloadQuality.ORIGINAL, 1f, 1_800_000_000, owned, StorageKind.INTERNAL, available = true),
    )

    private fun library(vararg films: OfflineEntry) = OfflineLibrary(emptyList(), films.toList(), emptyList())

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
        val vm = DownloadsViewModel(catalog, connections, slow)
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
        assertEquals(id, vm.state.value.confirmRemove)
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
        val vm = DownloadsViewModel(catalog, connections, failing)
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
        val vm = DownloadsViewModel(catalog, connections, flaky)
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

    @Test fun theOfflineSeriesFollowsTheSessionAndAMalformedIdIsGone() = runTest(UnconfinedTestDispatcher()) {
        val series = entry(50).item.id
        downloads.seriesFlow(BEN, series).value = OfflineSeries(entry(50), emptyList())
        val vm = OfflineSeriesViewModel(series, catalog, downloads)
        assertFalse(vm.state.value.gone)
        catalog.activeFlow.value = account(AMY, "amy")
        assertEquals(AMY, vm.state.value.session)
        assertTrue("Amy has no such series", vm.state.value.gone)
        assertTrue(OfflineSeriesViewModel("../x", catalog, downloads).state.value.gone)
    }
}
