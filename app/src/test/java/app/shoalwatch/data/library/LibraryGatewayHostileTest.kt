package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Untrusted input (SPEC S11): a hostile server must not get an unvalidated id, string, number or image tag past the gateway. */
class LibraryGatewayHostileTest : LibraryGatewayTestBase() {
    private val nameAsc = Sort(SortBy.NAME, descending = false)

    private fun hostilePage(limit: Int = 200): Page<ItemWithUserData> {
        enqueueFixture("hostile_items.json")
        return runBlocking { gateway.children(session, FILMS, 0, limit, nameAsc, ItemFilter()) }
    }

    private fun hostile(suffix: String): ItemWithUserData = hostilePage().items.single { it.item.id.endsWith(suffix) }

    private fun id(suffix: String) = "ff00000000000000000000000000$suffix"

    @Test fun anItemWhoseIdIsNotAGuidIsDroppedAndOthersSurvive() {
        val ids = hostilePage().items.map { it.item.id }
        assertEquals(14, ids.size) // 16 entries: one bad id and one null are gone
        assertFalse("not-a-guid" in ids)
        assertTrue(ids.all { Regex("[0-9a-f]{32}").matches(it) })
    }

    @Test fun dashesAndUpperCaseAreNormalisedAndBadOptionalIdsBecomeNull() {
        val item = hostile("f002").item
        assertEquals(id("f002"), item.id)
        assertNull(item.parentId)
        assertNull(item.seriesId)
        assertEquals(id("f0a1"), item.seasonId)
    }

    @Test fun longStringsAreCapped() {
        val item = hostile("f003").item
        assertEquals("N".repeat(200), item.name)
        assertEquals("S".repeat(200), item.sortName)
        assertEquals("R".repeat(200), item.seriesName)
        assertEquals("X".repeat(20), item.officialRating)
    }

    @Test fun bidiAndZeroWidthCharactersAreRemovedFromNames() {
        assertEquals("EvilMovieNamex", hostile("f004").item.name)
    }

    @Test fun anOverviewKeepsHtmlAndNewlinesButLosesControlAndFormatCharactersAndIsCapped() {
        val overview = hostile("f005").item.overview!!
        assertTrue(overview.startsWith("<script>alert(1)</script><b>Bold</b> line one\nline two hidden text\n\nlast linexxxx"))
        assertEquals(4000, overview.codePointCount(0, overview.length))
        assertTrue(overview.none { it == '\r' || it == '\u0000' || it == '\u0007' || it == '‮' || it == '​' })
    }

    @Test fun negativeNumbersBecomeNullOrZero() {
        val (item, data) = hostile("f006")
        assertNull(item.runtimeMs)
        assertEquals(0L, data!!.positionMs)
        assertEquals(0, data.playCount)
        assertNull(data.unplayedItemCount)
        assertNull(data.playedPercentage)
    }

    @Test fun outOfRangeNumbersAndUnparseableDatesBecomeNull() {
        val item = hostile("f007").item
        assertEquals(ItemKind.EPISODE, item.kind)
        assertNull(item.indexNumber)
        assertNull(item.parentIndexNumber)
        assertNull(item.year)
        assertNull(item.communityRating)
        assertNull(item.childCount)
        assertNull(item.premiereDate)
    }

    @Test fun imageTagsThatAreNotPlainTokensAreNull() {
        val images = hostile("f008").item.images
        assertEquals("ok1234", images.primaryTag)
        assertNull(images.thumbTag)
        assertNull(images.backdropTag)
        assertNull(images.seriesPrimaryTag)
    }

    @Test fun anUnknownTypeIsOtherAndAMissingUserDataIsNull() {
        assertEquals(ItemKind.OTHER, hostile("f009").item.kind)
        assertNull(hostile("f00a").userData)
        assertNull(hostile("f00b").userData) // an explicit null
    }

    @Test fun theListIsCappedAtTheRequestedLimitAndATotalBelowWhatCameBackIsUnknown() {
        val page = hostilePage(limit = 3)
        assertEquals(3, page.items.size)
        assertNull(page.total) // the server said 2 after sending 16 elements: nonsense, so unknown
        assertEquals(16, page.nextStartIndex)
    }

    @Test fun theTotalIsAtMostOneMillion() {
        web.enqueue(json("""{"Items":[{"Id":"${id("f001")}","Name":"x","Type":"Movie"}],"TotalRecordCount":2147483647}"""))
        assertEquals(1_000_000, runBlocking { gateway.children(session, FILMS, 0, 10, nameAsc, ItemFilter()) }.total)
    }

    @Test fun otherListsAreCappedToo() {
        enqueueFixture("hostile_items.json")
        assertEquals(4, runBlocking { gateway.resume(session, 4) }.size)
        enqueueFixture("hostile_items.json")
        assertEquals(2, runBlocking { gateway.search(session, "x", 2) }.size)
    }

    @Test fun aSingleItemWithABadIdFailsAsAServerError() {
        web.enqueue(json("""{"Id":"not-a-guid","Name":"x","Type":"Movie"}"""))
        assertEquals(AppError.Kind.SERVER_ERROR, failure { gateway.item(session, id("f001")) }.kind)
    }

    @Test fun viewsWithHostileNamesAreCleaned() {
        web.enqueue(json("""{"Items":[{"Id":"${id("f001")}","Name":"Fi‮lms​","CollectionType":"movies"},{"Id":"${id("f002")}","Name":"​","CollectionType":"tvshows"}]}"""))
        val views = runBlocking { gateway.views(session) }
        assertEquals("Films", views[0].name)
        assertEquals("", views[1].name) // nothing visible left: the UI supplies a placeholder
    }
}
