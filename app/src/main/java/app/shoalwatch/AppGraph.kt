package app.shoalwatch

import android.content.Context
import app.shoalwatch.data.api.ServerApi
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.library.LibraryRepository
import app.shoalwatch.data.library.Playstate
import app.shoalwatch.data.library.PlaystateRepository
import app.shoalwatch.data.library.PrefsMaintenanceLog
import app.shoalwatch.data.library.api.LibraryGateway
import app.shoalwatch.data.library.api.LibraryGatewayImpl
import app.shoalwatch.data.servers.AddressSelector
import app.shoalwatch.data.servers.AuthRepository
import app.shoalwatch.data.servers.Authenticator
import app.shoalwatch.data.servers.AuthenticatorImpl
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.StoreCatalog
import app.shoalwatch.data.servers.CertificateProber
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.JellyfinGateway
import app.shoalwatch.data.servers.JellyfinGatewayImpl
import app.shoalwatch.data.servers.ServerClients
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.net.AndroidNetworkMonitor
import app.shoalwatch.net.HttpClientFactory
import app.shoalwatch.net.NetworkMonitor
import app.shoalwatch.security.KeystoreKeyProvider
import app.shoalwatch.security.SecretBox
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
    val serverStore: ServerStore by lazy { ServerStore(
        database, secretBox, serverClients::evict,
        onServerRemoved = { addressSelector.forget(it); libraryRepository.forget(it) },
        onAccountGone = { server, user -> libraryRepository.forget(server, user) },
    ) }
    val httpClientFactory: HttpClientFactory by lazy { HttpClientFactory(appContext) }
    val deviceIdentity: DeviceIdentity by lazy { DeviceIdentity.load(appContext) }
    val jellyfinGateway: JellyfinGateway by lazy { JellyfinGatewayImpl(serverClients) }
    val networkMonitor: NetworkMonitor by lazy { AndroidNetworkMonitor(appContext, appScope) }
    val addressSelector: AddressSelector by lazy { AddressSelector(jellyfinGateway, networkMonitor, appScope, deviceIdentity, serverStore) }
    /** The one path for every signed-in request; nothing in the UI uses it yet. */
    val serverApi: ServerApi by lazy { ServerApi(serverStore, addressSelector, serverClients, deviceIdentity) }
    /** Library endpoints as safe models; only `library` uses it. */
    val libraryGateway: LibraryGateway by lazy { LibraryGatewayImpl(serverApi) }
    /** What every browsing screen reads: saved rows first, refreshed behind them (see "Library repository" in docs/ARCHITECTURE.md). */
    val libraryRepository: LibraryRepository by lazy { LibraryRepository(libraryGateway, database, appScope, Dispatchers.IO, System::currentTimeMillis, PrefsMaintenanceLog(appContext)) }
    val library: Library get() = libraryRepository
    /** The tidy-up actions (remove from Continue Watching, mark watched, favourite): Room first, then the server, rolled back on failure (see "Tidy-up actions" in docs/ARCHITECTURE.md). */
    val playstate: Playstate by lazy { PlaystateRepository(libraryGateway, database, libraryRepository, appScope, Dispatchers.IO, System::currentTimeMillis) }
    /** Loads saved tokens into the store's live lookup. Started at app start; anything authenticated waits on `serverStore.awaitWarmed()` (or uses `awaitActive()`). */
    val tokensWarmed: Job by lazy { appScope.launch { serverStore.warmTokens() } }
    /** What the trust prompt reads; the UI never touches `HttpClientFactory` itself. */
    val certificateProber: CertificateProber by lazy { CertificateProber(httpClientFactory::probeCertificate) }
    val authRepository: AuthRepository by lazy { AuthRepository(jellyfinGateway, serverStore, deviceIdentity, addressSelector) }

    /** What ViewModels use: the same stores without access tokens. */
    val serverCatalog: ServerCatalog by lazy { StoreCatalog(serverStore) }
    val authenticator: Authenticator by lazy { AuthenticatorImpl(authRepository) }
}
