package app.shoalwatch.data.subtitles.api

import app.shoalwatch.core.displayText
import app.shoalwatch.data.api.JsonBodies
import app.shoalwatch.data.subtitles.SubtitleCandidate
import app.shoalwatch.data.subtitles.SubtitleLimits
import app.shoalwatch.data.subtitles.isSubtitleId
import kotlinx.serialization.json.JsonElement

// The S11 boundary for remote subtitles: bounded list, ids checked, text cleaned, one bad element costs only itself.

private inline fun <T : Any> tolerant(decode: () -> T): T? = try {
    decode()
} catch (_: IllegalArgumentException) { // SerializationException is one; the element is untrusted and never echoed
    null
}

/** Valid candidates only (a safe id, a name or provider), at most [SubtitleLimits.RESULTS], the first of any id. */
internal fun List<JsonElement>?.toCandidates(): List<SubtitleCandidate> {
    val seen = HashSet<String>()
    val out = ArrayList<SubtitleCandidate>()
    for (element in this.orEmpty()) {
        if (out.size >= SubtitleLimits.RESULTS) break
        val dto = tolerant { JsonBodies.JSON.decodeFromJsonElement(RemoteSubtitleDto.serializer(), element) } ?: continue
        val id = dto.id?.takeIf { isSubtitleId(it) } ?: continue
        if (!seen.add(id)) continue
        val provider = displayText(dto.providerName, SubtitleLimits.TEXT)
        val name = displayText(dto.name, SubtitleLimits.TEXT)
        if (provider.isEmpty() && name.isEmpty()) continue
        out += SubtitleCandidate(
            id = id,
            provider = provider,
            name = name,
            format = displayText(dto.format, SubtitleLimits.FORMAT).ifEmpty { null },
            rating = dto.communityRating?.takeIf { it.isFinite() && it >= 0.0 }?.coerceAtMost(10.0)?.toFloat(),
            downloads = dto.downloadCount?.takeIf { it >= 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
            hearingImpaired = dto.hearingImpaired == true,
            forced = dto.forced == true,
        )
    }
    return out
}

/** How many entries are subtitle streams. The list is scanned to at most [MAX_STREAMS] entries. */
internal fun SubtitleItemDto.subtitleCount(): Int =
    mediaStreams.orEmpty().asSequence().take(MAX_STREAMS).count { e ->
        tolerant { JsonBodies.JSON.decodeFromJsonElement(StreamTypeDto.serializer(), e) }?.type == "Subtitle"
    }

internal const val MAX_STREAMS = 1000
