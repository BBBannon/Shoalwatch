package app.shoalwatch.data.library

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A caller pages by `nextStartIndex` and stops on `endReached`: neither depends on how many elements the gateway kept. */
class LibraryGatewayPageTest : LibraryGatewayTestBase() {
    private val nameAsc = Sort(SortBy.NAME, descending = false)

    private fun movie(n: Int) = """{"Id":"${"dd%030x".format(n)}","Name":"M$n","Type":"Movie"}"""

    private fun page(body: String, start: Int = 0, limit: Int = 10): Page<ItemWithUserData> {
        web.enqueue(json(body))
        return runBlocking { gateway.children(session, FILMS, start, limit, nameAsc, ItemFilter()) }
    }

    private fun movies(count: Int, first: Int = 1) = (first until first + count).joinToString(",") { movie(it) }

    // ---- A1: how far the server's list was read

    @Test fun skippedAndRepeatedElementsStillCountAsConsumed() {
        val page = page("""{"Items":[${movie(1)},${movie(1)},"junk",{"Id":5},${movie(2)}],"TotalRecordCount":100}""", start = 20)
        assertEquals(2, page.items.size)
        assertEquals(20, page.startIndex)
        assertEquals(25, page.nextStartIndex) // 20 + the five elements the server sent
        assertEquals(100, page.total)
        assertFalse(page.endReached)
    }

    @Test fun anAllMalformedPageIsNotTheEndWhenTheTotalSaysMoreRemain() {
        val page = page("""{"Items":["x",7,null],"TotalRecordCount":50}""")
        assertEquals(emptyList<ItemWithUserData>(), page.items)
        assertEquals(3, page.nextStartIndex)
        assertEquals(50, page.total)
        assertFalse(page.endReached)
    }

    @Test fun anEmptyAnswerIsTheEndWhateverTheTotalSays() {
        val page = page("""{"Items":[],"TotalRecordCount":900}""", start = 100)
        assertTrue(page.endReached)
        assertEquals(100, page.nextStartIndex)
        assertEquals(900, page.total)
    }

    @Test fun reachingTheServersTotalIsTheEnd() {
        assertTrue(page("""{"Items":[${movies(5)}],"TotalRecordCount":15}""", start = 10).endReached)
        assertFalse(page("""{"Items":[${movies(5)}],"TotalRecordCount":16}""", start = 10).endReached)
    }

    @Test fun withNoTotalAShortAnswerIsTheEndAndAFullOneIsNot() {
        assertTrue(page("""{"Items":[${movies(3)}]}""", limit = 10).endReached)
        assertFalse(page("""{"Items":[${movies(10)}]}""", limit = 10).endReached)
    }

    @Test fun aServerThatSendsMoreThanAskedAdvancesByWhatItSent() {
        val page = page("""{"Items":[${movies(12)}],"TotalRecordCount":40}""", limit = 10)
        assertEquals(10, page.items.size) // capped at the limit
        assertEquals(12, page.nextStartIndex) // but the server consumed twelve
        assertFalse(page.endReached)
    }

    // ---- A2: a nonsense total is no total

    @Test fun aTotalOfZeroWhileElementsCameBackIsUnknown() {
        val full = page("""{"Items":[${movies(10)}],"TotalRecordCount":0}""", limit = 10)
        assertNull(full.total)
        assertFalse(full.endReached) // unknown total, a full page: more may follow
        val short = page("""{"Items":[${movies(3)}],"TotalRecordCount":0}""", limit = 10)
        assertNull(short.total)
        assertTrue(short.endReached) // unknown total, a short page
    }

    @Test fun aNegativeTotalIsUnknown() {
        assertNull(page("""{"Items":[${movies(2)}],"TotalRecordCount":-5}""").total)
        assertNull(page("""{"Items":[],"TotalRecordCount":-1}""").total)
    }

    @Test fun aTotalSmallerThanWhatHasBeenSeenIsUnknown() {
        val behind = page("""{"Items":[${movies(2)}],"TotalRecordCount":5}""", start = 10)
        assertNull(behind.total)
        assertEquals(12, behind.nextStartIndex)
        assertNull(page("""{"Items":[${movies(2)}],"TotalRecordCount":11}""", start = 10).total) // 12 seen
        assertEquals(12, page("""{"Items":[${movies(2)}],"TotalRecordCount":12}""", start = 10).total)
    }

    @Test fun aTotalOfZeroForAnEmptyListIsKept() {
        val page = page("""{"Items":[],"TotalRecordCount":0}""")
        assertEquals(0, page.total)
        assertTrue(page.endReached)
    }

    /** Reports `claimed` as the total, whatever the real size. */
    private class Serving(val real: Int, val claimed: Int) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl!!
            val start = url.queryParameter("startIndex")!!.toInt()
            val limit = url.queryParameter("limit")!!.toInt()
            val rows = (start until minOf(start + limit, real)).joinToString(",") { n ->
                """{"Id":"${"cc%030x".format(n + 1)}","Name":"E$n","Type":"Episode","ParentIndexNumber":1,"IndexNumber":${n + 1}}"""
            }
            return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"Items":[$rows],"TotalRecordCount":$claimed}""")
        }
    }

    @Test fun aLyingLowTotalNoLongerTruncatesALongSeries() {
        web.dispatcher = Serving(real = 1200, claimed = 10)
        assertEquals(1200, runBlocking { gateway.episodes(session, SERIES, null) }.size)
    }

    @Test fun aZeroTotalNoLongerTruncatesALongSeriesEither() {
        web.dispatcher = Serving(real = 700, claimed = 0)
        assertEquals(700, runBlocking { gateway.episodes(session, SERIES, null) }.size)
    }
}
