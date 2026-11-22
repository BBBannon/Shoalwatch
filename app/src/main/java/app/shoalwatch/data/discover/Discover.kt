package app.shoalwatch.data.discover

import app.shoalwatch.data.images.ArtworkRef
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.ListShape
import app.shoalwatch.data.library.Page
import app.shoalwatch.data.library.Sort
import app.shoalwatch.data.servers.Session

/**
 * Discovery as the screens use it (Milestone 11): detail extras, person pages, filtered browsing, genre and studio pages and the
 * search extras. A token-free [Session] in, validated and bounded models out. **Live only**: nothing is cached; offline the calls
 * throw the `AppError` of the failure and the screen says "You're offline". Reads only. Ids must be normalised `JellyfinId`s, else
 * `SERVER_NOT_FOUND` with nothing sent; a filter or start index that cannot be used is an `AppError` of kind `UNKNOWN` with nothing sent.
 */
interface Discover {
    /**
     * Cast and crew, trailers, extras, similar items and versions of a film, episode, series or video. Each part is read on its own
     * and fails alone (empty); only when every part failed is the first failure thrown, so a screen can say it is offline.
     */
    suspend fun extras(s: Session, itemId: String): Extras

    /** The person's own page item (picture, overview). `SERVER_ERROR` when the server answers with a different item. */
    suspend fun person(s: Session, personId: String): Item

    /** The films and series a person appears in, [DiscoverLimits.PAGE] at a time from [startIndex]. */
    suspend fun personWorks(s: Session, personId: String, startIndex: Int): Page<ItemWithUserData>

    /** A library's items under [filter] (at most one value of each kind) in [sort] order, [DiscoverLimits.PAGE] at a time. */
    suspend fun browse(s: Session, parentId: String, shape: ListShape, filter: BrowseFilter, sort: Sort, startIndex: Int): Page<ItemWithUserData>

    /** The values a library can be filtered by. Each of the five lists fails on its own (empty). */
    suspend fun filterOptions(s: Session, parentId: String): FilterOptions

    /** Genres of a library ([parentId] null: the whole server), paged. */
    suspend fun genres(s: Session, parentId: String?, startIndex: Int): Page<Named>

    /** Studios of a library ([parentId] null: the whole server), paged. */
    suspend fun studios(s: Session, parentId: String?, startIndex: Int): Page<Named>

    /** People, genres and collections matching [term], [DiscoverLimits.SEARCH] each; each fails alone. A blank term sends nothing. */
    suspend fun searchMore(s: Session, term: String): SearchExtras
}

/** One person on a title or in a search. [role] is the character or job, [type] the server's kind (Actor, Director...). [artwork] is the server's own picture. */
class PersonRef(val id: String, val name: String, val role: String?, val type: String?, val artwork: ArtworkRef?) {
    override fun equals(other: Any?) = other is PersonRef && id == other.id && name == other.name && role == other.role && type == other.type && artwork == other.artwork
    override fun hashCode() = arrayOf(id, name, role, type, artwork).contentHashCode()
    override fun toString() = "PersonRef($id)"
}

/** A trailer on the internet: [url] is `http`/`https` only and is handed to Android, never fetched; [host] is for the button label. */
data class OnlineTrailer(val url: String, val host: String, val name: String?)

/** One file of a title. [name] is empty when the server gave none (the screen supplies the words). */
data class MediaVersion(val id: String, val name: String)

/** The extras of one item. [versions] has entries only when there is more than one. */
data class Extras(
    val cast: List<PersonRef>,
    val localTrailers: List<ItemWithUserData>,
    val onlineTrailers: List<OnlineTrailer>,
    val specialFeatures: List<ItemWithUserData>,
    val similar: List<ItemWithUserData>,
    val versions: List<MediaVersion>,
) {
    companion object { val NONE = Extras(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()) }
}

/**
 * At most one value of each. [letter] is "A" to "Z", or "#" for names before "A". [year] is 1800 to 2200; [rating] and [tag] are
 * plain text without comma or bar; genre and studio are normalised ids.
 */
data class BrowseFilter(
    val genreId: String? = null,
    val studioId: String? = null,
    val year: Int? = null,
    val rating: String? = null,
    val tag: String? = null,
    val letter: String? = null,
) {
    val isEmpty: Boolean get() = genreId == null && studioId == null && year == null && rating == null && tag == null && letter == null
}

/** A genre or studio (or any named thing with an id). */
data class Named(val id: String, val name: String)

data class FilterOptions(
    val years: List<Int>,
    val ratings: List<String>,
    val tags: List<String>,
    val genres: List<Named>,
    val studios: List<Named>,
)

data class SearchExtras(val people: List<PersonRef>, val genres: List<Named>, val collections: List<ItemWithUserData>) {
    companion object { val NONE = SearchExtras(emptyList(), emptyList(), emptyList()) }
}

/** Bounds shared by the gateway, the mappers and the screens. */
object DiscoverLimits {
    const val CAST = 50
    const val LOCAL_TRAILERS = 10
    const val ONLINE_TRAILERS = 10
    const val SPECIAL_FEATURES = 50
    const val SIMILAR = 12
    const val VERSIONS = 20
    const val PAGE = 100
    const val MAX_START = 100_000
    const val URL = 2_048
    const val HOST = 253
    const val NAME = 200
    const val TYPE = 30
    const val RATING = 20
    const val SEARCH = 10
    const val SEARCH_TERM = 100
    const val YEARS = 200
    const val RATINGS = 50
    const val TAGS = 200
    const val GENRES = 200
    const val STUDIOS = 200
}
