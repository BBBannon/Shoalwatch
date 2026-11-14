package app.offlinefin

import android.content.Context
import app.offlinefin.data.db.AppDatabase
import app.offlinefin.data.servers.AddressSelector
import app.offlinefin.data.servers.AuthRepository
import app.offlinefin.data.servers.Authenticator
import app.offlinefin.data.servers.AuthenticatorImpl
import app.offlinefin.data.servers.ServerCatalog
import app.offlinefin.data.servers.StoreCatalog
import app.offlinefin.data.servers.CertificateProber
import app.offlinefin.data.servers.DeviceIdentity
import app.offlinefin.data.servers.JellyfinGateway
import app.offlinefin.data.servers.JellyfinGatewayImpl
import app.offlinefin.data.servers.ServerClients
import app.offlinefin.data.servers.ServerStore
import app.offlinefin.net.AndroidNetworkMonitor
import app.offlinefin.net.HttpClientFactory
import app.offlinefin.net.NetworkMonitor
import app.offlinefin.security.KeystoreKeyProvider
import app.offlinefin.security.SecretBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Holds the app's lazy singletons (no DI framework). Later tasks add the rest of the data layer here. */
class AppGraph(private val appContext: Context) {
    /** Lives as long as the process; for work that must outlive any screen (monitoring, address probing). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database: AppDatabase by lazy { AppDatabase.open(appContext) }
    val secretBox: SecretBox by lazy { SecretBox(KeystoreKeyProvider()) }
    val serverClients: ServerClients by lazy { ServerClients(httpClientFactory) }
    val serverStore: ServerStore by lazy { ServerStore(database, secretBox, serverClients::evict, onServerRemoved = { addressSelector.forget(it) }) }
    val httpClientFactory: HttpClientFactory by lazy { HttpClientFactory(appContext) }
    val deviceIdentity: DeviceIdentity by lazy { DeviceIdentity.load(appContext) }
    val jellyfinGateway: JellyfinGateway by lazy { JellyfinGatewayImpl(serverClients) }
    val networkMonitor: NetworkMonitor by lazy { AndroidNetworkMonitor(appContext, appScope) }
    val addressSelector: AddressSelector by lazy { AddressSelector(jellyfinGateway, networkMonitor, appScope, deviceIdentity) }
    /** Loads saved tokens into the store's live lookup. Started at app start; anything authenticated waits on `serverStore.awaitWarmed()` (or uses `awaitActive()`). */
    val tokensWarmed: Job by lazy { appScope.launch { serverStore.warmTokens() } }
    /** What the trust prompt reads; the UI never touches `HttpClientFactory` itself. */
    val certificateProber: CertificateProber by lazy { CertificateProber(httpClientFactory::probeCertificate) }
    val authRepository: AuthRepository by lazy { AuthRepository(jellyfinGateway, serverStore, deviceIdentity) }

    /** What ViewModels use: the same stores without access tokens. */
    val serverCatalog: ServerCatalog by lazy { StoreCatalog(serverStore) }
    val authenticator: Authenticator by lazy { AuthenticatorImpl(authRepository) }
}
