package app.shoalwatch.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

enum class AuthOutcome { SUCCESS, CANCELLED, FAILED, UNAVAILABLE }

interface DeviceAuth {
    /** The phone has a secure screen lock (`KeyguardManager.isDeviceSecure`). */
    fun available(): Boolean

    /** Shows the system prompt over [activity]; suspends until it answers. */
    suspend fun authenticate(activity: Activity, title: String, subtitle: String): AuthOutcome
}

/**
 * Framework `BiometricPrompt` (API 28+, no extra library): API 30+ `setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)`;
 * API 29 `setDeviceCredentialAllowed(true)`. No negative button (the device credential is the fallback). Cancelled by a
 * `CancellationSignal` when the coroutine is cancelled. Needs `USE_BIOMETRIC`; nothing about the person or the result is kept.
 */
class FrameworkDeviceAuth(private val context: Context) : DeviceAuth {
    override fun available(): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    override suspend fun authenticate(activity: Activity, title: String, subtitle: String): AuthOutcome {
        if (!available()) return AuthOutcome.UNAVAILABLE
        val builder = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        val prompt = builder.build()
        return suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            prompt.authenticate(
                signal, activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(AuthOutcome.SUCCESS)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (continuation.isActive) continuation.resume(outcomeOf(errorCode))
                    }
                    // onAuthenticationFailed (one unrecognised finger) is not an answer: the prompt stays and asks again.
                },
            )
        }
    }

    internal companion object {
        /** The prompt's error codes as an [AuthOutcome]. */
        fun outcomeOf(errorCode: Int): AuthOutcome = when (errorCode) {
            BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> AuthOutcome.CANCELLED
            BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE, BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT,
            BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS, BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL,
            -> AuthOutcome.UNAVAILABLE
            else -> AuthOutcome.FAILED
        }
    }
}
