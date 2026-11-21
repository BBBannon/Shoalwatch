package app.shoalwatch.data.servers

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.api.ServerApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer

/** What approving a Quick Connect code came to. The screen says each in plain words. */
enum class ApproveOutcome { APPROVED, BAD_CODE, NOT_FOUND, SWITCHED_OFF, REFUSED, OFFLINE, SIGNED_OUT, FAILED }

/**
 * Approving a Quick Connect code shown on another device (Milestone 10): that device is signed in **as [Session]'s user**. This is
 * the token-bearing side of Quick Connect, so it goes through [ServerApi] like every signed-in call (the anonymous side, starting
 * and polling a code on this phone, stays in `AuthRepository`). Steps: the code must be [CODE_LENGTH] letters or digits (spaces
 * and dashes ignored), else nothing is sent; `GET /QuickConnect/Enabled` (Jellyfin answers a switched-off Authorize with 401,
 * which `ServerApi` would take as this phone's sign-in ending, so a switched-off server is found out first); then
 * `POST /QuickConnect/Authorize?code=` once, never retried, and only if [active] still says [Session] is the active profile just
 * before it is written. Straight after an app start [active] may not have heard the profile yet, so it is waited for (bounded by
 * [activeWaitMs], as `AdminAccess.check` does) rather than refused. The code is never logged.
 */
class QuickConnectApprover(
    private val api: ServerApi,
    private val active: StateFlow<Session?>,
    private val activeWaitMs: Long = ACTIVE_WAIT_MS,
) {

    suspend fun approve(s: Session, typed: String): ApproveOutcome {
        val code = normalise(typed) ?: return ApproveOutcome.BAD_CODE
        return try {
            val on = api.get(s, "/QuickConnect/Enabled", deserializer = Boolean.serializer(), maxBytes = 64)
            if (!on) return ApproveOutcome.SWITCHED_OFF
            withTimeoutOrNull(activeWaitMs) { active.first { it == s } } ?: return ApproveOutcome.REFUSED
            api.post(s, "/QuickConnect/Authorize", listOf("code" to code), retry = false, sendOnce = true, sendIf = { active.value == s })
            ApproveOutcome.APPROVED
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError) {
            AppLog.d(TAG, "approve failed: ${e.kind}")
            when (e.kind) {
                AppError.Kind.SERVER_NOT_FOUND -> ApproveOutcome.NOT_FOUND
                AppError.Kind.FORBIDDEN -> ApproveOutcome.REFUSED
                AppError.Kind.OFFLINE -> ApproveOutcome.OFFLINE
                AppError.Kind.UNAUTHORISED, AppError.Kind.NOT_SIGNED_IN -> ApproveOutcome.SIGNED_OUT
                else -> ApproveOutcome.FAILED
            }
        }
    }

    companion object {
        const val CODE_LENGTH = 6
        const val ACTIVE_WAIT_MS = 5_000L
        private val CODE = Regex("[A-Za-z0-9]{$CODE_LENGTH}")
        private const val TAG = "QuickConnect"

        /** The code as typed, without spaces and dashes, if it is exactly six letters or digits; else null. */
        fun normalise(typed: String): String? = typed.filterNot { it == ' ' || it == '-' }.takeIf { CODE.matches(it) }
    }
}
