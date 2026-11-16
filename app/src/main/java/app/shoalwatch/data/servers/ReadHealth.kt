package app.shoalwatch.data.servers

import app.shoalwatch.core.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

/**
 * Makes the store's observed reads safe for their collectors. Room throws a failed query (a damaged or full database) into
 * whoever collects; in a ViewModel or a composition that ends the app. A read wrapped by [guard] instead logs the failure (by
 * class only, see [AppLog]), sets [failed], and emits its fail-closed `empty` value: nobody active, an empty list, no server.
 * Never the value from before the failure, so nothing is shown for a session the store can no longer vouch for.
 *
 * [failed] is set BEFORE the empty value is emitted, so a collector can tell "nobody, because the read failed" from a real
 * sign-out. A failed read then waits for [retry] (the start-up and Home "Try again", through `retryWarmIfFailed`) and
 * subscribes again; a read that has not failed is never restarted. One-shot reads (`first()`) subscribe afresh anyway.
 */
internal class ReadHealth(private val tag: String) {
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed
    private val retries = MutableStateFlow(0)

    fun <T> guard(what: String, empty: T, source: () -> Flow<T>): Flow<T> = flow {
        while (true) {
            var failedAt: Int? = null
            emitAll(
                // Built inside the guarded flow: Room throws while BUILDING a flow on a closed database, before a catch could see it.
                flow { emitAll(source()) }.catch { e ->
                    if (e !is Exception || e is CancellationException) throw e
                    failedAt = retries.value
                    AppLog.w(tag, "Reading $what failed", e)
                    _failed.value = true
                    emit(empty)
                },
            )
            val seen = failedAt ?: return@flow
            retries.first { it != seen }
        }
    }

    /** Subscribes every failed read again. True if there was one. */
    fun retry(): Boolean {
        if (!_failed.value) return false
        _failed.value = false
        retries.update { it + 1 }
        return true
    }
}
