package app.shoalwatch.data.db

import app.cash.turbine.test
import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.data.db.library.ListEntryEntity
import app.shoalwatch.data.db.library.PageResult
import app.shoalwatch.data.db.library.toModel
import app.shoalwatch.data.library.LibraryKind
import app.shoalwatch.data.library.LibraryView
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lists (ordered membership), pages written as one transaction, and views. */
class LibraryListTest : LibraryTestBase() {
    private val lists get() = db.listDao()

    private fun films(vararg labels: String) = labels.map { row(item(it)) }

    private suspend fun names(listKey: String = "resume", s: Session = s1u1, limit: Int = 100) =
        lists.getList(s.serverId, s.userId, listKey, limit).map { it.item.name }

    @Test fun aListReadsBackInPositionOrderAndHonoursTheLimit() = runBlocking {
        library.writePage(s1u1, "resume", 0, films("c", "a", "b"), 3, t0, requestedAtSeq = 0)
        assertEquals(listOf("Film c", "Film a", "Film b"), names())
        assertEquals(listOf("Film c", "Film a"), names(limit = 2))
    }

    @Test fun aListIsJoinedToTheListOwnersUserData() = runBlocking {
        library.writeItems(s1u2, listOf(row(item("a"), watched(favourite = true))), t0, requestedAtSeq = 0)
        library.writePage(s1u1, "resume", 0, listOf(row(item("a"), watched(played = true))), 1, t0, requestedAtSeq = 0)
        library.writePage(s1u2, "resume", 0, listOf(row(item("a"))), 1, t0, requestedAtSeq = 0)
        assertTrue(lists.getList(S1, U1, "resume", 10).single().userData!!.played)
        val bob = lists.getList(S1, U2, "resume", 10).single().userData!!
        assertTrue("bob's own favourite, not ann's played", bob.favourite && !bob.played)
    }

    @Test fun twoUsersKeepTheirOwnListsUnderTheSameKey() = runBlocking {
        library.writePage(s1u1, "resume", 0, films("a", "b"), 2, t0, requestedAtSeq = 0)
        library.writePage(s1u2, "resume", 0, films("c"), 1, t0, requestedAtSeq = 0)
        assertEquals(listOf("Film a", "Film b"), names(s = s1u1))
        assertEquals(listOf("Film c"), names(s = s1u2))
        assertEquals(2, lists.getState(S1, U1, "resume")!!.total)
        assertEquals(1, lists.getState(S1, U2, "resume")!!.total)
    }

    @Test fun anEntryWhoseItemRowIsMissingIsNotReturned() = runBlocking {
        library.writePage(s1u1, "resume", 0, films("a", "b"), 2, t0, requestedAtSeq = 0)
        lists.insertEntries(listOf(ListEntryEntity(S1, U1, "resume", 2, id("ghost"))))
        assertEquals(listOf("Film a", "Film b"), names())
    }

    @Test fun laterPagesAppendAndKeepEarlierPositions() = runBlocking {
        library.writePage(s1u1, "kids", 0, films("a", "b"), 5, t0, requestedAtSeq = 0)
        library.writePage(s1u1, "kids", 2, films("c", "d"), 5, t0.plusSeconds(1), requestedAtSeq = 0)
        assertEquals(listOf("Film a", "Film b", "Film c", "Film d"), names("kids"))
        val state = lists.getState(S1, U1, "kids")!!
        assertEquals(5, state.total)
        assertEquals(4, state.loadedCount)
        assertEquals(t0.plusSeconds(1).toEpochMilli(), state.refreshedAt)
    }

    @Test fun rewritingAPageDeletesEverythingFromItsStartOn() = runBlocking {
        library.writePage(s1u1, "kids", 0, films("a", "b", "c", "d"), 4, t0, requestedAtSeq = 0)
        library.writePage(s1u1, "kids", 2, films("x"), 3, t0.plusSeconds(1), requestedAtSeq = 0)
        assertEquals(listOf("Film a", "Film b", "Film x"), names("kids"))
        assertEquals(3, lists.countEntries(S1, U1, "kids"))
        assertEquals(3, lists.getState(S1, U1, "kids")!!.loadedCount)
    }

