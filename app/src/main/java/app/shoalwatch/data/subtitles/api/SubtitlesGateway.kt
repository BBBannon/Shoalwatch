package app.shoalwatch.data.subtitles.api

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.api.ServerApi
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.subtitles.Arrival
import app.shoalwatch.data.subtitles.RemoteSubtitles
import app.shoalwatch.data.subtitles.SubtitleDownload
import app.shoalwatch.data.subtitles.SubtitleLimits
import app.shoalwatch.data.subtitles.SubtitleSearch
import app.shoalwatch.data.subtitles.chooseLanguage
import app.shoalwatch.data.subtitles.isLanguageCode
import app.shoalwatch.data.subtitles.isSubtitleId
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement

/**
 * [RemoteSubtitles] on [ServerApi]. Search and download are policy-gated by the server (SubtitleManagement), so both use
 * `verifyUnauthorised`: a 403 or a checked 401 becomes the typed refusal and the sign-in is kept. The download is sent once.
 * Paths checked against the 12.4.0 reference. Failures are logged by kind only.
 */
internal class SubtitlesGateway(
    private val api: ServerApi,
    private val phoneLanguage: () -> String? = { app.shoalwatch.data.subtitles.phoneLanguage() },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : RemoteSubtitles {

    override suspend fun search(s: Session, itemId: String, language: String): SubtitleSearch = logged("search") {
        val id = normalisedId(itemId)
        if (!isLanguageCode(language)) throw AppError.of(AppError.Kind.UNKNOWN)
        try {
            val list = api.get(
                s, "/Items/$id/RemoteSearch/Subtitles/$language", deserializer = ListSerializer(JsonElement.serializer()),
                maxBytes = LIST_BYTES, verifyUnauthorised = true,
            )
            SubtitleSearch.Found(list.toCandidates())
        } catch (e: AppError) {
            if (e.kind == AppError.Kind.FORBIDDEN) SubtitleSearch.NotAllowed else throw e
        }
    }

    override suspend fun download(s: Session, itemId: String, subtitleId: String): SubtitleDownload = logged("download") {
        val id = normalisedId(itemId)
        if (!isSubtitleId(subtitleId)) throw AppError.of(AppError.Kind.UNKNOWN)
        try {
            api.post(s, "/Items/$id/RemoteSearch/Subtitles/$subtitleId", sendOnce = true, verifyUnauthorised = true)
            SubtitleDownload.Requested
        } catch (e: AppError) {
            if (e.kind == AppError.Kind.FORBIDDEN) SubtitleDownload.NotAllowed else throw e
        }
    }

    override suspend fun subtitleCount(s: Session, itemId: String): Int = logged("subtitleCount") {
        val id = normalisedId(itemId)
        api.get(s, "/Items/$id", listOf("userId" to s.userId), SubtitleItemDto.serializer(), maxBytes = ITEM_BYTES).subtitleCount()
    }

    override suspend fun awaitArrival(s: Session, itemId: String, countBefore: Int): Arrival {
        normalisedId(itemId)
        var waited = 0L
        for (at in SubtitleLimits.CHECK_AT_MS) {
            pause(at - waited)
            waited = at
            val now = try {
                subtitleCount(s, itemId)
            } catch (_: AppError) {
                continue // a failed read is "not yet"; the next one may work
            }
            if (now > countBefore) return Arrival.Arrived
        }
        return Arrival.NotYet
    }

    override suspend fun preferredLanguage(s: Session): String {
        val profile = try {
            api.get(s, "/Users/Me", deserializer = MeLanguageDto.serializer(), maxBytes = ME_BYTES)
                .configuration?.subtitleLanguagePreference
        } catch (e: AppError) {
            AppLog.d(TAG, "subtitles preferredLanguage failed: ${e.kind}")
            null
        }
        return chooseLanguage(profile, phoneLanguage())
    }

    private fun normalisedId(raw: String): String =
        JellyfinId.parseOrNull(raw)?.value ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)

    private inline fun <T> logged(call: String, block: () -> T): T = try {
        block()
    } catch (e: AppError) {
        AppLog.d(TAG, "subtitles $call failed: ${e.kind}")
        throw e
    }

    private companion object {
        const val TAG = "Subtitles"
        const val ONE_MIB = 1024L * 1024
        const val ITEM_BYTES = 4 * ONE_MIB
        const val LIST_BYTES = 2 * ONE_MIB
        const val ME_BYTES = ONE_MIB
    }
}
