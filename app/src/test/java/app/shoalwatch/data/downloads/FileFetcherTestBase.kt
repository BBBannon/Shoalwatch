package app.shoalwatch.data.downloads

import app.shoalwatch.S1
import app.shoalwatch.data.api.MediaRoute
import app.shoalwatch.data.api.ServerApiTestBase
import app.shoalwatch.data.db.downloads.DownloadKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

/** A real download route (real ServerApi, ServerClients and OkHttp) to MockWebServer, and a real item folder in a temporary root. */
abstract class FileFetcherTestBase : ServerApiTestBase() {
    @get:Rule val tmp = TemporaryFolder()

    protected val item = "0123456789abcdef0123456789abcdef"
    protected val server = RangeServer()
    protected lateinit var fake: FakeVolumes
    internal lateinit var paths: DownloadPaths
    protected lateinit var part: File
    protected lateinit var target: File
    internal lateinit var log: ProgressLog
    protected var channel: RecordingChannel? = null

    @Before fun setUpFetcher() {
        web.dispatcher = server
        fake = FakeVolumes(tmp.root)
        paths = DownloadPaths(fake.volumes)
        val folder = paths.itemFolder(DownloadKey(S1, item), StorageKind.INTERNAL, null)!!.also { it.mkdirs() }
        part = paths.partFile(folder, "mp4")
        target = paths.mediaFile(folder, "mp4")
        log = ProgressLog(part, target)
    }

    protected fun route(): MediaRoute = runBlocking { api.downloadRoute(session, item) }

    internal fun fetcher(chunk: Int = 8 * 1024, record: Boolean = false) = FileFetcher(
        Dispatchers.IO, fake.volumes, chunk,
        channels = { f -> FetchSupport.appendChannel(f).let { if (record) RecordingChannel(it, f).also { c -> channel = c } else it } },
    )

    /** One fetch, resuming from what the log last saved (as the queue would, from Room: the count, validator, total and restarts). */
    internal fun fetch(
        expected: Long? = server.body.size.toLong(), resume: Boolean = true, f: FileFetcher = fetcher(), fits: (Long) -> Boolean = { true },
    ): FetchResult = runBlocking {
        val last = log.saves.lastOrNull()?.takeIf { resume }
        f.fetch(route(), item, part, target, last?.done ?: 0, last?.validator, expected, last?.total, last?.restarts ?: 0, fits, log)
    }

    protected fun assertComplete() {
        assertFalse("the part file is renamed", part.exists())
        assertArrayEquals("byte-identical", server.body, target.readBytes())
        for (s in log.saves) assertEquals("progress never claims more than is on disk", true, s.done <= s.onDisk)
    }
}
