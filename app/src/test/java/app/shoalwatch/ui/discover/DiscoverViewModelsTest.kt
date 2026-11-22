package app.shoalwatch.ui.discover

import app.shoalwatch.core.AppError
import app.shoalwatch.data.discover.BrowseFilter
import app.shoalwatch.data.discover.Discover
import app.shoalwatch.data.discover.Extras
import app.shoalwatch.data.discover.FilterOptions
import app.shoalwatch.data.discover.MediaVersion
import app.shoalwatch.data.discover.Named
import app.shoalwatch.data.discover.OnlineTrailer
import app.shoalwatch.data.discover.PersonRef
import app.shoalwatch.data.images.ArtworkRef
import app.shoalwatch.data.images.ArtworkType
import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.ListShape
import app.shoalwatch.data.library.Page
import app.shoalwatch.data.library.Sort
import app.shoalwatch.data.lists.CollectionRef
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeDiscover
import app.shoalwatch.ui.FakeLists
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.EMPTY_HOME
import app.shoalwatch.ui.browse.FILMS
import app.shoalwatch.ui.browse.FakeLibrary
import app.shoalwatch.ui.browse.SERVER_ID
import app.shoalwatch.ui.browse.account
import app.shoalwatch.ui.browse.entry
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.browse.item
import app.shoalwatch.ui.lists.ListsPhase
import app.shoalwatch.ui.testServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private fun art(n: Int) = ArtworkRef.of(BEN, id(n), ArtworkType.PRIMARY, "t$n")
private fun person(n: Int, name: String = "Person $n", role: String? = "Role") = PersonRef(id(n), name, role, "Actor", art(n))

private fun catalog() = FakeCatalog(listOf(testServer(id = SERVER_ID)), listOf(account(BEN, "ben"), account(AMY, "amy")), account(BEN, "ben"))

