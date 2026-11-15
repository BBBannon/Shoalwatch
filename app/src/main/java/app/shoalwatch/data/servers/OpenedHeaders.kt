package app.shoalwatch.data.servers

import app.shoalwatch.security.SecretBox
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Opened custom headers, memoised BY CIPHERTEXT (the key is a SHA-256 of the sealed bytes, never the plaintext), so the
 * Keystore is not used on every authenticated request. The row is still read every time and a change of headers is a
 * different ciphertext, so the memo cannot go stale. Only a successful open is kept (an unreadable blob is retried), at
 * most [SIZE] entries (oldest unused out), and it is emptied when a server is saved or removed ([forget]).
 */
internal class OpenedHeaders(private val box: SecretBox) {
    private val memo = object : LinkedHashMap<String, Map<String, String>>(SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>) = size > SIZE
    }

    /** The headers in [sealed]; none if it cannot be opened (not remembered, so the next read tries again). */
    fun open(sealed: ByteArray): Map<String, String> {
        val key = MessageDigest.getInstance("SHA-256").digest(sealed).joinToString("") { "%02x".format(it) }
        synchronized(memo) { memo[key] }?.let { return it }
        val opened = try {
            Json.decodeFromString(HEADERS, box.open(sealed))
        } catch (_: Exception) {
            return emptyMap()
        }
        synchronized(memo) { memo[key] = opened }
        return opened
    }

    fun forget() = synchronized(memo) { memo.clear() }

    companion object {
        private const val SIZE = 16
        val HEADERS = MapSerializer(String.serializer(), String.serializer())
    }
}
