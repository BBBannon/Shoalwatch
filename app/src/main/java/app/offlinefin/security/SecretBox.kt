package app.offlinefin.security

import app.offlinefin.core.AppError
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

fun interface KeyProvider {
    fun key(): SecretKey
}

/** AES-256-GCM envelope: version byte 0x01 | 12-byte random IV | ciphertext + 16-byte tag. */
class SecretBox(private val keys: KeyProvider) {
    private val random = SecureRandom()

    fun seal(plain: String): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.key(), GCMParameterSpec(TAG_BITS, iv))
        return byteArrayOf(VERSION) + iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    }

    fun open(sealed: ByteArray): String = try {
        require(sealed.size >= 1 + IV_BYTES + TAG_BITS / 8 && sealed[0] == VERSION)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(TAG_BITS, sealed, 1, IV_BYTES))
        String(cipher.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES), Charsets.UTF_8)
    } catch (e: Exception) {
        throw AppError(AppError.Kind.UNKNOWN, "Saved sign-in could not be read. Please sign in again.", e)
    }

    private companion object {
        const val VERSION: Byte = 0x01
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
