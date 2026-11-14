package app.offlinefin.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.channels.Channel

/** Says when the phone's default network may have changed (Wi-Fi to mobile data, a new Wi-Fi, lost, regained). */
interface NetworkMonitor {
    /** Emits once per burst of changes. Collecting it is what registers interest; there is no value to read. */
    val changes: Flow<Unit>
}

/**
 * [NetworkMonitor] over `ConnectivityManager.registerDefaultNetworkCallback`. The callback is registered when
 * the first collector arrives and removed when the last one leaves. Bursts are collapsed (debounced), so a
 * flapping signal causes one emission. Registering also reports the current network once, which just causes
 * one early re-check.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class AndroidNetworkMonitor(
    context: Context,
    scope: CoroutineScope,
    debounceMs: Long = DEBOUNCE_MS,
) : NetworkMonitor {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override val changes: Flow<Unit> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(Unit) }
            override fun onLost(network: Network) { trySend(Unit) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(Unit) }
        }
        val registered = try {
            manager.registerDefaultNetworkCallback(callback)
            true
        } catch (_: RuntimeException) {
            false // No monitoring is better than a crash: callers still re-check on failure and on refresh.
        }
        awaitClose { if (registered) manager.unregisterNetworkCallback(callback) }
    }.buffer(Channel.CONFLATED)
        .debounce(debounceMs)
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)

    private companion object {
        const val DEBOUNCE_MS = 500L
    }
}
