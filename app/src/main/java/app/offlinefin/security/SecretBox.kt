package app.offlinefin.security

import app.offlinefin.core.AppError
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

fun interface KeyProvider {
    fun key(): SecretKey
}

/** AES-256-GCM envelope: version byte 0x01 | 12-byte random IV | ciphertext + 16-byte tag. */
class SecretBox(private val keys: KeyProvider) {
    fun seal(plain: String): ByteArray = try {
        // No caller-supplied IV: AndroidKeyStore (randomized encryption required) generates it.
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.key())
        cipher.updateAAD(byteArrayOf(VERSION))
        val iv = cipher.iv
        check(iv != null && iv.size == IV_BYTES)
        byteArrayOf(VERSION) + iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    } catch (e: Exception) {
        throw AppError(AppError.Kind.UNKNOWN, "Sign-in could not be saved securely. Please try again.", e)
    }

    fun open(sealed: ByteArray): String = try {
        require(sealed.size >= 1 + IV_BYTES + TAG_BITS / 8 && sealed[0] == VERSION)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(TAG_BITS, sealed, 1, IV_BYTES))
        cipher.updateAAD(byteArrayOf(VERSION))
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
