package app.shoalwatch.data.discover.api

import app.shoalwatch.core.displayText
import app.shoalwatch.data.api.JsonBodies
import app.shoalwatch.data.discover.DiscoverLimits
import app.shoalwatch.data.discover.MediaVersion
import app.shoalwatch.data.discover.Named
import app.shoalwatch.data.discover.OnlineTrailer
import app.shoalwatch.data.discover.PersonRef
import app.shoalwatch.data.images.ArtworkRef
import app.shoalwatch.data.images.ArtworkType
import app.shoalwatch.data.library.Page
import app.shoalwatch.data.library.api.BaseItemDtoQueryResult
import app.shoalwatch.data.library.api.Limits
import app.shoalwatch.data.library.api.rawCount
import app.shoalwatch.data.library.api.toItemDto
import app.shoalwatch.data.library.api.usableTotal
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URISyntaxException

// The S11 boundary for discovery: bounded lists, ids checked, text cleaned, one bad element costs only itself.

private inline fun <T : Any> tolerant(decode: () -> T): T? = try {
    decode()
} catch (_: IllegalArgumentException) { // SerializationException is one; the element is untrusted and never echoed
    null
}

private fun <T : Any> JsonElement.decodeAs(serializer: DeserializationStrategy<T>): T? =
    tolerant { JsonBodies.JSON.decodeFromJsonElement(serializer, this) }

private fun idOf(raw: String?): String? = JellyfinId.parseOrNull(raw)?.value

private fun text(raw: String?, max: Int): String? = displayText(raw, max).ifEmpty { null }

// ---- cast

/** Cast and crew: valid people only (a real id and a name), at most [DiscoverLimits.CAST], the first of any id and type. */
internal fun List<JsonElement>?.toCast(session: Session): List<PersonRef> =
    orEmpty().asSequence().mapNotNull { it.decodeAs(PersonDto.serializer())?.toPersonRef(session) }
        .distinctBy { it.id to it.type }.take(DiscoverLimits.CAST).toList()

internal fun PersonDto.toPersonRef(session: Session): PersonRef? {
    val personId = idOf(id) ?: return null
    val shownName = text(name, DiscoverLimits.NAME) ?: return null
    val artwork = primaryImageTag?.let { ArtworkRef.of(session, personId, ArtworkType.PRIMARY, it) }
    return PersonRef(personId, shownName, text(role, DiscoverLimits.NAME), text(type, DiscoverLimits.TYPE), artwork)
}

// ---- online trailers (S7: never fetched, only handed to Android)

private val HOST = Regex("[a-z0-9]([a-z0-9.-]*[a-z0-9])?")

/**
 * Only an `http` or `https` address of at most [DiscoverLimits.URL] characters, all of them printable ASCII (no space, control or
 * invisible character), with a real host and no user name, else null. The host is what the button names.
 */
internal fun onlineTrailerOf(url: String?, name: String?): OnlineTrailer? {
    val raw = url ?: return null
    if (raw.isEmpty() || raw.length > DiscoverLimits.URL || raw.any { it.code !in 0x21..0x7E }) return null
    val uri = try {
        URI(raw)
    } catch (_: URISyntaxException) {
        return null
    }
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.rawUserInfo != null) return null
    val host = uri.host?.lowercase()?.takeIf { it.length <= DiscoverLimits.HOST && HOST.matches(it) } ?: return null
    return OnlineTrailer(raw, host, text(name, 100))
}

internal fun List<JsonElement>?.toOnlineTrailers(): List<OnlineTrailer> =
    orEmpty().asSequence().mapNotNull { e -> e.decodeAs(MediaUrlDto.serializer())?.let { onlineTrailerOf(it.url, it.name) } }
        .distinctBy { it.url }.take(DiscoverLimits.ONLINE_TRAILERS).toList()

// ---- versions

private val SOURCE_ID = Regex("[A-Za-z0-9_-]{1,64}")

/** The item's files when there is more than one (valid id, first of each id, at most [DiscoverLimits.VERSIONS]); else none. */
internal fun List<JsonElement>?.toVersions(): List<MediaVersion> {
    val all = orEmpty().asSequence().mapNotNull { e ->
        val dto = e.decodeAs(MediaSourceDto.serializer()) ?: return@mapNotNull null
        val sourceId = dto.id?.takeIf { SOURCE_ID.matches(it) } ?: return@mapNotNull null
        MediaVersion(sourceId, displayText(dto.name, DiscoverLimits.NAME))
    }.distinctBy { it.id }.take(DiscoverLimits.VERSIONS).toList()
    return if (all.size > 1) all else emptyList()
}

// ---- named things and pages

/** A genre, studio or other named element: needs a real id and a name. */
internal fun JsonElement.toNamed(): Named? {
    val dto = toItemDto() ?: return null
    val namedId = idOf(dto.id) ?: return null
    return Named(namedId, text(dto.name, DiscoverLimits.NAME) ?: return null)
}

internal fun List<JsonElement>?.toNamedList(limit: Int): List<Named> =
    orEmpty().asSequence().mapNotNull { it.toNamed() }.distinctBy { it.id }.take(limit).toList()

internal fun List<JsonElement>?.toPeople(session: Session, limit: Int): List<PersonRef> =
    orEmpty().asSequence().mapNotNull { e -> e.toItemDto()?.let { dto -> PersonDto(dto.id, dto.name, null, null, dto.imageTags?.get("Primary")).toPersonRef(session) } }
        .distinctBy { it.id }.take(limit).toList()

/** The same page rules as the library's `toPage`, for any kind of element: skipped or repeated ones never cost the next start index. */
internal fun <T : Any> BaseItemDtoQueryResult.toPageOf(start: Int, limit: Int, map: (JsonElement) -> T?, key: (T) -> String): Page<T> {
    val raw = rawCount()
    val next = start + raw
    val total = usableTotal(start)
    val end = raw == 0 || (total != null && next >= total) || (total == null && raw < limit)
    val kept = items.orEmpty().asSequence().mapNotNull(map).distinctBy(key).take(limit).toList()
    return Page(kept, start, total?.coerceAtMost(Limits.TOTAL_MAX.toLong())?.toInt(), next, end)
}

// ---- filter values

private fun plainValue(element: JsonElement, max: Int): String? {
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    val shown = displayText(primitive.content, max + 1)
    return shown.takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) <= max && ',' !in it && '|' !in it }
}

internal fun List<JsonElement>?.toYears(): List<Int> =
    orEmpty().asSequence().mapNotNull { e ->
        (e as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 1800..2200 }?.toInt()
    }.distinct().sortedDescending().take(DiscoverLimits.YEARS).toList()

internal fun List<JsonElement>?.toFilterTexts(max: Int, limit: Int): List<String> =
    orEmpty().asSequence().mapNotNull { plainValue(it, max) }.distinct().take(limit).toList()
