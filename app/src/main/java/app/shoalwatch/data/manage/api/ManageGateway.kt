package app.shoalwatch.data.manage.api

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.core.displayText
import app.shoalwatch.data.api.JsonBodies
import app.shoalwatch.data.api.ServerApi
import app.shoalwatch.data.manage.IdentifyKind
import app.shoalwatch.data.manage.IdentifyMatch
import app.shoalwatch.data.manage.ImageKind
import app.shoalwatch.data.manage.ItemImage
import app.shoalwatch.data.manage.Manage
import app.shoalwatch.data.manage.ManageLimits
import app.shoalwatch.data.manage.MetadataEdit
import app.shoalwatch.data.manage.MetadataForm
import app.shoalwatch.data.manage.PreparedImage
import app.shoalwatch.data.manage.RefreshMode
import app.shoalwatch.data.manage.RemoteImage
import app.shoalwatch.data.manage.RemoteImages
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/**
 * [Manage] on [ServerApi], the one authenticated call path (warm gate, verified address, the account's own client, the token only
 * in `Authorization`, 401 signs the account out locally). Every call is refused with nothing sent unless [allows] says the session
 * is a server-confirmed administrator ([delete] and [canDelete]: [allowsDelete], then the item's own `CanDelete` for this user, read
 * just before a delete is sent). Ids are validated before anything is sent. Reads use ServerApi's
 * one retry; every change goes through [send] (or the upload's own `postBytes`): sent once (`retry = false`, `sendOnce`) and only
 * if the same rule still holds just before it is written. Every path, parameter and field was checked against the 12.4.0
 * reference (see "Library management" in docs/ARCHITECTURE.md). Failures are logged by kind only.
 */
