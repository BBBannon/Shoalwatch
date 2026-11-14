package app.shoalwatch.data.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One bad element must not cost the page; duplicates are dropped; related fields cannot point at themselves. */
class LibraryGatewayRobustnessTest : LibraryGatewayTestBase() {
    private val nameAsc = Sort(SortBy.NAME, descending = false)

    private fun id(n: String) = "bb0000000000000000000000000000$n"

    private fun row(n: String, extra: String = "", name: String = "Row $n") = """{"Id":"${id(n)}","Name":"$name","Type":"Movie"$extra}"""

    private fun children(body: String): Page<ItemWithUserData> {
        web.enqueue(json(body))
        return runBlocking { gateway.children(session, FILMS, 0, 50, nameAsc, ItemFilter()) }
    }

    // ---- I1: element-level tolerance

    @Test fun elementsOfTheWrongTypeAreSkippedAndTheRestOfThePageSurvivesInOrder() {
        enqueueFixture("hostile_types.json")
        val page = runBlocking { gateway.children(session, FILMS, 0, 50, nameAsc, ItemFilter()) }
        assertEquals(
            listOf("01", "02", "a2", "03", "04", "05", "06", "07", "08", "09", "0a", "0b", "0d").map(::id),
            page.items.map { it.item.id },
        )
        // A number sent as a quoted string ("2020") is accepted by the JSON decoder and still range-checked.
        assertEquals(2020, page.items.single { it.item.id == id("a2") }.item.year)
        assertEquals(13, page.total) // "many" is not a number: the count falls back to what came back
    }

    @Test fun aUserDataThatIsNotUsableKeepsTheItemWithoutIt() {
        enqueueFixture("hostile_types.json")
        val items = runBlocking { gateway.children(session, FILMS, 0, 50, nameAsc, ItemFilter()) }.items.associateBy { it.item.id }
        assertNull(items.getValue(id("0a")).userData) // a string
        assertNull(items.getValue(id("0b")).userData) // wrong type inside
    }

    @Test fun theBareArrayOfLatestToleratesBadElementsToo() {
        web.enqueue(json("""[${row("01")}, {"Id":"${id("a1")}","IndexNumber":1.5}, "x", null, 7, ${row("02")}]"""))
        val items = runBlocking { gateway.latest(session, FILMS, 10) }
        assertEquals(listOf(id("01"), id("02")), items.map { it.item.id })
    }

    @Test fun viewsToleratesBadElementsToo() {
        web.enqueue(json("""{"Items":[{"Id":"${id("01")}","Name":"Films","CollectionType":"movies"},{"Id":5,"Name":"x"},"y",{"Id":"${id("02")}","Name":["no"]},{"Id":"${id("03")}","Name":"TV","CollectionType":"tvshows"}]}"""))
        assertEquals(listOf(id("01"), id("03")), runBlocking { gateway.views(session) }.map { it.id })
    }

    @Test fun aSingleItemOfTheWrongTypeStillFailsAsAServerError() {
        web.enqueue(json("""{"Id":"${id("01")}","IndexNumber":1.5}"""))
        assertEquals(app.shoalwatch.core.AppError.Kind.SERVER_ERROR, failure { gateway.item(session, id("01")) }.kind)
    }

    // ---- I2: duplicates

    @Test fun theFirstOccurrenceOfAnIdWinsInEveryList() {
        val dashed = "BB000000-0000-0000-0000-000000000001"
        val body = """{"Items":[{"Id":"$dashed","Name":"First","Type":"Movie"},${row("02")},{"Id":"${id("01")}","Name":"Second","Type":"Movie"}]}"""
        val page = children(body)
        assertEquals(listOf("First", "Row 02"), page.items.map { it.item.name })
        assertEquals(listOf(id("01"), id("02")), page.items.map { it.item.id })
        web.enqueue(json("[${row("03", name = "A")},${row("03", name = "B")}]"))
        assertEquals(listOf("A"), runBlocking { gateway.latest(session, FILMS, 10) }.map { it.item.name })
        web.enqueue(json(body))
        assertEquals(listOf("First", "Row 02"), runBlocking { gateway.resume(session, 10) }.map { it.item.name })
        web.enqueue(json("""{"Items":[{"Id":"$dashed","Name":"One","CollectionType":"movies"},{"Id":"${id("01")}","Name":"Two","CollectionType":"tvshows"}]}"""))
        assertEquals(listOf("One"), runBlocking { gateway.views(session) }.map { it.name })
    }

