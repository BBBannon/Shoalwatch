package app.offlinefin.data.servers

import app.offlinefin.core.AppError
import app.offlinefin.net.HttpClientFactory
import app.offlinefin.net.ServerAddress
import app.offlinefin.net.ServerNetConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [JellyfinGateway] on plain OkHttp + kotlinx.serialization (see "Jellyfin client decision" in
 * docs/ARCHITECTURE.md). Responses are untrusted: bodies over [MAX_BODY_BYTES] are refused and unknown
 * JSON keys ignored. Nothing here logs or puts a URL, token, secret or password in an exception.
 */
class JellyfinGatewayImpl(private val factory: HttpClientFactory) : JellyfinGateway {

    // One client per server configuration (see HttpClientFactory.clientFor). A small LRU keeps the few
    // configurations in use alive without letting one-off configs accumulate.
    private val clients = object : LinkedHashMap<ServerNetConfig, OkHttpClient>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ServerNetConfig, OkHttpClient>?) = size > MAX_CLIENTS
    }

    private fun clientFor(net: ServerNetConfig): OkHttpClient = synchronized(clients) {
        clients.getOrPut(net) { factory.clientFor(net) }
    }

    override suspend fun publicInfo(address: ServerAddress, net: ServerNetConfig): ServerInfo {
        val body = send(address, net, "System/Info/Public", badBody = AppError.Kind.SERVER_NOT_FOUND)
        val info = decode<PublicInfoDto>(body, AppError.Kind.SERVER_NOT_FOUND)
        val id = info.id?.takeIf { it.isNotBlank() } ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)
        val version = info.version.orEmpty()
        return ServerInfo(
            id = id,
            name = info.serverName?.takeIf { it.isNotBlank() } ?: "Jellyfin",
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
            code = dto.code?.takeIf { it.isNotBlank() } ?: throw AppError.of(AppError.Kind.SERVER_ERROR),
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
        send(address, net, "Sessions/Logout", post = emptyBody())
    }

    private suspend fun authenticate(address: ServerAddress, net: ServerNetConfig, path: String, json: String): AuthResult {
        val dto = decode<AuthResultDto>(send(address, net, path, post = json.toRequestBody(JSON)), AppError.Kind.SERVER_ERROR)
        val user = dto.user
        val userId = user?.id?.takeIf { it.isNotBlank() }
        val token = dto.accessToken?.takeIf { it.isNotBlank() }
        if (userId == null || token == null) throw AppError.of(AppError.Kind.SERVER_ERROR)
        return AuthResult(userId, user.name.orEmpty(), token, dto.serverId.orEmpty())
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
    ): String {
        val url = "${address.value}/$path".toHttpUrl().newBuilder()
            .apply { if (query != null) addQueryParameter(query.first, query.second) }
            .build()
        val request = Request.Builder().url(url).apply { if (post != null) post(post) else get() }.build()
        val response = try {
            clientFor(net).newCall(request).await()
        } catch (e: IOException) {
            throw AppError.from(e)
        }
        return withContext(Dispatchers.IO) {
            response.use {
                if (!it.isSuccessful) throw AppError.fromHttpStatus(it.code)
                readBounded(it, badBody)
            }
        }
    }

    private fun readBounded(response: Response, badBody: AppError.Kind): String = try {
        val source = response.body.source()
        source.request(MAX_BODY_BYTES + 1)
        if (source.buffer.size > MAX_BODY_BYTES) throw AppError.of(badBody)
        source.readUtf8()
    } catch (e: IOException) {
        throw AppError.from(e)
    }

    private inline fun <reified T> decode(body: String, badBody: AppError.Kind): T = try {
        LENIENT.decodeFromString<T>(body)
    } catch (e: IllegalArgumentException) {
        // SerializationException is an IllegalArgumentException. The body is untrusted: never copied into the error.
        throw AppError.of(badBody)
    }

    private fun emptyBody(): RequestBody = ByteArray(0).toRequestBody(null)

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, _, _ -> response.close() }
        })
    }

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
        const val MAX_CLIENTS = 8
        val JSON = "application/json".toMediaType()
        val LENIENT = Json { ignoreUnknownKeys = true }
    }
}
