package app.shoalwatch.data.downloads

import app.shoalwatch.core.AppError
import app.shoalwatch.data.downloads.api.DownloadFacts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a hostile or broken server can send in answer to the download facts request. */
class DownloadGatewayHostileTest : DownloadGatewayTestBase() {
    private val asked = (1..8).map { id(it, 'c') }

    private val mapped by lazy { facts(fixture("hostile-items.json"), asked).associateBy { it.itemId } }

    @Test fun aSizeThatIsNegativeOver256GibOrFractionalIsUnknown() {
        assertEquals(null, mapped.getValue(id(1, 'c')).sizeBytes)
        assertEquals(null, mapped.getValue(id(2, 'c')).sizeBytes)
        assertEquals(null, mapped.getValue(id(3, 'c')).sizeBytes)
        assertEquals("a size given as a string is not a number", null, mapped.getValue(id(5, 'c')).sizeBytes)
        assertEquals("256 GiB exactly is allowed", 274_877_906_944L, mapped.getValue(id(4, 'c')).sizeBytes)
    }

    @Test fun aSourceIdOrETagOutsideTheSafeAlphabetIsDropped() {
        val f = mapped.getValue(id(4, 'c'))
        assertEquals(null, f.sourceId)
        assertEquals(null, f.eTag)
        assertEquals("abc", mapped.getValue(id(1, 'c')).sourceId)
    }

    @Test fun aContainerListKeepsItsFirstNameAndAnUnsafeOneIsDropped() {
        assertEquals("mp4", mapped.getValue(id(5, 'c')).container)
        assertEquals(null, mapped.getValue(id(6, 'c')).container)
    }

    @Test fun canDownloadMustBeAJsonBooleanAndARepeatedIdKeepsTheFirst() {
        assertEquals(null, mapped.getValue(id(6, 'c')).canDownload)
        assertEquals(1, facts(fixture("hostile-items.json"), asked).count { it.itemId == id(6, 'c') })
    }

    @Test fun malformedElementsAreSkippedAndUnaskedIdsAreNotReturned() {
        assertEquals(asked.toSet(), mapped.keys)
        assertEquals(DownloadFacts(id(7, 'c'), true, null, null, null, null), mapped.getValue(id(7, 'c')))
        assertEquals(DownloadFacts(id(8, 'c'), true, null, null, null, null), mapped.getValue(id(8, 'c')))
    }

    @Test fun aBodyOverFourMibIsRefused() {
        val huge = """{"Items":[{"Id":"${id(1, 'c')}","Name":"${"x".repeat(5 * 1024 * 1024)}"}]}"""
        web.enqueue(json(huge))
        assertEquals(AppError.Kind.SERVER_ERROR, failure { gateway.facts(session, listOf(id(1, 'c'))) }.kind)
    }

    @Test fun fiftyOneIdsAreAskedInTwoCallsOfAtMostFifty() {
        val ids = (1..51).map { id(it, 'e') }
        web.enqueue(json("""{"Items":[{"Id":"${ids[0]}","CanDownload":true}]}"""))
        web.enqueue(json("""{"Items":[{"Id":"${ids[50]}","CanDownload":false}]}"""))
        val got = runBlocking { gateway.facts(session, ids) }
        assertEquals(listOf(ids[0] to true, ids[50] to false), got.map { it.itemId to it.canDownload })
        assertEquals(ids.take(50).joinToString(","), nextRequest().requestUrl!!.queryParameter("ids"))
        assertEquals(ids[50], nextRequest().requestUrl!!.queryParameter("ids"))
    }
}
