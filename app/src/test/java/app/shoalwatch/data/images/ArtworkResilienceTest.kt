package app.shoalwatch.data.images

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.core.AppError
import coil3.disk.DiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Passes every call through, counting snapshots and editors opened and closed, and can cancel a job at the moment one is opened. */
class WatchedDiskCache(private val delegate: DiskCache, private val watch: Watch) : DiskCache by delegate {
    class Watch {
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val editorsOpened = AtomicInteger()
        val editorsEnded = AtomicInteger()
        val cancelOnOpen = AtomicReference<Job?>()
    }

    private fun counted(s: DiskCache.Snapshot): DiskCache.Snapshot {
        watch.opened.incrementAndGet()
        return object : DiskCache.Snapshot by s {
            override fun close() { watch.closed.incrementAndGet(); s.close() }
            override fun closeAndOpenEditor(): DiskCache.Editor? = s.closeAndOpenEditor()
        }
    }

    override fun openSnapshot(key: String): DiskCache.Snapshot? =
        delegate.openSnapshot(key)?.let(::counted)?.also { watch.cancelOnOpen.getAndSet(null)?.cancel() }

    override fun openEditor(key: String): DiskCache.Editor? = delegate.openEditor(key)?.let { e ->
        watch.editorsOpened.incrementAndGet()
        watch.cancelOnOpen.getAndSet(null)?.cancel()
        object : DiskCache.Editor by e {
            override fun commit() { watch.editorsEnded.incrementAndGet(); e.commit() }
            override fun commitAndOpenSnapshot(): DiskCache.Snapshot? { watch.editorsEnded.incrementAndGet(); return e.commitAndOpenSnapshot()?.let(::counted) }
            override fun abort() { watch.editorsEnded.incrementAndGet(); e.abort() }
        }
    }
}

@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkResilienceTest : ArtworkTestBase() {
    private val watch = WatchedDiskCache.Watch()

    override fun makeCaches(directory: File) = ArtworkDiskCaches(directory, create = { dir, max ->
        WatchedDiskCache(ArtworkDiskCaches.defaultCreate(dir, max), watch)
    })

    // M1: a ref from a session that is no longer signed in never shows a saved picture.
    @Test fun aSignedOutSessionGetsNoPictureFromMemoryOrDisk() {
        web.enqueue(image(bitmapBytes(), "image/jpeg"))
        loaded(ref(), memory = true)
        loaded(ref(), memory = true) // from memory
        runBlocking { store.removeAccount(S1, app.shoalwatch.U1) }
        val fromMemory = failed(ref(), memory = true)
        val fromDisk = failed(ref(), memory = false)
        assertEquals(AppError.Kind.NOT_SIGNED_IN, (fromMemory as AppError).kind)
        assertEquals(AppError.Kind.NOT_SIGNED_IN, (fromDisk as AppError).kind)
        assertEquals("nothing was sent", 1, web.requestCount)
        assertNotNull("the entry itself stays for other users of the server", cached(ref(), 320))
    }

    // M3: a snapshot or editor is never left open when the caller is cancelled while it is being obtained.
    @Test fun cancellingWhileASavedPictureIsOpenedClosesTheSnapshot() {
        web.enqueue(image(bitmapBytes(), "image/jpeg"))
        loaded(ref())
        watch.opened.set(0); watch.closed.set(0)
        runBlocking {
            val job = async(Dispatchers.Default) { loader.execute(request(ref())) }
            watch.cancelOnOpen.set(job)
            runCatching { job.await() }
        }
        assertTrue("a snapshot was opened", watch.opened.get() >= 1)
        assertEquals("every opened snapshot was closed", watch.opened.get(), watch.closed.get())
    }

    @Test fun cancellingWhileAFreshPictureIsSavedLeavesNoOpenSnapshotOrEditor() {
        web.enqueue(image(bitmapBytes(), "image/jpeg"))
        runBlocking {
            val job = async(Dispatchers.Default) { loader.execute(request(ref())) }
            watch.cancelOnOpen.set(job)
            runCatching { job.await() }
        }
        assertEquals(1, watch.editorsOpened.get())
        assertEquals("the editor was committed or aborted", 1, watch.editorsEnded.get())
        assertEquals("every opened snapshot was closed", watch.opened.get(), watch.closed.get())
    }

    // M4: a saved picture that cannot be decoded is evicted so the next request downloads it again.
    @Test fun aSavedPictureThatFailsToDecodeIsEvictedAndFetchedAgain() {
        val good = noisyPng(64, 64)
        val r = ref()
        val corrupt = good.copyOf().also { for (i in 400 until 600) it[i] = (it[i].toInt() xor 0x5A).toByte() }
        val editor = diskOf(r).openEditor(ArtworkLimits.cacheKey(r, 320))!!
        diskOf(r).fileSystem.write(editor.data) { write(corrupt) }
        editor.commit()
        assertTrue("it is not caught by the header checks", ArtworkValidation.check(null, corrupt) == ArtworkLimits.Kind.PNG)
        failed(r)
        assertEquals("nothing was fetched for the first, failing request", 0, web.requestCount)
        assertNull("the unreadable entry was evicted", cached(r, 320))
        web.enqueue(image(good, "image/png"))
        assertEquals(64, loaded(r).width)
        assertEquals(1, web.requestCount)
    }

    @Test fun aSavedPictureThatEndsEarlyIsEvictedWithoutBeingDecoded() {
        val good = bitmapBytes(64, 64, android.graphics.Bitmap.CompressFormat.PNG)
        val r = ref()
        val editor = diskOf(r).openEditor(ArtworkLimits.cacheKey(r, 320))!!
        diskOf(r).fileSystem.write(editor.data) { write(good.copyOf(good.size - 30)) }
        editor.commit()
        web.enqueue(image(good, "image/png"))
        assertEquals(64, loaded(r).width)
        assertEquals("the damaged entry was not shown; the server was asked", 1, web.requestCount)
    }

    // M6: the memory-cache key behaves as the disk key does.
    @Test fun theMemoryKeyIsAddressIndependentAndTagAndBucketSensitive() {
        web.enqueue(image(bitmapBytes(), "image/jpeg"))
        loaded(ref(), memory = true, disk = false)
        assertTrue("the picture is in memory under the shared key", loader.memoryCache!!.keys.any { it.key == ArtworkLimits.cacheKey(ref(), 320) })
        val other = MockWebServer().apply { start() }
        try {
            moveServerTo(other)
            loaded(ref(), memory = true, disk = false)
            assertEquals("a memory hit sends nothing, to either address", 1, web.requestCount + other.requestCount)
            other.enqueue(image(bitmapBytes(), "image/jpeg")); other.enqueue(image(bitmapBytes(), "image/jpeg"))
            loaded(ref("new"), memory = true, disk = false)
            loaded(ref(), widthPx = 700, memory = true, disk = false)
            assertEquals("a new tag and a new bucket each miss", 2, other.requestCount)
            loaded(ref("new"), memory = true, disk = false)
            assertEquals("and then hit", 2, other.requestCount)
        } finally { other.shutdown() }
        assertFalse(U1.isEmpty())
    }
}
