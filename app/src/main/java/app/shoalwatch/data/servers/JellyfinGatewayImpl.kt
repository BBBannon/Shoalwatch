package app.shoalwatch.data.servers

import app.shoalwatch.core.AppError
import app.shoalwatch.core.displayText
import app.shoalwatch.data.api.JsonBodies
import app.shoalwatch.data.api.await
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.net.ServerNetConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * [JellyfinGateway] on plain OkHttp + kotlinx.serialization (see "Jellyfin client decision" in
 * docs/ARCHITECTURE.md). Calls use `ServerClients` (no redirects). Responses are untrusted: bodies over [MAX_BODY_BYTES] are refused and unknown
 * JSON keys ignored; server and user ids must be GUIDs and leave here normalised ([JellyfinId]); names and the version
 * are made safe to display ([displayText]); the Quick Connect code must be 4 to 12 ASCII letters or digits. Nothing here logs or puts a URL, token, secret or password in an exception.
 */
class JellyfinGatewayImpl(private val clients: ServerClients) : JellyfinGateway {

    override suspend fun publicInfo(address: ServerAddress, net: ServerNetConfig): ServerInfo {
        val body = send(address, net, "System/Info/Public", badBody = AppError.Kind.SERVER_NOT_FOUND)
        val info = decode<PublicInfoDto>(body, AppError.Kind.SERVER_NOT_FOUND)
        if (info.id.isNullOrBlank()) throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)
        // Something answered as Jellyfin, but with an id that is not a GUID: never used as a key, route or path.
        val id = JellyfinId.parseOrNull(info.id) ?: throw AppError.of(AppError.Kind.SERVER_UNSUPPORTED)
        val version = displayText(info.version, MAX_VERSION_LENGTH)
        return ServerInfo(
            id = id.value,
            name = displayText(info.serverName, MAX_NAME_LENGTH, fallback = DEFAULT_SERVER_NAME),
            version = version,
            address = address,
            compatibility = ServerInfo.compatibilityOf(version),
        )
    }

    override suspend fun quickConnectEnabled(address: ServerAddress, net: ServerNetConfig): Boolean =
        decode(send(address, net, "QuickConnect/Enabled"), AppError.Kind.SERVER_ERROR)

    override suspend fun quickConnectInitiate(address: ServerAddress, net: ServerNetConfig): QcSession {
        val dto = decode<QuickConnectDto>(send(address, net, "QuickConnect/Initiate", post = emptyBody()), AppError.Kind.SERVER_ERROR)
        return QcSession(
            secret = dto.secret?.takeIf { it.isNotBlank() } ?: throw AppError.of(AppError.Kind.SERVER_ERROR),
            // Shown on screen and read aloud, so only what a real code looks like gets through.
            code = dto.code?.takeIf { CODE.matches(it) } ?: throw AppError.of(AppError.Kind.SERVER_ERROR),
        )
    }

    /** The secret is a credential that must travel in the query string here, because the API requires it. */
    override suspend fun quickConnectState(address: ServerAddress, net: ServerNetConfig, secret: String): Boolean {
        val dto = decode<QuickConnectDto>(send(address, net, "QuickConnect/Connect", query = "secret" to secret), AppError.Kind.SERVER_ERROR)
        return dto.authenticated == true
    }

    override suspend fun authenticateWithQuickConnect(address: ServerAddress, net: ServerNetConfig, secret: String): AuthResult =
        authenticate(address, net, "Users/AuthenticateWithQuickConnect", buildJsonObject { put("Secret", secret) }.toString())

    override suspend fun authenticateByName(address: ServerAddress, net: ServerNetConfig, username: String, password: String): AuthResult =
        authenticate(
            address, net, "Users/AuthenticateByName",
            buildJsonObject { put("Username", username); put("Pw", password) }.toString(),
        )

    override suspend fun logout(address: ServerAddress, net: ServerNetConfig) {
        // Always a one-off client: `net` may hold the token being revoked, which must never be cached.
        send(address, net, "Sessions/Logout", post = emptyBody(), oneOff = true)
    }

    private suspend fun authenticate(address: ServerAddress, net: ServerNetConfig, path: String, json: String): AuthResult {
        val dto = decode<AuthResultDto>(send(address, net, path, post = json.toRequestBody(JSON)), AppError.Kind.SERVER_ERROR)
        val user = dto.user
        val userId = JellyfinId.parseOrNull(user?.id)
        val serverId = JellyfinId.parseOrNull(dto.serverId)
        val token = dto.accessToken?.takeIf { it.isNotBlank() }
        if (userId == null || serverId == null || token == null) throw AppError.of(AppError.Kind.SERVER_ERROR)
        return AuthResult(userId.value, displayText(user?.name, MAX_NAME_LENGTH, fallback = DEFAULT_USER_NAME), token, serverId.value)
    }

    /**
     * Performs one request and returns the (bounded) body text.
     * @param badBody the error kind for a body that is too large.
     */
    private suspend fun send(
        address: ServerAddress,
        net: ServerNetConfig,
        path: String,
        post: RequestBody? = null,
        query: Pair<String, String>? = null,
        badBody: AppError.Kind = AppError.Kind.SERVER_ERROR,
        oneOff: Boolean = false,
    ): String {
        val url = "${address.value}/$path".toHttpUrl().newBuilder()
            .apply { if (query != null) addQueryParameter(query.first, query.second) }
            .build()
        val request = Request.Builder().url(url).apply { if (post != null) post(post) else get() }.build()
        return withContext(Dispatchers.IO) {
            if (oneOff) clients.oneOff(net) { exchange(it, request, badBody) } else exchange(clients.clientFor(net), request, badBody)
        }
    }

    /** One block, so the response is closed on every path, including cancellation. */
    private suspend fun exchange(client: OkHttpClient, request: Request, badBody: AppError.Kind): String {
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw AppError.from(e)
        }
        return response.use {
            // Redirects are never followed (credentials travel in bodies and queries): any 3xx is an error (see fromHttpStatus).
            if (!it.isSuccessful) throw AppError.fromHttpStatus(it.code)
            readBounded(it, badBody)
        }
    }

    private fun readBounded(response: Response, badBody: AppError.Kind): String = try {
        JsonBodies.readBounded(response, MAX_BODY_BYTES, badBody)
    } catch (e: IOException) {
        throw AppError.from(e)
    }

    private inline fun <reified T> decode(body: String, badBody: AppError.Kind): T = JsonBodies.decode<T>(body, badBody)

    private fun emptyBody(): RequestBody = ByteArray(0).toRequestBody(null)

    @Serializable private class PublicInfoDto(
        @SerialName("Id") val id: String? = null,
        @SerialName("ServerName") val serverName: String? = null,
        @SerialName("Version") val version: String? = null,
    )

    @Serializable private class QuickConnectDto(
        @SerialName("Secret") val secret: String? = null,
        @SerialName("Code") val code: String? = null,
        @SerialName("Authenticated") val authenticated: Boolean? = null,
    )

    @Serializable private class UserDto(
        @SerialName("Id") val id: String? = null,
        @SerialName("Name") val name: String? = null,
    )

    @Serializable private class AuthResultDto(
        @SerialName("AccessToken") val accessToken: String? = null,
        @SerialName("ServerId") val serverId: String? = null,
        @SerialName("User") val user: UserDto? = null,
    )

    private companion object {
        const val MAX_BODY_BYTES = 1L shl 20
        const val MAX_NAME_LENGTH = 80

        /** What a name made only of invisible characters becomes (data-layer defaults, one place). */
        const val DEFAULT_SERVER_NAME = "Jellyfin"
        const val DEFAULT_USER_NAME = "Unnamed user"
        const val MAX_VERSION_LENGTH = 40
        val CODE = Regex("[A-Za-z0-9]{4,12}")
        val JSON = "application/json".toMediaType()
    }
}
