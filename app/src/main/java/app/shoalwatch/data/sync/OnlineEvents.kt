package app.shoalwatch.data.sync

import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.ServerConnections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

/**
 * "This server just came online", for the servers in [servers] (those with outbox rows). Only the servers in the set are
 * watched; one that leaves it is no longer watched. Nothing runs until something collects [cameOnline].
 */
internal class OnlineEvents(private val connections: ServerConnections, private val servers: Flow<Set<String>>, private val scope: CoroutineScope) {
    /** One [cameOnline], with [afterOffline]: the server was seen Offline since it was last Online (a real reconnection, not a re-probe). */
    data class Reconnection(val serverId: String, val afterOffline: Boolean)

    /**
     * Each time a server's connection becomes Online after being anything else (Checking, Offline, or a different Online).
     * The first reading of a newly watched server never emits: whoever added it (a change, or the start-up kick) already sends.
     */
    val reconnections: SharedFlow<Reconnection> = channelFlow {
        val watching = HashMap<String, Job>()
        servers.collect { ids ->
            (watching.keys - ids).forEach { watching.remove(it)?.cancel() }
            (ids - watching.keys).forEach { id -> watching[id] = launch { watch(id) } }
        }
    }.shareIn(scope, SharingStarted.WhileSubscribed())

    /** [reconnections] as server ids. */
    val cameOnline: Flow<String> = reconnections.map { it.serverId }

    private suspend fun ProducerScope<Reconnection>.watch(serverId: String) {
        var last: Connection? = null
        var offline = false
        connections.connection(serverId).collect { now ->
            val before = last
            last = now
            if (now is Connection.Offline) offline = true
            if (now is Connection.Online && before != null && now != before) send(Reconnection(serverId, afterOffline = offline))
            if (now is Connection.Online) offline = false
        }
    }
}