    @Test fun aRefreshFromZeroReplacesTheWholeList() = runBlocking {
        library.writePage(s1u1, "kids", 0, films("a", "b", "c"), 3, t0, requestedAtSeq = 0)
        library.writePage(s1u1, "kids", 0, films("z"), 1, t0.plusSeconds(1), requestedAtSeq = 0)
        assertEquals(listOf("Film z"), names("kids"))
        library.writePage(s1u1, "kids", 0, emptyList(), 0, t0.plusSeconds(2), requestedAtSeq = 0)
        assertEquals(emptyList<String>(), names("kids"))
        assertEquals(0, lists.getState(S1, U1, "kids")!!.loadedCount)
    }

    @Test fun anotherListIsNotAffectedByAPageWrite() = runBlocking {
        library.writePage(s1u1, "one", 0, films("a"), 1, t0, requestedAtSeq = 0)
        library.writePage(s1u1, "two", 0, films("b"), 1, t0, requestedAtSeq = 0)
        library.writePage(s1u1, "one", 0, films("c"), 1, t0, requestedAtSeq = 0)
        assertEquals(listOf("Film b"), names("two"))
    }

    @Test fun aTotalSmallerThanWhatIsLoadedIsRaisedToIt() = runBlocking {
        library.writePage(s1u1, "kids", 0, films("a", "b"), 1, t0, requestedAtSeq = 0)
        assertEquals(2, lists.getState(S1, U1, "kids")!!.total)
    }

    @Test fun aPageThatWouldLeaveAGapIsReportedStaleAndWritesNothing() = runBlocking {
        library.writePage(s1u1, "kids", 0, films("a", "b"), 9, t0, requestedAtSeq = 0)
        assertEquals(PageResult.Stale, library.writePage(s1u1, "kids", 5, films("c"), 9, t0.plusSeconds(1), requestedAtSeq = 0))
        assertEquals(listOf("Film a", "Film b"), names("kids"))
        assertEquals("the state is not rewritten either", t0.toEpochMilli(), lists.getState(S1, U1, "kids")!!.refreshedAt)
        assertEquals(0, count("items", "itemId = '${id("c")}'"))
        assertEquals(PageResult.Stale, library.writePage(s1u1, "empty", 1, films("c"), 9, t0, requestedAtSeq = 0))
        assertEquals(PageResult.Written, library.writePage(s1u1, "kids", 2, films("c"), 9, t0, requestedAtSeq = 0))
    }

    @Test fun aPageBasedOnAnOutdatedStateIsStaleEvenIfItsStartStillFits() = runBlocking {
        library.writePage(s1u1, "kids", 0, films("a", "b"), 9, t0, requestedAtSeq = 0)
        val before = lists.getState(S1, U1, "kids")!!
        library.writePage(s1u1, "kids", 0, films("x", "y", "z"), 9, t0.plusSeconds(1), requestedAtSeq = 0) // a refresh replaced the list meanwhile
        assertEquals(PageResult.Stale, library.writePage(s1u1, "kids", 2, films("c"), 9, t0.plusSeconds(2), basedOn = before, requestedAtSeq = 0))
        assertEquals(listOf("Film x", "Film y", "Film z"), names("kids"))
        val current = lists.getState(S1, U1, "kids")!!
        assertEquals(PageResult.Written, library.writePage(s1u1, "kids", 3, films("c"), 9, t0.plusSeconds(2), basedOn = current, requestedAtSeq = 0))
    }

