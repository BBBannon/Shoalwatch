package app.shoalwatch.data.library

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app never depends on the server's order (SPEC S11), and a long series is fetched in pages up to a hard ceiling. */
class LibraryGatewayOrderAndPagingTest : LibraryGatewayTestBase() {
    private fun num(id: String) = id.removePrefix("cc").toInt(16)

    private fun id(n: Int) = "cc%030x".format(n)

    private fun episode(n: Int, season: Int? = 1, index: Int? = n, sortName: String? = null) = buildString {
        append("""{"Id":"${id(n)}","Name":"E$n","Type":"Episode"""")
        if (season != null) append(""","ParentIndexNumber":$season""")
        if (index != null) append(""","IndexNumber":$index""")
        if (sortName != null) append(""","SortName":"$sortName"""")
        append("}")
    }

    private fun episodes(vararg rows: String): List<Int> {
        web.enqueue(json("""{"Items":[${rows.joinToString(",")}],"TotalRecordCount":${rows.size}}"""))
        return runBlocking { gateway.episodes(session, SERIES, null) }.map { num(it.item.id) }
    }

    @Test fun episodesAreSortedBySeasonThenEpisodeNumberWhateverOrderTheServerUses() {
        val order = episodes(episode(5, 2, 1), episode(2, 1, 2), episode(4, 2, 1 - 1), episode(1, 1, 1), episode(3, 1, 3))
        assertEquals(listOf(1, 2, 3, 4, 5), order)
    }

    @Test fun missingNumbersSortLastThenBySortNameThenById() {
        val order = episodes(
            episode(9, null, null, "zz"), episode(8, 1, null, "b"), episode(7, 1, null, "a"), episode(6, 1, 1),
            episode(11, 1, null, "a"), episode(10, null, null, "aa"),
        )
        // season 1: numbered first, then unnumbered by sort name then id; no season last, again by sort name
        assertEquals(listOf(6, 7, 11, 8, 10, 9), order)
    }

    private fun season(n: Int, index: Int?, name: String) = """{"Id":"${id(n)}","Name":"$name","Type":"Season"${index?.let { ""","IndexNumber":$it""" } ?: ""}}"""

    private fun seasonIds(vararg rows: String): List<Int> {
        web.enqueue(json("""{"Items":[${rows.joinToString(",")}]}"""))
        return runBlocking { gateway.seasons(session, SERIES) }.map { num(it.item.id) }
    }

    @Test fun seasonsPutSpecialsAfterTheNumberedOnesThenAnyUnnumberedOne() {
        val ids = seasonIds(season(1, 0, "Specials"), season(2, 2, "Season 2"), season(3, null, "Extras"), season(4, 1, "Season 1"), season(5, 3, "Season 3"))
        assertEquals(listOf(4, 2, 5, 1, 3), ids) // 1, 2, 3, then Specials (season 0), then the unnumbered one
    }

    @Test fun theSameSeasonsComeOutInTheSameOrderWhateverOrderTheServerUses() {
        val rows = listOf(season(1, 1, "S1"), season(2, 2, "S2"), season(3, 3, "S3"), season(4, 0, "Specials"), season(5, null, "Extras"))
        repeat(8) { seed -> assertEquals(listOf(1, 2, 3, 4, 5), seasonIds(*rows.shuffled(java.util.Random(seed.toLong())).toTypedArray())) }
    }

    @Test fun aWholeSeriesListPutsSeasonZeroAfterTheNumberedSeasonsAndNoSeasonAfterThat() {
        val rows = listOf(
            episode(1, 1, 1), episode(2, 1, 2), episode(3, 2, 1), episode(4, 3, 1),
            episode(5, 0, 1), episode(6, 0, 2), episode(7, null, 1), episode(8, null, null, "zz"),
        )
        repeat(8) { seed -> assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), episodes(*rows.shuffled(java.util.Random(seed.toLong())).toTypedArray())) }
    }

    private class Serving(val total: Int, val limitCap: Int = Int.MAX_VALUE) : Dispatcher() {
        val starts = mutableListOf<Int>()
        val limits = mutableListOf<Int>()
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl!!
            val start = url.queryParameter("startIndex")!!.toInt()
            val limit = minOf(url.queryParameter("limit")!!.toInt(), limitCap)
            synchronized(this) { starts += start; limits += limit }
            val rows = (start until minOf(start + limit, total)).joinToString(",") { n ->
                """{"Id":"${"cc%030x".format(n + 1)}","Name":"E$n","Type":"Episode","ParentIndexNumber":${n / 100 + 1},"IndexNumber":${n % 100 + 1}}"""
            }
            return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"Items":[$rows],"TotalRecordCount":$total,"StartIndex":$start}""")
        }
    }

    @Test fun aLongSeriesIsFetchedInPagesUntilEveryEpisodeIsHere() {
        val server = Serving(total = 2300).also { web.dispatcher = it }
        val episodes = runBlocking { gateway.episodes(session, SERIES, null) }
        assertEquals(2300, episodes.size)
        assertEquals(listOf(0, 500, 1000, 1500, 2000), server.starts)
        assertEquals(setOf(500), server.limits.toSet())
        assertEquals(episodes.map { it.item.id }.distinct(), episodes.map { it.item.id })
        assertEquals(1, episodes.first().item.parentIndexNumber)
        assertEquals(23, episodes.last().item.parentIndexNumber)
    }

    @Test fun aServerThatReturnsFewerThanAskedStillGetsAllItsEpisodes() {
        val server = Serving(total = 450, limitCap = 100).also { web.dispatcher = it }
        assertEquals(450, runBlocking { gateway.episodes(session, SERIES, null) }.size)
        assertEquals(listOf(0, 100, 200, 300, 400), server.starts)
    }

    @Test fun pagingStopsAtTenThousandItemsAndFiftyRequests() {
        val server = Serving(total = 1_000_000).also { web.dispatcher = it }
        val episodes = runBlocking { gateway.episodes(session, SERIES, null) }
        assertEquals(10_000, episodes.size)
        assertTrue(server.starts.size <= 50)
    }

    @Test fun pagingEndsAtFiftyRequestsEvenIfEachAnswersWithOneItem() {
        val server = Serving(total = 600, limitCap = 1).also { web.dispatcher = it }
        val episodes = runBlocking { gateway.episodes(session, SERIES, null) }
        assertEquals(50, episodes.size) // one item per request, so the fifty-request ceiling ends it
        assertEquals(50, server.starts.size)
    }

    @Test fun anEmptyPageEndsPagingEvenIfTheTotalPromisesMore() {
        web.enqueue(json("""{"Items":[${episode(1)}],"TotalRecordCount":900}"""))
        web.enqueue(json("""{"Items":[],"TotalRecordCount":900}"""))
        assertEquals(1, runBlocking { gateway.episodes(session, SERIES, null) }.size)
        assertEquals(2, web.requestCount)
    }

    @Test fun withNoTotalAShortPageIsTheLastOne() {
        web.enqueue(json("""{"Items":[${episode(1)}]}"""))
        assertEquals(1, runBlocking { gateway.episodes(session, SERIES, null) }.size)
        assertEquals(1, web.requestCount)
    }

    @Test fun childrenAlwaysTieBreakOnSortName() {
        web.enqueue(json("""{"Items":[]}"""))
        runBlocking { gateway.children(session, FILMS, 0, 10, Sort(SortBy.RATING, true), ItemFilter()) }
        val url = nextRequest().requestUrl!!
        assertEquals("CommunityRating,SortName", url.queryParameter("sortBy"))
        assertEquals("Descending,Ascending", url.queryParameter("sortOrder"))
    }
}