/** Milestone 11 Task 8: the detail extras block's ViewModel. */
class ExtrasViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val catalog = catalog()
    private val discover = FakeDiscover()
    private val lists = FakeLists()
    private val item = id(10)
    private fun vm() = ExtrasViewModel(item, catalog, discover, lists)

    private val full = Extras(
        cast = listOf(person(1), person(2)), localTrailers = listOf(entry(3, ItemKind.VIDEO, "Teaser")),
        onlineTrailers = listOf(OnlineTrailer("https://youtube.com/watch?v=x", "youtube.com", null)),
        specialFeatures = listOf(entry(4, ItemKind.VIDEO, "Making of")), similar = listOf(entry(5), entry(6)),
        versions = listOf(MediaVersion("a".repeat(32), "1080p"), MediaVersion("b".repeat(32), "4K")),
    )

    @Test fun readsTheExtrasAndTheCollectionsItIsPartOf() = runTest(main.dispatcher) {
        discover.extras = full
        lists.collectionsOf = listOf(CollectionRef(id(7), "Saga"))
        val v = vm()
        assertEquals(ExtrasPhase.READY, v.state.value.phase)
        assertEquals(2, v.state.value.extras.cast.size)
        assertEquals(listOf("Saga"), v.state.value.collections.map { it.name })
        assertEquals(listOf("extras:$item"), discover.calls)
        assertEquals(listOf("collectionsOf:$item"), lists.calls)
    }

    @Test fun everyPartFailingOfflineSaysSoAndTryAgainReadsAgain() = runTest(main.dispatcher) {
        discover.failure = AppError.of(AppError.Kind.OFFLINE)
        lists.failure = AppError.of(AppError.Kind.OFFLINE)
        val v = vm()
        assertEquals(ExtrasPhase.OFFLINE, v.state.value.phase)
        discover.failure = null
        lists.failure = null
        discover.extras = full
        v.retry()
        assertEquals(ExtrasPhase.READY, v.state.value.phase)
        assertEquals(2, v.state.value.extras.cast.size)
    }

    @Test fun theCollectionsFailingAloneHidesOnlyThem() = runTest(main.dispatcher) {
        discover.extras = full
        lists.failure = AppError.of(AppError.Kind.FORBIDDEN)
        val v = vm()
        assertEquals(ExtrasPhase.READY, v.state.value.phase)
        assertTrue(v.state.value.collections.isEmpty())
        assertEquals(2, v.state.value.extras.cast.size)
    }

    @Test fun aServerRefusalThatIsNotOfflineHidesTheBlockInsteadOfSayingOffline() = runTest(main.dispatcher) {
        discover.failure = AppError.of(AppError.Kind.SERVER_ERROR)
        val v = vm()
        assertEquals(ExtrasPhase.READY, v.state.value.phase)
        assertEquals(Extras.NONE, v.state.value.extras)
    }

    @Test fun theVersionPickedIsForOnePlayOnly() = runTest(main.dispatcher) {
        discover.extras = full
        val v = vm()
        assertEquals("a".repeat(32), v.state.value.shownVersionId)
        v.chooseVersion("b".repeat(32))
        assertEquals("b".repeat(32), v.state.value.shownVersionId)
        assertEquals("b".repeat(32), v.takeVersion())
        assertNull(v.takeVersion())
        assertEquals("a".repeat(32), v.state.value.shownVersionId)
    }

    @Test fun anIdThatIsNotOneOfTheVersionsIsIgnoredAndNeverSent() = runTest(main.dispatcher) {
        discover.extras = full
        val v = vm()
        v.chooseVersion("../etc")
        assertNull(v.state.value.chosenVersionId)
        assertNull(v.takeVersion())
    }

    @Test fun aDoubleTapOnPlayTakesTheChosenVersionOnceAndKeepsItForTheSecondTapToIgnore() = runTest(main.dispatcher) {
        discover.extras = full
        val v = vm()
        v.chooseVersion("b".repeat(32))
        val sent = mutableListOf<String?>()
        v.once { sent += v.takeVersion() }
        v.once { sent += v.takeVersion() }
        assertEquals(listOf<String?>("b".repeat(32)), sent)
    }

    @Test fun aSecondTapBeforeThePageIsBackDoesNothingAndResumingAllowsItAgain() = runTest(main.dispatcher) {
        val v = vm()
        var opened = 0
        v.once { opened++ }
        v.once { opened++ }
        assertEquals(1, opened)
        v.onResumed()
        v.once { opened++ }
        assertEquals(2, opened)
        v.release()
        v.once { opened++ }
        assertEquals(3, opened)
    }

    @Test fun comingBackReadsTheCollectionsAgain() = runTest(main.dispatcher) {
        discover.extras = full
        val v = vm()
        assertTrue(v.state.value.collections.isEmpty())
        lists.collectionsOf = listOf(CollectionRef(id(7), "Saga"))
        v.onResumed()
        assertEquals(listOf("Saga"), v.state.value.collections.map { it.name })
    }

    @Test fun anotherProfileStartsAgainAndNothingOfTheLastOneStays() = runTest(main.dispatcher) {
        discover.extras = full
        val v = vm()
        assertEquals(BEN, v.state.value.session)
        discover.extras = Extras.NONE
        catalog.activeFlow.value = account(AMY, "amy")
        assertEquals(AMY, v.state.value.session)
        assertEquals(Extras.NONE, v.state.value.extras)
        assertNull(v.state.value.chosenVersionId)
    }

    @Test fun nobodySignedInReadsNothing() = runTest(main.dispatcher) {
        catalog.activeFlow.value = null
        val v = vm()
        assertNull(v.state.value.session)
        assertTrue(discover.calls.isEmpty())
    }
}

/** A [Discover] whose pages are scripted (several pages, or held back), for the paging and stale-answer tests. */
private class ScriptedDiscover(private val base: FakeDiscover) : Discover by base {
    var workPages: List<List<ItemWithUserData>> = emptyList()
    var gate: CompletableDeferred<Unit>? = null
    val browseCalls = mutableListOf<Pair<BrowseFilter, Int>>()
    val browseParents = mutableListOf<String?>()
    var browseItems: (BrowseFilter) -> List<ItemWithUserData> = { emptyList() }
    var optionsFailure: AppError? = null

