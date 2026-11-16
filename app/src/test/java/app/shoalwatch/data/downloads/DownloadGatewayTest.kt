package app.shoalwatch.data.downloads

import app.shoalwatch.U1
import app.shoalwatch.core.AppError
import app.shoalwatch.data.downloads.api.DownloadFacts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `GET /Items?ids=` for CanDownload and the first media source, mapped field by field. */
class DownloadGatewayTest : DownloadGatewayTestBase() {
    private val five = (1..5).map { id(it) }

    @Test fun theQueryNamesTheIdsTheUserAndOnlyTheTwoFields() {
        facts(fixture("items-candownload.json"), five)
        val sent = nextRequest()
        assertEquals("GET", sent.method)
        val url = sent.requestUrl!!
        assertEquals("/Items", url.encodedPath)
        assertEquals(listOf("ids", "userId", "fields", "enableUserData"), url.queryParameterNames.toList())
        assertEquals(five.joinToString(","), url.queryParameter("ids"))
        assertEquals(U1, url.queryParameter("userId"))
        assertEquals("CanDownload,MediaSources", url.queryParameter("fields"))
        assertEquals("false", url.queryParameter("enableUserData"))
        assertTrue(sent.getHeader("Authorization")!!.contains("Token=\"tok-1\""))
    }

    @Test fun theFixtureIsMappedFieldByField() {
        val got = facts(fixture("items-candownload.json"), five)
        assertEquals(
            listOf(
                DownloadFacts(id(1), canDownload = true, sourceId = "a0000000000000000000000000000001", container = "mkv", sizeBytes = 1_503_238_553L, eTag = "5d1c0e9a7b"),
                // dashed id normalised; CanDownload false; the first of Jellyfin's comma-separated container names
                DownloadFacts(id(2), canDownload = false, sourceId = "a0000000000000000000000000000002", container = "mov", sizeBytes = 734_003_200L, eTag = null),
                // upper-case id normalised; CanDownload absent; container lower-cased; no size
                DownloadFacts(id(3), canDownload = null, sourceId = "a0000000000000000000000000000003", container = "mp4", sizeBytes = null, eTag = null),
                // CanDownload JSON null; no media sources
                DownloadFacts(id(4), canDownload = null, sourceId = null, container = null, sizeBytes = null, eTag = null),
                // CanDownload a string is not a boolean: unknown
                DownloadFacts(id(5), canDownload = null, sourceId = null, container = null, sizeBytes = null, eTag = null),
            ),
            got,
        )
    }

    /** Tasks 5-8 review I-4: the source whose Id is the item's (the file /Download sends) wins over a wider one listed first. */
    @Test fun theSourceWhoseIdIsTheItemsIsChosenOverOneListedFirst() {
        val wide = """{"Id":"${id(9, 'b')}","Container":"mkv","Size":9000000000,"ETag":"wide"}"""
        val own = """{"Id":"${id(1)}","Container":"mov,mp4","Size":1000,"ETag":"own"}"""
        val got = facts("""{"Items":[{"Id":"${id(1)}","CanDownload":true,"MediaSources":[$wide,$own]},
            {"Id":"${id(2)}","CanDownload":true,"MediaSources":[$wide]}]}""", listOf(id(1), id(2)))
        assertEquals(DownloadFacts(id(1), true, sourceId = id(1), container = "mov", sizeBytes = 1000, eTag = "own"), got[0])
        assertEquals("none of the item's id: the first", id(9, 'b'), got[1].sourceId)
    }

    @Test fun anIdTheServerDidNotReturnIsAbsent() {
        val got = facts("""{"Items":[{"Id":"${id(1)}","CanDownload":true}]}""", listOf(id(1), id(9)))
        assertEquals(listOf(id(1)), got.map { it.itemId })
    }

    @Test fun anIdThatIsNotAGuidIsRefusedBeforeAnythingIsSent() {
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure { gateway.facts(session, listOf(id(1), "../System/Info")) }.kind)
        assertEquals(0, web.requestCount)
    }

    @Test fun idsAreNormalisedBeforeSendingAndAnEmptyListSendsNothing() {
        facts("""{"Items":[]}""", listOf("A0000000-0000-0000-0000-000000000001", id(1)))
        assertEquals(id(1), nextRequest().requestUrl!!.queryParameter("ids"))
        assertEquals(emptyList<DownloadFacts>(), runBlocking { gateway.facts(session, emptyList()) })
        assertEquals(1, web.requestCount)
    }
}
