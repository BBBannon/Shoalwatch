package app.shoalwatch.data.downloads

import app.shoalwatch.core.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Each server behaviour from Task 1 is its own case, and every one that finishes ends with a byte-identical file. */
class FileFetcherTest : FileFetcherTestBase() {
    private val size get() = server.body.size.toLong()

    @Test fun aWholeFileArrivesAndThePartIsRenamed() {
        assertEquals(FetchResult.Done, fetch())
        assertComplete()
        assertEquals(ProgressLog.Saved(size, size, server.etag, 0, size), log.last)
        val sent = server.requests.single()
        assertEquals("GET", sent.method)
        assertEquals("/Items/$item/Download", sent.path)
        assertEquals("identity", sent.getHeader("Accept-Encoding"))
        assertNull("a first fetch sends no Range", sent.getHeader("Range"))
        assertNull("and never an If-Range it did not get from this file", sent.getHeader("If-Range"))
        assertTrue(sent.getHeader("Authorization")!!.contains("Token=\"tok-1\""))
        assertEquals(listOf(part to size), fake.reserved)
    }

    @Test fun aDropThenAResumeWithRangeHonoured() {
        server.dropNext = true
        assertEquals(FetchResult.Retry(AppError.Kind.NETWORK), fetch())
        val saved = log.last.done
        assertTrue("some bytes were kept: $saved", saved in 1 until size)
        assertEquals(server.etag, log.last.validator)
        assertEquals(FetchResult.Done, fetch())
        val resume = server.requests.last()
        assertEquals("bytes=$saved-", resume.getHeader("Range"))
        assertEquals(server.etag, resume.getHeader("If-Range"))
        assertComplete()
        assertEquals(0, log.last.restarts)
        assertEquals("space is reserved once, on the first start", 1, fake.reserved.size)
    }

    @Test fun aDropThenAResumeWithLastModifiedOnlyAsRealJellyfinSends() {
        server.noEtag = true
        server.dropNext = true
        assertEquals(FetchResult.Retry(AppError.Kind.NETWORK), fetch())
        val saved = log.last.done
        assertEquals(server.lastModified, log.last.validator)
        assertEquals(FetchResult.Done, fetch())
        assertEquals("bytes=$saved-", server.requests.last().getHeader("Range"))
        assertEquals(server.lastModified, server.requests.last().getHeader("If-Range"))
        assertComplete()
    }

    @Test fun aServerThatIgnoresRangeRestartsFromZeroWithoutCountingAChange() {
        server.noRange = true
        server.dropNext = true
        assertEquals(FetchResult.Retry(AppError.Kind.NETWORK), fetch())
        assertTrue(log.last.done > 0)
        assertEquals(FetchResult.Done, fetch())
        assertTrue(server.requests.last().getHeader("Range") != null)
        assertComplete()
        assertEquals("the same validator: not a change on the server", 0, log.last.restarts)
    }

    @Test fun aValidatorThatChangesEveryTimeRestartsAndThreeInARowIsChangedOnServer() {
        server.etagChanges = true
        server.dropAlways = true
        assertEquals(FetchResult.Retry(AppError.Kind.NETWORK), fetch())
        assertEquals(FetchResult.Retry(AppError.Kind.NETWORK), fetch())
        assertEquals(1, log.last.restarts)
        assertEquals(FetchResult.Retry(AppError.Kind.NETWORK), fetch())
        assertEquals(2, log.last.restarts)
        val before = log.saves.size
        assertEquals(FetchResult.Failed(DownloadFailure.CHANGED_ON_SERVER), fetch())
        assertEquals("nothing more is saved", before, log.saves.size)
        server.dropAlways = false
        server.etagChanges = false
        log.saves.clear()
        part.delete()
        assertEquals(FetchResult.Done, fetch())
        assertComplete()
    }

    @Test fun octetStreamAndNoContentTypeAreAccepted() {
        server.contentType = "application/octet-stream"
        assertEquals(FetchResult.Done, fetch())
        assertComplete()
        target.delete()
        log.saves.clear()
        server.contentType = null
        assertEquals(FetchResult.Done, fetch())
        assertComplete()
    }

    @Test fun aTornTailIsCutBeforeTheResume() {
        val saved = 40_000
        part.writeBytes(server.body.copyOf(saved) + Random(1).nextBytes(5_000))
        log.saves += ProgressLog.Saved(saved.toLong(), size, server.etag, 0, saved.toLong())
        assertEquals(FetchResult.Done, fetch())
        assertEquals("bytes=$saved-", server.requests.single().getHeader("Range"))
        assertComplete()
    }

    @Test fun a416WithACompletePartIsDone() {
        part.writeBytes(server.body)
        log.saves += ProgressLog.Saved(size, size, server.etag, 0, size)
        assertEquals(FetchResult.Done, fetch())
        assertEquals("bytes=$size-", server.requests.single().getHeader("Range"))
        assertComplete()
    }

    @Test fun progressIsNeverSavedBeforeItsBytesAreForcedToDisk() {
        val big = RangeServer(Random(3).nextBytes(20 * 1024 * 1024))
        web.dispatcher = big
        val f = fetcher(chunk = 256 * 1024, record = true)
        val seen = mutableListOf<Pair<Long, Long?>>() // (done, the size on disk at the last force before this save)
        val progress = Progress { done, _, _, _ -> seen += done to channel?.forcedSizes?.lastOrNull() }
        assertEquals(FetchResult.Done, runBlocking { f.fetch(route(), item, part, target, 0, null, big.body.size.toLong(), null, 0, progress) })
        assertTrue("saved every 8 MiB as well as at the end: ${seen.map { it.first }}", seen.size >= 3)
        for ((done, forced) in seen) assertTrue("$done was forced first ($forced)", forced != null && forced >= done)
        assertEquals(big.body.size.toLong(), seen.last().first)
    }

    @Test fun cancellingMidBodyCancelsTheCallAndKeepsTheSavedCount() = runBlocking {
        web.dispatcher = okhttp3.mockwebserver.QueueDispatcher()
        web.enqueue(MockResponse().setBody(Buffer().write(ByteArray(4 * 1024 * 1024))).throttleBody(64 * 1024, 100, TimeUnit.MILLISECONDS))
        val job = launch(Dispatchers.IO) { fetcher().fetch(route(), item, part, target, 0, null, 4L * 1024 * 1024, null, 0, log) }
        delay(400)
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(log.saves.isEmpty())
        assertFalse(target.exists())
    }

    @Test fun noRequestCarriesAnApiKey() {
        server.dropNext = true
        fetch()
        fetch()
        for (r in server.requests) {
            assertFalse(r.path!!.contains("api_key", ignoreCase = true) || r.path!!.contains("ApiKey", ignoreCase = true))
            assertTrue(r.getHeader("Authorization") != null)
        }
    }
}