    override suspend fun filterOptions(s: Session, parentId: String): FilterOptions = optionsFailure?.let { throw it } ?: base.filterOptions(s, parentId)

    override suspend fun personWorks(s: Session, personId: String, startIndex: Int): Page<ItemWithUserData> {
        base.failure?.let { throw it }
        val page = startIndex / 2
        val items = workPages.getOrElse(page) { emptyList() }
        return Page(items, startIndex, workPages.sumOf { it.size }, startIndex + 2, page >= workPages.lastIndex)
    }

    override suspend fun browse(s: Session, parentId: String?, shape: ListShape, filter: BrowseFilter, sort: Sort, startIndex: Int): Page<ItemWithUserData> {
        browseCalls += filter to startIndex
        browseParents += parentId
        gate?.await()
        base.failure?.let { throw it }
        val items = browseItems(filter)
        return Page(items, startIndex, items.size, startIndex + items.size, true)
    }
}

/** Milestone 11 Task 8: a person's page. */
class PersonViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val catalog = catalog()
    private val fake = FakeDiscover()
    private val discover = ScriptedDiscover(fake)
    private val who = id(20)
    private fun vm() = PersonViewModel(who, catalog, discover)

    @Test fun showsThePersonAndTheirWorksPageByPage() = runTest(main.dispatcher) {
        fake.person = item(20, ItemKind.OTHER, "Ada")
        discover.workPages = listOf(listOf(entry(1), entry(2)), listOf(entry(3)))
        val v = vm()
        assertEquals(ListsPhase.READY, v.state.value.phase)
        assertEquals("Ada", v.state.value.person?.name)
        assertEquals(listOf(id(1), id(2)), v.state.value.works.items.map { it.item.id })
        assertTrue(v.state.value.works.canLoadMore)
        v.onEndVisible()
        assertEquals(listOf(id(1), id(2), id(3)), v.state.value.works.items.map { it.item.id })
        assertFalse(v.state.value.works.canLoadMore)
    }

    @Test fun aPersonThePhoneDoesNotKnowIsGoneNotAnError() = runTest(main.dispatcher) {
        fake.person = null
        assertEquals(ListsPhase.GONE, vm().state.value.phase)
    }

    @Test fun offlineSaysSoAndTryAgainReadsAgain() = runTest(main.dispatcher) {
        fake.person = item(20, ItemKind.OTHER, "Ada")
        fake.failure = AppError.of(AppError.Kind.OFFLINE)
        val v = vm()
        assertEquals(ListsPhase.OFFLINE, v.state.value.phase)
        fake.failure = null
        discover.workPages = listOf(listOf(entry(1)))
        v.retry()
        assertEquals(ListsPhase.READY, v.state.value.phase)
        assertEquals(1, v.state.value.works.items.size)
    }

    @Test fun anotherProfileNeverSeesTheLastOnesWorks() = runTest(main.dispatcher) {
        fake.person = item(20, ItemKind.OTHER, "Ada")
        discover.workPages = listOf(listOf(entry(1)))
        val v = vm()
        assertEquals(1, v.state.value.works.items.size)
        discover.workPages = listOf(listOf(entry(2)))
        catalog.activeFlow.value = account(AMY, "amy")
        assertEquals(AMY, v.state.value.session)
        assertEquals(listOf(id(2)), v.state.value.works.items.map { it.item.id })
    }
}

