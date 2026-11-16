package app.shoalwatch.data.downloads

import android.system.ErrnoException
import android.system.OsConstants
import app.shoalwatch.core.AppError
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** What a hostile or broken server (or a full disk) can do to a file download. */
class FileFetcherHostileTest : FileFetcherTestBase() {
    private val body = ByteArray(10_000) { (it % 251).toByte() }

    @Before fun queue() { web.dispatcher = QueueDispatcher() }

    private fun answer(response: MockResponse) = web.enqueue(response)

    /** A 206 carries the validator these tests send as If-Range (`"a"`), so only what each test changes is wrong. */
    private fun ok(code: Int = 200, type: String? = "video/mp4", bytes: ByteArray = body) =
        MockResponse().setResponseCode(code).setBody(Buffer().write(bytes)).also { r -> type?.let { r.setHeader("Content-Type", it) } }
            .also { r -> if (code == 206) r.setHeader("ETag", "\"a\"") }

    private fun fetchFrom(resumeFrom: Long, validator: String? = null, expected: Long? = body.size.toLong()) =
        runBlocking { fetcher().fetch(route(), item, part, target, resumeFrom, validator, expected, null, 0, { true }, log) }

    @Test fun anHtmlPageIsRefusedBeforeAnythingIsWritten() {
        answer(ok(type = "text/html; charset=utf-8"))
        assertEquals(FetchResult.Failed(DownloadFailure.SERVER_ERROR), fetchFrom(0))
        assertTrue(!part.exists() || part.length() == 0L)
        for (type in listOf("application/json", "text/plain", "application/xhtml+xml", "application/xml")) {
            answer(ok(type = type))
            assertEquals(type, FetchResult.Failed(DownloadFailure.SERVER_ERROR), fetchFrom(0))
        }
    }

    @Test fun a206ToARequestWithNoRangeIsCutToZeroAndRetried() {
        answer(ok(code = 206).setHeader("Content-Range", "bytes 0-9999/10000"))
        assertEquals(FetchResult.Retry(AppError.Kind.SERVER_ERROR), fetchFrom(0))
        assertEquals(0L, if (part.exists()) part.length() else 0L)
        assertEquals(0L, log.last.done)
    }

    @Test fun aContentRangeWithTheWrongStartOrGarbageRestartsAndAWrongTotalIsChangedOnServer() {
        part.writeBytes(body.copyOf(4_000))
        answer(ok(code = 206, bytes = body.copyOfRange(3_000, 10_000)).setHeader("Content-Range", "bytes 3000-9999/10000"))
        assertEquals(FetchResult.Retry(AppError.Kind.SERVER_ERROR), fetchFrom(4_000, "\"a\""))
        assertEquals("cut to zero for a fresh start", 0L, part.length())
        part.writeBytes(body.copyOf(4_000))
        answer(ok(code = 206, bytes = body.copyOfRange(4_000, 10_000)).setHeader("Content-Range", "bytes 4000-9999/*"))
        assertEquals(FetchResult.Retry(AppError.Kind.SERVER_ERROR), fetchFrom(4_000, "\"a\""))
        part.writeBytes(body.copyOf(4_000))
        answer(ok(code = 206, bytes = body.copyOfRange(4_000, 10_000)).setHeader("Content-Range", "bytes 4000-10999/11000"))
        assertEquals(FetchResult.Failed(DownloadFailure.CHANGED_ON_SERVER), fetchFrom(4_000, "\"a\""))
        part.writeBytes(body.copyOf(4_000))
        answer(ok(code = 206, bytes = body.copyOfRange(4_000, 10_000)).setHeader("Content-Range", "bytes 4000-9998/10000"))
        assertEquals("the end must be the last byte", FetchResult.Retry(AppError.Kind.SERVER_ERROR), fetchFrom(4_000, "\"a\""))
    }

    @Test fun aContentLengthThatDisagreesWithTheSizeIsChangedOnServer() {
        answer(ok())
        assertEquals(FetchResult.Failed(DownloadFailure.CHANGED_ON_SERVER), fetchFrom(0, expected = 9_999))
    }