    @Test fun aDuplicateDoesNotUseUpTheLimit() {
        web.enqueue(json("""{"Items":[${row("01")},${row("01")},${row("02")}]}"""))
        val items = runBlocking { gateway.search(session, "x", 2) }
        assertEquals(listOf(id("01"), id("02")), items.map { it.item.id })
    }

    // ---- M3, M4, M7

    @Test fun aWatchedItemHasNoResumePosition() {
        val data = children("""{"Items":[${row("01", ""","UserData":{"Played":true,"PlaybackPositionTicks":50000000000,"PlayedPercentage":50.0,"PlayCount":1}""")}]}""").items[0].userData!!
        assertEquals(true, data.played)
        assertEquals(0L, data.positionMs)
        assertNull(data.playedPercentage)
        val unwatched = children("""{"Items":[${row("02", ""","UserData":{"Played":false,"PlaybackPositionTicks":50000000000,"PlayedPercentage":50.0}""")}]}""").items[0].userData!!
        assertEquals(5_000_000L, unwatched.positionMs)
        assertEquals(50.0f, unwatched.playedPercentage)
    }

    @Test fun anItemCannotBeItsOwnParentSeriesOrSeason() {
        val item = children("""{"Items":[${row("01", ""","ParentId":"${id("01")}","SeriesId":"${id("01").uppercase()}","SeasonId":"${id("01")}"""")}]}""").items[0].item
        assertNull(item.parentId)
        assertNull(item.seriesId)
        assertNull(item.seasonId)
        val other = children("""{"Items":[${row("02", ""","ParentId":"${id("09")}","SeriesId":"${id("08")}","SeasonId":"${id("07")}"""")}]}""").items[0].item
        assertEquals(listOf(id("09"), id("08"), id("07")), listOf(other.parentId, other.seriesId, other.seasonId))
    }

    @Test fun aNameOfOnlyInvisibleCharactersIsEmptyForItemsAndViews() {
        val item = children("""{"Items":[{"Id":"${id("01")}","Name":"​‮ ⁦","Type":"Movie"}]}""").items[0].item
        assertEquals("", item.name)
        web.enqueue(json("""{"Items":[{"Id":"${id("02")}","Name":"​","CollectionType":"movies"}]}"""))
        assertEquals("", runBlocking { gateway.views(session) }[0].name)
        val missing = children("""{"Items":[{"Id":"${id("03")}","Type":"Movie"}]}""").items[0].item
        assertEquals("", missing.name)
    }

    // ---- M9: caps and a hostile bare array

    @Test fun latestAndNextUpAreCappedAtTheRequestedLimit() {
        enqueueFixture("hostile_items.json")
        assertEquals(3, runBlocking { gateway.nextUp(session, 3) }.size)
        val many = (1..9).joinToString(",") { row("0$it") }
        web.enqueue(json("[$many]"))
        assertEquals(4, runBlocking { gateway.latest(session, FILMS, 4) }.size)
        web.enqueue(json("""{"Items":[$many]}"""))
        assertEquals(5, runBlocking { gateway.resume(session, 5) }.size)
    }

    @Test fun aHostileLatestArrayIsCleanedLikeAnyList() {
        val fixture = fixture("hostile_items.json")
        val array = fixture.substring(fixture.indexOf('['), fixture.lastIndexOf(']') + 1)
        web.enqueue(json(array))
        val items = runBlocking { gateway.latest(session, FILMS, 200) }
        assertEquals(14, items.size)
        assertEquals("N".repeat(200), items.single { it.item.id.endsWith("f003") }.item.name)
        assertEquals(ItemKind.OTHER, items.single { it.item.id.endsWith("f009") }.item.kind)
    }
}
