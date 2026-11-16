package app.shoalwatch.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn

/** Says when the phone's default network may have changed (Wi-Fi to mobile data, a new Wi-Fi, lost, regained). */
interface NetworkMonitor {
    /**
     * Emits as soon as the default network may have changed (conflated, not debounced: the consumer needs to
     * know at once that anything it learned about the network is stale, and debounces its own work).
     * Collecting it is what registers interest; there is no value to read.
     */
    val changes: Flow<Unit>
}

/**
 * [NetworkMonitor] over `ConnectivityManager.registerDefaultNetworkCallback`. The callback is registered when
 * the first collector arrives and removed when the last one leaves. Emissions are conflated. Capability
 * updates that only change signal strength are ignored (they arrive constantly); a different network, link
 * type or validated state counts. Registering also reports the current network once, which just causes one
 * early re-check.
 */
class AndroidNetworkMonitor(
    context: Context,
    scope: CoroutineScope,
) : NetworkMonitor {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override val changes: Flow<Unit> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var lastSignature: String? = null

            override fun onAvailable(network: Network) { trySend(Unit) }
            override fun onLost(network: Network) {
                lastSignature = null
                trySend(Unit)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val signature = signatureOf(network, capabilities)
                if (signature != lastSignature) {
                    lastSignature = signature
                    trySend(Unit)
                }
            }
        }
        val registered = try {
            manager.registerDefaultNetworkCallback(callback)
            true
        } catch (_: RuntimeException) {
            false // No monitoring is better than a crash: callers still re-check on failure and on refresh.
        }
        awaitClose { if (registered) manager.unregisterNetworkCallback(callback) }
    }.buffer(Channel.CONFLATED)
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)

    private fun signatureOf(network: Network, caps: NetworkCapabilities): String = buildString {
        append(network.toString())
        for (transport in TRANSPORTS) append(if (caps.hasTransport(transport)) '1' else '0')
        append(if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) '1' else '0')
    }

    private companion object {
        val TRANSPORTS = intArrayOf(
            NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_VPN,
        )
    }
}
