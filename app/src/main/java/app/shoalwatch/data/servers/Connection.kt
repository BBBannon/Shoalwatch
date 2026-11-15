package app.shoalwatch.data.servers

import app.shoalwatch.core.AppError
import app.shoalwatch.net.ServerAddress
import kotlinx.coroutines.flow.Flow

/**
 * Which address of a server is in use right now.
 *
 * A held [Online] is not proof of identity: the network may have changed since. Code that sends a credential
 * does not pick addresses itself: `AuthRepository` obtains one with [AddressSelector.awaitOnline] at send time
 * and checks the server's identity on it again immediately before sending anything.
 */
sealed interface Connection {
    data class Online(val address: ServerAddress, val isHome: Boolean) : Connection

    /**
     * Nothing answered as this server. [reason] is set when the cause is a security problem the user must act
     * on (changed or untrusted certificate, cleartext blocked, client certificate needed, damaged saved pin);
     * null for ordinary unreachability or a server that is no longer saved. Never includes an address.
     */
    data class Offline(val reason: AppError.Kind? = null) : Connection

    /**
     * Probing. [previous] is what was Online before the last network change or refresh, kept only so a screen
     * can say "Home network (checking...)" without flicker. It is not usable for sending anything.
     */
    data class Checking(val previous: Online? = null) : Connection
}

/** Where the selector reads a server's CURRENT settings from (`ServerStore` in the app). */
interface ServerSettingsSource {
    /** Every saved server; a new emission whenever any of them changes. */
    val servers: Flow<List<Server>>

    suspend fun server(id: String): Server?
}