/** Milestone 11 Task 8: the filtered list. */
class BrowseViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val catalog = catalog()
    private val library = FakeLibrary().also { it.homeFlow(BEN).value = EMPTY_HOME.copy(views = listOf(FILMS)) }
    private val fake = FakeDiscover()
    private val discover = ScriptedDiscover(fake).also { it.browseItems = { listOf(entry(1), entry(2)) } }
    private fun vm(initial: BrowseFilter = BrowseFilter(), parent: String? = FILMS.id) = BrowseViewModel(parent, initial, catalog, library, discover)

    @Test fun readsTheFirstPageOfTheLibraryAndItsFilterChoices() = runTest(main.dispatcher) {
        fake.options = FilterOptions(listOf(2019, 2020), listOf("PG"), listOf("kids"), listOf(Named(id(5), "Comedy")), listOf(Named(id(6), "Acme")))
        val v = vm()
        assertEquals(ListsPhase.READY, v.state.value.phase)
        assertEquals("Films", v.state.value.title)
        assertEquals(2, v.state.value.list.items.size)
        assertEquals(listOf(2019, 2020), v.state.value.options?.years)
        assertEquals(listOf(BrowseFilter() to 0), discover.browseCalls)
    }

    @Test fun opensAtTheGenreAPageSentItWith() = runTest(main.dispatcher) {
        val v = vm(BrowseFilter(genreId = id(5)))
        assertEquals(id(5), v.state.value.filter.genreId)
        assertEquals(BrowseFilter(genreId = id(5)) to 0, discover.browseCalls.single())
    }

    @Test fun eachFilterStartsTheListAgainWithOneValueOfEachKind() = runTest(main.dispatcher) {
        val v = vm()
        v.setGenre(id(5)); v.setStudio(id(6)); v.setYear(2020); v.setRating("PG"); v.setTag("kids")
        val last = discover.browseCalls.last()
        assertEquals(BrowseFilter(genreId = id(5), studioId = id(6), year = 2020, rating = "PG", tag = "kids"), last.first)
        assertEquals(0, last.second)
        v.setGenre(null)
        assertNull(v.state.value.filter.genreId)
        v.clearFilters()
        assertTrue(v.state.value.filter.isEmpty)
    }

    @Test fun aLetterJumpsAndTheSameLetterAgainClearsIt() = runTest(main.dispatcher) {
        val v = vm()
        v.setLetter("B")
        assertEquals("B", v.state.value.filter.letter)
        v.setLetter("#")
        assertEquals("#", v.state.value.filter.letter)
        v.setLetter("#")
        assertNull(v.state.value.filter.letter)
        assertEquals(27, BROWSE_LETTERS.size)
        assertEquals("#", BROWSE_LETTERS.first())
    }

    @Test fun anOlderAnswerNeverLandsInANewerList() = runTest(main.dispatcher) {
        discover.browseItems = { f -> if (f.letter == "A") listOf(entry(1, name = "Apple")) else listOf(entry(2, name = "Zebra")) }
        val v = vm()
        val gate = CompletableDeferred<Unit>()
        discover.gate = gate
        v.setLetter("A")
        discover.gate = null
        v.setLetter("Z")
        gate.complete(Unit)
        assertEquals(listOf("Zebra"), v.state.value.list.items.map { it.item.name })
        assertEquals("Z", v.state.value.filter.letter)
    }

    @Test fun offlineSaysSoAndTryAgainReadsAgain() = runTest(main.dispatcher) {
        fake.failure = AppError.of(AppError.Kind.OFFLINE)
        val v = vm()
        assertEquals(ListsPhase.OFFLINE, v.state.value.list.phase)
        fake.failure = null
        v.retry()
        assertEquals(ListsPhase.READY, v.state.value.list.phase)
        assertEquals(2, v.state.value.list.items.size)
    }

    @Test fun theFilterChoicesFailingLeavesTheListAlone() = runTest(main.dispatcher) {
        discover.optionsFailure = AppError.of(AppError.Kind.SERVER_ERROR)
        val v = vm()
        assertEquals(ListsPhase.READY, v.state.value.phase)
        assertEquals(2, v.state.value.list.items.size)
        assertNull(v.state.value.options)
    }

    @Test fun librariesThatCouldNotBeReadAreOfflineNotGuessedAt() = runTest(main.dispatcher) {
        library.homeFlow(BEN).value = EMPTY_HOME.copy(views = emptyList(), viewsFreshness = Freshness.Stale(AppError.Kind.NETWORK))
        val v = vm()
        assertEquals(ListsPhase.OFFLINE, v.state.value.phase)
        assertTrue(discover.browseCalls.isEmpty())
    }

    @Test fun tryAgainAlsoReadsTheFilterChoicesThatFailedWithTheList() = runTest(main.dispatcher) {
        discover.optionsFailure = AppError.of(AppError.Kind.NETWORK)
        val v = vm()
        assertNull(v.state.value.options)
        discover.optionsFailure = null
        fake.options = FilterOptions(listOf(2020), emptyList(), emptyList(), emptyList(), emptyList())
        v.retry()
        assertEquals(listOf(2020), v.state.value.options?.years)
    }

    @Test fun anotherProfileStartsFromTheOpeningFilterNotTheLastOnesChoices() = runTest(main.dispatcher) {
        library.homeFlow(AMY).value = EMPTY_HOME.copy(views = listOf(FILMS))
        val v = vm(BrowseFilter(genreId = id(5)))
        v.setYear(2020); v.setStudio(id(6))
        catalog.activeFlow.value = account(AMY, "amy")
        assertEquals(BrowseFilter(genreId = id(5)), v.state.value.filter)
        assertEquals(BrowseFilter(genreId = id(5)), discover.browseCalls.last().first)
    }

    @Test fun noLibraryBrowsesAcrossAllOfThemSendingNoParentAndNoChoicesRead() = runTest(main.dispatcher) {
        val v = vm(BrowseFilter(genreId = id(5)), parent = null)
        assertEquals(ListsPhase.READY, v.state.value.phase)
        assertEquals(listOf<String?>(null), discover.browseParents)
        assertEquals(2, v.state.value.list.items.size)
        assertTrue(fake.calls.none { it.startsWith("filterOptions") })
    }

    @Test fun aFolderThatIsNoLibraryListsItsChildrenOfAnyKind() = runTest(main.dispatcher) {
        val v = vm(parent = id(77))
        assertEquals(ListsPhase.READY, v.state.value.phase)
        assertEquals("", v.state.value.title)
        assertEquals(2, v.state.value.list.items.size)
    }
}

