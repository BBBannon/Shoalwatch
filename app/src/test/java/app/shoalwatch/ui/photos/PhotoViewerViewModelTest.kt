package app.shoalwatch.ui.photos

import app.shoalwatch.core.AppError
import app.shoalwatch.data.discover.BrowseFilter
import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.ItemDetail
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeDiscover
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.FakeLibrary
import app.shoalwatch.ui.browse.account
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.browse.item
import app.shoalwatch.ui.lists.ListsPhase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Milestone 11 Task 10: the viewer list is the tapped photo's folder, photos only, live, and always opens at the tapped one. */
class PhotoViewerViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val catalog = FakeCatalog(accounts = listOf(account(BEN, "ben"), account(AMY, "amy")), active = account(BEN, "ben"))
    private val discover = FakeDiscover()
    private val library = FakeLibrary()
    private val folder = id(500)

    private fun photo(n: Int, kind: ItemKind = ItemKind.PHOTO) = ItemWithUserData(item(n, kind, "IMG $n").copy(parentId = folder), null)
    private fun vm(photoN: Int, parent: String? = folder) = PhotoViewerViewModel(id(photoN), parent, catalog, discover, library)

    @Test fun readsTheFoldersPhotosAndStartsAtTheTappedOne() = runTest(main.dispatcher) {
        discover.browsed = (1..5).map { photo(it) }
        val v = vm(3)
        assertEquals(ListsPhase.READY, v.state.value.phase)
        assertEquals(listOf(id(1), id(2), id(3), id(4), id(5)), v.state.value.photos.map { it.id })
        assertEquals(2, v.state.value.startIndex)
        assertEquals(listOf("browse:$folder:${BrowseFilter()}:0"), discover.calls)
    }

    @Test fun onlyPhotosAreKeptAndEachOnce() = runTest(main.dispatcher) {
        discover.browsed = listOf(photo(1), photo(2, ItemKind.FOLDER), photo(3), photo(3), photo(4, ItemKind.VIDEO))
        val v = vm(3)
        assertEquals(listOf(id(1), id(3)), v.state.value.photos.map { it.id })
        assertEquals(1, v.state.value.startIndex)
    }

    @Test fun aPhotoNotInTheFolderIsShownFirstSoTheTappedPictureAlwaysOpens() = runTest(main.dispatcher) {
        discover.browsed = listOf(photo(1), photo(2))
        library.itemFlow(BEN, id(9)).value = ItemDetail(photo(9), Freshness.Fresh)
        val v = vm(9)
        assertEquals(listOf(id(9), id(1), id(2)), v.state.value.photos.map { it.id })
        assertEquals(0, v.state.value.startIndex)
    }

    @Test fun aPhotoOpenedFromAPlaylistFindsItsFolderFromItself() = runTest(main.dispatcher) {
        discover.browsed = (1..3).map { photo(it) }
        library.itemFlow(BEN, id(2)).value = ItemDetail(photo(2), Freshness.Fresh)
        val v = vm(2, parent = null)
        assertEquals(listOf(id(1), id(2), id(3)), v.state.value.photos.map { it.id })
        assertEquals(1, v.state.value.startIndex)
        assertTrue(discover.calls.single().startsWith("browse:$folder"))
    }

    @Test fun aPhotoWithNoFolderAndNothingElseOpensAlone() = runTest(main.dispatcher) {
        library.itemFlow(BEN, id(2)).value = ItemDetail(ItemWithUserData(item(2, ItemKind.PHOTO, "IMG 2"), null), Freshness.Fresh)
        val v = vm(2, parent = null)
        assertEquals(listOf(id(2)), v.state.value.photos.map { it.id })
        assertTrue(discover.calls.isEmpty())
    }

    @Test fun offlineSaysSoAndTryAgainReadsAgain() = runTest(main.dispatcher) {
        discover.failure = AppError.of(AppError.Kind.OFFLINE)
        val v = vm(1)
        assertEquals(ListsPhase.OFFLINE, v.state.value.phase)
        assertTrue(v.state.value.photos.isEmpty())
        discover.failure = null
        discover.browsed = listOf(photo(1))
        v.retry()
        assertEquals(ListsPhase.READY, v.state.value.phase)
        assertEquals(1, v.state.value.photos.size)
    }

    @Test fun aPhotoThatIsGoneSaysGone() = runTest(main.dispatcher) {
        library.itemFlow(BEN, id(2)).value = ItemDetail(null, Freshness.Stale(AppError.Kind.SERVER_NOT_FOUND))
        assertEquals(ListsPhase.GONE, vm(2, parent = null).state.value.phase)
    }

    @Test fun aFolderIsReadAtMostFiveHundredPhotosDeep() = runTest(main.dispatcher) {
        discover.browsed = (1..700).map { photo(it) }
        val v = vm(1)
        assertEquals(PhotoViewerViewModel.MAX_PHOTOS, v.state.value.photos.size)
    }

    @Test fun theProfileChangingStartsOverAndNothingOfTheOldOneLingers() = runTest(main.dispatcher) {
        discover.browsed = listOf(photo(1), photo(2))
        val v = vm(1)
        assertEquals(BEN, v.state.value.session)
        catalog.activeFlow.value = account(AMY, "amy")
        assertEquals(AMY, v.state.value.session)
    }

    @Test fun signedOutHasNoSessionAndReadsNothing() = runTest(main.dispatcher) {
        catalog.activeFlow.value = null
        val v = vm(1)
        assertNull(v.state.value.session)
        assertTrue(discover.calls.isEmpty())
    }

    @Test fun closeRunsOnceHoweverOftenItIsTapped() = runTest(main.dispatcher) {
        var closed = 0
        val v = vm(1)
        repeat(3) { v.once { closed++ } }
        assertEquals(1, closed)
    }

    @Test fun theStateNeverPrintsAnItem() {
        val text = PhotoViewerState(photos = listOf(item(1, ItemKind.PHOTO, "SECRET NAME"))).toString()
        assertTrue(!text.contains("SECRET"))
    }
}