internal class ManageGateway(
    private val api: ServerApi,
    /** `AdminRights.allows`. */
    private val allows: (Session) -> Boolean,
    /** `AdminRights.allowsDelete`. */
    private val allowsDelete: (Session) -> Boolean,
) : Manage {

    override suspend fun identify(s: Session, itemId: String, kind: IdentifyKind, name: String, year: Int?): List<IdentifyMatch> = gated(s, "identify") {
        val id = itemId(itemId)
        val term = displayText(name, ManageLimits.NAME)
        require(term.isNotEmpty()) { "Empty search." }
        val body = SearchQueryBody(SearchInfoBody(term, year?.takeIf { it in ManageLimits.YEAR_MIN..ManageLimits.YEAR_MAX }), id)
        // A search, not a change: one POST that changes nothing on the server, so it is not re-checked before writing.
        api.postForJson(
            s, "/Items/RemoteSearch/${kind.wire}", jsonBody = JsonBodies.JSON.encodeToString(SearchQueryBody.serializer(), body),
            deserializer = ELEMENTS, maxBytes = 2 * ONE_MIB,
        ).toMatches()
    }

    override suspend fun applyMatch(s: Session, itemId: String, match: IdentifyMatch, replaceAllImages: Boolean) = gated(s, "applyMatch") {
        val id = itemId(itemId)
        if (match.providerIds.isEmpty()) throw notFound()
        val body = ApplyBody(match.name, match.year, match.premiereDate?.let(ItemEdit::wireDate), match.providerIds, match.providerName)
        send(s, "POST", "/Items/RemoteSearch/Apply/$id", listOf("replaceAllImages" to replaceAllImages.toString()), JsonBodies.JSON.encodeToString(ApplyBody.serializer(), body))
    }

    override suspend fun images(s: Session, itemId: String): List<ItemImage> = gated(s, "images") {
        api.get(s, "/Items/${itemId(itemId)}/Images", deserializer = ELEMENTS, maxBytes = ONE_MIB).toImages()
    }

    override suspend fun remoteImages(s: Session, itemId: String, kind: ImageKind, allLanguages: Boolean): RemoteImages = gated(s, "remoteImages") {
        val query = listOf(
            "type" to kind.wire, "startIndex" to "0", "limit" to ManageLimits.REMOTE_IMAGES.toString(), "includeAllLanguages" to allLanguages.toString(),
        )
        api.get(s, "/Items/${itemId(itemId)}/RemoteImages", query, RemoteImageResultDto.serializer(), maxBytes = 2 * ONE_MIB).toRemote(kind)
    }

    override suspend fun useRemoteImage(s: Session, itemId: String, image: RemoteImage) = gated(s, "useRemoteImage") {
        val source = imageSource(image.source) ?: throw notFound()
        // The address is the server's own answer, handed back as a query value only; the server fetches it, never this phone.
        send(s, "POST", "/Items/${itemId(itemId)}/RemoteImages/Download", listOf("type" to image.kind.wire, "imageUrl" to source))
    }

    override suspend fun uploadImage(s: Session, itemId: String, kind: ImageKind, image: PreparedImage) = gated(s, "uploadImage") {
        val id = itemId(itemId)
        val jpeg = image.jpeg
        require(jpeg.size in 4..MAX_UPLOAD_BYTES && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "Not a prepared picture." }
        // Jellyfin's SetItemImage reads the body as base64 text (the reference's `image/*` binary is not what the server does).
        val body = Base64.getEncoder().encode(jpeg)
        api.postBytes(s, "/Items/$id/Images/${kind.wire}", body = body, contentType = "image/jpeg", sendIf = { allows(s) })
    }

    override suspend fun deleteImage(s: Session, itemId: String, kind: ImageKind, index: Int) = gated(s, "deleteImage") {
        require(index in 0..99) { "Invalid picture index." }
        send(s, "DELETE", "/Items/${itemId(itemId)}/Images/${kind.wire}/$index")
    }

    override suspend fun metadata(s: Session, itemId: String): MetadataForm = gated(s, "metadata") {
        val id = itemId(itemId)
        val options = api.get(s, "/Items/$id/MetadataEditor", deserializer = MetadataEditorDto.serializer(), maxBytes = ONE_MIB).ratingOptions()
        ItemEdit.form(item(s, id), id, options) ?: throw AppError.of(AppError.Kind.SERVER_ERROR)
    }

    override suspend fun saveMetadata(s: Session, itemId: String, edit: MetadataEdit, shown: MetadataForm) = gated(s, "saveMetadata") {
        val id = itemId(itemId)
        // Nothing differs from the form as shown: nothing is read and nothing is written (an untouched save changes nothing).
        if (ItemEdit.changedKeys(edit, shown).isEmpty()) return@gated
        val body = ItemEdit.withEdit(item(s, id), id, edit, shown) ?: throw AppError.of(AppError.Kind.SERVER_ERROR)
        // The gate may have closed while the item was read: [send] asks again just before writing, so nothing is posted then.
        send(s, "POST", "/Items/$id", jsonBody = body.toString())
    }

    override suspend fun refresh(s: Session, itemId: String, mode: RefreshMode, replaceImages: Boolean) = gated(s, "refresh") {
        send(s, "POST", "/Items/${itemId(itemId)}/Refresh", refreshQuery(mode, replaceImages))
    }

    override suspend fun canDelete(s: Session, itemId: String): Boolean = logged("canDelete") {
        allowsDelete(s) && itemCanDelete(s, itemId(itemId))
    }

    override suspend fun delete(s: Session, itemId: String) = logged("delete") {
        if (!allowsDelete(s)) throw AppError.of(AppError.Kind.FORBIDDEN)
        val id = itemId(itemId)
        // The item's own answer for this user, read just before sending: Jellyfin answers a delete it refuses with 401, which is not
        // to be mistaken for this sign-in ending. False (or not said): nothing is sent.
        if (!itemCanDelete(s, id)) throw AppError(AppError.Kind.FORBIDDEN, NOT_DELETABLE)
        api.delete(s, "/Items/$id", retry = false, sendOnce = true, sendIf = { allowsDelete(s) }, verifyUnauthorised = true)
    }

    // ---- rules

    /** `CanDelete` for this user on the item's own `GET /Items/{id}?userId=`: true only when it is the item asked for and says true. */
    private suspend fun itemCanDelete(s: Session, id: String): Boolean {
        val item = api.get(s, "/Items/$id", listOf("userId" to s.userId), ItemRightsDto.serializer(), maxBytes = ItemEdit.MAX_ITEM_BYTES)
        val said = (item.canDelete as? JsonPrimitive)?.takeIf { !it.isString }?.content
        return JellyfinId.parseOrNull(item.id)?.value == id && said == "true"
    }

    /** The server's own item (`GET /Items/{id}?userId=`), as a JSON object, for the full-item round trip. */
    private suspend fun item(s: Session, id: String): JsonObject =
        api.get(s, "/Items/$id", listOf("userId" to s.userId), JsonObject.serializer(), maxBytes = ItemEdit.MAX_ITEM_BYTES)

    /** Every change: sent once and never retried, and only if [allows] still holds at the moment it is written. */
    private suspend fun send(s: Session, method: String, path: String, query: List<Pair<String, String>> = emptyList(), jsonBody: String? = null) {
        val stillAllowed = { allows(s) }
        when (method) {
            "POST" -> api.post(s, path, query, jsonBody, retry = false, sendOnce = true, sendIf = stillAllowed)
            "DELETE" -> {
                require(jsonBody == null) { "No body on a DELETE." }
                api.delete(s, path, query, retry = false, sendOnce = true, sendIf = stillAllowed)
            }
            else -> throw IllegalArgumentException("Not an action method.")
        }
    }

    private inline fun <T> gated(s: Session, call: String, block: () -> T): T = logged(call) {
        if (!allows(s)) throw AppError.of(AppError.Kind.FORBIDDEN)
        block()
    }

    private inline fun <T> logged(call: String, block: () -> T): T = try {
        block()
    } catch (e: AppError) {
        AppLog.d(TAG, "manage $call failed: ${e.kind}")
        throw e
    }

    /** Only an id already in the normalised form the library uses; a dashed or upper-case one is refused, not repaired. */
    private fun itemId(raw: String) = raw.takeIf { JellyfinId.parseOrNull(it)?.value == it } ?: throw notFound()

    private fun notFound() = AppError.of(AppError.Kind.SERVER_NOT_FOUND)

    companion object {
        private const val TAG = "Manage"
        private const val NOT_DELETABLE = "The server doesn't let this profile delete that."
        private const val ONE_MIB = 1024L * 1024
        const val MAX_UPLOAD_BYTES = 10 * 1024 * 1024
        private val ELEMENTS = ListSerializer(JsonElement.serializer())

        /** The web dashboard's refresh dialog (plan decision 11), with only the parameters the 12.4.0 reference lists. */
        fun refreshQuery(mode: RefreshMode, replaceImages: Boolean): List<Pair<String, String>> {
            val full = if (mode == RefreshMode.SCAN) "Default" else "FullRefresh"
            return listOf(
                "metadataRefreshMode" to full, "imageRefreshMode" to full,
                "replaceAllMetadata" to (mode == RefreshMode.REPLACE_ALL).toString(),
                "replaceAllImages" to (mode != RefreshMode.SCAN && replaceImages).toString(),
                "regenerateTrickplay" to "false",
            )
        }
    }
}
