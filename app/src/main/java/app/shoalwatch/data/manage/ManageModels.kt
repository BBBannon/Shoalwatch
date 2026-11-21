package app.shoalwatch.data.manage

import java.time.LocalDate

/** A kind of picture an administrator can change. [wire] is the `ImageType` value in the 12.4.0 reference. */
enum class ImageKind(val wire: String) { PRIMARY("Primary"), BACKDROP("Backdrop"), LOGO("Logo"), THUMB("Thumb"), BANNER("Banner") }

/** What Identify searches as. Only films and series are offered (Milestone 10). [wire] is the `RemoteSearch/{type}` segment. */
enum class IdentifyKind(val wire: String) { MOVIE("Movie"), SERIES("Series") }

/** A field Jellyfin can lock against refreshes. [wire] is the `MetadataField` value in the 12.4.0 reference. */
enum class LockableField(val wire: String) {
    NAME("Name"), OVERVIEW("Overview"), GENRES("Genres"), TAGS("Tags"), STUDIOS("Studios"), OFFICIAL_RATING("OfficialRating"),
    CAST("Cast"), PRODUCTION_LOCATIONS("ProductionLocations"), RUNTIME("Runtime"),
}

/** The web dashboard's three refresh choices (see the plan, decision 11). */
enum class RefreshMode { SCAN, MISSING, REPLACE_ALL }

/** One picture the item has on the server now. [index] counts within its kind (backdrops can be several). */
data class ItemImage(val kind: ImageKind, val index: Int, val width: Int?, val height: Int?, val sizeBytes: Long?) {
    override fun toString() = "ItemImage($kind)"
}

/**
 * A picture the server's providers offer. Shown as text only: [source] is a third-party address (TMDb and others) the phone never
 * loads (S7); it is only handed back to the server in `RemoteImages/Download`, as a query value.
 */
class RemoteImage internal constructor(
    val kind: ImageKind,
    val providerName: String,
    val width: Int?,
    val height: Int?,
    val language: String?,
    val rating: Double?,
    val votes: Int?,
    internal val source: String,
) {
    override fun equals(other: Any?) = other is RemoteImage && kind == other.kind && source == other.source
    override fun hashCode() = 31 * kind.hashCode() + source.hashCode()
    override fun toString() = "RemoteImage($kind)"
}

/** The online pictures for one kind, and the providers the server asked. */
data class RemoteImages(val images: List<RemoteImage>, val providers: List<String>, val total: Int) {
    override fun toString() = "RemoteImages(${images.size})"
}

/**
 * One identify match. Its picture address (`ImageUrl`) is never kept: the phone does not load third-party pictures (S7).
 * [providerIds] are validated (key `[A-Za-z0-9_.-]{1,64}`, value printable, at most 200 characters) and re-encoded on apply.
 */
class IdentifyMatch internal constructor(
    val name: String,
    val year: Int?,
    val premiereDate: LocalDate?,
    val providerName: String,
    val overview: String?,
    internal val providerIds: Map<String, String>,
) {
    /** For lists: the same provider ids and name make the same match. */
    val key: String get() = "$providerName|" + providerIds.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
    override fun equals(other: Any?) = other is IdentifyMatch && key == other.key && name == other.name
    override fun hashCode() = key.hashCode()
    override fun toString() = "IdentifyMatch(<redacted>)"
}

/** The details an administrator can edit, as the server has them now. */
data class MetadataForm(
    val name: String,
    val sortName: String,
    val originalTitle: String,
    val overview: String,
    val year: Int?,
    val premiereDate: LocalDate?,
    val officialRating: String,
    val communityRating: Double?,
    val genres: List<String>,
    val tags: List<String>,
    val studios: List<String>,
    val locked: Set<LockableField>,
    val lockAll: Boolean,
    /** The age ratings the server knows (`MetadataEditor.ParentalRatingOptions`), for the picker. */
    val ratingOptions: List<String>,
) {
    override fun toString() = "MetadataForm(<redacted>)"
}

/** The form exactly as shown, as an edit: what a save sends when nothing was typed (and so changes nothing). */
fun MetadataForm.asEdit() = MetadataEdit(
    name, sortName, originalTitle, overview, year, premiereDate, officialRating, communityRating, genres, tags, studios, locked, lockAll,
)

/** What a save sends: typed values only, each bounded and cleaned before it is written over the server's own item. */
data class MetadataEdit(
    val name: String,
    val sortName: String,
    val originalTitle: String,
    val overview: String,
    val year: Int?,
    val premiereDate: LocalDate?,
    val officialRating: String,
    val communityRating: Double?,
    val genres: List<String>,
    val tags: List<String>,
    val studios: List<String>,
    val locked: Set<LockableField>,
    val lockAll: Boolean,
) {
    override fun toString() = "MetadataEdit(<redacted>)"
}

/** A picture ready to upload: JPEG bytes made on this phone (see `PickedImages`). */
class PreparedImage internal constructor(internal val jpeg: ByteArray, val width: Int, val height: Int) {
    override fun toString() = "PreparedImage(${width}x$height)"
}

/** Bounds shared by the gateway, the mappers and the screens. */
object ManageLimits {
    const val NAME = 200
    const val OVERVIEW = 8_000
    const val RATING = 20
    const val LIST_ENTRY = 100
    const val LIST_SIZE = 100
    const val RESULTS = 100
    const val REMOTE_IMAGES = 100
    const val ITEM_IMAGES = 100
    const val PROVIDER = 100
    const val LANGUAGE = 20
    const val URL = 2_048
    const val PROVIDER_IDS = 20
    const val PROVIDER_ID_VALUE = 200
    const val YEAR_MIN = 1800
    const val YEAR_MAX = 2200
    const val QUICK_CONNECT_CODE = 6
}
