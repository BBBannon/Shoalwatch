package app.shoalwatch.ui.admin

import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Loads while [live] names a session: at once, then after the delay [load] returns (null: wait for [kick]). One load at a time;
 * a change of [live] cancels a load in flight. [load] must not throw (the screen model catches).
 */
class AdminPoller(scope: CoroutineScope, live: Flow<Session?>, private val load: suspend (Session) -> Long?) {
    /** At most one waiting kick: kicks during a load merge into one more load after it. */
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var isLive = false

    init {
        scope.launch {
            live.distinctUntilChanged().collectLatest { session ->
                isLive = session != null
                if (session == null) return@collectLatest
                while (kicks.tryReceive().isSuccess) Unit // nothing asked for while not live is remembered
                while (true) {
                    val wait = load(session)
                    if (wait == null) kicks.receive() else withTimeoutOrNull(wait) { kicks.receive() }
                }
            }
        }
    }

    /** Load now (a refresh, or after an action) and restart the wait. Ignored while not live. */
    fun kick() {
        if (isLive) kicks.trySend(Unit)
    }
}
