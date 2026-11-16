package app.shoalwatch.data.images

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemImages
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.Session
import app.shoalwatch.testId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkRefTest {
    private val session = Session(S1, U1)
    private val id = testId("film")

    @Test fun aValidRefKeepsItsParts() {
        val ref = ArtworkRef.of(session, id, ArtworkType.PRIMARY, "Abc123")!!
        assertEquals(id, ref.itemId)
        assertEquals(ArtworkType.PRIMARY, ref.type)
        assertEquals("Abc123", ref.tag)
        assertEquals(session, ref.session)
        assertEquals(ref, ArtworkRef.of(session, id, ArtworkType.PRIMARY, "Abc123"))
        assertEquals(ref.hashCode(), ArtworkRef.of(session, id, ArtworkType.PRIMARY, "Abc123").hashCode())
    }

    @Test fun anItemIdThatIsNotANormalisedJellyfinIdIsRefused() {
        val dashed = "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}"
        for (bad in listOf("", "film", id.uppercase(), dashed, "../$id", "$id/x", id.dropLast(1), "$id?x=1", " $id")) {
            assertNull(bad, ArtworkRef.of(session, bad, ArtworkType.PRIMARY, "tag"))
        }
    }

    @Test fun aTagMustBe1To64AlphanumericCharacters() {
        assertNotNull(ArtworkRef.of(session, id, ArtworkType.THUMB, "a".repeat(64)))
        assertNotNull(ArtworkRef.of(session, id, ArtworkType.THUMB, "0"))
        for (bad in listOf("", "a".repeat(65), "a b", "a/b", "a&x=1", "a?b", "é", "a\nb", "tag.jpg", "..", "a-b", "a_b")) {
            assertNull(bad, ArtworkRef.of(session, id, ArtworkType.THUMB, bad))
        }
    }

    @Test fun toStringCarriesNoIdsOrTags() {
        val text = ArtworkRef.of(session, id, ArtworkType.PRIMARY, "SecretTag")!!.toString()
        assertFalse(text.contains("SecretTag")); assertFalse(text.contains(id)); assertFalse(text.contains(S1))
    }

    @Test fun typesMapToTheReferenceImageTypeNames() {
        assertEquals(listOf("Primary", "Backdrop", "Thumb"), ArtworkType.entries.map { it.path })
    }

    private fun item(kind: ItemKind = ItemKind.MOVIE, images: ItemImages, seriesId: String? = null) = Item(
        id = id, kind = kind, name = "x", sortName = null, parentId = null, seriesId = seriesId, seriesName = null, seasonId = null,
        indexNumber = null, parentIndexNumber = null, overview = null, runtimeMs = null, year = null, premiereDate = null,
        communityRating = null, officialRating = null, childCount = null, images = images,
    )

    @Test fun itemHelpersBuildRefsFromTheItemsOwnTags() {
        val i = item(images = ItemImages("p1", "b1", "t1", null))
        assertEquals(ArtworkRef.of(session, id, ArtworkType.PRIMARY, "p1"), i.primaryArtwork(session))
        assertEquals(ArtworkRef.of(session, id, ArtworkType.BACKDROP, "b1"), i.backdropArtwork(session))
        assertEquals(ArtworkRef.of(session, id, ArtworkType.THUMB, "t1"), i.thumbArtwork(session))
        val none = item(images = ItemImages(null, null, null, null))
        assertNull(none.primaryArtwork(session)); assertNull(none.backdropArtwork(session)); assertNull(none.thumbArtwork(session))
    }

    @Test fun anEpisodeWithoutAPrimaryImageUsesTheSeriesPoster() {
        val series = testId("series")
        val episode = item(ItemKind.EPISODE, ItemImages(null, null, null, "s9"), seriesId = series)
        assertEquals(ArtworkRef.of(session, series, ArtworkType.PRIMARY, "s9"), episode.primaryArtwork(session))
        val own = item(ItemKind.EPISODE, ItemImages("own", null, null, "s9"), seriesId = series)
        assertEquals(ArtworkRef.of(session, id, ArtworkType.PRIMARY, "own"), own.primaryArtwork(session))
        assertNull("no series id, no fallback", item(ItemKind.EPISODE, ItemImages(null, null, null, "s9")).primaryArtwork(session))
        assertNull("only episodes fall back", item(ItemKind.SEASON, ItemImages(null, null, null, "s9"), seriesId = series).primaryArtwork(session))
        assertNull("the thumb has no fallback", episode.thumbArtwork(session))
    }
}
