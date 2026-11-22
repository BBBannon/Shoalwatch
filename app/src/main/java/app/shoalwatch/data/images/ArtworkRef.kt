package app.shoalwatch.data.images

import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session

/** Which picture of an item. [path] is the `ImageType` value in the Jellyfin 12.4.0 reference (`GET /Items/{itemId}/Images/{imageType}`). */
enum class ArtworkType(val path: String) {
    PRIMARY("Primary"), BACKDROP("Backdrop"), THUMB("Thumb"),

    /** Milestone 11: a photo's own picture for the full-screen viewer (the server's Primary, at screen size: [ArtworkLimits.PHOTO_BUCKETS]). */
    PHOTO("Primary"),
}

/**
 * Which picture to show, as the UI knows it: no address, no URL. The only way to get one is [of], so every ref in the
 * app has a normalised item id and an alphanumeric tag. A plain class, not a data class, so there is no `copy` to build
 * an unchecked ref from a valid one.
 */
class ArtworkRef private constructor(val session: Session, val itemId: String, val type: ArtworkType, val tag: String) {
    override fun equals(other: Any?) = other is ArtworkRef && session == other.session && itemId == other.itemId && type == other.type && tag == other.tag
    override fun hashCode() = arrayOf(session, itemId, type, tag).contentHashCode()
    override fun toString() = "ArtworkRef($type)"

    companion object {
        /** Null unless [itemId] is already a normalised `JellyfinId` and [tag] matches `[A-Za-z0-9]{1,64}`. */
        fun of(session: Session, itemId: String, type: ArtworkType, tag: String): ArtworkRef? {
            if (JellyfinId.parseOrNull(itemId)?.value != itemId) return null
            if (!TAG.matches(tag)) return null
            return ArtworkRef(session, itemId, type, tag)
        }

        private val TAG = Regex("[A-Za-z0-9]{1,64}")
    }
}

/** The item's own poster; an episode with none shows its series' poster instead. */
fun Item.primaryArtwork(session: Session): ArtworkRef? {
    images.primaryTag?.let { tag -> ArtworkRef.of(session, id, ArtworkType.PRIMARY, tag)?.let { return it } }
    if (kind != ItemKind.EPISODE) return null
    val series = seriesId ?: return null
    val tag = images.seriesPrimaryTag ?: return null
    return ArtworkRef.of(session, series, ArtworkType.PRIMARY, tag)
}

fun Item.backdropArtwork(session: Session): ArtworkRef? = images.backdropTag?.let { ArtworkRef.of(session, id, ArtworkType.BACKDROP, it) }

fun Item.thumbArtwork(session: Session): ArtworkRef? = images.thumbTag?.let { ArtworkRef.of(session, id, ArtworkType.THUMB, it) }

/** Milestone 11: a photo at screen size, for the viewer; null when it has no picture. */
fun Item.photoArtwork(session: Session): ArtworkRef? = images.primaryTag?.let { ArtworkRef.of(session, id, ArtworkType.PHOTO, it) }
