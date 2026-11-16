package app.shoalwatch.data.sync.api

import app.shoalwatch.core.AppError
import app.shoalwatch.core.Ticks
import app.shoalwatch.data.api.ServerApi
import app.shoalwatch.data.library.api.LibraryGateway
import app.shoalwatch.data.library.api.UserItemDataDto
import app.shoalwatch.data.library.api.toServerUserData
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.sync.ServerUserData
import app.shoalwatch.data.sync.UserDataUpdate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The server's user-data endpoints for the outbox (see "Sync outbox" in docs/ARCHITECTURE.md). Every call goes through `ServerApi`
 * as [Session] `s`, with `userId` always `s.userId`; ids must be normalised `JellyfinId`s (anything else is `SERVER_NOT_FOUND`
 * with nothing sent). Every call is idempotent (absolute values), so each opts in to `ServerApi`'s one network retry. No request
 * ever carries `datePlayed`.
 */
internal interface SyncGateway {
    /** GET /UserItems/{id}/UserData?userId=. Null: the route answered 404 but GET /Items/{id} found the item (a server without the route). */
    suspend fun userData(s: Session, itemId: String): ServerUserData?

    /** POST /UserItems/{id}/UserData?userId= with only [body]'s non-null fields. Idempotent (absolute values), so ServerApi's one retry is allowed. */
    suspend fun update(s: Session, itemId: String, body: UserDataUpdate)

    /** POST /UserPlayedItems/{id}?userId= — NEVER with datePlayed. For seasons and series, whose children the server marks. */
    suspend fun markPlayed(s: Session, itemId: String)

    /** DELETE /UserPlayedItems/{id}?userId= */
    suspend fun markUnplayed(s: Session, itemId: String)

    /** POST / DELETE /UserFavoriteItems/{id}?userId= */
    suspend fun favourite(s: Session, itemId: String, on: Boolean)

    /** True only when GET /Items/{id} is SERVER_NOT_FOUND AND GET /UserViews succeeds: the item is gone, not the route or the proxy. */
    suspend fun itemGone(s: Session, itemId: String): Boolean
}

internal class SyncGatewayImpl(private val api: ServerApi, private val library: LibraryGateway) : SyncGateway {
    override suspend fun userData(s: Session, itemId: String): ServerUserData? {
        val id = idOf(itemId)
        val dto = try {
            api.get(s, "/UserItems/$id/UserData", withUser(s), UserItemDataDto.serializer(), maxBytes = MAX_USER_DATA_BYTES, retry = true)
        } catch (e: AppError) {
            if (e.kind != AppError.Kind.SERVER_NOT_FOUND) throw e
            library.item(s, id) // the item is there (else its own failure passes through): this server has no user-data route
            return null
        }
        return dto.toServerUserData()
    }

    override suspend fun update(s: Session, itemId: String, body: UserDataUpdate) {
        val json = SyncJson.ENCODER.encodeToString(UpdateUserItemDataBody.serializer(), body.toWire())
        api.post(s, "/UserItems/${idOf(itemId)}/UserData", withUser(s), json, retry = true)
    }

    override suspend fun markPlayed(s: Session, itemId: String) = api.post(s, "/UserPlayedItems/${idOf(itemId)}", withUser(s), retry = true)

    override suspend fun markUnplayed(s: Session, itemId: String) = api.delete(s, "/UserPlayedItems/${idOf(itemId)}", withUser(s), retry = true)

    override suspend fun favourite(s: Session, itemId: String, on: Boolean) {
        val path = "/UserFavoriteItems/${idOf(itemId)}"
        if (on) api.post(s, path, withUser(s), retry = true) else api.delete(s, path, withUser(s), retry = true)
    }

    override suspend fun itemGone(s: Session, itemId: String): Boolean {
        try {
            library.item(s, idOf(itemId))
            return false
        } catch (e: AppError) {
            if (e.kind != AppError.Kind.SERVER_NOT_FOUND) throw e
        }
        return try {
            library.views(s)
            true
        } catch (e: AppError) {
            // A redirect or a proxy answering 404 for everything: the route is gone, not the item.
            if (e.kind != AppError.Kind.SERVER_NOT_FOUND) throw e
            false
        }
    }

    private fun withUser(s: Session) = listOf("userId" to s.userId)

    private fun idOf(raw: String): String = JellyfinId.parseOrNull(raw)?.value?.takeIf { it == raw } ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)

    private companion object {
        const val MAX_USER_DATA_BYTES = 64L * 1024
    }
}

/** `UpdateUserItemDataDto`, only the fields the outbox sends; nulls are left out (see [SyncJson]). */
@Serializable
internal class UpdateUserItemDataBody(
    @SerialName("Played") val played: Boolean? = null,
    @SerialName("PlayCount") val playCount: Int? = null,
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long? = null,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null, // ISO-8601 UTC, e.g. "2026-11-15T14:05:00Z"
)

internal object SyncJson {
    /** As `PlaybackJson`: a null field is not sent at all. */
    val ENCODER: Json = Json { explicitNulls = false; encodeDefaults = true }
}

/** Dates in whole seconds, UTC (`Z`). */
internal fun UserDataUpdate.toWire() = UpdateUserItemDataBody(
    played = played,
    playCount = playCount,
    playbackPositionTicks = positionMs?.let(Ticks::fromMs),
    lastPlayedDate = lastPlayedAt?.let { Instant.ofEpochMilli(it).truncatedTo(ChronoUnit.SECONDS).toString() },
)
