package app.shoalwatch.data.library.api

import app.shoalwatch.core.Ticks
import app.shoalwatch.core.displayParagraphs
import app.shoalwatch.core.displayText
import app.shoalwatch.core.serverInstant
import app.shoalwatch.data.api.JsonBodies
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemFilter
import app.shoalwatch.data.library.ItemImages
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.LibraryKind
import app.shoalwatch.data.library.LibraryView
import app.shoalwatch.data.library.Page
import app.shoalwatch.data.library.Sort
import app.shoalwatch.data.library.SortBy
import app.shoalwatch.data.library.UserData
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.sync.ServerUserData
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant

// DTO -> model. This is the S11 boundary: nothing leaves here unvalidated. See "Library endpoints" in docs/ARCHITECTURE.md.

internal object Limits {
    const val NAME = 200
    const val OFFICIAL_RATING = 20
    const val OVERVIEW = 4000
    const val INDEX_MAX = 100_000
    const val COUNT_MAX = 1_000_000
    const val TOTAL_MAX = 1_000_000
    const val MAX_VIEWS = 100
    const val MAX_SEASONS = 1000
    const val PLAY_COUNT_MAX = 100_000
    const val POSITION_TICKS_MAX = Long.MAX_VALUE / 10_000
    /** 48 hours, as the playback and admin runtimes (security review M8 F10): anything longer is unknown. */
    val RUNTIME_TICKS_MAX = Ticks.fromMs(48L * 60 * 60 * 1000)
    val LAST_PLAYED_RANGE: ClosedRange<Instant> = Instant.parse("2000-01-01T00:00:00Z")..Instant.parse("2200-01-01T00:00:00Z")
}

private val IMAGE_TAG = Regex("[A-Za-z0-9]{1,64}")

private fun id(raw: String?): String? = JellyfinId.parseOrNull(raw)?.value

private fun intIn(value: Long?, range: IntRange): Int? = value?.takeIf { it in range.first..range.last }?.toInt()

private fun tag(raw: String?): String? = raw?.takeIf { IMAGE_TAG.matches(it) }

/** Lenient in form, strict in range (1800-2200, security review M8 F3): anything else is null, never a crash. */
private fun instant(raw: String?): Instant? = serverInstant(raw)

internal fun kindOf(type: String?): ItemKind = when (type) {
    "Movie" -> ItemKind.MOVIE
    "Series" -> ItemKind.SERIES
    "Season" -> ItemKind.SEASON
    "Episode" -> ItemKind.EPISODE
    "BoxSet" -> ItemKind.BOX_SET
    "Folder", "CollectionFolder", "UserView" -> ItemKind.FOLDER
    else -> ItemKind.OTHER
}

// ---- one element at a time: a wrong-typed element is skipped, never fatal to the list

private inline fun <T : Any> tolerant(decode: () -> T): T? = try {
    decode()
} catch (_: IllegalArgumentException) { // SerializationException is one; the element is untrusted and never echoed
    null
}

internal fun JsonElement.toItemDto(): BaseItemDto? = tolerant { JsonBodies.JSON.decodeFromJsonElement(BaseItemDto.serializer(), this) }

private fun JsonElement.toUserDataDto(): UserItemDataDto? = tolerant { JsonBodies.JSON.decodeFromJsonElement(UserItemDataDto.serializer(), this) }

/** Null (the item is dropped) when its own id is not a GUID. */
internal fun BaseItemDto.toItem(): Item? {
    val itemId = id(id) ?: return null
    // An item that is its own parent, series or season is nonsense from the server: that link is dropped.
    fun related(raw: String?) = id(raw)?.takeIf { it != itemId }
    return Item(
        id = itemId,
        kind = kindOf(type),
        name = displayText(name, Limits.NAME),
        sortName = displayText(sortName, Limits.NAME).ifEmpty { null },
        parentId = related(parentId),
        seriesId = related(seriesId),
        seriesName = displayText(seriesName, Limits.NAME).ifEmpty { null },
        seasonId = related(seasonId),
        indexNumber = intIn(indexNumber, 0..Limits.INDEX_MAX),
        parentIndexNumber = intIn(parentIndexNumber, 0..Limits.INDEX_MAX),
        overview = displayParagraphs(overview, Limits.OVERVIEW).ifEmpty { null },
        runtimeMs = runTimeTicks?.takeIf { it in 0..Limits.RUNTIME_TICKS_MAX }?.let(Ticks::toMs),
        year = intIn(productionYear, 1800..2200),
        premiereDate = instant(premiereDate),
        communityRating = communityRating?.takeIf { it in 0.0..10.0 }?.toFloat(),
        officialRating = displayText(officialRating, Limits.OFFICIAL_RATING).ifEmpty { null },
        childCount = intIn(childCount, 0..Limits.COUNT_MAX),
        images = ItemImages(
            primaryTag = tag(imageTags?.get("Primary")),
            backdropTag = tag(backdropImageTags?.firstOrNull()),
            thumbTag = tag(imageTags?.get("Thumb")),
            seriesPrimaryTag = tag(seriesPrimaryImageTag),
        ),
    )
}

internal fun UserItemDataDto.toUserData(): UserData {
    val watched = played == true
    return UserData(
        played = watched,
        // A watched item has no place to resume from, whatever stale position the server still holds.
        positionMs = if (watched) 0L else playbackPositionTicks?.takeIf { it >= 0 }?.let(Ticks::toMs) ?: 0L,
        playedPercentage = if (watched) null else playedPercentage?.takeIf { it in 0.0..100.0 }?.toFloat(),
        playCount = intIn(playCount, 0..Int.MAX_VALUE) ?: 0,
        favourite = isFavorite == true,
        lastPlayed = instant(lastPlayedDate),
        unplayedItemCount = intIn(unplayedItemCount, 0..Limits.COUNT_MAX),
    )
}

