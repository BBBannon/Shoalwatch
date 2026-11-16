package app.shoalwatch.data.db

import app.cash.turbine.test
import app.shoalwatch.S1
import app.shoalwatch.S2
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.data.db.library.toModel
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Items, the upsert rules, user data and offline search. */
class LibraryItemTest : LibraryTestBase() {
    private val items get() = db.itemDao()

    @Test fun anItemRoundTripsThroughTheDatabaseAsTheModel() = runBlocking {
        val film = item("a", overview = "Plot", childCount = 3, sortName = "A, Film")
        library.writeItems(s1u1, listOf(row(film, watched(played = true, favourite = true))), t0, requestedAtSeq = 0)
        val back = items.getItem(S1, U1, film.id)!!.toModel()
        assertEquals(film, back.item)
        assertEquals(watched(played = true, favourite = true), back.userData)
    }

    @Test fun episodesComeInSeasonThenEpisodeOrderWithMissingNumbersLast() = runBlocking {
        val episodes = listOf(
            episode("s2e1", 2, 1), episode("none", null, null), episode("s1e2", 1, 2), episode("s1x", 1, null),
            episode("s1e1", 1, 1), episode("noseason-e1", null, 1), episode("s2e0", 2, 0),
        )
        val other = episode("other-series", 1, 1, series = "other")
        val season = item("season1", ItemKind.SEASON, seriesId = "show", indexNumber = 1)
        library.writeItems(s1u1, (episodes + other + season).shuffled().map { row(it) }, t0, requestedAtSeq = 0)
        val order = items.getEpisodes(S1, U1, id("show"), null).map { it.item.name }
        assertEquals(listOf("s1e1", "s1e2", "s1x", "s2e0", "s2e1", "noseason-e1", "none").map { "Episode $it" }, order)
        val seasonOne = items.getEpisodes(S1, U1, id("show"), id("season1")).map { it.item.name }
        assertEquals(listOf("s1e1", "s1e2", "s1x").map { "Episode $it" }, seasonOne)
    }

    @Test fun twoUsersShareItemRowsButKeepTheirOwnUserData() = runBlocking {
        val film = item("a")
        library.writeItems(s1u1, listOf(row(film, watched(played = true))), t0, requestedAtSeq = 0)
        library.writeItems(s1u2, listOf(row(film, watched(positionMs = 5_000, favourite = true))), t0, requestedAtSeq = 0)
        assertEquals(1, items.countItems(S1))
        val ann = items.getItem(S1, U1, film.id)!!
        val bob = items.getItem(S1, U2, film.id)!!
        assertTrue(ann.userData!!.played)
        assertEquals(5_000L, bob.userData!!.positionMs)
        assertFalse(bob.userData!!.played)
        assertTrue(bob.userData!!.favourite)
        assertFalse(ann.userData!!.favourite)
    }

