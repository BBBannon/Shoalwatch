package app.shoalwatch.data.manage.api

import app.shoalwatch.core.displayParagraphs
import app.shoalwatch.core.displayText
import app.shoalwatch.core.serverInstant
import app.shoalwatch.data.api.JsonBodies
import app.shoalwatch.data.manage.IdentifyMatch
import app.shoalwatch.data.manage.ImageKind
import app.shoalwatch.data.manage.ItemImage
import app.shoalwatch.data.manage.ManageLimits
import app.shoalwatch.data.manage.RemoteImage
import app.shoalwatch.data.manage.RemoteImages
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.ZoneOffset

// The S11 boundary for library management: bounded lists and strings, every display string through displayText, numbers
// range-checked, third-party addresses kept only where they are handed back to the server, one bad element costs only itself.

internal fun <T : Any> JsonElement.decodeOrNull(deserializer: DeserializationStrategy<T>): T? = try {
    JsonBodies.JSON.decodeFromJsonElement(deserializer, this)
} catch (_: IllegalArgumentException) { // SerializationException is one; the element is untrusted and never echoed
    null
}

/** A JSON number (not a string) that is a whole number in [range], else null. */
internal fun wholeNumber(raw: JsonElement?, range: LongRange): Long? {
    val p = (raw as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
    return p.longOrNull?.takeIf { it in range }
}

/** A finite JSON number (not a string) in [range], else null. */
internal fun decimal(raw: JsonElement?, range: ClosedFloatingPointRange<Double>): Double? {
    val p = (raw as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
    return p.doubleOrNull?.takeIf { it.isFinite() && it in range }
}

internal fun kindOf(wire: String?): ImageKind? = ImageKind.entries.firstOrNull { it.wire.equals(wire, ignoreCase = true) }

/** A date from the server as the calendar day in UTC (how Jellyfin stores premiere dates), or null. */
internal fun day(raw: String?): LocalDate? = serverInstant(raw)?.atZone(ZoneOffset.UTC)?.toLocalDate()

private val YEARS = ManageLimits.YEAR_MIN.toLong()..ManageLimits.YEAR_MAX.toLong()
private val PROVIDER_KEY = Regex("[A-Za-z0-9_.-]{1,64}")
private val PRINTABLE = Regex("[\\x21-\\x7E]+")

/** Provider ids as the apply body will send them back: at most 20, each key and value checked. Anything else is left out. */
internal fun providerIds(raw: JsonElement?): Map<String, String> {
    val obj = raw as? JsonObject ?: return emptyMap()
    val out = LinkedHashMap<String, String>()
    for ((key, value) in obj) {
        if (out.size >= ManageLimits.PROVIDER_IDS) break
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
        if (!PROVIDER_KEY.matches(key) || text.length > ManageLimits.PROVIDER_ID_VALUE || !PRINTABLE.matches(text)) continue
        out[key] = text
    }
    return out
}

internal fun List<JsonElement>.toMatches(): List<IdentifyMatch> {
    val seen = HashSet<String>()
    val out = ArrayList<IdentifyMatch>()
    for (element in this) {
        if (out.size >= ManageLimits.RESULTS) break
        val dto = element.decodeOrNull(SearchResultDto.serializer()) ?: continue
        val name = displayText(dto.name, ManageLimits.NAME)
        val ids = providerIds(dto.providerIds)
        if (name.isEmpty() || ids.isEmpty()) continue // nothing to apply without a provider id
        val premiere = day(dto.premiereDate)?.takeIf { it.year.toLong() in YEARS }
        val match = IdentifyMatch(
            name = name,
            year = wholeNumber(dto.productionYear, YEARS)?.toInt() ?: premiere?.year,
            premiereDate = premiere,
            providerName = displayText(dto.searchProviderName, ManageLimits.PROVIDER),
            overview = displayParagraphs(dto.overview, ManageLimits.OVERVIEW).takeIf { it.isNotEmpty() },
            providerIds = ids,
        )
        if (seen.add(match.key)) out += match
    }
    return out
}

/** A picture address handed back to the server: http(s), printable, bounded. Never loaded on the phone. */
internal fun imageSource(raw: String?): String? {
    val url = raw?.takeIf { it.length <= ManageLimits.URL && PRINTABLE.matches(it) } ?: return null
    return url.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
}

internal fun RemoteImageResultDto.toRemote(asked: ImageKind): RemoteImages {
    val seen = HashSet<String>()
    val out = ArrayList<RemoteImage>()
    for (element in images.orEmpty()) {
        if (out.size >= ManageLimits.REMOTE_IMAGES) break
        val dto = element.decodeOrNull(RemoteImageDto.serializer()) ?: continue
        val source = imageSource(dto.url) ?: continue
        if (dto.type != null && kindOf(dto.type) != asked) continue // the server answered for another kind: not offered
        if (!seen.add(source)) continue
        out += RemoteImage(
            kind = asked,
            providerName = displayText(dto.providerName, ManageLimits.PROVIDER),
            width = wholeNumber(dto.width, 1L..100_000L)?.toInt(),
            height = wholeNumber(dto.height, 1L..100_000L)?.toInt(),
            language = displayText(dto.language, ManageLimits.LANGUAGE).takeIf { it.isNotEmpty() },
            rating = decimal(dto.communityRating, 0.0..10.0),
            votes = wholeNumber(dto.voteCount, 0L..100_000_000L)?.toInt(),
            source = source,
        )
    }
    val providers = providers.orEmpty().asSequence()
        .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        .map { displayText(it, ManageLimits.PROVIDER) }.filter { it.isNotEmpty() }.distinct().take(20).toList()
    val total = wholeNumber(total, 0L..1_000_000L)?.toInt()?.takeIf { it >= out.size } ?: out.size
    return RemoteImages(out, providers, total)
}

internal fun List<JsonElement>.toImages(): List<ItemImage> {
    val seen = HashSet<Pair<ImageKind, Int>>()
    val out = ArrayList<ItemImage>()
    for (element in this) {
        if (out.size >= ManageLimits.ITEM_IMAGES) break
        val dto = element.decodeOrNull(ImageInfoDto.serializer()) ?: continue
        val kind = kindOf(dto.imageType) ?: continue // a kind this app does not edit (Art, Disc, Chapter...) is not listed
        // Jellyfin leaves ImageIndex out (or null) for the single picture of a kind: that is index 0.
        val index = if (dto.imageIndex == null || dto.imageIndex is kotlinx.serialization.json.JsonNull) 0 else wholeNumber(dto.imageIndex, 0L..99L)?.toInt() ?: continue
        if (!seen.add(kind to index)) continue
        out += ItemImage(kind, index, wholeNumber(dto.width, 1L..100_000L)?.toInt(), wholeNumber(dto.height, 1L..100_000L)?.toInt(), wholeNumber(dto.size, 0L..10_000_000_000L))
    }
    return out.sortedWith(compareBy({ it.kind.ordinal }, { it.index }))
}

internal fun MetadataEditorDto.ratingOptions(): List<String> = parentalRatingOptions.orEmpty().asSequence()
    .mapNotNull { it.decodeOrNull(ParentalRatingDto.serializer())?.name }
    .map { displayText(it, ManageLimits.RATING) }.filter { it.isNotEmpty() }.distinct().take(200).toList()
