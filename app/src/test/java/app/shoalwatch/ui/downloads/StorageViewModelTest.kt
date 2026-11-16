package app.shoalwatch.ui.downloads

import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.downloads.RemoveOutcome
import app.shoalwatch.data.downloads.FilmUse
import app.shoalwatch.data.downloads.SeriesUse
import app.shoalwatch.data.downloads.StorageView
import app.shoalwatch.data.downloads.WatchedSummary
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.FakeDownloads
import app.shoalwatch.ui.browse.FakeHousekeeping
import app.shoalwatch.ui.browse.SERVER_ID
import app.shoalwatch.ui.browse.account
import app.shoalwatch.ui.browse.entry
import app.shoalwatch.ui.testServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Storage over [FakeHousekeeping] and [FakeDownloads]: the session rule, each confirm and what it says afterwards, a failed read. */
class StorageViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val catalog = FakeCatalog(listOf(testServer(id = SERVER_ID)), listOf(account(BEN, "ben"), account(AMY, "amy")), account(BEN, "ben"))
    private val housekeeping = FakeHousekeeping()
    private val downloads = FakeDownloads()

    private fun vm() = StorageViewModel(catalog, housekeeping, downloads)

    private val show = SeriesUse(entry(10, ItemKind.SERIES, "Ben's Show"), episodes = 3, watched = 1, bytes = 3_000, deletableBytes = 2_000, ownedEpisodes = 2)
    private val shared = SeriesUse(entry(11, ItemKind.SERIES, "Shared Show"), episodes = 1, watched = 0, bytes = 1_000, deletableBytes = 0, ownedEpisodes = 0)
    private val film = FilmUse(entry(20, name = "Ben's Film"), bytes = 4_000, watched = true, ownedByMe = true)
    private val notMine = FilmUse(entry(21, name = "Their Film"), bytes = 5_000, watched = false, ownedByMe = false)

    private fun view(vararg series: SeriesUse, films: List<FilmUse> = emptyList()) = StorageView(emptyList(), series.toList(), films, 0, false)

    @Test fun switchingAccountEmptiesTheScreenAtOnceAndTheOldProfilesTitlesNeverAppear() = runTest(UnconfinedTestDispatcher()) {
        housekeeping.storageFlow(BEN).value = view(show, films = listOf(film))
        val vm = vm()
        assertEquals(listOf("Ben's Show"), vm.state.value.view?.series?.map { it.series.item.name })
        val seen = mutableListOf<StorageUiState>()
        val job = launch { vm.state.collect { seen += it } }
        catalog.activeFlow.value = account(AMY, "amy")
        val after = seen.dropWhile { it.session != AMY }
        assertTrue("the new session's first state is loading and empty", after.first().loading && after.first().view == null)
        assertTrue(after.none { s -> s.view?.series.orEmpty().any { it.series.item.name == "Ben's Show" } || s.view?.films.orEmpty().any { it.film.item.name == "Ben's Film" } })
        assertEquals(AMY, vm.state.value.session)
        job.cancel()
    }

    @Test fun aConfirmOpenedForOneSessionNeverActsAsAnother() = runTest(UnconfinedTestDispatcher()) {
        housekeeping.storageFlow(BEN).value = view(show)
        val vm = vm()
        vm.askDeleteSeries(show.series.item.id)
        assertEquals(StorageConfirm.Series(show.series.item.id, "Ben's Show", 2_000), vm.state.value.confirm)
        catalog.activeFlow.value = account(AMY, "amy")
        assertNull("dropped at the switch", vm.state.value.confirm)
        vm.confirm()
        catalog.activeFlow.value = account(BEN, "ben")
        assertNull("it does not come back for the old session", vm.state.value.confirm)
        vm.confirm()
        assertEquals(emptyList<String>(), housekeeping.calls.filter { it.startsWith("delete") })
    }

    @Test fun deleteSeriesAsksThenDeletesAndSaysHowMany() = runTest(UnconfinedTestDispatcher()) {
        housekeeping.storageFlow(BEN).value = view(show)
        housekeeping.deletedSeries = 2
        val vm = vm()
        val messages = mutableListOf<StorageMessage>()
        val job = launch { vm.messages.toList(messages) }
        vm.askDeleteSeries(show.series.item.id)
        vm.confirm()
        assertEquals(listOf("deleteSeries:ben:${show.series.item.id}"), housekeeping.calls.filter { it.startsWith("delete") })
        assertEquals(listOf(StorageMessage(StorageMessage.Kind.DELETED, 2, BEN)), messages)
        assertNull(vm.state.value.confirm)
        housekeeping.deletedSeries = 0
        vm.askDeleteSeries(show.series.item.id)
        vm.confirm()
        assertEquals("all kept for another owner", StorageMessage.Kind.KEPT_FOR_OTHERS, messages.last().kind)
        job.cancel()
    }

    @Test fun aRowThisProfileOwnsNothingOfHasNoConfirm() = runTest(UnconfinedTestDispatcher()) {
        housekeeping.storageFlow(BEN).value = view(shared, films = listOf(notMine))
        val vm = vm()
        vm.askDeleteSeries(shared.series.item.id)
        assertNull(vm.state.value.confirm)
        vm.askDeleteFilm(notMine.film.item.id)
        assertNull(vm.state.value.confirm)
    }

    @Test fun deleteFilmUsesTheOwnRemovalAndSaysWhatHappened() = runTest(UnconfinedTestDispatcher()) {
        housekeeping.storageFlow(BEN).value = view(films = listOf(film))
        val vm = vm()
        val messages = mutableListOf<StorageMessage>()
        val job = launch { vm.messages.toList(messages) }
        vm.askDeleteFilm(film.film.item.id)
        assertEquals(StorageConfirm.Film(film.film.item.id, "Ben's Film", 4_000), vm.state.value.confirm)
        vm.confirm()
        assertEquals(listOf("remove:${film.film.item.id}"), downloads.calls)
        downloads.removeOutcome = { _, _ -> RemoveOutcome.KeptForOthers }
        vm.askDeleteFilm(film.film.item.id); vm.confirm()
        downloads.removeOutcome = { _, _ -> RemoveOutcome.NotYours }
        vm.askDeleteFilm(film.film.item.id); vm.confirm()
        assertEquals(
            listOf(StorageMessage.Kind.DELETED, StorageMessage.Kind.KEPT_FOR_OTHERS, StorageMessage.Kind.FAILED),
            messages.map { it.kind },
        )
        assertEquals(1, messages.first().count)
        job.cancel()
    }

    @Test fun deleteWatchedReadsTheSummaryFirstThenDeletes() = runTest(UnconfinedTestDispatcher()) {
        housekeeping.storageFlow(BEN).value = view(show)
        housekeeping.summary = WatchedSummary(3, 6_000, 1)
        housekeeping.deletedWatched = 3
        val vm = vm()
        val messages = mutableListOf<StorageMessage>()
        val job = launch { vm.messages.toList(messages) }
        vm.askDeleteWatched()
        assertEquals(StorageConfirm.Watched(WatchedSummary(3, 6_000, 1)), vm.state.value.confirm)
        assertEquals(listOf("summary:ben"), housekeeping.calls.toList())
        vm.cancel()
        assertNull(vm.state.value.confirm)
        vm.askDeleteWatched(); vm.confirm()
        assertEquals("deleteWatched:ben", housekeeping.calls.last())
        assertEquals(listOf(StorageMessage(StorageMessage.Kind.DELETED, 3, BEN)), messages)
        housekeeping.summary = WatchedSummary(0, 0, 0)
        vm.askDeleteWatched()
        assertNull("nothing watched: no confirm", vm.state.value.confirm)
        job.cancel()
    }

    @Test fun aFailedDeleteSaysSo() = runTest(UnconfinedTestDispatcher()) {
        housekeeping.storageFlow(BEN).value = view(show)
        val vm = vm()
        val messages = mutableListOf<StorageMessage>()
        val job = launch { vm.messages.toList(messages) }
        vm.askDeleteSeries(show.series.item.id)
        housekeeping.failWith = AppError.of(AppError.Kind.UNKNOWN)
        vm.confirm()
        vm.askDeleteWatched()
        assertEquals(listOf(StorageMessage.Kind.FAILED, StorageMessage.Kind.FAILED), messages.map { it.kind })
        job.cancel()
    }

    @Test fun aReadFailureIsALocalFailureWithTryAgain() = runTest(UnconfinedTestDispatcher()) {
        housekeeping.storageFailure = IllegalStateException("database closed")
        val vm = vm()
        assertTrue(vm.state.value.localFailure)
        assertNull(vm.state.value.view)
        housekeeping.storageFailure = null
        housekeeping.storageFlow(BEN).value = view(show)
        vm.tryAgain()
        assertEquals(listOf("Ben's Show"), vm.state.value.view?.series?.map { it.series.item.name })
        assertTrue(!vm.state.value.localFailure)
    }
}