    @Test fun aChunkedBodyLongerThanTheSizeIsCutOffAndChangedOnServer() {
        answer(MockResponse().setChunkedBody(Buffer().write(body), 1_000).setHeader("Content-Type", "video/mp4"))
        assertEquals(FetchResult.Failed(DownloadFailure.CHANGED_ON_SERVER), fetchFrom(0, expected = 5_000))
        assertTrue("never more than one byte past the size", part.length() <= 5_001)
        assertFalse(target.exists())
    }

    @Test fun aChunkedBodyIsAcceptedOnlyWhenTheSizeIsKnown() {
        answer(MockResponse().setChunkedBody(Buffer().write(body), 1_000).setHeader("Content-Type", "video/mp4"))
        assertEquals(FetchResult.Done, fetchFrom(0))
        answer(MockResponse().setChunkedBody(Buffer().write(body), 1_000).setHeader("Content-Type", "video/mp4"))
        assertEquals(FetchResult.Failed(DownloadFailure.SERVER_ERROR), runBlocking {
            target.delete(); fetcher().fetch(route(), item, part, target, 0, null, null, null, 0, { true }, log)
        })
    }

    @Test fun aGzipBodyIsRefused() {
        answer(ok().setHeader("Content-Encoding", "gzip"))
        assertEquals(FetchResult.Failed(DownloadFailure.SERVER_ERROR), fetchFrom(0))
    }

