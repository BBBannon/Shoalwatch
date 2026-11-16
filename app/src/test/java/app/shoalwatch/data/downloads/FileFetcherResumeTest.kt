package app.shoalwatch.data.downloads

import app.shoalwatch.core.AppError
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Tasks 5-8 review I-2 and Minors 2 and 5: a resume never joins two versions of a file, Room never claims bytes the part file
 * no longer holds, and a body that trickles is cut.
 */
class FileFetcherResumeTest : FileFetcherTestBase() {
    private val old = Random(11).nextBytes(64 * 1024)
    private val new = Random(12).nextBytes(64 * 1024)
    private val oldDate = "Tue, 01 Sep 2026 10:00:00 GMT"
    private val olderDate = "Sat, 01 Aug 2026 10:00:00 GMT"

    /** First: the old file, cut half-way. Then the file is replaced, and the server answers each ranged request with [ranged]. */
    private inner class Replaced(val ranged: (RecordedRequest) -> MockResponse) : Dispatcher() {
        var served = 0
        override fun dispatch(request: RecordedRequest): MockResponse {
            served++
            if (served == 1) {
                return MockResponse().setHeader("Last-Modified", oldDate).setHeader("Content-Type", "video/mp4").setBody(Buffer().write(old))
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            }
            if (request.getHeader("Range") != null) return ranged(request)
            return MockResponse().setHeader("Last-Modified", olderDate).setHeader("Content-Type", "video/mp4").setBody(Buffer().write(new))
        }
    }

    private fun rest(body: ByteArray, from: Long, total: Long = body.size.toLong(), modified: String? = olderDate) =
        MockResponse().setResponseCode(206).apply { modified?.let { setHeader("Last-Modified", it) } }.setHeader("Content-Type", "video/mp4")
            .setHeader("Content-Range", "bytes $from-${total - 1}/$total").setBody(Buffer().write(body, from.toInt(), (total - from).toInt()))

    private fun from(request: RecordedRequest) = Regex("bytes=(\\d+)-").matchEntire(request.getHeader("Range")!!)!!.groupValues[1].toLong()

    @Test fun a206FromAFileReplacedByOneWithAnOlderDateRestartsAndIsNeverAppended() {
        // ASP.NET evaluates If-Range: <date> as "not modified since", so a replacement dated earlier is served as a 206.
        web.dispatcher = Replaced { rest(new, from(it)) }
        assertEquals(FetchResult.Retry(AppError.Kind.NETWORK), fetch(expected = 64 * 1024))
        assertEquals(oldDate, log.last.validator)
        assertEquals(FetchResult.Retry(AppError.Kind.SERVER_ERROR), fetch(expected = 64 * 1024))
        assertEquals("the part is cut, and Room hears of it", 0L, log.last.done)
        assertEquals(0L, part.length())
        assertEquals("counted as a change on the server", 1, log.last.restarts)
        assertEquals(FetchResult.Done, fetch(expected = 64 * 1024))
        assertArrayEquals("the new file whole, never the old head with the new tail", new, target.readBytes())
    }

    @Test fun a206WithNoValidatorWhenOneWasSentRestarts() {
        web.dispatcher = Replaced { rest(old, from(it), modified = null) }
        fetch(expected = 64 * 1024)
        assertEquals(FetchResult.Retry(AppError.Kind.SERVER_ERROR), fetch(expected = 64 * 1024))
        assertEquals(0L, part.length())
    }

    @Test fun withSizeUnknownA206TotalThatDiffersFromTheStoredTotalRestarts() {
        val longer = old + ByteArray(1_000)
        web.dispatcher = Replaced { rest(longer, from(it), modified = oldDate) }
        assertEquals(FetchResult.Retry(AppError.Kind.NETWORK), fetch(expected = null))
        assertEquals("the first response's total is stored", 64L * 1024, log.last.total)
        assertEquals(FetchResult.Retry(AppError.Kind.SERVER_ERROR), fetch(expected = null))
        assertEquals(0L, part.length())
        assertEquals(0L, log.last.done)
    }

    @Test fun a416WithADifferentTotalThanTheStoredOneStartsAgain() {
        val held = 40_000
        part.writeBytes(old.copyOf(held))
        log.saves += ProgressLog.Saved(held.toLong(), 64L * 1024, oldDate, 0, held.toLong())
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */$held")
        }
        assertEquals(FetchResult.Retry(AppError.Kind.SERVER_ERROR), fetch(expected = null))
        assertTrue("not renamed as if complete", !target.exists())
        assertEquals(0L, part.length())
    }

    @Test fun a200WhileResumingTellsRoomThePartWasCutBeforeWritingAnything() {
        server.etagChanges = true
        server.dropNext = true
        assertEquals(FetchResult.Retry(AppError.Kind.NETWORK), fetch())
        val before = log.saves.size
        assertEquals(FetchResult.Done, fetch())
        val first = log.saves[before]
        assertEquals("the first save after the 200 is the cut", 0L, first.done)
        assertEquals(0L, first.onDisk)
        assertEquals(server.body.size.toLong(), first.total)
        assertEquals("\"v2\"", first.validator)
        assertComplete()
    }

    @Test fun aBodyThatTricklesIsCutAsATimeout() = runBlocking {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(old))
                .throttleBody(64, 200, TimeUnit.MILLISECONDS)
        }
        val slow = FileFetcher(kotlinx.coroutines.Dispatchers.IO, fake.volumes, 8 * 1024, stall = StallRule(windowMs = 500, minBytes = 4_096))
        val started = System.nanoTime()
        val result = slow.fetch(route(), item, part, target, 0, null, 64L * 1024, null, 0, { true }, log)
        assertEquals(FetchResult.Retry(AppError.Kind.TIMEOUT), result)
        assertTrue("cut within a few windows", (System.nanoTime() - started) / 1_000_000 < 5_000)
    }
}
