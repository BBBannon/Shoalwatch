package app.shoalwatch.data.library

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.data.images.ArtworkLimits
import app.shoalwatch.data.images.ArtworkType
import app.shoalwatch.data.library.api.BaseItemDtoQueryResult
import app.shoalwatch.data.library.api.kindOf
import app.shoalwatch.data.library.api.toViews
import app.shoalwatch.data.library.api.wire
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.images.photoArtwork
import app.shoalwatch.ui.present.Destination
import app.shoalwatch.ui.present.destination
import coil3.size.Size
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Milestone 11: photo and home-video libraries, photos, videos and playlists as kinds, and the photo picture's size. */
class NewKindsTest {
    private fun views(vararg types: String?) = BaseItemDtoQueryResult(
        types.mapIndexed { i, t -> Json.parseToJsonElement("""{"Id":"${"%032x".format(i + 1)}","Name":"L$i"${t?.let { ",\"CollectionType\":\"$it\"" } ?: ""}}""") as JsonElement },
    ).toViews()

    @Test fun photoAndHomeVideoLibrariesAreOfferedAndNothingElseNew() {
        val kinds = views("photos", "homevideos", "music", "books", "livetv", "playlists", "movies").map { it.kind }
        assertEquals(listOf(LibraryKind.PHOTOS, LibraryKind.HOME_VIDEOS, LibraryKind.MOVIES), kinds)
    }

    @Test fun photosVideosAlbumsAndPlaylistsHaveTheirOwnKinds() {
        assertEquals(ItemKind.PHOTO, kindOf("Photo"))
        assertEquals(ItemKind.VIDEO, kindOf("Video"))
        assertEquals(ItemKind.PLAYLIST, kindOf("Playlist"))
        assertEquals("an album opens like a folder", ItemKind.FOLDER, kindOf("PhotoAlbum"))
        assertEquals(ItemKind.OTHER, kindOf("MusicAlbum"))
        assertEquals("Photo,Playlist,Video", setOf(ItemKind.PHOTO, ItemKind.VIDEO, ItemKind.PLAYLIST).wire())
    }

    @Test fun photoAndHomeVideoLibrariesListTheirDirectChildren() {
        assertEquals(ListShape.DIRECT, ListShape.forLibrary(LibraryKind.PHOTOS))
        assertEquals(ListShape.DIRECT, ListShape.forLibrary(LibraryKind.HOME_VIDEOS))
    }

    private fun item(kind: ItemKind, parent: String? = null) = ItemWithUserData(
        Item("a".repeat(32), kind, "x", null, parent, null, null, null, null, null, null, null, null, null, null, null, null, ItemImages("t1", null, null, null)),
        null,
    )

    @Test fun aPhotoOpensTheViewerAVideoItsPageAPlaylistItsOwnPage() {
        assertEquals(Destination.Photo("a".repeat(32), "b".repeat(32)), item(ItemKind.PHOTO, "b".repeat(32)).destination())
        assertEquals(Destination.Detail("a".repeat(32)), item(ItemKind.VIDEO).destination())
        assertEquals(Destination.Playlist("a".repeat(32)), item(ItemKind.PLAYLIST).destination())
    }

    @Test fun aPhotoIsAskedForAtScreenSizeWithinASquare() {
        assertEquals(1920, ArtworkLimits.bucketFor(ArtworkType.PHOTO, Size(1080, 2340)))
        assertEquals(1280, ArtworkLimits.bucketFor(ArtworkType.PHOTO, Size(800, 1200)))
        assertEquals("never past 1,920", 1920, ArtworkLimits.bucketFor(ArtworkType.PHOTO, Size(4000, 3000)))
        assertEquals("a poster keeps its own buckets", 1280, ArtworkLimits.bucketFor(ArtworkType.PRIMARY, Size(1080, 2340)))
        val q = ArtworkLimits.query(ArtworkType.PHOTO, "t1", 1920, "Webp")
        assertEquals(listOf("tag" to "t1", "maxWidth" to "1920", "maxHeight" to "1920", "quality" to "90", "format" to "Webp"), q)
        assertEquals("no maxHeight for a poster", null, ArtworkLimits.query(ArtworkType.PRIMARY, "t1", 320, "Webp").toMap()["maxHeight"])
        val ref = item(ItemKind.PHOTO).item.photoArtwork(Session(S1, U1))
        assertEquals(ArtworkType.PHOTO, ref?.type)
        assertNull(item(ItemKind.PHOTO).item.copy(images = ItemImages(null, null, null, null)).photoArtwork(Session(S1, U1)))
    }
}