/** Milestone 11 Task 8: the genres and studios pages. */
class NamesViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val catalog = catalog()
    private val discover = FakeDiscover()

    @Test fun listsTheGenresOfTheLibrary() = runTest(main.dispatcher) {
        discover.genres = listOf(Named(id(5), "Comedy"), Named(id(6), "Drama"))
        val v = NamesViewModel(FILMS.id, NamesKind.GENRES, catalog, discover)
        assertEquals(listOf("Comedy", "Drama"), v.state.value.names.items.map { it.name })
        assertEquals(listOf("genres:${FILMS.id}:0"), discover.calls)
    }

    @Test fun listsTheStudiosOfTheLibrary() = runTest(main.dispatcher) {
        discover.studios = listOf(Named(id(6), "Acme"))
        val v = NamesViewModel(FILMS.id, NamesKind.STUDIOS, catalog, discover)
        assertEquals(listOf("Acme"), v.state.value.names.items.map { it.name })
        assertEquals(listOf("studios:${FILMS.id}:0"), discover.calls)
    }

    @Test fun offlineSaysSoWithTryAgain() = runTest(main.dispatcher) {
        discover.failure = AppError.of(AppError.Kind.OFFLINE)
        val v = NamesViewModel(FILMS.id, NamesKind.GENRES, catalog, discover)
        assertEquals(ListsPhase.OFFLINE, v.state.value.names.phase)
        discover.failure = null
        discover.genres = listOf(Named(id(5), "Comedy"))
        v.retry()
        assertEquals(1, v.state.value.names.items.size)
    }
}