    @Test fun aRedirectToAnotherHostIsNotFollowed() {
        val other = MockWebServer().apply { start() }
        try {
            answer(MockResponse().setResponseCode(301).setHeader("Location", other.url("/Items/$item/Download")))
            assertEquals(FetchResult.Retry(AppError.Kind.SERVER_NOT_FOUND), fetchFrom(0))
            assertEquals(0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test fun statusesMapToTheirResults() {
        for ((code, result) in listOf(
            401 to FetchResult.Unauthorised,
            403 to FetchResult.Failed(DownloadFailure.NOT_ALLOWED), // even when CanDownload said true: the server's answer wins
            400 to FetchResult.Failed(DownloadFailure.NOT_ALLOWED), // Jellyfin's answer for an item with no file
            404 to FetchResult.Failed(DownloadFailure.GONE_FROM_SERVER),
            500 to FetchResult.Retry(AppError.Kind.SERVER_ERROR),
            503 to FetchResult.Retry(AppError.Kind.SERVER_ERROR),
        )) {
            answer(MockResponse().setResponseCode(code))
            assertEquals("$code", result, fetchFrom(0))
        }
    }

    @Test fun aValidatorThatIsTooLongOrHasAControlCharacterIsNotKeptAndDatesAre() {
        answer(ok().setHeader("ETag", "\"" + "a".repeat(127) + "\""))
        fetchFrom(0)
        assertNull("129 characters", log.last.validator)
        target.delete()
        answer(ok().addHeaderLenient("ETag", "\"a\u0001b\""))
        fetchFrom(0)
        assertNull("a control character", log.last.validator)
        target.delete()
        answer(ok().setHeader("Last-Modified", "Sun, 15 Nov 2026 10:00:00 GMT"))
        fetchFrom(0)
        assertEquals("a date has spaces and commas and is kept", "Sun, 15 Nov 2026 10:00:00 GMT", log.last.validator)
    }

    @Test fun noValidatorMeansNoIfRange() {
        part.writeBytes(body.copyOf(4_000))
        answer(ok(code = 206, bytes = body.copyOfRange(4_000, 10_000)).setHeader("Content-Range", "bytes 4000-9999/10000"))
        assertEquals(FetchResult.Done, fetchFrom(4_000, validator = null))
        val sent = web.takeRequest()
        assertEquals("bytes=4000-", sent.getHeader("Range"))
        assertNull(sent.getHeader("If-Range"))
    }

    @Test fun aFullDiskIsNoSpace() {
        answer(ok())
        val f = FileFetcher(kotlinx.coroutines.Dispatchers.IO, fake.volumes, 1_000, channels = { file ->
            RecordingChannel(FetchSupport.appendChannel(file), file).also {
                it.failWrite = IOException("write failed", ErrnoException("write", OsConstants.ENOSPC))
            }
        })
        assertEquals(FetchResult.Failed(DownloadFailure.NO_SPACE), runBlocking { f.fetch(route(), item, part, target, 0, null, body.size.toLong(), null, 0, { true }, log) })
    }

    /**
     * Final review M-2: with `Size` unknown, nothing but the response's `Content-Length` says how big the file is, so the
     * queue's space rule is asked before the first byte, and a refusal reserves and writes nothing.
     */
    @Test fun aFileOfUnknownSizeThatWouldNotFitAboveTheReserveIsNoSpaceBeforeAnythingIsWritten() {
        answer(ok())
        val asked = mutableListOf<Long>()
        assertEquals(FetchResult.Failed(DownloadFailure.NO_SPACE), runBlocking {
            fetcher().fetch(route(), item, part, target, 0, null, null, null, 0, { asked += it; it <= 5_000 }, log)
        })
        assertEquals("asked for the whole Content-Length", listOf(10_000L), asked)
        assertTrue("nothing written", !part.exists() || part.length() == 0L)
        assertTrue("nothing reserved", fake.reserved.isEmpty())
    }

    @Test fun aResumeWhoseTotalWasNeverKnownIsCheckedForTheRestAndCutOnNoSpace() {
        part.writeBytes(body.copyOf(4_000))
        answer(ok(code = 206, bytes = body.copyOfRange(4_000, 10_000)).setHeader("Content-Range", "bytes 4000-9999/10000"))
        val asked = mutableListOf<Long>()
        assertEquals(FetchResult.Failed(DownloadFailure.NO_SPACE), runBlocking {
            fetcher().fetch(route(), item, part, target, 4_000, "\"a\"", null, null, 0, { asked += it; it <= 5_000 }, log)
        })
        assertEquals("asked for what is left", listOf(6_000L), asked)
        assertEquals("the part goes too, so the space is not kept", 0L, part.length())
        assertEquals("and Room is told so", 0L, log.last.done)
    }

    @Test fun aFullDiskPartWayCutsThePartSoItsSpaceIsNotKept() {
        part.writeBytes(body.copyOf(4_000))
        answer(ok(code = 206, bytes = body.copyOfRange(4_000, 10_000)).setHeader("Content-Range", "bytes 4000-9999/10000"))
        val f = FileFetcher(kotlinx.coroutines.Dispatchers.IO, fake.volumes, 1_000, channels = { file ->
            RecordingChannel(FetchSupport.appendChannel(file), file).also {
                it.failWrite = IOException("write failed", ErrnoException("write", OsConstants.ENOSPC))
            }
        })
        assertEquals(FetchResult.Failed(DownloadFailure.NO_SPACE), runBlocking { f.fetch(route(), item, part, target, 4_000, "\"a\"", body.size.toLong(), null, 0, { true }, log) })
        assertEquals(0L, part.length())
        assertEquals(0L, log.last.done)
    }

    @Test fun aReservationThatFailsIsNoSpace() {
        answer(ok())
        val full = object : Volumes by fake.volumes {
            override fun reserve(file: java.io.File, bytes: Long) = throw IOException("no space")
        }
        assertEquals(FetchResult.Failed(DownloadFailure.NO_SPACE), runBlocking {
            FileFetcher(kotlinx.coroutines.Dispatchers.IO, full).fetch(route(), item, part, target, 0, null, body.size.toLong(), null, 0, { true }, log)
        })
    }

    @Test fun aSignedOutRouteIsSignInNeededAndAStaleOneIsRetriedOffline() {
        val r = route()
        runBlocking { store.removeAccount(app.shoalwatch.S1, app.shoalwatch.U1) }
        assertEquals(FetchResult.Failed(DownloadFailure.SIGN_IN_NEEDED), runBlocking { fetcher().fetch(r, item, part, target, 0, null, null, null, 0, { true }, log) })
        assertEquals(0, web.requestCount)
    }

    @Test fun aStaleRouteIsRetriedOffline() {
        val r = route()
        selector.state.value = app.shoalwatch.data.servers.Connection.Checking(previous = online())
        assertEquals(FetchResult.Retry(AppError.Kind.OFFLINE), runBlocking { fetcher().fetch(r, item, part, target, 0, null, null, null, 0, { true }, log) })
        assertEquals(0, web.requestCount)
    }
}
