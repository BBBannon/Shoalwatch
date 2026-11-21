package app.shoalwatch.data.images

import app.shoalwatch.S1
import app.shoalwatch.S2
import app.shoalwatch.testId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Pictures go when their server goes; sign-out alone keeps them unless it was the server's last account. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkEvictionTest : ArtworkTestBase() {
    private var accountsLeft = true
    private val unconfined = CoroutineScope(Dispatchers.Unconfined)
    private fun eviction() = ArtworkEviction(caches, { loader.memoryCache }, { accountsLeft }, unconfined, Dispatchers.Unconfined)

    private fun put(serverId: String, key: String) {
        val cache = caches.forServer(serverId)
        val editor = cache.openEditor(key)!!
        cache.fileSystem.write(editor.data) { write(bitmapBytes()) }
        editor.commit()
    }

    private fun has(serverId: String, key: String) = caches.forServer(serverId).openSnapshot(key)?.also { it.close() } != null

    @Test fun eachServerHasItsOwnCacheDirectoryNamedByItsId() {
        put(S1, "art:$S1:a"); put(S2, "art:$S2:b")
        assertTrue(File(tmp.root, "artwork/$S1").isDirectory)
        assertTrue(File(tmp.root, "artwork/$S2").isDirectory)
        assertFalse("a key of one server is not found in another's cache", has(S2, "art:$S1:a"))
    }

    @Test fun eachServerCacheIsCappedAt250Mb() {
        var seen = 0L
        val own = ArtworkDiskCaches(tmp.newFolder("capped")) { dir, max -> seen = max; ArtworkDiskCaches.defaultCreate(dir, max) }
        own.forServer(S1)
        assertEquals(250L * 1024 * 1024, seen)
    }

    @Test fun removingAServerClearsItsDiskAndMemoryEntriesAndNobodyElses() {
        web.enqueue(image(bitmapBytes(), "image/jpeg"))
        loaded(ref(), memory = true)
        assertTrue("the picture is in memory", loader.memoryCache!!.keys.any { it.key == ArtworkLimits.cacheKey(ref(), 320) })
        put(S2, "art:$S2:keep")
        assertNotNull(cached(ref(), 320))
        eviction().onServerRemoved(S1)
        assertFalse("the server's folder is gone", File(tmp.root, "artwork/$S1").exists())
        assertTrue("another server's entry stays", has(S2, "art:$S2:keep"))
        assertTrue("the memory entries went too", loader.memoryCache!!.keys.none { it.key.startsWith("art:$S1:") })
        assertNull("disk entry gone", cached(ref(), 320))
        web.enqueue(image(bitmapBytes(), "image/jpeg"))
        loaded(ref(), memory = true)
        assertEquals("the memory entry went too, so it was fetched again", 2, web.requestCount)
    }

    @Test fun anItemsPicturesLeaveMemoryWhenTheyChangeAndNothingElseDoes() {
        web.enqueue(image(bitmapBytes(), "image/jpeg"))
        loaded(ref(), memory = true)
        put(S1, "art:$S1:other")
        eviction().evictItem(S1, ref().itemId)
        assertTrue(loader.memoryCache!!.keys.none { it.key.startsWith("art:$S1:${ref().itemId}:") })
        assertTrue("the server's cache stays", has(S1, "art:$S1:other"))
        eviction().evictItem(S1, "anything") // nothing there: no failure
    }

    @Test fun signingOutOneUserKeepsThePicturesWhileAnotherAccountRemains() {
        web.enqueue(image(bitmapBytes(), "image/jpeg"))
        loaded(ref())
        accountsLeft = true
        eviction().onAccountGone(S1)
        assertNotNull(cached(ref(), 320))
    }

    @Test fun signingOutTheLastAccountClearsTheServersPictures() {
        web.enqueue(image(bitmapBytes(), "image/jpeg"))
        loaded(ref())
        accountsLeft = false
        eviction().onAccountGone(S1)
        assertNull(cached(ref(), 320))
    }

    @Test fun aFailingEvictionIsSwallowedAndLoggedNotThrown() {
        val broken = ArtworkEviction(caches, { error("no memory cache") }, { error("no store") }, unconfined, Dispatchers.Unconfined)
        broken.onAccountGone(testId("x"))
        broken.onServerRemoved(testId("x"))
        runBlocking { broken.evict(testId("x")) }
    }

    /** Security review M8 F8 (Milestone 2 L2): a removed server's folder left by a process that died is deleted at the next start. */
    @Test fun theStartSweepDeletesTheFoldersOfServersThatNoLongerExist() {
        put(S1, "art:$S1:keep"); put(S2, "art:$S2:gone")
        val stray = File(tmp.root, "artwork/not-an-id").apply { mkdirs(); File(this, "x").writeText("x") }
        runBlocking { eviction().sweep { setOf(S1) }.join() }
        assertTrue("a server that exists keeps its pictures", has(S1, "art:$S1:keep"))
        assertFalse("a server that is gone loses its folder", File(tmp.root, "artwork/$S2").exists())
        assertFalse("anything else in the folder goes too", stray.exists())
    }

    @Test fun aSweepThatCannotReadTheServersDeletesNothing() {
        put(S1, "art:$S1:keep")
        runBlocking { eviction().sweep { throw IllegalStateException("db") }.join() }
        assertTrue(has(S1, "art:$S1:keep"))
    }
}
