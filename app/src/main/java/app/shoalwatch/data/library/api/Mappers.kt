package app.shoalwatch.data.library.api

import app.shoalwatch.core.Ticks
import app.shoalwatch.core.displayText
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
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

// DTO -> model. This is the S11 boundary: nothing leaves here unvalidated. See "Library endpoints" in docs/ARCHITECTURE.md.

internal object Limits {
    const val NAME = 200
    const val OFFICIAL_RATING = 20
    const val OVERVIEW = 4000
    const val INDEX_MAX = 100_000
    const val COUNT_MAX = 1_000_000
    const val TOTAL_MAX = 1_000_000
    const val MAX_VIEWS = 100
    const val MAX_UNPAGED = 1000
    const val DEFAULT_NAME = "Untitled"
    const val DEFAULT_VIEW_NAME = "Library"
}

private val IMAGE_TAG = Regex("[A-Za-z0-9]{1,64}")

private fun id(raw: String?): String? = JellyfinId.parseOrNull(raw)?.value

private fun intIn(value: Long?, range: IntRange): Int? = value?.takeIf { it in range.first..range.last }?.toInt()

private fun tag(raw: String?): String? = raw?.takeIf { IMAGE_TAG.matches(it) }

/** Lenient: Jellyfin's seven fractional digits, or an offset; anything else is null, never a crash. */
private fun instant(raw: String?): Instant? {
    if (raw.isNullOrBlank() || raw.length > 64) return null
    return try {
        Instant.parse(raw)
    } catch (_: DateTimeParseException) {
        try {
            OffsetDateTime.parse(raw).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

/** Plain text that keeps line breaks (at most one blank line in a row): everything else unusual goes, as in [displayText]. */
internal fun overviewText(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { displayText(it, Limits.OVERVIEW) }
    val kept = ArrayList<String>()
    for (line in lines) if (line.isNotEmpty() || (kept.isNotEmpty() && kept.last().isNotEmpty())) kept.add(line)
    val text = kept.joinToString("\n").trim()
    if (text.isEmpty()) return null
    return if (text.codePointCount(0, text.length) <= Limits.OVERVIEW) text else text.substring(0, text.offsetByCodePoints(0, Limits.OVERVIEW)).trimEnd()
}

internal fun kindOf(type: String?): ItemKind = when (type) {
    "Movie" -> ItemKind.MOVIE
    "Series" -> ItemKind.SERIES
    "Season" -> ItemKind.SEASON
    "Episode" -> ItemKind.EPISODE
    "BoxSet" -> ItemKind.BOX_SET
    "Folder", "CollectionFolder", "UserView" -> ItemKind.FOLDER
    else -> ItemKind.OTHER
}

/** Null (the item is dropped) when its own id is not a GUID. */
internal fun BaseItemDto.toItem(): Item? {
    val itemId = id(id) ?: return null
    return Item(
        id = itemId,
        kind = kindOf(type),
        name = displayText(name, Limits.NAME, fallback = Limits.DEFAULT_NAME),
        sortName = displayText(sortName, Limits.NAME).ifEmpty { null },
        parentId = id(parentId),
        seriesId = id(seriesId),
        seriesName = displayText(seriesName, Limits.NAME).ifEmpty { null },
        seasonId = id(seasonId),
        indexNumber = intIn(indexNumber, 0..Limits.INDEX_MAX),
        parentIndexNumber = intIn(parentIndexNumber, 0..Limits.INDEX_MAX),
        overview = overviewText(overview),
        runtimeMs = runTimeTicks?.takeIf { it >= 0 }?.let(Ticks::toMs),
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

internal fun UserItemDataDto.toUserData() = UserData(
    played = played == true,
    positionMs = playbackPositionTicks?.takeIf { it >= 0 }?.let(Ticks::toMs) ?: 0L,
    playedPercentage = playedPercentage?.takeIf { it in 0.0..100.0 }?.toFloat(),
    playCount = intIn(playCount, 0..Int.MAX_VALUE) ?: 0,
    favourite = isFavorite == true,
    lastPlayed = instant(lastPlayedDate),
    unplayedItemCount = intIn(unplayedItemCount, 0..Limits.COUNT_MAX),
)

internal fun BaseItemDto.toItemWithUserData(): ItemWithUserData? = toItem()?.let { ItemWithUserData(it, userData?.toUserData()) }

/** Items that are valid, at most [limit] of them. */
internal fun List<BaseItemDto?>?.toModels(limit: Int): List<ItemWithUserData> =
    orEmpty().asSequence().filterNotNull().mapNotNull { it.toItemWithUserData() }.take(limit).toList()

internal fun BaseItemDtoQueryResult.toModels(limit: Int): List<ItemWithUserData> = items.toModels(limit)

internal fun BaseItemDtoQueryResult.toPage(start: Int, limit: Int): Page<ItemWithUserData> {
    val list = items.toModels(limit)
    return Page(list, start, (totalRecordCount ?: list.size.toLong()).coerceIn(list.size.toLong(), Limits.TOTAL_MAX.toLong()).toInt())
}

/** Only films, TV, collections and mixed libraries; other kinds (music, books, ...) and invalid ids are skipped. */
internal fun BaseItemDtoQueryResult.toViews(): List<LibraryView> =
    items.orEmpty().asSequence().filterNotNull().mapNotNull { dto ->
        val viewId = id(dto.id) ?: return@mapNotNull null
        val kind = when (dto.collectionType) {
            "movies" -> LibraryKind.MOVIES
            "tvshows" -> LibraryKind.TV
            "boxsets" -> LibraryKind.BOX_SETS
            null, "mixed" -> LibraryKind.MIXED
            else -> return@mapNotNull null
        }
        LibraryView(viewId, displayText(dto.name, Limits.NAME, fallback = Limits.DEFAULT_VIEW_NAME), kind)
    }.take(Limits.MAX_VIEWS).toList()

// ---- model -> request values (the reference's enum spellings)

internal val SortBy.wire: String
    get() = when (this) {
        SortBy.NAME -> "SortName"
        SortBy.DATE_ADDED -> "DateCreated"
        SortBy.RELEASE_DATE -> "PremiereDate"
        SortBy.RATING -> "CommunityRating"
    }

internal val Sort.order: String get() = if (descending) "Descending" else "Ascending"

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