    @Test fun theSameItemIdOnTwoServersIsTwoRows() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("a", name = "On one"))), t0, requestedAtSeq = 0)
        library.writeItems(s2u1, listOf(row(item("a", name = "On two"))), t0, requestedAtSeq = 0)
        assertEquals("On one", items.getItem(S1, U1, id("a"))!!.item.name)
        assertEquals("On two", items.getItem(S2, U1, id("a"))!!.item.name)
    }

    @Test fun anItemWithNoUserDataForTheUserHasNull() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("a"), watched(played = true))), t0, requestedAtSeq = 0)
        library.writeItems(s1u2, listOf(row(item("a"))), t0, requestedAtSeq = 0) // the server showed Bob the item, with nothing of his own
        assertNull(items.getItem(S1, U2, id("a"))!!.userData)
    }

    // ---- the upsert rules

    @Test fun aListResponseDoesNotEraseAStoredOverviewButChildCountIsTakenAsSent() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("a", overview = "Long plot", childCount = 4))), t0, requestedAtSeq = 0)
        library.writeItems(s1u1, listOf(row(item("a", name = "Renamed", overview = null, childCount = null))), t0.plusSeconds(60), requestedAtSeq = 0)
        val stored = items.getItemEntity(S1, id("a"))!!
        assertEquals("Long plot", stored.overview)
        assertNull("a count from another user's write must never be shown: null means unknown", stored.childCount)
        assertEquals("every other column takes the new value", "Renamed", stored.name)
        assertEquals(t0.plusSeconds(60).toEpochMilli(), stored.fetchedAt)
    }

    @Test fun aNewOverviewReplacesTheOldOne() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("a", overview = "Old", childCount = 1))), t0, requestedAtSeq = 0)
        library.writeItems(s1u1, listOf(row(item("a", overview = "New", childCount = 2))), t0, requestedAtSeq = 0)
        val stored = items.getItemEntity(S1, id("a"))!!
        assertEquals("New", stored.overview)
        assertEquals(2, stored.childCount)
    }

    @Test fun otherColumnsTakeTheIncomingValueEvenWhenNull() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("a", primaryTag = "tag1", sortName = "A"))), t0, requestedAtSeq = 0)
        library.writeItems(s1u1, listOf(row(item("a", primaryTag = null, sortName = null))), t0, requestedAtSeq = 0)
        val stored = items.getItemEntity(S1, id("a"))!!
        assertNull(stored.primaryTag)
        assertNull(stored.sortName)
    }

    @Test fun anUpsertNeverClobbersPinned() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("a"))), t0, requestedAtSeq = 0)
        sql("UPDATE items SET pinned = 1 WHERE serverId = ? AND itemId = ?", S1, id("a"))
        library.writeItems(s1u1, listOf(row(item("a", name = "Again"))), t0.plusSeconds(1), requestedAtSeq = 0)
        val stored = items.getItemEntity(S1, id("a"))!!
        assertTrue(stored.pinned)
        assertEquals("Again", stored.name)
    }

    @Test fun anItemWithoutUserDataLeavesStoredUserDataUntouched() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("a"), watched(played = true, positionMs = 0, favourite = true))), t0, requestedAtSeq = 0)
        library.writeItems(s1u1, listOf(row(item("a", name = "Again"), null)), t0.plusSeconds(60), requestedAtSeq = 0)
        val data = items.getUserData(S1, U1, id("a"))!!
        assertTrue(data.played)
        assertTrue(data.favourite)
        assertEquals("not rewritten either", t0.toEpochMilli(), data.updatedAt)
    }

    @Test fun userDataFromTheServerOverwritesTheStoredRow() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("a"), watched(positionMs = 10)), row(item("b"), watched(favourite = true))), t0, requestedAtSeq = 0)
        library.writeItems(s1u1, listOf(row(item("a"), watched(played = true))), t0.plusSeconds(5), requestedAtSeq = 0)
        val data = items.getUserData(S1, U1, id("a"))!!
        assertTrue(data.played)
        assertEquals(0L, data.positionMs)
        assertEquals(t0.plusSeconds(5).toEpochMilli(), data.updatedAt)
        assertTrue(items.getUserData(S1, U1, id("b"))!!.favourite)
    }

    @Test fun aBatchOfItemsForAnotherServerIsRefusedWhole() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("a"))), t0, requestedAtSeq = 0)
        val stored = items.getItemEntity(S1, id("a"))!!
        val failure = runCatching { items.upsertItems(S2, listOf(stored)) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertNull(items.getItemEntity(S2, id("a")))
    }

    @Test fun aLargeBatchIsMergedAcrossIdChunks() = runBlocking {
        val many = (1..1_200).map { row(item("m$it", overview = "plot $it")) }
        library.writeItems(s1u1, many, t0, requestedAtSeq = 0)
        library.writeItems(s1u1, many.map { row(it.item.copy(overview = null, name = "N")) }, t0.plusSeconds(1), requestedAtSeq = 0)
        assertEquals(1_200, count("items", "overview IS NOT NULL AND name = 'N'"))
    }

    @Test fun anAuthoritativeWriteLetsOverviewAndChildCountBeCleared() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("a", overview = "Plot", childCount = 4))), t0, requestedAtSeq = 0)
        sql("UPDATE items SET pinned = 1 WHERE itemId = ?", id("a"))
        library.writeItems(s1u1, listOf(row(item("a", name = "Detail", overview = null, childCount = null))), t0.plusSeconds(1), authoritative = true, requestedAtSeq = 0)
        val stored = items.getItemEntity(S1, id("a"))!!
        assertNull(stored.overview)
        assertNull(stored.childCount)
        assertEquals("Detail", stored.name)
        assertTrue("pinned is still never touched", stored.pinned)
        library.writeItems(s1u1, listOf(row(item("a", overview = "Again"))), t0.plusSeconds(2), authoritative = true, requestedAtSeq = 0)
        library.writeItems(s1u1, listOf(row(item("a", overview = null))), t0.plusSeconds(3), requestedAtSeq = 0) // a list write keeps it
        assertEquals("Again", items.getItemEntity(S1, id("a"))!!.overview)
    }

    // ---- targeted user-data updates

    @Test fun setPlayedCreatesTheRowAndKeepsWhatItShouldKeep() = runBlocking {
        val now = t0.toEpochMilli()
        items.setPlayed(S1, U1, id("a"), true, 0, now)
        val created = items.getUserData(S1, U1, id("a"))!!
        assertTrue(created.played)
        assertEquals(1, created.playCount)
        assertEquals(now, created.lastPlayed)
        assertFalse(created.favourite)
        items.setFavourite(S1, U1, id("a"), true, now + 1)
        items.setPlayed(S1, U1, id("a"), false, 2_000, now + 2)
        val undone = items.getUserData(S1, U1, id("a"))!!
        assertFalse(undone.played)
        assertEquals(2_000L, undone.positionMs)
        assertEquals(0, undone.playCount)
        assertTrue("favourite is not touched by played", undone.favourite)
        assertEquals(now + 2, undone.updatedAt)
    }

    @Test fun setFavouriteAndSetPositionCreateRowsWithDefaults() = runBlocking {
        val now = t0.toEpochMilli()
        items.setFavourite(S1, U1, id("a"), true, now)
        items.setPosition(S1, U1, id("b"), 90_000, now)
        val fav = items.getUserData(S1, U1, id("a"))!!
        assertTrue(fav.favourite)
        assertFalse(fav.played)
        assertEquals(0L, fav.positionMs)
        assertEquals(now, fav.updatedAt)
        val pos = items.getUserData(S1, U1, id("b"))!!
        assertEquals(90_000L, pos.positionMs)
        assertFalse(pos.played)
        assertFalse(pos.favourite)
        assertEquals(now, pos.lastPlayed)
        items.setFavourite(S1, U1, id("a"), false, now + 5)
        assertFalse(items.getUserData(S1, U1, id("a"))!!.favourite)
        items.setPosition(S1, U1, id("b"), 0, now + 6)
        assertEquals(0L, items.getUserData(S1, U1, id("b"))!!.positionMs)
        assertEquals("clearing the position keeps the last-played time", now, items.getUserData(S1, U1, id("b"))!!.lastPlayed)
    }

    @Test fun userDataUpdatesAreForOneUserOnly() = runBlocking {
        items.setPlayed(S1, U1, id("a"), true, 0, t0.toEpochMilli())
        assertNull(items.getUserData(S1, U2, id("a")))
        assertNull(items.getUserData(S2, U1, id("a")))
    }

    @Test fun anUpdateForAnAccountThatDoesNotExistFails() {
        val failure = runCatching { runBlocking { items.setPlayed(S2, U2, id("a"), true, 0, 1) } }.exceptionOrNull()
        assertNotNull("the foreign key to accounts refuses it", failure)
        assertEquals(0, count("user_data"))
    }

    @Test fun observingAnItemSeesLaterUserDataChanges() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("a"))), t0, requestedAtSeq = 0)
        items.observeItem(S1, U1, id("a")).test {
            assertNull(awaitItem()!!.userData)
            items.setFavourite(S1, U1, id("a"), true, t0.toEpochMilli())
            assertTrue(awaitItem()!!.userData!!.favourite)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- kinds

    @Test fun anUnknownStoredKindReadsAsOther() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("a"))), t0, requestedAtSeq = 0)
        sql("UPDATE items SET kind = 'HOLOGRAM' WHERE itemId = ?", id("a"))
        assertEquals(ItemKind.OTHER, items.getItem(S1, U1, id("a"))!!.item.kind)
    }

    // ---- search

    private suspend fun search(query: String, kinds: Set<ItemKind> = emptySet(), limit: Int = 50, session: Session = s1u1) =
        items.searchByName(session.serverId, session.userId, query, kinds, limit).map { it.item.name }

    @Test fun searchIsCaseInsensitiveIncludingNonAsciiLetters() = runBlocking {
        library.writeItems(s1u1, listOf("Alien", "Élite Squad", "Zoë", "Straße").map { row(item(it, name = it)) }, t0, requestedAtSeq = 0)
        assertEquals(listOf("Alien"), search("ALIEN"))
        assertEquals(listOf("Élite Squad"), search("élite"))
        assertEquals(listOf("Élite Squad"), search("ÉLITE"))
        assertEquals(listOf("Zoë"), search("ZOË"))
    }

    @Test fun searchFoldsAccentsAndTheTurkishIs() = runBlocking {
        library.writeItems(s1u1, listOf("Élite", "İstanbul", "Ångström", "Isparta", "Plain").map { row(item(it, name = it)) }, t0, requestedAtSeq = 0)
        assertEquals(listOf("Élite"), search("elite"))
        assertEquals(listOf("İstanbul"), search("istanbul"))
        assertEquals(listOf("İstanbul"), search("İSTANBUL"))
        assertEquals(listOf("Ångström"), search("ANGSTROM"))
        assertEquals(listOf("Ångström"), search("ångström"))
        assertEquals(listOf("Isparta"), search("ısparta"))
        assertEquals(listOf("İstanbul", "Isparta").sorted(), search("is").sorted())
    }

    @Test fun anAllKindsSearchIncludesRowsWhoseStoredKindIsUnknown() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("a", name = "Mystery one")), row(item("b", name = "Mystery two"))), t0, requestedAtSeq = 0)
        sql("UPDATE items SET kind = 'HOLOGRAM' WHERE itemId = ?", id("a"))
        assertEquals(listOf("Mystery one", "Mystery two"), search("mystery"))
        assertEquals("a search for named kinds still filters", listOf("Mystery two"), search("mystery", setOf(ItemKind.MOVIE)))
    }

    @Test fun searchMatchesInsideTheNameAndOrdersByName() = runBlocking {
        library.writeItems(s1u1, listOf("The Lord", "Lord of Flies", "Other").map { row(item(it, name = it)) }, t0, requestedAtSeq = 0)
        assertEquals(listOf("Lord of Flies", "The Lord"), search("lord"))
    }

    @Test fun searchTreatsPercentUnderscoreAndBackslashLiterally() = runBlocking {
        val names = listOf("100% Wolf", "100 Wolf", "a_b", "axb", "back\\slash", "backslash", "50%")
        library.writeItems(s1u1, names.map { row(item(it, name = it)) }, t0, requestedAtSeq = 0)
        assertEquals(listOf("100% Wolf"), search("100%"))
        assertEquals(listOf("a_b"), search("a_b"))
        assertEquals(listOf("back\\slash"), search("back\\s"))
        assertEquals(listOf("100% Wolf", "50%"), search("%"))
        assertEquals(listOf("back\\slash"), search("\\"))
        assertEquals(listOf("a_b"), search("_"))
    }

    @Test fun searchHonoursKindsLimitAndBlankQueries() = runBlocking {
        library.writeItems(
            s1u1,
            listOf(
                row(item("m1", name = "Star one")), row(item("m2", name = "Star two")),
                row(item("sr", ItemKind.SERIES, name = "Star series")), row(item("ep", ItemKind.EPISODE, name = "Star episode")),
            ),
            t0, requestedAtSeq = 0,
        )
        assertEquals(setOf("Star series"), search("star", setOf(ItemKind.SERIES)).toSet())
        assertEquals(setOf("Star one", "Star two", "Star series"), search("star", setOf(ItemKind.MOVIE, ItemKind.SERIES)).toSet())
        assertEquals(4, search("star").size)
        assertEquals(2, search("star", limit = 2).size)
        assertEquals(emptyList<String>(), search("   "))
        assertEquals(emptyList<String>(), search(""))
        assertEquals(emptyList<String>(), search("star", limit = 0))
    }

    @Test fun searchIsPerServerAndJoinsTheGivenUsersData() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("a", name = "Match"), watched(played = true))), t0, requestedAtSeq = 0)
        library.writeItems(s1u2, listOf(row(item("a", name = "Match"))), t0, requestedAtSeq = 0)
        library.writeItems(s2u1, listOf(row(item("b", name = "Match elsewhere"))), t0, requestedAtSeq = 0)
        assertEquals(listOf("Match"), search("match"))
        val ann = items.searchByName(S1, U1, "match", emptySet(), 10).single()
        val bob = items.searchByName(S1, U2, "match", emptySet(), 10).single()
        assertTrue(ann.userData!!.played)
        assertNull(bob.userData)
    }

    @Test fun searchDoesNotConflateJapaneseVoicedAndUnvoicedKanaOrSplitHangul() = runBlocking {
        library.writeItems(s1u1, listOf("かさ", "がさ", "한국어 영화", "ก็ได้", "กด").map { row(item(it, name = it)) }, t0, requestedAtSeq = 0)
        assertEquals(listOf("かさ"), search("か"))
        assertEquals(listOf("がさ"), search("が"))
        assertEquals(listOf("한국어 영화"), search("한국어"))
        assertEquals(listOf("한국어 영화"), search("영화"))
        assertEquals(listOf("ก็ได้"), search("ก็")) // the Thai vowel mark is part of the letter, not an accent to strip
    }
}
