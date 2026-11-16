package app.shoalwatch.security

import app.shoalwatch.core.AppError
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HMAC-SHA256 over the PIN with a 16-byte random salt, 32-byte result, the whole sealed by [box] (Keystore AES-GCM), so the stored
 * bytes cannot be tried offline. Format before sealing: "p1:<iterations>:<base64 salt>:<base64 hash>". Comparison is constant-time
 * (`MessageDigest.isEqual`). Never keeps the PIN; the caller zeroes it. The platform's own PBKDF2 (no new dependency).
 */
class PinHasher(private val box: SecretBox, private val iterations: Int = ITERATIONS, private val random: SecureRandom = SecureRandom()) {
    init {
        require(iterations > 0)
    }

    fun seal(pin: CharArray): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = derive(pin, salt, iterations)
        try {
            return box.seal("$FORMAT:$iterations:${b64.encodeToString(salt)}:${b64.encodeToString(hash)}")
        } finally {
            hash.fill(0)
        }
    }

    /** @throws AppError when the blob cannot be opened (lost key, damaged) or is not in the format above. */
    fun matches(pin: CharArray, sealed: ByteArray): Boolean {
        val parts = box.open(sealed).split(':')
        val (rounds, salt, expected) = try {
            require(parts.size == 4 && parts[0] == FORMAT)
            val rounds = parts[1].toInt()
            require(rounds > 0)
            val salt = b64d.decode(parts[2])
            val expected = b64d.decode(parts[3])
            require(salt.size == SALT_BYTES && expected.size == HASH_BYTES)
            Triple(rounds, salt, expected)
        } catch (e: IllegalArgumentException) { // NumberFormatException is one too
            throw AppError.of(AppError.Kind.UNKNOWN)
        }
        val actual = derive(pin, salt, rounds)
        try {
            return MessageDigest.isEqual(actual, expected)
        } finally {
            actual.fill(0)
            expected.fill(0)
        }
    }

    private fun derive(pin: CharArray, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(pin, salt, rounds, HASH_BYTES * 8) // copies the PIN; cleared below
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    override fun toString() = "PinHasher(<redacted>)"

    companion object {
        const val ITERATIONS = 100_000
        private const val FORMAT = "p1"
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val SALT_BYTES = 16
        private const val HASH_BYTES = 32
        private val b64: Base64.Encoder = Base64.getEncoder()
        private val b64d: Base64.Decoder = Base64.getDecoder()
    }
}
