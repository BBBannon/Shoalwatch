package app.shoalwatch.data.api

import app.shoalwatch.core.AppError
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.Response
import java.io.IOException

/**
 * Reading and decoding untrusted response bodies, shared by `JellyfinGatewayImpl` and [ServerApi].
 * Nothing from a body ever reaches an error message.
 */
internal object JsonBodies {
    /**
     * The one decoding configuration: unknown keys are ignored and nothing else is relaxed. In particular an explicit
     * JSON null for a field that is not nullable is a decode failure (no `coerceInputValues`), never a silent default;
     * DTOs for untrusted responses declare every field nullable with a default instead.
     */
    val JSON = Json { ignoreUnknownKeys = true }

    /**
     * The body as UTF-8 text, refusing anything over [max] bytes by reading at most one byte more than that.
     * @throws AppError of kind [tooBig] when the body is over the limit.
     * @throws IOException when the connection fails while reading (the caller maps it).
     */
    fun readBounded(response: Response, max: Long, tooBig: AppError.Kind): String {
        val source = response.body.source()
        source.request(max + 1)
        if (source.buffer.size > max) throw AppError.of(tooBig)
        return source.readUtf8()
    }

    /** @throws AppError of kind [badBody] when [body] is not valid for [deserializer]. */
    fun <T> decode(body: String, deserializer: DeserializationStrategy<T>, badBody: AppError.Kind): T = try {
        JSON.decodeFromString(deserializer, body)
    } catch (e: IllegalArgumentException) {
        // SerializationException is an IllegalArgumentException. The body is untrusted: never copied into the error.
        throw AppError.of(badBody)
    }

    inline fun <reified T> decode(body: String, badBody: AppError.Kind): T =
        decode(body, kotlinx.serialization.serializer<T>(), badBody)
}
