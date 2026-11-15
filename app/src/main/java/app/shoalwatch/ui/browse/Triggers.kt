package app.shoalwatch.ui.browse

import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Emits the active [Session] each time the connection to its server becomes `Online` after having been anything else
 * (`Offline`, or `Checking`, which includes a network change). It does not emit for the first reading, so opening a screen while
 * already online is not a "regain" (collecting a list already refreshes what is due), and it follows the active account: a new
 * account starts watching its own server and forgets the old one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun connectionRegained(catalog: ServerCatalog, connections: ServerConnections): Flow<Session> =
    catalog.activeSession().flatMapLatest { session ->
        if (session == null) {
            emptyFlow()
        } else {
            flow {
                var wasOnline: Boolean? = null
                connections.connection(session.serverId).map { it is Connection.Online }.distinctUntilChanged().collect { online ->
                    if (online && wasOnline == false) emit(session)
                    wasOnline = online
                }
            }
        }
    }
