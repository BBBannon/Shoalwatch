package app.shoalwatch.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/** A connectivity service that refuses to register callbacks (e.g. too many registered). */
@Implements(ConnectivityManager::class)
class RefusingConnectivityManagerShadow {
    @Implementation
    fun registerDefaultNetworkCallback(callback: ConnectivityManager.NetworkCallback) {
        throw SecurityException("refused")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AndroidNetworkMonitorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shadow: ShadowConnectivityManager by lazy {
        shadowOf(context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
    }

    private fun capabilities(wifi: Boolean, validated: Boolean): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also {
            val s = shadowOf(it)
            if (wifi) s.addTransportType(NetworkCapabilities.TRANSPORT_WIFI) else s.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            if (validated) s.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

    @Test fun registersOnFirstCollectionAndUnregistersWhenTheLastCollectorLeaves() = runTest {
        val monitor = AndroidNetworkMonitor(context, backgroundScope)
        assertEquals("nothing registered before anyone collects", 0, shadow.networkCallbacks.size)

        val first = backgroundScope.launch { monitor.changes.collect { } }
        runCurrent()
        assertEquals(1, shadow.networkCallbacks.size)

        val second = backgroundScope.launch { monitor.changes.collect { } }
        runCurrent()
        assertEquals("one registration shared by both collectors", 1, shadow.networkCallbacks.size)

        first.cancel()
        runCurrent()
        assertEquals("still one collector", 1, shadow.networkCallbacks.size)

        second.cancel()
        runCurrent()
        assertEquals(0, shadow.networkCallbacks.size)
    }

    @Test fun emitsOnNetworkEventsButNotOnSignalStrengthOnlyUpdates() = runTest {
        val monitor = AndroidNetworkMonitor(context, backgroundScope)
        var count = 0
        backgroundScope.launch { monitor.changes.collect { count++ } }
        runCurrent()
        val callback = shadow.networkCallbacks.single()
        val network: Network = ShadowNetwork.newInstance(7)

        callback.onAvailable(network)
        runCurrent()
        assertEquals(1, count)

        callback.onCapabilitiesChanged(network, capabilities(wifi = true, validated = true))
        runCurrent()
        assertEquals("first capabilities for this network", 2, count)

        callback.onCapabilitiesChanged(network, capabilities(wifi = true, validated = true))
        runCurrent()
        assertEquals("same transport and validation: ignored", 2, count)

        callback.onCapabilitiesChanged(network, capabilities(wifi = false, validated = true))
        runCurrent()
        assertEquals("Wi-Fi to mobile data", 3, count)

        callback.onLost(network)
        runCurrent()
        assertEquals(4, count)
    }

    @Test @Config(shadows = [RefusingConnectivityManagerShadow::class])
    fun aRefusedRegistrationDoesNotCrashAndCollectionJustStaysQuiet() = runTest {
        val monitor = AndroidNetworkMonitor(context, backgroundScope)
        var count = 0
        val job = backgroundScope.launch { monitor.changes.collect { count++ } }
        runCurrent()
        assertEquals(0, count)
        job.cancel()
        runCurrent() // unregistering a callback that never registered must not throw either
    }
}
