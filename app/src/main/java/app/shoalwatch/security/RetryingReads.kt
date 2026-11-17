package app.shoalwatch.security

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen

/**
 * A screen's copy of a stored lock list that never ends the app on a failed read (Tasks 5-6 re-review I-A): it waits and reads
 * again, emitting nothing meanwhile, so the screen keeps what it last showed and a "Protected" mark is never dropped. Only for what
 * a screen shows: [LockGate] and [HouseholdGuard] handle their own failures, because they must act on one.
 */
fun <T> Flow<T>.retryingReads(waitMs: Long = LockGate.READ_RETRY_MS): Flow<T> = retryWhen { cause, _ ->
    if (cause is CancellationException) return@retryWhen false
    delay(waitMs)
    true
}
