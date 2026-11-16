package app.shoalwatch.data.images

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkFetcherTest : ArtworkTestBase() {
    private fun serve(vararg responses: MockResponse) = responses.forEach { web.enqueue(it) }

    @Test fun requestsThePrimaryImageWithTheBucketTagQualityAndFormatAndTheTokenInTheHeaderOnly() {
        serve(image(bitmapBytes(), "image/jpeg"))
        val image = loaded(ref("Tag9"), widthPx = 300)
        assertEquals(40, image.width)
        val request = web.takeRequest()
        val url = request.requestUrl!!
        assertEquals("GET", request.method)
        assertEquals(listOf("Items", item, "Images", "Primary"), url.pathSegments)
        assertEquals("Tag9", url.queryParameter("tag"))
        assertEquals("320", url.queryParameter("maxWidth"))
        assertEquals("90", url.queryParameter("quality"))
        assertEquals("Webp", url.queryParameter("format"))
        assertEquals(setOf("tag", "maxWidth", "quality", "format"), url.queryParameterNames)
        assertTrue(request.getHeader("Authorization")!!.contains("Token=\"tok-1\""))
        assertTrue(!request.path!!.contains("tok-1"))
    }

    @Test fun backdropAndThumbUseTheirOwnImageTypes() {
        serve(image(bitmapBytes(), "image/jpeg"), image(bitmapBytes(), "image/jpeg"))
        loaded(ref("b", ArtworkType.BACKDROP)); loaded(ref("t", ArtworkType.THUMB))
        assertEquals("Backdrop", web.takeRequest().requestUrl!!.pathSegments.last())
        assertEquals("Thumb", web.takeRequest().requestUrl!!.pathSegments.last())
    }

    @Test fun theRequestedSizeBecomesAWidthBucket() {
        val wanted = listOf(10 to "160", 160 to "160", 200 to "320", 400 to "480", 600 to "720", 900 to "1280", 4000 to "1280", null to "480")
        for ((i, pair) in wanted.withIndex()) {
            serve(image(bitmapBytes(), "image/jpeg"))
            loaded(ref("t$i"), widthPx = pair.first)
            assertEquals("for ${pair.first}", pair.second, web.takeRequest().requestUrl!!.queryParameter("maxWidth"))
        }
    }

    @Test fun aSecondLoadComesFromTheDiskCacheWithNoRequest() {
        serve(image(bitmapBytes(), "image/jpeg"))
        loaded(ref()); loaded(ref())
        assertEquals(1, web.requestCount)
        assertNotNull(cached(ref(), 320))
    }

    @Test fun theCacheKeyIsNotTheAddressSoSwitchingAddressesStillHits() {
        serve(image(bitmapBytes(), "image/jpeg"))
        loaded(ref())
        val other = MockWebServer().apply { start() }
        try {
            moveServerTo(other)
            loaded(ref())
            assertEquals(1, web.requestCount)
            assertEquals("the other address was never asked", 0, other.requestCount)
        } finally { other.shutdown() }
    }

    @Test fun aNewTagOrANewBucketIsAMiss() {
        serve(image(bitmapBytes(), "image/jpeg"), image(bitmapBytes(), "image/jpeg"), image(bitmapBytes(), "image/jpeg"))
        loaded(ref("old")); loaded(ref("new")); loaded(ref("new"), widthPx = 700)
        assertEquals(3, web.requestCount)
        assertNotNull(cached(ref("old"), 320)); assertNotNull(cached(ref("new"), 320)); assertNotNull(cached(ref("new"), 720))
        loaded(ref("new"), widthPx = 700)
        assertEquals("the same ref and bucket again is a hit", 3, web.requestCount)
    }

    @Test fun anotherUserOfTheSameServerReusesTheEntry() {
        serve(image(bitmapBytes(), "image/jpeg"))
        loaded(ref())
        runBlocking { store.upsertAccount(app.shoalwatch.data.servers.Account(session.serverId, app.shoalwatch.U2, "amy", "tok-2"), makeActive = false) }
        val other = ArtworkRef.of(app.shoalwatch.data.servers.Session(session.serverId, app.shoalwatch.U2), item, ArtworkType.PRIMARY, "tag1")!!
        loaded(other)
        assertEquals(1, web.requestCount)
    }

    @Test fun offlineWithNothingSavedIsAnErrorAndNothingIsSent() {
        selector.answer = { null }
        val error = failed(ref())
        assertEquals(0, web.requestCount)
        assertEquals("OFFLINE", (error as app.shoalwatch.core.AppError).kind.name)
        assertEquals("asked once: no retry loop", 1, selector.asks.get())
    }

    @Test fun offlineWithASavedImageShowsIt() {
        serve(image(bitmapBytes(), "image/jpeg"))
        loaded(ref())
        selector.answer = { null }
        assertEquals(40, loaded(ref()).width)
        assertEquals(1, web.requestCount)
    }

    private fun pinnedLoader(): PinnedArtwork {
        val pinned = PinnedArtwork(db.downloadDao(), app.shoalwatch.data.downloads.DownloadPaths(app.shoalwatch.data.downloads.FakeVolumes(tmp.newFolder("dl")).volumes))
        loader.shutdown()
        loader = ArtworkLoader.build(context, api, signedIn, caches, pinned = pinned)
        runBlocking { pinned.save(session.serverId, item, app.shoalwatch.data.downloads.ArtworkSlot.PRIMARY, "tag1", bitmapBytes()) }
        return pinned
    }

    @Test fun onlineWithAPinnedCopyForTheSameTagNoRequestIsSent() {
        pinnedLoader()
        assertEquals(40, loaded(ref()).width)
        assertEquals(0, web.requestCount)
    }

    @Test fun aSignedOutSessionIsNotSignedInBeforeThePinnedCopyIsLookedAt() {
        pinnedLoader()
        runBlocking { store.removeAccount(session.serverId, session.userId) }
        assertEquals(app.shoalwatch.core.AppError.Kind.NOT_SIGNED_IN, (failed(ref()) as app.shoalwatch.core.AppError).kind)
        assertEquals(0, web.requestCount)
    }

    @Test fun aNetworkFailureIsAnErrorResultNotAThrow() {
        web.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        web.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        failed(ref())
        assertEquals("ServerApi retries a lost connection once; the fetcher adds nothing", 2, web.requestCount)
        assertNull(cached(ref(), 320))
    }

    @Test fun cancellingStopsTheFetchAndLeavesNothingCached() {
        web.enqueue(image(bitmapBytes(), "image/jpeg").setBodyDelay(30, TimeUnit.SECONDS))
        runBlocking {
            val job = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { loader.execute(request(ref())) }
            assertNotNull(web.takeRequest(10, TimeUnit.SECONDS))
            val started = System.nanoTime()
            withTimeout(10_000) { job.cancelAndJoin() }
            assertTrue("cancelled promptly, not after the 30 s body delay", System.nanoTime() - started < 8_000_000_000L)
        }
        assertNull(cached(ref(), 320))
    }

    @Test fun aServerThatIgnoresMaxWidthIsDecodedNoWiderThanTwiceTheBucket() {
        serve(image(bitmapBytes(2400, 1600, Bitmap.CompressFormat.PNG), "image/png"))
        val image = loaded(ref(), widthPx = 100)
        assertTrue("decoded ${image.width} wide for the 160 bucket", image.width <= 320 && image.width >= 160)
    }

    @Test fun pngAndWebpAreAccepted() {
        serve(image(bitmapBytes(30, 45, Bitmap.CompressFormat.PNG), "image/png"), image(bitmapBytes(30, 45, Bitmap.CompressFormat.WEBP_LOSSY), "IMAGE/WEBP; q=1"))
        assertEquals(30, loaded(ref("a")).width)
        assertEquals(30, loaded(ref("b")).width)
    }

    @Test fun aDamagedSavedEntryIsDroppedAndFetchedAgain() {
        val r = ref()
        val editor = diskOf(r).openEditor(ArtworkLimits.cacheKey(r, 320))!!
        diskOf(r).fileSystem.write(editor.data) { write(pngDeclaring(20000, 20000)) }
        editor.commit()
        serve(image(bitmapBytes(), "image/jpeg"))
        assertEquals(40, loaded(r).width)
        assertEquals("the bad entry was not shown; the server was asked", 1, web.requestCount)
    }
}
