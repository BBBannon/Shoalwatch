package app.shoalwatch.data.downloads.api

import app.shoalwatch.core.AppError
import app.shoalwatch.data.api.JsonBodies
import app.shoalwatch.data.api.ServerApi
import app.shoalwatch.data.playback.MediaPaths
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What the server says about downloading an item, validated. [canDownload]: true, false, or null when the server did not say
 * (Task 1's rule: null allows the original only). [sourceId], [eTag]: the item's own source's (its `Id` is the item's), else the first's; `[A-Za-z0-9]{1,64}` else null.
 * [container]: its first comma-separated name, lower-cased, `[a-z0-9]{1,16}` else null. [sizeBytes]: 1..256 GiB else null.
 * [eTag] is a change hint only; it is never sent as `If-Range` (only a validator from an earlier response for the same file is).
 */
internal data class DownloadFacts(
    val itemId: String, val canDownload: Boolean?, val sourceId: String?, val container: String?, val sizeBytes: Long?, val eTag: String?,
) {
    override fun toString() = "DownloadFacts(<redacted>)"
}

/** The server's download facts, on `ServerApi`. See "Download call path" in docs/ARCHITECTURE.md. */
internal interface DownloadGateway {
    /**
     * GET /Items?ids=<up to 50, comma-delimited>&userId=&fields=CanDownload,MediaSources&enableUserData=false, in as many calls as
     * needed. Ids that are not GUIDs are `SERVER_NOT_FOUND` before anything is sent. Missing ids are absent from the answer; ids
     * that were not asked for are dropped; a repeated id keeps its first answer. Throws AppError.
     */
    suspend fun facts(s: Session, itemIds: List<String>): List<DownloadFacts>
}

internal class DownloadGatewayImpl(private val api: ServerApi) : DownloadGateway {
    override suspend fun facts(s: Session, itemIds: List<String>): List<DownloadFacts> {
        val ids = itemIds.map { JellyfinId.parseOrNull(it)?.value ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND) }.distinct()
        val found = LinkedHashMap<String, DownloadFacts>()
        for (chunk in ids.chunked(CHUNK)) {
            val asked = chunk.toSet()
            val query = listOf(
                "ids" to chunk.joinToString(","),
                "userId" to s.userId,
                "fields" to "CanDownload,MediaSources",
                "enableUserData" to "false",
            )
            val result = api.get(s, "/Items", query, DownloadItemsResult.serializer(), maxBytes = MAX_BODY)
            result.items.orEmpty().forEach { element ->
                val facts = element.decodeOrNull(DownloadItemDto.serializer())?.toFacts() ?: return@forEach
                if (facts.itemId in asked && facts.itemId !in found) found[facts.itemId] = facts
            }
        }
        return found.values.toList()
    }

    private companion object {
        const val CHUNK = 50
        const val MAX_BODY = 4L * 1024 * 1024
    }
}

/** Null when the item's own id is not a GUID (it is dropped). */
private fun DownloadItemDto.toFacts(): DownloadFacts? {
    val itemId = JellyfinId.parseOrNull(id)?.value ?: return null
    // The item's own source (its Id is the item's: the file /Items/{id}/Download sends), else the first (review I-4).
    val sources = (mediaSources as? JsonArray)?.take(MAX_SOURCES)?.map { it.decodeOrNull(DownloadSourceDto.serializer()) }.orEmpty()
    val source = sources.firstOrNull { it != null && JellyfinId.parseOrNull(it.id.orEmpty())?.value == itemId } ?: sources.firstOrNull()
    return DownloadFacts(
        itemId = itemId,
        canDownload = (canDownload as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull,
        sourceId = source?.id?.takeIf(MediaPaths::isSafeId),
        container = source?.container?.substringBefore(',')?.trim()?.lowercase()?.takeIf { CONTAINER.matches(it) },
        sizeBytes = (source?.size as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 1..MAX_SIZE },
        eTag = source?.eTag?.takeIf(MediaPaths::isSafeId),
    )
}

private fun <T : Any> JsonElement.decodeOrNull(deserializer: DeserializationStrategy<T>): T? = try {
    JsonBodies.JSON.decodeFromJsonElement(deserializer, this)
} catch (_: IllegalArgumentException) {
    null // SerializationException is an IllegalArgumentException: the element is skipped, nothing from it is kept
}

private val CONTAINER = Regex("[a-z0-9]{1,16}")
private const val MAX_SOURCES = 64
private const val MAX_SIZE = 256L * 1024 * 1024 * 1024