    @Test fun aNegativeStartIsStillAProgrammingError() = runBlocking {
        assertTrue(runCatching { library.writePage(s1u1, "kids", -1, films("c"), 9, t0, requestedAtSeq = 0) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun theContiguityGuardChecksPositionsNotJustTheCount() = runBlocking {
        library.writePage(s1u1, "kids", 0, films("a", "b", "c"), 9, t0, requestedAtSeq = 0)
        sql("UPDATE list_entries SET position = 5 WHERE listKey = 'kids' AND position = 2") // a gap: 0, 1, 5 (three rows)
        assertEquals(PageResult.Written, library.writePage(s1u1, "kids", 3, films("d"), 9, t0.plusSeconds(1), requestedAtSeq = 0))
        assertEquals("the live row after the gap survives", listOf("Film a", "Film b", "Film c", "Film d"), names("kids"))
        assertEquals(listOf(0, 1, 2, 3), positions("kids"))
    }

    // ---- removing and re-inserting one entry

    private fun positions(listKey: String, userId: String = U1): List<Int> =
        db.openHelper.readableDatabase.query("SELECT position FROM list_entries WHERE serverId = '$S1' AND userId = '$userId' AND listKey = '$listKey' ORDER BY position").use { c ->
            buildList { while (c.moveToNext()) add(c.getInt(0)) }
        }

    private suspend fun seed(labels: List<String>, total: Int? = 10, next: Int = 7) {
        library.writePage(s1u1, "kids", 0, labels.map { row(item(it)) }, total, t0, nextStartIndex = next, pagesLoaded = 2, endReached = false, requestedAtSeq = 0)
    }

    @Test fun removingFromTheHeadMiddleAndTailKeepsPositionsContiguous() = runBlocking {
        seed(listOf("a", "b", "c", "d", "e"))
        assertTrue(library.removeEntry(s1u1, "kids", id("a")))
        assertEquals(listOf("Film b", "Film c", "Film d", "Film e"), names("kids"))
        assertTrue(library.removeEntry(s1u1, "kids", id("d")))
        assertEquals(listOf("Film b", "Film c", "Film e"), names("kids"))
        assertTrue(library.removeEntry(s1u1, "kids", id("e")))
        assertEquals(listOf("Film b", "Film c"), names("kids"))
        assertEquals(listOf(0, 1), positions("kids"))
    }

    @Test fun removingAnAbsentItemChangesNothing() = runBlocking {
        seed(listOf("a", "b"))
        assertFalse(library.removeEntry(s1u1, "kids", id("zzz")))
        assertFalse(library.removeEntry(s1u1, "nolist", id("a")))
        assertEquals(listOf("Film a", "Film b"), names("kids"))
        assertEquals(2, lists.getState(S1, U1, "kids")!!.loadedCount)
    }

    @Test fun anItemInTheListTwiceIsRemovedEverywhere() = runBlocking {
        seed(listOf("a", "b", "a", "c", "a"))
        assertTrue(library.removeEntry(s1u1, "kids", id("a")))
        assertEquals(listOf("Film b", "Film c"), names("kids"))
        assertEquals(listOf(0, 1), positions("kids"))
        val state = lists.getState(S1, U1, "kids")!!
        assertEquals(2, state.loadedCount)
        assertEquals(7, state.total)
    }

    @Test fun removingUpdatesTheCountsButNeverTheServerIndex() = runBlocking {
        seed(listOf("a", "b", "c"), total = 10, next = 7)
        library.removeEntry(s1u1, "kids", id("b"))
        val state = lists.getState(S1, U1, "kids")!!
        assertEquals(listOf(2, 9, 7, 2, false), listOf(state.loadedCount, state.total, state.nextStartIndex, state.pagesLoaded, state.endReached))
    }

    @Test fun theTotalNeverDropsBelowWhatIsLoadedAndMayStayUnknown() = runBlocking {
        seed(listOf("a", "b", "c"), total = 3)
        library.removeEntry(s1u1, "kids", id("a"))
        assertEquals(2, lists.getState(S1, U1, "kids")!!.total)
        seed(listOf("a", "b", "c"), total = null)
        library.removeEntry(s1u1, "kids", id("a"))
        assertNull(lists.getState(S1, U1, "kids")!!.total)
    }

    @Test fun removingIsPerUserAndPerList() = runBlocking {
        seed(listOf("a", "b"))
        library.writePage(s1u2, "kids", 0, films("a", "b"), 2, t0, requestedAtSeq = 0)
        library.writePage(s1u1, "other", 0, films("a"), 1, t0, requestedAtSeq = 0)
        library.removeEntry(s1u1, "kids", id("a"))
        assertEquals(listOf("Film a", "Film b"), names("kids", s1u2))
        assertEquals(listOf("Film a"), names("other"))
        assertEquals(2, lists.getState(S1, U2, "kids")!!.loadedCount)
    }

    @Test fun insertingBackRestoresExactlyThePreviousList() = runBlocking {
        val labels = listOf("a", "b", "c", "d")
        for (index in labels.indices) {
            seed(labels)
            val before = lists.entryIds(S1, U1, "kids")
            assertTrue(library.removeEntry(s1u1, "kids", id(labels[index])))
            assertTrue(library.insertEntryAt(s1u1, "kids", index, id(labels[index])))
            assertEquals("restored at $index", before, lists.entryIds(S1, U1, "kids"))
            assertEquals(listOf(0, 1, 2, 3), positions("kids"))
            assertEquals(4, lists.getState(S1, U1, "kids")!!.loadedCount)
        }
    }

    @Test fun insertingAtTheEndAndOutOfRange() = runBlocking {
        seed(listOf("a", "b"), total = 5)
        assertTrue(library.insertEntryAt(s1u1, "kids", 2, id("c")))
        assertEquals(listOf("a", "b", "c").map(::id), lists.entryIds(S1, U1, "kids"))
        assertEquals(6, lists.getState(S1, U1, "kids")!!.total)
        assertEquals(7, lists.getState(S1, U1, "kids")!!.nextStartIndex)
        assertFalse(library.insertEntryAt(s1u1, "kids", 9, id("d")))
        assertFalse(library.insertEntryAt(s1u1, "kids", -1, id("d")))
        assertFalse(library.insertEntryAt(s1u1, "nolist", 0, id("d")))
        assertEquals(3, lists.countEntries(S1, U1, "kids"))
    }

    @Test fun listKeysMustBeOneToTwoHundredCharacters() = runBlocking {
        assertTrue(runCatching { library.writePage(s1u1, "", 0, films("a"), 1, t0, requestedAtSeq = 0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { library.writePage(s1u1, "k".repeat(201), 0, films("a"), 1, t0, requestedAtSeq = 0) }.exceptionOrNull() is IllegalArgumentException)
        library.writePage(s1u1, "k".repeat(200), 0, films("a"), 1, t0, requestedAtSeq = 0)
        assertEquals(1, names("k".repeat(200)).size)
    }

    @Test fun aPageIsOneTransactionSoAFailureLeavesNothingBehind() = runBlocking {
        val stranger = Session(S1, id("nobody")) // no such account: its user data and entries break the foreign key
        val failure = runCatching { library.writePage(stranger, "resume", 0, listOf(row(item("a"), watched(played = true))), 1, t0, requestedAtSeq = 0) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("the item upsert was rolled back too", 0, count("items"))
        assertEquals(0, count("user_data"))
        assertEquals(0, count("list_entries"))
        assertEquals(0, count("list_state"))
    }

    @Test fun aPageWithRepeatedItemsKeepsEveryPosition() = runBlocking {
        library.writePage(s1u1, "kids", 0, films("a", "a", "b"), 3, t0, requestedAtSeq = 0)
        assertEquals(listOf("Film a", "Film a", "Film b"), names("kids"))
    }

    @Test fun listFlowsReactToWritesToItsTables() = runBlocking {
        library.writePage(s1u1, "resume", 0, films("a"), 1, t0, requestedAtSeq = 0)
        lists.observeList(S1, U1, "resume", 10).test {
            assertEquals(listOf("Film a"), awaitItem().map { it.item.name })
            library.writePage(s1u1, "resume", 0, films("b", "a"), 2, t0.plusSeconds(1), requestedAtSeq = 0)
            assertEquals(listOf("Film b", "Film a"), awaitItem().map { it.item.name })
            db.itemDao().setFavourite(S1, U1, id("a"), true, t0.toEpochMilli())
            assertTrue(awaitItem().last().userData!!.favourite)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun stateFlowReportsRefreshes() = runBlocking {
        lists.observeState(S1, U1, "resume").test {
            assertNull(awaitItem())
            library.writePage(s1u1, "resume", 0, films("a"), 7, t0, requestedAtSeq = 0)
            assertEquals(7, awaitItem()!!.total)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- views

    private fun view(label: String, kind: LibraryKind = LibraryKind.MOVIES) = LibraryView(id(label), "Lib $label", kind)

    private suspend fun viewNames(userId: String = U1) = db.viewDao().getViews(S1, userId).map { it.name }

    @Test fun viewsKeepTheServersOrderAndAreReplacedAsAWhole() = runBlocking {
        library.replaceViews(s1u1, listOf(view("b"), view("a", LibraryKind.TV), view("c", LibraryKind.BOX_SETS)))
        assertEquals(listOf("Lib b", "Lib a", "Lib c"), viewNames())
        assertEquals(LibraryKind.TV, db.viewDao().getViews(S1, U1)[1].toModel().kind)
        library.replaceViews(s1u1, listOf(view("c", LibraryKind.BOX_SETS), view("d")))
        assertEquals(listOf("Lib c", "Lib d"), viewNames())
        library.replaceViews(s1u1, emptyList())
        assertEquals(emptyList<String>(), viewNames())
    }

    @Test fun viewsArePerUser() = runBlocking {
        library.replaceViews(s1u1, listOf(view("a"), view("b")))
        library.replaceViews(s1u2, listOf(view("a")))
        library.replaceViews(s1u2, listOf(view("c")))
        assertEquals(listOf("Lib a", "Lib b"), viewNames(U1))
        assertEquals(listOf("Lib c"), viewNames(U2))
    }

    @Test fun viewsFlowReactsToReplacement() = runBlocking {
        db.viewDao().observeViews(S1, U1).test {
            assertEquals(emptyList<String>(), awaitItem().map { it.name })
            library.replaceViews(s1u1, listOf(view("a")))
            assertEquals(listOf("Lib a"), awaitItem().map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- paging state (Milestone 2 task 4)

    @Test fun aListReadInOneGoIsMarkedEndReachedWithItsPagingStateFilledIn() = runBlocking {
        library.writePage(s1u1, "resume", 0, films("a", "b"), 2, t0, requestedAtSeq = 0)
        val state = lists.getState(S1, U1, "resume")!!
        assertEquals(listOf(2, 2, 1, true), listOf(state.loadedCount, state.nextStartIndex, state.pagesLoaded, state.endReached))
    }

    @Test fun pagingStateIsStoredAsGivenAndTheTotalMayBeUnknown() = runBlocking {
        library.writePage(s1u1, "kids", 0, films("a", "b"), null, t0, nextStartIndex = 5, pagesLoaded = 1, endReached = false, requestedAtSeq = 0)
        library.writePage(s1u1, "kids", 2, films("c"), null, t0.plusSeconds(1), nextStartIndex = 9, pagesLoaded = 2, endReached = false, requestedAtSeq = 0)
        val state = lists.getState(S1, U1, "kids")!!
        assertNull(state.total)
        assertEquals(listOf(3, 9, 2, false), listOf(state.loadedCount, state.nextStartIndex, state.pagesLoaded, state.endReached))
        assertEquals(listOf("a", "b", "c").map(::id), lists.entryIds(S1, U1, "kids"))
    }
}