/**
 * The server's copy for the outbox (Milestone 5), range-checked: `PlayCount` 0..100,000 else unknown, position 0..`Long.MAX_VALUE / 10,000`
 * ticks else 0, `LastPlayedDate` 2000-01-01..2200-01-01 else unknown.
 */
internal fun UserItemDataDto.toServerUserData(): ServerUserData = ServerUserData(
    played = played == true,
    playCount = intIn(playCount, 0..Limits.PLAY_COUNT_MAX),
    positionMs = playbackPositionTicks?.takeIf { it in 0..Limits.POSITION_TICKS_MAX }?.let(Ticks::toMs) ?: 0L,
    favourite = isFavorite == true,
    lastPlayedAt = instant(lastPlayedDate)?.takeIf { it in Limits.LAST_PLAYED_RANGE }?.toEpochMilli(),
)

/** The outbox's copy from an item's own UserData; null when it has none that can be read. */
internal fun BaseItemDto.toServerUserData(): ServerUserData? = userData?.toUserDataDto()?.toServerUserData()

/** The item survives a UserData that cannot be read: it just has none. */
internal fun BaseItemDto.toItemWithUserData(): ItemWithUserData? =
    toItem()?.let { ItemWithUserData(it, userData?.toUserDataDto()?.toUserData()) }

/** Valid items only, the first of any repeated id, at most [limit]. A bad or repeated element never uses up the limit. */
internal fun List<JsonElement>?.toModels(limit: Int): List<ItemWithUserData> =
    orEmpty().asSequence().mapNotNull { it.toItemDto()?.toItemWithUserData() }.distinctBy { it.item.id }.take(limit).toList()

internal fun BaseItemDtoQueryResult.toModels(limit: Int): List<ItemWithUserData> = items.toModels(limit)

/** How many elements the server sent in this response, before any is skipped, repeated or capped. */
internal fun BaseItemDtoQueryResult.rawCount(): Int = items?.size ?: 0

/**
 * The server's own count of how many items there are, when it gave a usable one: a number that is not negative, is not
 * zero while elements came back, and is not smaller than the elements already seen (`start` plus this response's
 * [rawCount]). Anything else is nonsense and counts as unknown (null).
 */
internal fun BaseItemDtoQueryResult.usableTotal(start: Int): Long? {
    val reported = (totalRecordCount as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
    val raw = rawCount()
    return reported.takeIf { it >= 0 && (raw == 0 || it > 0) && it >= start.toLong() + raw }
}

/**
 * [Page.nextStartIndex] counts every element the server sent, so a caller never re-fetches a skipped or repeated one.
 * The end is reached when the server sent nothing, or its usable total is reached, or (no usable total) it sent fewer
 * than [limit].
 */
internal fun BaseItemDtoQueryResult.toPage(start: Int, limit: Int): Page<ItemWithUserData> {
    val raw = rawCount()
    val next = start + raw
    val total = usableTotal(start)
    val end = raw == 0 || (total != null && next >= total) || (total == null && raw < limit)
    return Page(toModels(limit), start, total?.coerceAtMost(Limits.TOTAL_MAX.toLong())?.toInt(), next, end)
}

/** Only films, TV, collections and mixed libraries; other kinds (music, books, ...) and invalid ids are skipped. */
internal fun BaseItemDtoQueryResult.toViews(): List<LibraryView> =
    items.orEmpty().asSequence().mapNotNull { it.toItemDto() }.mapNotNull { dto ->
        val viewId = id(dto.id) ?: return@mapNotNull null
        val kind = when (dto.collectionType) {
            "movies" -> LibraryKind.MOVIES
            "tvshows" -> LibraryKind.TV
            "boxsets" -> LibraryKind.BOX_SETS
            null, "mixed" -> LibraryKind.MIXED
            else -> return@mapNotNull null
        }
        LibraryView(viewId, displayText(dto.name, Limits.NAME), kind)
    }.distinctBy { it.id }.take(Limits.MAX_VIEWS).toList()

// ---- model -> request values (the reference's enum spellings)

internal val SortBy.wire: String
    get() = when (this) {
        SortBy.NAME -> "SortName"
        SortBy.DATE_ADDED -> "DateCreated"
        SortBy.RELEASE_DATE -> "PremiereDate"
        SortBy.RATING -> "CommunityRating"
    }

/**
 * `sortBy` and `sortOrder` are array parameters, sent comma-delimited as the reference describes, one order per key.
 * `SortName` (ascending) is always the last key so that equal values (many films share a year or rating) keep a fixed order.
 */
internal fun Sort.sortByWire(): String = if (by == SortBy.NAME) by.wire else "${by.wire},SortName"

internal fun Sort.sortOrderWire(): String {
    val order = if (descending) "Descending" else "Ascending"
    return if (by == SortBy.NAME) order else "$order,Ascending"
}

/** The reference's `ItemFilter` values, comma-delimited; empty when no filter applies. */
internal fun ItemFilter.wire(): String = listOfNotNull("IsUnplayed".takeIf { unwatchedOnly }, "IsFavorite".takeIf { favouritesOnly }).joinToString(",")

/** `BaseItemKind` values; [ItemKind.OTHER] has none and is left out. */
internal fun Set<ItemKind>.wire(): String = mapNotNull {
    when (it) {
        ItemKind.MOVIE -> "Movie"
        ItemKind.SERIES -> "Series"
        ItemKind.SEASON -> "Season"
        ItemKind.EPISODE -> "Episode"
        ItemKind.BOX_SET -> "BoxSet"
        ItemKind.FOLDER -> "Folder"
        ItemKind.OTHER -> null
    }
}.sorted().joinToString(",")
