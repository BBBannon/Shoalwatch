package app.shoalwatch.data.db.library

import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemImages
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.LibraryKind
import app.shoalwatch.data.library.LibraryView
import app.shoalwatch.data.library.UserData
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

private val accents = Regex("[\u0300-\u036F]+")

/**
 * The key offline search compares against, built the same way for the stored name and for the query: decomposed, ONLY the
 * combining accents U+0300 to U+036F removed (so "elite" finds "Élite" and "angstrom" finds "Ångström"; the dot of Turkish İ is
 * one of them), recomposed, Turkish dotless ı read as i, then lower-cased for every script, locale-independent. Other
 * combining marks are part of their letters and stay (Japanese voiced kana, Indic and Thai vowel signs), and Hangul is recomposed.
 */
internal fun searchKey(text: String): String =
    Normalizer.normalize(Normalizer.normalize(text, Normalizer.Form.NFD).replace(accents, ""), Normalizer.Form.NFC)
        .replace('ı', 'i').lowercase(Locale.ROOT)

internal fun Item.toEntity(serverId: String, fetchedAt: Instant) = ItemEntity(
    serverId = serverId, itemId = id, kind = kind, name = name, nameKey = searchKey(name), sortName = sortName,
    parentId = parentId, seriesId = seriesId, seriesName = seriesName, seasonId = seasonId,
    indexNumber = indexNumber, parentIndexNumber = parentIndexNumber, overview = overview, runtimeMs = runtimeMs,
    year = year, premiereDate = premiereDate?.toEpochMilli(), communityRating = communityRating,
    officialRating = officialRating, childCount = childCount,
    primaryTag = images.primaryTag, backdropTag = images.backdropTag, thumbTag = images.thumbTag,
    seriesPrimaryTag = images.seriesPrimaryTag, fetchedAt = fetchedAt.toEpochMilli(),
)

internal fun UserData.toEntity(serverId: String, userId: String, itemId: String, updatedAt: Instant) = UserDataEntity(
    serverId = serverId, userId = userId, itemId = itemId, played = played, positionMs = positionMs,
    playedPercentage = playedPercentage, playCount = playCount, favourite = favourite,
    lastPlayed = lastPlayed?.toEpochMilli(), unplayedItemCount = unplayedItemCount, updatedAt = updatedAt.toEpochMilli(),
)

fun ItemEntity.toModel() = Item(
    id = itemId, kind = kind, name = name, sortName = sortName, parentId = parentId, seriesId = seriesId,
    seriesName = seriesName, seasonId = seasonId, indexNumber = indexNumber, parentIndexNumber = parentIndexNumber,
    overview = overview, runtimeMs = runtimeMs, year = year, premiereDate = premiereDate?.let(Instant::ofEpochMilli),
    communityRating = communityRating, officialRating = officialRating, childCount = childCount,
    images = ItemImages(primaryTag, backdropTag, thumbTag, seriesPrimaryTag),
)

fun UserDataEntity.toModel() = UserData(
    played = played, positionMs = positionMs, playedPercentage = playedPercentage, playCount = playCount,
    favourite = favourite, lastPlayed = lastPlayed?.let(Instant::ofEpochMilli), unplayedItemCount = unplayedItemCount,
)

fun ItemRow.toModel() = ItemWithUserData(item.toModel(), userData?.toModel())

/** A kind this version does not know (written by a later version) reads as [LibraryKind.MIXED]. */
fun ViewEntity.toModel() = LibraryView(viewId, name, LibraryKind.entries.firstOrNull { it.name == kind } ?: LibraryKind.MIXED)

internal fun LibraryView.toEntity(serverId: String, userId: String, position: Int) =
    ViewEntity(serverId, userId, id, name, kind.name, position)
