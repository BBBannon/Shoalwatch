package app.shoalwatch.data.images

import app.shoalwatch.S1
import app.shoalwatch.core.AppError
import app.shoalwatch.data.downloads.ArtworkSlot
import app.shoalwatch.data.downloads.DownloadPaths
import app.shoalwatch.data.downloads.FakeVolumes
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemImages
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.testId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.robolectric.annotation.GraphicsMode
import org.junit.Test

/** Pictures kept with a download: found offline after a cache miss, re-checked on every read, never past the session check. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PinnedArtworkTest : ArtworkTestBase() {
    internal lateinit var paths: DownloadPaths
    internal lateinit var pinned: PinnedArtwork

    @Before fun pinnedLoader() {
        paths = DownloadPaths(FakeVolumes(tmp.newFolder("downloads")).volumes)
        pinned = PinnedArtwork(db.downloadDao(), paths, Dispatchers.IO)
        loader.shutdown()
        loader = ArtworkLoader.build(context, api, signedIn, caches, pinned = pinned)
    }

    private fun pin(itemId: String = item, slot: ArtworkSlot = ArtworkSlot.PRIMARY, tag: String = "tag1", bytes: ByteArray = bitmapBytes()) =
        runBlocking { pinned.save(S1, itemId, slot, tag, bytes) }

    @Test fun offlineWithNoCacheEntryAPinnedPictureDecodesWithNoRequest() {
        pin()
        selector.answer = { null }
        assertEquals(40, loaded(ref()).width)
        assertEquals(0, web.requestCount)
        assertNull("the pinned copy is not copied into the evictable cache", cached(ref(), 320))
    }

    @Test fun aTamperedPinnedFileIsRefusedDeletedAndThePlaceholderShows() {
        pin()
        val file = paths.artworkFile(S1, item, ArtworkSlot.PRIMARY, "tag1")
        file.writeBytes(file.readBytes().copyOf(file.length().toInt() / 2)) // cut short: no longer a complete picture
        selector.answer = { null }
        assertEquals(AppError.Kind.OFFLINE, (failed(ref()) as AppError).kind)
        assertFalse(file.exists())
        assertEquals(emptyMap<ArtworkSlot, String>(), runBlocking { db.downloadDao().pinnedArtwork(S1, item) })
    }

    @Test fun aPinnedPictureOfAnotherTagIsNotUsed() {
        pin(tag = "old")
        selector.answer = { null }
        failed(ref("new"))
        assertEquals(0, web.requestCount)
        assertTrue("an older tag is kept until replaced", paths.artworkFile(S1, item, ArtworkSlot.PRIMARY, "old").exists())
    }

    @Test fun anEpisodesFallbackToTheSeriesPosterFindsThePinnedSeriesPoster() {
        val series = testId("show")
        pin(itemId = series, tag = "sp1")
        val episode = Item(
            id = testId("e1"), kind = ItemKind.EPISODE, name = "E1", sortName = null, parentId = null, seriesId = series, seriesName = null,
            seasonId = null, indexNumber = 1, parentIndexNumber = 1, overview = null, runtimeMs = null, year = null, premiereDate = null,
            communityRating = null, officialRating = null, childCount = null, images = ItemImages(null, null, null, "sp1"),
        )
        selector.answer = { null }
        assertEquals(40, loaded(episode.primaryArtwork(session)!!).width)
        assertEquals(0, web.requestCount)
    }

    @Test fun savingANewTagReplacesTheOldFile() {
        pin(tag = "old")
        pin(tag = "new")
        assertFalse(paths.artworkFile(S1, item, ArtworkSlot.PRIMARY, "old").exists())
        assertTrue(paths.artworkFile(S1, item, ArtworkSlot.PRIMARY, "new").exists())
        assertEquals(mapOf(ArtworkSlot.PRIMARY to "new"), runBlocking { db.downloadDao().pinnedArtwork(S1, item) })
    }
}
